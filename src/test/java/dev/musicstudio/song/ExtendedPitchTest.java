package dev.musicstudio.song;

import dev.musicstudio.song.SongProject.*;
import dev.musicstudio.media.*;
import dev.musicstudio.blueprint.MusicBlueprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedPitchTest {
    @TempDir Path temp;
    private static AudioFiles.Source tone(){float[] data=new float[AudioFiles.RATE];for(int i=0;i<data.length;i++)data[i]=(float)(.2*Math.sin(i*2*Math.PI*1000/AudioFiles.RATE));return new AudioFiles.Source(new AudioFiles.Wave(data,data.clone()),1,1);}
    private static SongProject song(int pitch){var s=ArrangementTest.song();s.patterns.getFirst().notes.forEach(List::clear);s.patterns.getFirst().notes.getFirst().add(new Note(0,1,pitch,127));s.endPpq=4*SongProject.PPQ;return s;}
    private static double frequency(AudioFiles.Wave wave){int frames=Math.min(wave.frames(),AudioFiles.RATE/10),cycles=0;for(int i=1;i<frames;i++)if(wave.left()[i-1]<=0&&wave.left()[i]>0)cycles++;return cycles*AudioFiles.RATE/(double)frames;}
    @Test void allMidiKeysAreStrictlyIncreasingAndOctavesRetainTheirRatios(){
        double previous=0;for(int note=0;note<=127;note++){double ratio=MusicalPitch.ratio(note,66,true);assertTrue(ratio>previous);if(note>0)assertEquals(Math.pow(2,1/12.0),ratio/previous,1e-10);previous=ratio;}
        assertEquals(.25,MusicalPitch.ratio(42,66,true));assertEquals(8,MusicalPitch.ratio(102,66,true));assertEquals(1,MusicalPitch.ratio(0,127,false));
        assertFalse(MusicalPitch.requiresSample(54,66,true));assertFalse(MusicalPitch.requiresSample(78,66,true));assertTrue(MusicalPitch.requiresSample(53,66,true));assertTrue(MusicalPitch.requiresSample(79,66,true));
    }
    @Test void mixedPcmUsesActualHighAndLowFrequenciesInsteadOfEndpointPitches() throws Exception {
        for(int pitch:new int[]{30,42,54,66,78,90,102}){var s=song(pitch);var wave=AudioFiles.mix(s,c->tone(),0,false,()->false).wave();assertEquals(1000*MusicalPitch.ratio(pitch,66,true),frequency(wave),11,"MIDI "+pitch);}
    }
    @Test void bakedNaturalNotesKeepDecayWhileEditedDurationsRemainGated(){
        for(int pitch:new int[]{42,90,102}){var s=song(pitch);var c=s.channels.getFirst();var event=s.events(0,false).getFirst();var wave=AudioFiles.noteAudio(tone(),c,event,s.bpm);assertEquals(1/MusicalPitch.ratio(pitch,66,true),wave.seconds(),1.0/AudioFiles.RATE);assertEquals(1000*MusicalPitch.ratio(pitch,66,true),frequency(wave),11);}
        var s=song(42);var e=new Event(0,0,2*SongProject.STEP,42,127);assertEquals(.25,AudioFiles.noteAudio(tone(),s.channels.getFirst(),e,120).seconds(),1.0/AudioFiles.RATE);
    }
    @Test void resourcePitchMultipliesTheFullMusicalRatioAndSampleCropOffset(){
        var s=song(90);var c=s.channels.getFirst();c.sound="example:sample";var source=tone();source=new AudioFiles.Source(source.wave(),1,1.5f);var e=new Event(0,0,SongProject.STEP,90,127,-1,SongProject.STEP);var wave=AudioFiles.noteAudio(source,c,e,120);
        assertEquals(source.wave().left()[(int)(SongProject.STEP*60.0*AudioFiles.RATE/(SongProject.PPQ*120)*6)],wave.left()[0],.00001);assertEquals(6000,frequency(wave),11);
    }
    @Test void buildingUsesCommandsOnlyForOutOfRangeNotesAndBakesTheirActualPitch() throws Exception {
        var s=song(66);s.patterns.getFirst().notes.getFirst().addAll(List.of(new Note(2,1,42,127),new Note(4,1,90,127)));String before=ProjectStore.encode(s);
        var preview=MusicBlueprint.preview(s);assertEquals(2,preview.outputs().stream().filter(MusicBlueprint.Output::command).count());assertEquals(0,preview.pitchLimited());assertEquals("error.blueprint_prepare",assertThrows(StudioException.class,()->MusicBlueprint.compile(s)).key);
        var baked=ProjectStore.copy(s);for(var e:baked.events(0,false)){var c=baked.voice(e.channel());if(BlueprintAudio.needsAudio(c,e))baked.blueprintAudio.put(BlueprintAudio.key(baked,c,e),AudioFiles.prepareNote(temp,baked,tone(),c,e,AudioFiles.codec(temp),temp.resolve("published")));}
        var building=MusicBlueprint.compile(baked);assertEquals(1,building.parts().stream().filter(p->p.block().equals("note_block")).count());var commands=building.parts().stream().filter(p->p.block().equals("command_block")).toList();assertEquals(2,commands.size());assertTrue(commands.stream().allMatch(p->p.command().startsWith("playsound musicstudio:sample/")&&p.command().endsWith("1.000000 1.000000 0")));assertEquals(0,building.pitchLimited());assertEquals(before,ProjectStore.encode(s));
    }
    @Test void wavMp3OggAndMidiRetainExtendedPitch() throws Exception {
        var s=song(90);for(String format:List.of("wav","mp3","ogg")){Path file=temp.resolve("high."+format);AudioFiles.render(s,c->tone(),file,format,AudioFiles.codec(temp));Path decoded=file;if(!format.equals("wav")){decoded=temp.resolve(format+"-decoded.wav");AudioFiles.run(AudioFiles.codec(temp),List.of("-i",file.toString(),decoded.toString()));}assertEquals(4000,frequency(AudioFiles.read(decoded)),20,format);}
        s.patterns.getFirst().notes.getFirst().getFirst().pitch=127;var exported=MidiFiles.exportTracks(s,temp.resolve("midi"));assertEquals(127,MidiFiles.read(exported.getFirst()).song().patterns.getFirst().notes.getFirst().getFirst().pitch);
    }
}
