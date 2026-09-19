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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 房间交接计划的<b>服务端一半</b>：房主把「该在哪里交接、新曲从哪一秒进」报上来之后，
 * 房间的换歌时刻与下一首的起点都必须跟着这份计划走。
 *
 * <p>为什么需要这一层：交接点得看音频内容（人声尾、尾奏落点、前奏长度），而音频只存在于各
 * 客户端本地；可「唱到第几毫秒」又只有服务端一套权威时钟。于是分工是 —— 房主分析、服务端
 * 裁决与广播、全房执行同一份。这里钉的就是「裁决」这一环：</p>
 * <ul>
 *   <li>计划可以把交接点挪到曲末前很远（内容说的）或很近（长尾奏）；</li>
 *   <li>计划可以新曲从中间进（跳过死寂前奏），时钟基准随之挪过去；</li>
 *   <li>不是房主报的、或者给错了曲目的计划，一律丢弃；</li>
 *   <li>没有计划时退回 {@code automixLeadMs}，行为与旧版一致。</li>
 * </ul>
 */
class RoomAutomixPlanTest {

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

    /** 房主客户端上报的计划；字段与 {@code RoomAutomix#encode} 一致。 */
    private JsonObject plan(long fire, long overlap, long cue) {
        JsonObject o = new JsonObject();
        o.addProperty("fireMillis", fire);
        o.addProperty("overlapMillis", overlap);
        o.addProperty("cueMillis", cue);
        o.addProperty("matchGain", 1.1f);
        o.addProperty("barAligned", true);
        o.addProperty("bassSwap", true);
        o.addProperty("tempoRatio", .97f);
        o.addProperty("pitchShift", false);
        o.addProperty("phaseErrorMillis", 30L);
        o.addProperty("vocalDuckDepth", .5f);
        o.addProperty("summary", "outro vocal tail");
        return o;
    }

    private void reportForTheQueuedHead(JsonObject plan) {
        room.reportPlan(owner, room.queue().get(0).entryId, plan);
    }

    // ── 交接点跟着内容走 ──────────────────────────────────────────────────────

