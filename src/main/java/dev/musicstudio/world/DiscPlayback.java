package dev.musicstudio.world;

import dev.musicstudio.network.MediaNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import java.util.*;

/** Ordinary jukebox storage/ejection, with a dynamic audio snapshot attached to the disc. */
public final class DiscPlayback {
    private record Playing(String hash,long start,int length){}
    private static final Map<ServerLevel,Map<BlockPos,Playing>> ACTIVE=new IdentityHashMap<>();
    public static void register(){NeoForge.EVENT_BUS.addListener(DiscPlayback::insert);}
    private static void insert(PlayerInteractEvent.RightClickBlock e){
        if(!(e.getLevel() instanceof ServerLevel level)||!(e.getEntity() instanceof ServerPlayer player))return;var item=e.getItemStack();String hash=StudioDisc.hash(item);if(hash.isEmpty()||!level.getBlockState(e.getPos()).is(Blocks.JUKEBOX))return;
        if(!level.mayInteract(player,e.getPos())){e.setCancellationResult(InteractionResult.FAIL);e.setCanceled(true);return;}
        if(!java.nio.file.Files.isRegularFile(MediaNetwork.file(level.getServer(),hash))){StudioMessages.error(player,new dev.musicstudio.song.StudioException("error.sample_server"));e.setCancellationResult(InteractionResult.FAIL);e.setCanceled(true);return;}
        // Vanilla handles actual insertion, statistics, hoppers and ejection; the jukebox hook observes its item.
    }
    public static void changed(JukeboxBlockEntity jukebox){if(!(jukebox.getLevel() instanceof ServerLevel level))return;BlockPos pos=jukebox.getBlockPos();var active=ACTIVE.computeIfAbsent(level,k->new HashMap<>());var old=active.remove(pos);if(old!=null)broadcast(level,pos,old,true,"");var item=jukebox.getTheItem();String hash=StudioDisc.hash(item);if(!hash.isEmpty()&&java.nio.file.Files.isRegularFile(MediaNetwork.file(level.getServer(),hash))){var play=new Playing(hash,level.getGameTime(),StudioDisc.ticks(item));active.put(pos.immutable(),play);broadcast(level,pos,play,false,item.getHoverName().getString());}}
    private static void broadcast(ServerLevel level,BlockPos pos,Playing p,boolean stop,String title){for(ServerPlayer player:level.players())if(player.blockPosition().distSqr(pos)<128*128)MediaNetwork.send(player,new MediaNetwork.Action(stop?"disc_stop":"disc_play",UUID.randomUUID(),p.hash,title,(int)Math.max(1,p.length-(level.getGameTime()-p.start)),List.of(""+pos.asLong())));}
    public static void tick(ServerLevel level){var active=ACTIVE.get(level);if(active==null)return;for(var it=active.entrySet().iterator();it.hasNext();){var entry=it.next();var pos=entry.getKey();var p=entry.getValue();boolean stop=!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof JukeboxBlockEntity box)||!StudioDisc.hash(box.getTheItem()).equals(p.hash)||!box.getSongPlayer().isPlaying()||level.getGameTime()-p.start>=p.length;if(stop){if(level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof JukeboxBlockEntity box&&StudioDisc.hash(box.getTheItem()).equals(p.hash))box.getSongPlayer().stop(level,box.getBlockState());broadcast(level,pos,p,true,"");it.remove();}}if(active.isEmpty())ACTIVE.remove(level);}
    public static void unload(ServerLevel level){ACTIVE.remove(level);}
}
