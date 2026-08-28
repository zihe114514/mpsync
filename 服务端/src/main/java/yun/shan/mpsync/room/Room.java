package yun.shan.mpsync.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.net.ServerNet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One synced listening room: membership + roles, the 点歌 queue, and the authoritative
 * playback clock.
 *
 * <p>Threading: every method here runs on the server thread. Network receivers hop
 * through {@code server.execute(...)} before touching a room, so no locking is needed.
 *
 * <p>Clock model: the server owns position. {@link #startEpoch} is the wall-clock instant
 * at which the current track's position would have been 0, so
 * {@code pos = now - startEpoch} while playing and {@link #pausedPos} while paused.
 * Clients get {@code serverTime} alongside every position so they can compensate for
 * one-way latency instead of trusting arrival time.
 */
public final class Room {

    private static final int HISTORY_MAX = 50;
    /** Treat "within this much of the end" as finished — decoders rarely hit the exact ms. */
    private static final long END_EPSILON_MS = 250L;

    public final String id;
    public final String code;
    public String name;
    public UUID owner;

    private final MinecraftServer server;
    private final MpConfig cfg;

    private final Map<UUID, Member> members = new LinkedHashMap<>();
    /** Players explicitly invited — they may join without knowing the code. */
    private final Map<UUID, Long> invites = new LinkedHashMap<>();

    private final List<QueueEntry> queue = new ArrayList<>();
    private final List<QueueEntry> history = new ArrayList<>();

    private QueueEntry current;
    private boolean playing;
    private long startEpoch;
    private long pausedPos;
    private RepeatMode repeat = RepeatMode.OFF;

    /** Monotonic playback-command counter: clients drop out-of-order commands with it. */
    private long seq;

    /** Set while the room is blocked waiting for a client to re-resolve a stream URL. */
    private boolean waitingUrl;
    private long waitingSince;

    private int tickCounter;
    private long lastRefreshAsk;
    private long allOfflineSince;

    public Room(MinecraftServer server, MpConfig cfg, String id, String code,
                ServerPlayerEntity creator, String name) {
        this.server = server;
        this.cfg = cfg;
        this.id = id;
        this.code = code;
        this.owner = creator.getUuid();
        this.name = name == null || name.isBlank()
                ? creator.getGameProfile().getName() + " 的一起听" : name;
        members.put(owner, new Member(owner, creator.getGameProfile().getName(), Role.OWNER));
    }

    // ── membership ───────────────────────────────────────────────────────────

    public Collection<Member> members() { return members.values(); }

    public Member member(UUID uuid) { return members.get(uuid); }

    public boolean has(UUID uuid) { return members.containsKey(uuid); }

    public Role roleOf(UUID uuid) {
        Member m = members.get(uuid);
        return m == null ? Role.MEMBER : m.role;
    }

    public boolean controls(UUID uuid) {
        Member m = members.get(uuid);
        return m != null && m.role.controls();
    }

    public boolean isOwner(UUID uuid) { return owner.equals(uuid); }

    public boolean invited(UUID uuid) { return invites.containsKey(uuid); }

    public void invite(UUID uuid) { invites.put(uuid, System.currentTimeMillis()); }

    public boolean full() {
        return cfg.maxRoomMembers > 0 && members.size() >= cfg.maxRoomMembers;
    }

    /** Add (or re-attach) a player. Returns the member record. */
    public Member join(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        invites.remove(uuid);
        Member m = members.get(uuid);
        if (m == null) {
            m = new Member(uuid, player.getGameProfile().getName(), Role.MEMBER);
            members.put(uuid, m);
        } else {
            m.name = player.getGameProfile().getName();
        }
        m.online = true;
        m.lastSeen = System.currentTimeMillis();
        allOfflineSince = 0L;
        return m;
    }

    /** Full removal (leave / kick). */
    public void remove(UUID uuid) {
        members.remove(uuid);
        // A leaving requester's pending picks stay: the room agreed to play them, and
        // silently dropping the queue on a disconnect is worse than an unowned entry.
        if (uuid.equals(owner)) promoteNewOwner();
    }

    /** Connection lost — keep the seat so a reconnect resumes in-place. */
    public void markOffline(UUID uuid) {
        Member m = members.get(uuid);
        if (m == null) return;
        m.online = false;
        m.listening = false;
        if (members.values().stream().noneMatch(x -> x.online)) {
            allOfflineSince = System.currentTimeMillis();
        }
    }

    /** Hand the crown to the longest-standing online member; keeps admins in front. */
    private void promoteNewOwner() {
        Member next = members.values().stream()
                .filter(m -> m.online && m.role == Role.ADMIN).findFirst()
                .orElseGet(() -> members.values().stream().filter(m -> m.online).findFirst()
                        .orElseGet(() -> members.values().stream().findFirst().orElse(null)));
        if (next == null) return;
        owner = next.uuid;
        next.role = Role.OWNER;
        notice(next.name + " 已成为新房主");
    }

    public boolean dead() {
        if (members.isEmpty()) return true;
        return allOfflineSince > 0
                && System.currentTimeMillis() - allOfflineSince > cfg.emptyRoomGraceMs;
    }

    // ── queue ────────────────────────────────────────────────────────────────

    public List<QueueEntry> queue() { return queue; }

    public List<QueueEntry> history() { return history; }

    public QueueEntry current() { return current; }

    public boolean isPlaying() { return playing; }

    public RepeatMode repeat() { return repeat; }

    public void setRepeat(RepeatMode mode) {
        this.repeat = mode == null ? RepeatMode.OFF : mode;
        broadcastState();
    }

    public int pendingOf(UUID uuid) {
        int n = 0;
        for (QueueEntry e : queue) if (uuid.equals(e.requester)) n++;
        return n;
    }

    /**
     * 点歌: queue a request. When nothing is playing the request starts immediately,
     * which is what makes the first pick in a fresh room audible without host action.
     *
     * @param playNext insert at the head instead of the tail. Honoured only for
     *                 owner/admin — letting any member jump the queue would make the
     *                 "下一曲播放" button a way to cut in front of everyone else.
     * @throws IllegalStateException with a user-facing reason when the queue rejects it
     */
    public QueueEntry enqueue(ServerPlayerEntity player, Track track, boolean playNext) {
        UUID uuid = player.getUuid();
        if (cfg.maxQueueSize > 0 && queue.size() >= cfg.maxQueueSize) {
            throw new IllegalStateException("队列已满");
        }
        if (!controls(uuid) && cfg.maxQueuePerMember > 0
                && pendingOf(uuid) >= cfg.maxQueuePerMember) {
            throw new IllegalStateException("你的排队歌曲已达上限（" + cfg.maxQueuePerMember + " 首）");
        }
        for (QueueEntry e : queue) {
            if (e.track.songId.equals(track.songId)) throw new IllegalStateException("这首歌已经在队列里了");
        }
        QueueEntry entry = new QueueEntry(track, uuid, player.getGameProfile().getName());
        if (playNext && controls(uuid)) queue.add(0, entry);
        else queue.add(entry);
        if (current == null) {
            startEntry(pollFront(entry), 0L);
        } else {
            broadcastQueue();
        }
        return entry;
    }

    private QueueEntry pollFront(QueueEntry expected) {
        int idx = queue.indexOf(expected);
        return idx >= 0 ? queue.remove(idx) : expected;
    }

    public QueueEntry findEntry(long entryId) {
        for (QueueEntry e : queue) if (e.entryId == entryId) return e;
        return null;
    }

    public boolean removeEntry(long entryId) {
        boolean removed = queue.removeIf(e -> e.entryId == entryId);
        if (removed) broadcastQueue();
        return removed;
    }

    public boolean moveEntry(long entryId, int to) {
        int from = -1;
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).entryId == entryId) { from = i; break; }
        if (from < 0) return false;
        int target = Math.max(0, Math.min(queue.size() - 1, to));
        QueueEntry e = queue.remove(from);
        queue.add(target, e);
        broadcastQueue();
        return true;
    }

    public void clearQueue() {
        queue.clear();
        broadcastQueue();
    }

    // ── playback ─────────────────────────────────────────────────────────────

    public long positionMs() {
        long pos = playing ? System.currentTimeMillis() - startEpoch : pausedPos;
        if (pos < 0) pos = 0;
        long dur = current == null ? 0 : current.track.durationMs;
        if (dur > 0 && pos > dur) pos = dur;
        return pos;
    }

    /** Owner/admin free switch: play this track right now, ahead of the queue. */
    public void playNow(ServerPlayerEntity actor, Track track) {
        QueueEntry entry = new QueueEntry(track, actor.getUuid(), actor.getGameProfile().getName());
        queue.removeIf(e -> e.track.songId.equals(track.songId));
        startEntry(entry, 0L);
    }

    /** Jump to an already-queued entry, dropping the ones it skipped past into history. */
    public boolean playEntry(long entryId) {
        int idx = -1;
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).entryId == entryId) { idx = i; break; }
        if (idx < 0) return false;
        QueueEntry entry = queue.remove(idx);
        startEntry(entry, 0L);
        return true;
    }

    public void next(boolean auto) {
        if (auto && repeat == RepeatMode.ONE && current != null) {
            startEntry(current, 0L);
            return;
        }
        if (!queue.isEmpty()) {
            startEntry(queue.remove(0), 0L);
            return;
        }
        if (repeat == RepeatMode.ALL) {
            // Refill from history (oldest first) so the room loops the same set.
            QueueEntry replay = current;
            if (!history.isEmpty()) {
                queue.addAll(history);
                history.clear();
                if (replay != null) queue.add(replay);
                startEntry(queue.remove(0), 0L);
                return;
            }
            if (replay != null) { startEntry(replay, 0L); return; }
        }
        stop();
    }

    public void prev() {
        if (history.isEmpty()) {
            seek(0L);
            return;
        }
        QueueEntry back = history.remove(history.size() - 1);
        if (current != null) queue.add(0, current);
        startEntryInternal(back, 0L, false);
        broadcastQueue();
    }

    private void startEntry(QueueEntry entry, long startPos) {
        startEntryInternal(entry, startPos, true);
    }

    private void startEntryInternal(QueueEntry entry, long startPos, boolean archiveCurrent) {
        if (archiveCurrent && current != null && current != entry) {
            history.add(current);
            while (history.size() > HISTORY_MAX) history.remove(0);
        }
        current = entry;
        pausedPos = Math.max(0L, startPos);
        startEpoch = System.currentTimeMillis() - pausedPos;
        playing = true;
        seq++;
        waitingUrl = false;

        if (!entry.track.hasFreshUrl()) {
            // No usable URL: hold the clock and ask a client to re-resolve. Pushing a
            // dead link would leave every member silently stuck instead.
            playing = false;
            waitingUrl = true;
            waitingSince = System.currentTimeMillis();
            requestUrl(entry);
            broadcastState();
            return;
        }
        broadcastPlay();
        broadcastQueue();
    }

    private void requestUrl(QueueEntry entry) {
        requestUrl(entry, false);
    }

    /**
     * Ask clients to (re-)resolve a stream URL.
     *
     * @param quiet true for a pre-emptive refresh while playback continues — no chat
     *              notice, because nothing is actually stalled
     */
    private void requestUrl(QueueEntry entry, boolean quiet) {
        JsonObject m = Proto.msg(Proto.S_URL_NEEDED);
        m.addProperty("songId", entry.track.songId);
        m.addProperty("name", entry.track.name);
        // Ask the requester first (their session found the song once already), then any
        // controller, then everyone — whoever answers first wins.
        List<UUID> targets = new ArrayList<>();
        if (entry.requester != null && members.containsKey(entry.requester)) targets.add(entry.requester);
        for (Member mem : members.values()) if (mem.role.controls() && !targets.contains(mem.uuid)) targets.add(mem.uuid);
        for (Member mem : members.values()) if (!targets.contains(mem.uuid)) targets.add(mem.uuid);
        for (UUID uuid : targets) send(uuid, m);
        if (!quiet) notice("正在获取《" + entry.track.name + "》的播放地址…");
    }

    /**
     * A client answered {@link Proto#S_URL_NEEDED} (or refreshed a queued track).
     *
     * <p>{@code servedDurationMs} lets a trial clip be rejected: the room hands one link to
     * everyone, so accepting a non-VIP account's 30s preview would cut the song short for
     * all members. A rejected answer changes nothing and the room keeps waiting, giving a
     * member who can resolve the full track a chance to answer.
     */
    public void supplyUrl(String songId, String url, long expiresAt, long servedDurationMs) {
        if (songId == null) return;
        boolean touched = false;
        if (current != null && current.track.songId.equals(songId)) {
            boolean wasStalled = waitingUrl;
            if (!current.track.adoptUrl(url, expiresAt, servedDurationMs, cfg)) return;
            if (current.track.hasFreshUrl() && wasStalled) {
                // The room was holding for this URL: start the clock and push play.
                waitingUrl = false;
                playing = true;
                startEpoch = System.currentTimeMillis() - pausedPos;
                seq++;
                broadcastPlay();
                broadcastQueue();
                return;
            }
            // Pre-emptive refresh while playing: store it silently so late joiners get a
            // live link. Re-broadcasting play here would restart everyone mid-song.
            touched = true;
        }
        for (QueueEntry e : queue) {
            if (e.track.songId.equals(songId) && !e.track.hasFreshUrl()
                    && e.track.adoptUrl(url, expiresAt, servedDurationMs, cfg)) {
                touched = true;
            }
        }
        if (touched) broadcastQueue();
    }

    public void pause() {
        if (current == null || !playing) return;
        pausedPos = positionMs();
        playing = false;
        seq++;
        JsonObject m = Proto.msg(Proto.S_PAUSE);
        stamp(m, pausedPos);
        broadcast(m);
    }

    public void resume() {
        if (current == null || playing) return;
        if (!current.track.hasFreshUrl()) {
            // URL expired while paused — re-resolve before pretending to resume.
            waitingUrl = true;
            waitingSince = System.currentTimeMillis();
            requestUrl(current);
            return;
        }
        startEpoch = System.currentTimeMillis() - pausedPos;
        playing = true;
        seq++;
        JsonObject m = Proto.msg(Proto.S_RESUME);
        stamp(m, pausedPos);
        broadcast(m);
    }

    public void seek(long pos) {
        if (current == null) return;
        long dur = current.track.durationMs;
        pausedPos = Math.max(0L, dur > 0 ? Math.min(pos, dur) : pos);
        if (playing) startEpoch = System.currentTimeMillis() - pausedPos;
        seq++;
        JsonObject m = Proto.msg(Proto.S_SEEK);
        stamp(m, pausedPos);
        broadcast(m);
    }

    public void stop() {
        if (current != null) {
            history.add(current);
            while (history.size() > HISTORY_MAX) history.remove(0);
        }
        current = null;
        playing = false;
        pausedPos = 0L;
        waitingUrl = false;
        seq++;
        broadcast(Proto.msg(Proto.S_STOP));
        broadcastQueue();
    }

    // ── roles ────────────────────────────────────────────────────────────────

    /**
     * Grant or revoke admin.
     *
     * @return a user-facing error, or {@code null} on success
     */
    public String setRole(UUID actor, UUID target, Role role) {
        Member t = members.get(target);
        if (t == null) return "对方不在房间里";
        if (!controls(actor)) return "只有房主或管理员可以设置权限";
        if (target.equals(owner)) return "不能修改房主的权限";
        if (role == Role.OWNER) return "请使用转让房主功能";
        if (role == Role.MEMBER && t.role == Role.ADMIN && !isOwner(actor)) {
            return "只有房主可以撤销管理员";
        }
        t.role = role;
        broadcastState();
        notice(t.name + (role == Role.ADMIN ? " 已成为管理员" : " 的管理员权限已被撤销"));
        return null;
    }

    /** Owner-only: hand the room over. */
    public String transfer(UUID actor, UUID target) {
        if (!isOwner(actor)) return "只有房主可以转让房间";
        Member t = members.get(target);
        if (t == null) return "对方不在房间里";
        Member old = members.get(owner);
        if (old != null) old.role = Role.ADMIN;
        owner = target;
        t.role = Role.OWNER;
        broadcastState();
        notice(t.name + " 已成为新房主");
        return null;
    }

    // ── heartbeat ────────────────────────────────────────────────────────────

    public void progress(UUID uuid, long pos, boolean listening) {
        Member m = members.get(uuid);
        if (m == null) return;
        m.lastSeen = System.currentTimeMillis();
        m.reportedPos = pos;
        m.listening = listening;
    }

    public int listenerCount() {
        int n = 0;
        for (Member m : members.values()) if (m.online && m.listening) n++;
        return n;
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    public void tick() {
        long now = System.currentTimeMillis();

        if (waitingUrl && now - waitingSince > cfg.urlWaitTimeoutMs) {
            waitingUrl = false;
            String title = current != null ? current.track.name : "";
            notice("无法获取《" + title + "》的播放地址，已跳过");
            next(false);
            return;
        }

        if (playing && current != null) {
            long dur = current.track.durationMs;
            if (dur > 0 && positionMs() >= dur - END_EPSILON_MS) {
                next(true);
                return;
            }
        }

        // Keep the stored URL alive for players who join mid-song: NetEase signs links
        // for minutes, and a joiner handed an expired link would get silence.
        if (current != null && !waitingUrl && playing
                && current.track.urlExpiresAt > 0
                && current.track.urlExpiresAt - now < 60_000L
                && now - lastRefreshAsk > 30_000L) {
            lastRefreshAsk = now;
            requestUrl(current, true);
        }

        if (++tickCounter >= cfg.timeBroadcastTicks) {
            tickCounter = 0;
            if (current != null) broadcastTime();
        }
    }

    // ── snapshots + sending ──────────────────────────────────────────────────

    /** Full room snapshot. Stream URLs are only included for the track that is playing. */
    public JsonObject snapshot() {
        JsonObject room = new JsonObject();
        room.addProperty("id", id);
        room.addProperty("code", code);
        room.addProperty("name", name);
        room.addProperty("owner", owner.toString());
        room.addProperty("repeat", repeat.id());
        room.addProperty("listeners", listenerCount());

        JsonArray mem = new JsonArray();
        for (Member m : members.values()) mem.add(m.toJson());
        room.add("members", mem);

        room.add("queue", queueJson());
        room.add("history", historyJson());

        JsonObject playback = new JsonObject();
        playback.addProperty("state", current == null ? "idle" : (playing ? "playing" : "paused"));
        playback.addProperty("waitingUrl", waitingUrl);
        playback.addProperty("pos", positionMs());
        playback.addProperty("serverTime", System.currentTimeMillis());
        playback.addProperty("seq", seq);
        if (current != null) playback.add("track", current.toJson(true));

        JsonObject msg = Proto.msg(Proto.S_ROOM_STATE);
        msg.add("room", room);
        msg.add("playback", playback);
        return msg;
    }

    private JsonArray queueJson() {
        JsonArray a = new JsonArray();
        for (QueueEntry e : queue) a.add(e.toJson(false));
        return a;
    }

    private JsonArray historyJson() {
        JsonArray a = new JsonArray();
        for (QueueEntry e : history) a.add(e.toJson(false));
        return a;
    }

    private void stamp(JsonObject m, long pos) {
        m.addProperty("pos", pos);
        m.addProperty("serverTime", System.currentTimeMillis());
        m.addProperty("seq", seq);
    }

    public void broadcastPlay() {
        if (current == null) return;
        JsonObject m = Proto.msg(Proto.S_PLAY);
        m.add("track", current.toJson(true));
        m.addProperty("requesterName", current.requesterName == null ? "" : current.requesterName);
        m.addProperty("playing", playing);
        stamp(m, positionMs());
        broadcast(m);
    }

    public void broadcastTime() {
        JsonObject m = Proto.msg(Proto.S_TIME);
        m.addProperty("playing", playing);
        m.addProperty("duration", current == null ? 0L : current.track.durationMs);
        m.addProperty("listeners", listenerCount());
        stamp(m, positionMs());
        broadcast(m);
    }

    public void broadcastQueue() {
        JsonObject m = Proto.msg(Proto.S_QUEUE);
        m.add("queue", queueJson());
        m.add("history", historyJson());
        broadcast(m);
    }

    public void broadcastState() {
        broadcast(snapshot());
    }

    public void notice(String text) {
        JsonObject m = Proto.msg(Proto.S_NOTICE);
        m.addProperty("msg", text);
        broadcast(m);
        // Chat mirror so vanilla clients (no music mod) can still follow the room.
        ServerNet.chatAll(server, members.keySet(), "[一起听] " + text);
    }

    public void broadcast(JsonObject msg) {
        for (UUID uuid : members.keySet()) send(uuid, msg);
    }

    public void send(UUID uuid, JsonObject msg) {
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
        if (p != null) ServerNet.send(p, msg);
    }

    public void sendSnapshot(ServerPlayerEntity player) {
        ServerNet.send(player, snapshot());
    }
}
