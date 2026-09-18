package yun.shan.mpsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.presence.PresenceRegistry;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.support.FakePlatform;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「这一首的交接计划已经算好了」必须在歌单里可见。
 *
 * <p>用户的要求是「待放歌曲推理计算完之后应当报告，且在歌单列表有展示」。服务端在这里的角色是
 * <b>把状态捎到歌单帧上</b>：计划一到就补一次 {@code S_QUEUE}，队首那一行带上 {@code planReady}，
 * 客户端的歌单列表据此显示标记。没有它，分析这件事在整个界面上完全不可见，用户只能从日志里猜。</p>
 *
 * <p>标记只属于<b>队首</b>：计划描述的是「从正在播的这一首怎么进入队首那一首」，队列里第二首
 * 之后还没有计划可言。</p>
 */
class RoomAutomixQueueMarkerTest {

    private static final String URL = "https://m8c.music.126.net/full.mp3";
    private static final long DURATION = 240_000L;

    private FakePlatform host;
    private MpConfig cfg;
    private PresenceRegistry presence;
    private Room room;
    private UUID owner;

    @BeforeEach
    void setUp() {
        host = new FakePlatform();
        Net.bind(host);
        cfg = new MpConfig();
        presence = new PresenceRegistry();
        owner = host.addPlayer("房主");
        room = new Room(cfg, "r1", "ABCDE", owner, "房主", "测试房");
        room.setPresence(presence);
        Net.markCapable(owner);
    }

    private Track playable(String songId) {
        Track t = new Track("netease", songId);
        t.name = "曲目" + songId;
        t.durationMs = DURATION;
        t.adoptUrl(URL, System.currentTimeMillis() + 600_000L, DURATION, cfg);
        return t;
    }

    private void capable(UUID uuid, String engine) {
        JsonObject p = new JsonObject();
        p.addProperty("automixCapable", true);
        p.addProperty("automixEngine", engine);
        presence.update(uuid, p);
    }

    private JsonObject plan(long fire, long overlap, long cue) {
        JsonObject o = new JsonObject();
        o.addProperty("fireMillis", fire);
        o.addProperty("overlapMillis", overlap);
        o.addProperty("cueMillis", cue);
        o.addProperty("summary", "test plan");
        return o;
    }

    /** 最近一次歌单广播里队首那一行的 JSON。 */
    private JsonObject queueHead() {
        JsonObject frame = host.last(Proto.S_QUEUE);
        assertNotNull(frame, "测试前提：应当有歌单广播");
        JsonArray queue = frame.getAsJsonArray("queue");
        assertTrue(queue.size() > 0, "测试前提：歌单里应当有下一首");
        return queue.get(0).getAsJsonObject();
    }

    /** 房主放一首，另一首排队等着 —— 于是「交接」这件事成立。 */
    private long setUpHandover() {
        capable(owner, "model");
        room.join(owner, "房主");
        room.enqueue(owner, "房主", playable("1"), false);
        room.enqueue(owner, "房主", playable("2"), false);
        room.refreshAnalyst();
        return room.queue().get(0).entryId;
    }

    @Test
    void beforeThePlanArrivesTheQueueRowIsNotMarkedReady() {
        setUpHandover();
        assertFalse(queueHead().has("planReady"),
                "计划还没到就不该说「已就绪」—— 那会让用户以为可以无缝了");
    }

    @Test
    void afterThePlanArrivesTheQueueRowIsMarkedReady() {
        long entryId = setUpHandover();
        room.reportPlan(owner, entryId, plan(172_000L, 9_000L, 0L));

        JsonObject head = queueHead();
        assertTrue(head.has("planReady"), "计划到手后队首必须带上就绪标记");
        assertTrue(head.get("planReady").getAsBoolean());
    }

    @Test
    void onlyTheHeadRowIsMarkedReady() {
        capable(owner, "model");
        room.join(owner, "房主");
        room.enqueue(owner, "房主", playable("1"), false);
        room.enqueue(owner, "房主", playable("2"), false);
        room.enqueue(owner, "房主", playable("3"), false);
        room.refreshAnalyst();
        room.reportPlan(owner, room.queue().get(0).entryId, plan(172_000L, 9_000L, 0L));

        JsonArray queue = host.last(Proto.S_QUEUE).getAsJsonArray("queue");
        assertTrue(queue.get(0).getAsJsonObject().has("planReady"), "队首是这一份计划的目标");
        assertFalse(queue.get(1).getAsJsonObject().has("planReady"),
                "第二首还没有计划 —— 计划只描述「怎么进入队首那一首」");
    }

    /**
     * 计划一到位就要补一次歌单广播。
     *
     * <p>不补的话，客户端要等到下一次歌单变化（可能是几十秒后的换歌）才会看到标记，
     * 「算完了立刻展示」就无从谈起。</p>
     */
    @Test
    void thePlanArrivalItselfTriggersAQueueBroadcast() {
        long entryId = setUpHandover();
        host.clear();

        room.reportPlan(owner, entryId, plan(172_000L, 9_000L, 0L));

        assertTrue(host.count(Proto.S_QUEUE) > 0,
                "计划到手必须补一次歌单广播，否则标记要等到下次换歌才显示");
    }

    /**
     * 同一份计划被重复上报时不再重复广播。
     *
     * <p>上报本来就可能重试（网络抖动），而每次广播都会让全房重跑一遍预置 —— 白烧 CPU。</p>
     */
    @Test
    void reportingTheSamePlanTwiceDoesNotRebroadcast() {
        long entryId = setUpHandover();
        JsonObject same = plan(172_000L, 9_000L, 0L);
        room.reportPlan(owner, entryId, same);
        host.clear();

        room.reportPlan(owner, entryId, plan(172_000L, 9_000L, 0L));

        assertEquals(0, host.count(Proto.S_QUEUE), "内容一样的计划不必再广播一次");
    }

    /** 换到下一首之后，标记必须跟着走 —— 新的队首是新的目标，还没有计划。 */
    @Test
    void theMarkerMovesWithTheQueue() {
        capable(owner, "model");
        room.join(owner, "房主");
        room.enqueue(owner, "房主", playable("1"), false);
        room.enqueue(owner, "房主", playable("2"), false);
        room.enqueue(owner, "房主", playable("3"), false);
        room.refreshAnalyst();
        room.reportPlan(owner, room.queue().get(0).entryId, plan(172_000L, 9_000L, 0L));
        assertTrue(queueHead().has("planReady"), "测试前提：当前队首已就绪");

        room.next(true);   // 自动进下一首
        room.refreshAnalyst();

        assertFalse(queueHead().has("planReady"),
                "换歌之后计划属于上一首的交接，新的队首还没算过");
    }
}
