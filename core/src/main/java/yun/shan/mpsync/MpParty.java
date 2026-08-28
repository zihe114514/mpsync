package yun.shan.mpsync;

import com.google.gson.JsonObject;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.platform.MpPlatform;
import yun.shan.mpsync.room.RoomManager;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Entry point every platform adapter drives. A Fabric mod initialiser or a Bukkit
 * {@code onEnable} does exactly four things: {@link #init}, forward inbound frames,
 * forward join/quit/tick, and {@link #shutdown}.
 *
 * <p>Nothing below this class knows which Minecraft version — or even which mod loader —
 * it is running on, which is why one room can hold a 1.20.1 and a 1.21.11 client at once.
 */
public final class MpParty {

    public static final String MODID = "mpsync";

    private MpParty() {}

    private static MpConfig config;
    private static RoomManager manager;
    private static MpPlatform platform;

    public static MpConfig config() { return config; }

    public static RoomManager manager() { return manager; }

    public static MpPlatform platform() { return platform; }

    /** @param configFile usually {@code <config or plugin dir>/mpsync.json} */
    public static void init(MpPlatform p, Path configFile) {
        platform = p;
        config = MpConfig.load(configFile);
        manager = new RoomManager(config);
        Net.bind(p);
    }

    /**
     * Feed one raw C2S payload. Safe to call from a network thread: the hop to the main
     * thread happens here, so no adapter has to remember to do it.
     */
    public static void onPacket(UUID sender, byte[] payload) {
        if (manager == null || sender == null) return;
        JsonObject msg = Proto.decode(payload);
        if (msg == null) return;
        Net.markCapable(sender);
        MpPlatform p = platform;
        if (p == null) return;
        p.execute(() -> {
            if (manager != null) manager.handle(sender, msg);
        });
    }

    public static void onPlayerJoin(UUID uuid) {
        if (manager != null) manager.onPlayerJoin(uuid);
    }

    public static void onPlayerQuit(UUID uuid) {
        if (manager != null) manager.onPlayerQuit(uuid);
    }

    /** Call once per server tick (Fabric) or from a 1-tick repeating task (Paper). */
    public static void tick() {
        if (manager != null) manager.tick();
    }

    public static void shutdown() {
        if (manager != null) manager.shutdown();
    }

    /** Version string reported to clients in the hello frame. */
    public static int protocol() { return Proto.VERSION; }
}
