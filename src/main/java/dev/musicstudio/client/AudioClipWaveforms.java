package dev.musicstudio.client;

import dev.musicstudio.song.*;
import dev.musicstudio.media.*;
import java.util.*;
import java.util.concurrent.*;

/** Small cached PCM peak envelopes, independent of the Pattern editor. */
final class AudioClipWaveforms {
    private final Map<String,CompletableFuture<float[]>> cache=new LinkedHashMap<>();
    float[] get(SongProject song,SongProject.Channel source){
        String key=song.projectId+":"+source.sampleId+":"+source.trimStart+":"+source.trimEnd+":"+source.fadeIn+":"+source.fadeOut+":"+source.gain;
        var future=cache.get(key);if(future==null){
            if(cache.size()>=64)cache.remove(cache.keySet().iterator().next());
            var snapshot=ProjectStore.GSON.fromJson(ProjectStore.GSON.toJson(source),SongProject.Channel.class);
            future=CompletableFuture.supplyAsync(()->{try{var wave=AudioFiles.effects(AudioFiles.read(ProjectMedia.wav(ClientMedia.projects(),song,snapshot.sampleId)),snapshot);float[] peaks=new float[2048];for(int i=0;i<wave.frames();i++){int bin=(int)((long)i*peaks.length/wave.frames());peaks[bin]=Math.max(peaks[bin],Math.max(Math.abs(wave.left()[i]),Math.abs(wave.right()[i])));}return peaks;}catch(Exception ex){throw new CompletionException(ex);}},ClientMedia.IO);cache.put(key,future);
        }return future.isDone()&&!future.isCompletedExceptionally()?future.getNow(null):null;
    }
}
