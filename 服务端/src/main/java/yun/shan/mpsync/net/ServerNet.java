package yun.shan.mpsync.net;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yun.shan.mpsync.room.RoomManager;

import java.util.Collection;
import java.util.UUID;

/**
 * Plugin-channel plumbing: decode C2S frames, hop to the server thread, and hand them
 * to {@link RoomManager}. Also the single place that writes S2C frames, so the
 * {@link ServerPlayNetworking#canSend} guard cannot be forgotten — a player without the
 * client mod (or on an older version) must simply receive nothing.
 */
public final class ServerNet {

    public static final Logger LOGGER = LoggerFactory.getLogger("mpsync");

    private ServerNet() {}

    public static void register(RoomManager manager) {
        ServerPlayNetworking.registerGlobalReceiver(Proto.C2S, (server, player, handler, buf, sender) -> {
            JsonObject msg = Proto.decode(buf);
            if (msg == null) return;
            // Networking runs on the netty thread; room state is server-thread-only.
            server.execute(() -> {
                if (player.isRemoved()) return;
                manager.handle(player, msg);
            });
        });
    }

    public static boolean canSend(ServerPlayerEntity player) {
        try {
            return ServerPlayNetworking.canSend(player, Proto.S2C);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void send(ServerPlayerEntity player, JsonObject msg) {
        if (player == null || msg == null || !canSend(player)) return;
        try {
            PacketByteBuf buf = Proto.encode(msg);
            ServerPlayNetworking.send(player, Proto.S2C, buf);
        } catch (Throwable t) {
            LOGGER.debug("[mpsync] send failed", t);
        }
    }

    public static void notice(ServerPlayerEntity player, String text) {
        JsonObject m = Proto.msg(Proto.S_NOTICE);
        m.addProperty("msg", text == null ? "" : text);
        send(player, m);
        chat(player, "[一起听] " + text);
    }

    public static void error(ServerPlayerEntity player, String text) {
        JsonObject m = Proto.msg(Proto.S_ERROR);
        m.addProperty("msg", text == null ? "操作失败" : text);
        send(player, m);
        chat(player, "[一起听] " + (text == null ? "操作失败" : text));
    }

    /** Chat mirror: the room stays usable (and debuggable) from a vanilla client. */
    public static void chat(ServerPlayerEntity player, String text) {
        if (player == null || text == null) return;
        player.sendMessage(Text.literal(text), false);
    }

    public static void chatAll(MinecraftServer server, Collection<UUID> uuids, String text) {
        if (server == null || uuids == null) return;
        for (UUID uuid : uuids) chat(server.getPlayerManager().getPlayer(uuid), text);
    }
}
