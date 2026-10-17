package dev.musicstudio.media;

import dev.musicstudio.song.*;
import javax.sound.midi.*;
import java.nio.file.*;
import java.util.*;

/** SMF type 0/1 import, tempo-map flattening into one editor BPM, standard PPQ export. */
public final class MidiFiles {
    private MidiFiles(){}
    public record Imported(SongProject song,int tempoChanges,int ignored){}
    private record Timed(long tick,int order,MidiMessage message,int track){}
    private record Tempo(long tick,long micros,int mpq){}
    private record Started(long tick,int velocity){}
    public static List<Path> exportTracks(SongProject original,Path destination) throws Exception {
        SongProject song=ExportRange.snapshot(original);
        if(song.schemaVersion==2)return exportArrangement(song,destination,!ExportRange.of(original).full());
        song.validate();Files.createDirectories(destination);List<Path> files=new ArrayList<>();List<SongProject.Event> events=song.events(0,false);
        for(int c=0;c<song.channels.size();c++)if(!song.channels.get(c).muted){
            int index=c;Sequence seq=new Sequence(Sequence.PPQ,SongProject.PPQ);Track track=seq.createTrack();meta(track,3,song.channels.get(c).name.getBytes(java.nio.charset.StandardCharsets.UTF_8),0);int mpq=60_000_000/song.bpm;meta(track,0x51,new byte[]{(byte)(mpq>>16),(byte)(mpq>>8),(byte)mpq},0);
            List<SongProject.Event> selected=events.stream().filter(e->e.channel()==index).toList();
            // MIDI listeners process note-offs before subsequent note-ons on the same tick.
            record Message(int tick,boolean on,int pitch,int velocity){}
            List<Message> messages=new ArrayList<>();for(var e:selected){messages.add(new Message(e.start(),true,e.pitch(),e.velocity()));messages.add(new Message(e.start()+e.duration(),false,e.pitch(),0));}
            messages.sort(Comparator.comparingInt(Message::tick).thenComparing(Message::on));for(var m:messages){var msg=new ShortMessage();msg.setMessage(m.on?ShortMessage.NOTE_ON:ShortMessage.NOTE_OFF,0,m.pitch,m.velocity);track.add(new MidiEvent(msg,m.tick));}
            meta(track,0x2f,new byte[0],song.endPpq);String name=String.format(Locale.ROOT,"%02d-%s.mid",c+1,safe(song.channels.get(c).name));Path target=destination.resolve(name),temp=Files.createTempFile(destination,"midi-",".tmp");try{MidiSystem.write(seq,1,temp.toFile());Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}files.add(target);
        }return files;
    }
    private static List<Path> exportArrangement(SongProject song,Path destination,boolean includeEmpty) throws Exception {
        song.validate();Files.createDirectories(destination);List<Path> files=new ArrayList<>();var all=song.events(0,false);
        for(int t=0;t<song.tracks.size();t++){int lane=t;if(song.tracks.get(t).muted)continue;var events=all.stream().filter(e->e.track()==lane&&e.channel()<song.channels.size()).toList();if(events.isEmpty()&&!includeEmpty)continue;
            Sequence sequence=new Sequence(Sequence.PPQ,SongProject.PPQ);Track conductor=sequence.createTrack();int mpq=60_000_000/song.bpm;meta(conductor,3,song.tracks.get(t).name.getBytes(java.nio.charset.StandardCharsets.UTF_8),0);meta(conductor,0x51,new byte[]{(byte)(mpq>>16),(byte)(mpq>>8),(byte)mpq},0);meta(conductor,0x2f,new byte[0],song.endPpq);
            for(int source:events.stream().map(SongProject.Event::channel).distinct().toList()){
                Track track=sequence.createTrack();meta(track,3,song.channels.get(source).name.getBytes(java.nio.charset.StandardCharsets.UTF_8),0);
                record Message(int tick,boolean on,int pitch,int velocity){}List<Message> messages=new ArrayList<>();for(var e:events)if(e.channel()==source){messages.add(new Message(e.start(),true,e.pitch(),e.velocity()));messages.add(new Message(e.start()+e.duration(),false,e.pitch(),0));}
                messages.sort(Comparator.comparingInt(Message::tick).thenComparing(Message::on));for(var m:messages){ShortMessage msg=new ShortMessage();msg.setMessage(m.on?ShortMessage.NOTE_ON:ShortMessage.NOTE_OFF,source%16,m.pitch,m.velocity);track.add(new MidiEvent(msg,m.tick));}meta(track,0x2f,new byte[0],song.endPpq);
            }
            Path target=destination.resolve(String.format(Locale.ROOT,"%02d-%s.mid",t+1,safe(song.tracks.get(t).name))),tmp=Files.createTempFile(destination,"midi-",".tmp");try{MidiSystem.write(sequence,1,tmp.toFile());Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}files.add(target);
        }return files;
    }
    private static String safe(String s){String r=s.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_. -]","_");return r.isBlank()?"Track":r.substring(0,Math.min(60,r.length()));}
    private static void meta(Track t,int type,byte[] data,long tick) throws Exception {var msg=new MetaMessage();msg.setMessage(type,data,data.length);t.add(new MidiEvent(msg,tick));}
    public static Imported read(Path path) throws Exception {
        if(Files.size(path)>8*1024*1024)throw new StudioException("error.midi_size");Sequence midi=MidiSystem.getSequence(path.toFile());if(midi.getDivisionType()!=Sequence.PPQ)throw new StudioException("error.midi_smpte");
        List<Timed> messages=new ArrayList<>();int order=0;var tracks=midi.getTracks();for(int i=0;i<tracks.length;i++)for(int j=0;j<tracks[i].size();j++){MidiEvent event=tracks[i].get(j);messages.add(new Timed(event.getTick(),order++,event.getMessage(),i));if(messages.size()>262144)throw new StudioException("error.midi_size");}messages.sort(Comparator.comparingLong(Timed::tick).thenComparingInt(Timed::order));
        List<Tempo> tempos=new ArrayList<>(List.of(new Tempo(0,0,500000)));int changes=0;for(var event:messages)if(event.message instanceof MetaMessage meta&&meta.getType()==0x51&&meta.getData().length==3){byte[] b=meta.getData();int mpq=(b[0]&255)<<16|(b[1]&255)<<8|(b[2]&255);if(mpq<=0)continue;Tempo before=tempos.getLast();long us=before.micros+(event.tick-before.tick)*before.mpq/midi.getResolution();tempos.add(new Tempo(event.tick,us,mpq));if(event.tick>0)changes++;}
        SongProject song=new SongProject();song.title=path.getFileName().toString().replaceFirst("(?i)\\.midi?$","");song.title=song.title.substring(0,Math.min(80,song.title.length()));song.bpm=Math.max(20,Math.min(400,Math.round(60_000_000f/tempos.stream().filter(t->t.tick==0).reduce((a,b)->b).orElse(tempos.getFirst()).mpq)));song.loop=false;
        Map<String,Integer> channelMap=new LinkedHashMap<>();Map<String,ArrayDeque<Started>> active=new HashMap<>();Map<Integer,String> names=new HashMap<>();Map<Integer,Integer> programs=new HashMap<>();List<List<SongProject.Note>> notes=new ArrayList<>();int ignored=0,count=0;long maxTick=0;
        for(var e:messages){maxTick=Math.max(maxTick,e.tick);if(e.message instanceof MetaMessage meta&&meta.getType()==3){names.put(e.track,new String(meta.getData(),java.nio.charset.StandardCharsets.UTF_8));continue;}if(!(e.message instanceof ShortMessage m))continue;int midiChannel=m.getChannel();if(m.getCommand()==ShortMessage.PROGRAM_CHANGE){programs.put(e.track*16+midiChannel,m.getData1());continue;}
            if(m.getCommand()!=ShortMessage.NOTE_ON&&m.getCommand()!=ShortMessage.NOTE_OFF){if(m.getCommand()==ShortMessage.CONTROL_CHANGE||m.getCommand()==ShortMessage.PITCH_BEND)ignored++;continue;}
            String group=e.track+":"+midiChannel,key=group+":"+m.getData1();boolean on=m.getCommand()==ShortMessage.NOTE_ON&&m.getData2()>0;
            if(on){if(!channelMap.containsKey(group)){if(song.channels.size()>=32)throw new StudioException("error.midi_tracks");String instrument=map(programs.getOrDefault(e.track*16+midiChannel,0),midiChannel,m.getData1());var c=new SongProject.Channel(names.getOrDefault(e.track,"MIDI "+(e.track+1))+" / "+(midiChannel+1),"minecraft:block.note_block."+instrument,0x577e87+(song.channels.size()*0x070503)%0x303030);c.name=c.name.substring(0,Math.min(80,c.name.length()));c.instrument=instrument;c.baseMidi=66;channelMap.put(group,song.channels.size());song.channels.add(c);notes.add(new ArrayList<>());}active.computeIfAbsent(key,k->new ArrayDeque<>()).add(new Started(e.tick,m.getData2()));}
            else {var queue=active.get(key);if(queue==null||queue.isEmpty())continue;var start=queue.removeFirst();int a=position(start.tick,tempos,midi.getResolution(),song.bpm),b=Math.max(a+1,position(e.tick,tempos,midi.getResolution(),song.bpm));var n=new SongProject.Note();n.start=a;n.duration=b-a;n.pitch=m.getData1();n.velocity=start.velocity;notes.get(channelMap.get(group)).add(n);if(++count>SongProject.MAX_EVENTS)throw new StudioException("error.note_limit");}
        }
        if(song.channels.isEmpty()||count==0)throw new StudioException("error.midi_empty");int end=Math.max(4*SongProject.PPQ,position(maxTick,tempos,midi.getResolution(),song.bpm));for(var list:notes)for(var n:list)end=Math.max(end,n.start+n.duration);end=(int)(((long)end+4*SongProject.PPQ-1)/(4*SongProject.PPQ)*(4*SongProject.PPQ));if(end>SongProject.MAX_SONG_PPQ)throw new StudioException("error.song_length");song.endPpq=end;
        var pattern=new SongProject.Pattern("Imported MIDI",song.channels.size());pattern.length=end;pattern.notes=notes;song.patterns.add(pattern);song.schemaVersion=2;song.tracks.add(new SongProject.Track("MIDI",0x577e87));song.clips.add(SongProject.Clip.pattern(0,0,0,end));song.growPattern(0);song.validate();return new Imported(song,changes,ignored+(int)active.values().stream().mapToLong(ArrayDeque::size).sum());
    }
    private static int position(long tick,List<Tempo> tempos,int resolution,int bpm){Tempo tempo=tempos.getFirst();for(var t:tempos){if(t.tick>tick)break;tempo=t;}double us=tempo.micros+(tick-tempo.tick)*(double)tempo.mpq/resolution;double ppq=us*bpm*SongProject.PPQ/60_000_000.0;if(ppq>SongProject.MAX_SONG_PPQ)throw new StudioException("error.song_length");return (int)Math.round(ppq);}
    private static String map(int program,int channel,int pitch){if(channel==9)return pitch<40?"basedrum":pitch<46?"snare":"hat";if(program<8)return "harp";if(program<16)return "bell";if(program<24)return "pling";if(program<32)return "guitar";if(program<40)return "bass";if(program<56)return "harp";if(program<72)return "didgeridoo";if(program<80)return "flute";if(program<104)return "bit";return "bell";}
}
