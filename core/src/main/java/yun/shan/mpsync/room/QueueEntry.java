package yun.shan.mpsync.room;

import com.google.gson.JsonObject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** A queued 点歌 request: the track plus who asked for it. */
public final class QueueEntry {

    private static final AtomicLong SEQ = new AtomicLong(1L);

    /** Stable handle so clients can remove/move an entry without index races. */
    public final long entryId = SEQ.getAndIncrement();
    public final Track track;
    public final UUID requester;
    public final String requesterName;
    public final long addedAt = System.currentTimeMillis();

    /**
     * 房主算出的交接计划：这一首放完时怎么交给下一首。
     *
     * <p>刻意可变：计划由房主在曲目<b>已经开播之后</b>上报（要先拿到下一首的音频才能分析），
     * 到达时刻不固定。{@code null} 表示还没有计划，换歌退回配置里的默认提前量 —— 这也是
     * 「老客户端 + 新服务端」以及「房主那台算不出」两种情况的共同退路。</p>
     */
    public volatile JsonObject automixPlan;

    /**
     * 这一格是房主/管理员用「下一首播放」<b>插</b>进来的。
     *
     * <p>只在服务端内部用，故意不上协议、不进 {@link #toJson(boolean)}：它唯一的作用是让连续插队
     * 能认出「已经插过的那几格」，从而按插入顺序排而不是倒序（见 {@code Room#jumpInsertAt}）。
     * 加一个 wire 字段要动客户端与快照，为一个纯内部排序问题不值当。</p>
     */
    public volatile boolean jumped;

    public QueueEntry(Track track, UUID requester, String requesterName) {
        this.track = track;
        this.requester = requester;
        this.requesterName = requesterName;
    }

    public JsonObject toJson(boolean withUrl) {
        JsonObject o = track.toJson(withUrl);
        o.addProperty("entryId", entryId);
        o.addProperty("requester", requester == null ? "" : requester.toString());
        o.addProperty("requesterName", requesterName == null ? "" : requesterName);
        o.addProperty("addedAt", addedAt);
        return o;
    }
}
