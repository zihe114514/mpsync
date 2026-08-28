package yun.shan.mpsync.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * MusicParty wire protocol (v{@value #VERSION}).
 *
 * <p>Two plugin channels carry a single UTF-8 JSON object each, framed as
 * {@code varint length + bytes} so payloads are not bound by
 * {@link PacketByteBuf#writeString(String)}'s 32k/short limit. JSON (instead of a
 * packed binary layout) is deliberate: the client mod and the server mod ship
 * separately, so unknown fields must be ignorable and new fields additive.
 *
 * <p>The server never touches NetEase itself. A client parses the share link,
 * resolves the stream URL through its own logged-in session, and uploads only
 * metadata + URL; the server redistributes it and owns the clock.
 */
public final class Proto {

    private Proto() {}

    /** Bump only for breaking changes; additive fields keep the same version. */
    public static final int VERSION = 1;

    /** client to server */
    public static final Identifier C2S = new Identifier("mpsync", "c2s");
    /** server to client */
    public static final Identifier S2C = new Identifier("mpsync", "s2c");

    /** Hard cap on a single frame (a room snapshot with a long queue stays far below). */
    public static final int MAX_FRAME = 512 * 1024;

    /** Only source accepted for tracks. */
    public static final String SOURCE_NETEASE = "netease";

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

    /** Field name of the op discriminator. */
    public static final String OP = "op";

    public static JsonObject msg(String op) {
        JsonObject o = new JsonObject();
        o.addProperty(OP, op);
        return o;
    }

    public static PacketByteBuf encode(JsonObject payload) {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(bytes.length + 5));
        buf.writeVarInt(bytes.length);
        buf.writeBytes(bytes);
        return buf;
    }

    /** @return the decoded object, or {@code null} when the frame is malformed. */
    public static JsonObject decode(PacketByteBuf buf) {
        try {
            int len = buf.readVarInt();
            if (len <= 0 || len > MAX_FRAME || len > buf.readableBytes()) return null;
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            JsonElement el = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        }
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
