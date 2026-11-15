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
            "bbd",
            "ek",
            "axz",
            "awy",
            "abo",
            "aay",
            "a",
            "z",
            "a",
            "(Lwk;Lawy;Laxm;)V",
            "b",
            "a",
            "abt",
            "a",
            "(Labt$a;Ljava/util/List;)Laae;")));
        System.out.println("[mpsync] Java Agent loaded (1.21.10)");
    }
}