    @Test
    void thePlanMovesTheHandoverOntoTheContent() {
        playingWithOneQueued();
        // 比默认提前量（4000ms）更早、但仍在 12 秒交叠上限内：内容说人声/尾奏收得早一点。
        // 交接点跟着内容走，不再是「距曲末固定 4000ms」——这正是无缝交接与定时淡化的区别。
        long fire = DURATION - 9_000L;     // 曲末前 9 秒，早于 4 秒的默认提前量
        reportForTheQueuedHead(plan(fire, 9_000L, 0L));
        host.clear();

        room.seek(fire - 200L);
        room.tick();
        assertEquals(0, host.count(Proto.S_PLAY), "还没到计划给的交接点，不许按固定提前量抢跑");

        room.seek(fire);
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY), "一到计划给的交接点就该换歌");
        assertEquals("曲目2", room.current().track.name, "换到的必须是下一首");
    }

    @Test
    void thePlanCanAlsoHandOverLaterThanTheConfiguredLead() {
        playingWithOneQueued();
        // 比默认提前量更晚：这一首有一段很长的尾奏，提前切会把尾奏整段砍掉。
        long fire = DURATION - 1_200L;
        reportForTheQueuedHead(plan(fire, 9_000L, 0L));
        host.clear();

        room.seek(DURATION - 3_500L);      // 默认提前量的位置已经过了
        room.tick();
        assertEquals(0, host.count(Proto.S_PLAY), "计划说还在尾奏里，就不许按固定提前量切");

        room.seek(DURATION - 1_000L);
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY), "计划给的时刻才是交接时刻");
    }

    @Test
    void thePlanDecidesWhereTheNextTrackStarts() {
        playingWithOneQueued();
        long cue = 2_150L;
        reportForTheQueuedHead(plan(DURATION - 9_000L, 9_000L, cue));

        room.seek(DURATION - 9_000L);
        room.tick();

        assertEquals("曲目2", room.current().track.name);
        assertEquals(cue, room.positionMs(), 700L,
                "新曲目必须从计划给的入口点起播 —— 这一步同时把全房时钟基准挪到入口点，"
                        + "所以中途进房的人也算得出同一句歌词在什么时候唱");
    }

    // ── 只有房主能定，且计划不能过期 ──────────────────────────────────────────

    @Test
    void aPlanFromSomeoneWhoCannotAnalyzeIsIgnored() {
        playingWithOneQueued();
        // 一个既不是房主、也没自报能算的人 —— 房间里没有他说话的地方。
        room.reportPlan(UUID.randomUUID(), room.queue().get(0).entryId, plan(10_000L, 9_000L, 0L));
        host.clear();

        room.seek(10_000L);
        room.tick();
        assertEquals(0, host.count(Proto.S_PLAY), "没被指派、也报不出能力位的人不能定全房的交接点");
    }

    @Test
    void aPlanForAnotherTrackIsDropped() {
        playingWithOneQueued();
        // entryId 对不上：队列已经变过（有人插歌、跳歌），这份计划说的是别的曲子。
        room.reportPlan(owner, 999_999L, plan(10_000L, 9_000L, 0L));
        host.clear();

        room.seek(10_000L);
        room.tick();
        assertEquals(0, host.count(Proto.S_PLAY), "过期的计划必须被丢弃，绝不能拿来交接");
    }

    @Test
    void aHandoverPointBeyondTheEndOfTheTrackIsIgnored() {
        playingWithOneQueued();
        reportForTheQueuedHead(plan(DURATION + 5_000L, 9_000L, 0L));
        host.clear();

        room.seek(DURATION - 3_500L);
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY),
                "越界的交接点必须被忽略并退回默认提前量，否则这一首会先播完、中间空一段");
    }

    /**
     * 交接点被报得离曲末太远（远超交叠长度上限）时必须被忽略。
     *
     * <p>真实的坑：modernMP 把 {@code fireMillis} 误报成「尾奏扫描窗口起点」（曲末前 25 秒），
     * 而合法计划的不变式是 {@code fire + overlap = dur} 且 {@code overlap ≤ 12s}，所以
     * {@code fire ≥ dur - 12s}。服务端只挡上限（不落到曲末之后）而不挡下限的话，全房会在曲末前
     * 25 秒就一起换歌，把 A 的最后 25 秒整段切掉。这一条钉住服务端的下限守卫。</p>
     */
    @Test
    void aHandoverPointFarTooEarlyIsIgnored() {
        playingWithOneQueued();
        // 曲末前 25 秒 —— 正是 modernMP 那个 bug 会报出来的值，远超 12 秒的交叠上限。
        long tooEarly = DURATION - 25_000L;
        reportForTheQueuedHead(plan(tooEarly, 9_000L, 0L));
        host.clear();

        room.seek(tooEarly + 10L);
        room.tick();
        assertEquals(0, host.count(Proto.S_PLAY),
                "离曲末超过 12 秒的交接点是坏计划，绝不能按它提前 25 秒换歌");

        // 退回默认提前量：走到「曲末 − 4000ms」才换。
        room.seek(DURATION - 3_500L);
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY),
                "坏计划被忽略后退回配置提前量，房间照常在正常位置换歌");
    }

    /** 正好卡在 12 秒下限上的交接点仍然有效（边界值不能误伤合法计划）。 */
    @Test
    void aHandoverPointRightAtTheTwelveSecondFloorStillFires() {
        playingWithOneQueued();
        long atFloor = DURATION - 12_000L;
        reportForTheQueuedHead(plan(atFloor, 12_000L, 0L));
        host.clear();

        room.seek(atFloor);
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY),
                "fire = dur - 12s 是合法计划的下限，必须照常交接");
    }

    /**
     * 一份<b>长交叠</b>的合法计划（Folia 能给到 25 秒、RoomAutomix 允许到 60 秒）必须放行。
     *
     * <p>下限不能写死成 12 秒：那会把这些精心算好的长过渡误杀、退回硬切。判据是「fire 不早于
     * dur − overlap」—— 交接点从曲末往前挪的极限就是这份计划打算叠多长。这里 fire 比曲末早
     * 20 秒，但它的 overlap 也正好是 20 秒，属于合法长过渡。</p>
     */
    @Test
    void aLegitimateLongOverlapPlanStillFires() {
        playingWithOneQueued();
        long overlap = 20_000L;                 // 远超 12 秒的配置提前量上限
        long fire = DURATION - overlap;         // fire + overlap = dur，合法
        reportForTheQueuedHead(plan(fire, overlap, 0L));
        host.clear();

        room.seek(DURATION - 15_000L);          // 还没到 fire（曲末前 20 秒）
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY),
                "长交叠计划（fire = dur − 20s、overlap = 20s）是合法的，不能因为超过 12 秒就被砍成硬切");
    }

    /**
     * 但「长 overlap」不能被拿来给一个内容点没找准的坏 fire 背书。
     *
     * <p>fire 报成曲末前 30 秒、overlap 却只有 5 秒 —— fire 远早于 dur − overlap，
     * 是 modernMP 那类 bug 的形态。下限取 {@code dur − max(12s, overlap)}，这里 = dur − 12s，
     * fire（dur − 30s）仍被挡下。</p>
     */
    @Test
    void aFarTooEarlyFireIsStillRejectedEvenWithASmallOverlap() {
        playingWithOneQueued();
        long tooEarly = DURATION - 30_000L;
        reportForTheQueuedHead(plan(tooEarly, 5_000L, 0L));
        host.clear();

        room.seek(tooEarly + 10L);
        room.tick();
        assertEquals(0, host.count(Proto.S_PLAY),
                "fire 远早于 dur − overlap 的坏计划仍要被挡下，长 overlap 的放宽只对 overlap 本身成立");
    }

    // ── 下发 ──────────────────────────────────────────────────────────────────

    @Test
    void theQueueBroadcastCarriesThePlan() {
        playingWithOneQueued();
        reportForTheQueuedHead(plan(172_000L, 9_000L, 2_150L));

        JsonObject next = host.last(Proto.S_QUEUE).getAsJsonObject("next");
        assertNotNull(next, "歌单广播仍然要带「下一首」");
        JsonObject carried = next.getAsJsonObject("plan");
        assertNotNull(carried, "客户端要靠计划提前预排交叠，而不是自己猜一个固定值");
        assertEquals(172_000L, carried.get("fireMillis").getAsLong());
        assertEquals(2_150L, carried.get("cueMillis").getAsLong());
        assertEquals(9_000L, carried.get("overlapMillis").getAsLong());
    }

    @Test
    void theSnapshotCarriesItToo() {
        playingWithOneQueued();
        reportForTheQueuedHead(plan(172_000L, 9_000L, 2_150L));

        JsonObject next = room.snapshot().getAsJsonObject("room").getAsJsonObject("next");
        assertNotNull(next, "快照里本来就有「下一首」");
        assertNotNull(next.getAsJsonObject("plan"),
                "中途进房与断线重连的人也要拿到同一份计划，否则他们会按兜底曲线交叠");
    }

    // ── 队首变化就作废计划 ──────────────────────────────────────────────────
    //
    // 计划描述的是「从 current 进入队首那一首」。插队 / 删歌 / 移动都会把队首换成别的曲子，
    // 而计划挂在 current 上不会自动失效。不作废的话：旧计划会被继续下发、并按为**另一首**算
    // 出来的 fire/cue 换歌，界面还显示「无缝已就绪」，新队首该有的计划又永远报不进来。

    @Test
    void insertingANewHeadInvalidatesThePlan() {
        playingWithOneQueued();
        long fire = 172_000L;
        reportForTheQueuedHead(plan(fire, 9_000L, 0L));
        assertNotNull(room.current().automixPlan, "测试前提：计划已挂在 current 上");

        // 房主插队一首（下一曲播放）——队首现在是曲目3，不再是当初算计划的曲目2。
        room.enqueue(owner, "房主", playable("3"), true);
        assertEquals("曲目3", room.queue().get(0).track.name, "测试前提：插队把队首换掉了");
        assertNull(room.current().automixPlan,
                "队首变了，为旧队首算的计划必须作废，否则会按错误的音频换歌");
        host.clear();

        room.seek(fire);
        room.tick();
        assertEquals(0, host.count(Proto.S_PLAY),
                "旧计划已作废，不许再按它给的（属于另一首歌的）交接点换歌");
    }

    @Test
    void removingTheHeadInvalidatesThePlan() {
        playingWithOneQueued();
        room.enqueue(owner, "房主", playable("3"), false);   // 队列现在是 [曲目2, 曲目3]
        long fire = 172_000L;
        reportForTheQueuedHead(plan(fire, 9_000L, 0L));       // 计划瞄准曲目2
        assertNotNull(room.current().automixPlan, "测试前提：计划已挂上");

        // 删掉队首曲目2 —— 队首现在是曲目3，计划对应的曲子没了。
        room.removeEntry(room.queue().get(0).entryId);
        assertEquals("曲目3", room.queue().get(0).track.name, "测试前提：删掉队首后曲目3顶上");
        assertNull(room.current().automixPlan, "删掉计划瞄准的那一首后，计划必须作废");
        host.clear();

        room.seek(fire);
        room.tick();
        assertEquals(0, host.count(Proto.S_PLAY), "对不上号的旧计划不能拿来交接");
    }

    @Test
    void movingAnotherEntryToTheHeadInvalidatesThePlan() {
        playingWithOneQueued();
        room.enqueue(owner, "房主", playable("3"), false);   // [曲目2, 曲目3]
        reportForTheQueuedHead(plan(172_000L, 9_000L, 0L));   // 计划瞄准曲目2

        // 把曲目3 移到队首。
        long threeId = room.queue().get(1).entryId;
        room.moveEntry(threeId, 0);
        assertEquals("曲目3", room.queue().get(0).track.name, "测试前提：移动把曲目3挪到队首");
        assertNull(room.current().automixPlan, "队首被移动换掉后，旧计划必须作废");
    }

    @Test
    void afterInvalidationTheNewHeadCanGetAFreshPlan() {
        playingWithOneQueued();
        reportForTheQueuedHead(plan(172_000L, 9_000L, 0L));
        room.enqueue(owner, "房主", playable("3"), true);     // 插队，作废旧计划
        assertNull(room.current().automixPlan, "测试前提：旧计划已作废");
        host.clear();

        // 现在为新队首（曲目3）报一份计划 —— 不作废的话 mayReportPlan 会因 automixPlan!=null 把它挡死。
        long newFire = DURATION - 8_000L;   // 曲末前 8 秒，落在 12 秒交叠上限内
        room.reportPlan(owner, room.queue().get(0).entryId, plan(newFire, 8_000L, 0L));
        assertNotNull(room.current().automixPlan, "作废后新队首必须能重新接收计划");

        room.seek(newFire);
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY), "换歌点跟着新队首那份计划走");
        assertEquals("曲目3", room.current().track.name);
    }

    // ── 退路 ──────────────────────────────────────────────────────────────────

    @Test
    void withoutAPlanTheConfiguredLeadStillRules() {
        playingWithOneQueued();
        host.clear();

        room.seek(DURATION - 3_500L);      // 默认提前量 4000ms
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY),
                "没有计划时（房主没算完 / 算不出 / 客户端是旧版）行为与旧版一致");
    }
}
