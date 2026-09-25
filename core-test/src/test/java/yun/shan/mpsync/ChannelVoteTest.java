package yun.shan.mpsync;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.room.VoteSession;
import yun.shan.mpsync.support.FakePlatform;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务器频道与投票。
 *
 * <p>频道和普通房间共用同一套播放引擎，区别只在「谁说得上话」：没有房主，管理权归服务器管理员，
 * 切歌和踢人交给投票。这组测试盯的就是这些分界，以及两个容易写错的数：
 *
 * <ul>
 *   <li><b>门槛是严格大于。</b>「50% 以上」不是 {@code >=} —— 4 个人里 2 票不算过半。</li>
 *   <li><b>踢人的分母排除当事人。</b>否则 80% 在小房间里等于永远踢不掉（5 个人要 5 票，
 *       包括被踢的人自己那一票）。</li>
 * </ul>
 */
class ChannelVoteTest {

    private FakePlatform host;
    private RoomManager manager;
    private MpConfig cfg;
    private UUID admin;
    private UUID a;
    private UUID b;
    private UUID c;

    private static final String URL = "https://m8c.music.126.net/full.mp3";

    @BeforeEach
    void setUp(@TempDir Path dir) {
        host = new FakePlatform();
        admin = host.addPlayer("Admin");
        a = host.addPlayer("A");
        b = host.addPlayer("B");
        c = host.addPlayer("C");
        MpParty.init(host, dir.resolve("mpsync.json"));
        manager = MpParty.manager();
        cfg = MpParty.config();
        manager.noteOperator(admin, true);
    }

    private Room channelWithEveryone() {
        Room ch = manager.joinChannel(admin);
        manager.joinChannel(a);
        manager.joinChannel(b);
        manager.joinChannel(c);
        return ch;
    }

    private Track playable(String songId, String name) {
        Track t = new Track("netease", songId);
        t.name = name;
        t.durationMs = 300_000L;
        t.adoptUrl(URL, System.currentTimeMillis() + 600_000L, 300_000L, cfg);
        return t;
    }

    @Test
    void channelHasNoOwnerAndSurvivesEveryoneLeaving() {
        Room ch = manager.joinChannel(a);
        assertTrue(ch.channel());
        assertNull(ch.owner, "频道没有房主 —— 它是服务器设施，不是某个人的房间");
        manager.leave(a);
        assertFalse(ch.dead(), "空频道也不能被回收，否则管理员维护的歌单跟着消失");
        assertNotNull(manager.channel());
        assertEquals(ch, manager.channel(), "频道必须是同一个实例，不能每次重建");
    }

    @Test
    void serverAdminControlsTheChannelAndPlainMembersDoNot() {
        Room ch = channelWithEveryone();
        assertTrue(ch.controls(admin), "服务器管理员在频道里说得上话");
        assertFalse(ch.controls(a), "普通成员在频道里没有直接控制权");
        // 房间角色仍然是 member —— 频道的权限不是靠 Role 给的。
        assertEquals("member", ch.roleOf(admin).id());
    }

