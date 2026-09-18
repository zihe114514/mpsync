package yun.shan.mpsync;

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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交接计划的<b>分析者指派</b>：谁点的歌就用谁的电脑先算，点歌人算不了再借别人的。
 *
 * <p>为什么需要这一层：算力落在谁手上跟「谁是房主」没关系 —— 取决于那台机器有没有这套代码、
 * 有没有装模型、音频读不读得到。把分析永远钉在房主那台上会让三种情况白瞎：房主是个没模型的
 * 客户端、房主压根是 modernMP（它没有分析这个概念）、而房间里明明有人装了模型闲着。
 * 所以服务端改成<b>指派</b>：点歌人 → 房主 → 房间里任何自报能算的人。</p>
 *
 * <p>这里钉的就是这套选人规则，以及它的每一种退路：没人能算时<b>不做假交叠</b>（服务端到点
 * 换歌，与旧版一致），被指派人拒绝时立刻改派，算完上报后指派结束。</p>
 */
class RoomAutomixAssignTest {

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

    /** 给某个人报上分析能力（客户端 profile 里的那两个字段）。 */
    private void capable(UUID uuid, String engine) {
        JsonObject p = new JsonObject();
        p.addProperty("automixCapable", true);
        p.addProperty("automixEngine", engine);
        presence.update(uuid, p);
    }

    /** 一个不能分析的人（modernMP、老客户端、或没装模型又不肯跑 DSP 的那种）。 */
    private void incapable(UUID uuid) {
        JsonObject p = new JsonObject();
        p.addProperty("automixCapable", false);
        p.addProperty("automixEngine", "");
        presence.update(uuid, p);
    }

    /** 房主放歌、另一个成员点了一首歌排在他后面；返回那一首的条目号。 */
    private long ownerPlayingThenMemberQueues(UUID member, String memberName) {
        room.join(member, memberName);
        room.enqueue(owner, "房主", playable("1"), false);
        assertNotNull(room.current(), "测试前提：第一首在放");
        room.enqueue(member, memberName, playable("2"), false);
        assertEquals(1, room.queue().size(), "测试前提：队列里正好一首「下一首」");
        return room.queue().get(0).entryId;
    }

    /**
     * 一个在房间里、能分析、但不点歌也不当房主的成员。
     *
     * <p>这正是「借用别人的电脑」要借的那台：他自报了模型能力位，静静等着被指派。</p>
     */
    private UUID idleHelperInRoom(String name) {
        UUID helper = host.addPlayer(name);
        room.join(helper, name);
        return helper;
    }

    /**
     * 某个 op 广播了几<b>次</b>。
     *
     * <p>不能用 {@code host.count(op)}：那是「帧数」，而房间里的广播是逐人下发的 ——
     * 这个类里房间有两个人，一次广播就会记两帧。这里按「同一时刻发给所有人的那一组」折算。</p>
     */
    private int broadcasts(String op) {
        return host.frames(op).size() / Math.max(1, room.members().size());
    }

