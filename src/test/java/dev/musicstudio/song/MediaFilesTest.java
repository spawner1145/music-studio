package dev.musicstudio.song;

import dev.musicstudio.media.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.midi.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaFilesTest {
    @TempDir Path temp;
    private static SongProject tune(){var s=SongProject.legacyDemo();s.channels=new ArrayList<>(s.channels.subList(0,2));s.channels.forEach(c->{c.volume=1;c.baseMidi=66;});s.channels.get(1).muted=true;s.patterns.clear();var p=new SongProject.Pattern("Test",2);p.notes.getFirst().add(new SongProject.Note(0,4,66,127));p.notes.get(1).add(new SongProject.Note(0,4,70,127));s.patterns.add(p);s.clips.clear();s.clips.add(new SongProject.Clip(0,0,0,1));s.clips.add(new SongProject.Clip(1,0,0,1));s.endPpq=4*SongProject.PPQ;return s;}
    private static AudioFiles.Wave tone(){float[] l=new float[44100],r=new float[44100];for(int i=0;i<l.length;i++)l[i]=r[i]=(float)(.5*Math.sin(i*2*Math.PI*440/44100));return new AudioFiles.Wave(l,r);}
    @Test void midiTracksExcludeMutedAndRoundTripPitchVelocityLength() throws Exception {
        var song=tune();var files=MidiFiles.exportTracks(song,temp.resolve("midi"));assertEquals(1,files.size());var loaded=MidiFiles.read(files.getFirst()).song();assertEquals(1,loaded.channels.size());var n=loaded.patterns.getFirst().notes.getFirst().getFirst();assertEquals(66,n.pitch);assertEquals(127,n.velocity);assertEquals(0,n.start);assertEquals(SongProject.PPQ,n.duration);assertEquals(120,loaded.bpm);
    }
    @Test void midiTempoMapPreservesAbsoluteTimingAndZeroVelocityIsNoteOff() throws Exception {
        Sequence midi=new Sequence(Sequence.PPQ,480);Track track=midi.createTrack();MetaMessage tempo=new MetaMessage();tempo.setMessage(0x51,new byte[]{0x0f,0x42,0x40},3);track.add(new MidiEvent(tempo,480));ShortMessage on=new ShortMessage();on.setMessage(0x90,60,91);track.add(new MidiEvent(on,0));ShortMessage off=new ShortMessage();off.setMessage(0x90,60,0);track.add(new MidiEvent(off,960));Path file=temp.resolve("tempo.mid");MidiSystem.write(midi,1,file.toFile());var result=MidiFiles.read(file);assertEquals(1,result.tempoChanges());var n=result.song().patterns.getFirst().notes.getFirst().getFirst();assertEquals(2880,n.duration);assertEquals(91,n.velocity);
    }
    @Test void sampleTrimAndFadesPreserveOriginalAndApplyGain() throws Exception {
        var input=tone();float[] original=input.left().clone();var c=tune().channels.getFirst();c.trimStart=.2f;c.trimEnd=.8f;c.fadeIn=.1f;c.fadeOut=.1f;c.gain=.5f;var output=AudioFiles.effects(input,c);assertEquals(26460,output.frames());assertEquals(0,output.left()[0],1e-7);assertEquals(0,output.left()[output.frames()-1],1e-7);float peak=0;for(float v:output.left())peak=Math.max(peak,Math.abs(v));assertEquals(.25,peak,.001);assertArrayEquals(original,input.left());
    }
    @Test void wavMp3AndOggAreRealDecodableAudioAndMuteIsRespected() throws Exception {
        var song=tune();Path codec=AudioFiles.codec(temp);for(String format:List.of("wav","mp3","ogg")){
            Path output=temp.resolve("mix."+format);int[] calls={0};var rendered=AudioFiles.render(song,c->{calls[0]++;return new AudioFiles.Source(tone(),1,1);},output,format,codec);assertEquals(1,calls[0]);assertEquals(22050,rendered.frames());assertEquals(1,rendered.scale());assertTrue(Files.size(output)>1000);
            Path decoded=temp.resolve("decoded-"+format+".wav");AudioFiles.run(codec,List.of("-i",output.toString(),"-ar","44100","-ac","2","-c:a","pcm_s16le",decoded.toString()));var wave=AudioFiles.read(decoded);assertEquals(.5,wave.seconds(),.08);float peak=0;for(int i=0;i<Math.min(22050,wave.frames());i++)peak=Math.max(peak,Math.abs(wave.left()[i]));assertTrue(peak>.4&&peak<.6);
        }
    }
    @Test void importedSamplesStayWithinProjectAndPersistWithEffects() throws Exception {
        var song=tune();Path source=temp.resolve("素材.wav");AudioFiles.wav(source,tone(),1);byte[] original=Files.readAllBytes(source);Path projects=temp.resolve("projects");var asset=AudioFiles.importAsset(source,projects,song,AudioFiles.codec(temp));song.assets.add(asset);var c=song.channels.getFirst();c.sampleId=asset.id;c.fadeIn=.2f;String id=AudioFiles.prepareSample(projects,song,c,AudioFiles.codec(temp),temp.resolve("published"));c.renderedAudio=id;assertArrayEquals(original,Files.readAllBytes(ProjectMedia.assets(projects,song).resolve(asset.original)));assertEquals(64,id.length());new ProjectStore(projects).save("test",song);var loaded=new ProjectStore(projects).load("test");assertEquals(.2f,loaded.channels.getFirst().fadeIn);assertTrue(Files.isRegularFile(ProjectMedia.folder(projects,song).resolve("project.json")));assertTrue(Files.isRegularFile(ProjectMedia.wav(projects,loaded,asset.id)));
    }
    @Test void projectFolderCannotEscapeAndInvalidSamplesAreRejected(){var s=tune();s.projectId="../../outside";assertTrue(ProjectMedia.folder(temp,s).startsWith(temp.toAbsolutePath()));s.channels.getFirst().sampleId="../../audio";assertThrows(StudioException.class,s::validate);}
    @Test void masterLimiterPreventsOverflowWithoutDroppingNotes() throws Exception {var song=tune();song.channels.get(1).muted=false;var result=AudioFiles.render(song,c->new AudioFiles.Source(tone(),2,1),temp.resolve("limited.wav"),"wav",AudioFiles.codec(temp));assertTrue(result.peak()>1&&result.scale()<1);var wave=AudioFiles.read(result.file());float peak=0;for(float x:wave.left())peak=Math.max(peak,Math.abs(x));assertTrue(peak<=.981f&&peak>.9f);}
}
