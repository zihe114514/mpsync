package yun.shan.mpsync.javaagent;

import java.lang.instrument.Instrumentation;

/**
 * 原版服务端 Java Agent 入口。
 */
public final class JavaAgent {
    private JavaAgent() {
    }

    public static void premain(String options, Instrumentation instrumentation) {
        System.setProperty("mpsync.agent.url", JavaAgent.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm());
        instrumentation.addTransformer(new JavaAgentTransformer(new JavaAgentTransformer.Mapping(
            "net/minecraft/server/players/PlayerList",
            "net/minecraft/commands/Commands",
            "net/minecraft/server/network/ServerGamePacketListenerImpl",
            "net/minecraft/server/level/ServerPlayer",
            "net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket",
            "net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket",
            "tickServer",
            "stopServer",
            "placeNewPlayer",
            null,
            "remove",
            "handleCustomPayload",
            "net/minecraft/network/protocol/common/custom/CustomPacketPayload",
            "codec",
            null)));
        System.out.println("[mpsync] Java Agent loaded (26.x)");
    }
}

