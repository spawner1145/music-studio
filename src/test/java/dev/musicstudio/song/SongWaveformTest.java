package dev.musicstudio.song;

import dev.musicstudio.media.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class SongWaveformTest {
    @TempDir Path temp;
    private SongProject song(){var s=SongProject.legacyDemo();s.channels=new ArrayList<>(s.channels.subList(0,2));s.channels.forEach(c->c.volume=1);s.patterns.clear();var p=new SongProject.Pattern("waveform",2);p.length=4*SongProject.PPQ;p.notes.get(0).add(new SongProject.Note(4,2,66,127));s.patterns.add(p);s.clips.clear();s.clips.add(new SongProject.Clip(0,0,0,1));s.clips.add(new SongProject.Clip(1,0,0,1));s.endPpq=p.length;s.loop=false;return s;}
    private AudioFiles.Source tone(float gain){float[] l=new float[44100],r=new float[44100];for(int i=0;i<l.length;i++)l[i]=r[i]=(float)Math.sin(i*2*Math.PI*440/44100)*gain;return new AudioFiles.Source(new AudioFiles.Wave(l,r),1,1);}
    private SongWaveform wave(SongProject s,AudioFiles.Provider provider) throws Exception {return SongWaveform.from(AudioFiles.mix(s,provider,0,false,()->false),()->false);}
    private float peak(SongWaveform.Columns columns){float peak=0;for(float x:columns.low())peak=Math.max(peak,Math.abs(x));for(float x:columns.high())peak=Math.max(peak,Math.abs(x));return peak;}
    @Test void waveformUsesSamePcmAsExportAndKeepsRestsAndNoteOffSilent() throws Exception {
        var s=song();var mix=AudioFiles.mix(s,c->tone(.5f),0,false,()->false);var data=SongWaveform.from(mix,()->false);assertEquals(2,data.seconds());assertEquals(0,peak(data.columns(0,.4,129)));assertEquals(.5f,peak(data.columns(.55,.65,129)),.001);assertEquals(0,peak(data.columns(.9,1.5,129)));
        Path file=temp.resolve("mix.wav");AudioFiles.render(s,c->tone(.5f),file,"wav",AudioFiles.codec(temp));var decoded=AudioFiles.read(file);for(int i=0;i<decoded.frames();i+=71)assertEquals(mix.wave().left()[i]*mix.scale(),decoded.left()[i],.00004);
    }
    @Test void actualStereoSumHandlesPhaseCancellationRatherThanAddingTrackEnvelopes() throws Exception {
        var s=song();s.patterns.getFirst().notes.get(1).add(new SongProject.Note(4,2,66,127));String first=s.channels.getFirst().id;var data=wave(s,c->tone(c.id.equals(first)?.5f:-.5f));assertEquals(0,peak(data.columns(0,2,129)));
    }
    @Test void muteVelocityAndGainChangeRealAmplitudeAndAllMutedIsFlat() throws Exception {
        var s=song();var c=s.channels.getFirst();var n=s.patterns.getFirst().notes.getFirst().getFirst();n.velocity=64;c.volume=.5f;assertEquals(.5f*64/127*.5f,peak(wave(s,ch->tone(.5f)).columns(.55,.7,129)),.001);
        c.muted=true;int[] calls={0};var silent=wave(s,ch->{calls[0]++;return tone(1);});assertEquals(0,calls[0]);assertEquals(0,peak(silent.columns(0,2,129)));
    }
    @Test void sampleTrimFadeAndPitchAreVisibleAndPatternModeHasItsOwnDuration() throws Exception {
        var s=song();var c=s.channels.getFirst();c.sound="example:sample";c.trimStart=.2f;c.trimEnd=.8f;c.fadeIn=.2f;c.fadeOut=.1f;c.gain=.4f;var source=tone(.5f);var processed=AudioFiles.effects(source.wave(),c);var mix=AudioFiles.mix(s,ch->new AudioFiles.Source(processed,1,1),0,true,()->false);var data=SongWaveform.from(mix,()->false);assertTrue(peak(data.columns(.5,.52,129))<peak(data.columns(.7,.72,129)));
        s.endPpq=8*SongProject.PPQ;assertEquals(2,SongWaveform.from(AudioFiles.mix(s,ch->tone(.5f),0,true,()->false),()->false).seconds());assertEquals(4,wave(s,ch->tone(.5f)).seconds());
        var base=AudioFiles.mix(s,ch->tone(.5f),0,true,()->false).wave();s.patterns.getFirst().notes.getFirst().getFirst().pitch=78;var higher=AudioFiles.mix(s,ch->tone(.5f),0,true,()->false).wave();assertEquals(base.left()[22050+20],higher.left()[22050+10],.00001);
    }
    @Test void silenceOutsideRangeCancellationAndMasterScaleAreReal() throws Exception {
        var s=song();var mix=AudioFiles.mix(s,ch->tone(2),0,false,()->false);var data=SongWaveform.from(mix,()->false);assertEquals(.98f,peak(data.columns(0,2,129)),.001);assertTrue(data.scale()<1);assertEquals(0,peak(data.columns(-1,-.5,129)));assertEquals(0,peak(data.columns(3,4,129)));
        assertThrows(CancellationException.class,()->AudioFiles.mix(s,ch->tone(.5f),0,false,()->true));assertThrows(CancellationException.class,()->SongWaveform.from(mix,()->true));
    }
}
