package dev.musicstudio.song;

import dev.musicstudio.song.SongProject.*;
import dev.musicstudio.media.*;
import dev.musicstudio.blueprint.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.midi.MidiSystem;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExportRangeTest {
    @TempDir Path temp;
    static final int BAR=4*SongProject.PPQ;
    @Test void equalCursorsAtAnyPositionExportWholeSongWithoutMutation(){
        var s=ArrangementTest.song();s.exportStart=s.exportEnd=2*BAR;String before=ProjectStore.encode(s);var r=ExportRange.of(s);assertTrue(r.full());assertEquals(0,r.start());var snapshot=ExportRange.snapshot(s);assertEquals(5*SongProject.STEP,snapshot.endPpq);assertEquals(s.events(0,false),snapshot.events(0,false));assertEquals(ProjectStore.encode(snapshot),ProjectStore.encode(ExportRange.snapshot(snapshot)));assertEquals(before,ProjectStore.encode(s));
    }
    @Test void reverseCursorsClipAndRebasePatternNotesPreservingTrailingSilence(){
        var s=ArrangementTest.song();s.exportStart=BAR+SongProject.PPQ;s.exportEnd=SongProject.PPQ;String before=ProjectStore.encode(s);var cut=ExportRange.snapshot(s);cut.validate();assertEquals(BAR,cut.endPpq);assertEquals(1,cut.events(0,false).size());var e=cut.events(0,false).getFirst();assertEquals(0,e.start());assertEquals(1,e.channel());assertEquals(0,cut.exportStart);assertEquals(BAR,cut.exportEnd);assertEquals(ProjectStore.encode(cut),ProjectStore.encode(ExportRange.snapshot(cut)));assertEquals(before,ProjectStore.encode(s));
    }
    @Test void cropIndependentAudioRetainsTrimOffsetAndBlankPadding(){
        var s=ArrangementTest.song();ArrangementTest.audio(s);s.clips.clear();s.clips.add(Clip.audio(0,0,BAR,BAR));s.exportStart=BAR+SongProject.PPQ;s.exportEnd=4*BAR;var cut=ExportRange.snapshot(s);assertEquals(3*BAR-SongProject.PPQ,cut.endPpq);var e=cut.events(0,false).getFirst();assertEquals(0,e.start());assertEquals(SongProject.PPQ,e.offset());assertEquals(3*SongProject.PPQ,e.duration());
    }
    @Test void rangeCanBeShorterThanOneBarAndEmptyRangesKeepAllRest(){
        var s=ArrangementTest.song();s.exportStart=3*BAR;s.exportEnd=3*BAR+SongProject.STEP;var cut=ExportRange.snapshot(s);cut.validate();assertEquals(SongProject.STEP,cut.endPpq);assertTrue(cut.events(0,false).isEmpty());
        var building=MusicBlueprint.compile(s);assertTrue(building.notes().isEmpty());assertEquals(4,building.period());assertTrue(building.parts().stream().anyMatch(p->p.block().equals("stone_button")));
    }
    @Test void actualMixWindowIncludesNaturalTailFromEarlierNoteAndKeepsTrailingRest() throws Exception {
        var s=ArrangementTest.song();s.patterns.getFirst().notes.get(1).clear();s.channels.getFirst().volume=1;float[] tone=new float[44100];Arrays.fill(tone,.3f);AudioFiles.Provider provider=c->new AudioFiles.Source(new AudioFiles.Wave(tone,tone.clone()),1,1);
        var cut=AudioFiles.mixWindow(s,provider,0,false,SongProject.PPQ,3*SongProject.PPQ,()->false);assertEquals(44100,cut.wave().frames());assertEquals(.3f,cut.wave().left()[0],1e-6);assertEquals(.3f,cut.wave().left()[22049],1e-6);assertEquals(0,cut.wave().left()[22050],1e-6);assertEquals(0,cut.wave().left()[44099],1e-6);
        s.exportStart=SongProject.PPQ;s.exportEnd=3*SongProject.PPQ;var file=temp.resolve("tail.wav");var rendered=AudioFiles.render(s,provider,file,"wav",AudioFiles.codec(temp));assertEquals(44100,rendered.frames());assertEquals(1,AudioFiles.read(file).seconds(),1e-6);
    }
    @Test void boundaryCrossingSampleMatchesFullPcmSliceExactly() throws Exception {
        var s=ArrangementTest.song();s.channels.getFirst().sampleId=ArrangementTest.audio(s).sampleId;s.channels.getFirst().tunable=false;s.patterns.getFirst().notes.get(1).clear();s.patterns.getFirst().notes.get(0).getFirst().duration=BAR;
        float[] pcm=new float[88200];for(int i=0;i<pcm.length;i++)pcm[i]=(i%1000)/2000f;AudioFiles.Provider provider=c->new AudioFiles.Source(new AudioFiles.Wave(pcm,pcm.clone()),1,1);
        var full=AudioFiles.mix(s,provider,0,false,()->false);var cut=AudioFiles.mixWindow(s,provider,0,false,SongProject.PPQ,3*SongProject.PPQ,()->false);assertArrayEquals(Arrays.copyOfRange(full.wave().left(),22050,66150),cut.wave().left());
    }
    @Test void selectionPastSongEndIsSilentAndHasExactDurationInAllAudioFormats() throws Exception {
        var s=ArrangementTest.song();s.exportStart=5*BAR;s.exportEnd=6*BAR;int[] calls={0};AudioFiles.Provider provider=c->{calls[0]++;throw new AssertionError("Silent range decoded unused source");};
        for(String format:List.of("wav","mp3","ogg")){var output=temp.resolve("rest."+format);var r=AudioFiles.render(s,provider,output,format,AudioFiles.codec(temp));assertEquals(88200,r.frames());assertTrue(Files.size(output)>100);}
        assertEquals(0,calls[0]);var files=MidiFiles.exportTracks(s,temp.resolve("midi"));assertFalse(files.isEmpty());for(Path file:files)assertEquals(BAR,MidiSystem.getSequence(file.toFile()).getTickLength());
    }
    @Test void selectedMidiAndBuildingUseTheSameRebasedTimeAndDuration() throws Exception {
        var s=ArrangementTest.song();s.exportStart=SongProject.PPQ;s.exportEnd=BAR+SongProject.PPQ;
        var b=MusicBlueprint.compile(s);assertEquals(1,b.notes().size());assertEquals(0,b.notes().getFirst().tick());assertEquals(40,b.period());
        var mids=MidiFiles.exportTracks(s,temp.resolve("midi"));var midi=MidiSystem.getSequence(mids.getFirst().toFile());assertEquals(BAR,midi.getTickLength());var read=MidiFiles.read(mids.getFirst()).song();assertEquals(0,read.events(0,false).getFirst().start());
    }
    @Test void saveAndBlueprintSnapshotsRetainCursorSettingsAndRejectInvalidRanges(){
        var s=ArrangementTest.song();s.exportStart=BAR;s.exportEnd=2*BAR;assertEquals(BAR,ProjectStore.copy(s).exportStart);assertEquals(2*BAR,BlueprintData.unpack(BlueprintData.pack(s)).exportEnd);s.exportEnd=-1;assertThrows(StudioException.class,s::validate);
    }
    @Test void sixthBarFromAnOldWindowReachesAudioMidiAndBuildingExports() throws Exception {
        var s=ArrangementTest.song();s.patterns.getFirst().length=7*BAR;s.endPpq=8*BAR;
        s.patterns.getFirst().notes.getFirst().add(new Note(5*16+4,1,66,127));
        var c=s.clips.getFirst();c.duration=7*BAR;c.contentLength=5*BAR;s.exportStart=5*BAR;s.exportEnd=7*BAR;
        var cut=ExportRange.snapshot(s);assertEquals(1,cut.events(0,false).size());assertEquals(SongProject.PPQ,cut.events(0,false).getFirst().start());
        float[] tone=new float[4410];Arrays.fill(tone,.25f);
        var mix=AudioFiles.mix(cut,ch->new AudioFiles.Source(new AudioFiles.Wave(tone,tone.clone()),1,1),0,false,()->false);
        assertEquals(4*44100,mix.wave().frames());assertEquals(.25f,mix.wave().left()[22050],.0001f);assertEquals(0,mix.wave().left()[3*44100]);
        var midi=MidiFiles.read(MidiFiles.exportTracks(s,temp).getFirst()).song();assertEquals(1,midi.events(0,false).size());assertEquals(SongProject.PPQ,midi.events(0,false).getFirst().start());
        var building=MusicBlueprint.compile(s);assertEquals(1,building.notes().size());assertEquals(10,building.notes().getFirst().tick());assertEquals(80,building.period());
    }
}
