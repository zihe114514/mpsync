package yun.shan.mpsync;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.room.Track;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 曲目校验：入队这道门上所有的把关都在这里。 */
class TrackTest {

    private static final String URL = "https://m8c.music.126.net/full.mp3";

    private static JsonObject track(String source, String songId, long durationMs) {
        JsonObject o = new JsonObject();
        if (source != null) o.addProperty("source", source);
        o.addProperty("songId", songId);
        o.addProperty("name", "测试曲目");
        o.addProperty("duration", durationMs);
        return o;
    }

    @Test
    void oldClientWithoutSourceFieldIsTreatedAsNetease() {
        Track t = Track.fromJson(track(null, "123456", 200_000L), new MpConfig());
        assertEquals("netease", t.source);
        assertEquals("netease:123456", t.key());
    }

    @Test
    void identityIsSourcePlusSongIdNotSongIdAlone() {
        // 跨平台之后不同平台完全可能出现相同的数字 id。只按 songId 比，两首歌会撞成一首：
        // 去重把第二首拒了，更糟的是 url_supply 会把 A 平台的链接塞进 B 平台的曲目里。
        MpConfig cfg = new MpConfig();
        Track a = Track.fromJson(track("netease", "2001", 200_000L), cfg);
        Track b = Track.fromJson(track("qq", "2001", 200_000L), cfg);
        assertEquals(a.songId, b.songId);
        assertFalse(a.key().equals(b.key()), "同 id 不同平台必须是两个不同的身份");
    }

    @Test
    void invalidSourceTokenIsRejected() {
        MpConfig cfg = new MpConfig();
        assertThrows(IllegalArgumentException.class,
                () -> Track.fromJson(track("net:ease", "1", 1000L), cfg));
    }

    @Test
    void serverOwnerCanNarrowTheAllowedPlatforms() {
        MpConfig cfg = new MpConfig();
        cfg.allowedSources = new ArrayList<>(List.of("netease"));
        assertEquals("netease", Track.fromJson(track("netease", "1", 1000L), cfg).source);
        assertThrows(IllegalArgumentException.class,
                () -> Track.fromJson(track("kugou", "abc", 1000L), cfg));
    }

    @Test
    void badSongIdIsRejected() {
        MpConfig cfg = new MpConfig();
        assertThrows(IllegalArgumentException.class, () -> Track.fromJson(track("netease", "", 1000L), cfg));
        assertThrows(IllegalArgumentException.class, () -> Track.fromJson(track("netease", "0", 1000L), cfg));
        // 冒号是身份键的分隔符，出现在 id 里会让两首歌撞键
        assertThrows(IllegalArgumentException.class, () -> Track.fromJson(track("netease", "a:b", 1000L), cfg));
    }

    @Test
    void trialClipIsRejectedBecauseOneLinkIsSharedByTheWholeRoom() {
        MpConfig cfg = new MpConfig();
        JsonObject o = track("netease", "123456", 200_000L);
        o.addProperty("url", URL);
        o.addProperty("urlDuration", 30_000L);   // 30 秒试听片段
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Track.fromJson(o, cfg));
        assertTrue(e.getMessage().contains("试听"), "拒绝原因要能直接给用户看：" + e.getMessage());
    }

    @Test
    void fullLengthStreamIsAccepted() {
        MpConfig cfg = new MpConfig();
        JsonObject o = track("netease", "123456", 200_000L);
        o.addProperty("url", URL);
        o.addProperty("urlDuration", 200_000L);
        o.addProperty("urlExpiresAt", System.currentTimeMillis() + 600_000L);
        Track t = Track.fromJson(o, cfg);
        assertTrue(t.hasFreshUrl());
    }

    @Test
    void unknownUrlDurationStillPassesForOldClients() {
        // 老客户端不上报 urlDuration，不能因此拒掉它的点歌。
        MpConfig cfg = new MpConfig();
        JsonObject o = track("netease", "123456", 200_000L);
        o.addProperty("url", URL);
        Track t = Track.fromJson(o, cfg);
        assertTrue(t.hasFreshUrl(), "没上报时长的应当放行，有效期用 assumedUrlLifetimeMs 兜");
    }

    @Test
    void privateAndLoopbackUrlsAreNeverForwarded() {
        // 房间会把这条链接下发给全部成员。允许内网地址等于把房间做成内网探测器。
        MpConfig cfg = new MpConfig();
        Track t = new Track("netease", "1");
        t.durationMs = 200_000L;
        for (String bad : List.of(
                "http://127.0.0.1/a.mp3",
                "http://localhost/a.mp3",
                "http://10.0.0.5/a.mp3",
                "http://192.168.1.9/a.mp3",
                "http://[::1]/a.mp3",
                "http://169.254.1.1/a.mp3",
                "ftp://example.com/a.mp3",
                "http://user@evil.example.com/a.mp3")) {
            assertFalse(t.adoptUrl(bad, 0L, 0L, cfg), "本该拒绝：" + bad);
            assertNull(t.url);
        }
        assertTrue(t.adoptUrl(URL, System.currentTimeMillis() + 600_000L, 200_000L, cfg));
    }

    @Test
    void expiringLinkIsNotConsideredFresh() {
        // 队列里的歌轮到播放时，链接快过期就要重新解析，而不是把一条快死的链接推出去。
        MpConfig cfg = new MpConfig();
        Track t = new Track("netease", "1");
        t.durationMs = 200_000L;
        t.adoptUrl(URL, System.currentTimeMillis() + 5_000L, 200_000L, cfg);
        assertFalse(t.hasFreshUrl(), "只剩 5 秒的链接不算新鲜");
    }

    @Test
    void metadataIsClippedAndNewlinesStripped() {
        MpConfig cfg = new MpConfig();
        JsonObject o = track("netease", "1", 1000L);
        o.addProperty("name", "a\nb");
        o.addProperty("artist", "x".repeat(500));
        Track t = Track.fromJson(o, cfg);
        assertFalse(t.name.contains("\n"), "换行要清掉，否则会把聊天行撑断");
        assertTrue(t.artist.length() <= 120);
    }
}
