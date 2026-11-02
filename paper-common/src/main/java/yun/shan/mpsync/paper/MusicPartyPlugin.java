package yun.shan.mpsync.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

import java.util.UUID;

/**
 * MusicParty as a Paper plugin.
 *
 * <p>Same engine, same wire format and same {@code /mp} commands as the Fabric builds, so a
 * Paper server hosts rooms for the very same clients — and, with a protocol translator in
 * front, for clients of other Minecraft versions at the same time.
 *
 * <p>同一个 jar 也跑在 Folia 上（{@code plugin.yml} 里的 {@code folia-supported}）。为此这里不碰
 * {@code Bukkit.getScheduler()} —— Folia 的 BukkitScheduler 一调就抛
 * {@link UnsupportedOperationException}。节拍、收包、进出服、指令全部经
 * {@link PaperPlatform#execute} 汇到全局区域，线程模型见 {@link PaperPlatform} 类注释。
 */
public final class MusicPartyPlugin extends JavaPlugin implements PluginMessageListener, Listener {

    private PaperPlatform platform;

    @Override
    public void onEnable() {
        PaperPlatform platform = new PaperPlatform(this);
        this.platform = platform;
        MpParty.init(platform, getDataFolder().toPath().resolve("mpsync.json"));

        getServer().getMessenger().registerOutgoingPluginChannel(this, Proto.S2C);
        getServer().getMessenger().registerIncomingPluginChannel(this, Proto.C2S, this);
        getServer().getPluginManager().registerEvents(this, this);

        // The room clock is tick-driven, exactly as on Fabric's END_SERVER_TICK.
        // 全局区域调度器：Paper 上就是主线程，Folia 上是串行的全局 tick，与 execute 同一条线。
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> MpParty.tick(), 1L, 1L);

        if (getCommand("mp") != null) {
            MpCommand cmd = new MpCommand(platform);
            getCommand("mp").setExecutor(cmd);
            getCommand("mp").setTabCompleter(cmd);
        }

        getLogger().info("MusicParty ready — protocol v" + Proto.VERSION
                + ", channels " + Proto.C2S + " / " + Proto.S2C
                + (platform.isFolia() ? ", Folia (global region)" : ""));
    }

    @Override
    public void onDisable() {
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        MpParty.shutdown();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Proto.C2S.equals(channel)) return;
        MpParty.onPacket(player.getUniqueId(), message);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Folia 上这两个事件在玩家所在区域的线程触发，必须先回引擎线程；Paper 上本就在主线程，
        // execute 会就地执行，行为不变。
        UUID uuid = event.getPlayer().getUniqueId();
        PaperPlatform p = platform;
        if (p != null) p.execute(() -> MpParty.onPlayerJoin(uuid));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PaperPlatform p = platform;
        if (p != null) p.execute(() -> MpParty.onPlayerQuit(uuid));
    }
}
