package yun.shan.mpsync.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;

/**
 * MusicParty wire protocol (v{@value #VERSION}).
 *
 * <h2>v2 新增（2026-09）</h2>
 * 全部是<b>加法</b>：新的 op 与新的字段。旧客户端不认识的 op 会走 default 分支被忽略，
 * 新字段读不到就用缺省值，所以 v1 客户端连 v2 服务端、v2 客户端连 v1 服务端都仍然能听歌 ——
 * 只是新功能（成员 Mod/VIP 展示、公开房间、待处理邀请、个人暂停、服务器频道、投票）不可用。
 * {@link #VERSION} 之所以还是要 +1，是给客户端一个「能不能显示这些页」的判据。
 *
 * <p>Two plugin channels carry a single UTF-8 JSON object each, framed as
 * {@code varint length + bytes} so payloads are not bound by
 * Minecraft's own string writer. JSON (instead of a
 * packed binary layout) is deliberate: the client mod and the server mod ship
 * separately, so unknown fields must be ignorable and new fields additive.
 *
 * <p>The framing is hand-written rather than delegated to Minecraft's buffer helpers, so
 * Fabric and Paper on every supported version produce byte-identical frames — that byte
 * identity is what lets a 1.20.1 client share a room hosted by a 1.21.11 server.
 *
 * <p>The server never touches NetEase itself. A client parses the share link,
 * resolves the stream URL through its own logged-in session, and uploads only
 * metadata + URL; the server redistributes it and owns the clock.
 */
public final class Proto {

    private Proto() {}

    /**
     * Bump only for breaking changes; additive fields keep the same version.
     *
     * <p>v2：房间身份/Mod/VIP 上报、全服在线态、公开房间、待处理邀请、个人暂停、
     * 服务端代解析、服务器频道与投票。协议本身仍向下兼容，见类注释。
     */
    public static final int VERSION = 2;

    /** Channel namespace; the platform adapter turns these into its own identifier type. */
    public static final String NAMESPACE = "mpsync";
    public static final String C2S_PATH = "c2s";
    public static final String S2C_PATH = "s2c";
    /** client to server */
    public static final String C2S = NAMESPACE + ":" + C2S_PATH;
    /** server to client */
    public static final String S2C = NAMESPACE + ":" + S2C_PATH;

    /** Hard cap on a single frame (a room snapshot with a long queue stays far below). */
    public static final int MAX_FRAME = 512 * 1024;

    /**
     * 曲目来源平台令牌。
     *
     * <p>以前只接受 {@code netease}。现在服务端只做「转发点歌人解析出来的直链」这件事，
     * 平台本身对服务端没有意义 —— 它既不解析也不取流。所以这里不再枚举白名单，只校验令牌
     * 格式，实际允许哪些平台由 {@code MpConfig.allowedSources} 决定。</p>
     *
     * <p>缺省值仍是 {@code netease}：老客户端不发 {@code source} 字段，必须继续当网易云处理。</p>
     */
    public static final String SOURCE_NETEASE = "netease";
    public static final String SOURCE_QQ      = "qq";
    public static final String SOURCE_KUGOU   = "kugou";
    public static final String SOURCE_GD      = "gd";

