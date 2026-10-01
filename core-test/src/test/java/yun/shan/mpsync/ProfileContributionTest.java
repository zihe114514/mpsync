package yun.shan.mpsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.presence.ClientProfile;
import yun.shan.mpsync.presence.PresenceRegistry;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.support.FakePlatform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会员 / 完整版可解 / LX 三档能力上报的裁决契约（{@code member} / {@code resolvable} /
 * {@code viaLx} / {@code contributeLx}，随 {@code C_PROFILE} 与 {@code C_HELLO} 上报）。
 *
 * <p>这次改动修的是一个具体的假信号：从前「贡献 VIP」的判据是「愿意 + 登录了该平台」，而
 * {@code vip} 的真实语义只是<b>已登录</b> —— 只登录不是会员的人会举旗当贡献者，然后答出
 * 一条试听片段，把整首歌截短给全房。新判据把他剥出贡献层，只留在「装了该平台」的兜底层；
 * 同时开出「贡献 LX」一层（愿意且只能靠 LX 自定义源解出的平台），排在 VIP 之后 ——
 * 本家会员的直链更稳，也不动第三方接口额度。</p>
 *
 * <p>另一条必须钉死的是<b>老客户端不许被误伤</b>：「报了空数组」（真没有）和「压根不发这
 * 几个字段」（老版本）在线报文里都读成空，判据必须能区分这两者，否则升级服务端等于把所有
 * 没升级客户端的贡献者资格悄悄清零 —— 而这在编译和现有测试上都没有痕迹。</p>
 */
class ProfileContributionTest {

    private FakePlatform host;
    private MpConfig cfg;
    private PresenceRegistry pr;
    private Room room;
    private UUID owner;

    @BeforeEach
    void setUp() {
        host = new FakePlatform();
        Net.bind(host);
        cfg = new MpConfig();
        pr = new PresenceRegistry();
        owner = host.addPlayer("房主");
        room = new Room(cfg, "r1", "ABCDE", owner, "房主", "测试房");
        room.setPresence(pr);
    }

    // ── 组装上报帧的小工具 ────────────────────────────────────────────────────

    private static JsonArray arr(String... tokens) {
        JsonArray a = new JsonArray();
        for (String t : tokens) a.add(t);
        return a;
    }

