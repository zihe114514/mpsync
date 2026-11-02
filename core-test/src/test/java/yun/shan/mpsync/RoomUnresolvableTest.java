package yun.shan.mpsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.presence.PresenceRegistry;
import yun.shan.mpsync.room.QueueEntry;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.support.FakePlatform;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「房间里没人能放」的歌。
 *
 * <p>现场的症状：队首两首都放不了时，聊天栏每 30 秒交替刷「问过了 1 台机器，都没能替《X》找到…」。
 * 根因是前瞻窗每 30 秒重新要一次链接，每次都把分层与换源整套重走、每次都向全房广播。这组用例钉住：
 * 每首最多说一次；成员没变就不再去问；空闲歌静默跳过；歌单里的歌移出；玩家点的歌保留；
 * 当前曲目不再空等总闸；服主 Token 那一路还可能答上时不急着下结论。</p>
 */
class RoomUnresolvableTest {

    private static final String URL = "https://m8c.music.126.net/full.mp3";

    private FakePlatform host;
    private MpConfig cfg;
    private Room room;
    private UUID owner;

    @BeforeEach
    void setUp() {
        host = new FakePlatform();
        Net.bind(host);
        cfg = new MpConfig();
        cfg.urlTierTimeoutMs = 1L;          // 分层一层一层秒过，没人有换源能力 → 很快判定放不了
        owner = host.addPlayer("房主");
        room = new Room(cfg, "r1", "ABCDE", owner, "房主", "测试房");
        room.setPresence(new PresenceRegistry());
    }

    private Track playable(String id) {
        Track t = new Track("netease", id);
        t.name = "能放" + id;
        t.durationMs = 240_000L;
        t.adoptUrl(URL, System.currentTimeMillis() + 600_000L, 240_000L, cfg);
        return t;
    }

    private static Track urlless(String id) {
        Track t = new Track("netease", id);
        t.name = "放不了" + id;
        t.durationMs = 240_000L;
        return t;
    }

    private static JsonObject meta(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("source", "netease");
        o.addProperty("songId", id);
        o.addProperty("name", "放不了" + id);
        o.addProperty("duration", 240_000L);
        return o;
    }

