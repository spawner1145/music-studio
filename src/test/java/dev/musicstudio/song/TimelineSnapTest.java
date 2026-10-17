package dev.musicstudio.song;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimelineSnapTest {
    private static final int STEP=SongProject.STEP,BAR=4*SongProject.PPQ;
    @Test void oneBarCanBeTargetedAtBeatAndSixteenthResolution(){
        // Same pointer: bar 2, beat 2, within its second sixteenth.
        double ppq=21.4*STEP;assertEquals(16*STEP,TimelineSnap.position(ppq,16,16*BAR));assertEquals(20*STEP,TimelineSnap.position(ppq,4,16*BAR));assertEquals(21*STEP,TimelineSnap.position(ppq,1,16*BAR));
    }
    @Test void mappingPreservesSongPositionAcrossScrollZoomAndGuiScale(){
        for(double scale:new double[]{.625,1,1.25,2})for(int bars:new int[]{4,8,12,16}){
            double left=286*scale,width=994*scale,x=left+width*5.4/(bars*16);int start=4*BAR;
            assertEquals(start+4*STEP,TimelineSnap.at(x,left,width,start,bars*BAR,4,32*BAR));assertEquals(start+5*STEP,TimelineSnap.at(x,left,width,start,bars*BAR,1,32*BAR));
        }
    }
    @Test void draggingOutsideRulerClampsToVisibleAndProjectBoundsOnTheGrid(){
        assertEquals(4*BAR,TimelineSnap.at(-100,286,994,4*BAR,4*BAR,1,16*BAR));assertEquals(8*BAR-STEP,TimelineSnap.at(1500,286,994,4*BAR,4*BAR,1,16*BAR));
        assertEquals(6*BAR-4*STEP,TimelineSnap.at(1500,286,994,4*BAR,4*BAR,4,6*BAR));assertEquals(0,TimelineSnap.position(-100,1,BAR));
        assertEquals(0,TimelineSnap.position(10000,16,STEP));
    }
}
