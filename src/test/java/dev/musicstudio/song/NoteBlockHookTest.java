package dev.musicstudio.song;

import dev.musicstudio.blueprint.*;
import dev.musicstudio.media.BlueprintAudio;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NoteBlockHookTest {
    private SongProject song(){var s=BlueprintCompilerTest.tune(false);s.channels.getFirst().volume=.6f;s.patterns.getFirst().notes.getFirst().clear();return s;}
    @Test void optInPreservesFullPitchVelocityAndGateWithoutCommandsOrBaking(){
        var s=song();s.noteBlockPitchHook=true;var ns=s.patterns.getFirst().notes.getFirst();
        ns.add(new SongProject.Note(0,1,42,127));ns.add(new SongProject.Note(2,2,90,64));ns.add(new SongProject.Note(4,1,66,80));
        var b=MusicBlueprint.compile(s);assertEquals(0,b.pitchLimited());assertEquals(0,b.shortened());assertTrue(b.outputs().stream().noneMatch(MusicBlueprint.Output::command));
        var voices=b.parts().stream().filter(p->p.noteVoice()!=null).map(MusicBlueprint.Part::noteVoice).toList();
        assertEquals(3,voices.size());assertTrue(voices.contains(new NoteBlockVoice(-12,127,0,.6f)));assertTrue(voices.contains(new NoteBlockVoice(36,64,5,.6f)));assertTrue(voices.contains(new NoteBlockVoice(12,80,0,.6f)));
        for(var e:s.events(0,false))assertFalse(BlueprintAudio.needsAudio(s,s.channels.getFirst(),e));
        assertTrue(b.parts().stream().filter(p->p.block().equals("note_block")).allMatch(p->p.value()>=0&&p.value()<=24));
        assertTrue(ProjectStore.copy(s).noteBlockPitchHook);assertTrue(BlueprintData.unpack(BlueprintData.pack(s)).noteBlockPitchHook);
    }
    @Test void oldProjectsAndDisabledModeKeepCommandFallback(){
        var s=song();assertFalse(s.noteBlockPitchHook);var oldJson=com.google.gson.JsonParser.parseString(ProjectStore.encode(s)).getAsJsonObject();oldJson.remove("noteBlockPitchHook");assertFalse(ProjectStore.decode(oldJson.toString()).noteBlockPitchHook);
        s.patterns.getFirst().notes.getFirst().add(new SongProject.Note(0,1,66,64));assertTrue(MusicBlueprint.compile(s).outputs().getFirst().command());
        s.noteBlockPitchHook=true;assertTrue(MusicBlueprint.compile(s).parts().stream().anyMatch(p->p.noteVoice()!=null));assertTrue(MusicBlueprint.legacySnapshot(s).parts().stream().allMatch(p->p.noteVoice()==null));
    }
    @Test void samplesSoundEventsAndCroppedNotesStillNeedCommands(){
        var s=song();s.noteBlockPitchHook=true;var c=s.channels.getFirst();var e=new SongProject.Event(0,0,240,66,64);
        assertTrue(BlueprintAudio.hooked(s,c,e));assertFalse(BlueprintAudio.hooked(s,c,new SongProject.Event(0,0,240,66,64,0,240)));
        c.tunable=false;assertTrue(BlueprintAudio.hooked(s,c,e));assertEquals(12,NoteBlockVoice.of(s,c,e).note());
        c.sound="minecraft:ambient.cave";assertFalse(BlueprintAudio.hooked(s,c,e));c.sound="minecraft:block.note_block.harp";c.sampleId="a".repeat(64);assertFalse(BlueprintAudio.hooked(s,c,e));
    }
    @Test void extremesAndSubTickDurationsAreValidated(){
        var s=song();var c=s.channels.getFirst();c.baseMidi=127;assertEquals(-115,NoteBlockVoice.of(s,c,new SongProject.Event(0,0,1,0,1)).note());
        c.baseMidi=0;assertEquals(new NoteBlockVoice(139,127,1,.6f),NoteBlockVoice.of(s,c,new SongProject.Event(0,0,1,127,127)));
        assertThrows(IllegalArgumentException.class,()->new NoteBlockVoice(140,127,0,1));assertThrows(IllegalArgumentException.class,()->new NoteBlockVoice(12,0,0,1));assertThrows(IllegalArgumentException.class,()->new NoteBlockVoice(12,127,0,Float.NaN));
    }
}