    private static void tick(Room r, int n) {
        for (int i = 0; i < n; i++) {
            try { Thread.sleep(3L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            r.tick();
        }
    }

    private long chatContaining(String needle) {
        return host.chat.stream().filter(s -> s.contains(needle)).count();
    }

    /** 绕过前瞻窗 30 秒的节流，模拟「过了 30 秒，又该重新问一次了」。 */
    @SuppressWarnings("unchecked")
    private static void forgetLookaheadCooldown(Room r) throws Exception {
        Field f = Room.class.getDeclaredField("resolveAskedAt");
        f.setAccessible(true);
        ((Map<String, Long>) f.get(r)).clear();
    }

    private long urlAsksFor(String songId) {
        return host.sent.stream().filter(s -> Proto.S_URL_NEEDED.equals(s.op())
                && songId.equals(Proto.str(s.frame(), "songId", ""))).count();
    }

    @Test
    void aPlaylistSongNobodyCanPlayIsTakenOutOfTheQueueWithASingleNotice() {
        room.enqueue(owner, "房主", playable("1"), false);          // 正在放，队里的歌走前瞻窗
        JsonArray arr = new JsonArray();
        arr.add(meta("11"));
        arr.add(meta("12"));
        room.enqueueBatch(owner, "房主", "灰色歌单", arr);
        host.clear();

        tick(room, 40);
        assertTrue(room.queue().isEmpty(), "放不了的歌单歌要移出队列，别等轮到它再空等总闸");
        assertEquals(1, chatContaining("放不了11"), "每首只说一次：" + host.chat);
        assertEquals(1, chatContaining("放不了12"), "每首只说一次：" + host.chat);
        assertEquals(0, chatContaining("问过了"), "歌单歌直接说移出，不再先报一句「问过了」：" + host.chat);
        assertEquals("1", room.current().track.songId, "正在放的歌不受影响");
    }

    @Test
    void aRequestedSongIsKeptAnnouncedOnceAndNotAskedAgainUntilTheRosterChanges() throws Exception {
        room.enqueue(owner, "房主", playable("1"), false);
        room.enqueue(owner, "房主", urlless("21"), false);         // 玩家点的、谁都放不了
        tick(room, 30);
        assertEquals(1, room.queue().size(), "玩家点的歌留着：点歌人还能自己换个平台重点");
        assertEquals(1, chatContaining("放不了21"), "说一次就够：" + host.chat);

        host.clear();
        forgetLookaheadCooldown(room);
        tick(room, 30);
        assertEquals(0, urlAsksFor("21"), "成员没变：同一批人已经证明放不了，别再每 30 秒重走一遍");
        assertEquals(0, host.chat.size(), "也不许再刷任何提示：" + host.chat);

        UUID newcomer = host.addPlayer("新来的");
        room.join(newcomer, "新来的");                              // 来了一台可能解得出的机器
        forgetLookaheadCooldown(room);
        tick(room, 12);
        assertTrue(urlAsksFor("21") > 0, "成员变了，之前的判定作废，要重新去问");
        assertTrue(host.sent.stream().anyMatch(s -> Proto.S_URL_NEEDED.equals(s.op()) && s.to().equals(newcomer)),
                "新来的人要被问到");
    }

    @Test
    void theCurrentSongIsSkippedAsSoonAsEveryoneHasFailedInsteadOfWaitingForTheTimeout() {
        cfg.urlWaitTimeoutMs = 600_000L;                            // 总闸长到测试里不可能到点
        room.enqueue(owner, "房主", urlless("31"), false);         // 空房点歌：立刻成为当前曲目、等链接
        room.enqueue(owner, "房主", playable("32"), false);
        tick(room, 40);
        assertEquals("32", room.current().track.songId, "问完一圈、换源也没人接，就别再空等总闸");
        assertTrue(room.isPlaying());
        assertEquals(1, chatContaining("已跳过"), host.chat.toString());
    }

    @Test
    void whileAServerTokenMightStillAnswerNothingIsDroppedYet() {
        cfg.serverTokens.put("netease", "MUSIC_U=test");
        cfg.serverResolveTimeoutMs = 600_000L;
        room.enqueue(owner, "房主", playable("1"), false);
        JsonArray arr = new JsonArray();
        arr.add(meta("41"));
        room.enqueueBatch(owner, "房主", "单子", arr);
        tick(room, 40);
        assertEquals(1, room.queue().size(), "服主 Token 是异步的，可能比「问完一圈」来得晚：等满它的超时再下结论");
    }

    @Test
    void idleSongsNobodyCanPlayAreSkippedSilentlyAndReplaced(@TempDir Path dir) {
        FakePlatform h = new FakePlatform();
        UUID admin = h.addPlayer("Admin");
        MpParty.init(h, dir.resolve("mpsync.json"));
        RoomManager manager = MpParty.manager();
        MpConfig c = MpParty.config();
        c.urlTierTimeoutMs = 1L;
        c.idleMaxConsecutiveFails = 100;    // 只测「跳过并补歌」，不让连续失败暂停整张单
        manager.noteOperator(admin, true);

        Room ch = manager.joinChannel(admin);
        JsonArray arr = new JsonArray();
        for (int i = 10; i < 22; i++) arr.add(meta("5" + i));
        ch.enqueueBatch(admin, "Admin", "电台", arr);
        manager.saveIdleFrom(admin, "电台", false);
        manager.setIdleEnabled(true);
        ch.stop();
        ch.clearQueue();

        tick(ch, 10);                                               // 起播一首空闲歌，并备好后面两首
        QueueEntry cur = ch.current();
        ch.supplyUrl(cur.track.source, cur.track.songId, URL, System.currentTimeMillis() + 600_000L, 240_000L);
        String firstQueued = ch.queue().get(0).track.key();
        h.clear();

        tick(ch, 30);
        assertTrue(ch.queue().stream().noneMatch(e -> e.track.key().equals(firstQueued)),
                "放不了的空闲歌要被跳过");
        assertTrue(ch.queue().stream().allMatch(e -> e.tier == QueueEntry.TIER_IDLE));
        assertFalse(ch.queue().isEmpty(), "跳过之后要补上下一首空闲歌");
        assertTrue(h.chat.isEmpty(), "空闲歌全程静默，频道里可能有上百人：" + h.chat);
        assertNotEquals(firstQueued, ch.queue().get(0).track.key());
    }
}
