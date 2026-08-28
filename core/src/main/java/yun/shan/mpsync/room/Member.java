package yun.shan.mpsync.room;

import com.google.gson.JsonObject;

import java.util.UUID;

/** One participant of a {@link Room}. Mutated on the server thread only. */
public final class Member {

    public final UUID uuid;
    public String name;
    public Role role;
    /** Online here means "has an active play connection", not "currently playing audio". */
    public boolean online = true;
    /** Last client heartbeat ({@code progress}) — epoch ms. */
    public long lastSeen = System.currentTimeMillis();
    /** Whether the client reports audio actually playing (drives the listener count). */
    public boolean listening;
    /** Last position the client reported, for drift diagnostics via {@code /mp info}. */
    public long reportedPos;

    public Member(UUID uuid, String name, Role role) {
        this.uuid = uuid;
        this.name = name;
        this.role = role;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name);
        o.addProperty("role", role.id());
        o.addProperty("online", online);
        o.addProperty("listening", listening);
        return o;
    }
}
