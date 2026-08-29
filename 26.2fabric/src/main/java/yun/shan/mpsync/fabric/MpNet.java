package yun.shan.mpsync.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import yun.shan.mpsync.MpParty;

/** Networking on Minecraft 26.2 (payload registries renamed to serverbound/clientboundPlay). */
public final class MpNet {

    private MpNet() {}

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(MpPayloads.C2S.TYPE, MpPayloads.C2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MpPayloads.S2C.TYPE, MpPayloads.S2C.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.C2S.TYPE, (payload, context) ->
                MpParty.onPacket(context.player().getUUID(), payload.data()));
    }

    public static boolean canSend(ServerPlayer player) {
        try {
            return ServerPlayNetworking.canSend(player, MpPayloads.S2C.TYPE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void send(ServerPlayer player, byte[] payload) {
        try {
            ServerPlayNetworking.send(player, new MpPayloads.S2C(payload));
        } catch (Throwable t) {
            OfficialPlatform.LOGGER.debug("[mpsync] send failed", t);
        }
    }
}
