package yun.shan.mpsync;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.room.QueueEntry;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.presence.PresenceRegistry;
import yun.shan.mpsync.support.FakePlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点歌与插队落在队列里的<b>位置</b>。
 *
 * <p>用户报的是「新点的歌不是在已排的后面，而是下一首播放的」。这里盯住两件事：</p>
 *
 * <ul>
 *   <li><b>普通点歌永远排队尾。</b>只有客户端明确按了「下一首播放」那颗按钮才带 {@code next}，
 *       而带 {@code next} 也<b>只对房主/管理员生效</b>：成员发过来会被降级成追加，否则这颗按钮
 *       就成了给所有人插队的后门。</li>
 *   <li><b>连续插队之间是先到先得，不是倒序。</b>插队过去写成 {@code queue.add(0, ...)}，房主按
 *       1、2、3 点的三首会听到 3、2、1。现在插入点取「队首那段已插过的」之后，整段仍然压在所有
 *       正常排队的歌之前。</li>
 * </ul>
 *
 * <p>{@code jumped} 只在服务端内部用来认那一段，刻意不上歌单快照 —— 排序问题不该变成一个
 * wire 字段、更不该让老客户端因为多出来的键而变样。</p>
 */
class RoomQueueJumpOrderTest {

    private static final String URL = "https://m8c.music.126.net/full.mp3";
    private static final long DURATION = 240_000L;

    private FakePlatform host;
    private MpConfig cfg;
    private Room room;
    private UUID owner;
    private UUID member;

    @BeforeEach
    void setUp() {
        host = new FakePlatform();
        Net.bind(host);
        cfg = new MpConfig();
        owner = host.addPlayer("房主");
        member = host.addPlayer("成员");
        room = new Room(cfg, "r1", "ABCDE", owner, "房主", "测试房");
        room.setPresence(new PresenceRegistry());
        Net.markCapable(owner);
    }

    private Track playable(String songId) {
        Track t = new Track("netease", songId);
        t.name = "曲目" + songId;
        t.durationMs = DURATION;
        t.adoptUrl(URL, System.currentTimeMillis() + 600_000L, DURATION, cfg);
        return t;
    }

    /** 队列里现在的曲目 id，按将要播放的顺序。 */
    private List<String> queued() {
        List<String> ids = new ArrayList<>();
        for (QueueEntry e : room.queue()) ids.add(e.track.songId);
        return ids;
    }

    @Test
    void plainPicksLineUpBehindTheAlreadyQueuedOnes() {
        room.enqueue(owner, "房主", playable("start"), false);      // 房间空着：立刻起播，不占队列
        room.enqueue(member, "成员", playable("q1"), false);
        room.enqueue(member, "成员", playable("q2"), false);
        room.enqueue(owner, "房主", playable("q3"), false);

        assertEquals(List.of("q1", "q2", "q3"), queued(),
                "点歌就是排队：谁点的、权限多大都排在已经排着的那几首后面");
    }

    @Test
    void jumpsFromTheOwnerStayInPickOrderInsteadOfReversing() {
        room.enqueue(owner, "房主", playable("start"), false);
        room.enqueue(member, "成员", playable("q1"), false);
        room.enqueue(owner, "房主", playable("j1"), true);
        room.enqueue(owner, "房主", playable("j2"), true);
        room.enqueue(owner, "房主", playable("j3"), true);

        assertEquals(List.of("j1", "j2", "j3", "q1"), queued(),
                "三首插队要按 1、2、3 播，同时整段仍然压着成员那首 q1");
    }

    @Test
    void nextFromAMemberIsJustAnAppend() {
        room.enqueue(owner, "房主", playable("start"), false);
        room.enqueue(member, "成员", playable("q1"), false);
        QueueEntry jumped = room.enqueue(member, "成员", playable("mine"), true);

        assertFalse(jumped.jumped, "没有管理权的人带 next 也不该插队");
        assertEquals(List.of("q1", "mine"), queued(), "成员发 next 只会落到队尾");
    }

    @Test
    void afterTheJumpBlockDrainsTheNextJumpGoesBackToFront() {
        room.enqueue(owner, "房主", playable("start"), false);
        room.enqueue(member, "成员", playable("q1"), false);
        QueueEntry j1 = room.enqueue(owner, "房主", playable("j1"), true);
        QueueEntry j2 = room.enqueue(owner, "房主", playable("j2"), true);
        assertEquals(List.of("j1", "j2", "q1"), queued());

        // 队首那格被房主自己从歌单里删掉（播完也一样）：剩下的 j2 还是插过队的，
        // 新插的那首要接在它后面，而不是跳到最前把 j2 挤后。
        assertTrue(room.removeEntry(j1.entryId));
        QueueEntry j3 = room.enqueue(owner, "房主", playable("j3"), true);
        assertEquals(List.of("j2", "j3", "q1"), queued());

        // 插队这一段全清空了（都播完或被删掉）：插入点回到队首，压回正常排队之前。
        room.removeEntry(j2.entryId);
        room.removeEntry(j3.entryId);
        QueueEntry j4 = room.enqueue(owner, "房主", playable("j4"), true);
        assertTrue(j4.jumped);
        assertEquals(List.of("j4", "q1"), queued());
    }

    @Test
    void jumpMarkerStaysOffTheWire() {
        room.enqueue(owner, "房主", playable("start"), false);
        QueueEntry jumped = room.enqueue(owner, "房主", playable("j1"), true);
        assertTrue(jumped.jumped, "管理者的 next 要记下来，插入点靠它算");

        JsonObject snapshot = jumped.toJson(true);
        assertFalse(snapshot.has("jumped"),
                "这是服务端内部的排序标记，不该变成歌单帧上的新字段");
    }
}
