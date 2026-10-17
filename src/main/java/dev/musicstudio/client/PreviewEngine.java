package dev.musicstudio.client;

import dev.musicstudio.song.SongProject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import java.util.*;

/** Owns only Studio sound instances; stop never silences unrelated game sounds. */
public final class PreviewEngine {
    private record Voice(SoundInstance sound,long offNanos) {}
    private final List<Voice> voices=new ArrayList<>();
    private List<SongProject.Event> events=List.of();
    private List<SongProject.Event> loopEvents=List.of();
    private SongProject song;
    private long origin;
    private int next,period;
    private boolean playing;
    private double position;
    public String warning="";
    public boolean playing() { return playing; }
    SoundInstance lastVoice(){return voices.isEmpty()?null:voices.getLast().sound;}
    public double position() { if(!playing)return position;double current=(System.nanoTime()-origin)*SongProject.PPQ*song.bpm/60_000_000_000.0;return song.loop?current%period:Math.min(period,current); }
    public boolean trigger(String id,int pitch,int base,boolean tunable,int velocity,float volume,int durationPpq,int bpm) {
        var entry=SoundCatalog.INSTANCE.find(id);
        if(entry.isEmpty()||!entry.get().playable()){warning=StudioText.tr("audio.unavailable",id);return false;}
        float ratio=(float)dev.musicstudio.media.MusicalPitch.ratio(pitch,base,tunable);
        var instance=new StudioSoundInstance(id,Math.max(0,Math.min(1,volume*velocity/127f)),ratio);
        if(voices.size()>=96) { Voice old=voices.removeFirst();Minecraft.getInstance().getSoundManager().stop(old.sound);warning=StudioText.tr("audio.voice_limit"); }
        Minecraft.getInstance().getSoundManager().play(instance);
        boolean natural=dev.musicstudio.blueprint.MusicBlueprint.noteBlockSound(id)&&durationPpq==SongProject.VANILLA_NOTE_DURATION;
        long duration=(long)(durationPpq*60_000_000_000.0/(SongProject.PPQ*bpm));
        voices.add(new Voice(instance,natural?Long.MAX_VALUE:System.nanoTime()+Math.max(50_000_000L,duration)));
        return true;
    }
    public void start(SongProject song,int pattern,boolean patternMode,double start) {
        stop();this.song=SongProjectCopy.snapshot(song);loopEvents=this.song.events(pattern,patternMode);events=eventsFrom(this.song,pattern,patternMode,start);
        period=patternMode?song.patterns.get(pattern).length:song.endPpq;
        position=Math.max(0,Math.min(period-1,(int)start));origin=System.nanoTime()-(long)(position*60_000_000_000.0/(SongProject.PPQ*song.bpm));
        next=0;while(next<events.size()&&events.get(next).start()<position)next++;playing=true;
    }
    public void tick() {
        long now=System.nanoTime();
        voices.removeIf(v->{if(now>=v.offNanos){Minecraft.getInstance().getSoundManager().stop(v.sound);return true;}return false;});
        if(!playing)return;
        double unit=60_000_000_000.0/(SongProject.PPQ*song.bpm);
        position=(now-origin)/unit;
        if(position>=period) {
            stopVoices();
            if(!song.loop){playing=false;position=0;return;}
            long periods=Math.max(1,(long)(position/period));origin+=(long)(periods*period*unit);events=loopEvents;next=0;position=(now-origin)/unit;
        }
        int budget=256;
        while(next<events.size()&&events.get(next).start()<=position&&budget-->0) {
            var e=events.get(next++);var c=song.voice(e.channel());
            // Drop stale events after a stall instead of bursting a whole song at once.
            String baked=e.offset()>0?song.blueprintAudio.get(dev.musicstudio.media.BlueprintAudio.key(song,c,e)):null;
            if(position-e.start()<SongProject.PPQ/2.0)trigger(baked==null?c.sound:"musicstudio:sample/"+baked,baked==null?e.pitch():66,baked==null?c.baseMidi:66,baked==null&&c.tunable,e.velocity(),c.volume,e.duration(),song.bpm);
        }
    }
    private void stopVoices(){for(Voice v:voices)Minecraft.getInstance().getSoundManager().stop(v.sound);voices.clear();}
    public void stop(){playing=false;stopVoices();}
    static List<SongProject.Event> eventsFrom(SongProject song,int pattern,boolean patternMode,double start){
        int period=patternMode?song.patterns.get(pattern).length:song.endPpq,position=Math.max(0,Math.min(period-1,(int)start));List<SongProject.Event> result=new ArrayList<>();for(var e:song.events(pattern,patternMode)){
            if(e.start()>=position)result.add(e);else if(e.start()+e.duration()>position)result.add(new SongProject.Event(e.channel(),position,e.start()+e.duration()-position,e.pitch(),e.velocity(),e.track(),e.offset()+position-e.start()));
        }result.sort(Comparator.comparingInt(SongProject.Event::start));return result;
    }
    private static final class SongProjectCopy { static SongProject snapshot(SongProject p){return dev.musicstudio.song.ProjectStore.copy(p);} }
}
