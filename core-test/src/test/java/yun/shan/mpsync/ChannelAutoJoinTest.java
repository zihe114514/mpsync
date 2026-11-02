package yun.shan.mpsync;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.command.MpTextCommands;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.Member;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;
import yun.shan.mpsync.support.FakePlatform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 频道自动加入（{@link MpConfig#channelAutoJoin}）。
 *
 * <p>盯的几条边界：触发点是 hello（只有装了 mod 的才会被拉）；主动离开 = 退出自动加入，且跨重启
 * 记得住；自己重新加入才划掉；被封的、已在别的房间的都不动；长期离线的座位要回收，否则频道成员表
 * 会累积成「所有来过的人」。</p>
 */
class ChannelAutoJoinTest {

    private Path dir;
    private FakePlatform host;
    private RoomManager manager;
    private MpConfig cfg;
    private UUID a;
    private UUID b;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        this.dir = dir;
        host = new FakePlatform();
        a = host.addPlayer("A");
        b = host.addPlayer("B");
        boot();
        cfg.channelAutoJoin = true;
    }

    private void boot() {
        MpParty.init(host, dir.resolve("mpsync.json"));
        manager = MpParty.manager();
        cfg = MpParty.config();
    }

    private void hello(UUID who) {
        MpParty.onPacket(who, Proto.encode(Proto.msg(Proto.C_HELLO)));
    }

    @Test
    void offByDefault(@TempDir Path other) {
        MpParty.init(host, other.resolve("mpsync.json"));
        assertFalse(MpParty.config().channelAutoJoin, "默认关闭：装了插件不该突然把所有人拉进频道");
        hello(a);
        assertNull(MpParty.manager().roomOf(a));
    }

    @Test
    void helloPutsTheClientIntoTheChannelQuietly() {
        hello(b);                     // b 先进来，看他会不会被 a 的加入刷屏
        host.clear();
        hello(a);
        Room ch = manager.roomOf(a);
        assertNotNull(ch, "握手后应自动在频道里");
        assertTrue(ch.channel());
        assertSame(ch, manager.roomOf(b));
        assertTrue(host.chat.stream().noneMatch(s -> s.contains("加入了频道")),
                "自动加入不向全频道广播，百人服上线高峰会刷屏：" + host.chat);
        JsonObject hello = host.last(Proto.S_HELLO);
        assertTrue(hello.get("channelAutoJoin").getAsBoolean(), "hello 要告诉客户端服务端会自动加入");
        // 快照只发一次（随 broadcastState），不重复。
        long snapshotsToA = host.sent.stream()
                .filter(s -> s.to().equals(a) && Proto.S_ROOM_STATE.equals(s.op())).count();
        assertEquals(1, snapshotsToA);
    }

    @Test
    void leavingIsJustLeavingAndTheNextLoginJoinsAgain() {
        hello(a);
        manager.leave(a);
        assertFalse(manager.optedOutOfAutoJoin(a), "/mp leave 只是这一次离开，不是关掉自动加入");
        assertNull(manager.roomOf(a));
        hello(a);                     // 下次进服
        assertNotNull(manager.roomOf(a), "离开过的人下次进服照样自动加入");
    }

    @Test
    void personalSwitchOffIsRememberedAcrossRestarts() {
        var out = MpTextCommands.run(a, false, new String[]{"autojoin", "off"});
        assertTrue(manager.optedOutOfAutoJoin(a), out.toString());
        hello(a);
        assertNull(manager.roomOf(a), "关掉个人开关后不再自动加入");
        assertTrue(Files.exists(dir.resolve("mpsync-autojoin-optout.json")));

        boot();                       // 模拟重启
        cfg.channelAutoJoin = true;
        assertTrue(manager.optedOutOfAutoJoin(a), "个人开关要落盘，重启后仍然有效");
        hello(a);
        assertNull(manager.roomOf(a));

        manager.joinChannel(a);
        manager.leave(a);
        assertTrue(manager.optedOutOfAutoJoin(a), "手动进出频道不改变个人开关");
    }

    @Test
    void personalSwitchOffDoesNotKickYouOutAndOnJoinsYouRightAway() {
        hello(a);
        MpTextCommands.run(a, false, new String[]{"autojoin", "off"});
        assertNotNull(manager.roomOf(a), "关掉的是以后，现在在频道里的不踢");
        manager.leave(a);
        var out = MpTextCommands.run(a, false, new String[]{"autojoin", "on"});
        assertFalse(manager.optedOutOfAutoJoin(a));
        assertNotNull(manager.roomOf(a), "全服开着时打开个人开关应当场加入：" + out);
    }

    @Test
    void bannedPlayersAndPlayersInAnotherRoomAreLeftAlone() {
        manager.bans().ban(a, "A", "测试", 0L, "admin");
        hello(a);
        assertNull(manager.roomOf(a), "被封禁的人不自动加入");

        Room mine = manager.create(b, "B 的房间");
        hello(b);
        assertSame(mine, manager.roomOf(b), "已经在自己房间里的人不能被挪走");
    }

    @Test
    void longOfflineSeatsAreReclaimedButShortOnesAreKept() {
        hello(a);
        hello(b);
        Room ch = manager.channel();
        manager.onPlayerQuit(a);
        manager.onPlayerQuit(b);
        Member ma = ch.member(a);
        ma.offlineSince = System.currentTimeMillis() - cfg.emptyRoomGraceMs - 1_000L;   // 早就走了
        for (int i = 0; i < 400; i++) manager.tick();
        assertNull(ch.member(a), "离线超过宽限期的座位要回收");
        assertNull(manager.roomOf(a));
        assertNotNull(ch.member(b), "刚掉线的保留座位，重连能原位接上");
        assertSame(ch, manager.roomOf(b));
    }

    @Test
    void autojoinCommandTogglesAndPersistsAndPullsInOnlineClients() throws Exception {
        cfg.channelAutoJoin = false;
        hello(a);                     // 握过手（有档案）但还没进频道
        assertNull(manager.roomOf(a));
        var out = MpTextCommands.run(b, true, new String[]{"autojoin", "server", "on"});
        assertTrue(cfg.channelAutoJoin);
        assertTrue(Files.readString(dir.resolve("mpsync.json")).contains("\"channelAutoJoin\": true"),
                "开关要写回 mpsync.json：" + out);
        assertNotNull(manager.roomOf(a), "刚打开时，已经在线的客户端玩家也应该被放进频道");
        var denied = MpTextCommands.run(a, false, new String[]{"autojoin", "server", "off"});
        assertTrue(cfg.channelAutoJoin, "非管理员不能改全服开关：" + denied);
    }

    @Test
    void tabCompletionOffersAutojoinToEveryoneAndAdminCommandsOnlyToAdmins() {
        assertTrue(MpTextCommands.suggest(new String[]{"auto"}, null).contains("autojoin"));
        assertEquals(java.util.List.of("on", "off"),
                MpTextCommands.suggest(new String[]{"autojoin", ""}, null));
        assertFalse(MpTextCommands.suggest(new String[]{""}, null).contains("idle"),
                "普通玩家看不到管理员子命令");

        assertTrue(MpTextCommands.suggest(new String[]{"i"}, null, true).contains("idle"));
        assertTrue(MpTextCommands.suggest(new String[]{"autojoin", "s"}, null, true).contains("server"));
        assertEquals(java.util.List.of("on", "off"),
                MpTextCommands.suggest(new String[]{"autojoin", "server", ""}, null, true));
        assertTrue(MpTextCommands.suggest(new String[]{"idle", ""}, null, true).containsAll(
                java.util.List.of("save", "add", "on", "off", "shuffle", "list", "clear")));
        assertEquals(java.util.List.of("on", "off"),
                MpTextCommands.suggest(new String[]{"idle", "shuffle", "o"}, null, true));
    }
}
