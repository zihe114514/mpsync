package yun.shan.mpsync.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.EventNetworkChannel;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

/**
 * Forge 1.21.1 (FG6 / Forge 52) networking via EventNetworkChannel on vanilla ids
 * {@code mpsync:c2s}/{@code mpsync:s2c}. Payload bytes match Fabric/Paper {@link Proto} frames.
 */
public final class MpNet {

    private MpNet() {}

    public static final ResourceLocation C2S = ResourceLocation.fromNamespaceAndPath(Proto.NAMESPACE, Proto.C2S_PATH);
    public static final ResourceLocation S2C = ResourceLocation.fromNamespaceAndPath(Proto.NAMESPACE, Proto.S2C_PATH);

    private static EventNetworkChannel C2S_CH;
    private static EventNetworkChannel S2C_CH;

    public static void register() {
        C2S_CH = ChannelBuilder.named(C2S).networkProtocolVersion(1).optional().eventNetworkChannel();
        S2C_CH = ChannelBuilder.named(S2C).networkProtocolVersion(1).optional().eventNetworkChannel();
        C2S_CH.addListener(MpNet::onC2S);
    }

    private static void onC2S(CustomPayloadEvent event) {
        FriendlyByteBuf buf = event.getPayload();
        if (buf == null) return;
        byte[] data = new byte[Math.min(buf.readableBytes(), Proto.MAX_FRAME)];
        buf.readBytes(data);
        CustomPayloadEvent.Context ctx = event.getSource();
        ServerPlayer player = ctx.getSender();
        if (player != null) {
            ctx.enqueueWork(() -> MpParty.onPacket(player.getUUID(), data));
        }
        ctx.setPacketHandled(true);
    }

    public static boolean canSend(ServerPlayer player) {
        return player != null && S2C_CH != null;
    }

    public static void send(ServerPlayer player, byte[] payload) {
        if (player == null || payload == null) return;
        try {
            player.connection.send(new ClientboundCustomPayloadPacket(new MpPayloads.S2C(payload)));
        } catch (Throwable t) {
            ForgePlatform.LOGGER.debug("[mpsync] send failed", t);
        }
    }
}
