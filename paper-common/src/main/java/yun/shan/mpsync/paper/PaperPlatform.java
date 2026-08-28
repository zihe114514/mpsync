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
 */
public final class PaperPlatform implements MpPlatform {

    private final JavaPlugin plugin;

    public PaperPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
    }

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
        // Plugin messages already arrive on the main thread on most builds; running inline
        // there keeps a room command in the same tick instead of one tick late.
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
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