    /** 吸收一帧档案；kv 按「键」，值 成对给出（值可以是 String / boolean / JsonElement）。 */
    private void update(UUID who, Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = (String) kv[i];
            Object v = kv[i + 1];
            if (v instanceof JsonElement je) o.add(k, je);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else o.addProperty(k, String.valueOf(v));
        }
        pr.update(who, o);
    }

    private UUID inRoom(String name) {
        UUID u = host.addPlayer(name);
        room.join(u, name);
        return u;
    }

    /** 一条没有直链的曲目：入队即卡「等链接」，把 S_URL_NEEDED 分层完整走一遍。 */
    private Track urlless(String songId) {
        Track t = new Track("netease", songId);
        t.name = "会员曲" + songId;
        t.durationMs = 240_000L;
        return t;
    }

    // ── 核心修复：只登录、非会员、无 LX 不再是贡献者 ──────────────────────────

    @Test
    void loggedInNonMemberWithoutLxIsNoLongerAContributor() {
        UUID m = inRoom("登录党");
        update(m, "client", "muonium",
                "vip", arr("netease"), "contributeVip", true,
                "member", arr(), "resolvable", arr(), "viaLx", arr(), "contributeLx", false);

        // 旧判据是「contributeVip && canResolve」。他两条都满足 —— 断言这一点，
        // 就证明了 contributes=false 不是数据没喂够，而是判据真的换了。
        assertTrue(pr.canResolve(m, "netease"), "登录了该平台，兜底层该有他");
        assertTrue(cfg.preferContributors);
        assertFalse(pr.contributes(m, "netease"),
                "只登录不是会员、又没有 LX 路：他会答出试听片段，不该举旗当贡献者");
        assertFalse(pr.contributesVip(m, "netease"));
        assertFalse(pr.contributesLx(m, "netease"));
    }

    @Test
    void trueMemberAndVipFreePlatformStillContribute() {
        UUID mem = inRoom("真会员");
        update(mem, "vip", arr("netease"), "contributeVip", true,
                "member", arr("netease"), "resolvable", arr("netease"), "viaLx", arr());
        assertTrue(pr.contributesVip(mem, "netease"));

        // 「这个平台本来不卖会员」那一路：能登录就给完整版，resolvable 有而 member 空。
        UUID free = inRoom("b站用户");
        update(free, "vip", arr("bilibili"), "contributeVip", true,
                "member", arr(), "resolvable", arr("bilibili"), "viaLx", arr());
        assertTrue(pr.contributesVip(free, "bilibili"),
                "不卖会员的平台，登录即完整版，该算 VIP 贡献档");
    }

    @Test
    void lxOnlyPlatformCountsAsContributionOnlyWhileWilling() {
        UUID lx = inRoom("LX党");
        update(lx, "vip", arr("netease"), "contributeVip", true,
                "member", arr(), "resolvable", arr("netease"),
                "viaLx", arr("netease"), "contributeLx", true);
        assertFalse(pr.contributesVip(lx, "netease"), "只有 LX 解得出的平台不进 VIP 档");
        assertTrue(pr.contributesLx(lx, "netease"));
        assertTrue(pr.contributes(lx, "netease"));

        // 关掉 contributeLx：贡献资格随之消失 —— 它和 contributeVip 完全独立，
        // 后者为真也救不回来（member 没有、resolvable 又被 viaLx 排除）。
        update(lx, "contributeLx", false);
        assertFalse(pr.contributes(lx, "netease"),
                "不愿意动用 LX 脚本的人不该被问，哪怕他 resolvable 里有这个平台");
    }

    // ── 老客户端：不发新字段时，判据必须与改动前完全一致 ──────────────────────

    @Test
    void legacyClientKeepsThePreChangeVerdicts() {
        UUID old = inRoom("老客户端");
        update(old, "client", "melodify", "modVersion", "1.9.9",
                "vip", arr("netease"), "contributeVip", true);
        assertTrue(pr.canResolve(old, "netease"));
        assertTrue(pr.contributes(old, "netease"),
                "老客户端只报 vip：贡献判据必须原样退化成改动前的「愿意+登录了该平台」");
        assertFalse(pr.contributesLx(old, "netease"), "没有 viaLx 上报就不可能有 LX 档");
        assertFalse(pr.contributes(old, "qq"), "没报过的平台仍然不算");

        UUID shy = inRoom("老客户端-不愿贡献");
        update(shy, "vip", arr("netease"), "contributeVip", false);
        assertFalse(pr.contributes(shy, "netease"));
        assertTrue(pr.canResolve(shy, "netease"));

        // 后续只带在听字段的帧不得把新数组当成「报了空」——缺字段就是缺字段。
        update(old, "nowPlaying", "海阔天空", "localPlaying", true);
        assertTrue(pr.contributes(old, "netease"), "一帧普通心跳不能清零老客户端的贡献资格");
        ClientProfile p = pr.get(old);
        assertNotNull(p);
        assertEquals("海阔天空", p.nowPlaying);
        assertFalse(p.capabilityKnown, "从头到尾没报过新数组，就不许按新判据走");
    }

    @Test
    void framesOnlyOverwriteWhatTheyActuallyCarry() {
        UUID p = inRoom("分帧上报");
        update(p, "member", arr("netease"), "contributeVip", true);
        update(p, "resolvable", arr("netease", "qq"), "viaLx", arr("qq"),
                "contributeLx", true);
        // 后一帧没带 member，前一帧的值得留着（apply 只覆盖真带来的字段）。
        assertTrue(pr.contributesVip(p, "netease"));
        assertTrue(pr.contributesLx(p, "qq"));
        assertFalse(pr.contributesVip(p, "qq"), "只有 LX 解得出的平台不算 VIP 档");
        assertFalse(pr.canResolve(p, "gd"), "三个集合都没有 → 有希望答上也谈不上");
    }

    // ── requestUrl 分层：一次只问一层，上层落空才往下 ─────────────────────────
    //
    // 改造前这条钉的是「一次全发、顺序即优先级」，而先答先赢会让手快的脚本贡献者顶掉慢一点的
    // 真会员答案。现在顺序变成了<b>层与层之间的串行</b>：第一发只点到真会员，那一层没人答或
    // 全部弃权，才发第二发。入房顺序仍然故意是反的 —— 谁先谁后由分层决定，不由加入先后决定。

    /** 把每层窗口压到 1ms：「等满一层」在测试里就是 tick 一下，不必真睡五秒。 */
    private void tiersExpireInstantly() { cfg.urlTierTimeoutMs = 1L; }

    /** 把每层窗口拉到不可能自然到点，用来单独验「弃权当场推进」这条路。 */
    private void tiersNeverExpire() { cfg.urlTierTimeoutMs = 600_000L; }

    private List<UUID> asked() {
        List<UUID> out = new ArrayList<>();
        for (FakePlatform.Sent s : host.sent) {
            if (Proto.S_URL_NEEDED.equals(s.op())) out.add(s.to());
        }
        return out;
    }

    private List<String> rescues() {
        List<String> out = new ArrayList<>();
        for (FakePlatform.Sent s : host.sent) {
            if (Proto.S_SWITCH_RESCUE.equals(s.op())) out.add(s.to() + ":"
                    + s.frame().get("entryId").getAsLong());
        }
        return out;
    }

    private void tickTiers() {
        try {
            Thread.sleep(6L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        room.tick();
    }

    @Test
    void eachTierIsAskedOnlyAfterThePreviousOneRunsOut() {
        UUID lxGuy = inRoom("LX党");
        UUID poser = inRoom("登录党");   // 只登录、非会员、无 LX —— 上一轮那个假信号本人
        UUID vipGuy = inRoom("真会员");
        update(lxGuy, "vip", arr("netease"), "contributeVip", true,
                "member", arr(), "resolvable", arr("netease"),
                "viaLx", arr("netease"), "contributeLx", true);
        update(poser, "vip", arr("netease"), "contributeVip", true,
                "member", arr(), "resolvable", arr(), "viaLx", arr());
        update(vipGuy, "vip", arr("netease"), "contributeVip", true,
                "member", arr("netease"), "resolvable", arr("netease"), "viaLx", arr());
        // 老客户端只报了 vip：它进不了「真会员」那一层（会员位无从判断，不许猜），
        // 但走 contributesVip 的退化判据落在第 3 层 —— 位置与改动前对它的待遇一致。
        UUID legacyGuy = inRoom("老客户端");
        update(legacyGuy, "vip", arr("netease"), "contributeVip", true);
        tiersExpireInstantly();
        host.clear();

        // 点歌人（房主，没有任何档案）点一条注定要重解的曲子 → 触发分层询问。
        room.enqueue(owner, "房主", urlless("9"), false);
        assertEquals(List.of(vipGuy), asked(), "第一发只点到真会员那一档");

        host.clear();
        tickTiers();
        assertEquals(List.of(lxGuy), asked(), "第二发才是贡献 LX");
        host.clear();
        tickTiers();
        assertEquals(List.of(legacyGuy), asked(),
                "第三发是「贡献 VIP 但不是真会员」：本来不卖会员的平台与查不到档位的登录态");
        host.clear();
        tickTiers();
        assertEquals(List.of(poser), asked(),
                "第五发才是「装了该平台但不保证肯答」；点歌人那一层因为他刚失败过被跳过");
        host.clear();
        tickTiers();
        assertEquals(List.of(owner), asked(), "最后一发广播给全房剩下的人");

        host.clear();
        tickTiers();
        tickTiers();
        assertTrue(asked().isEmpty(), "问完就没有下一层了，不能反复骚扰同一批人");
        assertEquals(List.of(owner + ":" + room.current().entryId), rescues(),
                "全层落空只向点歌人求一次跨平台救援");
        host.clear();
        tickTiers();
        assertTrue(rescues().isEmpty(), "救援也是一次性的");
    }

    @Test
    void theLxTierPrefersTheWiderScriptRegardlessOfJoinOrder() {
        // 单输入框架：一个脚本常常同时吃下几个渠道，两个人各挂一个脚本时
        // 先问谁不该由进房先后决定。多渠道那台后进来，仍然该第一个被问。
        UUID narrow = inRoom("单渠道");
        UUID wide = inRoom("多渠道");
        update(narrow, "vip", arr(), "contributeVip", false,
                "member", arr(), "resolvable", arr("netease"), "viaLx", arr("netease"),
                "contributeLx", true);
        update(wide, "vip", arr(), "contributeVip", false,
                "member", arr(), "resolvable", arr("netease", "qq", "kugou"),
                "viaLx", arr("netease", "qq", "kugou"), "contributeLx", true);
        tiersNeverExpire();
        host.clear();

        // 两个人都不是真会员：第一层没人，首发直接落在「贡献 LX」那一层。
        room.enqueue(owner, "房主", urlless("9"), false);
        assertEquals(List.of(wide, narrow), asked(),
                "同一层内先问覆盖渠道多的那台，而不是先进房的那台");
    }

    @Test
    void aDeclineMovesToTheNextTierWithoutWaitingForTheWindow() {
        UUID lxGuy = inRoom("LX党");
        UUID vipGuy = inRoom("真会员");
        update(lxGuy, "vip", arr("netease"), "contributeVip", true,
                "member", arr(), "resolvable", arr("netease"),
                "viaLx", arr("netease"), "contributeLx", true);
        update(vipGuy, "vip", arr("netease"), "contributeVip", true,
                "member", arr("netease"), "resolvable", arr("netease"), "viaLx", arr());
        tiersNeverExpire();   // 窗口长到不可能自然到点：只有弃权能推进
        host.clear();

        room.enqueue(owner, "房主", urlless("9"), false);
        assertEquals(List.of(vipGuy), asked());
        long entryId = room.current().entryId;

        host.clear();
        room.declineUrl(vipGuy, entryId, 1, "netease", "9", "trial-only");
        assertEquals(List.of(lxGuy), asked(), "会员当场说「只解到试听」，第二发立刻发给 LX 党");

        host.clear();
        room.declineUrl(vipGuy, entryId, 1, "netease", "9", "trial-only");
        assertTrue(asked().isEmpty(), "同一层的重复弃权不再推进");
    }

    @Test
    void aStaleDeclineCannotSkipTheTierStillBeingAsked() {
        UUID lxGuy = inRoom("LX党");
        UUID vipGuy = inRoom("真会员");
        update(lxGuy, "vip", arr("netease"), "contributeVip", true,
                "member", arr(), "resolvable", arr("netease"),
                "viaLx", arr("netease"), "contributeLx", true);
        update(vipGuy, "vip", arr("netease"), "contributeVip", true,
                "member", arr("netease"), "resolvable", arr("netease"), "viaLx", arr());
        tiersNeverExpire();
        host.clear();
        room.enqueue(owner, "房主", urlless("9"), false);
        long entryId = room.current().entryId;

        host.clear();
        // 层 2 的弃权拿到层 1 的窗口里：必须当过期帧丢弃，否则一层白等会被另一层替它决定。
        room.declineUrl(lxGuy, entryId, 2, "netease", "9", "failed");
        assertTrue(asked().isEmpty(), "层号对不上的弃权不许推进");
    }

    @Test
    void estimatedLengthsAreJudgedByTheLooserThreshold() {
        // 声明 240 秒。实测 150 秒（62.5%）按 80% 卡是试听片段；估算 150 秒按 50% 放行 ——
        // CDN 报的尺寸与标称码率都会偏，拿实测的标准卡估算值会把完整版误杀成试听。
        assertEquals(0.8d, cfg.minStreamDurationRatio, "实测阈值是既有的 80%");
        assertEquals(0.5d, cfg.estimatedStreamDurationRatio, "估算阈值默认 50%");
        assertTrue(Track.isTrialClip(150_000L, 240_000L, cfg), "实测 62.5% 算试听片段");
        assertFalse(Track.isTrialClip(150_000L, 240_000L, cfg, true), "同样长度按估算放行");
        assertTrue(Track.isTrialClip(100_000L, 240_000L, cfg, true), "短到 41% 仍然拒");
        assertFalse(Track.isTrialClip(0L, 240_000L, cfg, true), "0 = 未知，照旧放行");
    }

    // ── 成员那一条的能力透出（两套客户端拿它画 chip）─────────────────────────

    @Test
    void memberEntryCarriesAutomixAndTheThreeSourceSets() {
        UUID helper = inRoom("模型机");
        update(helper, "client", "muonium",
                "automixCapable", true, "automixEngine", "model",
                "member", arr("netease"), "resolvable", arr("netease", "bilibili"),
                "viaLx", arr("bilibili"));

        JsonObject state = room.snapshot();
        assertNotNull(state);
        JsonArray members = state.getAsJsonObject("room").getAsJsonArray("members");
        assertEquals(2, members.size());
        JsonObject e = entryOf(members, helper);
        assertTrue(e.get("automixCapable").getAsBoolean());
        assertEquals("model", e.get("automixEngine").getAsString());
        assertEquals(List.of("netease"), strings(e.getAsJsonArray("member")));
        assertEquals(List.of("netease", "bilibili"), strings(e.getAsJsonArray("resolvable")));
        assertEquals(List.of("bilibili"), strings(e.getAsJsonArray("viaLx")));

        // 没上报过的人：字段必须在、且老实是空 —— 空白和「真没有」都得能画出来。
        JsonObject plain = entryOf(members, owner);
        assertFalse(plain.get("automixCapable").getAsBoolean());
        assertEquals("", plain.get("automixEngine").getAsString());
        assertEquals(0, plain.getAsJsonArray("member").size());
        assertEquals(0, plain.getAsJsonArray("resolvable").size());
        assertEquals(0, plain.getAsJsonArray("viaLx").size());
    }

    @Test
    void profileFrameFlowsIntoTheRoomMemberEntryEndToEnd(@TempDir Path dir) {
        // 走真实入口（C_PROFILE → RoomManager → 房间广播）再验一遍形状：
        // 单元级断言挡不住「字段进了档案、但没拼进成员条目」这种半途而废。
        FakePlatform h = new FakePlatform();
        UUID a = h.addPlayer("Alice");
        MpParty.init(h, dir.resolve("mpsync.json"));
        JsonObject p = Proto.msg(Proto.C_PROFILE);
        p.addProperty("client", "muonium");
        p.addProperty("automixCapable", true);
        p.addProperty("automixEngine", "dsp");
        p.add("vip", arr("netease"));
        p.add("member", arr("netease"));
        p.add("resolvable", arr("netease"));
        p.add("viaLx", arr());
        p.addProperty("contributeVip", true);
        p.addProperty("contributeLx", true);
        MpParty.onPacket(a, Proto.encode(p));

        RoomManager mgr = MpParty.manager();
        assertNotNull(mgr);
        mgr.create(a, "房");
        JsonObject state = h.last(Proto.S_ROOM_STATE);
        assertNotNull(state);
        JsonObject me = state.getAsJsonObject("room").getAsJsonArray("members").get(0).getAsJsonObject();
        assertTrue(me.get("automixCapable").getAsBoolean());
        assertEquals("dsp", me.get("automixEngine").getAsString());
        assertEquals(List.of("netease"), strings(me.getAsJsonArray("member")));
        assertEquals(List.of("netease"), strings(me.getAsJsonArray("resolvable")));
        assertEquals(0, me.getAsJsonArray("viaLx").size());
    }

    // ── 小工具 ────────────────────────────────────────────────────────────────

    private static JsonObject entryOf(JsonArray members, UUID uuid) {
        for (JsonElement el : members) {
            JsonObject o = el.getAsJsonObject();
            if (uuid.toString().equals(o.get("uuid").getAsString())) return o;
        }
        throw new AssertionError("成员条目里没有 " + uuid);
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : a) out.add(el.getAsString());
        return out;
    }
}
