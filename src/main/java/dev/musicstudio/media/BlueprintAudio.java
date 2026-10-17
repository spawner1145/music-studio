package dev.musicstudio.media;

import dev.musicstudio.blueprint.MusicBlueprint;
import dev.musicstudio.song.SongProject;
import java.nio.charset.StandardCharsets;

/** Per-note building decisions; never rewrites the editor's sound or the MIDI/audio score. */
public final class BlueprintAudio {
    private BlueprintAudio(){}
    public static boolean noteBlock(SongProject.Channel c){return c.sampleId.isEmpty()&&MusicBlueprint.noteBlockSound(c.sound);}
    public static SongProject.Note drawnNote(SongProject.Channel c,int step,int pitch,int remaining,int otherLength){
        var n=new SongProject.Note(step,noteBlock(c)?1:otherLength,pitch,noteBlock(c)?SongProject.VANILLA_NOTE_VELOCITY:100);n.duration=Math.min(n.duration,remaining);return n;
    }
    public static boolean extended(SongProject.Channel c,SongProject.Event e){return MusicalPitch.requiresSample(e.pitch(),c.baseMidi,c.tunable);}
    public static boolean altered(SongProject.Channel c,SongProject.Event e){return noteBlock(c)&&(extended(c,e)||e.offset()>0||e.duration()!=SongProject.VANILLA_NOTE_DURATION||e.velocity()!=SongProject.VANILLA_NOTE_VELOCITY);}
    public static boolean needsAudio(SongProject.Channel c,SongProject.Event e){return extended(c,e)||e.offset()>0||!c.sampleId.isEmpty()||noteBlock(c)&&e.duration()!=SongProject.VANILLA_NOTE_DURATION;}
    public static boolean hooked(SongProject song,SongProject.Channel c,SongProject.Event e){return song.noteBlockPitchHook&&noteBlock(c)&&e.offset()==0;}
    public static boolean altered(SongProject song,SongProject.Channel c,SongProject.Event e){return !hooked(song,c,e)&&altered(c,e);}
    public static boolean needsAudio(SongProject song,SongProject.Channel c,SongProject.Event e){return !hooked(song,c,e)&&needsAudio(c,e);}
    public static String key(SongProject song,SongProject.Channel c,SongProject.Event e){
        return ProjectMedia.hash((c.sound+":"+c.sampleId+":"+c.trimStart+":"+c.trimEnd+":"+c.fadeIn+":"+c.fadeOut+":"+c.gain+":"+c.baseMidi+":"+c.tunable+":"+e.pitch()+":"+e.duration()+":"+e.offset()+":"+song.bpm).getBytes(StandardCharsets.UTF_8));
    }
    public static String prepared(SongProject song,SongProject.Channel c,SongProject.Event e){String hash=song.blueprintAudio.get(key(song,c,e));if(hash==null&&e.offset()==0)hash=song.blueprintAudio.get(ProjectMedia.hash((c.sound+":"+c.baseMidi+":"+c.tunable+":"+e.pitch()+":"+e.duration()+":"+song.bpm).getBytes(StandardCharsets.UTF_8)));return hash;}
}
