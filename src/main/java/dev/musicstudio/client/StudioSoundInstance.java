package dev.musicstudio.client;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

/** Marker for the workstation's own voices; other game sounds keep their normal pitch policy. */
public class StudioSoundInstance extends SimpleSoundInstance {
    public StudioSoundInstance(String sound,float volume,float pitch){
        super(ResourceLocation.parse(sound),SoundSource.RECORDS,volume,pitch,SoundInstance.createUnseededRandom(),false,0,SoundInstance.Attenuation.NONE,0,0,0,true);
    }
    protected StudioSoundInstance(dev.musicstudio.network.NoteVoiceNetwork.Play p){
        super(p.sound(),p.source(),p.volume(),p.pitch(),net.minecraft.util.RandomSource.create(p.seed()),false,0,SoundInstance.Attenuation.LINEAR,p.x(),p.y(),p.z(),false);
    }
}
