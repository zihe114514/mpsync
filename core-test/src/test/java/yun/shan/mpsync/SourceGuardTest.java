package yun.shan.mpsync;

import org.junit.jupiter.api.Test;
import yun.shan.mpsync.util.SourceGuard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可选 host 白名单参考。默认 allowedHosts 为空，这里只验证服主收窄后的行为。 */
class SourceGuardTest {

    @Test
    void defaultHostReferenceIncludesBilibiliAndYouTubeCdns() {
        // Bilibili playurl 实测返回 *.bilivideo.com；封面在 *.hdslb.com。
        assertTrue(SourceGuard.isAllowedUrl(
                "https://cn-tj-fx-01-04.bilivideo.com/upgcxcode/a.m4s", SourceGuard.DEFAULT_HOSTS));
        assertTrue(SourceGuard.isAllowedUrl(
                "https://i0.hdslb.com/bfs/archive/cover.jpg", SourceGuard.DEFAULT_HOSTS));

        // YouTube InnerTube 的 audio/mp4 直链在 *.googlevideo.com；封面在 *.ytimg.com。
        assertTrue(SourceGuard.isAllowedUrl(
                "https://rr3---sn-example.googlevideo.com/videoplayback", SourceGuard.DEFAULT_HOSTS));
        assertTrue(SourceGuard.isAllowedUrl(
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", SourceGuard.DEFAULT_HOSTS));
    }

    @Test
    void hostSuffixesCannotBeBypassedBySimilarPrefixes() {
        assertFalse(SourceGuard.isAllowedUrl(
                "https://evilbilivideo.com/a.m4s", SourceGuard.DEFAULT_HOSTS));
        assertFalse(SourceGuard.isAllowedUrl(
                "https://evilgooglevideo.com/videoplayback", SourceGuard.DEFAULT_HOSTS));
    }
}
