package yun.shan.mpsync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守住整个架构立足的那条不变量：<b>core 不碰 Minecraft，也不碰任何加载器。</b>
 *
 * <p>16 个服务端模块共用同一份 core，跨 1.20.1 / 1.21.1 / 1.21.11 / 26.2 四个游戏版本。
 * 这件事之所以成立，不是因为「跨版本共用很聪明」，而是因为 core 里只有房间成员表、点歌队列、
 * {@code pos = now - startEpoch} 的时钟和 varint 分帧 —— 这些东西在四个版本之间没有任何差异。
 * 一旦 core 里出现一行 {@code net.minecraft.*}，它就立刻变成版本绑定的代码，而 Yarn 名与
 * 官方名（26.x）之间还不通用。</p>
 *
 * <p>本工程的类路径上只有 gson，所以真正的守卫其实是编译器：写了就编不过。这个测试是第二道，
 * 作用是把失败信息说清楚（指出哪个文件哪一行），而不是让人对着一串符号找不到的报错发呆。</p>
 */
class CorePlatformFreeTest {

    /** 出现任何一个前缀，都说明 core 被绑到某个平台上了。 */
    private static final List<String> FORBIDDEN = List.of(
            "net.minecraft", "net.fabricmc", "net.minecraftforge", "net.neoforged",
            "org.bukkit", "io.papermc", "com.mojang");

    private static Path coreSrc() {
        // 测试的工作目录是 core-test/，core 就在隔壁。
        return Path.of("..", "core", "src", "main", "java");
    }

    @Test
    void coreSourcesExist() throws IOException {
        Path src = coreSrc();
        assertTrue(Files.isDirectory(src), "找不到 core 源码目录：" + src.toAbsolutePath());
        try (Stream<Path> s = Files.walk(src)) {
            long n = s.filter(p -> p.toString().endsWith(".java")).count();
            assertTrue(n >= 10, "core 只找到 " + n + " 个 java 文件，路径大概是错的");
        }
    }

    @Test
    void coreImportsNothingPlatformSpecific() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> s = Files.walk(coreSrc())) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (!line.startsWith("import ")) continue;
                    String imported = line.substring(7).replace("static ", "").replace(";", "").trim();
                    for (String bad : FORBIDDEN) {
                        if (imported.startsWith(bad)) {
                            offences.add(f.getFileName() + ":" + (i + 1) + " -> " + imported);
                        }
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "core 必须与平台无关，但出现了这些 import：\n  " + String.join("\n  ", offences)
                        + "\n（平台相关的代码放到 fabric-common / paper-common / forge-common /"
                        + " neoforge-common 或各版本模块里，core 只能通过 MpPlatform 接口与宿主打交道）");
    }

    @Test
    void onlyGsonIsAllowedAsThirdParty() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> s = Files.walk(coreSrc())) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (!line.startsWith("import ")) continue;
                    String imported = line.substring(7).replace("static ", "").replace(";", "").trim();
                    boolean ok = imported.startsWith("java.")
                            || imported.startsWith("javax.")
                            || imported.startsWith("com.google.gson")
                            || imported.startsWith("yun.shan.mpsync");
                    if (!ok) offences.add(f.getFileName() + " -> " + imported);
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "core 的第三方依赖只允许 gson（多一个依赖，16 个模块就都要跟着加）：\n  "
                        + String.join("\n  ", offences));
    }

    @Test
    void platformInterfaceStaysThin() throws IOException {
        // MpPlatform 是 core 与宿主之间唯一的门。它越窄，新增一个加载器的成本越低。
        // 这个数字不是教条：真要加方法就把它改大，但改动会出现在 diff 里，需要有人点头。
        Path iface = coreSrc().resolve("yun/shan/mpsync/platform/MpPlatform.java");
        assertTrue(Files.exists(iface), "MpPlatform 不在了？" + iface);
        long methods = Files.readAllLines(iface, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(l -> l.endsWith(");") || l.startsWith("default "))
                .count();
        assertFalse(methods > 12, "MpPlatform 膨胀到 " + methods + " 个方法了，"
                + "每加一个方法，四个 *-common 和 16 个模块都要跟着实现");
    }
}
