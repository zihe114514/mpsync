package yun.shan.mpsync.fabric;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import yun.shan.mpsync.net.Proto;

/**
 * The two channel payloads for the 1.20.5+ custom-payload API.
 *
 * <p>Each carries the raw {@link Proto} frame and nothing else — no codec-level structure —
 * so the bytes are identical to what 1.20.1 (plain {@code PacketByteBuf}) and Paper
 * (plugin messages) put on the wire. That is what keeps rooms cross-version.
 */
public final class MpPayloads {

    private MpPayloads() {}

    public record C2S(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<C2S> ID =
                new CustomPayload.Id<>(Identifier.of(Proto.NAMESPACE, Proto.C2S_PATH));
        public static final PacketCodec<PacketByteBuf, C2S> CODEC =
                CustomPayload.codecOf((value, buf) -> buf.writeBytes(value.data()),
                        buf -> new C2S(readAll(buf)));

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    public record S2C(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<S2C> ID =
                new CustomPayload.Id<>(Identifier.of(Proto.NAMESPACE, Proto.S2C_PATH));
        public static final PacketCodec<PacketByteBuf, S2C> CODEC =
                CustomPayload.codecOf((value, buf) -> buf.writeBytes(value.data()),
                        buf -> new S2C(readAll(buf)));

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Drain the rest of the buffer: the frame is self-delimiting (varint length inside). */
    private static byte[] readAll(PacketByteBuf buf) {
        byte[] data = new byte[Math.min(buf.readableBytes(), Proto.MAX_FRAME)];
        buf.readBytes(data);
        return data;
    }
}
