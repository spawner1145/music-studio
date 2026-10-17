package dev.musicstudio.song;

import dev.musicstudio.client.PianoView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PianoViewTest {
    @Test void maximizeAndRestoreRetainExactWindowBounds(){var v=new PianoView();v.bounds(127,92,1000,590);v.toggleMaximize();assertTrue(v.maximized);assertEquals(0,v.x);assertEquals(0,v.y);assertEquals(1280,v.width);assertEquals(806,v.height);v.toggleMaximize();assertFalse(v.maximized);assertEquals(127,v.x);assertEquals(92,v.y);assertEquals(1000,v.width);assertEquals(590,v.height);}
    @Test void eachEdgeKeepsOppositeEdgeAndWindowInsideWorkspace(){
        var v=new PianoView();v.bounds(200,150,900,500);v.resize(200,150,900,500,1,-80,0);assertEquals(120,v.x);assertEquals(980,v.width);assertEquals(1100,v.x+v.width);v.resize(200,150,900,500,2,90,0);assertEquals(200,v.x);assertEquals(990,v.width);
        v.resize(200,150,900,500,4,0,-80);assertEquals(70,v.y);assertEquals(580,v.height);assertEquals(650,v.y+v.height);v.resize(200,150,900,500,8,0,100);assertEquals(150,v.y);assertEquals(600,v.height);
        v.resize(200,150,900,500,5,-9999,-9999);assertEquals(0,v.x);assertEquals(0,v.y);assertEquals(1100,v.width);assertEquals(650,v.height);v.resize(200,150,900,500,10,9999,9999);assertEquals(1280,v.x+v.width);assertEquals(806,v.y+v.height);
        v.resize(200,150,900,500,5,9999,9999);assertEquals(PianoView.MIN_WIDTH,v.width);assertEquals(PianoView.MIN_HEIGHT,v.height);
    }
    @Test void timeAndPitchZoomKeepPointerAnchorAndPanRespectFullRange(){
        var v=new PianoView();v.start=20;v.span=40;v.top=30;v.rows=20;double t=v.start+v.span*.4,p=v.top+v.rows*.6;v.zoomTime(3,.4,256);v.zoomPitch(3,.6,256);assertEquals(t,v.start+v.span*.4,1e-9);assertEquals(p,v.top+v.rows*.6,1e-9);
        v.start=999;v.top=999;v.clamp(256);assertEquals(999,v.start);assertEquals(128-v.rows,v.top);v.start=-20;v.top=-20;v.clamp(256);assertEquals(0,v.start);assertEquals(0,v.top);
    }
    @Test void pixelCoordinatesStillIdentifyCorrectTimeAndKeyAfterResizePanAndZoom(){
        var v=new PianoView();v.bounds(0,0,1280,806);v.timeRange(64,80,256);v.pitchRange(40,64,256);assertEquals(70,v.timeAt(v.timeX(70)),.0001);for(int pitch=64;pitch<88;pitch++)assertEquals(pitch,v.pitchAt(v.pitchY(pitch)+v.gridHeight()/(float)v.rows/2));
        v.zoomTime(5,.5,256);v.zoomPitch(-2,.5,256);assertEquals(72,v.timeAt(v.timeX(72)),.0001);assertEquals(80,v.pitchAt(v.pitchY(80)+v.gridHeight()/(float)v.rows/2));
    }
    @Test void tinyPatternsFullPitchRangeAndExtremeZoomRemainFinite(){
        var v=new PianoView();v.zoomTime(-1000,.5,1);v.zoomPitch(-1000,.5,1);assertEquals(PianoView.MAX_TIME,v.span);assertEquals(128,v.rows);v.zoomTime(1000,.5,1);v.zoomPitch(1000,.5,1);assertEquals(1.0/240,v.span);assertEquals(3,v.rows);assertTrue(Double.isFinite(v.start)&&Double.isFinite(v.top));
        v.toggleMaximize();v.resize(0,0,1280,806,10,-100,-100);assertEquals(1280,v.width);assertEquals(806,v.height);
    }
    @Test void emptyPatternDoesNotLimitPanningZoomingOrScrollbarExtent(){
        var v=new PianoView();v.timeRange(100000,100256,1);assertEquals(100000,v.start);assertEquals(256,v.span);assertTrue(v.extent(1)>v.start+v.span);
        for(int i=0;i<12;i++){double at=v.start+v.span*.4;v.zoomTime(-2,.4,1);assertEquals(at,v.start+v.span*.4,.00001);assertTrue(v.span>256);}
        v.timeRange(PianoView.MAX_TIME-10,PianoView.MAX_TIME+100,1);assertEquals(PianoView.MAX_TIME,v.start+v.span);assertEquals(PianoView.MAX_TIME,v.extent(1));
    }
}
