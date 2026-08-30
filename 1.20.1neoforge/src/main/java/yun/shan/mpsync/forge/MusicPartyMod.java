package yun.shan.mpsync.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

@Mod(MusicPartyMod.MODID)
public final class MusicPartyMod {

    public static final String MODID = "mpsync";

    public MusicPartyMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(MpNet::register);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MpParty.init(new ForgePlatform(event.getServer(), MpNet::canSend, MpNet::send),
                FMLPaths.CONFIGDIR.get().resolve("mpsync.json"));
        ForgePlatform.LOGGER.info("[mpsync] MusicParty ready — protocol v{}, channels {} / {}",
                Proto.VERSION, Proto.C2S, Proto.S2C);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(ForgeCommands.root(src -> src.hasPermission(2)));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) MpParty.tick();
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