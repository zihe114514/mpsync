package yun.shan.mpsync;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.RepeatMode;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.support.FakePlatform;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 房间内无缝交接的<b>服务端一半</b>：提前把下一首（连同直链）发给全房，并在本曲结束前
 * {@code automixLeadMs} 毫秒就换歌。客户端那半（提前下载、换歌时交叠）靠这两样才有原料。
 *
 * <p>为什么提前换歌必须发生在服务端：房间只有一套权威时钟，{@code startEpoch} 决定下一首的
 * 0 点落在哪个墙钟时刻。客户端自己提前起播的话，它本机下一首的位置会比服务端多出这个提前量，
 * 随后每一发心跳都判它脱节、把它硬跳回去 —— 交叠白做，还多出一路抖动。所以这里钉的是：
 * 提前量生效时房间确实提前换歌，而把它设成 0 时行为与旧版一字不差。</p>
 *
 * <p>同时钉住几个「不许提前」的边界：单曲循环重播的就是同一首，队列空且不是列表循环时下一刻
 * 就是停止 —— 这两种情况下提前等于把最后一首的结尾整段截掉。</p>
 */
class RoomAutomixLeadTest {

    private static final String URL = "https://m8c.music.126.net/full.mp3";
    private static final long DURATION = 60_000L;

    private FakePlatform host;
    private MpConfig cfg;
    private Room room;
    private UUID owner;

    @BeforeEach
    void setUp() {
        host = new FakePlatform();
        Net.bind(host);

        cfg = new MpConfig();
        owner = host.addPlayer("房主");
        room = new Room(cfg, "r1", "ABCDE", owner, "房主", "测试房");
        Net.markCapable(owner);
    }

    private Track playable(String songId) {
        Track t = new Track("netease", songId);
        t.name = "曲目" + songId;
        t.durationMs = DURATION;
        t.adoptUrl(URL, System.currentTimeMillis() + 600_000L, DURATION, cfg);
        assertTrue(t.hasFreshUrl(), "测试前提：这首歌有可用链接");
        return t;
    }

    /** 一首在放、队列里正好一首「下一首」。 */
    private void playingWithOneQueued() {
        room.enqueue(owner, "房主", playable("1"), false);
        room.enqueue(owner, "房主", playable("2"), false);
        assertNotNull(room.current(), "测试前提：第一首在放");
        assertEquals(1, room.queue().size(), "测试前提：队列里正好只有一首「下一首」");
    }

    // ── 提前换歌 ──────────────────────────────────────────────────────────────

    @Test
    void withALeadTheRoomSwitchesBeforeTheTrackEnds() {
        cfg.automixLeadMs = 4000L;
        playingWithOneQueued();
        host.clear();

        room.seek(DURATION - 3_500L);      // 距结束 3.5s，小于提前量 4s
        room.tick();

        assertEquals(1, host.count(Proto.S_PLAY),
                "提前量生效时应当在曲末前就换歌，而不是等到距结束 250ms");
        assertEquals("曲目2", room.current().track.name, "换到的必须是下一首");
        assertTrue(room.isPlaying(), "换歌之后房间仍在播：下一首的 0 点就是这一时刻");
    }

    @Test
    void theDefaultLeadAloneIsEnoughToSwitchEarly() {
        // 默认就是开着的：一个字段都不配，房间也该在曲末前换歌。
        assertEquals(4000L, cfg.automixLeadMs, "默认必须是开着的");
        playingWithOneQueued();
        host.clear();

        room.seek(DURATION - 3_500L);
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY), "默认提前量生效时应当在曲末前换歌");
    }

    @Test
    void turningTheLeadOffRestoresTheOldAdvancePoint() {
        // 设成 0 = 关。想保住整条尾巴的服主走这条路，行为必须与旧版一字不差。
        cfg.automixLeadMs = 0L;
        playingWithOneQueued();
        host.clear();

        room.seek(DURATION - 3_500L);
        room.tick();
        assertEquals(0, host.count(Proto.S_PLAY), "提前量关着就不许提前换歌");

        room.seek(DURATION - 100L);        // 进入原来的 250ms 判据
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY), "关掉提前量时，原判据照常生效");
    }

    @Test
    void theLastTrackIsNotTruncated() {
        // 队列空了、也不是列表循环：下一刻就是停止。提前换歌会把它砍在结尾之前。
        cfg.automixLeadMs = 4000L;
        room.enqueue(owner, "房主", playable("1"), false);
        host.clear();

        room.seek(DURATION - 3_500L);
        room.tick();

        assertNotNull(room.current(), "不许因为提前量把最后一首也切掉");
        assertEquals(0, host.count(Proto.S_STOP), "也不许提前停止");
    }

    @Test
    void singleLoopIsNotAdvancedEarly() {
        // 单曲循环的「下一首」就是它自己。提前重播等于每次都截掉自己的结尾。
        cfg.automixLeadMs = 4000L;
        playingWithOneQueued();
        room.setRepeat(RepeatMode.ONE);
        host.clear();

        room.seek(DURATION - 3_500L);
        room.tick();
        assertEquals(0, host.count(Proto.S_PLAY), "单曲循环不许提前重播");

        room.seek(DURATION - 100L);
        room.tick();
        assertEquals(1, host.count(Proto.S_PLAY), "走到原判据时才重播同一首");
    }

    // ── 提前下发下一首 ────────────────────────────────────────────────────────

    @Test
    void queueBroadcastCarriesTheNextTrackWithItsUrl() {
        playingWithOneQueued();
        JsonObject next = host.last(Proto.S_QUEUE).getAsJsonObject("next");
        assertNotNull(next, "歌单广播要带上「下一首」，客户端才有东西可以提前下载");
        assertEquals("2", next.get("songId").getAsString());
        assertEquals(URL, next.get("url").getAsString(),
                "没有直链的话客户端只能各自再去解析一次，房间内就做不到全房同一条流");
    }

    @Test
    void theRoomSnapshotAlsoCarriesIt() {
        // 中途进房的人先拿快照、再等歌单广播；快照不带的话，他会有一整段「不知道下一首」。
        playingWithOneQueued();
        JsonObject roomJson = room.snapshot().getAsJsonObject("room");
        assertTrue(roomJson.has("next"), "快照里也要有下一首");
        assertEquals(URL, roomJson.getAsJsonObject("next").get("url").getAsString());
    }

    @Test
    void noNextIsAnnouncedWhenThereIsNoSecondTrack() {
        room.enqueue(owner, "房主", playable("1"), false);
        assertFalse(host.last(Proto.S_QUEUE).has("next"), "队列空就没有「下一首」可预告");
        assertNull(room.snapshot().getAsJsonObject("room").get("next"));
    }

    @Test
    void theNextTrackWithoutAFreshUrlIsAnnouncedWithoutAUrl() {
        // 链接还没凑齐时只发元数据：客户端要先靠它重建曲目去解析，但绝不能拿到一条死链。
        room.enqueue(owner, "房主", playable("1"), false);
        Track bare = new Track("netease", "9");
        bare.name = "会员曲";
        bare.durationMs = DURATION;
        room.enqueue(owner, "房主", bare, false);

        JsonObject next = host.last(Proto.S_QUEUE).getAsJsonObject("next");
        assertNotNull(next, "元数据仍然要发 —— 客户端靠它重建曲目");
        assertFalse(next.has("url"), "没有可用链接时不许带 url");
    }
}
