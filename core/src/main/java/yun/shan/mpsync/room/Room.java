package yun.shan.mpsync.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.presence.ClientProfile;
import yun.shan.mpsync.presence.PresenceRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

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
    /** 频道没有房主，这里是 null —— 所有读它的地方都必须判空。 */
    public UUID owner;
    /** 普通房间 or 服务器频道。决定「谁说得上话」，播放引擎完全一样。 */
    public final RoomKind kind;
    /**
     * 是否公开（出现在公开房间列表里，任何人都能直接加入）。
     *
     * <p>默认 false：房间是私密的，只有拿到邀请码或被邀请的人能进。频道天生公开。
     */
    public boolean open;

    private final MpConfig cfg;

    /** 客户端自报档案（Mod / VIP / 在听），拼进成员条目用。由 {@link RoomManager} 注入。 */
    private PresenceRegistry presence;
    /**
     * 「这个人是不是服务器管理员」。频道里没有房主，管理权就落在服务器管理员身上，
     * 而 core 不认识任何平台的权限系统，所以由外面注入。
     */
    private Predicate<UUID> serverAdmin = u -> false;

    /** 进行中的投票（频道用）。同时最多一个切歌投票和一个针对某人的踢人投票。 */
    private final List<VoteSession> votes = new ArrayList<>();
    /** 踢人投票失败后的冷却：key 是被投的人，value 是冷却到期时刻。 */
    private final Map<UUID, Long> kickCooldown = new LinkedHashMap<>();

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
    /**
     * 最近一次播放命令（换歌 / 暂停 / 恢复 / 拖进度 / 等到链接后起播）下发的时刻。
     * 用来算时钟静默窗，见 {@link #inClockGrace}。
     */
    private long lastCommandAt;

    public Room(MpConfig cfg, String id, String code, UUID creator, String creatorName, String name) {
        this.cfg = cfg;
        this.id = id;
        this.code = code;
        this.kind = RoomKind.ROOM;
        this.owner = creator;
        this.name = name == null || name.isBlank() ? creatorName + " 的一起听" : name;
        members.put(owner, new Member(owner, creatorName, Role.OWNER));
    }

    /**
     * 服务器频道：没有创建者、没有房主、天生公开、永不因为空房而关闭。
     *
     * <p>刻意不复用上面那个构造器再把 owner 抹掉：房主为 null 会牵动 remove/promote/snapshot
     * 好几条路径，用一个独立入口把这件事说清楚，比事后修补安全。
     */
    public Room(MpConfig cfg, String id, String code, String name) {
        this.cfg = cfg;
        this.id = id;
        this.code = code;
        this.kind = RoomKind.CHANNEL;
        this.owner = null;
        this.open = true;
        this.name = name == null || name.isBlank() ? "服务器频道" : name;
    }

    /** 注入客户端档案表；不注入也能跑，只是成员条目里的 Mod/VIP 全是空。 */
    public void setPresence(PresenceRegistry registry) { this.presence = registry; }

    /**
     * 服务端自己去解析一条直链（服主配置了平台 Token 时）。
     *
     * <p>注入而不是直接调用：core 里的房间引擎不该知道 HTTP 长什么样，也不该在单元测试里
     * 真的发请求。没注入就退化成「只向客户端要链接」，也就是 v1 的行为。
     */
    @FunctionalInterface
    public interface ServerResolve {
        /** 异步解析；解析成功后自行回调 {@link Room#supplyUrl}。 */
        void request(String source, String songId, String name);
    }

    private ServerResolve serverResolve;

    public void setServerResolve(ServerResolve hook) { this.serverResolve = hook; }

    /** 注入「服务器管理员」判据。频道的管理权全靠它。 */
    public void setServerAdmin(Predicate<UUID> check) {
        if (check != null) this.serverAdmin = check;
    }

    public boolean channel() { return kind.channel(); }

    /** 这个人在本房间是否有管理权（频道里就是服务器管理员）。 */
    public boolean serverAdmin(UUID uuid) {
        try {
            return uuid != null && serverAdmin.test(uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── membership ───────────────────────────────────────────────────────────

    public Collection<Member> members() { return members.values(); }

    public Member member(UUID uuid) { return members.get(uuid); }

    public boolean has(UUID uuid) { return members.containsKey(uuid); }

    public Role roleOf(UUID uuid) {
        Member m = members.get(uuid);
        return m == null ? Role.MEMBER : m.role;
    }

    /**
     * 有没有「房主/管理员」那一档权限。
     *
     * <p>频道没有房主，所以改判服务器管理员：频道的歌单由管理员管，切歌／踢人则交给投票 ——
     * 普通成员在频道里拿不到直接控制权，这正是频道和普通房间的分界。
     */
    public boolean controls(UUID uuid) {
        if (channel()) return serverAdmin(uuid);
        Member m = members.get(uuid);
        return m != null && m.role.controls();
    }

    public boolean isOwner(UUID uuid) { return owner != null && owner.equals(uuid); }

    public boolean invited(UUID uuid) { return invites.containsKey(uuid); }

    public void invite(UUID uuid) { invites.put(uuid, System.currentTimeMillis()); }

    public boolean full() {
        int cap = channel() ? cfg.maxChannelMembers : cfg.maxRoomMembers;
        return cap > 0 && members.size() >= cap;
    }

    /** Add (or re-attach) a player. Returns the member record. */
    public Member join(UUID uuid, String name) {
        invites.remove(uuid);
        Member m = members.get(uuid);
        if (m == null) {
            m = new Member(uuid, name, Role.MEMBER);
            members.put(uuid, m);
        } else {
            m.name = name;
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
        if (owner != null && uuid.equals(owner)) promoteNewOwner();
        // 走掉的人身上挂着的投票要清：他的那一票不该继续算进分母，针对他的踢人投票也没意义了。
        votes.removeIf(v -> v.kind == VoteSession.Kind.KICK && uuid.equals(v.target));
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
        if (channel()) return;                 // 频道本来就没有房主，别凭空造一个
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
        // 频道是服务器设施，不随人走人留而生灭 —— 空着也要在那儿等下一个人进来。
        if (channel()) return false;
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

    /**
     * 单人同时能挂在队上的歌数。
     *
     * <p>频道单列一档（默认 3 首，需求写死的规则）：频道是全服共用的，一个人塞十首就把
     * 后面所有人挡在外面了。普通房间沿用 {@code maxQueuePerMember}。
     */
    public int perMemberCap() {
        return channel() ? cfg.channelMaxPerMember : cfg.maxQueuePerMember;
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
    public QueueEntry enqueue(UUID uuid, String name, Track track, boolean playNext) {
        if (cfg.maxQueueSize > 0 && queue.size() >= cfg.maxQueueSize) {
            throw new IllegalStateException("队列已满");
        }
        int cap = perMemberCap();
        if (!controls(uuid) && cap > 0 && pendingOf(uuid) >= cap) {
            throw new IllegalStateException("你的排队歌曲已达上限（" + cap + " 首）");
        }
        for (QueueEntry e : queue) {
            // 按 source:songId 比，而不是只比 songId：跨平台之后不同平台会撞到相同的数字 id。
            if (e.track.key().equals(track.key())) throw new IllegalStateException("这首歌已经在队列里了");
        }
        QueueEntry entry = new QueueEntry(track, uuid, name);
        if (playNext && controls(uuid)) queue.add(0, entry);
        else queue.add(entry);
        if (current == null) {
            startEntry(pollFront(entry), 0L);
        } else {
            broadcastQueue();
            if (!entry.track.hasFreshUrl()) {
                // 点歌人本机解析不出完整音频（多半是会员曲而他不是会员），所以这首歌是「裸」进
                // 队的。现在就静默向全房要一条，而不是等轮到它才要 —— 那时房间会真的卡住
                // urlWaitTimeoutMs 那么久，运气不好直接被跳过。
                //
                // 必须放在 broadcastQueue() 之后：非网易云平台的客户端要靠歌单快照里的元数据
                // 才能重建曲目去解析，快照还没到就只能沉默。
                requestUrl(entry, true);
            }
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

    /**
     * 记一次命令帧下发。之后 {@link MpConfig#clockGraceMs} 毫秒内不发时钟心跳。
     *
     * <p>同时把 {@link #tickCounter} 归零：静默窗一结束就立刻打一发心跳没有意义，
     * 那时候慢的客户端可能刚开始播，让它走满一个完整的广播间隔再校准。</p>
     */
    private void markCommand() {
        lastCommandAt = System.currentTimeMillis();
        tickCounter = 0;
    }

    /** 是否还在命令帧之后的时钟静默窗内。 */
    private boolean inClockGrace(long now) {
        return cfg.clockGraceMs > 0L && lastCommandAt > 0L && now - lastCommandAt < cfg.clockGraceMs;
    }

    public long positionMs() {
        long pos = playing ? System.currentTimeMillis() - startEpoch : pausedPos;
        if (pos < 0) pos = 0;
        long dur = current == null ? 0 : current.track.durationMs;
        if (dur > 0 && pos > dur) pos = dur;
        return pos;
    }

    /** Owner/admin free switch: play this track right now, ahead of the queue. */
    public void playNow(UUID actor, String actorName, Track track) {
        QueueEntry entry = new QueueEntry(track, actor, actorName);
        queue.removeIf(e -> e.track.key().equals(track.key()));
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
        // 换歌是最容易引发集体误判的时刻：全房都在开流，位置必然是散的。
        markCommand();

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
     * 让客户端（重新）解析一条取流链接。
     *
     * <p>这是「非会员点歌，房间里的会员代为解析」的实现：服务端自己没有任何平台会话，所有链接
     * 都来自成员。收到请求的客户端只有在能解析出<b>完整</b>音频时才应答 —— 非会员解析到试听
     * 片段会主动沉默，所以最终被采纳的一定是某个会员成员的链接。</p>
     *
     * @param quiet 播放未中断时的预解析／续签走 true —— 不发聊天提示，因为并没有卡住
     */
    private void requestUrl(QueueEntry entry, boolean quiet) {
        String source = entry.track.source;

        // ① 服主配置了这个平台的 Token 时，先让服务端自己去解析。这条路对玩家零成本：
        //    不需要房间里恰好有个会员在线，也不需要谁把自己的账号暴露给别人。
        ServerResolve hook = serverResolve;
        if (hook != null) {
            try {
                hook.request(source, entry.track.songId, entry.track.name);
            } catch (Throwable t) {
                Net.warn("[mpsync] 服务端代解析调用失败", t);
            }
        }

        JsonObject m = Proto.msg(Proto.S_URL_NEEDED);
        // 带上 source：客户端要据此决定用哪个平台的会话去重新解析，也用于确认这条应答是给
        // 这首歌的。老客户端不读这个字段，行为不变。
        m.addProperty("source", source);
        m.addProperty("songId", entry.track.songId);
        m.addProperty("name", entry.track.name);

        // ② 再问客户端。顺序即「谁最可能答得上」：
        //    贡献 VIP 且装了这个平台的成员 → 装了这个平台的成员 → 点歌人 → 管理者 → 所有人。
        //
        //    为什么把贡献者放最前：他们明确表示愿意替房间解析，而且档案里说明了自己登录了这个
        //    平台。先问一次注定能答上的人，比广播给全房再等谁碰巧是会员要快得多，也少打扰。
        //
        //    例外：曲目压根没有链接，说明点歌人刚刚已经试过并且失败了（非会员只解析到试听片段，
        //    客户端会保持沉默）。这种情况下把他排到最后，让可能是会员的其他成员先答，省掉一次
        //    注定无用的往返。
        boolean requesterAlreadyFailed = !entry.track.hasFreshUrl();
        List<UUID> targets = new ArrayList<>();
        PresenceRegistry pr = presence;
        if (pr != null && cfg.preferContributors) {
            for (Member mem : members.values()) {
                if (mem.online && pr.contributes(mem.uuid, source)) targets.add(mem.uuid);
            }
            for (Member mem : members.values()) {
                if (mem.online && pr.canResolve(mem.uuid, source) && !targets.contains(mem.uuid)) {
                    targets.add(mem.uuid);
                }
            }
        }
        if (!requesterAlreadyFailed && entry.requester != null && members.containsKey(entry.requester)
                && !targets.contains(entry.requester)) {
            targets.add(entry.requester);
        }
        for (Member mem : members.values()) {
            if (mem.role.controls() && !targets.contains(mem.uuid)) targets.add(mem.uuid);
        }
        for (Member mem : members.values()) {
            if (!targets.contains(mem.uuid)) targets.add(mem.uuid);
        }
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
    public void supplyUrl(String source, String songId, String url, long expiresAt, long servedDurationMs) {
        if (songId == null) return;
        // 老客户端的 url_supply 不带 source，按网易云处理。
        String key = (source == null || source.isBlank() ? Proto.SOURCE_NETEASE : source) + ":" + songId;
        boolean touched = false;
        if (current != null && current.track.key().equals(key)) {
            boolean wasStalled = waitingUrl;
            if (!current.track.adoptUrl(url, expiresAt, servedDurationMs, cfg)) return;
            if (current.track.hasFreshUrl() && wasStalled) {
                // The room was holding for this URL: start the clock and push play.
                waitingUrl = false;
                playing = true;
                startEpoch = System.currentTimeMillis() - pausedPos;
                seq++;
                // 等到链接之后的起播等同于换歌，同样要让出静默窗。
                // （下面「播放中静默续签」那条路不打标记：它不动时钟，也不会让客户端重开流。）
                markCommand();
                broadcastPlay();
                broadcastQueue();
                return;
            }
            // Pre-emptive refresh while playing: store it silently so late joiners get a
            // live link. Re-broadcasting play here would restart everyone mid-song.
            touched = true;
        }
        for (QueueEntry e : queue) {
            if (e.track.key().equals(key) && !e.track.hasFreshUrl()
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
        markCommand();
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
        markCommand();
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
        markCommand();
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
        for (Member m : members.values()) if (m.actuallyListening()) n++;
        return n;
    }

    // ── 个人暂停 ─────────────────────────────────────────────────────────────

    /**
     * 个人暂停／恢复：只影响这一个成员，房间时钟一动不动。
     *
     * <p>恢复的那一刻必须由服务端立刻推一帧权威位置过去（{@link #sendResync}），否则他会
     * 从「按下暂停时的位置」接着放 —— 房间已经往前走了几分钟，他一开口就是脱节的。
     * 这也是为什么个人暂停要过服务端，而不是客户端自己静音了算。
     *
     * @return 面向用户的错误，null 表示成功
     */
    public String setSelfPause(UUID uuid, boolean paused) {
        Member m = members.get(uuid);
        if (m == null) return "你不在这个房间里";
        if (m.selfPaused == paused) return null;
        m.selfPaused = paused;
        if (!paused) sendResync(uuid);
        // 收听人数变了，全房都该看到。
        broadcastState();
        return null;
    }

    /**
     * 只给一个人补一帧「现在该放到哪」。
     *
     * <p>用的就是 {@link Proto#S_PLAY}：它带曲目、直链、位置、播放态，客户端收到会重开流并
     * 跳到位置 —— 这正是个人暂停恢复后需要的动作。{@code seq} 不自增，所以对别人毫无影响。
     */
    public void sendResync(UUID uuid) {
        if (current == null) {
            send(uuid, Proto.msg(Proto.S_STOP));
            return;
        }
        JsonObject m = Proto.msg(Proto.S_PLAY);
        m.add("track", current.toJson(true));
        m.addProperty("requesterName", current.requesterName == null ? "" : current.requesterName);
        m.addProperty("playing", playing);
        m.addProperty("resync", true);
        stamp(m, positionMs());
        send(uuid, m);
    }

    // ── 投票（频道） ──────────────────────────────────────────────────────────

    public List<VoteSession> votes() { return votes; }

    /** 分母：在线成员数。离线的人投不了票，把他算进分母等于永远过不了。 */
    public int voteEligible() {
        return voteEligible(null);
    }

    /**
     * 某个投票的分母。
     *
     * <p>踢人投票把<b>被投的人自己</b>排除在外。否则 80% 的门槛在小房间里等于「永远踢不掉」——
     * 5 个人里踢 1 个需要 {@code floor(5×0.8)+1 = 5} 票，也就是连当事人都得投同意。排除他之后，
     * 门槛的含义变成「除他以外的人里超过 80% 同意」，这才是需求那句话可执行的形态。
     */
    public int voteEligible(VoteSession v) {
        int n = 0;
        for (Member m : members.values()) {
            if (!m.online) continue;
            if (v != null && v.kind == VoteSession.Kind.KICK && m.uuid.equals(v.target)) continue;
            n++;
        }
        return Math.max(1, n);
    }

    public VoteSession vote(long voteId) {
        for (VoteSession v : votes) if (v.id == voteId) return v;
        return null;
    }

    /** 针对某人的踢人投票还剩多少冷却（毫秒）；0 表示可以发起。 */
    public long kickCooldownLeft(UUID target) {
        Long until = kickCooldown.get(target);
        if (until == null) return 0L;
        long left = until - System.currentTimeMillis();
        if (left <= 0L) {
            kickCooldown.remove(target);
            return 0L;
        }
        return left;
    }

    /**
     * 发起一次投票。
     *
     * @return 面向用户的错误，null 表示已开始
     */
    public String startVote(VoteSession.Kind kindOf, UUID initiator, UUID target) {
        if (!channel()) return "投票只在服务器频道里使用";
        Member me = members.get(initiator);
        if (me == null) return "你不在频道里";
        int eligible = voteEligible();
        if (eligible < cfg.voteMinMembers) {
            return "频道里人太少（不足 " + cfg.voteMinMembers + " 人），暂不需要投票";
        }
        for (VoteSession v : votes) {
            if (v.kind == kindOf && (kindOf == VoteSession.Kind.SKIP
                    || (target != null && target.equals(v.target)))) {
                return "已经有一个同样的投票在进行中";
            }
        }
        String subject;
        double ratio;
        if (kindOf == VoteSession.Kind.SKIP) {
            if (current == null) return "现在没有在放的歌";
            subject = current.track.display();
            ratio = cfg.voteSkipRatio;
        } else {
            Member t = target == null ? null : members.get(target);
            if (t == null) return "对方不在频道里";
            if (target.equals(initiator)) return "不能对自己发起踢出投票";
            if (serverAdmin(target)) return "不能投票踢出服务器管理员";
            long left = kickCooldownLeft(target);
            if (left > 0L) {
                return "上一次针对 " + t.name + " 的投票没有通过，冷却还剩 "
                        + yun.shan.mpsync.util.Durations.label(left);
            }
            subject = t.name;
            ratio = cfg.voteKickRatio;
        }
        VoteSession v = new VoteSession(kindOf, initiator, me.name, target, subject,
                cfg.voteWindowMs, ratio);
        votes.add(v);
        eligible = voteEligible(v);
        broadcastVote(v);
        // 聊天栏广播：需求要的那句话。装了播放器的成员在 GUI 里会看到一张投票卡（带按钮），
        // 没装的人靠这一行也能参与 —— 命令和按钮走的是同一条 vote_cast。
        Net.chatAll(members.keySet(), "[一起听] " + v.headline() + "，回「/mp agree」表示同意（"
                + (cfg.voteWindowMs / 1000L) + " 秒内，需 " + v.needed(eligible) + "/" + eligible + " 票）");
        // 发起人那一票已经算上了，可能一个人就够（比如两个人的频道里 2 票门槛只需要 2 票时不够，
        // 但 1 人频道被 voteMinMembers 挡掉了），所以立刻检查一次。
        settleIfDecided(v);
        return null;
    }

    /** @return 面向用户的提示；null 表示投票已记录 */
    public String castVote(long voteId, UUID uuid, boolean agree) {
        VoteSession v = voteId > 0 ? vote(voteId) : latestOpenVote();
        if (v == null) return "现在没有进行中的投票";
        if (!members.containsKey(uuid)) return "只有频道里的玩家可以投票";
        // 当事人不参与自己的踢出投票：他不在分母里（否则 80% 永远够不到），
        // 那么他的票也不该进分子，否则「剩下多少人没投」这个账算不平。
        if (v.kind == VoteSession.Kind.KICK && uuid.equals(v.target)) {
            return "你不能给针对自己的投票投票";
        }
        if (!v.cast(uuid, agree)) return "你已经投过票了";
        broadcastVote(v);
        settleIfDecided(v);
        return null;
    }

    /** 最近开始、还没结束的那个投票 —— 聊天栏里回「同意」时不带 id，就投给它。 */
    public VoteSession latestOpenVote() {
        VoteSession best = null;
        for (VoteSession v : votes) {
            if (v.closed()) continue;
            if (best == null || v.startedAt > best.startedAt) best = v;
        }
        return best;
    }

    private void settleIfDecided(VoteSession v) {
        int eligible = voteEligible(v);
        // 两种都要提前结算：票数已经够了，以及剩下的人全投同意也够不到门槛。
        // 后者不判的话，一个注定失败的投票会把频道整整挂住一个窗口。
        if (v.decided(eligible) || v.hopeless(eligible)) finishVote(v);
    }

    /** 结算一个投票并执行结果。 */
    private void finishVote(VoteSession v) {
        int eligible = voteEligible(v);
        boolean passed = v.close(eligible);
        broadcastVote(v);
        votes.remove(v);
        if (v.kind == VoteSession.Kind.SKIP) {
            if (passed) {
                notice("投票通过（" + v.agrees() + "/" + eligible + "），切到下一首");
                next(false);
            } else {
                notice("切歌投票未通过（" + v.agrees() + "/" + eligible + "，需 "
                        + v.needed(eligible) + " 票）");
            }
            return;
        }
        Member t = v.target == null ? null : members.get(v.target);
        String who = t != null ? t.name : v.subject;
        if (passed) {
            notice("投票通过（" + v.agrees() + "/" + eligible + "），" + who + " 被移出频道");
            pendingKick = v.target;       // 真正的移除要回到 RoomManager 做（它管 playerRoom）
        } else {
            // 需求：不满足票数就进冷却，避免乱踢。冷却比投票窗口长得多（默认 1 小时）。
            if (v.target != null) {
                kickCooldown.put(v.target, System.currentTimeMillis() + cfg.voteKickCooldownMs);
            }
            notice("踢出 " + who + " 的投票未通过（" + v.agrees() + "/" + eligible + "，需 "
                    + v.needed(eligible) + " 票），" + yun.shan.mpsync.util.Durations.label(cfg.voteKickCooldownMs)
                    + "内不能再对他发起");
        }
    }

    /**
     * 投票通过要踢的人。
     *
     * <p>为什么不在 {@link #finishVote} 里直接踢：成员表在 Room 里，但「玩家 → 房间」的索引在
     * {@link RoomManager} 里，从 Room 里单方面删人会留下一条指向空房间的索引。所以这里只登记，
     * 由 RoomManager 在 tick 里取走并走它自己的 kick 流程。
     */
    private UUID pendingKick;

    public UUID takePendingKick() {
        UUID out = pendingKick;
        pendingKick = null;
        return out;
    }

    public void broadcastVote(VoteSession v) {
        broadcast(v.frame(voteEligible(v)));
    }

    /** 每个 tick 检查超时的投票。 */
    private void tickVotes(long now) {
        if (votes.isEmpty()) return;
        for (Iterator<VoteSession> it = votes.iterator(); it.hasNext(); ) {
            VoteSession v = it.next();
            if (!v.expired(now)) continue;
            it.remove();
            // finishVote 会再从列表里删一次（已经不在了，remove 返回 false，无害），
            // 这样超时和提前结算走的是同一段结算逻辑。
            finishVote(v);
        }
    }

    // ── tick ─────────────────────────────────────────────────────────────────

    public void tick() {
        long now = System.currentTimeMillis();

        // 投票先结算：一个超时的切歌投票可能正好要换歌，让它排在时钟判断之前，
        // 免得同一 tick 里「自动进下一首」和「投票切歌」各切一次。
        tickVotes(now);

        if (waitingUrl && now - waitingSince > cfg.urlWaitTimeoutMs) {
            waitingUrl = false;
            String title = current != null ? current.track.name : "";
            // 走到这里说明房间里没有一个成员能解析出这首歌的完整音频（会员曲而在场都不是会员，
            // 或者这首歌本身已下架）。把原因说明白，否则看起来像是服务端坏了。
            notice("房间里没有成员能解析出《" + title + "》的完整播放地址（可能需要会员），已跳过");
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

        // 心跳只在「房间确实在稳定播放」时发。两个门禁：
        //   waitingUrl —— 正卡在等链接，位置没有意义；
        //   inClockGrace —— 命令帧刚下发，全房都在开流，这时候校准只会引发一轮集体误判 seek。
        if (++tickCounter >= cfg.timeBroadcastTicks) {
            tickCounter = 0;
            if (current != null && !waitingUrl && !inClockGrace(now)) broadcastTime();
        }
    }

    // ── snapshots + sending ──────────────────────────────────────────────────

    /** Full room snapshot. Stream URLs are only included for the track that is playing. */
    public JsonObject snapshot() {
        JsonObject room = new JsonObject();
        room.addProperty("id", id);
        room.addProperty("code", code);
        room.addProperty("name", name);
        room.addProperty("owner", owner == null ? "" : owner.toString());
        room.addProperty("repeat", repeat.id());
        room.addProperty("listeners", listenerCount());
        // v2：房间种类、是否公开、单人在队上限。老客户端读不到，行为不变。
        room.addProperty("kind", kind.id());
        room.addProperty("channel", channel());
        room.addProperty("public", open);
        room.addProperty("perMemberCap", perMemberCap());

        JsonArray mem = new JsonArray();
        for (Member m : members.values()) mem.add(m.toJson(profileOf(m.uuid)));
        room.add("members", mem);

        room.add("queue", queueJson());
        room.add("history", historyJson());

        if (!votes.isEmpty()) {
            JsonArray va = new JsonArray();
            for (VoteSession v : votes) va.add(v.toJson(voteEligible(v)));
            room.add("votes", va);
        }

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

    /** 这个成员的客户端档案；没注入档案表或没上报过都返回 null。 */
    private ClientProfile profileOf(UUID uuid) {
        PresenceRegistry p = presence;
        return p == null ? null : p.get(uuid);
    }

    /**
     * 公开房间列表里的一条。
     *
     * <p>刻意<b>不</b>带直链，也不带完整歌单 —— 这份数据会发给房间外的人，他们只需要判断
     * 「这个房间在放什么、有多少人、要不要进」。
     */
    public JsonObject publicJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("code", code);
        o.addProperty("name", name);
        o.addProperty("kind", kind.id());
        o.addProperty("channel", channel());
        Member ow = owner == null ? null : members.get(owner);
        o.addProperty("ownerName", ow == null ? (channel() ? "服务器" : "") : ow.name);
        o.addProperty("members", members.size());
        o.addProperty("listeners", listenerCount());
        o.addProperty("queue", queue.size());
        o.addProperty("playing", playing);
        o.addProperty("nowPlaying", current == null ? "" : current.track.display());
        o.addProperty("full", full());
        return o;
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

    /**
     * 时钟心跳。带 {@code heartbeat: true} 明示「这不是命令」——客户端据此决定用软/硬偏差
     * 那一套阈值去判断要不要纠偏，而不是像收到 play/seek 那样无条件对齐。
     * 命令帧不带这个字段，老客户端读不到也不影响（缺省视为 false）。
     */
    public void broadcastTime() {
        JsonObject m = Proto.msg(Proto.S_TIME);
        m.addProperty("heartbeat", true);
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
        Net.chatAll(members.keySet(), "[一起听] " + text);
    }

    public void broadcast(JsonObject msg) {
        for (UUID uuid : members.keySet()) send(uuid, msg);
    }

    public void send(UUID uuid, JsonObject msg) {
        Net.send(uuid, msg);
    }

    public void sendSnapshot(UUID uuid) {
        Net.send(uuid, snapshot());
    }
}
