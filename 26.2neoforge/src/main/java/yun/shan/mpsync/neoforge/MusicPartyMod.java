package yun.shan.mpsync.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

@Mod(MusicPartyMod.MODID)
public final class MusicPartyMod {

    public static final String MODID = "mpsync";

    public MusicPartyMod(IEventBus modBus) {
        modBus.addListener(MpNet::register);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MpParty.init(new NeoForgePlatform(event.getServer(), MpNet::canSend, MpNet::send),
                FMLPaths.CONFIGDIR.get().resolve("mpsync.json"));
        NeoForgePlatform.LOGGER.info("[mpsync] MusicParty ready — protocol v{}, channels {} / {}",
                Proto.VERSION, Proto.C2S, Proto.S2C);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(NeoForgeCommands.root());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MpParty.tick();
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() != null) MpParty.onPlayerJoin(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) MpParty.onPlayerQuit(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent event) {
        MpParty.shutdown();
    }
}
