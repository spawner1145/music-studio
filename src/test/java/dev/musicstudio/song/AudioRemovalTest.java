package dev.musicstudio.song;

import dev.musicstudio.song.SongProject.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioRemovalTest {
    private SongProject fixture(){
        var s=ArrangementTest.song();var first=ArrangementTest.audio(s);
        for(int i=1;i<3;i++){var source=new Channel("Recording "+i,first.sound,first.color);source.sampleId=first.sampleId;s.audioSources.add(source);}
        for(int i=0;i<3;i++)s.clips.add(Clip.audio(i%2,i,i*ArrangementTest.BAR,ArrangementTest.BAR));
        s.clips.add(Clip.audio(1,1,3*ArrangementTest.BAR,ArrangementTest.BAR));s.validate();return s;
    }
    @Test void removalDeletesEveryPlacementAndKeepsOtherBindingsAndPatternSamplers(){
        var s=fixture();var deleted=s.audioSources.get(1);int sampler=s.instrumentFromAudio(deleted.id);
        s.patterns.getFirst().notes.get(sampler).add(new Note(2,2,72,100));
        String patterns=ProjectStore.GSON.toJson(s.patterns),channels=ProjectStore.GSON.toJson(s.channels),tracks=ProjectStore.GSON.toJson(s.tracks),assets=ProjectStore.GSON.toJson(s.assets);
        var remaining=s.audioSources.get(2);var clip=s.clips.get(3);String clipId=clip.id;
        s.removeAudioSource(1);s.validate();assertEquals(2,s.audioSources.size());assertEquals(3,s.clips.size());
        assertTrue(s.audioSources.stream().noneMatch(c->c.id.equals(deleted.id)));assertSame(remaining,s.audioSources.get(1));
        assertSame(clip,s.clips.stream().filter(c->c.id.equals(clipId)).findFirst().orElseThrow());assertEquals(1,clip.audioSource);
        assertEquals(patterns,ProjectStore.GSON.toJson(s.patterns));assertEquals(channels,ProjectStore.GSON.toJson(s.channels));assertEquals(tracks,ProjectStore.GSON.toJson(s.tracks));assertEquals(assets,ProjectStore.GSON.toJson(s.assets));
        assertTrue(s.events(0,false).stream().anyMatch(e->e.channel()==sampler&&e.pitch()==72));
        assertEquals(ProjectStore.encode(s),ProjectStore.encode(ProjectStore.copy(s)));
    }
    @Test void copiedSurvivingAudioKeepsIdentityAndDeletedAudioCannotPasteIntoItsSuccessor(){
        var s=fixture();var deleted=s.clips.get(2);var surviving=s.clips.get(3);String survivorId=s.audioSources.get(2).id;
        var stale=EditorClipboard.copyClips(s,Set.of(deleted.id));var copied=EditorClipboard.copyClips(s,Set.of(surviving.id));s.removeAudioSource(1);
        String after=ProjectStore.encode(s);assertThrows(StudioException.class,()->stale.pasteClips(s,0));assertEquals(after,ProjectStore.encode(s));
        var ids=copied.pasteClips(s,0);var pasted=s.clips.stream().filter(c->ids.contains(c.id)).findFirst().orElseThrow();assertEquals(survivorId,s.audioSources.get(pasted.audioSource).id);s.validate();
    }
    @Test void firstLastAndFinalAudioCanBeRemovedAndHistoryRestoresExactReferences(){
        var s=fixture();String before=ProjectStore.encode(s);s.removeAudioSource(0);s.removeAudioSource(1);s.removeAudioSource(0);s.validate();
        assertTrue(s.audioSources.isEmpty());assertEquals(1,s.clips.size());assertFalse(s.clips.getFirst().audio());assertEquals(1,s.assets.size());
        assertEquals(before,ProjectStore.encode(ProjectStore.decode(before)));
    }
    @Test void invalidIndicesLeaveTheProjectUnchanged(){
        var s=fixture();String before=ProjectStore.encode(s);assertThrows(StudioException.class,()->s.removeAudioSource(-1));assertThrows(StudioException.class,()->s.removeAudioSource(3));assertEquals(before,ProjectStore.encode(s));
    }
}
