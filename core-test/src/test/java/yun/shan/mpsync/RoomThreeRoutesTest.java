package yun.shan.mpsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.presence.PresenceRegistry;
import yun.shan.mpsync.room.QueueEntry;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.support.FakePlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「点的歌到底交给谁」这三条路的端到端场景。
 *
 * <p>玩家看到的现象只有一个：非会员点了一首要会员的歌，房间里明明有能用的人，歌却放不出来。
 * 拆开是三条互不相同的路，每一条都要在<b>服务端这一侧</b>钉住，因为客户端只要少收一帧就只会沉默：</p>
 *
 * <ol>
 *   <li><b>同平台代解析</b> —— MP 玩家点的歌，装了 LX 自定义源的 Mu 替它解出同一平台那条链；</li>
 *   <li><b>会员应答</b> —— 房间里真会员先被问到，且非会员答出的试听片段顶不上去；</li>
 *   <li><b>跨平台兜底</b> —— 全房都答不上时，服务端把这一格<b>派单</b>给装了跨平台流水线的机器，
 *       它换到别的平台的同一首歌并用 {@code track_replace} 交回来，房间要<b>真的出声</b>。</li>
 * </ol>
 *
 * <p>第 3 条这次专门补了一个坑：{@code track_replace} 原先无条件把正在播的那一格丢回「等链接」，
 * 于是带着可用直链的答卷反而让房间重新挂起，等满总闸被跳过 —— 换源明明成功了，玩家听到的是安静。
 * 下面几条 {@code isPlaying()} 断言钉的就是这一手。</p>
 */
class RoomThreeRoutesTest {

    /** 分层阶梯的层号：与服务端 Room 的 ASK_* 一致，这里是对外契约的一部分，写死才测得出漂移。 */
    private static final int TIER_MEMBER = 1, TIER_CONTRIBUTED_LX = 2;

    private static final String URL = "https://m8c.music.126.net/full.mp3";
    private static final String OTHER_URL = "https://m8c.music.126.net/script.mp3";
    private static final String KUGOU_URL = "https://fs-web.kugou.com/audio.mp3";

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

    // ── 组帧与小工具 ──────────────────────────────────────────────────────────

    private static JsonArray arr(String... tokens) {
        JsonArray a = new JsonArray();
        for (String t : tokens) a.add(t);
        return a;
    }

