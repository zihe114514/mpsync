package yun.shan.mpsync.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

/** MusicParty for Fabric on Minecraft 26.2 (official names, no remapping). */
public final class MusicPartyMod implements ModInitializer {

    @Override
    public void onInitialize() {
        MpNet.register();
        // 指令必须在这里注册：放进 SERVER_STARTING 会错过专用服务器第一次构建指令表，/mp 不存在。
        MpCommands.register();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            OfficialPlatform platform = new OfficialPlatform(server);
            MpParty.init(platform, FabricLoader.getInstance().getConfigDir().resolve("mpsync.json"));
            OfficialPlatform.LOGGER.info("[mpsync] MusicParty ready — protocol v{}, channels {} / {}",
                    Proto.VERSION, Proto.C2S, Proto.S2C);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> MpParty.tick());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                MpParty.onPlayerJoin(handler.getPlayer().getUUID()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MpParty.onPlayerQuit(handler.getPlayer().getUUID()));

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> MpParty.shutdown());
    }
}
