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
        // 远早于「曲末前 4000ms」：比如人声在尾奏前就收住了。
        long fire = 172_000L;
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
