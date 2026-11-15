package yun.shan.mpsync.javaagent;

import java.nio.file.Path;

import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

/**
 * 由注入器调用的服务器事件入口。
 */
public final class Hooks {
    private static boolean initialized;

    public static void commands(Commands commands) {
        MpCommands.register(commands.getDispatcher());
    }

    public static void tick(MinecraftServer server) {
        if (!initialized) {
            MpParty.init(new OfficialPlatform(server), Path.of("config", "mpsync.json"));
            initialized = true;
            OfficialPlatform.LOGGER.info("[mpsync] MusicParty ready — protocol v{}", Proto.VERSION);
        }
        MpParty.tick();
    }

    public static void join(ServerPlayer player) {
        MpNet.register(player);
        MpParty.onPlayerJoin(player.getUUID());
    }

    public static void quit(ServerPlayer player) {
        MpParty.onPlayerQuit(player.getUUID());
    }

    public static void shutdown() {
        if (initialized) {
            MpParty.shutdown();
            initialized = false;
        }
    }

    // 1.20.1 原版服务端的客户端上行自定义数据包上限为 32767 字节
    // 按道理来说放得下，批量歌单或很长的字段可能超限并导致断线
    public static void packet(ServerGamePacketListenerImpl listener, ServerboundCustomPayloadPacket packet) {
        if (!MpNet.C2S.equals(packet.getIdentifier())) return;
        FriendlyByteBuf data = packet.getData();
        if (data.readableBytes() > Proto.MAX_FRAME) return;
        byte[] bytes = new byte[data.readableBytes()];
        data.getBytes(data.readerIndex(), bytes);
        MpParty.onPacket(listener.player.getUUID(), bytes);
    }
}
