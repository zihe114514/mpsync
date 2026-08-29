package yun.shan.mpsync.fabric;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yun.shan.mpsync.platform.MpPlatform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * {@link MpPlatform} on Fabric, for every Minecraft version whose Yarn names still spell
 * these classes the same way (1.20.1 → 1.21.11).
 *
 * <p>The two genuinely version-dependent operations — "can this connection take our
 * channel" and "write these bytes" — are injected by the per-version {@code MpNet}, which
 * is the only file that has to be rewritten when Mojang changes the custom-payload API.
 */
public final class FabricPlatform implements MpPlatform {

    public static final Logger LOGGER = LoggerFactory.getLogger("mpsync");

    private final MinecraftServer server;
    private final Predicate<ServerPlayerEntity> canSend;
    private final BiConsumer<ServerPlayerEntity, byte[]> sender;

    public FabricPlatform(MinecraftServer server,
                          Predicate<ServerPlayerEntity> canSend,
                          BiConsumer<ServerPlayerEntity, byte[]> sender) {
        this.server = server;
        this.canSend = canSend;
        this.sender = sender;
    }

    private ServerPlayerEntity player(UUID uuid) {
        if (server == null || uuid == null) return null;
        return server.getPlayerManager().getPlayer(uuid);
    }

    @Override
    public String nameOf(UUID uuid) {
        ServerPlayerEntity p = player(uuid);
        // Not getGameProfile().getName(): authlib 7 (1.21.11+) made GameProfile a record,
        // so that accessor is gone. Entity.getName() exists on every supported version.
        return p == null ? null : p.getName().getString();
    }

    @Override
    public UUID uuidByName(String name) {
        if (server == null || name == null || name.isBlank()) return null;
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
        return p == null ? null : p.getUuid();
    }

    @Override
    public Collection<UUID> onlinePlayers() {
        List<UUID> out = new ArrayList<>();
        if (server == null) return out;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) out.add(p.getUuid());
        return out;
    }

    @Override
    public boolean canSend(UUID uuid) {
        ServerPlayerEntity p = player(uuid);
        if (p == null) return false;
        try {
            return canSend.test(p);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void sendRaw(UUID uuid, byte[] payload) {
        ServerPlayerEntity p = player(uuid);
        if (p == null || payload == null) return;
        sender.accept(p, payload);
    }

    @Override
    public void chat(UUID uuid, String text) {
        ServerPlayerEntity p = player(uuid);
        if (p != null) p.sendMessage(Text.literal(text), false);
    }

    @Override
    public void execute(Runnable task) {
        if (server == null) task.run();
        else server.execute(task);
    }

    @Override
    public void warn(String message, Throwable t) {
        LOGGER.warn(message, t);
    }

    /** Online names, for command completion. */
    public List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        if (server == null) return out;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            out.add(p.getName().getString());
        }
        return out;
    }
}
