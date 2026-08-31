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
    /**
     * 命令帧之后的「时钟静默窗」：换歌／暂停／恢复／拖进度之后这么久内不发时钟心跳。
     *
     * <p>为什么需要：命令帧刚下发的那几秒，全房客户端都在开流（建连、缓冲、解码器起步），
     * 各家网络快慢不一，位置天然是散的。这时候再按 {@link #timeBroadcastTicks} 打一发心跳，
     * 每个还没缓冲完的客户端都会算出一个大偏差，于是集体 seek —— 而 seek 会重新开流，
     * 下一发心跳又是同样的局面，进度就在那儿来回跳。静默窗把这段「本来就该乱」的时间让出去，
     * 等大家真的开始播了再校准。</p>
     *
     * <p>命令帧本身不受影响，仍然立刻下发：静默的只是心跳。</p>
     */
    public long clockGraceMs = 4000L;
    /**
     * 软偏差阈值：客户端与服务端位置差超过这个值才值得处理，但只做静默微调（追/缓播放速度或
     * 小步纠偏），不许硬 seek。播放器缓冲抖动、解码延迟常年在 1~2 秒这个量级，硬跳只会更难听。
     */
    public long softDriftMs = 2500L;
    /** 硬偏差阈值：只有差到这个程度（真的脱节了）才允许客户端硬 seek 回服务端位置。 */
    public long hardDriftMs = 6000L;
    /** 两次硬 seek 之间的最小间隔。网络差的时候，这一条是「来回跳」的最后一道闸。 */
    public long resyncCooldownMs = 10_000L;
    /**
     * 连续多少发心跳都判定超阈值，才真的动手。
     *
     * <p>单发心跳不可信：一次 RTT 抖动就能造出几秒的假偏差。要求连续命中，能把瞬时抖动
     * 和真正的脱节区分开。</p>
     */
    public int driftConfirmHits = 2;
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
            // 时钟同步这一组：错配的阈值比默认值更糟，所以逐项兜底，并保证 soft < hard。
            if (cfg.clockGraceMs < 0L) cfg.clockGraceMs = 0L;
            if (cfg.clockGraceMs > 30_000L) cfg.clockGraceMs = 30_000L;
            if (cfg.softDriftMs < 1000L) cfg.softDriftMs = 1000L;
            if (cfg.hardDriftMs < cfg.softDriftMs + 1000L) cfg.hardDriftMs = cfg.softDriftMs + 1000L;
            if (cfg.resyncCooldownMs < 2000L) cfg.resyncCooldownMs = 2000L;
            if (cfg.driftConfirmHits < 1) cfg.driftConfirmHits = 1;
            if (cfg.driftConfirmHits > 10) cfg.driftConfirmHits = 10;
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
