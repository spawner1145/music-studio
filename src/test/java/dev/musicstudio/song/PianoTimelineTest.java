package dev.musicstudio.song;

import dev.musicstudio.client.PianoTimeline;
import dev.musicstudio.song.SongProject.Clip;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PianoTimelineTest {
    private static final int BAR=4*SongProject.PPQ,STEP=SongProject.STEP;
    @Test void localAndSongTimesMatchAcrossMultipleInstances(){
        var s=ArrangementTest.song();var later=Clip.pattern(1,0,2*BAR,BAR);s.clips.add(later);
        assertEquals(5*STEP,PianoTimeline.position(s,0,2*BAR+5*STEP,later.id));
        assertEquals(2*BAR+5*STEP,PianoTimeline.seek(s,0,5*STEP,2*BAR,"").orElseThrow());
        assertEquals(5*STEP,PianoTimeline.seek(s,0,5*STEP,0,"").orElseThrow());
    }
    @Test void selectedInstanceWinsWhenSeveralInstancesArePossible(){
        var s=ArrangementTest.song();var later=Clip.pattern(1,0,2*BAR,BAR);s.clips.add(later);
        assertEquals(2*BAR+STEP,PianoTimeline.seek(s,0,STEP,0,later.id).orElseThrow());
        later.start=STEP;later.duration=BAR-STEP;later.contentOffset=-STEP;
        assertEquals(3*STEP,PianoTimeline.position(s,0,3*STEP,later.id));
    }
    @Test void paddingIsSilentAndCroppingKeepsTheSourceOrigin(){
        var s=ArrangementTest.song();s.endPpq=6*BAR;var c=s.clips.getFirst();c.start=BAR;c.duration=3*BAR;c.contentOffset=BAR;c.contentLength=BAR;
        assertTrue(Double.isNaN(PianoTimeline.position(s,0,BAR+STEP,c.id)));
        assertTrue(Double.isNaN(PianoTimeline.position(s,0,3*BAR+STEP,c.id)));
        assertEquals(STEP,PianoTimeline.position(s,0,2*BAR+STEP,c.id));
        assertEquals(2*BAR+STEP,PianoTimeline.seek(s,0,STEP,BAR,c.id).orElseThrow());
        c.start=2*BAR;c.duration=BAR-4*STEP;c.contentOffset=-4*STEP;
        assertEquals(4*STEP,PianoTimeline.position(s,0,2*BAR,c.id));
        assertTrue(PianoTimeline.seek(s,0,2*STEP,2*BAR,c.id).isEmpty());
        assertEquals(2*BAR+STEP,PianoTimeline.seek(s,0,5*STEP,2*BAR,c.id).orElseThrow());
    }
    @Test void legacyRepeatsSeekTheNearestCycleWithoutRepeatingPadding(){
        var s=ArrangementTest.song();var c=s.clips.getFirst();c.duration=3*BAR;c.contentLength=2*BAR;
        assertEquals(2*STEP,PianoTimeline.position(s,0,BAR+2*STEP,c.id));
        assertEquals(BAR+2*STEP,PianoTimeline.seek(s,0,2*STEP,BAR+STEP,c.id).orElseThrow());
        assertTrue(Double.isNaN(PianoTimeline.position(s,0,2*BAR+STEP,c.id)));
    }
    @Test void emptyAndAudioOnlyArrangementsDoNotInventPatternInstances(){
        var s=ArrangementTest.song();s.clips.clear();s.clips.add(Clip.audio(0,0,0,BAR));
        assertTrue(PianoTimeline.seek(s,0,STEP,0,"").isEmpty());assertTrue(Double.isNaN(PianoTimeline.position(s,0,STEP,"")));
    }
    @Test void visualPositionUsesArrangementGeometryEvenWhenTheTrackIsMuted(){
        var s=ArrangementTest.song();s.tracks.getFirst().muted=true;
        assertEquals(4*STEP,PianoTimeline.position(s,0,4*STEP,""));assertEquals(4*STEP,PianoTimeline.seek(s,0,4*STEP,0,"").orElseThrow());
        assertTrue(Double.isNaN(PianoTimeline.position(s,0,BAR,"")));
    }
    @Test void sixthBarIsSeekableInAnOldFiveBarSourceWindow(){
        var s=ArrangementTest.song();s.patterns.getFirst().length=7*BAR;s.endPpq=10*BAR;
        var c=s.clips.getFirst();c.start=2*BAR;c.duration=7*BAR;c.contentLength=5*BAR;
        assertEquals(5*BAR+STEP,PianoTimeline.position(s,0,7*BAR+STEP,c.id));
        assertEquals(7*BAR+STEP,PianoTimeline.seek(s,0,5*BAR+STEP,0,c.id).orElseThrow());
        c.resize(c.start,c.start+4*BAR);assertTrue(PianoTimeline.seek(s,0,5*BAR+STEP,0,c.id).isEmpty());
        c.resize(c.start,c.start+7*BAR);assertEquals(7*BAR+STEP,PianoTimeline.seek(s,0,5*BAR+STEP,0,c.id).orElseThrow());
    }
}
