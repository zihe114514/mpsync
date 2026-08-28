package yun.shan.mpsync.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import yun.shan.mpsync.MpParty;

/** Networking on the 1.20.5+ payload API (Minecraft 1.21.11 — identical to 1.21.1). */
public final class MpNet {

    private MpNet() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(MpPayloads.C2S.ID, MpPayloads.C2S.CODEC);
        PayloadTypeRegistry.playS2C().register(MpPayloads.S2C.ID, MpPayloads.S2C.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.C2S.ID, (payload, context) ->
                MpParty.onPacket(context.player().getUuid(), payload.data()));
    }

    public static boolean canSend(ServerPlayerEntity player) {
        try {
            return ServerPlayNetworking.canSend(player, MpPayloads.S2C.ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void send(ServerPlayerEntity player, byte[] payload) {
        try {
            ServerPlayNetworking.send(player, new MpPayloads.S2C(payload));
        } catch (Throwable t) {
            FabricPlatform.LOGGER.debug("[mpsync] send failed", t);
        }
    }
}
