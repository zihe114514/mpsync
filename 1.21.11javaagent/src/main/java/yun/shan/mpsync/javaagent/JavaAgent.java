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
            "bbz",
            "ee",
            "ayi",
            "axg",
            "aby",
            "abi",
            "a",
            "x",
            "a",
            "(Lwu;Laxg;Laxu;)V",
            "b",
            "a",
            "acd",
            "a",
            "(Lacd$a;Ljava/util/List;)Laao;")));
        System.out.println("[mpsync] Java Agent loaded (1.21.11)");
    }
}

