package yun.shan.mpsync.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import yun.shan.mpsync.MpParty;

public final class MpNet {

    private MpNet() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(MpPayloads.C2S.TYPE, MpPayloads.C2S.CODEC, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp) {
                MpParty.onPacket(sp.getUUID(), payload.data());
            }
        });
        registrar.playToClient(MpPayloads.S2C.TYPE, MpPayloads.S2C.CODEC, (payload, ctx) -> {});
    }

    public static boolean canSend(ServerPlayer player) {
        try {
            return player.connection.hasChannel(MpPayloads.S2C.TYPE);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void send(ServerPlayer player, byte[] payload) {
        try {
            PacketDistributor.sendToPlayer(player, new MpPayloads.S2C(payload));
        } catch (Throwable t) {
            NeoForgePlatform.LOGGER.debug("[mpsync] send failed", t);
        }
    }
}
