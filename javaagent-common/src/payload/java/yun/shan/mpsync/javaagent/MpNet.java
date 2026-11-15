package yun.shan.mpsync.javaagent;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * 发送数据包；客户端发来协议帧后会被识别为支持该协议。
 */
public final class MpNet {
    private MpNet() {
    }

    public static boolean canSend(ServerPlayer player) {
        return false;
    }

    public static void send(ServerPlayer player, byte[] payload) {
        player.connection.send(new ClientboundCustomPayloadPacket(new MpPayloads.S2C(payload)));
    }
}
