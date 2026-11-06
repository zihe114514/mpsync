package yun.shan.mpsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.command.MpTextCommands;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.RoomManager;
import yun.shan.mpsync.support.FakePlatform;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /mp status}：全服有多少人装了客户端并和服务端接上。
 *
 * <p>「装了客户端」的判据是服务端手里有他的档案（握手时建、下线即清），所以没发过 hello 的人
 * —— 没装 mod，或者装了但通道没通 —— 一律算「未检测到客户端」。</p>
 */
class StatusCommandTest {

    private FakePlatform host;
    private RoomManager manager;
    private UUID admin;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        host = new FakePlatform();
        admin = host.addPlayer("Admin");
        MpParty.init(host, dir.resolve("mpsync.json"));
        manager = MpParty.manager();
    }

    private static JsonArray arr(String... tokens) {
        JsonArray a = new JsonArray();
        for (String t : tokens) a.add(t);
        return a;
    }

    private UUID connect(String name, String client, String version, JsonObject extra) {
        UUID uuid = host.addPlayer(name);
        JsonObject hello = Proto.msg(Proto.C_HELLO);
        hello.addProperty("client", client);
        hello.addProperty("modVersion", version);
        if (extra != null) for (String k : extra.keySet()) hello.add(k, extra.get(k));
        MpParty.onPacket(uuid, Proto.encode(hello));
        return uuid;
    }

    /** 三个接上了的客户端（两个 Mu 不同版本、一个 MP），两个没装的（Admin 自己也没发 hello）。 */
    private void populate() {
        JsonObject vip = new JsonObject();
        vip.add("vip", arr("netease"));
        vip.add("member", arr("netease"));
        vip.addProperty("contributeVip", true);
        UUID a = connect("Alice", "muonium", "2.3.1", vip);

        UUID b = connect("Bob", "muonium", "2.3.0", null);

        JsonObject lx = new JsonObject();
        lx.add("resolvable", arr("qq"));
        lx.add("viaLx", arr("qq"));
        lx.addProperty("contributeLx", true);
        connect("Carol", "melodify", "1.2", lx);

        host.addPlayer("Dave");                     // 没装 mod / 没连上：从没发过 hello

        manager.joinChannel(a);
        manager.joinChannel(b);
    }

    @Test
    void summarisesWhoHasTheClientAndWhoDoesNot() {
        populate();
        List<String> out = MpTextCommands.run(admin, true, new String[]{"status"});
        String all = String.join("\n", out);
        assertTrue(out.get(0).contains("在线 5 人"), all);
        assertTrue(out.get(0).contains("已装客户端并完成握手 3 人"), all);
        assertTrue(out.get(0).contains("MuoniumPlayer 2") && out.get(0).contains("modernMP 1"), all);
        assertTrue(out.get(0).contains("未检测到客户端 2 人"), "Admin 和 Dave 都没发过 hello：" + all);
        assertTrue(all.contains("MuoniumPlayer 2.3.1 ×1") && all.contains("MuoniumPlayer 2.3.0 ×1")
                && all.contains("modernMP 1.2 ×1"), "版本要分开数，才看得出谁的客户端太旧：" + all);
        assertTrue(all.contains("服务器频道 2 人"), all);
        assertTrue(all.contains("有会员 1 人") && all.contains("贡献会员解析 1 人") && all.contains("贡献 LX 1 人"), all);
    }

    @Test
    void listNamesThePlayersWithoutAClient() {
        populate();
        List<String> out = MpTextCommands.run(admin, true, new String[]{"status", "list"});
        String all = String.join("\n", out);
        assertTrue(out.get(0).contains("2 人"), all);
        assertTrue(all.contains("Dave") && all.contains("Admin"), all);
        assertFalse(all.contains("Alice"), "接上了的人不该出现在这张单里：" + all);
    }

    @Test
    void aPlayerWhoLeavesNoLongerCountsAsConnected() {
        populate();
        UUID bob = host.uuidByName("Bob");
        manager.onPlayerQuit(bob);
        host.names.remove(bob);
        List<String> out = MpTextCommands.run(admin, true, new String[]{"status"});
        assertTrue(out.get(0).contains("在线 4 人") && out.get(0).contains("完成握手 2 人"), out.toString());
    }

    @Test
    void everyoneConnectedSaysSo() {
        host.names.remove(admin);
        connect("Alice", "muonium", "2.3.1", null);
        List<String> out = MpTextCommands.run(host.uuidByName("Alice"), true, new String[]{"status", "list"});
        assertEquals(List.of("所有在线玩家都已装客户端并完成握手"), out);
    }

    @Test
    void adminOnlyAndOfferedByTabCompletionToAdmins() {
        UUID bob = connect("Bob", "muonium", "2.3.0", null);
        List<String> denied = MpTextCommands.run(bob, false, new String[]{"status"});
        assertEquals(1, denied.size());
        assertTrue(denied.get(0).contains("管理员"), denied.toString());

        assertTrue(MpTextCommands.suggest(new String[]{"st"}, null, true).contains("status"));
        assertFalse(MpTextCommands.suggest(new String[]{"st"}, null).contains("status"), "普通玩家看不到");
        assertEquals(List.of("list"), MpTextCommands.suggest(new String[]{"status", ""}, null, true));
    }
}
