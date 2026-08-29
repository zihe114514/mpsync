package yun.shan.mpsync.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.util.SourceGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** {@code config/mpsync.json} — written with defaults on first run. */
public final class MpConfig {

    /** Members allowed in one room (0 = unlimited). */
    public int maxRoomMembers = 32;
    /** Pending 点歌 entries allowed per room. */
    public int maxQueueSize = 200;
    /** Pending entries a single non-admin member may hold at once (0 = unlimited). */
    public int maxQueuePerMember = 5;
    /** Rooms one server may host at a time (0 = unlimited). */
    public int maxRooms = 32;
    /** Interval of the authoritative clock broadcast, in ticks. */
    public int timeBroadcastTicks = 40;
    /** Assumed stream-URL lifetime when a client does not report one. */
    public long assumedUrlLifetimeMs = 20 * 60 * 1000L;
    /**
     * 房间等一条重新解析的链接多久才放弃并跳过这首歌。
     *
     * <p>这个窗口要装得下「服务端发问 → 客户端向平台发一次 HTTP → 客户端回帧」一整个往返，而且
     * 是别人机器上的网络。非会员点会员曲时全靠这条路把链接凑齐，10 秒经常不够，所以默认 20 秒，
     * 并在 {@link #load} 里兜一个 12 秒的下限。</p>
     */
    public long urlWaitTimeoutMs = 20_000L;
    /** Drop a room once every member has been offline this long. */
    public long emptyRoomGraceMs = 5 * 60 * 1000L;
    /** Allow members without control rights to 点歌. */
    public boolean membersCanEnqueue = true;
    /** Let a requester delete their own pending entry. */
    public boolean requesterCanRemoveOwn = true;
    /**
     * Minimum served-audio length, as a fraction of the track length, for a stream URL to
     * be accepted. Guards against a non-VIP client resolving a 30s trial clip and having
     * the whole room play that. Set to 0 to disable the check.
     */
    public double minStreamDurationRatio = 0.8;
    /**
     * 允许入队的播放源。<b>默认留空 = 不限制平台。</b>
     *
     * <p>服务端不解析链接也不取流，它转发的是点歌人自己解析出来的直链，所以平台对服务端没有
     * 技术意义 —— 客户端支持哪个平台，房间就能放哪个平台。这一项留着只是给服主一个收窄的开关
     * （比如只想让房间放网易云就填 {@code ["netease"]}）。可用的令牌见
     * {@link Proto#SOURCE_NETEASE} 一组常量，但不限于它们。</p>
     */
    public List<String> allowedSources = new ArrayList<>();
    /**
     * 取流／封面链接允许的 host 后缀。<b>默认留空 = 只要是公网 http(s) 地址就放行。</b>
     *
     * <p>之所以不再内置平台白名单：每加一个音乐平台都要改服务端，而客户端能支持的平台远多于
     * 服务端能维护的列表。留空不等于毫无把关 —— {@link SourceGuard#isPublicHttpUrl} 仍然会挡掉
     * 环回／私网／链路本地地址，所以房间不能被拿来让全房客户端探内网。想收窄到某几个平台时，
     * 可以照抄 {@link SourceGuard#DEFAULT_HOSTS} 再增删。</p>
     */
    public List<String> allowedHosts = new ArrayList<>();

    /** 平台是否开放；{@code allowedSources} 为空视为不限制。 */
    public boolean isSourceAllowed(String source) {
        if (allowedSources == null || allowedSources.isEmpty()) return true;
        for (String s : allowedSources) {
            if (s != null && s.trim().equalsIgnoreCase(source)) return true;
        }
        return false;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static MpConfig load(Path file) {
        MpConfig cfg = new MpConfig();
        try {
            if (Files.exists(file)) {
                MpConfig read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MpConfig.class);
                if (read != null) cfg = read;
            }
            if (cfg.allowedHosts == null) cfg.allowedHosts = new ArrayList<>();
            else if (isLegacyNeteaseOnlyHosts(cfg.allowedHosts)) {
                // 旧版本写出来的配置里只有网易云那 5 条后缀。它非空，所以不会被当成「不限制」，
                // 结果是升级之后其他平台的直链一律被拒，看起来像「多平台没生效」。清空成不限制。
                // 只在内容与旧默认值完全一致时才升级 —— 服主手改过的列表不能被覆盖。
                cfg.allowedHosts = new ArrayList<>();
            }
            if (cfg.allowedSources == null) cfg.allowedSources = new ArrayList<>();
            if (cfg.timeBroadcastTicks < 5) cfg.timeBroadcastTicks = 5;
            if (cfg.assumedUrlLifetimeMs < 60_000L) cfg.assumedUrlLifetimeMs = 60_000L;
            // 低于这个值时「向别的成员要链接」在物理上来不及，等于把功能关掉，所以兜底抬上来。
            if (cfg.urlWaitTimeoutMs < 12_000L) cfg.urlWaitTimeoutMs = 12_000L;
            if (cfg.minStreamDurationRatio < 0d) cfg.minStreamDurationRatio = 0d;
            if (cfg.minStreamDurationRatio > 1d) cfg.minStreamDurationRatio = 1d;
            cfg.save(file);
        } catch (Throwable ignored) {
            // A broken config must not stop the server from booting — defaults win.
        }
        return cfg;
    }

    /** 存量配置是否就是旧版的「仅网易云」默认值（顺序无关，逐项比较）。 */
    private static boolean isLegacyNeteaseOnlyHosts(List<String> hosts) {
        if (hosts.size() != SourceGuard.NETEASE_HOSTS.size()) return false;
        for (String h : hosts) {
            if (h == null) return false;
            boolean found = false;
            for (String known : SourceGuard.NETEASE_HOSTS) {
                if (known.equalsIgnoreCase(h.trim())) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    public void save(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }
}
