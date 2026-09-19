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

    /**
     * 跨过指派超时。
     *
     * <p>超时是个<b>很小的正数</b>（配置里 0 已经改为表示「取消超时」，不能再拿 0 当「当场过期」用了），
     * 睡一小会儿就必定超过它；这比依赖墙钟抖动稳。
     */
    private static void sleepPastTimeout() {
        try {
            Thread.sleep(40L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    // ── 模型优先跨越角色：需求「即便房主也借用装了模型的机器」──────────────────
    //
    // 从前 pickAnalyst 只要点歌人 / 房主自报 capable 就直接选中，engineRank 只在第③档内部比较。
    // modernMP 恒报 capable=true/engine="dsp"，于是没装模型的点歌人 / 房主会压过房里装了模型
    // 的机器 ——「即便房主也借用推理」根本不成立。正确顺序：任意 model 都排在任意 dsp 之前。

    @Test
    void aModelCapableHelperBeatsADspRequester() {
        UUID requester = host.addPlayer("点歌的人");
        UUID model = idleHelperInRoom("装着模型的人");
        capable(requester, "dsp");         // 点歌人只有内置信号处理（例如 modernMP）
        capable(model, "model");           // 房里有台装了模型的机器闲着
        incapable(owner);
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        host.clear();

        room.refreshAnalyst();

        assertEquals(model, assignFrame().to(),
                "点歌人只有 dsp、房里有 model 时，先借装了模型的那台，而不是让点歌人硬算");
    }

    @Test
    void aModelCapableHelperBeatsADspOwner() {
        UUID requester = host.addPlayer("点歌的人");
        UUID model = idleHelperInRoom("装着模型的人");
        incapable(requester);
        capable(owner, "dsp");             // 房主只有内置信号处理
        capable(model, "model");
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        host.clear();

        room.refreshAnalyst();

        assertEquals(model, assignFrame().to(),
                "房主只有 dsp、房里有 model 时，即便房主也要借用装了模型的那台");
    }

    @Test
    void betweenTwoDspMachinesTheRequesterStillWins() {
        UUID requester = host.addPlayer("点歌的人");
        UUID helper = idleHelperInRoom("另一台只有 dsp");
        capable(requester, "dsp");
        capable(helper, "dsp");
        capable(owner, "dsp");
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        host.clear();

        room.refreshAnalyst();

        assertEquals(requester, assignFrame().to(),
                "同档位（都只有 dsp）时角色决定顺序：点歌人先算");
    }

    @Test
    void betweenADspRequesterAndADspOwnerTheRequesterWins() {
        UUID requester = host.addPlayer("点歌的人");
        capable(requester, "dsp");
        capable(owner, "dsp");
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        host.clear();

        room.refreshAnalyst();

        assertEquals(requester, assignFrame().to(),
                "同为 dsp 时点歌人排在房主之前（谁点的歌用谁的电脑）");
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
        // 超时必须是正数才生效（0 = 已取消），所以取一个很小的正数再真的睡过去。
        cfg.automixAssignTimeoutMs = 1L;
        sleepPastTimeout();
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

        // 指派已经过去了不少时间。同样用一个很小的正数超时 + 睡过去（0 现在表示「取消超时」）。
        cfg.automixAssignTimeoutMs = 1L;
        sleepPastTimeout();
        room.tick();

        FakePlatform.Sent assign = assignFrame();
        assertNotNull(assign, "超时要能一路退到能算的那台。帧="
                + host.sent.stream().map(FakePlatform.Sent::op).toList());
        assertEquals(helper, assign.to(), "点歌人超时、房主没能力，就借到装了模型的那台");
        assertNotEquals(requester, assign.to(), "超时过的那台不再问第二遍");
    }

    // ── 超时被取消：0 = 一直等 ──────────────────────────────────────────────

    /**
     * 超时被取消之后，一台慢机器可以一直算下去：服务端不许因为「等太久」就把活儿抢走。
     *
     * <p>这是「取消超时」的全部意义 —— Folia 那套模型推理动辄几十秒，固定超时会在它还没跑完时
     * 就改派，白烧两台的算力，还把算得慢但在算的那台拉进黑名单。</p>
     */
    @Test
    void aCancelledTimeoutLetsTheSlowMachineFinish() {
        UUID requester = host.addPlayer("点歌的人");
        // 房里确实有一台装了模型的机器闲着 —— 没有它这个测试就不成立（那是在测「没人可借」）。
        UUID helper = idleHelperInRoom("装着模型的人");
        capable(requester, "model");
        capable(helper, "model");
        capable(owner, "model");
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        assertEquals(requester, assignFrame().to(), "测试前提：先问的是点歌人");
        host.clear();

        cfg.automixAssignTimeoutMs = 0L;   // 取消超时
        room.tick();
        room.tick();

        assertNull(assignFrame(), "超时取消了就不该再改派：房里的模型机也别抢，让点歌人算完");
    }

    /**
     * 但「取消超时」不等于「取消改派」：被指派人掉线之后必须换人。
     *
     * <p>这条是取消超时的<b>前提条件</b>，不是可选项。超时本来是唯一能兜住「那台机器彻底不响应」的
     * 路径，关掉它就必须把「掉线」单独判一次 —— 否则一台离线机器能把整首歌卡在等计划的状态里，
     * 比不做交叠还糟。注意这里用的是 {@code markOffline}（连接断开、座位保留），不是 {@code remove}
     * （退房/被踢，那条路本来就会重新选人）。</p>
     */
    @Test
    void aDroppedAnalystIsStillReassignedWithTheTimeoutCancelled() {
        UUID requester = host.addPlayer("点歌的人");
        UUID helper = idleHelperInRoom("装着模型的人");
        capable(requester, "model");
        capable(helper, "model");
        incapable(owner);
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        assertEquals(requester, assignFrame().to(), "测试前提：先问的是点歌人");
        host.clear();

        cfg.automixAssignTimeoutMs = 0L;   // 取消超时
        room.markOffline(requester);       // 点歌人那台断线了
        room.tick();

        FakePlatform.Sent assign = assignFrame();
        assertNotNull(assign, "掉线是明确信号，改派不该依赖超时");
        assertEquals(helper, assign.to(), "点歌人掉线、房主没能力，就借到房间里能算的那台");
        assertNotEquals(requester, assign.to(), "掉线的那台不再问");
    }

    /**
     * 被指派者<b>退房</b>（不是掉线）、而房里再没有第二台能算时，必须告诉全房「现在没人算了」。
     *
     * <p>{@code remove} 里若抢先把 {@code analyst} 置 null，{@code assignAnalyzer} 的
     * 「分析者变了才广播」判据就会以为本来就没有分析者，空的 {@code S_ANALYZE_STATE} 发不出去 ——
     * 全房会把已经走掉的人一直显示成「分析中」直到换歌。</p>
     */
    @Test
    void whenTheAssignedAnalystLeavesAndNobodyCanReplaceThemTheRoomIsToldNobodyIsAnalyzing() {
        UUID requester = host.addPlayer("点歌的人");
        UUID helper = idleHelperInRoom("装着模型的人");
        incapable(requester);
        incapable(owner);
        capable(helper, "model");
        ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        assertEquals(helper, assignFrame().to(), "测试前提：房里唯一能算的那台被指派");
        host.clear();

        room.remove(helper);               // 他退房了，房里再没有能算的人

        JsonObject state = host.last(Proto.S_ANALYZE_STATE);
        assertNotNull(state, "唯一分析者退房后必须广播一次，否则界面一直显示他在「分析中」");
        assertEquals("", state.get("analyst").getAsString(), "此刻确实没人在算了");
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
        long fire = DURATION - 9_000L;
        room.reportPlan(requester, entryId, plan(fire, 9_000L, 0L));

        JsonObject carried = host.last(Proto.S_QUEUE).getAsJsonObject("next").getAsJsonObject("plan");
        assertNotNull(carried, "非房主算出来的计划同样要广播给全房");
        assertEquals(fire, carried.get("fireMillis").getAsLong());

        room.seek(fire);
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

    // ── 房主抢跑：指派了别人时房主不能凭房主身份直通 ────────────────────────────
    //
    // mayReportPlan 里有条兼容老客户端的直通「isOwner(actor) → 放行」。它必须加 analyst == null
    // 的前提：服务端已经把活儿派给房里装了模型的另一台时，房主（客户端侧 analyzingHere() =
    // owner && !assignmentSeen，在第一帧 analyze_state 到达前为真）会抢先算完上报，把该借用的
    // 那份计划挤掉。房主自己算不了（capable=false）时，这条直通是它唯一的入口，堵住才有意义。

    @Test
    void theOwnerCannotPreemptThePlanWhenAnalysisIsAssignedElsewhere() {
        UUID requester = host.addPlayer("点歌的人");
        UUID model = idleHelperInRoom("装着模型的人");
        incapable(requester);
        incapable(owner);                  // 房主自己算不了（modernMP / 没模型）
        capable(model, "model");
        long entryId = ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        assertEquals(model, assignFrame().to(), "测试前提：活儿派给了装模型的那台");
        host.clear();

        // 房主抢跑上报（它以为该自己算）—— 必须被拒，指派对象没变。
        room.reportPlan(owner, entryId, plan(172_000L, 9_000L, 0L));

        assertNull(room.current().automixPlan,
                "指派给了别人时，房主不能靠房主身份把自己那份塞进来");
        room.seek(172_000L);
        room.tick();
        assertEquals(0, broadcasts(Proto.S_PLAY), "被拒的计划不能拿来换歌");
    }

    /**
     * 但没有指派（老服务端行为 / 全是老客户端）时，房主直通仍然放行 —— 这条兼容不能误伤。
     */
    @Test
    void theOwnerPassthroughStillWorksWhenNobodyWasAssigned() {
        // 全是老客户端：没有人报过 profile，legacyFallback 生效，指派会落到房主。
        room.join(host.addPlayer("成员"), "成员");
        room.enqueue(owner, "房主", playable("1"), false);
        room.enqueue(owner, "房主", playable("2"), false);
        long entryId = room.queue().get(0).entryId;
        // 老服务端从不指派；这里模拟「还没指派」的窗口：清掉指派再让房主上报。
        room.reportPlan(owner, entryId, plan(172_000L, 9_000L, 0L));

        assertNotNull(room.current().automixPlan,
                "没有指派别人时，房主上报的计划仍要被采纳（兼容老客户端）");
    }

    /**
     * 第③条兜底（「能算的人抢先算完了」）同样必须以「还没指派」为前提。
     *
     * <p>它和房主直通是同一类抢跑：服务端已经把活儿派给了某一台（例如点歌人），另一台<b>也能算</b>
     * 的成员却在第一帧 analyze_state 到达前就算完上报了。若放行，被指派者那份（可能档位更高、
     * 带了 stem 交接）就会被顶掉，「模型优先 + 服务端指派」形同虚设。所以这里也堵死：
     * 已指派给别人时，非被指派者即便自报能算也不得抢报。</p>
     */
    @Test
    void aCapableMemberWhoWasNotAssignedCannotPreemptTheAssignedAnalyst() {
        UUID requester = host.addPlayer("点歌的人");
        UUID helper = idleHelperInRoom("另一台能算的人");
        capable(requester, "model");
        capable(helper, "model");
        incapable(owner);
        long entryId = ownerPlayingThenMemberQueues(requester, "点歌的人");
        room.refreshAnalyst();
        assertEquals(requester, assignFrame().to(), "测试前提：活儿派给了点歌人");
        host.clear();

        // 另一台能算的成员抢跑上报（它没被指派）—— 必须被拒。
        room.reportPlan(helper, entryId, plan(172_000L, 9_000L, 0L));

        assertNull(room.current().automixPlan,
                "已指派给别人时，另一台能算的成员不能靠「我也能算」把自己那份塞进来");
        room.seek(172_000L);
        room.tick();
        assertEquals(0, broadcasts(Proto.S_PLAY), "被拒的计划不能拿来换歌");
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
