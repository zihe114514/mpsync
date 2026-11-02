package yun.shan.mpsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.command.MpTextCommands;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.IdlePlaylist;
import yun.shan.mpsync.room.QueueEntry;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.support.FakePlatform;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 频道空闲歌单。
 *
 * <p>核心承诺：没人点歌时频道自动放这张单；有人点歌就排在它前面、但不打断正在放的那首空闲歌；
 * 只对频道生效；没人在线不补歌；解析不出来的歌冷却、连续失败整体暂停；歌单落盘、重启后还在。</p>
 */
class ChannelIdlePlaylistTest {

    private static final String URL = "https://m8c.music.126.net/full.mp3";

    private Path dir;
    private FakePlatform host;
    private RoomManager manager;
    private MpConfig cfg;
    private UUID admin;
    private UUID a;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        this.dir = dir;
        host = new FakePlatform();
        admin = host.addPlayer("Admin");
        a = host.addPlayer("A");
        MpParty.init(host, dir.resolve("mpsync.json"));
        manager = MpParty.manager();
        cfg = MpParty.config();
        manager.noteOperator(admin, true);
    }

    private static JsonObject trackJson(String id, long durationMs) {
        JsonObject o = new JsonObject();
        o.addProperty("source", "netease");
        o.addProperty("songId", id);
        o.addProperty("name", "歌" + id);
        o.addProperty("artist", "歌手");
        o.addProperty("duration", durationMs);
        return o;
    }

    private Track playable(String id) {
        Track t = new Track("netease", id);
        t.name = "点" + id;
        t.durationMs = 200_000L;
        t.adoptUrl(URL, System.currentTimeMillis() + 600_000L, 200_000L, cfg);
        return t;
    }

    /** 管理员把 n 首灌进频道，存成空闲歌单并打开；然后清空频道，回到「没人点歌」的状态。 */
    private Room channelWithIdle(int n) {
        Room ch = manager.joinChannel(admin);
        JsonArray arr = new JsonArray();
        for (int i = 1; i <= n; i++) arr.add(trackJson("10" + i, 180_000L));
        ch.enqueueBatch(admin, "Admin", "夜间歌单", arr);
        assertEquals(n, manager.saveIdleFrom(admin, "夜间", false));
        manager.setIdleEnabled(true);
        ch.stop();
        ch.clearQueue();
        return ch;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void tick(Room room, int n) {
        for (int i = 0; i < n; i++) room.tick();
    }

    /** 让当前那首空闲歌拿到链接、开始播。 */
    private static void resolveCurrent(Room ch) {
        QueueEntry cur = ch.current();
        ch.supplyUrl(cur.track.source, cur.track.songId, URL, System.currentTimeMillis() + 600_000L, 180_000L);
    }

    @Test
    void anIdleChannelStartsPlayingTheIdlePlaylist() {
        Room ch = channelWithIdle(3);
        assertNull(ch.current());
        tick(ch, 10);
        QueueEntry cur = ch.current();
        assertNotNull(cur, "频道没人点歌时应自动起播空闲歌单");
        assertEquals(QueueEntry.TIER_IDLE, cur.tier);
        assertEquals(cfg.idleRequesterName, cur.requesterName);
        assertEquals("夜间", cur.playlist, "客户端靠 playlist 标出这是服务器歌单");
        assertNull(cur.requester, "空闲歌没有点歌人");
        assertEquals(cfg.idleQueueLead, ch.queue().size(), "提前备好下一首，前瞻解析与交叠才有对象");
    }

    @Test
    void nothingIsQueuedWhenNobodyIsOnline() {
        Room ch = channelWithIdle(3);
        manager.onPlayerQuit(admin);
        tick(ch, 20);
        assertNull(ch.current(), "没人在线就不补歌，免得白烧别人的解析额度");
        assertTrue(ch.queue().isEmpty());
    }

    @Test
    void aRequestQueuesAheadOfIdleSongsWithoutInterruptingTheOneThatIsPlaying() {
        Room ch = channelWithIdle(3);
        tick(ch, 10);
        resolveCurrent(ch);
        QueueEntry idleNow = ch.current();
        manager.joinChannel(a);
        ch.enqueue(a, "A", playable("900"), false);
        assertSame(idleNow, ch.current(), "正在放的空闲歌不被打断，放完再轮到点的歌");
        assertEquals("900", ch.queue().get(0).track.songId, "点的歌排在所有空闲歌前面");
        ch.next(true);
        assertEquals("900", ch.current().track.songId);
    }

    @Test
    void requestingASongThatIsQueuedAsIdlePromotesItInsteadOfRejecting() {
        Room ch = channelWithIdle(3);
        tick(ch, 10);
        QueueEntry queuedIdle = ch.queue().get(0);
        Track same = playable(queuedIdle.track.songId);
        manager.joinChannel(a);
        assertDoesNotThrow(() -> ch.enqueue(a, "A", same, false),
                "服务器自己补的歌不该挡住玩家点同一首");
        long copies = ch.queue().stream().filter(e -> e.track.key().equals(same.key())).count();
        assertEquals(1, copies);
        assertEquals(QueueEntry.TIER_REQUEST, ch.queue().get(0).tier);
    }

    @Test
    void loadingAPlaylistDropsPendingIdleSongs() {
        Room ch = channelWithIdle(3);
        tick(ch, 10);
        JsonArray arr = new JsonArray();
        arr.add(trackJson("700", 180_000L));
        ch.enqueueBatch(admin, "Admin", "新单", arr);
        assertTrue(ch.queue().stream().noneMatch(e -> e.tier == QueueEntry.TIER_IDLE),
                "灌了新单，排在队尾的空闲歌就该让位");
        assertEquals(QueueEntry.TIER_IDLE, ch.current().tier, "正在放的那首不动");
    }

    @Test
    void turningItOffDropsPendingIdleButLetsTheCurrentSongFinish() {
        Room ch = channelWithIdle(3);
        tick(ch, 10);
        resolveCurrent(ch);
        QueueEntry cur = ch.current();
        manager.setIdleEnabled(false);
        assertSame(cur, ch.current());
        assertTrue(ch.queue().isEmpty());
        ch.next(true);
        assertNull(ch.current(), "关掉之后放完这首就停");
        tick(ch, 20);
        assertNull(ch.current());
    }

    @Test
    void privateRoomsNeverGetIdleSongs() {
        channelWithIdle(3);
        Room mine = manager.create(a, "A 的房间");
        tick(mine, 20);
        assertNull(mine.current(), "空闲歌单只属于频道");
        assertTrue(mine.queue().isEmpty());
    }

    @Test
    void unresolvableSongsCoolDownAndRepeatedFailuresPauseThePlaylist() {
        cfg.idleMaxConsecutiveFails = 2;
        IdlePlaylist idle = new IdlePlaylist(cfg);
        idle.replace("t", List.of(playable("1"), playable("2"), playable("3")));
        idle.setEnabled(true);
        Track first = idle.next(Set.of());
        idle.failed(first.key());
        assertTrue(idle.active(), "失败一首还不至于停");
        for (int i = 0; i < 5; i++) {
            Track t = idle.next(Set.of());
            assertNotNull(t);
            assertFalse(t.key().equals(first.key()), "失败过的歌在冷却期内不再被选中");
        }
        idle.failed("netease:2");
        assertFalse(idle.active(), "连续失败到上限就整体暂停，免得空转刷跳歌");
        assertTrue(idle.pausedRemainingMs() > 0);
        idle.setEnabled(true);
        assertTrue(idle.active(), "管理员重新打开 = 给它一次干净的机会");
    }

    @Test
    void anIdleSongThatNobodyCanResolveIsSkippedQuietly() {
        Room ch = channelWithIdle(3);
        tick(ch, 10);
        String first = ch.current().track.key();
        host.clear();
        // 模拟等链超时：总闸调成 0，再等几毫秒（同一毫秒内 now - waitingSince > 0 不成立）。
        cfg.urlWaitTimeoutMs = 0L;
        sleepQuietly(5);
        tick(ch, 1);
        assertFalse(first.equals(ch.current() == null ? "" : ch.current().track.key()), "超时应跳到下一首");
        assertTrue(host.chat.stream().noneMatch(s -> s.contains("已跳过")),
                "空闲歌跳过只打日志，不向全频道刷屏：" + host.chat);
    }

    @Test
    void savedPlaylistSurvivesARestart() {
        channelWithIdle(3);
        MpParty.init(host, dir.resolve("mpsync.json"));
        IdlePlaylist idle = MpParty.manager().idle();
        assertEquals(3, idle.size());
        assertEquals("夜间", idle.name());
        assertTrue(idle.enabled());
    }

    @Test
    void tracksWithoutDurationAreNeverStored() {
        IdlePlaylist idle = new IdlePlaylist(cfg);
        Track noLength = new Track("netease", "55");
        noLength.name = "没时长";
        assertEquals(1, idle.replace("t", List.of(noLength, playable("56"))),
                "没有时长的歌会让房间卡在那一首上，不能进空闲歌单");
    }

    @Test
    void idleCommandsAreAdminOnlyAndReportStatus() {
        channelWithIdle(2);
        List<String> denied = MpTextCommands.run(a, false, new String[]{"idle", "off"});
        assertTrue(manager.idle().enabled(), "非管理员不能改：" + denied);
        List<String> info = MpTextCommands.run(admin, true, new String[]{"idle"});
        assertTrue(info.get(0).contains("开启") && info.get(0).contains("2 首"), info.toString());
        MpTextCommands.run(admin, true, new String[]{"idle", "off"});
        assertFalse(manager.idle().enabled());
        List<String> empty = MpTextCommands.run(admin, true, new String[]{"idle", "save"});
        assertTrue(empty.get(0).contains("没有玩家点的歌"), "频道里没歌时要告诉管理员先灌单：" + empty);
    }

    /**
     * 真实的管理员流程，全程走协议与指令：频道不许灌整张歌单（客户端在频道里连按钮都没有），
     * 所以管理员要在自己的房间里灌好再存。这条路曾经漏测 —— 上面的辅助方法直接调了
     * {@code Room#enqueueBatch}，绕过了频道那道拦截。
     */
    @Test
    void adminBuildsTheIdlePlaylistInAPrivateRoomBecauseTheChannelRefusesWholePlaylists() {
        manager.joinChannel(admin);
        JsonObject batch = Proto.msg(Proto.C_ENQUEUE_BATCH);
        batch.addProperty("playlist", "我的歌单");
        JsonArray tracks = new JsonArray();
        for (int i = 1; i <= 5; i++) tracks.add(trackJson("50" + i, 200_000L));
        batch.add("tracks", tracks);

        MpParty.onPacket(admin, Proto.encode(batch));
        assertTrue(manager.channel().queue().size() <= 1 && manager.channel().current() == null,
                "频道照旧拒绝整张歌单（上游规则不变）");

        MpTextCommands.run(admin, true, new String[]{"leave"});
        MpTextCommands.run(admin, true, new String[]{"create", "歌单工作间"});
        Room work = manager.roomOf(admin);
        assertFalse(work.channel());
        MpParty.onPacket(admin, Proto.encode(batch));
        assertEquals(4, work.queue().size(), "私人房间里整张单能灌进去（第一首起播，其余排队）");

        List<String> out = MpTextCommands.run(admin, true, new String[]{"idle", "save", "工作间单"});
        assertEquals(5, manager.idle().size(), out.toString());
        assertTrue(out.get(0).contains("歌单工作间"), "提示要说明是从哪个房间存的：" + out);
        assertTrue(out.stream().anyMatch(s -> s.contains("/mp disband")), "提示下一步可以解散工作间：" + out);

        MpTextCommands.run(admin, true, new String[]{"disband"});
        MpTextCommands.run(admin, true, new String[]{"channel"});
        MpTextCommands.run(admin, true, new String[]{"idle", "on"});
        Room ch = manager.channel();
        tick(ch, 10);
        assertNotNull(ch.current(), "存好之后频道开始放空闲歌单");
        assertEquals("工作间单", ch.current().playlist);
    }
}
