package yun.shan.mpsync.fabric;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

/**
 * Networking for the pre-1.20.5 custom-payload API (raw {@code PacketByteBuf} channels).
 *
 * <p>The bytes on the wire are produced by {@link Proto} and are therefore identical to
 * what the 1.21.x adapters and the Paper plugin write — a client on any supported version
 * (including one bridged by a protocol translator) reads the same frames.
 */
public final class MpNet {

    private MpNet() {}

    public static final Identifier C2S = new Identifier(Proto.NAMESPACE, Proto.C2S_PATH);
    public static final Identifier S2C = new Identifier(Proto.NAMESPACE, Proto.S2C_PATH);

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(C2S, (server, player, handler, buf, responseSender) -> {
            // Copy off the netty buffer immediately: it is released as soon as we return,
            // and the room engine runs later on the server thread.
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            MpParty.onPacket(player.getUuid(), data);
        });
    }

    public static boolean canSend(ServerPlayerEntity player) {
        try {
            return ServerPlayNetworking.canSend(player, S2C);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void send(ServerPlayerEntity player, byte[] payload) {
        try {
            ServerPlayNetworking.send(player, S2C, new PacketByteBuf(Unpooled.wrappedBuffer(payload)));
        } catch (Throwable t) {
            FabricPlatform.LOGGER.debug("[mpsync] send failed", t);
        }
    }
}
