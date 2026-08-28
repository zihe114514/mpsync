package yun.shan.mpsync.room;

import com.google.gson.JsonObject;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.util.NeteaseGuard;

/**
 * A NetEase song as uploaded by a client: metadata plus the stream URL the uploader
 * resolved with its own session. The server stores it verbatim and never fetches audio.
 *
 * <p>Stream URLs are short-lived (NetEase signs them), hence {@link #urlExpiresAt}:
 * when a queued track reaches the front with a stale URL the room asks a client to
 * re-resolve it ({@link Proto#S_URL_NEEDED}) instead of pushing a dead link.
 */
public final class Track {

    public final String songId;
    public String name;
    public String artist;
    public String album;
    public String cover;
    public long durationMs;
    public String url;
    /** Epoch ms after which {@link #url} must be re-resolved; 0 = unknown (assume short). */
    public long urlExpiresAt;
    /** Length of the audio {@link #url} serves, as reported by the resolving client;
     *  0 when unknown. Shorter than {@link #durationMs} means a VIP trial clip. */
    public long urlDurationMs;

    public Track(String songId) {
        this.songId = songId;
    }

    /**
     * Validate + adopt a client-supplied track object.
     *
     * @throws IllegalArgumentException with a user-facing (Chinese) reason when the
     *         payload is not an acceptable NetEase track.
     */
    public static Track fromJson(JsonObject o, MpConfig cfg) {
        if (o == null) throw new IllegalArgumentException("缺少歌曲信息");
        String source = Proto.str(o, "source", Proto.SOURCE_NETEASE);
        if (!Proto.SOURCE_NETEASE.equalsIgnoreCase(source)) {
            throw new IllegalArgumentException("仅支持网易云音乐播放源");
        }
        String songId = Proto.str(o, "songId", "").trim();
        if (!NeteaseGuard.isNeteaseId(songId)) throw new IllegalArgumentException("无效的网易云歌曲 ID");

        Track t = new Track(songId);
        t.name = clip(Proto.str(o, "name", "未知歌曲"), 120);
        t.artist = clip(Proto.str(o, "artist", ""), 120);
        t.album = clip(Proto.str(o, "album", ""), 120);
        String cover = Proto.str(o, "cover", "");
        t.cover = NeteaseGuard.isAllowedUrl(cover, cfg.allowedHosts) ? cover : "";
        t.durationMs = Math.max(0L, Proto.num(o, "duration", 0L));
        String url = Proto.str(o, "url", "");
        long served = Proto.num(o, "urlDuration", 0L);
        if (!url.isBlank() && isTrialClip(served, t.durationMs, cfg)) {
            // One resolved link is shared with the whole room, so a trial clip resolved by
            // a non-VIP account would truncate the song for everyone.
            throw new IllegalArgumentException("该歌曲解析到的只是试听片段（需要 VIP），已拒绝");
        }
        t.adoptUrl(url, Proto.num(o, "urlExpiresAt", 0L), served, cfg);
        return t;
    }

    /**
     * Accept a (re-)resolved URL, dropping anything outside the allowlist and any trial
     * clip (a shorter-than-track stream, i.e. a VIP-gated preview).
     *
     * @return true when the URL was adopted
     */
    public boolean adoptUrl(String url, long expiresAt, long servedDurationMs, MpConfig cfg) {
        boolean ok = url != null && !url.isBlank()
                && NeteaseGuard.isAllowedUrl(url, cfg.allowedHosts)
                && !isTrialClip(servedDurationMs, durationMs, cfg);
        if (ok) {
            this.url = url;
            this.urlDurationMs = servedDurationMs;
            this.urlExpiresAt = expiresAt > 0
                    ? expiresAt
                    : System.currentTimeMillis() + cfg.assumedUrlLifetimeMs;
        } else {
            this.url = null;
            this.urlDurationMs = 0L;
            this.urlExpiresAt = 0L;
        }
        return ok;
    }

    /**
     * Whether a resolved stream is only a preview.
     *
     * <p>Both lengths come from NetEase itself (track length vs. length of the served
     * audio), so this needs no extra request. Unknown values pass: an old client that does
     * not report {@code urlDuration} must still be able to 点歌.
     */
    public static boolean isTrialClip(long servedMs, long trackMs, MpConfig cfg) {
        if (servedMs <= 0L || trackMs <= 0L) return false;
        return servedMs < trackMs * cfg.minStreamDurationRatio;
    }

    public boolean hasFreshUrl() {
        return url != null && !url.isBlank()
                && urlExpiresAt > System.currentTimeMillis() + 15_000L;
    }

    public String display() {
        return artist == null || artist.isBlank() ? name : name + " - " + artist;
    }

    public JsonObject toJson(boolean withUrl) {
        JsonObject o = new JsonObject();
        o.addProperty("source", Proto.SOURCE_NETEASE);
        o.addProperty("songId", songId);
        o.addProperty("name", name);
        o.addProperty("artist", artist);
        o.addProperty("album", album);
        o.addProperty("cover", cover);
        o.addProperty("duration", durationMs);
        if (withUrl && url != null) {
            o.addProperty("url", url);
            o.addProperty("urlExpiresAt", urlExpiresAt);
            if (urlDurationMs > 0) o.addProperty("urlDuration", urlDurationMs);
        }
        return o;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
