package dev.musicstudio.client;

import dev.musicstudio.MusicStudio;
import dev.musicstudio.blueprint.*;
import dev.musicstudio.song.*;
import dev.musicstudio.world.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Opt-in end-to-end test in a fresh, disposable development world. */
public final class NoteHookSmoke {
    private record Heard(HookedNoteSoundInstance sound,float actualPitch,int tick){}
    private static final List<Heard> heard=new CopyOnWriteArrayList<>();
    private static boolean created;private static int warmup;private static volatile int tick;
    private static SongProject song;private static MusicBlueprint blueprint;private static BlockPos anchor,root;
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException("NOTE_HOOK_SMOKE: "+message);}
    public static void tick(){var mc=Minecraft.getInstance();if(mc.getOverlay()!=null)return;
        if(!created&&mc.screen instanceof TitleScreen&&++warmup>40){created=true;
            NeoForge.EVENT_BUS.addListener((PlaySoundSourceEvent e)->{if(e.getSound() instanceof HookedNoteSoundInstance voice){try{var source=com.mojang.blaze3d.audio.Channel.class.getDeclaredField("source");source.setAccessible(true);float actual=org.lwjgl.openal.AL10.alGetSourcef(source.getInt(e.getChannel()),org.lwjgl.openal.AL10.AL_PITCH);heard.add(new Heard(voice,actual,tick));}catch(Exception error){throw new IllegalStateException(error);}}});
            song=SongProject.legacyDemo();song.title="Note block hook smoke";song.loop=false;song.noteBlockPitchHook=true;song.channels=new ArrayList<>(List.of(new SongProject.Channel("Harp","minecraft:block.note_block.harp",0x6989ab)));song.channels.getFirst().volume=.3f;
            var p=new SongProject.Pattern("Hook",1);p.notes.getFirst().add(new SongProject.Note(0,1,42,127));p.notes.getFirst().add(new SongProject.Note(0,2,42,64));p.notes.getFirst().add(new SongProject.Note(8,1,90,80));song.patterns=new ArrayList<>(List.of(p));song.clips=new ArrayList<>(List.of(new SongProject.Clip(0,0,0,1)));song.endPpq=4*SongProject.PPQ;song.maxX=40;song.maxZ=32;song.maxY=12;
            mc.createWorldOpenFlows().createFreshLevel("MusicStudioHookSmoke-"+System.currentTimeMillis(),new LevelSettings("Hook smoke",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(42L,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),new TitleScreen());
        }
        if(mc.level==null||mc.player==null||mc.getSingleplayerServer()==null)return;tick++;
        if(tick==30)mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());p.teleportTo(5,-46,18);p.getAbilities().flying=true;p.onUpdateAbilities();p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(64);p.serverLevel().setDayTime(6000);});
        if(tick==55){mc.player.setYRot(180);mc.player.setXRot(50);mc.player.getAbilities().flying=true;StudioText.testLanguage("zh_cn");var editor=EditorScreen.create();mc.setScreen(editor);editor.canvas.smokeExport(song);}
        if(tick==75){Screenshot.grab(mc.gameDirectory,"musicstudio-note-hook-export.png",mc.getMainRenderTarget(),c->{});((EditorScreen)mc.screen).canvas.smokeCreateBlueprint();}
        if(tick==115){int slot=-1;for(int i=0;i<9;i++)if(mc.player.getInventory().getItem(i).is(MusicBlocks.BLUEPRINT.get())){slot=i;break;}check(slot>=0,"Network export failed");mc.player.getInventory().selected=slot;blueprint=BlueprintItem.decode(mc.player.getMainHandItem());check(blueprint.parts().stream().filter(p->p.noteVoice()!=null).count()==3,"Hook controls lost in network item");check(blueprint.outputs().stream().noneMatch(MusicBlueprint.Output::command),"Unexpected command baking");check(BlueprintItem.data(mc.player.getMainHandItem()).getInt("version")==4,"Wrong item version");mc.setScreen(null);}
        if(tick==135){check(mc.hitResult instanceof BlockHitResult,"Missing anchor");var hit=(BlockHitResult)mc.hitResult;anchor=hit.getBlockPos().relative(hit.getDirection());root=BlueprintPlacement.lever(anchor,blueprint,0);mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);}
        if(tick==155)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()).teleportTo(anchor.getX()+12,anchor.getY()+6,anchor.getZ()+4));
        if(tick==170){check(mc.level.getBlockState(root).is(Blocks.STONE_BUTTON),"Missing button");mc.player.getInventory().selected=8;mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(root),Direction.UP,root,false));}
        if(tick==190){var low=heard.stream().filter(h->Math.abs(h.actualPitch-.25f)<.00001).toList();check(low.size()==2,"Missing overlapping low voices");try{int playing=0;for(var h:low)if(StudioPitchSmoke.hasPlayingChannel(h.sound))playing++;check(playing==1,"Gate did not stop exactly one OpenAL voice: "+playing);}catch(Exception e){throw new IllegalStateException(e);}check(low.stream().filter(h->h.sound.isStopped()).count()==1,"Gate did not preserve the natural voice");}
        if(tick==230){
            check(heard.size()==3,"Missing or doubled client sounds: "+heard.size());check(heard.stream().filter(h->Math.abs(h.actualPitch-.25f)<.00001).count()==2,"OpenAL clamped low pitch");check(heard.stream().anyMatch(h->Math.abs(h.actualPitch-4)<.00001),"OpenAL clamped high pitch");check(heard.stream().filter(h->h.sound.isStopped()).count()==1,"Gate stopped natural or other notes");
            check(heard.stream().anyMatch(h->Math.abs(h.sound.getVolume()-.9f*64/127)<.00001&&h.sound.isStopped()),"Velocity or gate did not reach client");
            var ordinary=new net.minecraft.client.resources.sounds.SimpleSoundInstance(net.minecraft.resources.ResourceLocation.parse("minecraft:block.note_block.harp"),net.minecraft.sounds.SoundSource.RECORDS,.1f,4,net.minecraft.client.resources.sounds.SoundInstance.createUnseededRandom(),false,0,net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE,0,0,0,true);
            try{check(StudioPitchSmoke.calculated(ordinary)==2,"Hook changed unrelated sounds");Files.writeString(mc.gameDirectory.toPath().resolve("musicstudio/note-hook-smoke-complete.txt"),"PASS: network UI export, actual native redstone placement, OpenAL .25/.25/4 pitch, velocity 64 and isolated gate; "+heard);}catch(Exception error){throw new IllegalStateException(error);}
            MusicStudio.LOGGER.info("NOTE_HOOK_SMOKE PASS: {} actual OpenAL voices, pitch .25/.25/4, velocity and isolated gate",heard.size());mc.stop();
        }
        if(tick>270)throw new IllegalStateException("Hook smoke timeout");
    }
}
