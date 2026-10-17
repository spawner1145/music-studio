package dev.musicstudio.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.*;
import com.mojang.blaze3d.audio.Channel;
import org.lwjgl.openal.AL10;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Development smoke checks: inspect the real OpenAL source on its owning sound thread. */
final class StudioPitchSmoke {
    private static SoundEngine engine() throws Exception {Field field=SoundManager.class.getDeclaredField("soundEngine");field.setAccessible(true);return (SoundEngine)field.get(Minecraft.getInstance().getSoundManager());}
    static float calculated(SoundInstance sound) throws Exception {sound.resolve(Minecraft.getInstance().getSoundManager());Method method=SoundEngine.class.getDeclaredMethod("calculatePitch",SoundInstance.class);method.setAccessible(true);return (float)method.invoke(engine(),sound);}
    static boolean hasPlayingChannel(SoundInstance sound) throws Exception {
        Field field=SoundEngine.class.getDeclaredField("instanceToChannel");field.setAccessible(true);var handle=(ChannelAccess.ChannelHandle)((Map<?,?>)field.get(engine())).get(sound);
        return handle!=null&&!handle.isStopped();
    }
    static CompletableFuture<Float> actual(SoundInstance sound) throws Exception {
        Field field=SoundEngine.class.getDeclaredField("instanceToChannel");field.setAccessible(true);Map<?,?> channels=(Map<?,?>)field.get(engine());var handle=(ChannelAccess.ChannelHandle)channels.get(sound);
        if(handle==null)throw new IllegalStateException("Studio voice has no actual audio channel");var result=new CompletableFuture<Float>();
        handle.execute(channel->{try{Field source=Channel.class.getDeclaredField("source");source.setAccessible(true);float pitch=AL10.alGetSourcef(source.getInt(channel),AL10.AL_PITCH);int error=AL10.alGetError();if(error!=AL10.AL_NO_ERROR)throw new IllegalStateException("OpenAL pitch error: "+error);result.complete(pitch);}catch(Exception error){result.completeExceptionally(error);}});return result;
    }
}
