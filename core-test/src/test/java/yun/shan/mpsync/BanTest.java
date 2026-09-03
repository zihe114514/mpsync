package yun.shan.mpsync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.ban.BanEntry;
import yun.shan.mpsync.ban.BanList;
import yun.shan.mpsync.command.MpTextCommands;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;
import yun.shan.mpsync.support.FakePlatform;
import yun.shan.mpsync.util.Durations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一起听封禁：{@code /mp ban <玩家> <原因> <时长>}。
 *
 * <p>两条容易写错的地方在这里钉住：
 * <ul>
 *   <li><b>要落盘。</b>封禁是管理动作，重启就没了等于没封 —— 这和房间刻意<b>不</b>落盘正好相反
 *       （房间里的直链几分钟就过期，恢复出来全是死链）。</li>
 *   <li><b>要把人请出去。</b>只把名字记进名单、人还坐在房间里听着，是最容易漏的一步。</li>
 * </ul>
 *
 * <p>另外测时长解析：管理员是在聊天栏现敲的，语序和单位都得宽容。
 */
class BanTest {

    private FakePlatform host;
    private RoomManager manager;
    private UUID op;
    private UUID target;
    private Path dir;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        dir = tmp;
        host = new FakePlatform();
        op = host.addPlayer("Op");
        target = host.addPlayer("Griefer");
        MpParty.init(host, tmp.resolve("mpsync.json"));
        manager = MpParty.manager();
    }

    private List<String> run(UUID who, boolean operator, String... args) {
        return MpTextCommands.run(who, operator, args);
    }

    @Test
    void onlyServerAdminsMayBan() {
        List<String> out = run(target, false, "ban", "Op", "捣乱", "1h");
        assertTrue(String.join("", out).contains("只有服务器管理员"), out.toString());
        assertFalse(manager.bans().isBanned(op, "Op"));
    }

    @Test
    void banAcceptsReasonThenDuration() {
        run(op, true, "ban", "Griefer", "一直刷歌", "30m");
        BanEntry e = manager.bans().find(target, "Griefer");
        assertNotNull(e, "封禁必须生效");
        assertEquals("一直刷歌", e.reason);
        assertFalse(e.permanent());
        assertTrue(e.until - System.currentTimeMillis() > 25 * 60_000L);
    }

    @Test
    void banAlsoAcceptsDurationThenReason() {
        // 管理员在聊天栏现敲，语序不该被强求。
        run(op, true, "ban", "Griefer", "2h", "刷", "歌");
        BanEntry e = manager.bans().find(target, "Griefer");
        assertNotNull(e);
        assertEquals("刷 歌", e.reason);
        assertTrue(e.until - System.currentTimeMillis() > 100 * 60_000L);
    }

    @Test
    void omittingTheDurationMeansPermanent() {
        run(op, true, "ban", "Griefer", "开挂");
        BanEntry e = manager.bans().find(target, "Griefer");
        assertNotNull(e);
        assertTrue(e.permanent());
        assertEquals("永久", e.remainingLabel());
    }

    @Test
    void bannedPlayerCannotCreateOrJoinRooms() {
        run(op, true, "ban", "Griefer", "捣乱", "1h");
        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> manager.create(target, "房"));
        assertTrue(boom.getMessage().contains("封禁"), boom.getMessage());

        Room room = manager.create(op, "房");
        assertThrows(IllegalStateException.class, () -> manager.join(target, room.code));
    }

    @Test
    void banningSomeoneAlreadyInARoomRemovesThem() {
        Room room = manager.create(op, "房");
        manager.join(target, room.code);
        assertTrue(room.has(target));
        run(op, true, "ban", "Griefer", "捣乱", "1h");
        assertFalse(room.has(target), "封了还坐在房间里听着，等于没封");
        assertNull(manager.roomOf(target));
    }

    @Test
    void bannedPlayerGetsAFrameSoTheGuiCanExplainItself() {
        run(op, true, "ban", "Griefer", "捣乱", "1h");
        host.clear();
        assertThrows(IllegalStateException.class, () -> manager.create(target, "房"));
        assertNotNull(host.last("banned"),
                "客户端要能把一起听整块置灰并说明原因，而不是每次点击弹一次错误");
    }

    @Test
    void banSurvivesARestart() throws Exception {
        run(op, true, "ban", "Griefer", "捣乱", "7d");
        Path file = dir.resolve("mpsync-bans.json");
        assertTrue(Files.exists(file), "封禁名单必须落盘，重启就失效等于没封");

        BanList fresh = new BanList();
        fresh.load(file);
        assertTrue(fresh.isBanned(target, "Griefer"));
    }

    @Test
    void offlinePlayersCanBeBannedByName() {
        UUID unknown = null;
        manager.bans().ban(unknown, "SomeoneOffline", "预防", 0L, "Op");
        assertTrue(manager.bans().isBanned(null, "someoneoffline"),
                "按名字封禁必须大小写不敏感 —— 玩家名的大小写没人记得住");
    }

    @Test
    void expiredBansDisappear() {
        manager.bans().ban(target, "Griefer", "短封", System.currentTimeMillis() - 1L, "Op");
        assertFalse(manager.bans().isBanned(target, "Griefer"), "到期的封禁不该继续生效");
        assertTrue(manager.bans().all().isEmpty());
    }

    @Test
    void unbanWorksByName() {
        run(op, true, "ban", "Griefer", "捣乱", "1h");
        assertTrue(manager.bans().unban("Griefer"));
        assertFalse(manager.bans().isBanned(target, "Griefer"));
    }

    @Test
    void durationParsingIsForgiving() {
        assertEquals(30 * 60_000L, Durations.parseMs("30m"));
        assertEquals(30 * 60_000L, Durations.parseMs("30"));          // 纯数字按分钟
        assertEquals(30 * 60_000L, Durations.parseMs("30分钟"));
        assertEquals(2 * 3_600_000L, Durations.parseMs("2h"));
        assertEquals(2 * 3_600_000L, Durations.parseMs("2小时"));
        assertEquals(7 * 86_400_000L, Durations.parseMs("7d"));
        assertEquals(Durations.PERMANENT, Durations.parseMs("永久"));
        assertEquals(Durations.PERMANENT, Durations.parseMs("0"));
        assertEquals(Durations.INVALID, Durations.parseMs("刷歌"));
        assertFalse(Durations.looksLikeDuration("因为他刷歌"));
        assertTrue(Durations.looksLikeDuration("15min"));
    }

    @Test
    void runningAnyCommandTeachesTheServerWhoIsAnAdmin() {
        // core 拿不到任何平台的权限系统，只能从命令层的 operator 标志学。这条路是频道管理权
        // 与 GUI 管理按钮的唯一来源，所以必须真的记下来。
        assertFalse(manager.isServerAdmin(op));
        run(op, true, "info");
        assertTrue(manager.isServerAdmin(op));
        run(op, false, "info");
        assertFalse(manager.isServerAdmin(op), "掉了权限也要跟着掉");
    }
}