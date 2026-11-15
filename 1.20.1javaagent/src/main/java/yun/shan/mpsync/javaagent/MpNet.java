package yun.shan.mpsync.javaagent;

import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import yun.shan.mpsync.net.Proto;

/**
 * 使用 1.20.1 的原始自定义数据包传输协议帧。
 */
public final class MpNet {
    static final ResourceLocation C2S = new ResourceLocation(Proto.NAMESPACE, Proto.C2S_PATH);
    private static final ResourceLocation S2C = new ResourceLocation(Proto.NAMESPACE, Proto.S2C_PATH);
    private static final ResourceLocation REGISTER = new ResourceLocation("minecraft", "register");

    private MpNet() {
    }

    public static boolean canSend(ServerPlayer player) {
        return false;
    }

    public static void send(ServerPlayer player, byte[] bytes) {
        send(player, S2C, bytes);
    }

    public static void register(ServerPlayer player) {
        send(player, REGISTER, Proto.C2S.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(ServerPlayer player, ResourceLocation channel, byte[] bytes) {
        player.connection.send(new ClientboundCustomPayloadPacket(channel, new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))));
    }
}
