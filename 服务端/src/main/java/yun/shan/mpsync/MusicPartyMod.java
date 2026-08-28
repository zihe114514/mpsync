package yun.shan.mpsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import yun.shan.mpsync.command.MpCommands;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.net.ServerNet;
import yun.shan.mpsync.room.RoomManager;

/**
 * MusicParty — server-side synced listening rooms for the Melodify client mod.
 *
 * <p>Scope on purpose: this mod moves <em>metadata and a clock</em>, never audio. A
 * client resolves a NetEase share link with its own session and uploads
 * {songId, title, artist, cover, duration, streamUrl}; the server validates the source,
 * keeps the room queue, and pushes play/pause/seek plus an authoritative position to
 * every member. See {@link Proto} for the wire format.
 *
 * <p>Declared {@code environment: "*"} so it also runs on the integrated server of a LAN
 * host; it registers no client-side code.
 */
public final class MusicPartyMod implements ModInitializer {

    public static final String MODID = "mpsync";

    private static MpConfig config;
    private static RoomManager manager;

    public static MpConfig config() { return config; }

    public static RoomManager manager() { return manager; }

    @Override
    public void onInitialize() {
        config = MpConfig.load(FabricLoader.getInstance().getConfigDir().resolve("mpsync.json"));
        manager = new RoomManager(config);

        ServerNet.register(manager);
        MpCommands.register(manager);

        ServerTickEvents.END_SERVER_TICK.register(manager::tick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                manager.onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                manager.onPlayerDisconnect(handler.getPlayer()));

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> manager.shutdown());

        ServerNet.LOGGER.info("[mpsync] MusicParty ready — protocol v{}, channels {} / {}",
                Proto.VERSION, Proto.C2S, Proto.S2C);
    }
}
