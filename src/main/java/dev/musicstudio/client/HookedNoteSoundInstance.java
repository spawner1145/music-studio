package dev.musicstudio.client;

import dev.musicstudio.network.NoteVoiceNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.sound.*;

/** Each note owns its gate; overlapping voices never stop one another. */
public final class HookedNoteSoundInstance extends StudioSoundInstance implements TickableSoundInstance {
    private final int duration;
    private int ticks;
    private volatile boolean started;
    public HookedNoteSoundInstance(NoteVoiceNetwork.Play p){super(p);duration=p.durationTicks();}
    public static void init(){
        NoteVoiceNetwork.playback=p->Minecraft.getInstance().getSoundManager().play(new HookedNoteSoundInstance(p));
        NeoForge.EVENT_BUS.addListener((PlaySoundSourceEvent e)->started(e));
        NeoForge.EVENT_BUS.addListener((PlayStreamingSourceEvent e)->started(e));
    }
    private static void started(SoundEvent.SoundSourceEvent e){if(e.getSound() instanceof HookedNoteSoundInstance note)note.started=true;}
    @Override public void tick(){if(started&&duration>0)ticks++;}
    @Override public boolean isStopped(){return duration>0&&ticks>=duration;}
}
