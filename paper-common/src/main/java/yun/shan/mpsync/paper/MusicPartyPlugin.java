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

/**
 * MusicParty as a Paper plugin.
 *
 * <p>Same engine, same wire format and same {@code /mp} commands as the Fabric builds, so a
 * Paper server hosts rooms for the very same clients — and, with a protocol translator in
 * front, for clients of other Minecraft versions at the same time.
 */
public final class MusicPartyPlugin extends JavaPlugin implements PluginMessageListener, Listener {

    @Override
    public void onEnable() {
        PaperPlatform platform = new PaperPlatform(this);
        MpParty.init(platform, getDataFolder().toPath().resolve("mpsync.json"));

        getServer().getMessenger().registerOutgoingPluginChannel(this, Proto.S2C);
        getServer().getMessenger().registerIncomingPluginChannel(this, Proto.C2S, this);
        getServer().getPluginManager().registerEvents(this, this);

        // The room clock is tick-driven, exactly as on Fabric's END_SERVER_TICK.
        getServer().getScheduler().runTaskTimer(this, MpParty::tick, 1L, 1L);

        if (getCommand("mp") != null) {
            MpCommand cmd = new MpCommand(platform);
            getCommand("mp").setExecutor(cmd);
            getCommand("mp").setTabCompleter(cmd);
        }

        getLogger().info("MusicParty ready — protocol v" + Proto.VERSION
                + ", channels " + Proto.C2S + " / " + Proto.S2C);
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
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
        MpParty.onPlayerJoin(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        MpParty.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
