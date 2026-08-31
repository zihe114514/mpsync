package yun.shan.mpsync.support;

import com.google.gson.JsonObject;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.platform.MpPlatform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 测试用的假宿主服务器。
 *
 * <p>{@link MpPlatform} 只有 8 个方法，而且只有原始字节跨过这个边界 —— 这正是 core 能被
 * 纯 JVM 测试覆盖的原因：这里不需要 Minecraft，只需要把 {@code sendRaw} 收到的帧解码存下来。</p>
 *
 * <p>{@link #execute} 直接同步执行：房间状态本来就是单线程的，测试里再丢到别的线程只会
 * 让断言变成竞态。</p>
 */
public final class FakePlatform implements MpPlatform {

    /** 一条下发到某个玩家的帧。 */
    public record Sent(UUID to, JsonObject frame) {
        public String op() { return frame.has(Proto.OP) ? frame.get(Proto.OP).getAsString() : ""; }
    }

    public final Map<UUID, String> names = new LinkedHashMap<>();
    public final List<Sent> sent = new ArrayList<>();
    public final List<String> chat = new ArrayList<>();
    /** 置 false 可以模拟「客户端没声明通道」。 */
    public boolean canSend = true;

    public UUID addPlayer(String name) {
        UUID uuid = UUID.randomUUID();
        names.put(uuid, name);
        return uuid;
    }

    @Override public String nameOf(UUID uuid) { return names.get(uuid); }

    @Override public UUID uuidByName(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    @Override public Collection<UUID> onlinePlayers() { return names.keySet(); }

    @Override public boolean canSend(UUID uuid) { return canSend; }

    @Override public void sendRaw(UUID uuid, byte[] payload) {
        JsonObject frame = Proto.decode(payload);
        if (frame != null) sent.add(new Sent(uuid, frame));
    }

    @Override public void chat(UUID uuid, String text) { chat.add(text); }

    @Override public void execute(Runnable task) { task.run(); }

    @Override public void warn(String message, Throwable t) { }

    // ── 断言辅助 ──────────────────────────────────────────────────────────────

    public void clear() { sent.clear(); chat.clear(); }

    public List<JsonObject> frames(String op) {
        List<JsonObject> out = new ArrayList<>();
        for (Sent s : sent) if (op.equals(s.op())) out.add(s.frame());
        return out;
    }

    public int count(String op) { return frames(op).size(); }

    /** 某个 op 的最后一帧，没有则 null。 */
    public JsonObject last(String op) {
        List<JsonObject> all = frames(op);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }
}
