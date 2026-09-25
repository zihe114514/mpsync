package yun.shan.mpsync.neoforge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import yun.shan.mpsync.net.Proto;

/** Channel payloads for NeoForge 26.3 (official Identifier rename). */
public final class MpPayloads {

    private MpPayloads() {}

    public record C2S(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<C2S> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(Proto.NAMESPACE, Proto.C2S_PATH));
        public static final StreamCodec<FriendlyByteBuf, C2S> CODEC = CustomPacketPayload.codec(
                (value, buf) -> buf.writeBytes(value.data()),
                buf -> new C2S(readAll(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record S2C(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<S2C> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(Proto.NAMESPACE, Proto.S2C_PATH));
        public static final StreamCodec<FriendlyByteBuf, S2C> CODEC = CustomPacketPayload.codec(
                (value, buf) -> buf.writeBytes(value.data()),
                buf -> new S2C(readAll(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[Math.min(buf.readableBytes(), Proto.MAX_FRAME)];
        buf.readBytes(data);
        return data;
    }
}
