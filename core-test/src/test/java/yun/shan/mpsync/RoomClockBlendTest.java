package yun.shan.mpsync;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.support.FakePlatform;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 房间内 automix 的<b>交叠期</b>要把时钟心跳让出来 —— 服务端这一半。
 *
 * <p>用户报的症状：一起听里开着无缝切换时，偶尔会被「强制拉到播放进度」，交叠的听感当场断掉。
 * 成因不在纠偏阈值本身，而在那段让路的时间没盖住交叠：</p>
 * <ol>
 *   <li>服务端在交接点把房间时钟切到新曲（{@code startEpoch} 落在它自己发现到点的那一瞬）并广播
 *       换歌帧；</li>
 *   <li>各客户端开始淡入新曲的时刻必然更晚 —— 帧在路上要花时间，本机可能已经攒着一条落在软档里
 *       没纠的漂移，加上新那条流的缓冲。于是淡入期间「本机位置」和「房间位置」本来就差着几秒，
 *       这是设计如此，不是脱节；</li>
 *   <li>可静默窗只按 {@link MpConfig#clockGraceMs} 开（{@code load()} 的下限是
 *       {@code automixLeadMs + 1000}，默认五秒）。一段跟音频内容走的长尾奏能叠十几二十秒，
 *       窗一过心跳就恢复，各端量出「超过硬阈值」，两发确认后就把刚叠进来的那一条
 *       {@code jump()} 回去 —— 截断。</li>
 * </ol>
 *
 * <p>所以换歌那一刻的静默窗要按<b>这一首计划里实际的交叠长度</b>来开。放在服务端是因为它是
 * 全房唯一的时钟来源：心跳不发，同房间里的 modernMP 客户端也一样不会被误判 —— 比只在某一个
 * 客户端里加保护要彻底。</p>
 *
 * <p>窗有多长直接读 {@link Room#clockQuietRemainingMs()}，不用「睡到心跳回来」反推：心跳真正
 * 落到某个成员还要过广播节奏那一道，靠等待去量会把那层节奏一起量进来（实测同一份窗要再过几秒
 * 才看得见帧）。「让路多久」和「节奏多久一发」是两件事，各测各的；而「配置窗过了之后心跳确实
 * 会回来」这一条由 {@code RoomClockTest} 守着，本类的三条反面用例也各自当场读到一发。</p>
 */
class RoomClockBlendTest {

    private static final String URL = "https://m8c.music.126.net/full.mp3";
    private static final long DURATION = 240_000L;

    private FakePlatform host;
    private MpConfig cfg;
    private Room room;
    private UUID owner;

    @BeforeEach
    void setUp() {
        host = new FakePlatform();
        Net.bind(host);

        cfg = new MpConfig();
        cfg.clockGraceMs = 200L;      // 真实默认 4000（开交叠后 load() 抬到 lead+1000）
        cfg.timeBroadcastTicks = 1;   // 每 tick 都到广播点，便于精确计数
        cfg.automixLeadMs = 4_000L;

        owner = host.addPlayer("房主");
        room = new Room(cfg, "r1", "ABCDE", owner, "房主", "测试房");
        Net.markCapable(owner);
    }

    private Track playable(String songId) {
        Track t = new Track("netease", songId);
        t.name = "曲目" + songId;
        t.durationMs = DURATION;
        t.adoptUrl(URL, System.currentTimeMillis() + 600_000L, DURATION, cfg);
        return t;
    }

    /** 一首在放、队列里正好一首「下一首」。 */
    private void playingWithOneQueued() {
        room.enqueue(owner, "房主", playable("1"), false);
        room.enqueue(owner, "房主", playable("2"), false);
        assertNotNull(room.current(), "测试前提：第一首在放");
        assertEquals(1, room.queue().size(), "测试前提：队列里正好只有一首「下一首」");
    }

    /** 房主客户端上报的计划；字段与客户端 {@code RoomAutomix#encode} 一致。 */
    private JsonObject plan(long fire, long overlap, long cue) {
        JsonObject o = new JsonObject();
        o.addProperty("fireMillis", fire);
        o.addProperty("overlapMillis", overlap);
        o.addProperty("cueMillis", cue);
        o.addProperty("summary", "outro vocal tail");
        return o;
    }

    private void reportForTheQueuedHead(JsonObject plan) {
        room.reportPlan(owner, room.queue().get(0).entryId, plan);
    }

    /** 走到服务端自己提前换歌那一路：拖到交接点再 tick，换歌就在这一 tick 里发生。 */
    private void handOverAt(long fire) {
        room.seek(fire);
        room.tick();
        assertEquals("曲目2", room.current().track.name, "测试前提：这一 tick 完成了交叠换歌");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ═════════════════ 交叠期间要让路 ═════════════════

    @Test
    void plannedBlendWindowCoversTheWholeCrossfade() {
        playingWithOneQueued();
        long fire = DURATION - 9_000L;
        reportForTheQueuedHead(plan(fire, 9_000L, 0L));

        handOverAt(fire);
        // 窗 = automixLeadMs(4000) + overlap(9000) + 1000：淡入没结束之前，心跳一发都不许下去。
        long quiet = room.clockQuietRemainingMs();
        assertTrue(quiet >= 13_000L && quiet <= 14_000L,
                "静默窗要盖得住这一段交叠，实际 " + quiet + "ms");

        room.tick();
        assertEquals(0, host.count(Proto.S_TIME), "窗内的 tick 不许发心跳");

        sleep(320L);                                   // 早已越过 clockGraceMs(200)
        room.tick();
        assertEquals(0, host.count(Proto.S_TIME),
                "配置窗过了不等于能校准：此刻各端量到的偏差是「本机比房间晚几秒开始淡入」，"
                        + "拿它去比硬阈值就会把刚叠上的那一条薅回去，用户听到的正是截断");
    }

    @Test
    void theWindowCountsDownInsteadOfLatchingForever() {
        playingWithOneQueued();
        long fire = DURATION - 9_000L;
        reportForTheQueuedHead(plan(fire, 9_000L, 0L));
        handOverAt(fire);

        long before = room.clockQuietRemainingMs();
        sleep(700L);
        long after = room.clockQuietRemainingMs();
        assertTrue(after > 0L, "测试前提：这一段窗还没走完，实际剩余 " + after + "ms");
        assertTrue(after < before - 400L,
                "让路要在有限时间里走完，否则房间再也没有人校准：before=" + before + " after=" + after);
    }

    @Test
    void anAbsurdOverlapIsCappedInsteadOfBlindingTheRoom() {
        playingWithOneQueued();
        // 一份把交叠报成一分钟的坏计划：上限之外那段不许一并让路 —— 客户端的位置也要有人拉得回来。
        reportForTheQueuedHead(plan(DURATION - 60_000L, 60_000L, 0L));

        handOverAt(DURATION - 60_000L);
        long quiet = room.clockQuietRemainingMs();
        assertTrue(quiet > 25_000L && quiet <= 30_000L,
                "保护窗必须有上限，实际 " + quiet + "ms");
    }

    // ═════════════════ 别的换歌不许借这段窗 ═════════════════

    @Test
    void manualNextDoesNotBorrowTheBlendWindow() {
        playingWithOneQueued();
        reportForTheQueuedHead(plan(DURATION - 9_000L, 9_000L, 0L));

        room.next(false);                              // 人按的「下一首」：没有交叠这回事
        assertTrue(room.clockQuietRemainingMs() <= cfg.clockGraceMs + 50L,
                "手动换歌只让配置的窗，实际剩余 " + room.clockQuietRemainingMs() + "ms");

        sleep(320L);
        room.tick();
        assertEquals(1, host.count(Proto.S_TIME), "一次点跳过不该让房间二十秒不校准");
    }

    @Test
    void aSwitchWithoutAPlanKeepsTheOrdinaryWindow() {
        playingWithOneQueued();
        // 被指派的那台还没交卷（或全房没人能算）：服务端到点硬切，两边都不淡入。
        handOverAt(DURATION - cfg.automixLeadMs);
        assertTrue(room.clockQuietRemainingMs() <= cfg.clockGraceMs + 50L,
                "没有计划就没有一段要盖住的混音，实际剩余 " + room.clockQuietRemainingMs() + "ms");

        sleep(320L);
        room.tick();
        assertEquals(1, host.count(Proto.S_TIME),
                "这一首没有计划，此刻的偏差就是真脱节 —— 心跳必须已经在发");
    }

    @Test
    void blendWindowIsNotHeldWhenAutomixIsOff() {
        cfg.automixLeadMs = 0L;        // 服主关掉了提前换歌：尾奏要完整放完
        playingWithOneQueued();
        long fire = DURATION - 9_000L;
        reportForTheQueuedHead(plan(fire, 9_000L, 0L));

        handOverAt(fire);
        assertTrue(room.clockQuietRemainingMs() <= cfg.clockGraceMs + 50L,
                "没开交叠就不该按计划的交叠长度让路，实际剩余 "
                        + room.clockQuietRemainingMs() + "ms");

        sleep(320L);
        room.tick();
        assertEquals(1, host.count(Proto.S_TIME), "关掉交叠之后校准要立刻恢复");
    }
}