    /** 这一帧是发给谁的分析指派，没有则 null。 */
    private FakePlatform.Sent assignFrame() {
        List<FakePlatform.Sent> all = host.sent.stream()
                .filter(s -> Proto.S_ANALYZE_ASSIGN.equals(s.op())).toList();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private JsonObject plan(long fire, long overlap, long cue) {
        JsonObject o = new JsonObject();
        o.addProperty("fireMillis", fire);
        o.addProperty("overlapMillis", overlap);
        o.addProperty("cueMillis", cue);
        o.addProperty("summary", "test plan");
        return o;
    }

    // ── 优先级：点歌人先算 ────────────────────────────────────────────────────

    @Test
    void theRequesterIsAskedFirst() {
        UUID member = host.addPlayer("点歌的人");
        capable(member, "model");
        capable(owner, "model");
        long entryId = ownerPlayingThenMemberQueues(member, "点歌的人");
        host.clear();

        room.refreshAnalyst();

        FakePlatform.Sent assign = assignFrame();
        assertNotNull(assign, "房间里有点歌人且他能算，就该指派给他");
        assertEquals(member, assign.to(), "谁点的歌就用谁的电脑先算");
        assertEquals(entryId, assign.frame().get("entryId").getAsLong(),
                "指派里必须说清算的是哪一首「下一首」");
    }

    @Test
    void theAssignFrameCarriesBothTracksSoTheAnalystCanReadAudio() {
        UUID member = host.addPlayer("点歌的人");
        capable(member, "model");
        ownerPlayingThenMemberQueues(member, "点歌的人");
        host.clear();

        room.refreshAnalyst();

        JsonObject frame = assignFrame().frame();
        JsonObject cur = frame.getAsJsonObject("current");
        JsonObject next = frame.getAsJsonObject("next");
        assertNotNull(cur, "分析要读正在播的那一首，所以它的直链必须一起给过去");
        assertNotNull(next, "下一首同理 —— 两首歌的 PCM 都要能拿到");
        assertTrue(cur.get("url").getAsString().startsWith("http"),
                "给过去的必须是能直接取流的直链，不能是纯元数据");
        assertTrue(next.get("url").getAsString().startsWith("http"),
                "下一首也要带直链，否则借来的那台机器根本读不到音频");
    }

    // ── 退路：点歌人算不了就借别人的 ──────────────────────────────────────────

    @Test
    void theOwnerIsBorrowedWhenTheRequesterCannotAnalyze() {
        UUID member = host.addPlayer("点歌的人");
        incapable(member);                 // 点歌人是个没有分析能力的客户端
        capable(owner, "model");
        ownerPlayingThenMemberQueues(member, "点歌的人");
        host.clear();

        room.refreshAnalyst();

        FakePlatform.Sent assign = assignFrame();
        assertNotNull(assign, "点歌人算不了，就该借房主那台");
        assertEquals(owner, assign.to());
    }

    @Test
    void anyCapableMemberCanBeBorrowedWhenNeitherTheRequesterNorTheOwnerCan() {
        UUID requester = host.addPlayer("点歌的人");
        UUID helper = idleHelperInRoom("装着模型的人");
        incapable(requester);
        incapable(owner);                  // 房主是 modernMP 之类：它没有分析能力
        capable(helper, "model");
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        host.clear();

        room.refreshAnalyst();

        FakePlatform.Sent assign = assignFrame();
        assertNotNull(assign, "房主算不了时，房间里任何一个能算的人都该被借到");
        assertEquals(helper, assign.to(), "modernMP 当房主时，分析要落在装着模型的那台上");
    }

    @Test
    void aModelCapableHelperBeatsAPlainDspOne() {
        UUID requester = host.addPlayer("点歌的人");
        UUID dsp = idleHelperInRoom("只有内置信号处理");
        UUID model = idleHelperInRoom("装着模型的人");
        incapable(requester);
        incapable(owner);
        capable(dsp, "dsp");
        capable(model, "model");
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        host.clear();

        room.refreshAnalyst();

        assertEquals(model, assignFrame().to(),
                "同样能算时先问档位高的：装了模型的能带来 stem 交接与过渡族");
    }

    // ── 没人能算：不做假交叠 ──────────────────────────────────────────────────

    @Test
    void whenNobodyCanAnalyzeThereIsNoAssignmentAtAll() {
        UUID requester = host.addPlayer("点歌的人");
        incapable(requester);
        incapable(owner);
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        host.clear();

        room.refreshAnalyst();

        assertNull(assignFrame(), "一台能算的都没有，就不该派活");
        JsonObject state = host.last(Proto.S_ANALYZE_STATE);
        assertNotNull(state, "但要说清「这一首没人算」，客户端据此不再干等");
        assertEquals("", state.get("analyst").getAsString());
    }

    @Test
    void withoutAnAnalystTheRoomStillHandsOverOnTheConfiguredLead() {
        UUID requester = host.addPlayer("点歌的人");
        incapable(requester);
        incapable(owner);
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        host.clear();

        room.seek(DURATION - 3_500L);      // 默认提前量 4000ms
        room.tick();

        assertEquals(1, broadcasts(Proto.S_PLAY),
                "没人能算时绝不做假交叠：服务端到点换歌，行为与旧版一致");
    }

    // ── 拒绝与超时：立刻改派 ──────────────────────────────────────────────────

    @Test
    void aDeclineReassignsImmediatelyInsteadOfWaitingForTheTimeout() {
        UUID requester = host.addPlayer("点歌的人");
        UUID helper = idleHelperInRoom("装着模型的人");
        capable(requester, "model");
        capable(helper, "model");
        capable(owner, "model");
        long entryId = ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        assertEquals(requester, assignFrame().to(), "测试前提：先问的是点歌人");
        host.clear();

        // 点歌人明确说「我算不了」（读不到音频、曲目太短、模型不可用）。
        room.declinePlan(requester, entryId);

        FakePlatform.Sent assign = assignFrame();
        assertNotNull(assign, "拒绝是明确信号，当场就该改派，不必干等到超时");
        assertEquals(owner, assign.to(),
                "改派按优先级给下一台：点歌人放弃后轮到房主，而不是再问一次刚拒绝的那个人");
        assertNotEquals(requester, assign.to(), "绝不能把活又派回刚拒绝的那台");
    }

    /**
     * 连点歌人带房主都不能算时，拒绝要能一路退到「借别人的电脑」那一层。
     */
    @Test
    void decliningTwiceFallsThroughToTheBorrowedMachine() {
        UUID requester = host.addPlayer("点歌的人");
        UUID helper = idleHelperInRoom("装着模型的人");
        capable(requester, "model");
        capable(helper, "model");
        incapable(owner);                  // 房主算不了
        long entryId = ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        assertEquals(requester, assignFrame().to(), "测试前提：先问的是点歌人");
        host.clear();
        room.declinePlan(requester, entryId);

        assertEquals(helper, assignFrame().to(),
                "点歌人拒绝、房主又没能力，就该借到房间里那台装了模型的机器上。"
                        + "helper=" + helper + " owner=" + owner + " requester=" + requester
                        + " 实际=" + (assignFrame() == null ? "无" : assignFrame().to()));
    }

    @Test
    void aDeclineFromSomebodyWhoWasNotAskedChangesNothing() {
        UUID requester = host.addPlayer("点歌的人");
        UUID stranger = host.addPlayer("路人");
        capable(requester, "model");
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        host.clear();

        room.declinePlan(stranger, room.queue().get(0).entryId);

        assertNull(assignFrame(), "没被指派的人说「我不干」不作数，别把指派搅乱");
    }

    @Test
    void aTimeoutReassignsToTheNextMachine() {
        UUID requester = host.addPlayer("点歌的人");
        UUID helper = idleHelperInRoom("装着模型的人");
        capable(requester, "model");
        capable(helper, "model");
        capable(owner, "model");
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        assertEquals(requester, assignFrame().to(), "测试前提：先问的是点歌人");
        host.clear();

        // 把时钟往后拨过指派超时：点歌人那台没交卷（机器慢 / 卡住了）。
        cfg.automixAssignTimeoutMs = 0L;
        room.tick();

        FakePlatform.Sent assign = assignFrame();
        assertNotNull(assign, "超时也要改派，不能让一首曲子因为一台慢机器就整首不交叠");
        assertEquals(owner, assign.to(), "改派按优先级给下一台");
        assertNotEquals(requester, assign.to(), "超时过的那台不再问第二遍");
    }

    /**
     * 超时的机器如果又轮回来，等于这首曲子永远等不到计划 —— 所以超时和拒绝一样要拉黑。
     */
    @Test
    void aTimedOutMachineIsNotAskedAgain() {
        UUID requester = host.addPlayer("点歌的人");
        UUID helper = idleHelperInRoom("装着模型的人");
        capable(requester, "model");
        capable(helper, "model");
        incapable(owner);
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        assertEquals(requester, assignFrame().to(), "测试前提：先问的是点歌人");
        host.clear();

        // 指派已经过去了不少时间（用 0 表示「当场就算超时」，免得依赖墙钟抖动）。
        cfg.automixAssignTimeoutMs = 0L;
        room.tick();

        FakePlatform.Sent assign = assignFrame();
        assertNotNull(assign, "超时要能一路退到能算的那台。帧="
                + host.sent.stream().map(FakePlatform.Sent::op).toList());
        assertEquals(helper, assign.to(), "点歌人超时、房主没能力，就借到装了模型的那台");
        assertNotEquals(requester, assign.to(), "超时过的那台不再问第二遍");
    }

    // ── 交卷 ──────────────────────────────────────────────────────────────────

    @Test
    void anAssignedAnalystWhoIsNotTheOwnerCanHandInThePlan() {
        UUID requester = host.addPlayer("点歌的人");
        capable(requester, "model");
        capable(owner, "model");
        long entryId = ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        assertEquals(requester, assignFrame().to(), "测试前提：指派给了点歌人（他不是房主）");
        host.clear();

        // 被指派者交卷：他不是房主，但这份计划必须被采纳 —— 这正是这次改动的全部意义。
        room.reportPlan(requester, entryId, plan(172_000L, 9_000L, 0L));

        JsonObject carried = host.last(Proto.S_QUEUE).getAsJsonObject("next").getAsJsonObject("plan");
        assertNotNull(carried, "非房主算出来的计划同样要广播给全房");
        assertEquals(172_000L, carried.get("fireMillis").getAsLong());

        room.seek(172_000L);
        room.tick();
        assertEquals(1, broadcasts(Proto.S_PLAY), "换歌点按被指派者算出的那份走");
    }

    @Test
    void afterThePlanArrivesTheAssignmentIsOverAndNobodyElseIsAsked() {
        UUID requester = host.addPlayer("点歌的人");
        capable(requester, "model");
        capable(owner, "model");
        long entryId = ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        room.reportPlan(requester, entryId, plan(172_000L, 9_000L, 0L));
        host.clear();

        room.tick();

        assertNull(assignFrame(), "计划已经到手，就不该再去打扰别人重算一遍");
    }

    /**
     * 计划必须说的是「正在播的这一首的下一首」。
     *
     * <p>队列变过（有人插歌、跳歌）之后，这份计划对应的就是别的曲子了 —— 拿它去交接会把
     * 换歌点安到错误的音频上。</p>
     */
    @Test
    void aPlanForAnotherEntryIsDroppedEvenFromTheAssignedAnalyst() {
        UUID requester = host.addPlayer("点歌的人");
        capable(requester, "model");
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        host.clear();

        room.reportPlan(requester, 999_999L, plan(10_000L, 9_000L, 0L));
        room.seek(10_000L);
        room.tick();

        assertEquals(0, broadcasts(Proto.S_PLAY), "entryId 对不上的计划必须丢弃");
    }

    // ── 能力位晚到 ────────────────────────────────────────────────────────────

    /**
     * 能力位是在 profile 里报上来的，而来得比换歌晚是常态（刚连上、资产还在加载）。
     * 此时必须能补救 —— 否则房间里明明有能算的机器，却每一首都被判成「没人能算」。
     */
    @Test
    void aCapabilityThatArrivesLateStillGetsTheRoomAnAnalyst() {
        UUID requester = host.addPlayer("点歌的人");
        UUID helper = idleHelperInRoom("装着模型的人");
        incapable(requester);
        incapable(owner);
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        host.clear();
        assertNull(assignFrame(), "测试前提：这会儿还没人报能力位");

        capable(helper, "model");      // 他的 profile 现在才到
        room.refreshAnalyst();

        FakePlatform.Sent assign = assignFrame();
        assertNotNull(assign, "能力位晚到也要能补上分析者");
        assertEquals(helper, assign.to());
    }
}
