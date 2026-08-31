package yun.shan.mpsync;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.Track;
import yun.shan.mpsync.support.FakePlatform;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时钟静默窗 —— 「网络不好时进度来回跳、根本听不成」那个问题的服务端一半。
 *
 * <p>症状的成因：命令帧（换歌／暂停／恢复／拖进度）刚下发的那几秒，全房客户端都在开流
 * （建连、缓冲、解码器起步），位置天然是散的。这时候按 {@code timeBroadcastTicks} 打一发
 * 心跳，每个还没缓冲完的客户端都会算出一个大偏差，于是集体 seek；seek 又要重新开流，
 * 下一发心跳还是同样的局面 —— 正反馈，进度就在那儿来回跳。</p>
 *
 * <p>修法是：命令帧照旧立刻下发，但之后 {@code clockGraceMs} 内不发心跳。这组测试把静默窗
 * 压到 200ms、心跳间隔压到 1 tick，这样不用真等 4 秒也能验证行为。</p>
 */
class RoomClockTest {

    private FakePlatform host;
    private MpConfig cfg;
    private Room room;
    private UUID owner;

    private static final String URL = "https://m8c.music.126.net/full.mp3";

    @BeforeEach
    void setUp() {
        host = new FakePlatform();
        Net.bind(host);

        cfg = new MpConfig();
        cfg.clockGraceMs = 200L;      // 真实默认 4000，压小以免测试真的睡 4 秒
        cfg.timeBroadcastTicks = 1;   // 每 tick 都到广播点，便于精确计数

        owner = host.addPlayer("房主");
        room = new Room(cfg, "r1", "ABCDE", owner, "房主", "测试房");
        Net.markCapable(owner);
    }

    private Track playable(String songId) {
        Track t = new Track("netease", songId);
        t.name = "曲目" + songId;
        t.durationMs = 300_000L;
        t.adoptUrl(URL, System.currentTimeMillis() + 600_000L, 300_000L, cfg);
        assertTrue(t.hasFreshUrl(), "测试前提：这首歌有可用链接");
        return t;
    }

    private void tick(int times) {
        for (int i = 0; i < times; i++) room.tick();
    }

