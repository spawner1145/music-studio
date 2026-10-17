package dev.musicstudio.song;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PianoAudioSourceTest {
    @Test void independentAudioCanAlsoBeAnInstrumentWithoutSharingMutableSettings(){
        var s=ArrangementTest.song();var audio=ArrangementTest.audio(s);audio.trimStart=.1f;audio.trimEnd=1.5f;audio.fadeIn=.2f;audio.fadeOut=.3f;audio.gain=.7f;
        s.clips.add(SongProject.Clip.audio(1,0,ArrangementTest.BAR,ArrangementTest.BAR));String clips=ProjectStore.GSON.toJson(s.clips);String original=ProjectStore.GSON.toJson(audio);int count=s.channels.size();
        int index=s.instrumentFromAudio(audio.id);var sampler=s.channels.get(index);assertEquals(count,index);assertNotEquals(audio.id,sampler.id);assertEquals(audio.id,sampler.originAudioId);assertEquals(audio.sampleId,sampler.sampleId);assertEquals(.7f,sampler.gain);assertEquals(.1f,sampler.trimStart);assertEquals(.3f,sampler.fadeOut);assertTrue(sampler.tunable);assertFalse(sampler.muted);
        for(var p:s.patterns){assertEquals(s.channels.size(),p.notes.size());assertTrue(p.notes.get(index).isEmpty());}assertEquals(clips,ProjectStore.GSON.toJson(s.clips));
        sampler.gain=2;sampler.trimStart=.4f;assertEquals(original,ProjectStore.GSON.toJson(audio));s.validate();var restored=ProjectStore.copy(s);assertEquals(index,restored.instrumentFromAudio(audio.id));assertEquals(count+1,restored.channels.size());
    }
    @Test void repeatedSelectionReusesTheInstrumentAndUndoCanRemoveItAtomically(){
        var s=ArrangementTest.song();var audio=ArrangementTest.audio(s);String before=ProjectStore.encode(s);int index=s.instrumentFromAudio(audio.id);int count=s.channels.size();s.patterns.getFirst().notes.get(index).add(new SongProject.Note(1,1,66,100));
        assertEquals(index,s.instrumentFromAudio(audio.id));assertEquals(count,s.channels.size());assertEquals(1,s.patterns.getFirst().notes.get(index).size());s.validate();assertEquals(before,ProjectStore.encode(ProjectStore.decode(before)));
        while(s.channels.size()<128){s.channels.add(new SongProject.Channel("Other","minecraft:block.note_block.harp",0));s.patterns.forEach(p->p.notes.add(new ArrayList<>()));}
        assertEquals(index,s.instrumentFromAudio(audio.id));var second=new SongProject.Channel("Second audio",audio.sound,0);second.sampleId=audio.sampleId;s.audioSources.add(second);String full=ProjectStore.encode(s);assertThrows(IllegalArgumentException.class,()->s.instrumentFromAudio(second.id));assertEquals(full,ProjectStore.encode(s));
    }
}
