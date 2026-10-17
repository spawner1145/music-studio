package dev.musicstudio.song;

import dev.musicstudio.song.SongProject.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PatternAndSourceTest {
    @Test void deletingPatternRemovesItsInstancesAndPreservesOtherReferencesAndAudio(){
        var s=ArrangementTest.song();var p=new Pattern("Keep",2);p.notes.get(1).add(new Note(2,1,70,120));s.patterns.add(p);s.clips.add(Clip.pattern(1,1,ArrangementTest.BAR,ArrangementTest.BAR));ArrangementTest.audio(s);var a=Clip.audio(0,0,2*ArrangementTest.BAR,ArrangementTest.BAR);s.clips.add(a);
        String before=ProjectStore.encode(s),keep=p.id;int sources=s.channels.size(),tracks=s.tracks.size();s.removePattern(0);s.validate();
        assertEquals(1,s.patterns.size());assertEquals(keep,s.patterns.getFirst().id);assertEquals(2,s.clips.size());assertEquals(0,s.clips.getFirst().pattern);assertTrue(s.clips.contains(a));assertEquals(-1,a.pattern);assertEquals(sources,s.channels.size());assertEquals(tracks,s.tracks.size());assertEquals(3,ProjectStore.decode(before).clips.size());
    }
    @Test void deletingLastPatternCreatesFreshEmptyPatternWithoutOrphanedInstances(){
        var s=ArrangementTest.song();ArrangementTest.audio(s);s.clips.add(Clip.audio(1,0,ArrangementTest.BAR,ArrangementTest.BAR));String id=s.patterns.getFirst().id;s.removePattern(0);s.validate();
        assertEquals(1,s.patterns.size());assertNotEquals(id,s.patterns.getFirst().id);assertEquals(s.channels.size(),s.patterns.getFirst().notes.size());assertTrue(s.patterns.getFirst().notes.stream().allMatch(List::isEmpty));assertEquals(1,s.clips.size());assertTrue(s.clips.getFirst().audio());
    }
    @Test void replacingSamplerPreservesSourceIdentityNotesMuteNamesAndArrangement(){
        var s=ArrangementTest.song();var c=s.channels.getFirst();String id=c.id,notes=ProjectStore.GSON.toJson(s.patterns),clips=ProjectStore.GSON.toJson(s.clips);c.muted=true;c.fadeIn=.2f;c.gain=.5f;c.volume=.4f;String asset=ArrangementTest.audio(s).sampleId;
        s.replaceSampler(id,asset);s.validate();assertEquals(id,c.id);assertEquals("Harp",c.name);assertEquals(.4f,c.volume);assertTrue(c.muted);assertEquals(asset,c.sampleId);assertEquals("musicstudio:sample/"+asset,c.sound);assertTrue(c.tunable);assertEquals(0,c.fadeIn);assertEquals(1,c.gain);assertEquals(notes,ProjectStore.GSON.toJson(s.patterns));assertEquals(clips,ProjectStore.GSON.toJson(s.clips));assertEquals(2,s.channels.size());
    }
    @Test void invalidDeletionAndReplacementAreAtomic(){
        var s=ArrangementTest.song();String before=ProjectStore.encode(s);assertThrows(StudioException.class,()->s.removePattern(99));assertThrows(StudioException.class,()->s.replaceSampler(s.channels.getFirst().id,"b".repeat(64)));assertEquals(before,ProjectStore.encode(s));
    }
    @Test void clipboardCanRestoreDeletedPatternWithAllSources(){
        var s=ArrangementTest.song();var c=s.clips.getFirst();String id=s.patterns.getFirst().id;var clipboard=EditorClipboard.copyClips(s,Set.of(c.id));s.removePattern(0);clipboard.pasteClips(s,ArrangementTest.BAR);s.validate();assertEquals(2,s.patterns.size());assertEquals(id,s.patterns.get(s.clips.getFirst().pattern).id);assertEquals(2,s.events(0,false).size());
    }
}