    private static void sleepPastGrace() {
        try {
            Thread.sleep(320L);   // > clockGraceMs(200)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void trackStartSuppressesHeartbeatsForTheGraceWindow() {
        room.enqueue(owner, "房主", playable("1"), false);
        assertEquals(1, host.count(Proto.S_PLAY), "命令帧本身必须立刻下发");

        host.clear();
        tick(20);
        assertEquals(0, host.count(Proto.S_TIME),
                "换歌之后的静默窗内不许发心跳 —— 那会儿全房都在开流，心跳只会引发一轮集体误判 seek");

        sleepPastGrace();
        tick(1);
        assertEquals(1, host.count(Proto.S_TIME), "静默窗过了就要正常校准");
    }

    @Test
    void heartbeatIsLabelledSoClientsCanTellItFromACommand() {
        room.enqueue(owner, "房主", playable("1"), false);
        sleepPastGrace();
        host.clear();
        tick(1);

        JsonObject beat = host.last(Proto.S_TIME);
        assertNotNull(beat, "静默窗后应当有心跳");
        assertTrue(beat.has("heartbeat") && beat.get("heartbeat").getAsBoolean(),
                "心跳要自报身份：客户端据此走软/硬偏差那套判定，而不是像 play/seek 一样无条件对齐");
        assertTrue(beat.has("pos") && beat.has("serverTime") && beat.has("seq"),
                "客户端要靠 serverTime 补单向延迟、靠 seq 丢弃乱序命令");

        // 命令帧不带这个字段，老客户端读不到也不受影响（缺省视为 false）。
        JsonObject play = host.last(Proto.S_PLAY);
        assertFalse(play != null && play.has("heartbeat"), "命令帧不该带 heartbeat 标记");
    }

    @Test
    void pauseResumeAndSeekEachReArmTheGraceWindow() {
        room.enqueue(owner, "房主", playable("1"), false);
        sleepPastGrace();

        host.clear();
        room.pause();
        tick(20);
        assertEquals(0, host.count(Proto.S_TIME), "暂停后静默");

        sleepPastGrace();
        host.clear();
        room.resume();
        tick(20);
        assertEquals(0, host.count(Proto.S_TIME), "恢复后静默：客户端要重新起流");

        sleepPastGrace();
        host.clear();
        room.seek(30_000L);
        tick(20);
        assertEquals(0, host.count(Proto.S_TIME), "拖进度后静默");
        assertEquals(1, host.count(Proto.S_SEEK), "但 seek 命令本身立刻下发");
    }

    @Test
    void roomWaitingForAUrlNeverSendsHeartbeats() {
        // 卡在等链接时位置没有意义，发心跳只会让客户端把「还没开始」当成「脱节了」。
        Track bare = new Track("netease", "9");
        bare.name = "会员曲";
        bare.durationMs = 300_000L;
        assertFalse(bare.hasFreshUrl());

        room.enqueue(owner, "房主", bare, false);
        assertEquals(1, host.count(Proto.S_URL_NEEDED), "该向全房要链接");

        sleepPastGrace();
        host.clear();
        tick(20);
        assertEquals(0, host.count(Proto.S_TIME), "waitingUrl 期间不许发心跳");
    }

    @Test
    void adoptingASuppliedUrlStartsPlaybackAndReArmsGrace() {
        Track bare = new Track("netease", "9");
        bare.name = "会员曲";
        bare.durationMs = 300_000L;
        room.enqueue(owner, "房主", bare, false);

        sleepPastGrace();
        host.clear();
        room.supplyUrl("netease", "9", URL, System.currentTimeMillis() + 600_000L, 300_000L);
        assertEquals(1, host.count(Proto.S_PLAY), "拿到链接就起播");

        tick(20);
        assertEquals(0, host.count(Proto.S_TIME),
                "等到链接之后的起播等同于换歌，同样要让出静默窗");
    }

    @Test
    void suppliedUrlForAnotherPlatformIsNotAppliedToThisTrack() {
        // url_supply 必须按 source:songId 认领。只比 songId 的话，qq 的链接会被塞进网易云的曲目。
        Track bare = new Track("netease", "9");
        bare.durationMs = 300_000L;
        room.enqueue(owner, "房主", bare, false);
        host.clear();

        room.supplyUrl("qq", "9", URL, System.currentTimeMillis() + 600_000L, 300_000L);
        assertEquals(0, host.count(Proto.S_PLAY), "平台不同就不是同一首歌");
        assertFalse(room.isPlaying());
    }

    @Test
    void trialClipAnswerIsRejectedAndTheRoomKeepsWaiting() {
        Track bare = new Track("netease", "9");
        bare.durationMs = 300_000L;
        room.enqueue(owner, "房主", bare, false);
        host.clear();

        // 非会员解析到 30 秒试听片段：接受了就等于全房只能听 30 秒。
        room.supplyUrl("netease", "9", URL, System.currentTimeMillis() + 600_000L, 30_000L);
        assertEquals(0, host.count(Proto.S_PLAY));
        assertFalse(room.isPlaying(), "该继续等，给房里的会员留出应答机会");
    }

    @Test
    void positionFollowsTheServerClock() {
        room.enqueue(owner, "房主", playable("1"), false);
        room.seek(45_000L);
        long pos = room.positionMs();
        assertTrue(pos >= 45_000L && pos < 46_000L, "拖到 45s 后位置应当从 45s 起算，实际 " + pos);

        room.pause();
        long paused = room.positionMs();
        try {
            Thread.sleep(60L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(paused, room.positionMs(), "暂停期间位置必须冻住");
    }

    @Test
    void seekIsClampedToTheTrackLength() {
        room.enqueue(owner, "房主", playable("1"), false);
        room.seek(999_999_999L);
        assertTrue(room.positionMs() <= 300_000L, "不能拖到曲目长度之外");
        room.seek(-5000L);
        assertEquals(0L, room.positionMs());
    }

    @Test
    void duplicateEnqueueOfTheSameTrackIsRejectedPerPlatform() {
        room.enqueue(owner, "房主", playable("1"), false);   // 直接开始播
        room.enqueue(owner, "房主", playable("2"), false);   // 进队列
        // 同平台同 id → 重复
        assertThrowsIllegalState(() -> room.enqueue(owner, "房主", playable("2"), false));
        // 不同平台同 id → 不算重复
        Track qq = new Track("qq", "2");
        qq.durationMs = 300_000L;
        qq.adoptUrl(URL, System.currentTimeMillis() + 600_000L, 300_000L, cfg);
        room.enqueue(owner, "房主", qq, false);
    }

    private static void assertThrowsIllegalState(Runnable r) {
        try {
            r.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("本该抛 IllegalStateException（这首歌已经在队列里了）");
    }
}
