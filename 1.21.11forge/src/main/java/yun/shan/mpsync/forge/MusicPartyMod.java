package yun.shan.mpsync.forge;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.net.Proto;

@Mod(MusicPartyMod.MODID)
public final class MusicPartyMod {
    public static final String MODID = "mpsync";

    public MusicPartyMod(FMLJavaModLoadingContext context) {
        MpNet.register();
        ServerStartingEvent.BUS.addListener(MusicPartyMod::onServerStarting);
        RegisterCommandsEvent.BUS.addListener(MusicPartyMod::onRegisterCommands);
        TickEvent.ServerTickEvent.Post.BUS.addListener(MusicPartyMod::onServerTick);
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(MusicPartyMod::onLogin);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(MusicPartyMod::onLogout);
        ServerStoppedEvent.BUS.addListener(MusicPartyMod::onStopped);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        MpParty.init(new ForgePlatform(event.getServer(), MpNet::canSend, MpNet::send),
                FMLPaths.CONFIGDIR.get().resolve("mpsync.json"));
        ForgePlatform.LOGGER.info("[mpsync] MusicParty ready — protocol v{}, channels {} / {}",
                Proto.VERSION, Proto.C2S, Proto.S2C);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(ForgeCommands.root(src ->
                src.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))));
    }

    private static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        MpParty.tick();
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() != null) MpParty.onPlayerJoin(event.getEntity().getUUID());
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) MpParty.onPlayerQuit(event.getEntity().getUUID());
    }

    private static void onStopped(ServerStoppedEvent event) {
        MpParty.shutdown();
    }
}
