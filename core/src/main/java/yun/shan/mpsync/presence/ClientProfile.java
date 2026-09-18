package yun.shan.mpsync.presence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import yun.shan.mpsync.net.Proto;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 一个玩家的客户端自报信息（{@link Proto#C_PROFILE}）。
 *
 * <p>为什么必须由客户端自报、服务端不能自己查：这三件事全在客户端进程里 —— 装的是哪个
 * 播放器 mod、哪些平台已经登录（因此能代解析会员曲）、以及此刻正在听什么。服务端一个平台
 * 会话都没有，也看不到别人的本地播放器状态。
 *
 * <p>没上报过的玩家（装的是老版本客户端，或压根没装播放器）会被当成「未知客户端」，
 * 在成员列表里显示为「未知」而不是假装有 —— 猜一个比说不知道更糟。
 */
public final class ClientProfile {

    /** 客户端标识：{@code melodify}（modernMP）/ {@code muonium}（MuoniumPlayer）/ 其它。 */
    public String mod = "";
    /** 客户端自报的展示名；为空时由 {@link #displayName()} 从 {@link #mod} 推。 */
    public String modName = "";
    public String modVersion = "";
    /** 客户端声明的协议版本，用来判断它能不能显示 v2 的那些页。 */
    public int proto;

    /**
     * 已登录、因此有能力解析出完整音频的平台集合。
     *
     * <p>刻意叫 VIP 而不是「已登录」：房间里真正关心的是「这个人能不能把会员曲解析成完整音频」，
     * 而客户端能给出的最可靠信号就是「这个平台的会话在」。客户端上报的是它自己 {@code
     * MusicProvider.isLoggedIn()} 为真的平台。</p>
     */
    public final Set<String> vipSources = new LinkedHashSet<>();

    /** 玩家是否愿意替别人解析（贡献 VIP）。默认关，必须显式打开。 */
    public boolean contributeVip;

    /**
     * 这台客户端能不能跑「房间交接计划」的音频分析（{@code RoomAutomix}）。
     *
     * <p>房间里换歌点必须全房一致，所以只能由一台机器算完上报、服务端裁决广播。问题是
     * <b>「哪一台能算」跟「谁是房主」无关</b>：算力落在谁手上取决于客户端有没有这套代码、
     * 有没有装模型、音频能不能解码。modernMP 压根没有这个概念，它永远算不出 —— 但它同样
     * 要听房间里的无缝交接，于是它得借别人的机器。
     *
     * <p>所以这一位由客户端自报，服务端据此做<b>指派</b>（见 {@code Room#assignAnalyzer}）：
     * 优先点歌人 → 其次房主 → 再次房间里任何一个能算的人。为 false 的人永远不会被指派，
     * 也就不会白跑一次最重的后台任务。</p>
     */
    public boolean automixCapable;

    /**
     * 分析能力档位：{@code model} = 装了模型（Folia 推理，算得出 stem 交接、过渡族、频段倾斜）；
     * {@code dsp} = 只有内置信号处理；空串 = 不能分析。
     *
     * <p>它只影响<b>指派顺序</b>（同一优先级里先问档位高的），不参与计划本体的校验 ——
     * 计划里自己带着过渡族与曲线参数，执行端照着跑就行，不需要知道是哪种引擎算的。</p>
     */
    public String automixEngine = "";

    /** 本地正在听的曲目（不在房间里时也上报，全服在线态要用）。 */
    public String nowPlaying = "";
    public String nowArtist = "";
    public boolean localPlaying;

    public long updatedAt = System.currentTimeMillis();

    public boolean hasVip() { return !vipSources.isEmpty(); }

    public boolean canResolve(String source) {
        if (source == null) return false;
        return vipSources.contains(source.trim().toLowerCase(Locale.ROOT));
    }

    /** 展示名：客户端报了就用它，否则按已知标识翻译，都不认识就叫「未知客户端」。 */
    public String displayName() {
        if (modName != null && !modName.isBlank()) return modName;
        switch (mod == null ? "" : mod.toLowerCase(Locale.ROOT)) {
            case "melodify": case "modernmp": return "modernMP";
            case "muonium":  case "muoniumplayer": return "MuoniumPlayer";
            default: return "未知客户端";
        }
    }

    /**
     * 两个字母的角标：MP = modernMP / Melodify，Mu = MuoniumPlayer，其余「??」。
     *
     * <p>这是需求里点名要的「MP 和 Mu」——成员列表一行放不下完整 mod 名，角标能一眼看出
     * 同一个房间里混着哪两种客户端（它们的对时行为必须一致，混房时这一列很有用）。
     */
    public String badge() {
        switch (mod == null ? "" : mod.toLowerCase(Locale.ROOT)) {
            case "melodify": case "modernmp": return "MP";
            case "muonium":  case "muoniumplayer": return "Mu";
            default: return "??";
        }
    }

    /** 吸收一帧 {@code profile}（或带这些字段的 {@code hello}）。只覆盖真的带过来的字段。 */
    public void apply(JsonObject o) {
        if (o == null) return;
        String id = Proto.str(o, "client", Proto.str(o, "mod", mod));
        if (id != null && !id.isBlank()) mod = id.trim().toLowerCase(Locale.ROOT);
        String name = Proto.str(o, "modName", null);
        if (name != null && !name.isBlank()) modName = clip(name);
        String ver = Proto.str(o, "modVersion", null);
        if (ver != null && !ver.isBlank()) modVersion = clip(ver);
        int p = Proto.i32(o, "proto", proto);
        if (p > 0) proto = p;
        if (o.has("vip")) {
            vipSources.clear();
            JsonArray a = Proto.arr(o, "vip");
            if (a != null) {
                for (int i = 0; i < a.size() && vipSources.size() < 24; i++) {
                    try {
                        String s = a.get(i).getAsString();
                        if (s != null && Proto.isValidSource(s.trim().toLowerCase(Locale.ROOT))) {
                            vipSources.add(s.trim().toLowerCase(Locale.ROOT));
                        }
                    } catch (Throwable ignored) {
                        // 一条坏元素不该让整份上报作废。
                    }
                }
            }
        }
        if (o.has("contributeVip")) contributeVip = Proto.bool(o, "contributeVip", contributeVip);
        // 房间交接计划的分析能力。老客户端不发这两项，于是 automixCapable 保持上次的值
        // （默认 false）—— 它们不会被指派，房间照常按「没人算得出」处理。
        if (o.has("automixCapable")) automixCapable = Proto.bool(o, "automixCapable", automixCapable);
        if (o.has("automixEngine")) {
            String engine = Proto.str(o, "automixEngine", "");
            automixEngine = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
            if (automixEngine.length() > 16) automixEngine = automixEngine.substring(0, 16);
        }
        if (o.has("nowPlaying")) nowPlaying = clip(Proto.str(o, "nowPlaying", ""));
        if (o.has("nowArtist")) nowArtist = clip(Proto.str(o, "nowArtist", ""));
        if (o.has("localPlaying")) localPlaying = Proto.bool(o, "localPlaying", localPlaying);
        updatedAt = System.currentTimeMillis();
    }

    /** 在听的曲目一行展示；没有就返回空串（调用方自己决定显示「未在听」）。 */
    public String listeningLabel() {
        if (nowPlaying == null || nowPlaying.isBlank()) return "";
        return nowArtist == null || nowArtist.isBlank() ? nowPlaying : nowPlaying + " - " + nowArtist;
    }

    public JsonObject toJson(UUID uuid, String name) {
        JsonObject o = new JsonObject();
        if (uuid != null) o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name == null ? "" : name);
        o.addProperty("mod", mod);
        o.addProperty("modName", displayName());
        o.addProperty("modBadge", badge());
        o.addProperty("modVersion", modVersion);
        o.addProperty("proto", proto);
        o.addProperty("vip", hasVip());
        JsonArray a = new JsonArray();
        for (String s : vipSources) a.add(s);
        o.add("vipSources", a);
        o.addProperty("contributeVip", contributeVip);
        o.addProperty("automixCapable", automixCapable);
        o.addProperty("automixEngine", automixEngine == null ? "" : automixEngine);
        o.addProperty("nowPlaying", nowPlaying == null ? "" : nowPlaying);
        o.addProperty("nowArtist", nowArtist == null ? "" : nowArtist);
        o.addProperty("localPlaying", localPlaying);
        return o;
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= 80 ? t : t.substring(0, 80);
    }
}