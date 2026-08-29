package yun.shan.mpsync.room;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.net.ServerNet;

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
 * <p>All entry points assume the server thread (see {@link ServerNet}).
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

    public Room roomOf(ServerPlayerEntity player) {
        return player == null ? null : roomOf(player.getUuid());
    }

    public Room roomOf(UUID uuid) {
        String id = playerRoom.get(uuid);
        return id == null ? null : rooms.get(id);
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    /** @throws IllegalStateException with a user-facing reason */
    public Room create(ServerPlayerEntity player, String name) {
        if (roomOf(player) != null) throw new IllegalStateException("你已经在一个房间里了");
        if (cfg.maxRooms > 0 && rooms.size() >= cfg.maxRooms) {
            throw new IllegalStateException("服务器房间数量已达上限");
        }
        String id = newRoomId();
        String code = newCode();
        Room room = new Room(player.getServer(), cfg, id, code, player, name);
        rooms.put(id, room);
        byCode.put(code, id);
        playerRoom.put(player.getUuid(), id);
        room.sendSnapshot(player);
        ServerNet.notice(player, "房间已创建，邀请码：" + code);
        return room;
    }

    /** @return the joined room; throws with a user-facing reason otherwise */
    public Room join(ServerPlayerEntity player, String codeOrId) {
        Room existing = roomOf(player);
        if (existing != null) {
            if (existing.code.equalsIgnoreCase(codeOrId) || existing.id.equalsIgnoreCase(codeOrId)) {
                existing.join(player);
                existing.sendSnapshot(player);
                return existing;
            }
            throw new IllegalStateException("你已经在一个房间里了，请先离开");
        }
        Room room = resolve(codeOrId);
        if (room == null) {
            // A player can also join by invite alone (the client stores the room id).
            room = rooms.values().stream().filter(r -> r.invited(player.getUuid())).findFirst().orElse(null);
        }
        if (room == null) throw new IllegalStateException("邀请码无效或房间不存在");
        if (room.full() && !room.has(player.getUuid())) throw new IllegalStateException("房间已满");
        room.join(player);
        playerRoom.put(player.getUuid(), room.id);
        room.broadcastState();
        room.notice(player.getGameProfile().getName() + " 加入了房间");
        return room;
    }

    public Room resolve(String codeOrId) {
        if (codeOrId == null || codeOrId.isBlank()) return null;
        String key = codeOrId.trim();
        String id = byCode.get(key.toUpperCase(Locale.ROOT));
        Room room = id != null ? rooms.get(id) : null;
        return room != null ? room : rooms.get(key);
    }

    public void leave(ServerPlayerEntity player) {
        Room room = roomOf(player);
        if (room == null) return;
        UUID uuid = player.getUuid();
        boolean wasOwner = room.isOwner(uuid);
        playerRoom.remove(uuid);
        room.remove(uuid);
        ServerNet.send(player, left("你已离开一起听房间"));
        if (room.members().isEmpty()) {
            drop(room);
        } else {
            room.broadcastState();
            room.notice(player.getGameProfile().getName() + (wasOwner ? " (房主) 离开了房间" : " 离开了房间"));
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

    public void onPlayerJoin(ServerPlayerEntity player) {
        Room room = roomOf(player);
        if (room == null) return;
        room.join(player);              // re-attach the seat kept across the disconnect
        room.sendSnapshot(player);
        room.broadcastState();
    }

    public void onPlayerDisconnect(ServerPlayerEntity player) {
        Room room = roomOf(player);
        if (room == null) return;
        room.markOffline(player.getUuid());
        room.broadcastState();
    }

    public void tick(MinecraftServer server) {
        if (rooms.isEmpty()) return;
        for (Room room : new ArrayList<>(rooms.values())) {
            try {
                room.tick();
            } catch (Throwable t) {
                // One bad room must not take the server tick down with it.
                ServerNet.LOGGER.warn("[mpsync] room {} tick failed", room.id, t);
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
    public void handle(ServerPlayerEntity player, JsonObject msg) {
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
                    ServerNet.send(player, hello);
                    Room room = roomOf(player);
                    if (room != null) room.sendSnapshot(player);
                }
                case Proto.C_CREATE -> create(player, Proto.str(msg, "name", ""));
                case Proto.C_JOIN   -> join(player, Proto.str(msg, "code", Proto.str(msg, "room", "")));
                case Proto.C_LEAVE  -> leave(player);
                case Proto.C_SYNC   -> {
                    Room room = roomOf(player);
                    if (room != null) room.sendSnapshot(player);
                    else ServerNet.send(player, left(""));
                }
                default -> handleInRoom(player, op, msg);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            ServerNet.error(player, e.getMessage());
        } catch (Throwable t) {
            ServerNet.LOGGER.warn("[mpsync] op {} from {} failed", op, player.getGameProfile().getName(), t);
            ServerNet.error(player, "操作失败");
        }
    }

    private void handleInRoom(ServerPlayerEntity player, String op, JsonObject msg) {
        Room room = roomOf(player);
        if (room == null) {
            ServerNet.error(player, "你还没有加入一起听房间");
            return;
        }
        UUID uuid = player.getUuid();
        boolean control = room.controls(uuid);

        switch (op) {
            case Proto.C_ENQUEUE -> {
                if (!control && !cfg.membersCanEnqueue) {
                    ServerNet.error(player, "本房间未开放点歌");
                    return;
                }
                Track track = Track.fromJson(Proto.obj(msg, "track"), cfg);
                QueueEntry entry = room.enqueue(player, track, Proto.bool(msg, "next", false));
                room.notice(player.getGameProfile().getName() + " 点歌：" + entry.track.display());
            }
            case Proto.C_PLAY_NOW -> {
                if (requireControl(player, control)) return;
                room.playNow(player, Track.fromJson(Proto.obj(msg, "track"), cfg));
            }
            case Proto.C_PLAY_INDEX -> {
                if (requireControl(player, control)) return;
                long entryId = Proto.num(msg, "entryId", -1L);
                boolean ok = entryId > 0
                        ? room.playEntry(entryId)
                        : playByIndex(room, Proto.i32(msg, "index", -1));
                if (!ok) ServerNet.error(player, "这首歌已不在队列中");
            }
            case Proto.C_REMOVE -> {
                long entryId = Proto.num(msg, "entryId", -1L);
                QueueEntry entry = room.findEntry(entryId);
                if (entry == null) { ServerNet.error(player, "歌曲不在队列中"); return; }
                boolean own = cfg.requesterCanRemoveOwn && uuid.equals(entry.requester);
                if (!control && !own) { ServerNet.error(player, "只能删除自己点的歌"); return; }
                room.removeEntry(entryId);
            }
            case Proto.C_MOVE -> {
                if (requireControl(player, control)) return;
                room.moveEntry(Proto.num(msg, "entryId", -1L), Proto.i32(msg, "to", 0));
            }
            case Proto.C_CLEAR -> {
                if (requireControl(player, control)) return;
                room.clearQueue();
            }
            case Proto.C_PAUSE -> {
                if (requireControl(player, control)) return;
                room.pause();
            }
            case Proto.C_RESUME -> {
                if (requireControl(player, control)) return;
                room.resume();
            }
            case Proto.C_SEEK -> {
                if (requireControl(player, control)) return;
                room.seek(Proto.num(msg, "pos", 0L));
            }
            case Proto.C_NEXT -> {
                if (requireControl(player, control)) return;
                room.next(false);
            }
            case Proto.C_PREV -> {
                if (requireControl(player, control)) return;
                room.prev();
            }
            case Proto.C_REPEAT -> {
                if (requireControl(player, control)) return;
                room.setRepeat(RepeatMode.of(Proto.str(msg, "mode", "off")));
            }
            case Proto.C_INVITE -> {
                ServerPlayerEntity target = targetOf(player, msg);
                if (target == null) { ServerNet.error(player, "找不到该玩家"); return; }
                if (room.has(target.getUuid())) { ServerNet.error(player, "对方已在房间里"); return; }
                room.invite(target.getUuid());
                JsonObject inv = Proto.msg(Proto.S_INVITE);
                inv.addProperty("roomId", room.id);
                inv.addProperty("code", room.code);
                inv.addProperty("roomName", room.name);
                inv.addProperty("fromName", player.getGameProfile().getName());
                ServerNet.send(target, inv);
                ServerNet.chat(target, "[一起听] " + player.getGameProfile().getName()
                        + " 邀请你加入《" + room.name + "》，使用 /mp join " + room.code + " 加入");
                ServerNet.notice(player, "已邀请 " + target.getGameProfile().getName());
            }
            case Proto.C_SET_ROLE -> {
                ServerPlayerEntity target = targetOf(player, msg);
                UUID targetUuid = target != null ? target.getUuid() : uuidOf(Proto.str(msg, "target", ""));
                if (targetUuid == null) { ServerNet.error(player, "找不到该玩家"); return; }
                String err = room.setRole(uuid, targetUuid, Role.of(Proto.str(msg, "role", "member")));
                if (err != null) ServerNet.error(player, err);
            }
            case Proto.C_KICK -> {
                if (requireControl(player, control)) return;
                ServerPlayerEntity target = targetOf(player, msg);
                UUID targetUuid = target != null ? target.getUuid() : uuidOf(Proto.str(msg, "target", ""));
                if (targetUuid == null || !room.has(targetUuid)) { ServerNet.error(player, "对方不在房间里"); return; }
                if (room.isOwner(targetUuid)) { ServerNet.error(player, "不能移出房主"); return; }
                if (room.roleOf(targetUuid) == Role.ADMIN && !room.isOwner(uuid)) {
                    ServerNet.error(player, "只有房主可以移出管理员");
                    return;
                }
                kick(room, targetUuid);
            }
            case Proto.C_TRANSFER -> {
                ServerPlayerEntity target = targetOf(player, msg);
                UUID targetUuid = target != null ? target.getUuid() : uuidOf(Proto.str(msg, "target", ""));
                if (targetUuid == null) { ServerNet.error(player, "找不到该玩家"); return; }
                String err = room.transfer(uuid, targetUuid);
                if (err != null) ServerNet.error(player, err);
            }
            case Proto.C_DISBAND -> {
                if (!room.isOwner(uuid)) { ServerNet.error(player, "只有房主可以解散房间"); return; }
                disband(room, "房主已解散房间");
            }
            case Proto.C_URL_SUPPLY -> room.supplyUrl(
                    Proto.str(msg, "songId", ""),
                    Proto.str(msg, "url", ""),
                    Proto.num(msg, "urlExpiresAt", 0L),
                    Proto.num(msg, "urlDuration", 0L));
            case Proto.C_PROGRESS -> room.progress(uuid,
                    Proto.num(msg, "pos", 0L), Proto.bool(msg, "playing", false));
            default -> ServerNet.error(player, "不支持的操作：" + op);
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

    private static boolean requireControl(ServerPlayerEntity player, boolean control) {
        if (control) return false;
        ServerNet.error(player, "只有房主或管理员可以执行此操作");
        return true;
    }

    private static ServerPlayerEntity targetOf(ServerPlayerEntity actor, JsonObject msg) {
        String target = Proto.str(msg, "target", "");
        if (target.isBlank() || actor.getServer() == null) return null;
        ServerPlayerEntity byName = actor.getServer().getPlayerManager().getPlayer(target);
        if (byName != null) return byName;
        UUID uuid = uuidOf(target);
        return uuid == null ? null : actor.getServer().getPlayerManager().getPlayer(uuid);
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
