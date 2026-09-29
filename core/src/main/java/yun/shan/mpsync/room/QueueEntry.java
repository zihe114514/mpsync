package yun.shan.mpsync.room;

import com.google.gson.JsonObject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** A queued 点歌 request: the track plus who asked for it. */
public final class QueueEntry {

    /** 房主/管理员用「下一首播放」插进来的 —— 排在最前。 */
    public static final int TIER_JUMP = 0;
    /** 一首一首点进来的排队歌 —— 中间档。 */
    public static final int TIER_REQUEST = 1;
    /** 整张歌单灌进来的待播歌 —— 谁都能插到它前面。 */
    public static final int TIER_PLAYLIST = 2;

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
     * 这一格在队列里排第几档，见 {@link #TIER_JUMP} / {@link #TIER_REQUEST} / {@link #TIER_PLAYLIST}。
     *
     * <p>它取代了原来那个「插过队没有」的布尔位。布尔位只说得出「压在所有排队歌前面」，而房间里
     * 现在有三种来路：房主/管理员插队、别人一首首点的排队歌、以及整张歌单灌进来的待播歌。
     * 优先级就按档位数值比：{@code Room#insertAt(int)} 把新条目落在「第一个档位比我低的条目」之前，
     * 于是插队段永远最前、点歌排在歌单之前、歌单垫底，而同一档内部保持先到先得。</p>
     *
     * <p>和原来的 {@code jumped} 一样，档位本身<b>不上协议</b>：客户端要看的是
     * {@link #playlist} 有没有名字，那已经足够把第三档标出来；插队与点歌的区别在聊天提示里写着。
     * 多一个 wire 字段就要多动一处客户端和快照，为一个界面用不上的信息不值当。</p>
     */
    public volatile int tier = TIER_REQUEST;

    /**
     * 这一格来自哪张歌单；不是灌单进来的就是空串。
     *
     * <p>这是上协议的（{@link #toJson(boolean)} 里的 {@code playlist}），因为界面上要能回答
     * 「这一首是谁点的还是歌单里的」—— 一张 100 首的单子灌进来之后，如果列表里全是「点歌 张三」，
     *  nobody 看得出后面几十首是自动排的，也就不知道自己点的那首其实会插在那批歌前面。</p>
     */
    public volatile String playlist = "";

    /** 这一格是不是房主/管理员插进来的。 */
    public boolean jumped() { return tier == TIER_JUMP; }

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
        // 只在真属于某张歌单时才带这个键：队列里绝大多数条目是逐首点的，没必要每帧多发一份空串。
        if (playlist != null && !playlist.isEmpty()) o.addProperty("playlist", playlist);
        return o;
    }
}
