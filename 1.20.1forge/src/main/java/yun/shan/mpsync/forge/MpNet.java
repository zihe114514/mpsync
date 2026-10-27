package yun.shan.mpsync.forge;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

/**
 * Forge 1.20.1 networking via vanilla custom-payload channels {@code mpsync:c2s}/{@code mpsync:s2c}.
 * Bytes are the same {@link Proto} frames Fabric/Paper write — no SimpleChannel discriminator.
 */
public final class MpNet {

    private MpNet() {}

    public static final ResourceLocation C2S = new ResourceLocation(Proto.NAMESPACE, Proto.C2S_PATH);
    public static final ResourceLocation S2C = new ResourceLocation(Proto.NAMESPACE, Proto.S2C_PATH);

    private static EventNetworkChannel C2S_CH;
    private static EventNetworkChannel S2C_CH;

    public static void register() {
        C2S_CH = NetworkRegistry.newEventChannel(C2S, () -> "1", s -> true, s -> true);
        S2C_CH = NetworkRegistry.newEventChannel(S2C, () -> "1", s -> true, s -> true);
        C2S_CH.addListener(MpNet::onC2S);
    }

    /**
     * C2S 包（PLAY_TO_SERVER）投递的事件类是 {@code ClientCustomPayloadEvent} ——
     * 事件名里的 Client 说的是「包从客户端来」，不是在哪处理。收
     * {@code ServerCustomPayloadEvent} 的话类型对不上，监听器永远不触发，
     * 整个服务端对 C2S 失聪（vanilla 侧只会在客户端看到 Unknown packet）。
     */
    private static void onC2S(NetworkEvent.ClientCustomPayloadEvent event) {
        FriendlyByteBuf buf = event.getPayload();
        if (buf == null) return;
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        NetworkEvent.Context ctx = event.getSource().get();
        ServerPlayer player = ctx.getSender();
        if (player != null) MpParty.onPacket(player.getUUID(), data);
        ctx.setPacketHandled(true);
    }

    public static boolean canSend(ServerPlayer player) {
        return player != null && S2C_CH != null;
    }

    public static void send(ServerPlayer player, byte[] payload) {
        if (player == null || payload == null) return;
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
            player.connection.send(new ClientboundCustomPayloadPacket(S2C, buf));
        } catch (Throwable t) {
            ForgePlatform.LOGGER.debug("[mpsync] send failed", t);
        }
    }
}