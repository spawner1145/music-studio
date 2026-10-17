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
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import java.nio.file.*;

/** Explicit development opt-in, always creates a new world under the independent run/ folder. */
public final class BlueprintSmoke {
    private static int nativeVoices;
    private static boolean created;private static int warmup,tick;private static SongProject song;private static BlockPos anchor,root;
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException("BLUEPRINT_SMOKE: "+message);}
    public static void tick(){Minecraft mc=Minecraft.getInstance();if(mc.getOverlay()!=null)return;
        if(!created&&mc.screen instanceof TitleScreen&&++warmup>40){created=true;net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.NoteBlockEvent.Play e)->{if(e.getLevel().isClientSide())nativeVoices++;});
            song=SongProject.legacyDemo();song.title="Blueprint smoke";song.loop=false;song.endPpq=4*SongProject.PPQ;song.clips.clear();for(int c=0;c<song.channels.size();c++)song.clips.add(new SongProject.Clip(c,0,0,1));song.maxX=40;song.maxZ=32;song.maxY=12;song.channels.get(6).muted=true;song.channels.get(7).muted=true;for(var pattern:song.patterns)for(var notes:pattern.notes)for(var note:notes){note.duration=SongProject.STEP;note.velocity=127;}
            mc.createWorldOpenFlows().createFreshLevel("MusicStudioBlueprintSmoke-"+System.currentTimeMillis(),new LevelSettings("Blueprint smoke",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(42L,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),new TitleScreen());
        }
        if(mc.level==null||mc.player==null||mc.getSingleplayerServer()==null)return;tick++;
        if(tick==30){mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());p.teleportTo(5,-46,18);p.setYRot(180);p.setXRot(50);p.getAbilities().flying=true;p.onUpdateAbilities();p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(64);p.serverLevel().setDayTime(6000);});}
        if(tick==55){mc.player.setYRot(180);mc.player.setXRot(50);mc.player.getAbilities().flying=true;StudioText.testLanguage("zh_cn");var s=EditorScreen.create();mc.setScreen(s);s.canvas.smokeExport(song);}
        if(tick==75){Screenshot.grab(mc.gameDirectory,"musicstudio-blueprint-export.png",mc.getMainRenderTarget(),c->{});check(mc.screen instanceof EditorScreen,"Editor missing");((EditorScreen)mc.screen).canvas.smokeCreateBlueprint();}
        if(tick==110){int slot=-1;for(int i=0;i<9;i++)if(mc.player.getInventory().getItem(i).is(MusicBlocks.BLUEPRINT.get())){slot=i;break;}check(slot>=0,"Server did not return blueprint item");mc.player.getInventory().selected=slot;mc.setScreen(null);mc.player.setYRot(180);mc.player.setXRot(50);}
        if(tick==135){Screenshot.grab(mc.gameDirectory,"musicstudio-blueprint-preview.png",mc.getMainRenderTarget(),c->{});check(mc.hitResult instanceof BlockHitResult,"No anchor");var hit=(BlockHitResult)mc.hitResult;anchor=hit.getBlockPos().relative(hit.getDirection());var b=BlueprintItem.decode(mc.player.getMainHandItem());root=BlueprintPlacement.lever(anchor,b,0);mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);}
        if(tick==170){check(mc.level.getBlockState(root).is(net.minecraft.world.level.block.Blocks.STONE_BUTTON),"Native start button missing");mc.player.getInventory().selected=8;mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(root),Direction.UP,root,false));}
        if(tick==180){var b=MusicBlueprint.compile(song);mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());p.teleportTo(anchor.getX()+b.x()/2.0,anchor.getY()+26,anchor.getZ()+b.z()+24);});mc.player.setYRot(180);mc.player.setXRot(47);}
        if(tick==195)Screenshot.grab(mc.gameDirectory,"musicstudio-blueprint-building.png",mc.getMainRenderTarget(),c->{});
        if(tick==220){check(nativeVoices>0,"No native note-block playback on client");check(!mc.level.getBlockState(root).getValue(net.minecraft.world.level.block.ButtonBlock.POWERED),"Start button did not release");MusicStudio.LOGGER.info("BLUEPRINT_SMOKE PASS: actual network export, portable item, preview, native use-on placement, native button/repeaters/note blocks, automatic button release and {} client notes",nativeVoices);
            try{Files.writeString(mc.gameDirectory.toPath().resolve("musicstudio/blueprint-smoke-complete.txt"),"Export/placement/preview/playback verified; client voices="+nativeVoices);}catch(Exception e){throw new IllegalStateException(e);}mc.stop();}
        if(tick>260)throw new IllegalStateException("Blueprint smoke timed out");
    }
}