    @Test
    void channelCapsEachPlayerAtThreePendingSongs() {
        Room ch = manager.joinChannel(a);
        assertEquals(3, ch.perMemberCap(), "需求写死：频道里一个人同时只能点三首");
        ch.enqueue(a, "A", playable("1", "一"), false);   // 立刻起播，不占队列
        ch.enqueue(a, "A", playable("2", "二"), false);
        ch.enqueue(a, "A", playable("3", "三"), false);
        ch.enqueue(a, "A", playable("4", "四"), false);
        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> ch.enqueue(a, "A", playable("5", "五"), false));
        assertTrue(boom.getMessage().contains("3"), "拒绝时要说清上限是几首：" + boom.getMessage());
    }

    @Test
    void plainMemberPressingNextStartsAVoteInsteadOfSkipping() {
        Room ch = channelWithEveryone();
        ch.enqueue(admin, "Admin", playable("1", "一"), false);
        ch.enqueue(admin, "Admin", playable("2", "二"), false);
        host.clear();

        MpParty.onPacket(a, Proto.encode(Proto.msg(Proto.C_NEXT)));
        assertEquals("一", ch.current().track.name, "投票没通过之前不许换歌");
        JsonObject vote = host.last(Proto.S_VOTE);
        assertNotNull(vote, "普通成员按下一曲应该开一次投票");
        assertEquals("skip", vote.getAsJsonObject("vote").get("kind").getAsString());
        assertEquals(1, vote.getAsJsonObject("vote").get("agree").getAsInt(),
                "发起人自动算一票 —— 按下按钮本身就是表态");
    }

    @Test
    void skipNeedsStrictlyMoreThanHalf() {
        Room ch = channelWithEveryone();          // 4 人在线
        ch.enqueue(admin, "Admin", playable("1", "一"), false);
        ch.enqueue(admin, "Admin", playable("2", "二"), false);

        assertNull(ch.startVote(VoteSession.Kind.SKIP, a, null));
        VoteSession v = ch.latestOpenVote();
        assertNotNull(v);
        assertEquals(3, v.needed(4), "4 个人「50% 以上」要 3 票，不是 2 票");

        assertNull(ch.castVote(v.id, b, true));   // 2 票
        assertEquals("一", ch.current().track.name, "刚好一半不算通过");
        assertNull(ch.castVote(v.id, c, true));   // 3 票 → 通过
        assertEquals("二", ch.current().track.name, "过半后立刻切歌，不必等满 30 秒");
    }

    @Test
    void kickVoteExcludesTheTargetFromTheDenominator() {
        Room ch = channelWithEveryone();          // 4 人
        assertNull(ch.startVote(VoteSession.Kind.KICK, a, c));
        VoteSession v = ch.latestOpenVote();
        assertNotNull(v);
        assertEquals(3, ch.voteEligible(v), "被投的人不算进分母");
        assertEquals(3, v.needed(3), "3 个人里「80% 以上」= 3 票");
    }

    @Test
    void aHopelessVoteSettlesImmediatelyInsteadOfHangingTheChannel() {
        Room ch = channelWithEveryone();          // 4 人，踢 c → 分母 3（a/b/admin）
        assertNull(ch.startVote(VoteSession.Kind.KICK, a, c));
        VoteSession v = ch.latestOpenVote();
        assertNotNull(v);
        // 分母 3、门槛 3 票，a 那一票之外只剩 b 和 admin；b 一投反对，剩下的人全同意也只有
        // 2 票 —— 结论已经定了，必须当场结算，而不是把频道挂满整个窗口。
        assertNull(ch.castVote(v.id, b, false));
        assertNull(ch.latestOpenVote(), "注定失败的投票不该把频道挂满整个窗口");
        assertNotNull(ch.castVote(v.id, admin, false), "已经结算的投票不该还能投");
        assertTrue(ch.has(c), "没通过就不能踢人");
    }

    @Test
    void failedKickVotePutsTheTargetOnALongCooldown() {
        Room ch = channelWithEveryone();
        assertNull(ch.startVote(VoteSession.Kind.KICK, a, c));
        VoteSession v = ch.latestOpenVote();
        ch.castVote(v.id, b, false);
        ch.castVote(v.id, admin, false);
        assertTrue(ch.kickCooldownLeft(c) > 0L, "投票失败后必须进冷却，避免乱踢");
        String err = ch.startVote(VoteSession.Kind.KICK, b, c);
        assertNotNull(err, "冷却期内不能再对同一个人发起");
        assertTrue(err.contains("冷却"), err);
    }

    @Test
    void theKickTargetCannotVoteOnTheirOwnRemoval() {
        Room ch = channelWithEveryone();
        assertNull(ch.startVote(VoteSession.Kind.KICK, a, c));
        VoteSession v = ch.latestOpenVote();
        assertNotNull(ch.castVote(v.id, c, false),
                "当事人不在分母里，他的票也不该进分子 —— 否则票账算不平");
    }

    @Test
    void passingAKickVoteActuallyRemovesThePlayer() {
        Room ch = channelWithEveryone();          // 分母 3
        assertNull(ch.startVote(VoteSession.Kind.KICK, a, c));
        VoteSession v = ch.latestOpenVote();
        ch.castVote(v.id, b, true);
        ch.castVote(v.id, admin, true);           // 3/3 → 通过
        // Room 只登记，真正的移除由 RoomManager 在 tick 里做（playerRoom 索引在那一层）。
        manager.tick();
        assertFalse(ch.has(c), "投票通过就该真的把人请出去");
        assertNull(manager.roomOf(c), "同时必须清掉「这个人在哪个房间」的索引");
    }

    @Test
    void kickCooldownIsAtLeastAnHourByDefault() {
        assertTrue(cfg.voteKickCooldownMs >= 60 * 60 * 1000L,
                "需求：踢人投票失败后的冷却要超过 1 小时，实测 " + cfg.voteKickCooldownMs);
    }

    @Test
    void voteIsRefusedWhenThereAreTooFewPeople() {
        Room ch = manager.joinChannel(a);
        ch.enqueue(a, "A", playable("1", "一"), false);
        String err = ch.startVote(VoteSession.Kind.SKIP, a, null);
        assertNotNull(err, "一个人的频道不需要投票");
        assertTrue(err.contains("人太少"), err);
    }

    @Test
    void nobodyMayVoteTwice() {
        Room ch = channelWithEveryone();
        ch.enqueue(admin, "Admin", playable("1", "一"), false);
        ch.enqueue(admin, "Admin", playable("2", "二"), false);
        assertNull(ch.startVote(VoteSession.Kind.SKIP, a, null));
        VoteSession v = ch.latestOpenVote();
        assertNotNull(ch.castVote(v.id, a, true), "发起人已经投过了，不能再投一次");
    }

    @Test
    void votingIsRestrictedToPeopleInsideTheChannel() {
        Room ch = channelWithEveryone();
        ch.enqueue(admin, "Admin", playable("1", "一"), false);
        ch.enqueue(admin, "Admin", playable("2", "二"), false);
        assertNull(ch.startVote(VoteSession.Kind.SKIP, a, null));
        VoteSession v = ch.latestOpenVote();
        UUID outsider = host.addPlayer("Outsider");
        assertNotNull(ch.castVote(v.id, outsider, true), "房间外的人不能投票");
    }

    @Test
    void chatBroadcastCarriesTheAskAndHowToAgree() {
        Room ch = channelWithEveryone();
        ch.enqueue(admin, "Admin", playable("1", "起风了"), false);
        ch.enqueue(admin, "Admin", playable("2", "二"), false);
        host.chat.clear();
        ch.startVote(VoteSession.Kind.SKIP, a, null);
        boolean ok = host.chat.stream().anyMatch(l ->
                l.contains("想要切换歌曲") && l.contains("起风了") && l.contains("同意"));
        assertTrue(ok, "需求要求把这句话广播到聊天框，实测：" + host.chat);
    }

    @Test
    void serverAdminCanStillSkipDirectly() {
        Room ch = channelWithEveryone();
        ch.enqueue(admin, "Admin", playable("1", "一"), false);
        ch.enqueue(admin, "Admin", playable("2", "二"), false);
        MpParty.onPacket(admin, Proto.encode(Proto.msg(Proto.C_NEXT)));
        assertEquals("二", ch.current().track.name, "管理员要能收拾场面，不必陪着投票");
    }
}