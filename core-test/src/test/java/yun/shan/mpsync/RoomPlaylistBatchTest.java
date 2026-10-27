package yun.shan.mpsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.QueueEntry;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.support.FakePlatform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「播放歌单」：整张单子灌进房间队列时的<b>排位</b>、去重、上限，以及它<b>不许</b>做的事。
 *
 * <p>需求里那三档优先级（房主/管理员插队 &gt; 别人点的排队歌 &gt; 歌单待播）是这一组测试的主体。
 * 它们全是「排序塌了也不报错、只是房间里的顺序变得让人看不懂」那一类，所以只能靠断言守着；
 * 而 {@code Room#insertAt} 把三档压成一个 {@code <=} 比较之后，任何一次「顺手改回 add(0) /
 * add末尾」都会让下面好几条同时红 —— 这正是想要的效果。</p>
 *
 * <p>另一条被反复钉住的是<b>「已经播放的不算」</b>：灌单不许碰播放头。它和插队刚好相反 ——
 * 插队是管理者的明确命令，可以打断；有人丢一张 200 首的单子进房间，绝不能把全房正在听的这首切掉。</p>
 *
 * <p>最后一条是这个功能真正的工程风险：<b>前瞻窗</b>。逐首走 {@code enqueue} 会让一张长单子
 * 当场向全房要一百条播放链接（每台客户端各自去真取流），而其中绝大部分要等几十分钟才轮到、
 * 链接那时早已过期。这里用「收到帧的曲目集合」而不是帧条数来断言 —— 广播是逐收件人发的，
 * 数条数会跟着成员数变，这是这个仓库踩过的坑。</p>
 */
class RoomPlaylistBatchTest {

    private static final String URL = "https://m8c.music.126.net/full.mp3";
    private static final long DURATION = 240_000L;

    private FakePlatform host;
    private MpConfig cfg;
    private RoomManager manager;
    private Room room;
    private UUID owner;
    private UUID member;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        host = new FakePlatform();
        owner = host.addPlayer("房主");
        member = host.addPlayer("成员");
        MpParty.init(host, dir.resolve("mpsync.json"));
        manager = MpParty.manager();
        cfg = MpParty.config();
        room = manager.create(owner, "测试房");
        manager.join(member, room.code);
        Net.markCapable(owner);
    }

    /** 有可用直链的曲目：走 {@link Room#enqueue} 那一首的默认路径。 */
    private Track playable(String songId) {
        Track t = new Track("netease", songId);
        t.name = "曲目" + songId;
        t.durationMs = DURATION;
        t.adoptUrl(URL, System.currentTimeMillis() + 600_000L, DURATION, cfg);
        return t;
    }

    /** 歌单帧里的一条：<b>只有元数据</b>，这正是灌单与点歌的区别 —— 链接交给前瞻窗按需问。 */
    private JsonObject meta(String songId) {
        JsonObject o = new JsonObject();
        o.addProperty("source", "netease");
        o.addProperty("songId", songId);
        o.addProperty("name", "曲目" + songId);
        o.addProperty("duration", DURATION);
        return o;
    }

    private JsonArray metas(String... songIds) {
        JsonArray a = new JsonArray();
        for (String id : songIds) a.add(meta(id));
        return a;
    }

    private Room.BatchResult batch(String listName, String... songIds) {
        return room.enqueueBatch(owner, "房主", listName, metas(songIds));
    }

    /** 队列里现在的曲目 id，按将要播放的顺序。 */
    private List<String> queued() {
        List<String> ids = new ArrayList<>();
        for (QueueEntry e : room.queue()) ids.add(e.track.songId);
        return ids;
    }

    // ══════════════════════════ 三档排位 ══════════════════════════

    @Test
    void playlistSitsBehindEveryQueuedPick() {
        room.enqueue(owner, "房主", playable("start"), false);   // 房间空着：当场起播，不占队列
        room.enqueue(member, "成员", playable("q1"), false);
        batch("深夜电台", "p1", "p2", "p3");

        assertEquals(List.of("q1", "p1", "p2", "p3"), queued(),
                "歌单歌排在已经排着的点歌之后 —— 别人的排队不能被一张单子挤到后面");
    }

    @Test
    void laterPicksGoAheadOfThePlaylistBlock() {
        room.enqueue(owner, "房主", playable("start"), false);
        batch("深夜电台", "p1", "p2");

        room.enqueue(member, "成员", playable("q1"), false);
        assertEquals(List.of("q1", "p1", "p2"), queued(),
                "灌完单之后再点的那一首要越过整段歌单，落在排队位");

        room.enqueue(member, "成员", playable("q2"), false);
        assertEquals(List.of("q1", "q2", "p1", "p2"), queued(),
                "排队歌之间还是先到先得，歌单整段永远垫在最后");
    }

    @Test
    void ownerJumpStillBeatsThePlaylistBlock() {
        room.enqueue(owner, "房主", playable("start"), false);
        batch("深夜电台", "p1", "p2");

        room.enqueue(owner, "房主", playable("j1"), true);
        room.enqueue(owner, "房主", playable("j2"), true);
        assertEquals(List.of("j1", "j2", "p1", "p2"), queued(),
                "插队压在歌单之前，并且插队之间保持 1、2 的正序");

        // 插队段排空之后，插入点回到队首：档位规则不能把下一首插队歌锁在歌单后面。
        room.removeEntry(j1("j1").entryId);
        room.removeEntry(j1("j2").entryId);
        room.enqueue(owner, "房主", playable("j3"), true);
        assertEquals(List.of("j3", "p1", "p2"), queued());
    }

    private QueueEntry j1(String songId) {
        for (QueueEntry e : room.queue()) if (e.track.songId.equals(songId)) return e;
        throw new AssertionError("队列里没有 " + songId);
    }

    @Test
    void pinningAPlaylistTrackToTheHeadSurvivesLaterPicks() {
        room.enqueue(owner, "房主", playable("start"), false);
        batch("深夜电台", "p1", "p2", "p3");

        // 管理者的「置顶」是一次显式排位，它必须胜过档位规则 —— 否则下一首普通点歌就按
        // 「点歌排在歌单之前」把它挤回去，置顶等于没做。
        assertTrue(room.moveEntry(j1("p2").entryId, 0));
        room.enqueue(member, "成员", playable("q1"), false);

        assertEquals(List.of("p2", "q1", "p1", "p3"), queued());
    }

    // ══════════════════════════ 不许打断正在播的 ══════════════════════════

    @Test
    void batchNeverInterruptsWhatIsPlaying() {
        room.enqueue(owner, "房主", playable("start"), false);
        long posBefore = room.positionMs();
        host.clear();

        batch("深夜电台", "p1", "p2", "p3");

        assertEquals("start", room.current().track.songId, "正在播的那一首不能因为灌单被换掉");
        assertEquals(0, host.count(Proto.S_PLAY), "灌单不发播放帧：发一发全房就重开一次流");
        assertEquals(0, host.count(Proto.S_SEEK), "同理不许拖走任何人的播放头");
        assertTrue(posBefore >= 0L);
        assertTrue(host.count(Proto.S_QUEUE) > 0, "但歌单快照要立刻广播，界面才知道多了三首");
    }

    @Test
    void thePlayingTrackAndQueuedDuplicatesAreSkippedSilently() {
        room.enqueue(owner, "房主", playable("start"), false);
        room.enqueue(member, "成员", playable("q1"), false);

        // 一张从网页上抓下来的单子经常就包含眼下在放的那首；撞上一次就整张退回是不可接受的。
        Room.BatchResult r = batch("深夜电台", "start", "q1", "p1", "p2");

        assertEquals(2, r.added);
        assertEquals(2, r.duplicate);
        assertEquals(List.of("q1", "p1", "p2"), queued(), "重复的那两条一条都没再排进来");
        assertFalse(r.describe().contains("截断"), "没被上限截过就不该在提示里说截断");
        assertTrue(r.describe().contains("跳过 2 首"), r.describe());
    }

    @Test
    void nothingNewToQueueRejectsWithAReason() {
        room.enqueue(owner, "房主", playable("start"), false);
        IllegalStateException dup = assertThrows(IllegalStateException.class,
                () -> batch("深夜电台", "start"));
        assertTrue(dup.getMessage().contains("已经在队列里或正在播放"), dup.getMessage());

        assertThrows(IllegalStateException.class, () -> room.enqueueBatch(owner, "房主", "空的", new JsonArray()));
    }

    @Test
    void brokenEntriesAreSkippedInsteadOfRejectingTheWholeList() {
        room.enqueue(owner, "房主", playable("start"), false);
        JsonArray mixed = new JsonArray();
        mixed.add(meta("p1"));
        JsonObject noId = new JsonObject();
        noId.addProperty("source", "netease");
        noId.addProperty("name", "没有 id 的一首");
        mixed.add(noId);                                   // 缺 songId → fromJson 抛
        mixed.add(badSource("p2"));                        // 未开放的平台 → 抛
        mixed.add(meta("p3"));

        Room.BatchResult r = room.enqueueBatch(owner, "房主", "混着坏数据的单", mixed);

        assertEquals(2, r.added, "两条好的照样排进来");
        assertEquals(2, r.invalid, "两条坏的单独计数");
        assertEquals(List.of("p1", "p3"), queued());
    }

    private JsonObject badSource(String songId) {
        JsonObject o = meta(songId);
        o.addProperty("source", "this_platform_is_not_allowed");
        return o;
    }

    // ══════════════════════════ 两层上限 ══════════════════════════

    @Test
    void configuredBatchLimitTruncates() {
        room.enqueue(owner, "房主", playable("start"), false);
        cfg.playlistBatchLimit = 2;

        Room.BatchResult r = batch("深夜电台", "p1", "p2", "p3", "p4", "p5");

        assertEquals(2, r.added);
        assertTrue(r.truncated, "5 首只排了 2 首，必须说清是被单次上限截的");
        assertTrue(r.describe().contains("按单次上限截断"), r.describe());
        assertEquals(List.of("p1", "p2"), queued());
    }

    @Test
    void roomQueueCapStopsTheOverflow() {
        room.enqueue(owner, "房主", playable("start"), false);
        room.enqueue(member, "成员", playable("q1"), false);
        cfg.maxQueueSize = 3;

        Room.BatchResult r = batch("深夜电台", "p1", "p2", "p3", "p4");

        assertEquals(2, r.added, "总队列只剩 2 个位置，就只排 2 首");
        assertEquals(2, r.invalid, "剩下的计入「排不进去」，提示里要说");
        assertEquals(List.of("q1", "p1", "p2"), queued());
    }

    @Test
    void aZeroBatchLimitMeansNoExtraTruncationInsideTheRoom() {
        // 配置里 0 的含义是「关掉这个功能」，但那一句挡在 RoomManager 的分派上（见
        // aMemberIsRefusedWhileAdminOnlyIsOn 旁边那条）；Room 这一层把 0 当「不再额外截断」，
        // 这样万一哪天从别的入口调进来，也不会变成「一首都不给排」这种最难查的静默失败。
        room.enqueue(owner, "房主", playable("start"), false);
        cfg.playlistBatchLimit = 0;

        assertEquals(3, batch("深夜电台", "p1", "p2", "p3").added);
    }

    // ══════════════════════════ 前瞻窗 ══════════════════════════

    /** 某个 op 的所有帧里出现的曲目 id；广播是逐收件人发的，所以只能这样数。 */
    private Set<String> songIdsIn(String op) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonObject frame : host.frames(op)) {
            String id = Proto.str(frame, "songId", "");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    @Test
    void lookaheadOnlyAsksForTheNextFewTracks() {
        room.enqueue(owner, "房主", playable("start"), false);
        cfg.queueResolveLead = 2;
        host.clear();

        batch("深夜电台", "p1", "p2", "p3", "p4", "p5");

        assertEquals(Set.of("p1", "p2"), songIdsIn(Proto.S_URL_NEEDED),
                "五首裸歌只问前两组：一次灌单不该拿别人的账号和上行把整张单抽一遍");
    }

    @Test
    void tracksThatAlreadyHaveALinkAreNotAskedAgain() {
        room.enqueue(owner, "房主", playable("start"), false);
        cfg.queueResolveLead = 3;
        host.clear();

        JsonArray withUrl = new JsonArray();
        JsonObject ready = meta("p1");
        ready.addProperty("url", URL);
        ready.addProperty("urlExpiresAt", System.currentTimeMillis() + 600_000L);
        ready.addProperty("urlDuration", DURATION);
        withUrl.add(ready);
        withUrl.add(meta("p2"));
        room.enqueueBatch(owner, "房主", "半备好的单", withUrl);

        assertEquals(Set.of("p2"), songIdsIn(Proto.S_URL_NEEDED),
                "带链接的那条已经在手上，不该再向全房问一遍");
    }

    @Test
    void theSameTrackIsNotAskedTwiceInsideTheCooldown() {
        room.enqueue(owner, "房主", playable("start"), false);
        cfg.queueResolveLead = 1;
        host.clear();

        batch("深夜电台", "p1", "p2");
        int first = host.count(Proto.S_URL_NEEDED);
        // 队列一变就跑一遍前瞻（客户端每次点歌、每次补链接都会触发），同一首在冷却里不能重复问。
        batch("另一张单", "p3");

        assertEquals(first, host.count(Proto.S_URL_NEEDED), "p1 还在前瞻窗里，但不该被再问一遍");
    }

    // ══════════════════════════ 快照上多出来的那个键 ══════════════════════════

    @Test
    void theSnapshotSaysWhichEntriesCameFromAPlaylist() {
        room.enqueue(owner, "房主", playable("start"), false);
        room.enqueue(member, "成员", playable("q1"), false);
        batch("深夜电台", "p1", "p2");

        JsonArray queue = host.last(Proto.S_QUEUE).getAsJsonArray("queue");
        assertEquals(3, queue.size());
        assertFalse(queue.get(0).getAsJsonObject().has("playlist"),
                "普通点歌不该多出一个空键：老客户端读到 null 与读到空串等价，但界面会以为它是歌单歌");
        assertEquals("深夜电台", queue.get(1).getAsJsonObject().get("playlist").getAsString());
        assertEquals("深夜电台", queue.get(2).getAsJsonObject().get("playlist").getAsString());
        assertFalse(queue.get(0).getAsJsonObject().has("tier"),
                "档位是服务端内部的排序依据，不是界面要读的东西");
    }

    @Test
    void anEmptyPlaylistNameFallsBackToALabelInsteadOfAnEmptyTag() {
        room.enqueue(owner, "房主", playable("start"), false);
        room.enqueueBatch(owner, "房主", "   ", metas("p1"));

        assertEquals("未命名歌单", room.queue().get(0).playlist);
        assertFalse(room.queue().get(0).jumped(), "灌进来的歌当然不是插队");
    }

    @Test
    void tracksWithoutADurationAreSkippedBecauseTheRoomCannotHandOver() {
        // 自动进歌的判据是 positionMs() >= fireAt(dur)。dur=0 时那句永远不成立，房间会卡死在
        // 这一首上直到有人手按「下一曲」—— 一次灌一百首时这就是「房间停半小时」。
        room.enqueue(owner, "房主", playable("start"), false);
        JsonArray mixed = new JsonArray();
        mixed.add(meta("p1"));
        JsonObject blind = meta("p2");
        blind.addProperty("duration", 0L);
        mixed.add(blind);
        mixed.add(meta("p3"));

        Room.BatchResult r = room.enqueueBatch(owner, "房主", "没时长的单", mixed);

        assertEquals(2, r.added);
        assertEquals(1, r.noLength);
        assertTrue(r.describe().contains("没有时长信息"), r.describe());
        assertEquals(List.of("p1", "p3"), queued());
    }

    @Test
    void aWholeListWithoutDurationsFailsWithTheReasonThatActuallyHelps() {
        room.enqueue(owner, "房主", playable("start"), false);
        JsonArray blind = new JsonArray();
        for (String id : new String[]{"p1", "p2"}) {
            JsonObject o = meta(id);
            o.addProperty("duration", 0L);
            blind.add(o);
        }
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> room.enqueueBatch(owner, "房主", "整张没时长", blind));
        assertTrue(e.getMessage().contains("时长"), e.getMessage());
    }

    @Test
    void anEmptyRoomStartsTheQueueHeadNotTheFirstTrackOfThePlaylist() {
        // 房间空着时灌单会起播，但起播的必须是<b>整条队列的头</b>：之前已经有人排过队的话，
        // 按优先级那些歌先到，不能因为「这次是灌单」就跳到他们前面。
        room.enqueue(member, "成员", playable("q1"), false);
        assertEquals("q1", room.current().track.songId, "空房点歌当场起播，q1 已经是 current");

        batch("深夜电台", "p1", "p2");
        assertEquals("q1", room.current().track.songId, "有歌在放就一首都不许动");
        assertEquals(List.of("p1", "p2"), queued());
    }

    // ══════════════════════════ 撤回这张单 ══════════════════════════

    @Test
    void withdrawingAPlaylistTakesOnlyItsOwnPendingTracks() {
        room.enqueue(owner, "房主", playable("start"), false);
        room.enqueue(member, "成员", playable("q1"), false);
        batch("深夜电台", "p1", "p2", "p3");
        batch("另一张", "x1");

        assertEquals(3, room.removePlaylist(owner, "深夜电台"), "只撤这张单的那三首");

        assertEquals(List.of("q1", "x1"), queued(), "别人点的、另一张单的都不该跟着一起没了");
        assertEquals("start", room.current().track.songId);
    }

    @Test
    void withdrawingNeverCutsWhatIsAlreadyPlaying() {
        // 灌进来之后立刻轮到它：正在播的那一首也在同一张单子里。撤回不能把它从别人耳朵里抽走。
        batch("深夜电台", "p1", "p2");
        assertEquals("p1", room.current().track.songId);

        assertEquals(1, room.removePlaylist(owner, "深夜电台"));

        assertEquals("p1", room.current().track.songId, "撤的是待播，不是已经在放的那一首");
        assertEquals(List.of(), queued());
    }

    @Test
    void aMemberCannotWithdrawSomeoneElsesBatch() {
        // 先让房间有歌在放：空房灌单会把第一批的第一首直接起播（见
        // aRoomWithNothingPlayingStartsWithTheFirstPickedTrack），那时队列里只剩 p2，
        // 这条测试想钉的「别人的批次撤不动」就被起播那一步搅混了。
        room.enqueue(owner, "房主", playable("start"), false);
        batch("深夜电台", "p1", "p2");

        assertEquals(0, room.removePlaylist(member, "深夜电台"),
                "歌单名是客户端自己填的，不能靠报一个名字拆掉别人的队列");
        assertEquals(List.of("p1", "p2"), queued());
    }

    @Test
    void aMemberCanWithdrawTheirOwnBatch() {
        room.enqueue(owner, "房主", playable("start"), false);
        room.enqueueBatch(member, "成员", "深夜电台", metas("p1", "p2"));

        assertEquals(2, room.removePlaylist(member, "深夜电台"));
        assertEquals(List.of(), queued());
    }

    // ══════════════════════════ 协议与权限 ══════════════════════════

    private void send(UUID from, JsonObject msg) {
        MpParty.onPacket(from, Proto.encode(msg));
    }

    private JsonObject batchFrame(String listName, String... ids) {
        JsonObject m = Proto.msg(Proto.C_ENQUEUE_BATCH);
        m.addProperty("playlist", listName);
        m.add("tracks", metas(ids));
        return m;
    }

    @Test
    void helloAnnouncesThePlaylistCapability() {
        JsonObject hello = Proto.msg(Proto.C_HELLO);
        hello.addProperty("proto", Proto.VERSION);
        send(owner, hello);

        JsonObject reply = host.last(Proto.S_HELLO);
        assertNotNull(reply);
        assertTrue(reply.get("playlistBatchLimit").getAsInt() > 0, "客户端靠它决定那颗按钮出不出现");
        assertFalse(reply.get("playlistAdminOnly").getAsBoolean(),
                "默认对成员开放灌单（另有单次上限与导入冷却兜底）");
    }

    @Test
    void aMemberIsRefusedWhileAdminOnlyIsOn() {
        cfg.playlistAdminOnly = true;   // 默认已开放：显式收紧才走拒绝路径
        room.enqueue(owner, "房主", playable("start"), false);
        host.clear();

        send(member, batchFrame("深夜电台", "p1"));

        JsonObject error = host.last(Proto.S_ERROR);
        assertNotNull(error, "普通成员灌单要被挡下来");
        assertTrue(Proto.str(error, "msg", "").contains("房主"), Proto.str(error, "msg", ""));
        assertEquals(List.of(), queued(), "被挡下就不该有任何一条排进来");
    }

    @Test
    void aMemberIsAllowedOnceTheServerOpensItUp() {
        cfg.playlistAdminOnly = false;
        room.enqueue(owner, "房主", playable("start"), false);
        host.clear();

        send(member, batchFrame("深夜电台", "p1", "p2"));

        assertEquals(List.of("p1", "p2"), queued());
        assertNull(host.last(Proto.S_ERROR));
        assertTrue(Proto.str(host.last(Proto.S_NOTICE), "msg", "").contains("已排入歌单"),
                "灌单要在聊天栏说一句，否则别人只看到队列凭空多了两首");
    }

    @Test
    void theChannelRefusesWholePlaylists() {
        // 另找一个人：setUp 里房主已经在自己的房间里了，而一个人只能挂在一个房间上
        // （joinChannel 会为「已经在别的房里」直接抛）。
        UUID stranger = host.addPlayer("路人");
        manager.noteOperator(stranger, true);
        Room channel = manager.joinChannel(stranger);
        host.clear();

        send(stranger, batchFrame("深夜电台", "p1"));

        JsonObject error = host.last(Proto.S_ERROR);
        assertNotNull(error, "全服共用的频道里不能一个人倒进一百首");
        assertTrue(Proto.str(error, "msg", "").contains("频道"), Proto.str(error, "msg", ""));
        assertTrue(channel.queue().isEmpty());
    }

    @Test
    void aRoomWithNothingPlayingStartsWithTheFirstPickedTrack() {
        // 房间一首都没有时灌单，起播这一批的第一首不是「打断」，而是「终于有歌可放」——
        // 和空房点歌时那句注释说的是同一件事。
        host.clear();

        batch("深夜电台", "p1", "p2");

        assertEquals("p1", room.current().track.songId);
        assertEquals(List.of("p2"), queued());
        assertEquals("深夜电台", room.current().playlist);
        // 灌单进来的条目<b>不带直链</b>（链接交给前瞻窗按需问），所以这一首现在是「等链接」而不是
        // 已经在放。这不是缺陷：空房里逐首点歌走的也是同一条等待路径，而这里必须当场向全房要
        // p1 的链接 —— 房间没歌可放的时候，等一等比什么都不做强。
        assertFalse(room.isPlaying(), "没有链接就不能开时钟，否则全房各自对着一个空位置校准");
        assertTrue(songIdsIn(Proto.S_URL_NEEDED).contains("p1"), "开播那一首要立刻向房间要链接");
    }
}
