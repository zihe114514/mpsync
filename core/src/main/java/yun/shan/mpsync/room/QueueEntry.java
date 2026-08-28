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
