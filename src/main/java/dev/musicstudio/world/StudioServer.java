package dev.musicstudio.world;

import dev.musicstudio.network.StudioNetwork;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class StudioServer {
    public static void register(){NeoForge.EVENT_BUS.addListener(StudioServer::tick);NeoForge.EVENT_BUS.addListener(StudioServer::unload);NeoForge.EVENT_BUS.addListener(StudioServer::logout);NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e)->dev.musicstudio.network.MediaNetwork.manifest((net.minecraft.server.level.ServerPlayer)e.getEntity()));NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e)->dev.musicstudio.network.MediaNetwork.stop());}
    private static void tick(LevelTickEvent.Post e){if(e.getLevel() instanceof ServerLevel level){BlueprintPlacement.tick(level);DiscPlayback.tick(level);if(level==level.getServer().overworld())dev.musicstudio.network.MediaNetwork.tick();if(level.getGameTime()%200==0)StudioNetwork.expire();}}
    private static void unload(LevelEvent.Unload e){if(e.getLevel() instanceof ServerLevel level){BlueprintPlacement.unload(level);DiscPlayback.unload(level);}}
    private static void logout(PlayerEvent.PlayerLoggedOutEvent e){StudioNetwork.logout(e.getEntity().getUUID());dev.musicstudio.network.MediaNetwork.logout(e.getEntity().getUUID());}
}
