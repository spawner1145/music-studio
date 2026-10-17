package dev.musicstudio.client;

import dev.musicstudio.MusicStudio;
import dev.musicstudio.song.SongProject;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import java.nio.file.*;

@Mod(value=MusicStudio.ID,dist=Dist.CLIENT)
public final class StudioClient {
    public static SongProject project=SongProject.demo();
    public static Path projectFile;
    private boolean smokeOpened;
    private int warmupTicks;
    public StudioClient(IEventBus bus) {
        BlueprintClient.init();
        HookedNoteSoundInstance.init();
        ClientMediaNetwork.init();
        bus.addListener((RegisterGuiLayersEvent e)->e.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MusicStudio.ID,"blueprint_preview"),BlueprintClient::hud));
        NeoForge.EVENT_BUS.addListener(BlueprintClient::render);
        bus.addListener(this::reloadListeners);
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::logout);
    }
    private void reloadListeners(RegisterClientReloadListenersEvent e) {
        e.registerReloadListener((ResourceManagerReloadListener)manager->{SoundCatalog.INSTANCE.invalidate();StudioText.invalidate();});
    }
    private void commands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("music").then(Commands.literal("editor").executes(ctx->{
            // Opening a screen while the chat command is still closing would immediately discard it.
            Minecraft.getInstance().tell(()->Minecraft.getInstance().setScreen(EditorScreen.create()));return 1;
        })).then(Commands.literal("sounds").executes(ctx->{
            Minecraft.getInstance().tell(()->{var screen=EditorScreen.create();Minecraft.getInstance().setScreen(screen);screen.canvas.openSounds(false);});return 1;
        })));
    }
    private void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        BlueprintClient.logout();
        ClientMediaNetwork.logout();
        if(Minecraft.getInstance().screen instanceof EditorScreen s)s.canvas.close();
        SoundCatalog.INSTANCE.invalidate();
    }
    private void tick(ClientTickEvent.Post e) {
        BlueprintClient.tick();
        ClientMediaNetwork.tick();
        if(Boolean.getBoolean("musicstudio.reorderSmoke"))ReorderSmoke.tick();
        if(Boolean.getBoolean("musicstudio.projectFolderSmoke"))ProjectFolderSmoke.tick();
        if(Boolean.getBoolean("musicstudio.noteHookSmoke"))NoteHookSmoke.tick();
        if(Boolean.getBoolean("musicstudio.blueprintSmoke"))BlueprintSmoke.tick();
        if(Boolean.getBoolean("musicstudio.mediaSmoke"))MediaSmoke.tick();
        if(Boolean.getBoolean("musicstudio.waveformSmoke"))WaveformSmoke.tick();
        Minecraft mc=Minecraft.getInstance();
        if(mc.getOverlay()==null)SoundCatalog.INSTANCE.refreshIfNeeded();
        if(!Boolean.getBoolean("musicstudio.smoke")||mc.getOverlay()!=null)return;
        if(!smokeOpened&&mc.screen!=null&&++warmupTicks>40) {
            MusicStudio.LOGGER.info("STUDIO_SMOKE: replacing startup screen {}",mc.screen.getClass().getSimpleName());
            smokeOpened=true;
            StudioText.testLanguage("zh_cn");
            var dispatcher=new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
            commands(new RegisterClientCommandsEvent(dispatcher,null));
            try { dispatcher.execute("music editor",(net.minecraft.commands.CommandSourceStack)null); }
            catch(Exception ex) {throw new IllegalStateException("Editor command smoke failed",ex);}
            MusicStudio.LOGGER.info("STUDIO_SMOKE: dispatched /music editor");
        }
        if(smokeOpened&&mc.screen instanceof EditorScreen s)ArrangementSmoke.tick(s);
    }
}
