package dev.musicstudio.song;

import dev.musicstudio.song.SongProject.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PatternGrowthTest {
    static final int BAR=4*SongProject.PPQ,STEP=SongProject.STEP;
    @Test void suggestedLengthLeavesAWholeSilentBarAndRoundsUp(){
        assertEquals(2*BAR,SongProject.suggestedPatternLength(0));assertEquals(2*BAR,SongProject.suggestedPatternLength(BAR));
        assertEquals(3*BAR,SongProject.suggestedPatternLength(BAR+1));assertEquals(4*BAR,SongProject.suggestedPatternLength(2*BAR+STEP));
    }
    @Test void starterPatternsAlsoKeepTheDefaultSilentBar(){
        var s=SongProject.demo();for(var p:s.patterns){int end=p.notes.stream().flatMap(List::stream).mapToInt(n->n.start+n.duration).max().orElse(0);assertTrue(p.length>=end+BAR);}
        s.validate();
    }
    @Test void allSourcesDetermineLengthAndWholeInstancesGrowTogether(){
        var s=ArrangementTest.song();s.clips.add(Clip.pattern(1,0,2*BAR,BAR));s.patterns.getFirst().notes.get(1).add(new Note(20,17,72,90));s.growPattern(0);
        assertEquals(4*BAR,s.patterns.getFirst().length);assertEquals(4*BAR,s.clips.getFirst().duration);assertEquals(4*BAR,s.clips.get(1).contentLength);assertEquals(6*BAR,s.endPpq);s.validate();
        assertTrue(s.events(0,false).stream().anyMatch(e->e.start()==20*STEP&&e.duration()==17*STEP));
    }
    @Test void paddingIsRetainedAndTrimmedOrRepeatedInstancesKeepTheirWindows(){
        var s=ArrangementTest.song();s.endPpq=8*BAR;var padded=s.clips.getFirst();padded.resize(0,3*BAR);padded.contentOffset=BAR;
        var trimmed=Clip.pattern(1,0,4*BAR,BAR);trimmed.resize(4*BAR+STEP,5*BAR-STEP);s.clips.add(trimmed);
        var repeated=Clip.pattern(1,0,6*BAR,2*BAR);s.clips.add(repeated);int cutDuration=trimmed.duration;
        s.patterns.getFirst().notes.getFirst().add(new Note(18,1,75,127));s.growPattern(0);
        assertEquals(3*BAR,s.patterns.getFirst().length);assertEquals(5*BAR,padded.duration);assertEquals(BAR,padded.contentOffset);assertEquals(BAR,padded.duration-padded.contentOffset-padded.contentLength);
        assertEquals(cutDuration,trimmed.duration);assertEquals(-STEP,trimmed.contentOffset);assertEquals(2*BAR,repeated.duration);assertEquals(2*BAR,repeated.contentLength);s.validate();
    }
    @Test void growingUnplacedPatternDoesNotExtendTheArrangementOrShortenManualSpace(){
        var s=ArrangementTest.song();s.clips.clear();s.patterns.getFirst().length=8*BAR;int end=s.endPpq;s.growPattern(0);assertEquals(8*BAR,s.patterns.getFirst().length);assertEquals(end,s.endPpq);
        s.patterns.getFirst().notes.getFirst().add(new Note(200,1,70,127));s.growPattern(0);assertTrue(s.patterns.getFirst().length>201*STEP);assertEquals(end,s.endPpq);s.validate();
    }
    @Test void pasteBeyondOldEndGrowsPatternAndUndoSnapshotRestoresExactState(){
        var s=ArrangementTest.song();var n=s.patterns.getFirst().notes.getFirst().getFirst();var clipboard=EditorClipboard.copyNotes(s.patterns.getFirst().notes.getFirst(),Set.of(n.id));String before=ProjectStore.encode(s);
        var ids=clipboard.pasteNotes(s,0,1,300*BAR);assertEquals(1,ids.size());assertEquals(302*BAR,s.patterns.getFirst().length);assertEquals(302*BAR,s.endPpq);s.validate();assertEquals(before,ProjectStore.encode(ProjectStore.decode(before)));
        String grown=ProjectStore.encode(s);assertThrows(IllegalArgumentException.class,()->clipboard.pasteNotes(s,0,1,SongProject.MAX_SONG_PPQ));assertEquals(grown,ProjectStore.encode(s));
    }
    @Test void longPatternsUseTheProjectTimeRangeAndNearEndGrowthNeverOverflows(){
        var s=ArrangementTest.song();s.patterns.getFirst().notes.getFirst().add(new Note(10000,1,65,127));s.growPattern(0);assertTrue(s.patterns.getFirst().length>256*BAR);s.validate();
        assertEquals(SongProject.MAX_SONG_PPQ,SongProject.suggestedPatternLength(SongProject.MAX_SONG_PPQ));
        assertThrows(IllegalArgumentException.class,()->SongProject.suggestedPatternLength((long)SongProject.MAX_SONG_PPQ+1));
    }
    @Test void oldFiveBarSourceWindowDoesNotHideTheSixthBarOfASevenBarPattern(){
        var s=ArrangementTest.song();var p=s.patterns.getFirst();p.length=7*BAR;
        p.notes.getFirst().add(new Note(5*16+4,1,62,127));
        var c=s.clips.getFirst();c.duration=7*BAR;c.contentLength=5*BAR;s.endPpq=8*BAR;
        var json=ProjectStore.GSON.toJsonTree(s).getAsJsonObject();
        json.getAsJsonArray("clips").forEach(k->k.getAsJsonObject().remove("fixedPatternWindow"));
        var loaded=ProjectStore.decode(json.toString());
        assertTrue(loaded.events(0,false).stream().anyMatch(e->e.start()==5*BAR+4*STEP));
        assertTrue(loaded.clipEvents(loaded.clips.getFirst(),5*BAR,6*BAR).stream().anyMatch(e->e.pitch()==62));
        assertEquals(7*BAR,loaded.clips.getFirst().duration);
    }
    @Test void croppedInstanceCanRevealLaterEditsWhenExtendedAgain(){
        var s=ArrangementTest.song();var p=s.patterns.getFirst();p.length=5*BAR;
        var c=s.clips.getFirst();c.duration=c.contentLength=5*BAR;c.resize(STEP,4*BAR);
        p.notes.getFirst().add(new Note(5*16+4,1,105,127));s.growPattern(0);
        assertEquals(7*BAR,p.length);assertEquals(4*BAR-STEP,c.duration);assertEquals(-STEP,c.contentOffset);
        c.resize(STEP,7*BAR);s.endPpq=8*BAR;
        assertTrue(s.events(0,false).stream().anyMatch(e->e.start()==5*BAR+4*STEP&&e.pitch()==105));
        p.notes.getFirst().add(new Note(7*16+4,1,24,127));s.growPattern(0);
        assertEquals(7*BAR-STEP,c.duration);
        c.resize(0,9*BAR);s.endPpq=10*BAR;
        assertTrue(s.events(0,false).stream().anyMatch(e->e.start()==7*BAR+4*STEP&&e.pitch()==24));s.validate();
    }
    @Test void repeatedWindowSurvivesGrowthSaveAndClipboardCapturedBeforeGrowth(){
        var s=ArrangementTest.song();s.endPpq=16*BAR;
        var repeated=Clip.pattern(1,0,0,2*BAR);s.clips.add(repeated);
        var clipboard=EditorClipboard.copyClips(s,Set.of(repeated.id));
        s.patterns.getFirst().notes.getFirst().add(new Note(5*16+4,1,62,127));s.growPattern(0);
        var loaded=ProjectStore.copy(s);var restored=loaded.clips.get(1);
        assertEquals(2*BAR,loaded.clipSourceLength(restored));assertTrue(restored.fixedPatternWindow);
        var ids=clipboard.pasteClips(loaded,8*BAR);
        var pasted=loaded.clips.stream().filter(c->ids.contains(c.id)).findFirst().orElseThrow();
        pasted.resize(pasted.start,pasted.start+7*BAR);
        assertEquals(2*BAR,loaded.clipSourceLength(pasted));
        assertFalse(loaded.clipEvents(pasted,pasted.start,pasted.start+pasted.duration).stream().anyMatch(e->e.pitch()==62));loaded.validate();
    }
    @Test void clipboardCapturedBeforeSourceGrowthKeepsAnEditableLinkedCrop(){
        var s=ArrangementTest.song();s.endPpq=16*BAR;var clipboard=EditorClipboard.copyClips(s,Set.of(s.clips.getFirst().id));
        s.patterns.getFirst().notes.getFirst().add(new Note(5*16+4,1,62,127));s.growPattern(0);
        var ids=clipboard.pasteClips(s,8*BAR);var pasted=s.clips.stream().filter(c->ids.contains(c.id)).findFirst().orElseThrow();
        assertEquals(BAR,pasted.duration);pasted.resize(pasted.start,pasted.start+7*BAR);
        assertTrue(s.clipEvents(pasted,pasted.start,pasted.start+pasted.duration).stream().anyMatch(e->e.start()==13*BAR+4*STEP&&e.pitch()==62));s.validate();
    }
}
