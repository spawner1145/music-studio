package dev.musicstudio.song;

import dev.musicstudio.blueprint.*;
import dev.musicstudio.media.*;
import dev.musicstudio.song.SongProject.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.midi.MidiSystem;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FullSongExportTest {
    @TempDir Path temp;
    private static final int BAR=4*SongProject.PPQ,STEP=SongProject.STEP,RATE=AudioFiles.RATE;
    private static SongProject song(){
        var s=ArrangementTest.song();s.endPpq=128*BAR;s.patterns.getFirst().notes.forEach(List::clear);
        s.patterns.getFirst().notes.getFirst().add(new Note(0,1,66,127));s.clips.getFirst().resize(0,64*BAR);return s;
    }
    private static AudioFiles.Source tone(int frames,float value){float[] pcm=new float[frames];Arrays.fill(pcm,value);return new AudioFiles.Source(new AudioFiles.Wave(pcm,pcm.clone()),1,1);}
    @Test void hugeProjectAndPaddedClipDoNotAddSilenceOrTriggerTheTenMinuteLimit() throws Exception {
        var s=song();s.endPpq=SongProject.MAX_SONG_PPQ;s.exportStart=s.exportEnd=40*BAR;String before=ProjectStore.encode(s);int[] calls={0};
        var result=AudioFiles.render(s,c->{calls[0]++;return tone(RATE,.25f);},temp.resolve("short.wav"),"wav",AudioFiles.codec(temp));
        assertEquals(RATE,result.frames());assertEquals(1,calls[0]);assertEquals(before,ProjectStore.encode(s));assertEquals(.25f,AudioFiles.read(result.file()).left()[RATE-1],.0001);
    }
    @Test void naturalDecayCanFinishAfterTheProjectAndClipBoundary() throws Exception {
        var s=song();s.endPpq=2*BAR;var c=s.clips.getFirst();c.start=2*BAR-STEP;c.duration=STEP;
        var mixed=AudioFiles.mixFullExport(s,ch->tone(RATE,.2f));int expected=(int)Math.round(c.start*60.0*RATE/(SongProject.PPQ*s.bpm))+RATE;
        assertEquals(expected,mixed.wave().frames());assertEquals(.2f,mixed.wave().right()[expected-1]);
        assertTrue(mixed.wave().seconds()>s.endPpq*60.0/(SongProject.PPQ*s.bpm));
    }
    @Test void instrumentAndResourcePitchDetermineTheTrueNaturalTail() throws Exception {
        var s=song();s.patterns.getFirst().notes.getFirst().getFirst().pitch=54;
        var mixed=AudioFiles.mixFullExport(s,c->{var source=tone(RATE,.2f);return new AudioFiles.Source(source.wave(),1,1.5f);});
        assertEquals(58800,mixed.wave().frames());
    }
    @Test void noteGateAndSourceSilenceEndEarlyAndMutedLateClipsAreExcluded() throws Exception {
        var s=song();s.patterns.getFirst().notes.getFirst().getFirst().duration=SongProject.PPQ;s.clips.add(Clip.pattern(1,0,90*BAR,BAR));s.tracks.get(1).muted=true;
        float[] pcm=new float[3*RATE];Arrays.fill(pcm,0,RATE/5,.3f);
        var mix=AudioFiles.mixFullExport(s,c->new AudioFiles.Source(new AudioFiles.Wave(pcm,pcm.clone()),1,1));
        assertEquals(RATE/5,mix.wave().frames());assertEquals(SongProject.PPQ,ExportRange.snapshot(s).endPpq);
    }
    @Test void quietRightChannelTailAndInternalRestSurvive() throws Exception {
        var s=song();s.patterns.getFirst().notes.getFirst().add(new Note(8,1,66,127));
        float[] left=new float[RATE/2],right=left.clone();right[right.length-1]=1f/32767;
        var mix=AudioFiles.mixFullExport(s,c->new AudioFiles.Source(new AudioFiles.Wave(left,right),1,1));
        assertEquals(3*RATE/2,mix.wave().frames());assertEquals(0,mix.wave().right()[RATE*3/4]);assertEquals(1f/32767,mix.wave().right()[mix.wave().frames()-1]);
    }
    @Test void zeroVolumeAndCancelledMixDoNotCreateAnEmptyProjectLengthTail() throws Exception {
        var s=song();s.channels.getFirst().volume=0;int[] calls={0};var silent=AudioFiles.mixFullExport(s,c->{calls[0]++;throw new AssertionError("silent source decoded");});
        assertEquals(0,calls[0]);assertEquals(1,silent.wave().frames());
        s.channels.getFirst().volume=1;s.patterns.getFirst().notes.get(1).add(new Note(0,1,66,127));
        var cancelled=AudioFiles.mixFullExport(s,c->tone(RATE,c==s.channels.getFirst()?.2f:-.2f));assertEquals(1,cancelled.wave().frames());
    }
    @Test void explicitSelectionStillIncludesTailRestAfterRepeatedSnapshots() throws Exception {
        var s=song();s.exportStart=0;s.exportEnd=4*BAR;var cut=ExportRange.snapshot(s);var again=ExportRange.snapshot(cut);
        assertEquals(4*BAR,again.endPpq);assertEquals(ProjectStore.encode(cut),ProjectStore.encode(again));
        var rendered=AudioFiles.render(again,c->tone(RATE/10,.2f),temp.resolve("selected.wav"),"wav",AudioFiles.codec(temp));
        assertEquals(8*RATE,rendered.frames());assertEquals(0,AudioFiles.read(rendered.file()).left()[rendered.frames()-1]);
    }
    @Test void midiAndNativePeriodUseTheLastActiveNoteInsteadOfPaddedInstances() throws Exception {
        var s=song();s.patterns.getFirst().notes.getFirst().add(new Note(4,1,69,127));s.clips.add(Clip.pattern(1,0,100*BAR,BAR));s.tracks.get(1).muted=true;
        var midi=MidiSystem.getSequence(MidiFiles.exportTracks(s,temp).getFirst().toFile());assertEquals(5*STEP,midi.getTickLength());
        assertEquals(14,MusicBlueprint.compile(s).period());s.loop=true;assertEquals(16,MusicBlueprint.compile(s).period());
    }
    @Test void resolvedAudioTailSurvivesBlueprintPackingAndCompilation() throws Exception {
        var s=song();int end=Math.max(ExportRange.contentEnd(s),AudioFiles.fullEndPpq(s,c->tone(3*RATE/2,.2f)));
        var prepared=ExportRange.snapshot(s,end);var restored=BlueprintData.unpack(BlueprintData.pack(prepared));
        assertEquals(3*SongProject.PPQ,restored.endPpq);assertEquals(30,MusicBlueprint.compile(restored).period());assertEquals(128*BAR,s.endPpq);
    }
    @Test void independentAudioUsesItsCropOffsetAndVisibleDuration() throws Exception {
        var s=song();ArrangementTest.audio(s);s.clips.clear();var c=Clip.audio(0,0,SongProject.PPQ,BAR);s.clips.add(c);c.resize(2*SongProject.PPQ,3*SongProject.PPQ);
        assertEquals(SongProject.PPQ,s.events(0,false).getFirst().offset());
        assertEquals(3*RATE/2,AudioFiles.mixFullExport(s,ch->tone(2*RATE,.2f)).wave().frames());
    }
    @Test void rawPlaySoundCommandKeepsItsActualSourceTailInTheBuildingPeriod() throws Exception {
        var s=song();s.commandSamples=true;var c=s.channels.getFirst();c.sound="minecraft:ambient.cave";c.instrument="";
        int audioEnd=AudioFiles.fullEndPpq(s,ch->tone(RATE,.2f));assertTrue(audioEnd>=STEP&&audioEnd<=STEP+1);
        int end=AudioFiles.blueprintEndPpq(s,ch->tone(RATE,.2f));assertEquals(2*SongProject.PPQ,end);
        var b=MusicBlueprint.compile(ExportRange.snapshot(s,end));assertEquals(20,b.period());assertTrue(b.outputs().getFirst().command());
    }
}
