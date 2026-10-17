package dev.musicstudio.mixin;

import dev.musicstudio.client.StudioSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class StudioPitchMixin {
    @Inject(method="calculatePitch",at=@At("HEAD"),cancellable=true)
    private void musicstudio$fullPitch(SoundInstance sound,CallbackInfoReturnable<Float> callback){
        if(sound instanceof StudioSoundInstance){float pitch=sound.getPitch();if(Float.isFinite(pitch)&&pitch>0)callback.setReturnValue(pitch);}
    }
}
