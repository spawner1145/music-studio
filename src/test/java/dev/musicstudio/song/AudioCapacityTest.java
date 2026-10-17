package dev.musicstudio.song;

import dev.musicstudio.media.*;
import dev.musicstudio.song.SongProject.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioCapacityTest {
    @TempDir Path temp;
    @Test void tenMinuteSourceOver64MiBCanImportSaveReopenAndEditPastTwoMinutes() throws Exception {
        Path input=temp.resolve("long.wav"),projects=temp.resolve("projects"),codec=AudioFiles.codec(temp);
        AudioFiles.run(codec,List.of("-f","lavfi","-i","sine=frequency=330:duration=605:sample_rate=44100","-ac","2","-c:a","pcm_s16le",input.toString()));
        assertTrue(Files.size(input)>64L*1024*1024);var s=ArrangementTest.song();var asset=AudioFiles.importAsset(input,projects,s,codec);assertEquals(605*AudioFiles.RATE,asset.frames);s.assets.add(asset);
        var c=new Channel("Long recording","musicstudio:sample/"+asset.id,0);c.sampleId=asset.id;c.tunable=false;s.audioSources.add(c);s.endPpq=605*2*SongProject.PPQ;s.exportEnd=s.endPpq;s.clips.clear();s.clips.add(Clip.audio(0,0,0,s.endPpq));
        var rendered=AudioFiles.render(s,channel->new AudioFiles.Source(AudioFiles.read(ProjectMedia.wav(projects,s,asset.id)),1,1),temp.resolve("long-export.wav"),"wav",codec);assertEquals(asset.frames,rendered.frames());assertEquals(asset.frames,AudioFiles.frameCount(rendered.file()));
        c.trimStart=121;c.trimEnd=604;c.fadeIn=c.fadeOut=121;s.validate();
        Path file=ProjectFiles.saveAs(temp.resolve("Long.musicstudio"),projects,s);var reopened=ProjectFiles.open(file,temp.resolve("fresh"));assertEquals(asset.frames,reopened.assets.getFirst().frames);assertEquals(604,reopened.audioSources.getFirst().trimEnd);
        assertEquals(-1,Files.mismatch(input,reopened.projectDirectory.resolve("assets").resolve(asset.original)));
        var wave=AudioFiles.read(ProjectMedia.wav(projects,reopened,asset.id));assertEquals(605,wave.seconds());assertNotEquals(0,wave.left()[604*AudioFiles.RATE+20]);
    }
    @Test void mixingAndNoteRenderingHaveNoTenMinuteOrDecodedSourceBudget() throws Exception {
        var s=ArrangementTest.song();s.channels.get(1).muted=true;int ppq=603*2*SongProject.PPQ;s.endPpq=ppq;s.patterns.getFirst().length=ppq;s.patterns.getFirst().notes.getFirst().clear();var note=new Note();note.start=0;note.duration=ppq;note.pitch=66;note.velocity=127;s.patterns.getFirst().notes.getFirst().add(note);s.clips.getFirst().duration=s.clips.getFirst().contentLength=ppq;
        float[] pcm=new float[181*AudioFiles.RATE];pcm[0]=pcm[pcm.length-1]=.5f;var source=new AudioFiles.Source(new AudioFiles.Wave(pcm,pcm),1,1);
        var cached=AudioFiles.cached(c->source);assertSame(source,cached.source(s.channels.getFirst()));
        var rendered=AudioFiles.mix(s,cached,0,false,()->false);assertEquals(603,rendered.wave().seconds());assertEquals(.5f,rendered.wave().left()[pcm.length-1]);
        var individual=AudioFiles.noteAudio(source,s.channels.getFirst(),s.events(0,false).getFirst(),s.bpm);assertEquals(pcm.length,individual.frames());
    }
    @Test void audioAssetCountsAndProjectFileSizeAreNotCapped() throws Exception {
        var s=ArrangementTest.song();for(int i=0;i<140;i++){String hash=String.format(Locale.ROOT,"%064x",i);s.assets.add(new Asset(hash,"Recording "+i,hash+"-source.wav",AudioFiles.RATE*700));var c=new Channel("Audio "+i,"musicstudio:sample/"+hash,0);c.sampleId=hash;s.audioSources.add(c);}s.validate();assertEquals(140,ProjectStore.copy(s).audioSources.size());
        var empty=ArrangementTest.song();Path file=temp.resolve("padded.musicstudio");Files.writeString(file," ".repeat(4*1024*1024+1)+ProjectStore.encode(empty));assertEquals(ProjectStore.encode(empty),ProjectStore.encode(ProjectFiles.open(file,temp.resolve("cache"))));
    }
}
