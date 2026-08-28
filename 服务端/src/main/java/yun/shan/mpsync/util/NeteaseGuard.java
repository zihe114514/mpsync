package yun.shan.mpsync.util;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * NetEase-only gate. The feature spec restricts synced playback to NetEase Cloud Music,
 * so every uploaded track is validated here before it can enter a room queue:
 * numeric song id, and a stream URL whose host belongs to NetEase's CDN.
 *
 * <p>This is a redistribution guard, not a security boundary — clients could still lie
 * about titles. It exists so a room can never be turned into a relay for arbitrary
 * third-party URLs (which would also break the client's decoder assumptions).
 */
public final class NeteaseGuard {

    private NeteaseGuard() {}

    /** Suffix allowlist for stream/cover URLs, overridable via config. */
    public static final List<String> DEFAULT_HOSTS = List.of(
            "music.126.net",
            "music.163.com",
            "126.net",
            "163.com",
            "netease.com"
    );

    public static boolean isNeteaseId(String songId) {
        if (songId == null || songId.isBlank() || songId.length() > 20) return false;
        for (int i = 0; i < songId.length(); i++) {
            if (songId.charAt(i) < '0' || songId.charAt(i) > '9') return false;
        }
        return !songId.equals("0");
    }

    /** @param hosts allowed host suffixes; pass {@link #DEFAULT_HOSTS} for the built-ins. */
    public static boolean isAllowedUrl(String url, List<String> hosts) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Throwable ignored) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String suffix : hosts) {
            String s = suffix.toLowerCase(Locale.ROOT);
            if (host.equals(s) || host.endsWith("." + s)) return true;
        }
        return false;
    }
}
