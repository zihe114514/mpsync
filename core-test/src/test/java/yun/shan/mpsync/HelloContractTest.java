package yun.shan.mpsync;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.support.FakePlatform;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hello} 帧是服务端与两个客户端（modernMP、MuoniumPlayer）之间的握手契约。
 *
 * <p>时钟纠偏的那几个阈值由服务端统一下发 —— 这是让「同一房间里不同客户端口径一致」的关键：
 * 各个游戏版本、两个播放器，全用服务端这一套数，不会出现同一发心跳有人跳有人不跳、
 * 互相拉扯。少下发一个字段，客户端就会退回自带默认值，问题变成「偶尔不同步」这种极难查的形态，
 * 所以这里逐个字段钉住。</p>
 *
 * <p>这组断言同时是一次真实的回归：这套改动曾经被一次误合并整体回滚过，而回滚在编译上
 * 毫无痕迹 —— 唯一能发现的方式就是检查 hello 里到底有没有这些字段。</p>
 */
class HelloContractTest {

    private FakePlatform host;
    private UUID player;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        host = new FakePlatform();
        player = host.addPlayer("玩家A");
        MpParty.init(host, dir.resolve("mpsync.json"));
    }

    private JsonObject sayHello() {
        MpParty.onPacket(player, Proto.encode(Proto.msg(Proto.C_HELLO)));
        JsonObject hello = host.last(Proto.S_HELLO);
        assertNotNull(hello, "hello 必须有回应，否则客户端连不上房间系统");
        return hello;
    }

    @Test
    void helloCarriesEveryClockThreshold() {
        JsonObject hello = sayHello();
        MpConfig cfg = MpParty.config();

        assertEquals(cfg.clockGraceMs, hello.get("clockGraceMs").getAsLong());
        assertEquals(cfg.softDriftMs, hello.get("softDriftMs").getAsLong());
        assertEquals(cfg.hardDriftMs, hello.get("hardDriftMs").getAsLong());
        assertEquals(cfg.resyncCooldownMs, hello.get("resyncCooldownMs").getAsLong());
        assertEquals(cfg.driftConfirmHits, hello.get("driftConfirmHits").getAsInt());
        assertEquals(cfg.timeBroadcastTicks, hello.get("timeBroadcastTicks").getAsInt());
    }

    @Test
    void helloKeepsTheOlderFieldsClientsAlreadyRelyOn() {
        JsonObject hello = sayHello();
        assertEquals(Proto.VERSION, hello.get("proto").getAsInt());
        assertEquals("mpsync", hello.get("server").getAsString());
        assertTrue(hello.has("membersCanEnqueue"));
        assertTrue(hello.has("maxQueuePerMember"));
    }

    @Test
    void serverOwnerOverridesReachTheClient() {
        // 服主改了配置，客户端必须跟着改 —— 否则「统一口径」这件事就没做到。
        MpParty.config().softDriftMs = 3000L;
        MpParty.config().hardDriftMs = 9000L;
        host.clear();

        JsonObject hello = sayHello();
        assertEquals(3000L, hello.get("softDriftMs").getAsLong());
        assertEquals(9000L, hello.get("hardDriftMs").getAsLong());
    }

    @Test
    void unknownOpsDoNotKillTheConnection() {
        // 新版客户端会发老服务端不认识的 op。回一句错误就好，不能让整条连接或房间挂掉。
        MpParty.onPacket(player, Proto.encode(Proto.msg("op_from_the_future")));
        assertNotNull(host.last(Proto.S_ERROR), "不认识的 op 应当回一条 error");
    }

    @Test
    void garbageFramesAreIgnored() {
        MpParty.onPacket(player, new byte[]{1, 2, 3});
        MpParty.onPacket(player, new byte[0]);
        MpParty.onPacket(null, Proto.encode(Proto.msg(Proto.C_HELLO)));
        // 没抛异常就算过：网络上什么都可能来。
    }
}
