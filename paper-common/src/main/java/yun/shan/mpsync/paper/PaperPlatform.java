package yun.shan.mpsync.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.platform.MpPlatform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@link MpPlatform} on Bukkit/Paper. The Bukkit API surface used here (plugin messaging,
 * player lookup, scheduler) has been stable for years, so this one file serves every
 * supported server version — the per-version projects differ only in which {@code
 * paper-api} they compile against and which bytecode level they target.
 *
 * <h2>Folia</h2>
 * Folia 没有主线程：每个区域各跑各的 tick 线程，收包、进出服、玩家指令都落在玩家
 * 所在区域的线程上，而且可能同时有好几个。core 要求「房间状态单线程」，这里用
 * <b>全局区域（GlobalRegionScheduler）</b>顶替原来的主线程 —— 它是串行的，不绑定
 * 任何位置，正好承载跟世界无关的房间引擎。Paper 上全局区域调度器就是主线程，
 * 所以同一套代码两边语义一致。
 *
 * <p>S2C 帧与聊天镜像直接在引擎线程上发：Folia 对玩家句柄不做线程检查（补丁原注
 * 「no checks for players」），发包最终落到 Netty 的线程安全队列里。改走玩家的
 * EntityScheduler 每帧要多等 1 tick，还要给每个收件人各排一个任务，百人服的广播
 * 得不偿失。
 */
public final class PaperPlatform implements MpPlatform {

    private final JavaPlugin plugin;
    private final boolean folia;

    public PaperPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
    }

    /**
     * 按类探测而不是调 {@code Bukkit.isGlobalTickThread()}：后者 1.20.1 的 paper-api
     * 里还没有，而这个文件五个 Paper 版本共用。
     */
    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public boolean isFolia() { return folia; }

    public JavaPlugin plugin() { return plugin; }

    @Override
    public String nameOf(UUID uuid) {
        Player p = uuid == null ? null : Bukkit.getPlayer(uuid);
        return p == null ? null : p.getName();
    }

    @Override
    public UUID uuidByName(String name) {
        if (name == null || name.isBlank()) return null;
        Player p = Bukkit.getPlayerExact(name);
        return p == null ? null : p.getUniqueId();
    }

    @Override
    public Collection<UUID> onlinePlayers() {
        List<UUID> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getUniqueId());
        return out;
    }

    @Override
    public boolean canSend(UUID uuid) {
        Player p = uuid == null ? null : Bukkit.getPlayer(uuid);
        return p != null && p.getListeningPluginChannels().contains(Proto.S2C);
    }

    @Override
    public void sendRaw(UUID uuid, byte[] payload) {
        Player p = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (p == null || payload == null) return;
        try {
            p.sendPluginMessage(plugin, Proto.S2C, payload);
        } catch (Throwable t) {
            // Thrown when the channel is not registered (or the player just left).
            plugin.getLogger().log(Level.FINE, "[mpsync] send failed", t);
        }
    }

    @Override
    public void chat(UUID uuid, String text) {
        Player p = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(text);
    }

    @Override
    public void execute(Runnable task) {
        if (task == null) return;
        if (folia) {
            // Folia 上 isPrimaryThread() 对任何区域线程都返回 true，拿它判断「已在引擎线程」
            // 会让几个区域同时改房间状态。入口全是区域线程 / 解析线程池，一律排进全局区域。
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
            return;
        }
        // Plugin messages already arrive on the main thread on most builds; running inline
        // there keeps a room command in the same tick instead of one tick late.
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    @Override
    public void warn(String message, Throwable t) {
        plugin.getLogger().log(Level.WARNING, message, t);
    }

    public List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }
}
