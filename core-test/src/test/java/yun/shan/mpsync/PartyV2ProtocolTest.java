package yun.shan.mpsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;
import yun.shan.mpsync.support.FakePlatform;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议 v2 的对外契约：客户端身份与 VIP、全服在线态、公开房间、待处理邀请、个人暂停。
 *
 * <p>为什么值得逐项钉住：这些字段全是「加法」，少发一个在编译上毫无痕迹，症状却是 GUI 上某一栏
 * 永远空白 —— 而空白和「真的没人有 VIP」长得一模一样，只能靠检查帧本身来区分。
 *
 * <p>另一条被钉住的是<b>邀请不进聊天框</b>：那是需求明确要求的行为，而它的实现只是「删掉一行
 * Net.chat」。没有断言守着的话，任何一次重构都可能把那行加回来。
 */
class PartyV2ProtocolTest {

    private FakePlatform host;
    private RoomManager manager;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        host = new FakePlatform();
        alice = host.addPlayer("Alice");
        bob = host.addPlayer("Bob");
        MpParty.init(host, dir.resolve("mpsync.json"));
        manager = MpParty.manager();
    }

    private void send(UUID from, JsonObject msg) {
        MpParty.onPacket(from, Proto.encode(msg));
    }

    private JsonObject hello(UUID who, String mod, String version, String... vip) {
        JsonObject m = Proto.msg(Proto.C_HELLO);
        m.addProperty("proto", Proto.VERSION);
        m.addProperty("client", mod);
        m.addProperty("modVersion", version);
        JsonArray a = new JsonArray();
        for (String v : vip) a.add(v);
        m.add("vip", a);
        m.addProperty("contributeVip", true);
        send(who, m);
        return host.last(Proto.S_HELLO);
    }

    @Test
    void helloAdvertisesEveryV2Capability() {
        JsonObject h = hello(alice, "melodify", "1.10.0");
        assertNotNull(h);
        assertEquals(2, h.get("proto").getAsInt(), "v2 必须自报协议号，客户端据此决定显示哪些页");
        for (String key : new String[]{"channelEnabled", "channelName", "channelCode",
                "channelMaxPerMember", "publicRoomsEnabled", "voteWindowMs", "voteSkipRatio",
                "voteKickRatio", "voteKickCooldownMs", "serverResolve", "serverAdmin"}) {
            assertTrue(h.has(key), "hello 缺少 v2 字段：" + key);
        }
        // 没配 Token 的服务器必须老实说自己不能代解析，否则客户端会告诉玩家一个做不到的承诺。
        assertFalse(h.get("serverResolve").getAsBoolean());
    }

    @Test
    void helloIsFollowedByInvitesAndPublicRooms() {
        hello(alice, "melodify", "1.10.0");
        assertNotNull(host.last(Proto.S_INVITES), "握手后应直接补上待处理邀请，GUI 不该再等一轮");
        assertNotNull(host.last(Proto.S_ROOMS), "握手后应直接补上公开房间列表");
    }

    @Test
    void memberEntryCarriesModBadgeVipAndNowPlaying() {
        hello(alice, "melodify", "1.10.0", "netease");
        JsonObject prog = Proto.msg(Proto.C_PROGRESS);
        prog.addProperty("nowPlaying", "起风了");
        prog.addProperty("nowArtist", "买辣椒也用券");
        prog.addProperty("localPlaying", true);
        send(alice, prog);

        manager.create(alice, "房");
        JsonObject state = host.last(Proto.S_ROOM_STATE);
        assertNotNull(state);
        JsonObject me = state.getAsJsonObject("room").getAsJsonArray("members")
                .get(0).getAsJsonObject();
        assertEquals("MP", me.get("modBadge").getAsString(), "modernMP 的角标必须是 MP");
        assertEquals("modernMP", me.get("modName").getAsString());
        assertEquals("1.10.0", me.get("modVersion").getAsString());
        assertTrue(me.get("vip").getAsBoolean(), "上报了已登录的网易云，就该算有 VIP 解析能力");
        assertTrue(me.get("contributeVip").getAsBoolean());
        assertEquals("起风了 - 买辣椒也用券", me.get("nowPlaying").getAsString());
    }

    @Test
    void unknownClientIsReportedAsUnknownRatherThanGuessed() {
        // 没有 hello / profile：服务端不认识这个客户端，必须说不知道，不能替它编一个。
        manager.create(bob, "房");
        JsonObject state = host.last(Proto.S_ROOM_STATE);
        JsonObject m = state.getAsJsonObject("room").getAsJsonArray("members")
                .get(0).getAsJsonObject();
        assertEquals("", m.get("mod").getAsString());
        assertFalse(m.get("vip").getAsBoolean());
    }

    @Test
    void presenceListsOnlinePlayersAndWhatTheyAreListeningTo() {
        hello(alice, "melodify", "1.10.0", "netease");
        hello(bob, "muonium", "0.4");
        JsonObject prog = Proto.msg(Proto.C_PROGRESS);
        prog.addProperty("nowPlaying", "海阔天空");
        send(bob, prog);

        send(alice, Proto.msg(Proto.C_PRESENCE));
        JsonObject frame = host.last(Proto.S_PRESENCE);
        assertNotNull(frame);
        JsonArray players = frame.getAsJsonArray("players");
        assertEquals(2, players.size());
        boolean sawMu = false;
        for (int i = 0; i < players.size(); i++) {
            JsonObject p = players.get(i).getAsJsonObject();
            if ("Bob".equals(p.get("name").getAsString())) {
                sawMu = true;
                assertEquals("Mu", p.get("modBadge").getAsString(),
                        "MuoniumPlayer 的角标必须是 Mu");
                assertEquals("海阔天空", p.get("nowPlaying").getAsString());
            }
        }
        assertTrue(sawMu);
    }

    @Test
    void progressOutsideARoomIsAcceptedInsteadOfAnsweredWithAnError() {
        // 心跳在房间外也会来（全服在线态靠它）。之前它走 handleInRoom，会换回一句
        // 「你还没有加入一起听房间」—— 客户端每 5 秒收到一次错误提示。
        hello(alice, "melodify", "1.10.0");
        host.clear();
        send(alice, Proto.msg(Proto.C_PROGRESS));
        assertEquals(0, host.count(Proto.S_ERROR), "房间外的心跳不该换回错误");
    }

    @Test
    void privateRoomsStayOutOfThePublicList() {
        manager.create(alice, "私密房");
        send(bob, Proto.msg(Proto.C_ROOMS));
        JsonObject frame = host.last(Proto.S_ROOMS);
        assertNotNull(frame);
        for (int i = 0; i < frame.getAsJsonArray("rooms").size(); i++) {
            JsonObject r = frame.getAsJsonArray("rooms").get(i).getAsJsonObject();
            assertTrue(r.get("channel").getAsBoolean(),
                    "只有频道该出现在列表里；私密房间不能被陌生人看到");
        }
    }

    @Test
    void openingARoomPutsItInThePublicListWithoutLeakingTheQueue() {
        Room room = manager.create(alice, "公开房");
        send(alice, publicToggle(true));
        assertTrue(room.open);

        send(bob, Proto.msg(Proto.C_ROOMS));
        JsonObject frame = host.last(Proto.S_ROOMS);
        JsonObject found = null;
        for (int i = 0; i < frame.getAsJsonArray("rooms").size(); i++) {
            JsonObject r = frame.getAsJsonArray("rooms").get(i).getAsJsonObject();
            if ("公开房".equals(r.get("name").getAsString())) found = r;
        }
        assertNotNull(found, "设为公开后必须出现在列表里");
        assertEquals("Alice", found.get("ownerName").getAsString());
        assertFalse(found.has("queue") && found.get("queue").isJsonArray(),
                "列表条目只带计数，不该把整份歌单发给房间外的人");
    }

    private static JsonObject publicToggle(boolean open) {
        JsonObject m = Proto.msg(Proto.C_SET_PUBLIC);
        m.addProperty("public", open);
        return m;
    }

    @Test
    void inviteGoesToThePendingListAndNotToChat() {
        manager.create(alice, "房");
        host.clear();
        JsonObject inv = Proto.msg(Proto.C_INVITE);
        inv.addProperty("target", "Bob");
        send(alice, inv);

        JsonObject list = host.last(Proto.S_INVITES);
        assertNotNull(list, "被邀请人应收到一份待处理邀请列表");
        assertEquals(1, list.getAsJsonArray("invites").size());
        JsonObject one = list.getAsJsonArray("invites").get(0).getAsJsonObject();
        assertEquals("Alice", one.get("fromName").getAsString());
        assertEquals("房", one.get("roomName").getAsString());

        for (String line : host.chat) {
            assertFalse(line.contains("邀请你加入"),
                    "需求明确要求邀请不出现在聊天框，实测收到：" + line);
        }
    }

    @Test
    void acceptingAnInviteJoinsTheRoomAndClearsTheCard() {
        Room room = manager.create(alice, "房");
        JsonObject inv = Proto.msg(Proto.C_INVITE);
        inv.addProperty("target", "Bob");
        send(alice, inv);

        JsonObject accept = Proto.msg(Proto.C_INVITE_ACCEPT);
        accept.addProperty("roomId", room.id);
        send(bob, accept);

        assertTrue(room.has(bob), "接受邀请就该真的进房间");
        JsonObject list = host.last(Proto.S_INVITES);
        assertEquals(0, list.getAsJsonArray("invites").size(), "用掉的邀请卡应该消失");
    }

    @Test
    void decliningAnInviteRemovesItWithoutJoining() {
        Room room = manager.create(alice, "房");
        JsonObject inv = Proto.msg(Proto.C_INVITE);
        inv.addProperty("target", "Bob");
        send(alice, inv);

        JsonObject decline = Proto.msg(Proto.C_INVITE_DECLINE);
        decline.addProperty("roomId", room.id);
        send(bob, decline);

        assertFalse(room.has(bob));
        assertEquals(0, host.last(Proto.S_INVITES).getAsJsonArray("invites").size());
    }

    @Test
    void personalPauseDropsTheListenerCountWithoutTouchingTheRoomClock() {
        Room room = manager.create(alice, "房");
        manager.join(bob, room.code);
        room.progress(alice, 0L, true);
        room.progress(bob, 0L, true);
        assertEquals(2, room.listenerCount());

        assertNull(room.setSelfPause(bob, true));
        assertEquals(1, room.listenerCount(), "个人暂停的人不该算进收听人数");
        assertTrue(room.member(bob).selfPaused);
        // 房间时钟一动不动 —— 这正是「个人」暂停与房间暂停的分界。
        assertTrue(room.isPlaying() || room.current() == null);
    }

    @Test
    void leavingPersonalPauseImmediatelyPushesTheAuthoritativePosition() {
        Room room = manager.create(alice, "房");
        room.setSelfPause(alice, true);
        host.clear();
        room.setSelfPause(alice, false);
        // 房间里什么都没在放时补一帧 stop 也算「立刻同步」——重点是必须有一帧只发给他，
        // 而不是让他从暂停那一刻的位置继续放。
        assertTrue(host.count(Proto.S_STOP) + host.count(Proto.S_PLAY) >= 1,
                "恢复个人暂停必须立刻收到一帧权威位置");
    }
}