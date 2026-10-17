package dev.musicstudio.client;

import dev.musicstudio.media.*;
import dev.musicstudio.song.*;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;

/** Debounced cancellable background mix; all publication and resource resolution happen on the client. */
public final class WaveformPreview {
    private record Key(String project,long revision,long resources,int pattern,boolean patternOnly){}
    private Key requested;private Future<?> work;private volatile long generation;private long changedAt;private boolean launched,closed;
    private SongWaveform waveform;private SongWaveform.Columns overview;private String error="";
    public SongWaveform waveform(){return waveform;}
    public SongWaveform.Columns overview(){return overview;}
    public String error(){return error;}
    public boolean pending(){return requested!=null&&error.isEmpty()&&waveform==null;}
    public void invalidate(){generation++;if(work!=null)work.cancel(true);work=null;requested=null;waveform=null;overview=null;error="";launched=false;}
    public void update(SongProject song,long revision,int pattern,boolean patternOnly,boolean busy){
        var mc=Minecraft.getInstance();if(closed||mc.getOverlay()!=null)return;
        Key key=new Key(song.projectId,revision,SoundCatalog.INSTANCE.revision(),patternOnly?pattern:-1,patternOnly);
        if(!key.equals(requested)){invalidate();requested=key;changedAt=System.currentTimeMillis();}
        if(busy){if(launched&&waveform==null&&error.isEmpty()){invalidate();requested=key;changedAt=System.currentTimeMillis();}return;}
        if(launched||System.currentTimeMillis()-changedAt<400)return;launched=true;long token=generation;
        try{SongProject snapshot=ProjectStore.copy(song);Set<Integer> used=new HashSet<>();for(var e:snapshot.events(pattern,patternOnly))used.add(e.channel());for(int i=0;i<snapshot.channels.size();i++)if(!used.contains(i))snapshot.channels.get(i).muted=true;
            var provider=ClientMedia.capture(snapshot,pattern,patternOnly);work=ClientMedia.IO.submit(()->{try{
                var result=SongWaveform.from(AudioFiles.mix(snapshot,provider,pattern,patternOnly,()->closed||token!=generation),()->closed||token!=generation);var columns=result.columns(0,result.seconds(),129);
                mc.execute(()->{if(!closed&&token==generation){waveform=result;overview=columns;}});
            }catch(CancellationException|InterruptedException ex){/* A newer edit or closing the screen superseded this work. */}
            catch(Exception ex){mc.execute(()->{if(!closed&&token==generation)error=StudioText.error(ex);});}});
        }catch(Exception ex){error=StudioText.error(ex);}
    }
    public void close(){closed=true;invalidate();}
}
