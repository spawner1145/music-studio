package dev.musicstudio.song;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SongProjectTest {
    @TempDir Path directory;
    @Test void absoluteQuantizationDoesNotAccumulateIntervalRounding() {
        assertArrayEquals(new long[]{0,3,5,8,10},new long[]{SongProject.quantizeTick(0,120),SongProject.quantizeTick(240,120),SongProject.quantizeTick(480,120),SongProject.quantizeTick(720,120),SongProject.quantizeTick(960,120)});
        assertEquals(600000,SongProject.quantizeTick(960*10000,20));
    }
    @Test void patternRepeatsAndClipCropPreservePolyphonyAndSilence() {
        SongProject s=SongProject.legacyDemo();s.clips.clear();
        var p=s.patterns.getFirst();p.notes.forEach(List::clear);
        p.notes.get(0).add(new SongProject.Note(0,2,60,90));
        p.notes.get(0).add(new SongProject.Note(0,2,64,100));
        p.notes.get(0).add(new SongProject.Note(31,1,67,100));
        var clip=new SongProject.Clip(0,0,1,4);clip.duration=35*SongProject.STEP;s.clips.add(clip);s.validate();
        var events=s.events(0,false);
        assertEquals(5,events.size());
        assertEquals(4*SongProject.PPQ,events.getFirst().start());
        assertEquals(events.get(0).start(),events.get(1).start());
        assertEquals((16+32)*SongProject.STEP,events.get(3).start());
        s.channels.getFirst().muted=true;assertTrue(s.events(0,false).isEmpty());
    }
    @Test void savesAreAtomicWithBackupAndRoundTripSoundBindings() throws Exception {
        ProjectStore store=new ProjectStore(directory);SongProject s=SongProject.legacyDemo();
        s.channels.get(2).sound="examplemod:instruments.custom/pluck";
        store.save("夜航",s);String original=Files.readString(store.path("夜航"));
        s.bpm=145;store.save("夜航",s);
        assertEquals(145,store.load("夜航").bpm);
        assertEquals("examplemod:instruments.custom/pluck",store.load("夜航").channels.get(2).sound);
        assertEquals(original,Files.readString(directory.resolve("夜航.json.bak")));
        assertEquals(List.of("夜航"),store.list());
        assertEquals(s.events(0,false),store.load("夜航").events(0,false));
    }
    @Test void malformedProjectsAndPathEscapesAreRejected() {
        ProjectStore store=new ProjectStore(directory);
        for(String n:List.of("../escape","..","C:\\escape","CON","file.","test/evil"))assertThrows(IllegalArgumentException.class,()->store.path(n));
        SongProject s=SongProject.legacyDemo();s.patterns.getFirst().notes.getFirst().getFirst().duration=Integer.MAX_VALUE;
        assertThrows(IllegalArgumentException.class,s::validate);
        s=SongProject.legacyDemo();s.channels.getFirst().sound="../../evil";assertThrows(IllegalArgumentException.class,s::validate);
        assertThrows(IllegalArgumentException.class,()->ProjectStore.decode("null"));
    }
    @Test void duplicatedEventIdsAndDanglingPatternReferencesAreRejected() {
        SongProject s=SongProject.legacyDemo();var ns=s.patterns.getFirst().notes.getFirst();ns.get(1).id=ns.getFirst().id;assertThrows(IllegalArgumentException.class,s::validate);
        SongProject other=SongProject.legacyDemo();other.clips.getFirst().pattern=999;assertThrows(IllegalArgumentException.class,other::validate);
    }
}
