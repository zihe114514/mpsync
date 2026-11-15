package yun.shan.mpsync.javaagent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

public final class Hooks {
    private static boolean initialized;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List addPayloads(List original, boolean inbound) {
        List out = new ArrayList(original);
        out.add(new CustomPacketPayload.TypeAndCodec(Registration.TYPE, Registration.CODEC));
        if (inbound) out.add(new CustomPacketPayload.TypeAndCodec(MpPayloads.C2S.TYPE, MpPayloads.C2S.CODEC));
        else out.add(new CustomPacketPayload.TypeAndCodec(MpPayloads.S2C.TYPE, MpPayloads.S2C.CODEC));
        return out;
    }

    public static void commands(Commands c) {
        MpCommands.register(c.getDispatcher());
    }

    public static void tick(MinecraftServer s) {
        if (!initialized) {
            MpParty.init(new OfficialPlatform(s), Path.of("config", "mpsync.json"));
            initialized = true;
            OfficialPlatform.LOGGER.info("[mpsync] MusicParty ready — protocol v{}", Proto.VERSION);
        }
        MpParty.tick();
    }

    public static void join(ServerPlayer p) {
        p.connection.send(new ClientboundCustomPayloadPacket(new Registration(Proto.C2S.getBytes(StandardCharsets.UTF_8))));
        MpParty.onPlayerJoin(p.getUUID());
    }

    public static void quit(ServerPlayer p) {
        MpParty.onPlayerQuit(p.getUUID());
    }

    public static void shutdown() {
        if (initialized) {
            MpParty.shutdown();
            initialized = false;
        }
    }

    public static void packet(ServerGamePacketListenerImpl l, ServerboundCustomPayloadPacket p) {
        if (p.payload() instanceof MpPayloads.C2S data) MpParty.onPacket(l.player.getUUID(), data.data());
    }

    public record Registration(byte[] data) implements CustomPacketPayload {
        static final Type<Registration> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("minecraft", "register"));
        static final StreamCodec<FriendlyByteBuf, Registration> CODEC = CustomPacketPayload.codec((v, b) -> b.writeBytes(v.data), b -> {
            if (b.readableBytes() > 32767) throw new IllegalArgumentException("registration too large");
            byte[] bytes = new byte[b.readableBytes()];
            b.readBytes(bytes);
            return new Registration(bytes);
        });

        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