    private void update(UUID who, Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = (String) kv[i];
            Object v = kv[i + 1];
            if (v instanceof com.google.gson.JsonElement je) o.add(k, je);
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

    /** 一条没有直链的曲目：入队即卡「等链接」，把 {@code url_needed} 分层完整走一遍。 */
    private Track urlless(String songId) {
        Track t = new Track("netease", songId);
        t.name = "会员曲" + songId;
        t.durationMs = 240_000L;
        return t;
    }

    /** {@code track_replace} 的答卷：换到酷狗的同名歌，并且带着一条已经解出来的直链。 */
    private static JsonObject replacementFrame(String url, long servedMs, boolean estimated) {
        JsonObject o = new JsonObject();
        o.addProperty("source", "kugou");
        o.addProperty("songId", "hash9");
        o.addProperty("name", "会员曲9");
        o.addProperty("artist", "歌手");
        o.addProperty("duration", 240_000L);
        if (url != null) {
            o.addProperty("url", url);
            o.addProperty("urlExpiresAt", System.currentTimeMillis() + 5 * 60_000L);
        }
        if (servedMs > 0L) o.addProperty("urlDuration", servedMs);
        if (estimated) o.addProperty("urlDurationEstimated", true);
        return o;
    }

    private void tiersExpireInstantly() {
        cfg.urlTierTimeoutMs = 1L;
    }

    /** 窗口长到不可能自然到点：只有「答上了」或「弃权」才推进，用来把分层单独钉住。 */
    private void tiersNeverExpire() {
        cfg.urlTierTimeoutMs = 600_000L;
        cfg.switchRescueWindowMs = 600_000L;
    }

    private List<FakePlatform.Sent> frames(String op) {
        List<FakePlatform.Sent> out = new ArrayList<>();
        for (FakePlatform.Sent s : host.sent) if (op.equals(s.op())) out.add(s);
        return out;
    }

    private List<UUID> asked() {
        List<UUID> out = new ArrayList<>();
        for (FakePlatform.Sent s : frames(Proto.S_URL_NEEDED)) out.add(s.to());
        return out;
    }

    private List<JsonObject> asks() {
        List<JsonObject> out = new ArrayList<>();
        for (FakePlatform.Sent s : frames(Proto.S_URL_NEEDED)) out.add(s.frame());
        return out;
    }

    private List<JsonObject> rescues() {
        List<JsonObject> out = new ArrayList<>();
        for (FakePlatform.Sent s : frames(Proto.S_SWITCH_RESCUE)) out.add(s.frame());
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

    /** 把分层一路跑到落空（六层各等一个窗），停在「该派单了」的那一刻。 */
    private void runTiersToExhaustion() {
        for (int i = 0; i < 12; i++) tickTiers();
    }

    // ── 路①：同平台代解析（Mu 的 LX 脚本替 MP 玩家解同一平台）────────────────

    @Test
    void lxContributorResolvesForTheRequesterAndTheRoomActuallyPlays() {
        UUID lx = inRoom("Mu装了脚本");
        update(lx, "vip", arr(), "contributeVip", false,
                "member", arr(), "resolvable", arr("netease"),
                "viaLx", arr("netease"), "contributeLx", true);
        tiersNeverExpire();
        host.clear();

        QueueEntry entry = room.enqueue(owner, "MP玩家", urlless("9"), false);
        assertFalse(room.isPlaying(), "手里没有可用直链时房间挂在等链上，而不是硬推一条死的");

        List<JsonObject> asks = asks();
        assertEquals(1, asks.size(), "真会员那一层没人，首发直接落在贡献 LX");
        JsonObject ask = asks.get(0);
        assertEquals(lx, frames(Proto.S_URL_NEEDED).get(0).to());
        assertEquals(TIER_CONTRIBUTED_LX, ask.get("tier").getAsInt());
        assertEquals(entry.entryId, ask.get("entryId").getAsLong(), "答卷要能对上号");
        assertFalse(ask.get("mine").getAsBoolean(), "被问的不是自己的歌，客户端据此换提示文案");
        assertTrue(ask.get("deadlineMs").getAsLong() > System.currentTimeMillis());

        host.clear();
        // 脚本不报「这条链接实际服务多长」，客户端估一个并声明是估算的（estimated=true 走松一档的阈值）。
        room.supplyUrl(lx, entry.entryId, "netease", "9", URL,
                System.currentTimeMillis() + 5 * 60_000L, 240_000L, true);

        assertTrue(room.isPlaying(), "LX 贡献者的答卷必须让房间真的出声");
        assertEquals(URL, host.last(Proto.S_PLAY).getAsJsonObject("track").get("url").getAsString());
        assertEquals(QueueEntry.ASK_IDLE, entry.askTier, "采纳之后分层收口");
        assertEquals(TIER_CONTRIBUTED_LX, entry.adoptedTier);
        room.tick();
        assertTrue(asks().isEmpty(), "有了链接就不要再问任何一层，也不要再抽别人的脚本额度");
    }

    // ── 路②：会员应答（试听片段顶不上去）──────────────────────────────────────

    @Test
    void memberIsAskedFirstAndItsTrialClipCannotBecomeTheRoomsLink() {
        UUID member = inRoom("真会员");
        UUID lx = inRoom("Mu装了脚本");
        update(member, "vip", arr("netease"), "contributeVip", true,
                "member", arr("netease"), "resolvable", arr("netease"), "viaLx", arr());
        update(lx, "vip", arr(), "contributeVip", false,
                "member", arr(), "resolvable", arr("netease"),
                "viaLx", arr("netease"), "contributeLx", true);
        tiersNeverExpire();
        host.clear();

        QueueEntry entry = room.enqueue(owner, "MP玩家", urlless("9"), false);
        assertEquals(List.of(member), asked(), "第一发只问真会员那一档");
        assertEquals(TIER_MEMBER, asks().get(0).get("tier").getAsInt());
        long entryId = entry.entryId;
        long expires = System.currentTimeMillis() + 5 * 60_000L;

        // 会员账号当场只解到 30 秒（掉登录、风控降级都会这样）：整房共用一条链接，拒收。
        host.clear();
        room.supplyUrl(member, entryId, "netease", "9", URL, expires, 30_000L, false);
        assertFalse(room.isPlaying(), "试听片段不能顶替完整版");
        assertEquals(QueueEntry.ADOPTED_NONE, entry.adoptedTier);
        assertEquals(TIER_MEMBER, entry.askTier, "拒收之后这一层还开着，别人照样能答");

        host.clear();
        room.supplyUrl(member, entryId, "netease", "9", URL, expires, 240_000L, false);
        assertTrue(room.isPlaying(), "完整版一到手就起播");
        assertEquals(TIER_MEMBER, entry.adoptedTier);

        // 迟到的 LX 答卷（层号更差）顶不掉会员那一条。
        host.clear();
        room.supplyUrl(lx, entryId, "netease", "9", OTHER_URL, expires, 240_000L, true);
        assertEquals(URL, room.current().track.url, "低可信层的迟到答案不许覆盖");
        assertTrue(asks().isEmpty());
    }

    // ── 路③：跨平台兜底（派单 → track_replace 带链 → 真的起播）───────────────

    @Test
    void nobodyCanResolveSoTheRoomDelegatesAndTheReplacementWithAUrlStartsPlayback() {
        UUID mp = owner;                       // 点歌人：modernMP，既没脚本也没跨平台流水线
        UUID mu = inRoom("Mu有流水线");
        update(mp, "vip", arr("netease"), "contributeVip", false,
                "member", arr(), "resolvable", arr(), "viaLx", arr());
        update(mu, "vip", arr(), "contributeVip", false, "member", arr(),
                "resolvable", arr("kugou", "qq"), "viaLx", arr(), "switchRescue", true);
        tiersExpireInstantly();
        host.clear();

        QueueEntry entry = room.enqueue(mp, "MP玩家", urlless("9"), false);
        runTiersToExhaustion();

        List<JsonObject> rescues = rescues();
        assertEquals(1, rescues.size(), "全层落空只派一单，且只派给报了跨平台流水线的那台");
        JsonObject r = rescues.get(0);
        assertEquals(mu, frames(Proto.S_SWITCH_RESCUE).get(0).to());
        assertEquals(entry.entryId, r.get("entryId").getAsLong());
        assertEquals(1, r.get("attempt").getAsInt(), "attempt 数的是第几台，从 1 开始");
        assertFalse(r.get("mine").getAsBoolean(), "被派的是别人的歌");
        assertEquals(mu, entry.rescueTarget);

        host.clear();
        // 那台跑完流水线，换到酷狗的同名歌，并且带着已经解出来、验证过的直链。
        assertNull(room.replaceTrack(mu, entry.entryId,
                replacementFrame(KUGOU_URL, 236_000L, true)));
        assertEquals("kugou", room.current().track.source);
        assertTrue(room.isPlaying(), "带着可用直链的替换帧要当场出声 —— 这一手以前会把房间重新挂成等链");
        JsonObject play = host.last(Proto.S_PLAY);
        assertNotNull(play);
        assertEquals(KUGOU_URL, play.getAsJsonObject("track").get("url").getAsString());
        assertTrue(asks().isEmpty(), "有了链接就不要再问一遍分层");
        assertNull(entry.rescueTarget, "答卷到手，派单收口");
    }

    @Test
    void aDispatchedMachineThatGivesUpIsReplacedImmediatelyAndBystandersCannotReplace() {
        UUID wide = inRoom("Mu多渠道");
        UUID narrow = inRoom("Mu单渠道");
        UUID bystander = inRoom("围观的");
        update(wide, "resolvable", arr("kugou", "qq", "bilibili"), "switchRescue", true);
        update(narrow, "resolvable", arr("kugou"), "switchRescue", true);
        tiersExpireInstantly();
        host.clear();

        QueueEntry entry = room.enqueue(owner, "MP玩家", urlless("9"), false);
        runTiersToExhaustion();
        assertEquals(List.of(wide), recipients(Proto.S_SWITCH_RESCUE),
                "先派本机能出完整版的平台多的那台，而不是先进房的那台");
        assertFalse(recipients(Proto.S_SWITCH_RESCUE).contains(bystander),
                "没有跨平台流水线的人收不到派单");

        JsonObject e = rescues().get(0);
        // 没被派到的人发弃权：对不上派单，直接丢掉（迟到的、或者乱发的都不能改派）。
        room.switchDecline(bystander, entry.entryId, "乱发");
        assertEquals(1, rescues().size(), "不是当前执行者的弃权不产生改派");
        // 没被派到的人也不能替换队列那一格 —— 判权跟着派单走。
        String refusal = room.replaceTrack(bystander, entry.entryId, replacementFrame(KUGOU_URL, 240_000L, true));
        assertNotNull(refusal, "围观成员不许换掉别人点的歌");
        assertEquals("netease", room.current().track.source, "被拒绝的替换不能已经生效");

        host.clear();
        room.switchDecline(wide, entry.entryId, "没搜到");
        List<JsonObject> next = rescues();
        assertEquals(1, next.size(), "当场说换不到就立刻改派，不用等满窗口");
        assertEquals(narrow, frames(Proto.S_SWITCH_RESCUE).get(0).to());
        assertEquals(2, next.get(0).get("attempt").getAsInt(), "改派之后是第二台");
        assertEquals(narrow, entry.rescueTarget);
    }

    @Test
    void aSilentExecutorIsReplacedWhenTheWindowRunsOut() throws InterruptedException {
        UUID first = inRoom("第一台");
        UUID second = inRoom("第二台");
        update(first, "resolvable", arr("kugou", "qq"), "switchRescue", true);
        update(second, "resolvable", arr("kugou", "qq"), "switchRescue", true);
        cfg.switchRescueWindowMs = 250L;
        cfg.urlTierTimeoutMs = 1L;
        host.clear();

        QueueEntry entry = room.enqueue(owner, "MP玩家", urlless("9"), false);
        for (int i = 0; i < 6; i++) tickTiers();
        assertEquals(List.of(first), recipients(Proto.S_SWITCH_RESCUE), "同分按加入顺序");

        host.clear();
        tickTiers();                       // 窗还没走完：不许改派
        assertTrue(rescues().isEmpty());
        Thread.sleep(320L);
        room.tick();
        assertEquals(List.of(second), recipients(Proto.S_SWITCH_RESCUE), "窗口走完换下一台");
        assertEquals(2, rescues().get(0).get("attempt").getAsInt(), "改派之后是第二台");
    }

    @Test
    void dispatchStopsAtTheConfiguredMachineCap() {
        UUID a = inRoom("甲");
        UUID b = inRoom("乙");
        UUID c = inRoom("丙");
        update(a, "resolvable", arr("kugou"), "switchRescue", true);
        update(b, "resolvable", arr("kugou"), "switchRescue", true);
        update(c, "resolvable", arr("kugou"), "switchRescue", true);
        cfg.switchRescueAttempts = 2;
        cfg.urlTierTimeoutMs = 1L;
        cfg.switchRescueWindowMs = 600_000L;
        host.clear();

        QueueEntry entry = room.enqueue(owner, "MP玩家", urlless("9"), false);
        runTiersToExhaustion();
        room.switchDecline(entry.rescueTarget, entry.entryId, "没搜到");
        assertTrue(entry.rescueTarget != null, "第二台照常派出去");
        room.switchDecline(entry.rescueTarget, entry.entryId, "没搜到");
        assertNull(entry.rescueTarget, "问满 switchRescueAttempts 台就收手：卡住的时长 = 台数 × 窗口");
        assertEquals(2, rescues().size(), "配置封顶之后不许再派第三台");
        assertTrue(host.chat.stream().anyMatch(s -> s.contains("2")), "提示要说清问过几台");
    }

    @Test
    void theRequesterOwnsTheFrameWhenHeIsAlsoTheBestExecutor() {
        // 点歌人自己就是那台装了流水线的 Mu（换源开着、别人都不行）：这一格是他自己的歌，
        // 客户端拿 mine 决定提示文案与「要不要问本机换源」，所以这一位必须由服务端说清楚。
        update(owner, "resolvable", arr("kugou"), "switchRescue", true);
        cfg.urlTierTimeoutMs = 1L;
        cfg.switchRescueWindowMs = 600_000L;
        host.clear();

        QueueEntry entry = room.enqueue(owner, "房主", urlless("9"), false);
        runTiersToExhaustion();
        assertEquals(List.of(owner), recipients(Proto.S_SWITCH_RESCUE), "点歌人自己的机器先上");
        assertTrue(rescues().get(0).get("mine").getAsBoolean());
    }

    private List<UUID> recipients(String op) {
        List<UUID> out = new ArrayList<>();
        for (FakePlatform.Sent s : frames(op)) out.add(s.to());
        return out;
    }
}
