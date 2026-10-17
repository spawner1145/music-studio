package dev.musicstudio.world;

import dev.musicstudio.MusicStudio;
import dev.musicstudio.blueprint.MusicBlueprint;
import dev.musicstudio.song.StudioException;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;

/** The server places ordinary vanilla blocks; thereafter Minecraft owns all signal propagation. */
public final class BlueprintPlacement {
    private static final Map<ServerLevel,List<Job>> JOBS=new IdentityHashMap<>();
    private static final class Job {
        final ServerPlayer player;final BlockPos anchor;final MusicBlueprint b;final int rotation;
        final List<BlockPos> positions;final List<BlockSnapshot> old=new ArrayList<>();int written;
        Job(ServerPlayer p,BlockPos anchor,MusicBlueprint b,int r){player=p;this.anchor=anchor;this.b=b;rotation=r;positions=b.parts().stream().map(part->b.rotate(part.pos(),r)).map(c->anchor.offset(c.x(),c.y(),c.z())).toList();}
    }
    public static boolean busy(ServerLevel level){return JOBS.containsKey(level);}
    public static BlockPos lever(BlockPos anchor,MusicBlueprint b,int r){var c=b.rotate(b.lever(),r);return anchor.offset(c.x(),c.y(),c.z());}
    public static void begin(ServerLevel level,ServerPlayer player,BlockPos anchor,MusicBlueprint b,int rotation){
        if(JOBS.values().stream().flatMap(List::stream).anyMatch(j->j.player.getUUID().equals(player.getUUID()))||JOBS.values().stream().mapToInt(List::size).sum()>=8)throw new StudioException("error.blueprint_busy");
        if(b.outputs().stream().anyMatch(MusicBlueprint.Output::command)&&(!player.hasPermissions(2)||!level.getServer().isCommandBlockEnabled()))throw new StudioException("error.blueprint_commands");
        for(var note:b.notes()){var voice=b.voices().get(note.voice());if(voice.sound().startsWith("musicstudio:sample/")&&!java.nio.file.Files.isRegularFile(dev.musicstudio.network.MediaNetwork.file(level.getServer(),voice.sound().substring("musicstudio:sample/".length()))))throw new StudioException("error.sample_server");}
        if(!b.fitsRotation(rotation))throw new StudioException("error.vanilla_rotation");
        checkArea(level,player,anchor,b,rotation);var job=new Job(player,anchor,b,rotation);
        JOBS.computeIfAbsent(level,k->new ArrayList<>()).add(job);player.displayClientMessage(Component.translatable("musicstudio.blueprint.building",b.parts().size()),true);
    }
    public static void checkArea(ServerLevel level,ServerPlayer p,BlockPos anchor,MusicBlueprint b,int r){
        for(int y=0;y<b.y();y++)for(int z=0;z<b.depth(r);z++)for(int x=0;x<b.width(r);x++){
            BlockPos pos=anchor.offset(x,y,z);
            if(level.isOutsideBuildHeight(pos)||!level.getWorldBorder().isWithinBounds(pos))throw new StudioException("error.blueprint_bounds");
            if(!level.hasChunkAt(pos))throw new StudioException("error.blueprint_chunks");
            if(!level.mayInteract(p,pos)||!p.mayBuild())throw new StudioException("error.blueprint_protected");
            if(!level.getBlockState(pos).isAir())throw new StudioException("error.blueprint_collision",pos.getX(),pos.getY(),pos.getZ());
        }
    }
    public static BlockState state(MusicBlueprint.Part part,int r){
        Block block=BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(part.block()));BlockState state=block.defaultBlockState();
        if(part.block().equals("repeater")||part.block().equals("comparator")){
            // Minecraft's diode FACING points to its INPUT, opposite the signal travel direction.
            Direction flow=Direction.byName(part.facing());for(int i=0;i<(r&3);i++)flow=flow.getClockWise();state=state.setValue(BlockStateProperties.HORIZONTAL_FACING,flow.getOpposite());
        }
        if(part.block().equals("repeater"))state=state.setValue(RepeaterBlock.DELAY,part.value());
        if(part.block().equals("comparator"))state=state.setValue(ComparatorBlock.MODE,part.value()==1?ComparatorMode.SUBTRACT:ComparatorMode.COMPARE);
        if(part.block().equals("lever"))state=state.setValue(LeverBlock.FACE,AttachFace.FLOOR).setValue(LeverBlock.FACING,Direction.NORTH).setValue(LeverBlock.POWERED,false);
        if(part.block().equals("stone_button")){Direction facing=Direction.byName(part.facing());for(int i=0;i<(r&3);i++)facing=facing.getClockWise();state=state.setValue(ButtonBlock.FACE,AttachFace.FLOOR).setValue(ButtonBlock.FACING,facing).setValue(ButtonBlock.POWERED,false);}
        if(part.block().equals("note_block"))state=state.setValue(NoteBlock.NOTE,part.value()).setValue(NoteBlock.INSTRUMENT,NoteBlockInstrument.valueOf(part.instrument().toUpperCase(Locale.ROOT))).setValue(NoteBlock.POWERED,false);
        return state;
    }
    public static void tick(ServerLevel level){
        List<Job> jobs=JOBS.get(level);if(jobs==null)return;int budget=256;
        for(var it=jobs.iterator();it.hasNext()&&budget>0;){Job j=it.next();try{
            if(j.player.hasDisconnected()||j.player.serverLevel()!=level||!j.player.isCreative())throw new StudioException("error.blueprint_cancelled");
            while(j.written<j.positions.size()&&budget-->0){
                int i=j.written;BlockPos pos=j.positions.get(i);
                if(!level.hasChunkAt(pos)||!level.mayInteract(j.player,pos)||!level.getBlockState(pos).isAir())throw new StudioException("error.blueprint_changed");
                var part=j.b.parts().get(i);j.old.add(BlockSnapshot.create(level.dimension(),level,pos,2));
                if(!level.setBlock(pos,state(part,j.rotation),2))throw new StudioException("error.blueprint_changed");j.written++;
                if(part.block().equals("note_block"))ExtendedNoteData.get(level).put(pos,part.noteVoice());
                if(part.block().equals("command_block")){var cmd=(CommandBlockEntity)level.getBlockEntity(pos);cmd.getCommandBlock().setCommand(part.command());cmd.getCommandBlock().setTrackOutput(false);cmd.setAutomatic(false);cmd.setChanged();}
            }
            if(j.written==j.positions.size()){
                if(NeoForge.EVENT_BUS.post(new BlockEvent.EntityMultiPlaceEvent(List.copyOf(j.old),Blocks.AIR.defaultBlockState(),j.player)).isCanceled())throw new StudioException("error.blueprint_protected");
                Set<BlockPos> occupied=new HashSet<>(j.positions);for(int yy=0;yy<j.b.y();yy++)for(int zz=0;zz<j.b.depth(j.rotation);zz++)for(int xx=0;xx<j.b.width(j.rotation);xx++){BlockPos pos=j.anchor.offset(xx,yy,zz);if(!level.hasChunkAt(pos)||!level.mayInteract(j.player,pos)||(!occupied.contains(pos)&&!level.getBlockState(pos).isAir()))throw new StudioException("error.blueprint_changed");}
                // Recompute wire shapes after every support and note is present, then notify neighbours.
                for(int i=0;i<j.positions.size();i++){BlockPos pos=j.positions.get(i);if(!level.getBlockState(pos).is(state(j.b.parts().get(i),j.rotation).getBlock()))throw new StudioException("error.blueprint_changed");}
                for(BlockPos pos:j.positions){BlockState state=Block.updateFromNeighbourShapes(level.getBlockState(pos),level,pos);level.setBlock(pos,state,2);}
                for(BlockPos pos:j.positions)level.updateNeighborsAt(pos,level.getBlockState(pos).getBlock());
                BlockPos start=lever(j.anchor,j.b,j.rotation);j.player.displayClientMessage(Component.translatable("musicstudio.blueprint.placed",Component.translatable(j.b.loop()?"musicstudio.blueprint.lever":"musicstudio.blueprint.button"),start.getX(),start.getY(),start.getZ()),false);it.remove();
            }
        }catch(Exception e){rollback(level,j);StudioMessages.error(j.player,e);it.remove();}}
        if(jobs.isEmpty())JOBS.remove(level);
    }
    private static void rollback(ServerLevel level,Job j){for(int i=j.written-1;i>=0;i--){try{BlockPos pos=j.positions.get(i);Block expected=state(j.b.parts().get(i),j.rotation).getBlock();if(level.getBlockState(pos).is(expected))j.old.get(i).restore(2);else MusicStudio.LOGGER.warn("Blueprint rollback skipped externally changed block at {}",pos);}catch(Exception e){MusicStudio.LOGGER.error("Blueprint rollback failed at "+j.positions.get(i),e);}}}
    public static void unload(ServerLevel level){var jobs=JOBS.remove(level);if(jobs!=null)jobs.forEach(j->rollback(level,j));}
}
