package yun.shan.mpsync.room;

import com.google.gson.JsonObject;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Owns every {@link Room} on this server and routes the client protocol into them.
 *
 * <p>Rooms are deliberately in-memory: a listening session is tied to the players who
 * are online, and NetEase stream URLs expire within minutes, so persisting rooms across
 * restarts would restore a queue whose links are all dead.
 *
 * <p>All entry points assume the server's main thread; the platform adapter hops there
 * before calling in (see {@code MpPlatform#execute}).
 */
public final class RoomManager {

    /** Ambiguous glyphs (0/O, 1/I) removed — codes get read aloud in voice chat. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private final MpConfig cfg;
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<String, String> byCode = new HashMap<>();
    private final Map<UUID, String> playerRoom = new HashMap<>();

    public RoomManager(MpConfig cfg) {
        this.cfg = cfg;
    }

    public MpConfig config() { return cfg; }

    public Collection<Room> rooms() { return rooms.values(); }

    public Room roomOf(UUID uuid) {
        String id = playerRoom.get(uuid);
        return id == null ? null : rooms.get(id);
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    /** @throws IllegalStateException with a user-facing reason */
    public Room create(UUID uuid, String name) {
        if (roomOf(uuid) != null) throw new IllegalStateException("你已经在一个房间里了");
        if (cfg.maxRooms > 0 && rooms.size() >= cfg.maxRooms) {
            throw new IllegalStateException("服务器房间数量已达上限");
        }
        String id = newRoomId();
        String code = newCode();
        Room room = new Room(cfg, id, code, uuid, Net.nameOf(uuid), name);
        rooms.put(id, room);
        byCode.put(code, id);
        playerRoom.put(uuid, id);
        room.sendSnapshot(uuid);
        Net.notice(uuid, "房间已创建，邀请码：" + code);
        return room;
    }

    /** @return the joined room; throws with a user-facing reason otherwise */
    public Room join(UUID uuid, String codeOrId) {
        String name = Net.nameOf(uuid);
        Room existing = roomOf(uuid);
        if (existing != null) {
            if (existing.code.equalsIgnoreCase(codeOrId) || existing.id.equalsIgnoreCase(codeOrId)) {
                existing.join(uuid, name);
                existing.sendSnapshot(uuid);
                return existing;
            }
            throw new IllegalStateException("你已经在一个房间里了，请先离开");
        }
        Room room = resolve(codeOrId);
        if (room == null) {
            // Joining by invite alone is allowed (the client remembers the room id).
            room = rooms.values().stream().filter(r -> r.invited(uuid)).findFirst().orElse(null);
        }
        if (room == null) throw new IllegalStateException("邀请码无效或房间不存在");
        if (room.full() && !room.has(uuid)) throw new IllegalStateException("房间已满");
        room.join(uuid, name);
        playerRoom.put(uuid, room.id);
        room.broadcastState();
        room.notice(name + " 加入了房间");
        return room;
    }

    public Room resolve(String codeOrId) {
        if (codeOrId == null || codeOrId.isBlank()) return null;
        String key = codeOrId.trim();
        String id = byCode.get(key.toUpperCase(Locale.ROOT));
        Room room = id != null ? rooms.get(id) : null;
        return room != null ? room : rooms.get(key);
    }

    public void leave(UUID uuid) {
        Room room = roomOf(uuid);
        if (room == null) return;
        String name = Net.nameOf(uuid);
        boolean wasOwner = room.isOwner(uuid);
        playerRoom.remove(uuid);
        room.remove(uuid);
        Net.send(uuid, left("你已离开一起听房间"));
        if (room.members().isEmpty()) {
            drop(room);
        } else {
            room.broadcastState();
            room.notice(name + (wasOwner ? " (房主) 离开了房间" : " 离开了房间"));
        }
    }

    /** Owner-only teardown: everyone is dropped and the room id/code are released. */
    public void disband(Room room, String reason) {
        for (UUID uuid : new ArrayList<>(room.members().stream().map(m -> m.uuid).toList())) {
            playerRoom.remove(uuid);
            room.send(uuid, left(reason));
        }
        drop(room);
    }

    private void drop(Room room) {
        rooms.remove(room.id);
        byCode.remove(room.code);
        for (Member m : new ArrayList<>(room.members())) playerRoom.remove(m.uuid);
    }

    private static JsonObject left(String reason) {
        JsonObject m = Proto.msg(Proto.S_LEFT);
        m.addProperty("reason", reason == null ? "" : reason);
        return m;
    }

    public void onPlayerJoin(UUID uuid) {
        Room room = roomOf(uuid);
        if (room == null) return;
        room.join(uuid, Net.nameOf(uuid));   // re-attach the seat kept across the disconnect
        room.sendSnapshot(uuid);
        room.broadcastState();
    }

    public void onPlayerQuit(UUID uuid) {
        Net.forget(uuid);
        Room room = roomOf(uuid);
        if (room == null) return;
        room.markOffline(uuid);
        room.broadcastState();
    }

    public void tick() {
        if (rooms.isEmpty()) return;
        for (Room room : new ArrayList<>(rooms.values())) {
            try {
                room.tick();
            } catch (Throwable t) {
                // One bad room must not take the server tick down with it.
                Net.warn("[mpsync] room " + room.id + " tick failed", t);
            }
            if (room.dead()) disband(room, "房间已因长时间无人而关闭");
        }
    }

    public void shutdown() {
        rooms.clear();
        byCode.clear();
        playerRoom.clear();
    }

    // ── protocol dispatch ────────────────────────────────────────────────────

    /** Handle one decoded C2S frame. Runs on the server thread. */
    public void handle(UUID uuid, JsonObject msg) {
        String op = Proto.str(msg, Proto.OP, "");
        if (op.isEmpty()) return;
        try {
            switch (op) {
                case Proto.C_HELLO -> {
                    JsonObject hello = Proto.msg(Proto.S_HELLO);
                    hello.addProperty("proto", Proto.VERSION);
                    hello.addProperty("server", "mpsync");
                    hello.addProperty("membersCanEnqueue", cfg.membersCanEnqueue);
                    hello.addProperty("maxQueuePerMember", cfg.maxQueuePerMember);
                    // 时钟纠偏阈值由服务端统一下发。这是让「同一房间里不同客户端口径一致」的关键：
                    // modernMP / MuoniumPlayer、1.20.1 到 26.2，全都用这一套数，
                    // 不会出现同一发心跳有人跳有人不跳、互相拉扯的局面。
                    // 老客户端读不到这几个字段，就用它自带的默认值（与这里的默认值相同）。
                    hello.addProperty("clockGraceMs", cfg.clockGraceMs);
                    hello.addProperty("softDriftMs", cfg.softDriftMs);
                    hello.addProperty("hardDriftMs", cfg.hardDriftMs);
                    hello.addProperty("resyncCooldownMs", cfg.resyncCooldownMs);
                    hello.addProperty("driftConfirmHits", cfg.driftConfirmHits);
                    // 心跳间隔也一并告知：客户端据此知道「多久没收到心跳算掉线」，
                    // 也能推出 driftConfirmHits 次确认大约要花多少时间。
                    hello.addProperty("timeBroadcastTicks", cfg.timeBroadcastTicks);
                    Net.send(uuid, hello);
                    Room room = roomOf(uuid);
                    if (room != null) room.sendSnapshot(uuid);
                }
                case Proto.C_CREATE -> create(uuid, Proto.str(msg, "name", ""));
                case Proto.C_JOIN   -> join(uuid, Proto.str(msg, "code", Proto.str(msg, "room", "")));
                case Proto.C_LEAVE  -> leave(uuid);
                case Proto.C_SYNC   -> {
                    Room room = roomOf(uuid);
                    if (room != null) room.sendSnapshot(uuid);
                    else Net.send(uuid, left(""));
                }
                default -> handleInRoom(uuid, op, msg);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            Net.error(uuid, e.getMessage());
        } catch (Throwable t) {
            Net.warn("[mpsync] op " + op + " from " + Net.nameOf(uuid) + " failed", t);
            Net.error(uuid, "操作失败");
        }
    }

    private void handleInRoom(UUID uuid, String op, JsonObject msg) {
        Room room = roomOf(uuid);
        if (room == null) {
            Net.error(uuid, "你还没有加入一起听房间");
            return;
        }
        boolean control = room.controls(uuid);

        switch (op) {
            case Proto.C_ENQUEUE -> {
                if (!control && !cfg.membersCanEnqueue) {
                    Net.error(uuid, "本房间未开放点歌");
                    return;
                }
                Track track = Track.fromJson(Proto.obj(msg, "track"), cfg);
                String who = Net.nameOf(uuid);
                QueueEntry entry = room.enqueue(uuid, who, track, Proto.bool(msg, "next", false));
                room.notice(who + " 点歌：" + entry.track.display());
            }
            case Proto.C_PLAY_NOW -> {
                if (requireControl(uuid, control)) return;
                room.playNow(uuid, Net.nameOf(uuid), Track.fromJson(Proto.obj(msg, "track"), cfg));
            }
            case Proto.C_PLAY_INDEX -> {
                if (requireControl(uuid, control)) return;
                long entryId = Proto.num(msg, "entryId", -1L);
                boolean ok = entryId > 0
                        ? room.playEntry(entryId)
                        : playByIndex(room, Proto.i32(msg, "index", -1));
                if (!ok) Net.error(uuid, "这首歌已不在队列中");
            }
            case Proto.C_REMOVE -> {
                long entryId = Proto.num(msg, "entryId", -1L);
                QueueEntry entry = room.findEntry(entryId);
                if (entry == null) { Net.error(uuid, "歌曲不在队列中"); return; }
                boolean own = cfg.requesterCanRemoveOwn && uuid.equals(entry.requester);
                if (!control && !own) { Net.error(uuid, "只能删除自己点的歌"); return; }
                room.removeEntry(entryId);
            }
            case Proto.C_MOVE -> {
                if (requireControl(uuid, control)) return;
                room.moveEntry(Proto.num(msg, "entryId", -1L), Proto.i32(msg, "to", 0));
            }
            case Proto.C_CLEAR -> {
                if (requireControl(uuid, control)) return;
                room.clearQueue();
            }
            case Proto.C_PAUSE -> {
                if (requireControl(uuid, control)) return;
                room.pause();
            }
            case Proto.C_RESUME -> {
                if (requireControl(uuid, control)) return;
                room.resume();
            }
            case Proto.C_SEEK -> {
                if (requireControl(uuid, control)) return;
                room.seek(Proto.num(msg, "pos", 0L));
            }
            case Proto.C_NEXT -> {
                if (requireControl(uuid, control)) return;
                room.next(false);
            }
            case Proto.C_PREV -> {
                if (requireControl(uuid, control)) return;
                room.prev();
            }
            case Proto.C_REPEAT -> {
                if (requireControl(uuid, control)) return;
                room.setRepeat(RepeatMode.of(Proto.str(msg, "mode", "off")));
            }
            case Proto.C_INVITE -> {
                UUID target = targetOf(msg);
                if (target == null) { Net.error(uuid, "找不到该玩家"); return; }
                if (room.has(target)) { Net.error(uuid, "对方已在房间里"); return; }
                room.invite(target);
                String from = Net.nameOf(uuid);
                JsonObject inv = Proto.msg(Proto.S_INVITE);
                inv.addProperty("roomId", room.id);
                inv.addProperty("code", room.code);
                inv.addProperty("roomName", room.name);
                inv.addProperty("fromName", from);
                Net.send(target, inv);
                Net.chat(target, "[一起听] " + from
                        + " 邀请你加入《" + room.name + "》，使用 /mp join " + room.code + " 加入");
                Net.notice(uuid, "已邀请 " + Net.nameOf(target));
            }
            case Proto.C_SET_ROLE -> {
                UUID targetUuid = targetOf(msg);
                if (targetUuid == null) { Net.error(uuid, "找不到该玩家"); return; }
                String err = room.setRole(uuid, targetUuid, Role.of(Proto.str(msg, "role", "member")));
                if (err != null) Net.error(uuid, err);
            }
            case Proto.C_KICK -> {
                if (requireControl(uuid, control)) return;
                UUID targetUuid = targetOf(msg);
                if (targetUuid == null || !room.has(targetUuid)) { Net.error(uuid, "对方不在房间里"); return; }
                if (room.isOwner(targetUuid)) { Net.error(uuid, "不能移出房主"); return; }
                if (room.roleOf(targetUuid) == Role.ADMIN && !room.isOwner(uuid)) {
                    Net.error(uuid, "只有房主可以移出管理员");
                    return;
                }
                kick(room, targetUuid);
            }
            case Proto.C_TRANSFER -> {
                UUID targetUuid = targetOf(msg);
                if (targetUuid == null) { Net.error(uuid, "找不到该玩家"); return; }
                String err = room.transfer(uuid, targetUuid);
                if (err != null) Net.error(uuid, err);
            }
            case Proto.C_DISBAND -> {
                if (!room.isOwner(uuid)) { Net.error(uuid, "只有房主可以解散房间"); return; }
                disband(room, "房主已解散房间");
            }
            // 直链应答：必须连带平台一起回传，否则跨平台房间里 songId 相同的两首歌会串链接
            case Proto.C_URL_SUPPLY -> room.supplyUrl(
                    Proto.str(msg, "source", Proto.SOURCE_NETEASE),
                    Proto.str(msg, "songId", ""),
                    Proto.str(msg, "url", ""),
                    Proto.num(msg, "urlExpiresAt", 0L),
                    Proto.num(msg, "urlDuration", 0L));
            case Proto.C_PROGRESS -> room.progress(uuid,
                    Proto.num(msg, "pos", 0L), Proto.bool(msg, "playing", false));
            default -> Net.error(uuid, "不支持的操作：" + op);
        }
    }

    private boolean playByIndex(Room room, int index) {
        List<QueueEntry> queue = room.queue();
        if (index < 0 || index >= queue.size()) return false;
        return room.playEntry(queue.get(index).entryId);
    }

    public void kick(Room room, UUID targetUuid) {
        playerRoom.remove(targetUuid);
        room.send(targetUuid, left("你已被移出一起听房间"));
        String name = room.member(targetUuid) != null ? room.member(targetUuid).name : "玩家";
        room.remove(targetUuid);
        if (room.members().isEmpty()) drop(room);
        else {
            room.broadcastState();
            room.notice(name + " 已被移出房间");
        }
    }

    private static boolean requireControl(UUID uuid, boolean control) {
        if (control) return false;
        Net.error(uuid, "只有房主或管理员可以执行此操作");
        return true;
    }

    /** Resolve the {@code target} field: online name first, then a raw UUID. */
    private static UUID targetOf(JsonObject msg) {
        String target = Proto.str(msg, "target", "");
        if (target.isBlank()) return null;
        UUID byName = Net.uuidByName(target);
        return byName != null ? byName : uuidOf(target);
    }

    private static UUID uuidOf(String s) {
        try { return UUID.fromString(s); } catch (Throwable ignored) { return null; }
    }

    private String newRoomId() {
        String id;
        do {
            id = Long.toHexString(RNG.nextLong() & 0xFFFFFFFFL);
        } while (rooms.containsKey(id));
        return id;
    }

    private String newCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) sb.append(CODE_ALPHABET[RNG.nextInt(CODE_ALPHABET.length)]);
            code = sb.toString();
        } while (byCode.containsKey(code));
        return code;
    }
}
