package dev.musicstudio.client;

import dev.musicstudio.MusicStudio;
import dev.musicstudio.media.AudioFiles;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import java.nio.file.*;

/** Explicit development opt-in; generated music and all output stay under run/musicstudio. */
public final class ReorderSmoke {
    private static EditorScreen editor;private static Path root;private static int warmup,stage,age,ticks;
    public static void tick(){var mc=Minecraft.getInstance();if(mc.getOverlay()!=null)return;
        if(editor==null&&mc.screen instanceof TitleScreen&&++warmup>40){try{root=mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve("musicstudio/reorder-smoke-"+System.currentTimeMillis());Files.createDirectories(root);float[] pcm=new float[22050];for(int i=0;i<pcm.length;i++)pcm[i]=(float)(.2*Math.sin(2*Math.PI*330*i/44100));AudioFiles.wav(root.resolve("source.wav"),new AudioFiles.Wave(pcm,pcm.clone()),1);StudioText.testLanguage("zh_cn");editor=EditorScreen.create();mc.setScreen(editor);}catch(Exception e){throw new IllegalStateException(e);}}
        if(editor==null||mc.screen!=editor)return;if(++ticks>1800)throw new IllegalStateException("Reorder smoke timeout at "+stage);if(++age<8)return;
        try{if(editor.canvas.smokeReorderStep(stage,root)){if(stage==9||stage==13||stage==18||stage==26)Screenshot.grab(mc.gameDirectory,"musicstudio-reorder-"+stage+".png",mc.getMainRenderTarget(),c->{});MusicStudio.LOGGER.info("REORDER_SMOKE PASS stage {}",stage);stage++;age=0;if(stage==32){MusicStudio.LOGGER.info("REORDER_SMOKE PASS: all 32 stages, including Pattern/Audio double-click and audio deletion; artifacts {}",root);mc.stop();}}}catch(Exception e){throw new IllegalStateException("Reorder smoke stage "+stage,e);}
    }
}
