package yun.shan.mpsync.neoforge;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yun.shan.mpsync.platform.MpPlatform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** {@link MpPlatform} for NeoForge 26.3 (same Minecraft APIs as OfficialPlatform). */
public final class NeoForgePlatform implements MpPlatform {

    public static final Logger LOGGER = LoggerFactory.getLogger("mpsync");

    private final MinecraftServer server;
    private final Predicate<ServerPlayer> canSend;
    private final BiConsumer<ServerPlayer, byte[]> sender;

    public NeoForgePlatform(MinecraftServer server,
                            Predicate<ServerPlayer> canSend,
                            BiConsumer<ServerPlayer, byte[]> sender) {
        this.server = server;
        this.canSend = canSend;
        this.sender = sender;
    }

    private ServerPlayer player(UUID uuid) {
        if (server == null || uuid == null) return null;
        return server.getPlayerList().getPlayer(uuid);
    }

    @Override public String nameOf(UUID uuid) {
        ServerPlayer p = player(uuid);
        return p == null ? null : p.getName().getString();
    }

    @Override public UUID uuidByName(String name) {
        if (server == null || name == null || name.isBlank()) return null;
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p == null ? null : p.getUUID();
    }

    @Override public Collection<UUID> onlinePlayers() {
        List<UUID> out = new ArrayList<>();
        if (server == null) return out;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) out.add(p.getUUID());
        return out;
    }

    @Override public boolean canSend(UUID uuid) {
        ServerPlayer p = player(uuid);
        if (p == null) return false;
        try { return canSend.test(p); } catch (Throwable ignored) { return false; }
    }

    @Override public void sendRaw(UUID uuid, byte[] payload) {
        ServerPlayer p = player(uuid);
        if (p == null || payload == null) return;
        sender.accept(p, payload);
    }

    @Override public void chat(UUID uuid, String text) {
        ServerPlayer p = player(uuid);
        if (p != null) p.sendSystemMessage(Component.literal(text));
    }

    @Override public void execute(Runnable task) {
        if (server == null) task.run();
        else server.execute(task);
    }

    @Override public void warn(String message, Throwable t) { LOGGER.warn(message, t); }

    public List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        if (server == null) return out;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) out.add(p.getName().getString());
        return out;
    }
}
