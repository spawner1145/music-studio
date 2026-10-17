package dev.musicstudio.testing;

import com.mojang.authlib.GameProfile;
import dev.musicstudio.blueprint.*;
import dev.musicstudio.song.*;
import dev.musicstudio.world.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.event.level.NoteBlockEvent;
import java.util.*;
import java.util.function.Consumer;

@GameTestHolder("musicstudio") @PrefixGameTestTemplate(false)
public final class BlueprintGameTests {
    private static SongProject tune(boolean loop){var s=SongProject.legacyDemo();s.channels=new ArrayList<>(List.of(s.channels.get(2)));s.patterns.clear();var p=new SongProject.Pattern("Native test",1);p.length=SongProject.PPQ*4;for(int step:new int[]{0,1,4,8})p.notes.getFirst().add(new SongProject.Note(step,1,66+step,127));s.patterns.add(p);s.clips.clear();s.endPpq=SongProject.PPQ*4;s.clips.add(new SongProject.Clip(0,0,0,1));s.bpm=120;s.loop=loop;s.maxX=32;s.maxZ=24;s.maxY=12;return s;}
    private static ServerPlayer player(GameTestHelper h){return new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"StudioGameTest")){
        @Override public boolean isCreative(){return true;}
        @Override public boolean hasPermissions(int p){return true;}
        @Override public boolean hasDisconnected(){return false;}
    };}
    private static BlockPos pos(BlockPos a,MusicBlueprint b,MusicBlueprint.Cell c,int r){var t=b.rotate(c,r);return a.offset(t.x(),t.y(),t.z());}
    private static void check(boolean v,String message){if(!v)throw new GameTestAssertException(message);}
    private static void flip(GameTestHelper h,BlockPos lever){h.getLevel().getBlockState(lever).useWithoutItem(h.getLevel(),player(h),new BlockHitResult(Vec3.atCenterOf(lever),net.minecraft.core.Direction.UP,lever,false));}
    private record Played(BlockPos pos,long tick){}
    @GameTest(template="test_room",timeoutTicks=65,batch="hook")
    public static void hookHonorsEventCancellationInstrumentChangesAndOrdinaryNotes(GameTestHelper h){
        var level=h.getLevel();var changed=h.absolutePos(new BlockPos(3,4,3));var cancelled=changed.east(2);var ordinary=changed.east(4);var heard=new HashMap<BlockPos,Float>();
        for(var p:List.of(changed,cancelled,ordinary)){level.setBlock(p.below(),Blocks.DIRT.defaultBlockState(),3);level.setBlock(p,Blocks.NOTE_BLOCK.defaultBlockState(),3);}
        var voice=new NoteBlockVoice(-12,64,5,.6f);ExtendedNoteData.get(level).put(changed,voice);ExtendedNoteData.get(level).put(cancelled,voice);
        Consumer<NoteBlockEvent.Play> modifier=e->{if(e.getLevel()==level){if(e.getPos().equals(changed))e.setInstrument(net.minecraft.world.level.block.state.properties.NoteBlockInstrument.ZOMBIE);if(e.getPos().equals(cancelled))e.setCanceled(true);}};
        Consumer<net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition> sound=e->{var p=BlockPos.containing(e.getPosition());if(e.getLevel()==level&&List.of(changed,cancelled,ordinary).contains(p))heard.put(p,e.getNewPitch());};
        NeoForge.EVENT_BUS.addListener(modifier);NeoForge.EVENT_BUS.addListener(sound);
        h.runAtTickTime(5,()->{for(var p:List.of(changed,cancelled,ordinary))level.blockEvent(p,Blocks.NOTE_BLOCK,0,0);});
        h.runAtTickTime(15,()->{NeoForge.EVENT_BUS.unregister(modifier);NeoForge.EVENT_BUS.unregister(sound);check(heard.size()==2&&!heard.containsKey(cancelled),"Cancelled note still played");check(heard.get(changed)==1,"Non-tunable event instrument was transposed");check(heard.get(ordinary)==.5f,"Ordinary note pitch changed");h.succeed();});
    }
    @GameTest(template="test_room",timeoutTicks=140,batch="hook")
    public static void extendedNoteBlocksKeepControlsAndActualRedstonePlayback(GameTestHelper h){
        var s=tune(false);s.noteBlockPitchHook=true;s.channels.getFirst().volume=.6f;var ns=s.patterns.getFirst().notes.getFirst();ns.clear();
        ns.add(new SongProject.Note(0,1,42,127));ns.add(new SongProject.Note(2,2,90,64));ns.add(new SongProject.Note(6,1,66,80));
        var b=MusicBlueprint.compile(s);check(b.outputs().stream().noneMatch(MusicBlueprint.Output::command),"Hook notes created commands");
        var item=BlueprintItem.create(BlueprintData.pack(s),b);check(BlueprintItem.data(item).getInt("version")==4,"Missing hook blueprint version");check(BlueprintItem.decode(item).parts().equals(b.parts()),"Hook controls lost in item");
        var old=BlueprintItem.create(BlueprintData.pack(s),b);net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,old,t->t.putInt("version",3));
        boolean fallback=false;try{BlueprintItem.decode(old);}catch(StudioException e){fallback=e.key.equals("error.blueprint_prepare");}check(fallback,"Version 3 unexpectedly enabled hook");
        BlockPos a=h.absolutePos(new BlockPos(2,2,2));Map<BlockPos,float[]> heard=new HashMap<>();
        Consumer<net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition> listener=e->{if(e.getLevel()==h.getLevel()){BlockPos p=BlockPos.containing(e.getPosition());if(b.outputs().stream().anyMatch(o->pos(a,b,o.pos(),1).equals(p)))heard.put(p,new float[]{e.getNewPitch(),e.getNewVolume()});}};
        NeoForge.EVENT_BUS.addListener(listener);BlueprintPlacement.begin(h.getLevel(),player(h),a,b,1);
        h.runAtTickTime(20,()->{
            var data=ExtendedNoteData.get(h.getLevel());var restored=ExtendedNoteData.load(data.save(new net.minecraft.nbt.CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
            for(var part:b.parts())if(part.noteVoice()!=null){BlockPos p=pos(a,b,part.pos(),1);check(h.getLevel().getBlockState(p).is(Blocks.NOTE_BLOCK),"Hook is not a vanilla note block");check(part.noteVoice().equals(data.voice(p))&&part.noteVoice().equals(restored.voice(p)),"Controls did not persist or rotate");}
            flip(h,pos(a,b,b.lever(),1));
        });
        h.runAtTickTime(85,()->{
            NeoForge.EVENT_BUS.unregister(listener);check(heard.size()==3,"Redstone did not trigger all hook sounds: "+heard.size());
            for(var part:b.parts())if(part.noteVoice()!=null){BlockPos p=pos(a,b,part.pos(),1);var v=part.noteVoice();var actual=heard.get(p);check(Math.abs(actual[0]-NoteBlock.getPitchFromNote(v.note()))<.00001,"Extended sound pitch was clamped");check(Math.abs(actual[1]-3*v.volume()*v.velocity()/127f)<.00001,"Velocity or volume ignored");
                flip(h,p);var tuned=ExtendedNoteData.get(h.getLevel()).voice(p);check(tuned.note()==v.note()+1&&tuned.velocity()==v.velocity()&&tuned.durationTicks()==v.durationTicks(),"Manual tuning lost extended controls");
                h.getLevel().destroyBlock(p,false);check(ExtendedNoteData.get(h.getLevel()).voice(p)==null,"Broken note block leaked controls");h.getLevel().setBlock(p,Blocks.NOTE_BLOCK.defaultBlockState(),3);check(ExtendedNoteData.get(h.getLevel()).voice(p)==null,"Replacement inherited controls");}
            h.succeed();
        });
    }
    private static Consumer<NoteBlockEvent.Play> listen(GameTestHelper h,BlockPos a,MusicBlueprint b,int r,List<Played> list){Set<BlockPos> positions=new HashSet<>();b.outputs().forEach(o->positions.add(pos(a,b,o.pos(),r)));Consumer<NoteBlockEvent.Play> c=e->{if(e.getLevel()==h.getLevel()&&positions.contains(e.getPos()))list.add(new Played(e.getPos(),h.getLevel().getGameTime()));};NeoForge.EVENT_BUS.addListener(c);return c;}
    @GameTest(template="test_room",timeoutTicks=180)
    public static void nativeRepeatersAndNoteTiming(GameTestHelper h){
        var b=MusicBlueprint.compile(tune(false));BlockPos a=h.absolutePos(new BlockPos(2,2,2));List<Played> notes=new ArrayList<>();var listener=listen(h,a,b,0,notes);BlueprintPlacement.begin(h.getLevel(),player(h),a,b,0);
        h.runAtTickTime(12,()->{check(!BlueprintPlacement.busy(h.getLevel()),"Not built");var state=h.getLevel().getBlockState(pos(a,b,b.lever(),0));check(state.is(Blocks.STONE_BUTTON)&&!state.getValue(ButtonBlock.POWERED)&&notes.isEmpty(),"Single playback must start with an idle stone button");flip(h,pos(a,b,b.lever(),0));});
        h.runAtTickTime(65,()->{check(notes.size()==b.outputs().size(),"Expected "+b.outputs().size()+" native notes, got "+notes);long first=notes.getFirst().tick;for(var o:b.outputs()){BlockPos p=pos(a,b,o.pos(),0);var heard=notes.stream().filter(n->n.pos.equals(p)).toList();check(heard.size()==1&&heard.getFirst().tick-first==o.tick(),"Native timing mismatch at "+p+": "+heard+" expected "+o.tick());}check(!h.getLevel().getBlockState(pos(a,b,b.lever(),0)).getValue(ButtonBlock.POWERED),"Button did not release automatically");});
        h.runAtTickTime(80,()->{check(notes.size()==b.outputs().size(),"Single playback repeated by itself");flip(h,pos(a,b,b.lever(),0));});
        h.runAtTickTime(140,()->{NeoForge.EVENT_BUS.unregister(listener);check(notes.size()==2*b.outputs().size(),"Second button press did not replay all notes");for(var o:b.outputs()){var heard=notes.stream().filter(n->n.pos.equals(pos(a,b,o.pos(),0))).toList();check(heard.size()==2&&heard.get(1).tick-heard.getFirst().tick==68,"Button replay changed note timing");}check(!h.getLevel().getBlockState(pos(a,b,b.lever(),0)).getValue(ButtonBlock.POWERED),"Replayed button did not reset");h.succeed();});
    }
    @GameTest(template="test_room",timeoutTicks=4300)
    public static void nativeHundredLoopsAndLeverStop(GameTestHelper h){
        var b=MusicBlueprint.compile(tune(true));BlockPos a=h.absolutePos(new BlockPos(2,2,2));List<Played> notes=new ArrayList<>();var listener=listen(h,a,b,0,notes);BlueprintPlacement.begin(h.getLevel(),player(h),a,b,0);
        h.runAtTickTime(12,()->flip(h,pos(a,b,b.lever(),0)));
        h.runAtTickTime(4030,()->{BlockPos first=pos(a,b,b.outputs().getFirst().pos(),0);var onsets=notes.stream().filter(n->n.pos.equals(first)).toList();check(onsets.size()>=100,"Clock failed: "+onsets.size()+" cycles");for(int i=1;i<100;i++)check(onsets.get(i).tick-onsets.get(i-1).tick==b.period(),"Native clock drift: "+onsets.subList(0,Math.min(6,onsets.size())));flip(h,pos(a,b,b.lever(),0));});
        int[] afterDrain={0};h.runAtTickTime(4100,()->afterDrain[0]=notes.size());h.runAtTickTime(4180,()->{NeoForge.EVENT_BUS.unregister(listener);check(notes.size()==afterDrain[0],"Notes repeated after lever off and drain");h.succeed();});
    }
    @GameTest(template="test_room",timeoutTicks=180,batch="rotations")
    public static void fourRotationsAreNativeAndPlayable(GameTestHelper h){
        var s=tune(false);s.maxX=s.maxZ=32;var b=MusicBlueprint.compile(s);List<Played> notes=new ArrayList<>();List<Consumer<NoteBlockEvent.Play>> listeners=new ArrayList<>();
        for(int r=0;r<4;r++){BlockPos a=h.absolutePos(new BlockPos(2+(r%2)*35,2,2+(r/2)*35));listeners.add(listen(h,a,b,r,notes));BlueprintPlacement.begin(h.getLevel(),player(h),a,b,r);}
        h.runAtTickTime(25,()->{for(int r=0;r<4;r++){BlockPos a=h.absolutePos(new BlockPos(2+(r%2)*35,2,2+(r/2)*35));for(var part:b.parts())check(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(h.getLevel().getBlockState(pos(a,b,part.pos(),r)).getBlock()).getNamespace().equals("minecraft"),"Custom playback block generated");flip(h,pos(a,b,b.lever(),r));}});
        h.runAtTickTime(95,()->{listeners.forEach(NeoForge.EVENT_BUS::unregister);check(notes.size()==b.outputs().size()*4,"Rotations not all playable: "+notes.size());h.succeed();});
    }
    @GameTest(template="test_room",timeoutTicks=180)
    public static void serpentineLayersAndChordIsolation(GameTestHelper h){
        var s=tune(false);s.maxX=20;s.maxZ=14;s.maxY=12;var list=s.patterns.getFirst().notes.getFirst();list.clear();for(int step=0;step<14;step++)for(int chord=0;chord<4;chord++)list.add(new SongProject.Note(step,1,60+chord,127));var b=MusicBlueprint.compile(s);check(b.y()>4,"Expected multilevel circuit");BlockPos a=h.absolutePos(new BlockPos(2,2,2));List<Played> notes=new ArrayList<>();var listener=listen(h,a,b,0,notes);BlueprintPlacement.begin(h.getLevel(),player(h),a,b,0);
        h.runAtTickTime(20,()->flip(h,pos(a,b,b.lever(),0)));
        h.runAtTickTime(95,()->{NeoForge.EVENT_BUS.unregister(listener);check(notes.size()==b.outputs().size(),"Chord/wiring ghost or missing notes: "+notes.size()+" vs "+b.outputs().size());long first=notes.stream().mapToLong(Played::tick).min().orElseThrow();for(var o:b.outputs()){var p=pos(a,b,o.pos(),0);var heard=notes.stream().filter(n->n.pos.equals(p)).toList();check(heard.size()==1&&heard.getFirst().tick-first==o.tick(),"Fold/chord timing mismatch "+o+" -> "+heard);}h.succeed();});
    }
    @GameTest(template="test_room",timeoutTicks=150)
    public static void collisionAndProtectionRollback(GameTestHelper h){
        var b=MusicBlueprint.compile(tune(false));BlockPos a=h.absolutePos(new BlockPos(2,2,2));var p=player(h);h.getLevel().setBlock(a,Blocks.STONE.defaultBlockState(),2);boolean rejected=false;try{BlueprintPlacement.begin(h.getLevel(),p,a,b,0);}catch(StudioException e){rejected=true;}check(rejected&&h.getLevel().getBlockState(a).is(Blocks.STONE),"Collision overwritten");h.getLevel().setBlock(a,Blocks.AIR.defaultBlockState(),2);
        Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityMultiPlaceEvent> protection=e->{if(e.getEntity()==p)e.setCanceled(true);};NeoForge.EVENT_BUS.addListener(protection);BlueprintPlacement.begin(h.getLevel(),p,a,b,0);
        h.runAtTickTime(20,()->{NeoForge.EVENT_BUS.unregister(protection);for(var part:b.parts())check(h.getLevel().getBlockState(pos(a,b,part.pos(),0)).isAir(),"Rollback left block");h.succeed();});
    }
    @GameTest(template="test_room",timeoutTicks=150)
    public static void realCommandBlockUsesVanillaPlaySound(GameTestHelper h){
        var s=tune(false);s.commandSamples=true;s.channels.getFirst().sound="minecraft:entity.experience_orb.pickup";var b=MusicBlueprint.compile(s);BlockPos a=h.absolutePos(new BlockPos(2,2,2));BlueprintPlacement.begin(h.getLevel(),player(h),a,b,0);
        h.runAtTickTime(20,()->flip(h,pos(a,b,b.lever(),0)));
        h.runAtTickTime(85,()->{for(var o:b.outputs()){var cmd=(CommandBlockEntity)h.getLevel().getBlockEntity(pos(a,b,o.pos(),0));check(cmd.getCommandBlock().getCommand().startsWith("playsound minecraft:"),"Hidden custom playback command");check(!cmd.isPowered(),"Command block did not reset after button released");check(cmd.saveWithFullMetadata(h.getLevel().registryAccess()).getLong("LastExecution")>0,"Command block did not execute");}h.succeed();});
    }
    @GameTest(template="test_room",timeoutTicks=140)
    public static void allTwentyTwoNativeInstrumentsAndSixteenNoteChord(GameTestHelper h){
        var s=tune(false);s.channels.clear();var pattern=new SongProject.Pattern("All instruments",22);int index=0;
        for(var instrument:net.minecraft.world.level.block.state.properties.NoteBlockInstrument.values())if(!instrument.hasCustomSound()){
            var channel=new SongProject.Channel(instrument.getSerializedName(),instrument.getSoundEvent().value().getLocation().toString(),0);channel.tunable=instrument.isTunable();s.channels.add(channel);pattern.notes.get(index).add(new SongProject.Note(index<16?0:8,1,66,127));index++;
        }
        s.patterns=new ArrayList<>(List.of(pattern));s.clips.clear();for(int i=0;i<22;i++)s.clips.add(new SongProject.Clip(i,0,0,1));var b=MusicBlueprint.compile(s);BlockPos a=h.absolutePos(new BlockPos(2,2,2));List<Played> notes=new ArrayList<>();var listener=listen(h,a,b,0,notes);BlueprintPlacement.begin(h.getLevel(),player(h),a,b,0);
        h.runAtTickTime(25,()->{for(var o:b.outputs())check(h.getLevel().getBlockState(pos(a,b,o.pos(),0)).getValue(NoteBlock.INSTRUMENT).getSerializedName().equals(o.instrument()),"Wrong base/head instrument "+o);flip(h,pos(a,b,b.lever(),0));});
        h.runAtTickTime(90,()->{NeoForge.EVENT_BUS.unregister(listener);check(notes.size()==22,"Missing or ghost notes in native instruments: "+notes.size());h.succeed();});
    }
    @GameTest(template="test_room",timeoutTicks=140)
    public static void breakingAnActualRepeaterCutsDownstreamMusic(GameTestHelper h){
        var b=MusicBlueprint.compile(tune(false));BlockPos a=h.absolutePos(new BlockPos(2,2,2));List<Played> notes=new ArrayList<>();var listener=listen(h,a,b,0,notes);
        var cut=b.parts().stream().filter(p->p.block().equals("repeater")&&p.pos().x()>9).findFirst().orElseThrow();boolean[] broken={false};
        Consumer<NoteBlockEvent.Play> breaker=e->{if(e.getLevel()==h.getLevel()&&e.getPos().equals(pos(a,b,b.outputs().getFirst().pos(),0))&&!broken[0]){broken[0]=true;h.getLevel().destroyBlock(pos(a,b,cut.pos(),0),false);}};NeoForge.EVENT_BUS.addListener(breaker);BlueprintPlacement.begin(h.getLevel(),player(h),a,b,0);
        h.runAtTickTime(15,()->flip(h,pos(a,b,b.lever(),0)));
        h.runAtTickTime(85,()->{NeoForge.EVENT_BUS.unregister(listener);NeoForge.EVENT_BUS.unregister(breaker);check(broken[0]&&notes.size()==1,"Broken physical circuit kept playing: "+notes);h.succeed();});
    }
    @GameTest(template="test_room",timeoutTicks=1350)
    public static void fastestNativeClockDoesNotMergePulses(GameTestHelper h){
        var s=tune(true);s.bpm=400;var b=MusicBlueprint.compile(s);BlockPos a=h.absolutePos(new BlockPos(2,2,2));List<Played> notes=new ArrayList<>();var listener=listen(h,a,b,0,notes);BlueprintPlacement.begin(h.getLevel(),player(h),a,b,0);
        h.runAtTickTime(15,()->flip(h,pos(a,b,b.lever(),0)));
        h.runAtTickTime(1250,()->{NeoForge.EVENT_BUS.unregister(listener);for(var o:b.outputs()){var heard=notes.stream().filter(n->n.pos.equals(pos(a,b,o.pos(),0))).toList();check(heard.size()>=100,"Fast repeater pulses merged: "+o+" cycles="+heard.size());for(int i=1;i<100;i++)check(heard.get(i).tick-heard.get(i-1).tick==b.period(),"Fast clock drift");}flip(h,pos(a,b,b.lever(),0));h.succeed();});
    }
    @GameTest(template="test_room",timeoutTicks=80,batch="media")
    public static void exportedDiscFitsNativeJukeboxPersistsAndStops(GameTestHelper h) throws Exception {
        String hash="d".repeat(64);PathAudio.install(h,hash);BlockPos pos=h.absolutePos(new BlockPos(3,3,3));h.getLevel().setBlock(pos,Blocks.JUKEBOX.defaultBlockState(),3);
        var item=StudioDisc.create(hash,"GameTest record",12);var saved=item.save(h.getLevel().registryAccess());var restored=net.minecraft.world.item.ItemStack.parse(h.getLevel().registryAccess(),saved).orElseThrow();check(StudioDisc.hash(restored).equals(hash),"Disc did not survive save/load");
        var box=(net.minecraft.world.level.block.entity.JukeboxBlockEntity)h.getLevel().getBlockEntity(pos);box.setTheItem(restored);check(box.getSongPlayer().isPlaying(),"Native jukebox did not accept exported disc");
        h.runAtTickTime(30,()->{check(!box.getSongPlayer().isPlaying(),"Dynamic record length did not stop native jukebox");check(!box.getTheItem().isEmpty(),"Playback consumed the disc");h.succeed();});
    }
    private static final class PathAudio {static void install(GameTestHelper h,String hash)throws Exception{var path=dev.musicstudio.network.MediaNetwork.file(h.getLevel().getServer(),hash);java.nio.file.Files.createDirectories(path.getParent());java.nio.file.Files.write(path,new byte[]{'O','g','g','S'});}}
    @GameTest(template="test_room",timeoutTicks=150,batch="hybrid")
    public static void editedNotesMixNativeAndRealCommandsAndOldBlueprintsStillPlace(GameTestHelper h) throws Exception {
        var s=tune(false);var notes=s.patterns.getFirst().notes.getFirst();notes.get(1).velocity=64;notes.get(2).duration=2*SongProject.STEP;String hash="c".repeat(64);PathAudio.install(h,hash);
        var e=s.events(0,false).get(2);s.blueprintAudio.put(dev.musicstudio.media.BlueprintAudio.key(s,s.channels.getFirst(),e),hash);var b=MusicBlueprint.compile(s);check(b.outputs().stream().filter(MusicBlueprint.Output::command).count()==2,"Mixed notes were converted per track instead of per note");check(!s.commandSamples,"Automatic conversion required arbitrary-sample opt-in");
        var item=BlueprintItem.create(dev.musicstudio.blueprint.BlueprintData.pack(s),b);check(BlueprintItem.decode(item).parts().equals(b.parts()),"Hybrid blueprint snapshot lost prepared audio");
        var old=dev.musicstudio.song.ProjectStore.copy(s);old.blueprintAudio.clear();var legacy=MusicBlueprint.legacySnapshot(old);var oldItem=BlueprintItem.create(dev.musicstudio.blueprint.BlueprintData.pack(old),legacy);net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,oldItem,t->t.putInt("version",2));check(BlueprintItem.decode(oldItem).outputs().stream().noneMatch(MusicBlueprint.Output::command),"Old vanilla blueprint was reinterpreted");
        BlockPos a=h.absolutePos(new BlockPos(2,2,2));List<Played> played=new ArrayList<>();var listener=listen(h,a,b,0,played);BlueprintPlacement.begin(h.getLevel(),player(h),a,b,0);
        h.runAtTickTime(15,()->flip(h,pos(a,b,b.lever(),0)));
        h.runAtTickTime(90,()->{NeoForge.EVENT_BUS.unregister(listener);check(played.size()==2,"Expected exactly two native note-block notes, got "+played.size());for(var output:b.outputs())if(output.command()){var block=(CommandBlockEntity)h.getLevel().getBlockEntity(pos(a,b,output.pos(),0));check(block.saveWithFullMetadata(h.getLevel().registryAccess()).getLong("LastExecution")>0,"Edited note command never ran");}h.succeed();});
    }
}
