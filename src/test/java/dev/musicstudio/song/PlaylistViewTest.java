package dev.musicstudio.song;

import dev.musicstudio.client.PlaylistView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistViewTest {
    @Test void panCanGoBeyondCurrentSongAndExtentKeepsRoomToTheRight(){var v=new PlaylistView();v.pan(256);assertEquals(256,v.start);assertTrue(v.extent(16)>v.start+v.bars);v.pan(-5);assertEquals(0,v.start);}
    @Test void continuousZoomPreservesPointerAnchorAndExceedsOldLimits(){var v=new PlaylistView();v.pan(32);double anchor=v.start+v.bars*.3;v.zoom(12,.3);assertTrue(v.bars<4);assertEquals(anchor,v.start+v.bars*.3,1e-6);v.zoom(-26,.3);assertTrue(v.bars>16);assertTrue(Double.isFinite(v.bars));v.zoom(10000,.5);assertTrue(v.bars>0);v.zoom(-10000,.5);assertTrue(Double.isFinite(v.bars));}
    @Test void resizingOneTrackPersistsAndAddingTracksDoesNotShrinkExistingOnes(){var s=SongProject.legacyDemo();s.channels.getFirst().displayHeight=142;String json=ProjectStore.encode(s);var loaded=ProjectStore.decode(json);assertEquals(142,loaded.channels.getFirst().displayHeight);var c=new SongProject.Channel("new","minecraft:block.note_block.harp",0);s.channels.add(c);s.patterns.forEach(p->p.notes.add(new java.util.ArrayList<>()));assertEquals(65,c.displayHeight);assertEquals(142,s.channels.getFirst().displayHeight);assertEquals(65,s.channels.get(1).displayHeight);s.validate();}
    @Test void oldScoresGetDefaultTrackHeightAndLongerArrangementsAreValid(){String json=ProjectStore.encode(SongProject.legacyDemo()).replaceAll("\\s*\"displayHeight\": 65,","");var s=ProjectStore.decode(json);assertEquals(65,s.channels.getFirst().displayHeight);s.endPpq=300*4*SongProject.PPQ;assertDoesNotThrow(s::validate);}
}