    /** 令牌格式：小写字母、数字、下划线，1~16 位。用来做键，所以不能含分隔符。 */
    public static boolean isValidSource(String source) {
        if (source == null || source.isEmpty() || source.length() > 16) return false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    // ── C2S ops ──────────────────────────────────────────────────────────────
    public static final String C_HELLO       = "hello";
    public static final String C_CREATE      = "create_room";
    public static final String C_INVITE      = "invite";
    public static final String C_JOIN        = "join";
    public static final String C_LEAVE       = "leave";
    public static final String C_DISBAND     = "disband";
    public static final String C_ENQUEUE     = "enqueue";      // member 点歌
    public static final String C_PLAY_NOW    = "play_now";     // owner/admin: play this track immediately
    public static final String C_PLAY_INDEX  = "play_index";   // owner/admin: jump to queue index
    public static final String C_REMOVE      = "remove";
    public static final String C_MOVE        = "move";
    public static final String C_CLEAR       = "clear_queue";
    public static final String C_PAUSE       = "pause";
    public static final String C_RESUME      = "resume";
    public static final String C_SEEK        = "seek";
    public static final String C_NEXT        = "next";
    public static final String C_PREV        = "prev";
    public static final String C_REPEAT      = "repeat";
    public static final String C_SET_ROLE    = "set_role";     // grant/revoke admin
    public static final String C_KICK        = "kick";
    public static final String C_TRANSFER    = "transfer";     // owner only
    public static final String C_PROGRESS    = "progress";     // heartbeat
    public static final String C_SYNC        = "sync";         // request a full snapshot
    public static final String C_URL_SUPPLY  = "url_supply";   // answer to S_URL_NEEDED

    // ── C2S ops added in v2 ──────────────────────────────────────────────────
    /** 客户端自报身份：Mod 名与版本、已登录（可代解析）的平台、是否愿意贡献 VIP、本地在听。 */
    public static final String C_PROFILE        = "profile";
    /** 请求全服在线态（谁在线、用什么客户端、有没有 VIP、正在听什么）。 */
    public static final String C_PRESENCE       = "presence";
    /** 请求公开房间列表。 */
    public static final String C_ROOMS          = "rooms";
    /** 房主/管理员切换房间是否公开。 */
    public static final String C_SET_PUBLIC     = "set_public";
    /** 请求待处理邀请列表（邀请不再只靠聊天栏一行字）。 */
    public static final String C_INVITES        = "invites";
    public static final String C_INVITE_ACCEPT  = "invite_accept";
    public static final String C_INVITE_DECLINE = "invite_decline";
    /** 个人暂停：只停自己，不动房间时钟。 */
    public static final String C_SELF_PAUSE     = "self_pause";
    /** 加入服务器频道（全服公开大房间）。 */
    public static final String C_CHANNEL_JOIN   = "channel_join";
    /** 发起投票：{@code kind = skip | kick}。 */
    public static final String C_VOTE_START     = "vote_start";
    /** 投票：{@code voteId} + {@code agree}。 */
    public static final String C_VOTE_CAST      = "vote_cast";

    // ── S2C ops ──────────────────────────────────────────────────────────────
    public static final String S_HELLO       = "hello";
    public static final String S_ROOM_STATE  = "room_state";   // full snapshot
    public static final String S_QUEUE       = "queue";        // queue/history only
    public static final String S_MEMBERS     = "members";
    public static final String S_PLAY        = "play";
    public static final String S_PAUSE       = "pause";
    public static final String S_RESUME      = "resume";
    public static final String S_SEEK        = "seek";
    public static final String S_STOP        = "stop";
    public static final String S_TIME        = "time";         // authoritative clock tick
    public static final String S_INVITE      = "invite";
    public static final String S_LEFT        = "left";
    public static final String S_NOTICE      = "notice";
    public static final String S_ERROR       = "error";
    public static final String S_URL_NEEDED  = "url_needed";   // ask a client to re-resolve a stream URL

    // ── S2C ops added in v2 ──────────────────────────────────────────────────
    /** 全服在线态列表。 */
    public static final String S_PRESENCE     = "presence";
    /** 公开房间列表。 */
    public static final String S_ROOMS        = "rooms";
    /** 待处理邀请列表。 */
    public static final String S_INVITES      = "invites";
    /** 投票开始／进度／结束。 */
    public static final String S_VOTE         = "vote";
    /** 被封禁：带原因与解封时间，客户端据此把一起听整块置灰。 */
    public static final String S_BANNED       = "banned";

    /** Field name of the op discriminator. */
    public static final String OP = "op";

    public static JsonObject msg(String op) {
        JsonObject o = new JsonObject();
        o.addProperty(OP, op);
        return o;
    }

    /** Frame one JSON object: varint payload length followed by the UTF-8 bytes. */
    public static byte[] encode(JsonObject payload) {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        byte[] head = varInt(bytes.length);
        byte[] out = new byte[head.length + bytes.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(bytes, 0, out, head.length, bytes.length);
        return out;
    }

    /**
     * Decode one frame.
     *
     * <p>Tolerates a payload that arrives without the varint header (a bridge or proxy may
     * hand over the bare JSON): when the first byte is '{' the whole array is parsed as-is.
     *
     * @return the decoded object, or {@code null} when the frame is malformed
     */
    public static JsonObject decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            if (data[0] == '{') return parse(new String(data, StandardCharsets.UTF_8));
            int len = 0, shift = 0, i = 0;
            while (i < data.length) {
                byte b = data[i++];
                len |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) return null;
            }
            if (len <= 0 || len > MAX_FRAME || len > data.length - i) return null;
            return parse(new String(data, i, len, StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject parse(String json) {
        JsonElement el = JsonParser.parseString(json);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    /** Minecraft-style varint, hand-written so Paper produces the same bytes as Fabric. */
    private static byte[] varInt(int value) {
        byte[] tmp = new byte[5];
        int n = 0;
        int v = value;
        do {
            byte b = (byte) (v & 0x7F);
            v >>>= 7;
            if (v != 0) b |= (byte) 0x80;
            tmp[n++] = b;
        } while (v != 0);
        byte[] out = new byte[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    // ── lenient accessors (an older/newer peer may omit anything) ─────────────

    public static String str(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsString(); } catch (Throwable ignored) { return def; }
    }

    public static long num(JsonObject o, String key, long def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsLong(); } catch (Throwable ignored) { return def; }
    }

    public static int i32(JsonObject o, String key, int def) {
        return (int) num(o, key, def);
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try { return o.get(key).getAsBoolean(); } catch (Throwable ignored) { return def; }
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return null;
        return o.getAsJsonObject(key);
    }

    public static JsonArray arr(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return null;
        return o.getAsJsonArray(key);
    }
}
