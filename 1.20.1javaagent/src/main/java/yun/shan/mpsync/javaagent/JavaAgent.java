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
            "alk",
            "dt",
            "aiy",
            "aig",
            "zq",
            null,
            "a",
            "t",
            "a",
            "(Lsd;Laig;)V",
            "c",
            "a",
            null,
            null,
            null)));
        System.out.println("[mpsync] Java Agent loaded (1.20.1)");
    }
}
