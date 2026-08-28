package yun.shan.mpsync.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

/**
 * MusicParty for Fabric 1.21.11.
 *
 * <p>All room logic lives in the shared {@code core} sources; this class only wires the
 * platform up. Declared {@code environment: "*"} so a LAN host's integrated server also
 * serves rooms; it registers no client-side code.
 */
public final class MusicPartyMod implements ModInitializer {

    @Override
    public void onInitialize() {
        MpNet.register();
        FabricCommands.register(src -> src.getPermissions().hasPermission(
                new Permission.Level(PermissionLevel.GAMEMASTERS)));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            MpParty.init(new FabricPlatform(server, MpNet::canSend, MpNet::send),
                    FabricLoader.getInstance().getConfigDir().resolve("mpsync.json"));
            FabricPlatform.LOGGER.info("[mpsync] MusicParty ready — protocol v{}, channels {} / {}",
                    Proto.VERSION, Proto.C2S, Proto.S2C);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> MpParty.tick());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                MpParty.onPlayerJoin(handler.getPlayer().getUuid()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MpParty.onPlayerQuit(handler.getPlayer().getUuid()));

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> MpParty.shutdown());
    }
}
