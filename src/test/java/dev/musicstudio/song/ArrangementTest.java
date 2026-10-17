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

class ArrangementTest {
    @TempDir Path temp;
    static final int BAR=4*SongProject.PPQ;
    static SongProject song(){
        var s=new SongProject();s.schemaVersion=2;s.endPpq=4*BAR;s.loop=false;
        var harp=new Channel("Harp","minecraft:block.note_block.harp",0x577e87);harp.instrument="harp";harp.volume=1;
        var bass=new Channel("Bass","minecraft:block.note_block.bass",0x876d80);bass.instrument="bass";bass.volume=1;
        s.channels.addAll(List.of(harp,bass));s.tracks.add(new SongProject.Track("Arrangement",0x577e87));s.tracks.add(new SongProject.Track("Other lane",0x876d80));
        var p=new Pattern("Two instruments",2);p.length=BAR;p.notes.get(0).add(new Note(0,1,66,127));p.notes.get(1).add(new Note(4,1,69,127));s.patterns.add(p);
        s.clips.add(Clip.pattern(0,0,0,BAR));return s;
    }
    static String times(List<Event> events){return events.stream().map(e->e.channel()+":"+e.start()+":"+e.duration()+":"+e.pitch()+":"+e.velocity()+":"+e.offset()).sorted().toList().toString();}
    static Channel audio(SongProject s){String id="a".repeat(64);s.assets.add(new Asset(id,"Recording",id+"-source.wav",88200));var c=new Channel("Recording","musicstudio:sample/"+id,0x7d7297);c.sampleId=id;c.tunable=false;c.volume=1;s.audioSources.add(c);return c;}
    @Test void onePatternInstanceIncludesEverySourceAndCanBeRepeatedOnAnyLane(){
        var s=song();s.clips.add(Clip.pattern(1,0,2*BAR,BAR));assertEquals(4,s.events(0,false).size());
        s.patterns.getFirst().notes.get(1).add(new Note(8,1,72,127));var events=s.events(0,false);assertEquals(6,events.size());assertEquals(3,events.stream().filter(e->e.track()==0).count());assertEquals(3,events.stream().filter(e->e.track()==1).count());
        s.clips.getFirst().track=1;assertEquals(6,s.events(0,false).stream().filter(e->e.track()==1).count());s.validate();
    }
    @Test void oneLaneMixesDifferentPatternsAndIndependentAudioWithoutPatternMutation(){
        var s=song();var second=new Pattern("Other melody",2);second.length=BAR;second.notes.get(0).add(new Note(1,1,70,127));s.patterns.add(second);s.clips.add(Clip.pattern(0,1,BAR,BAR));audio(s);s.clips.add(Clip.audio(0,0,2*BAR,BAR));
        var before=ProjectStore.GSON.toJson(s.patterns);assertEquals(4,s.events(0,false).size());assertEquals(1,s.events(0,false).stream().filter(e->e.channel()==2).count());
        s.audioSources.getFirst().gain=.5f;assertEquals(before,ProjectStore.GSON.toJson(s.patterns));assertEquals(2,s.channels.size());assertEquals(2,s.patterns.size());s.validate();
    }
    @Test void sourceDeletionDoesNotDeletePatternInstancesOrPlaylistLanes(){
        var s=song();audio(s);s.clips.add(Clip.audio(1,0,BAR,BAR));s.removeChannel(0);s.validate();assertEquals(2,s.clips.size());assertEquals(2,s.tracks.size());assertEquals(1,s.patterns.getFirst().notes.size());assertEquals(2,s.events(0,false).size());
        assertSame(s.audioSources.getFirst(),s.voice(1));
    }
    @Test void deletingLaneDoesNotDeleteInstrumentsPatternsOrAudioAssets(){
        var s=song();audio(s);s.clips.add(Clip.audio(1,0,BAR,BAR));s.removeTrack(0);s.validate();assertEquals(2,s.channels.size());assertEquals(1,s.patterns.size());assertEquals(1,s.audioSources.size());assertEquals(1,s.clips.size());assertEquals(0,s.clips.getFirst().track);
    }
    @Test void trackMuteAndSourceMuteAreIndependentAndPatternModeIgnoresPlaylistMute(){
        var s=song();audio(s);s.clips.add(Clip.audio(0,0,BAR,BAR));s.tracks.getFirst().muted=true;assertTrue(s.events(0,false).isEmpty());assertEquals(2,s.events(0,true).size());
        s.tracks.getFirst().muted=false;s.channels.getFirst().muted=true;assertEquals(2,s.events(0,false).size());s.audioSources.getFirst().muted=true;assertEquals(1,s.events(0,false).size());
    }
    @Test void patternPaddingRetainsAllSourcePositionsAndDoesNotCreateRepeats(){
        var s=song();var c=s.clips.getFirst();c.start=BAR;var expected=times(s.events(0,false));c.resize(0,4*BAR);assertEquals(expected,times(s.events(0,false)));assertEquals(2,s.events(0,false).size());
        c.resize(BAR+SongProject.PPQ,4*BAR);assertEquals(1,s.events(0,false).size());c.resize(0,4*BAR);assertEquals(expected,times(s.events(0,false)));s.validate();
    }
    @Test void trimmedAudioReadsActualSourceOffsetAndPaddedTailStaysSilent() throws Exception {
        var s=song();s.clips.clear();audio(s);var c=Clip.audio(0,0,0,BAR);s.clips.add(c);c.resize(SongProject.PPQ,BAR);c.start=0;c.resize(0,2*BAR);s.endPpq=2*BAR;
        var event=s.events(0,false).getFirst();assertEquals(SongProject.PPQ,event.offset());assertEquals(0,event.start());assertEquals(3*SongProject.PPQ,event.duration());
        float[] pcm=new float[88200];Arrays.fill(pcm,0,44100,.1f);Arrays.fill(pcm,44100,88200,.7f);
        var mix=AudioFiles.mix(s,source->new AudioFiles.Source(new AudioFiles.Wave(pcm,pcm.clone()),1,1),0,false,()->false);
        assertEquals(.1f,mix.wave().left()[0],.0001);assertEquals(.7f,mix.wave().left()[22050],.0001);assertEquals(0,mix.wave().left()[66150],.0001);assertEquals(4*44100,mix.wave().frames());
        var crop=AudioFiles.noteAudio(new AudioFiles.Source(new AudioFiles.Wave(pcm,pcm.clone()),1,1),s.audioSources.getFirst(),event,s.bpm);assertEquals(66150,crop.frames());assertEquals(.7f,crop.left()[22050],.0001);
    }
    @Test void midiControlledSamplerStaysInsideTheSharedPattern(){
        var s=song();var sample=audio(s);s.audioSources.clear();s.channels.add(sample);s.patterns.forEach(p->{p.notes.add(new ArrayList<>());p.notes.get(2).add(new Note(2,4,78,95));});s.validate();assertEquals(3,s.events(0,false).size());assertEquals(1,s.patterns.size());assertEquals(1,s.clips.size());assertTrue(s.audioSources.isEmpty());
    }
    @Test void legacyMigrationPreservesEveryEventAndLeavesEachClipAsAWholePattern(){
        var s=SongProject.legacyDemo();s.clips.get(0).resize(0,3*BAR);var before=times(s.events(0,false));int count=s.patterns.stream().flatMap(p->p.notes.stream()).mapToInt(List::size).sum();s.upgrade();s.validate();
        assertEquals(before,times(s.events(0,false)));assertEquals(count,s.patterns.stream().flatMap(p->p.notes.stream()).mapToInt(List::size).sum());assertEquals(8,s.tracks.size());assertEquals(2,s.schemaVersion);assertEquals(ProjectStore.encode(s),ProjectStore.encode(ProjectStore.copy(s)));
        String stable=ProjectStore.encode(s);s.upgrade();assertEquals(stable,ProjectStore.encode(s));
    }
    @Test void mixedClipboardPreservesLaneIdsAudioReferencesLinkedPatternsAndPadding(){
        var s=song();s.endPpq=6*BAR;audio(s);var audio=Clip.audio(1,0,BAR,BAR);s.clips.add(audio);audio.resize(BAR,2*BAR+SongProject.PPQ);
        var clipboard=EditorClipboard.copyClips(s,new HashSet<>(s.clips.stream().map(c->c.id).toList()));var ids=clipboard.pasteClips(s,2*BAR);assertEquals(2,ids.size());var pasted=s.clips.stream().filter(c->ids.contains(c.id)&&c.audio()).findFirst().orElseThrow();assertEquals(1,pasted.track);assertEquals(0,pasted.audioSource);assertEquals(audio.duration,pasted.duration);assertEquals(BAR,pasted.contentLength);s.validate();
    }
    @Test void midiExportsPerPlaylistLaneWithMultipleSourcePartsAndImportsOnePattern() throws Exception {
        var s=song();s.patterns.getFirst().notes.get(0).add(new Note(8,1,72,127));audio(s);s.clips.add(Clip.audio(0,0,BAR,BAR));
        var files=MidiFiles.exportTracks(s,temp);assertEquals(1,files.size());var midi=MidiSystem.getSequence(files.getFirst().toFile());assertEquals(3,midi.getTracks().length);assertEquals(2*BAR,midi.getTickLength());var imported=MidiFiles.read(files.getFirst()).song();assertEquals(2,imported.channels.size());assertEquals(1,imported.patterns.size());assertEquals(1,imported.clips.size());assertEquals(3,imported.events(0,false).size());
        s.tracks.getFirst().muted=true;assertTrue(MidiFiles.exportTracks(s,temp.resolve("muted")).isEmpty());
    }
    @Test void nativeBuildingMixesSourcesAndMuteStillExcludesWholeLanes(){
        var s=song();var b=MusicBlueprint.compile(s);assertEquals(2,b.notes().size());assertEquals(2,b.outputs().stream().filter(o->!o.command()).count());audio(s);s.commandSamples=true;s.clips.add(Clip.audio(1,0,BAR,BAR));s.tracks.get(1).muted=true;assertEquals(2,MusicBlueprint.compile(s).notes().size());s.tracks.get(1).muted=false;for(var e:s.events(0,false))if(e.channel()>=s.channels.size())s.blueprintAudio.put(BlueprintAudio.key(s,s.voice(e.channel()),e),"b".repeat(64));b=MusicBlueprint.compile(s);assertEquals(3,b.notes().size());assertEquals(1,b.outputs().stream().filter(MusicBlueprint.Output::command).count());
    }
    @Test void mixedPortableProjectPreservesSourcesPatternsLanesAndActualAssets() throws Exception {
        var s=song();float[] pcm=new float[4410];Arrays.fill(pcm,.2f);Path projects=temp.resolve("projects"),file=temp.resolve("song.musicstudio"),wav=temp.resolve("recording.wav");AudioFiles.wav(wav,new AudioFiles.Wave(pcm,pcm.clone()),1);var asset=AudioFiles.importAsset(wav,projects,s,AudioFiles.codec(temp));s.assets.add(asset);var c=new Channel("Audio","musicstudio:sample/"+asset.id,0x123456);c.sampleId=asset.id;s.audioSources.add(c);s.clips.add(Clip.audio(0,0,BAR,s.audioLength(c)));ProjectFiles.save(file,projects,s);var loaded=ProjectFiles.open(file,temp.resolve("other"));assertEquals(ProjectStore.encode(s),ProjectStore.encode(loaded));assertEquals(4410,AudioFiles.read(ProjectMedia.wav(temp.resolve("other"),loaded,asset.id)).frames());
    }
}
