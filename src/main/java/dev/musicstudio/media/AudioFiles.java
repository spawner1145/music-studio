package dev.musicstudio.media;

import dev.musicstudio.song.*;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Offline PCM mixing; codecs run as an interruptible external process without a shell. */
public final class AudioFiles {
    public static final int RATE=44100;
    public record Wave(float[] left,float[] right){public int frames(){return left.length;}public double seconds(){return frames()/(double)RATE;}}
    public record Source(Wave wave,float volume,float pitch){}
    public interface Provider {Source source(SongProject.Channel channel) throws Exception;}
    public record Rendered(Path file,float peak,float scale,int frames){}
    public record Mix(Wave wave,float peak,float scale){}
    private AudioFiles(){}
    public static Path codec(Path game){
        String override=System.getProperty("musicstudio.ffmpeg","");Path path=override.isBlank()?game.resolve("musicstudio/tools/"+(System.getProperty("os.name").startsWith("Windows")?"ffmpeg.exe":"ffmpeg")):Path.of(override);
        if(!Files.isRegularFile(path))throw new StudioException("error.codec_missing");return path.toAbsolutePath();
    }
    public static void run(Path codec,List<String> arguments) throws Exception {
        List<String> command=new ArrayList<>(List.of(codec.toString(),"-nostdin","-hide_banner","-loglevel","error","-y"));command.addAll(arguments);Path log=Files.createTempFile("musicstudio-codec-",".log");Process process=null;
        try{process=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(log.toFile()).start();process.waitFor();if(process.exitValue()!=0){String message=Files.readString(log);throw new StudioException("error.codec_failed",message.substring(0,Math.min(160,message.length())));}}
        finally{if(process!=null&&process.isAlive())process.destroyForcibly();Files.deleteIfExists(log);}
    }
    public static SongProject.Asset importAsset(Path file,Path projects,SongProject song,Path codec) throws Exception {
        if(!Files.isRegularFile(file))throw new StudioException("error.audio_missing");String extension=file.getFileName().toString().replaceFirst("^.*\\.","").toLowerCase(Locale.ROOT);
        if(!Set.of("wav","ogg","mp3","flac","aif","aiff","m4a").contains(extension))throw new StudioException("error.audio_format");String id=ProjectMedia.hash(file);Path dir=ProjectMedia.assets(projects,song);Files.createDirectories(dir);Path original=dir.resolve(id+"-source."+extension),wav=dir.resolve(id+".wav");
        if(!Files.exists(wav)){Path temp=Files.createTempFile(dir,"decode-",".wav");try{run(codec,List.of("-i",file.toAbsolutePath().toString(),"-vn","-ar","44100","-ac","2","-c:a","pcm_s16le",temp.toString()));if(frameCount(temp)==0)throw new StudioException("error.audio_empty");Files.copy(file,original,StandardCopyOption.REPLACE_EXISTING);Files.move(temp,wav,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
        int frames=frameCount(wav);if(frames==0)throw new StudioException("error.audio_empty");String name=file.getFileName().toString();return new SongProject.Asset(id,name.substring(0,Math.min(name.length(),160)),original.getFileName().toString(),frames);
    }
    public static int frameCount(Path wav) throws Exception {
        try(var input=AudioSystem.getAudioInputStream(wav.toFile())){long frames=input.getFrameLength();if(frames>=0&&input.getFormat().getSampleRate()==RATE)return Math.toIntExact(frames);}
        return read(wav).frames();
    }
    public static Wave read(Path wav) throws Exception {
        try(var input=AudioSystem.getAudioInputStream(wav.toFile())){var format=new AudioFormat(RATE,16,2,true,false);try(var pcm=AudioSystem.getAudioInputStream(format,input)){
            long known=pcm.getFrameLength();int capacity=known<0?16384:Math.toIntExact(known);float[] l=new float[capacity],r=new float[capacity];byte[] bytes=new byte[65536];int count,frames=0;
            while((count=pcm.read(bytes))!=-1){checkCancelled(()->false);if(count==0)continue;int added=count/4,needed=Math.addExact(frames,added);if(needed>l.length){int size=(int)Math.min(Integer.MAX_VALUE-8L,Math.max(needed,l.length+Math.max(16384L,l.length/2L)));l=Arrays.copyOf(l,size);r=Arrays.copyOf(r,size);}ByteBuffer b=ByteBuffer.wrap(bytes,0,count).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<added;i++){l[frames]=b.getShort()/32768f;r[frames++]=b.getShort()/32768f;}}
            return new Wave(frames==l.length?l:Arrays.copyOf(l,frames),frames==r.length?r:Arrays.copyOf(r,frames));
        }}
    }
    public static Wave effects(Wave input,SongProject.Channel c){
        int a=Math.max(0,Math.min(input.frames()-1,Math.round(c.trimStart*RATE))),b=c.trimEnd==0?input.frames():Math.max(a+1,Math.min(input.frames(),Math.round(c.trimEnd*RATE)));int n=b-a;float[] l=new float[n],r=new float[n];
        float fadeIn=Math.min(n,c.fadeIn*RATE),fadeOut=Math.min(n,c.fadeOut*RATE);for(int i=0;i<n;i++){float envelope=c.gain;if(fadeIn>0)envelope*=Math.min(1,i/fadeIn);if(fadeOut>0)envelope*=Math.min(1,(n-1-i)/fadeOut);l[i]=clamp(input.left[a+i]*envelope);r[i]=clamp(input.right[a+i]*envelope);}return new Wave(l,r);
    }
    /** Matches the existing offline mix: resample pitch, gate at note-off, retain natural decay. */
    public static Wave noteAudio(Source source,SongProject.Channel c,SongProject.Event event,int bpm){
        double pitch=source.pitch*MusicalPitch.ratio(event.pitch(),c.baseMidi,c.tunable);
        boolean natural=BlueprintAudio.noteBlock(c)&&event.duration()==SongProject.VANILLA_NOTE_DURATION&&event.offset()==0;
        double seconds=natural?source.wave.frames()/(pitch*RATE):event.duration()*60.0/(SongProject.PPQ*bpm);
        long duration=Math.round(seconds*RATE);
        double offset=event.offset()*60.0*RATE/(SongProject.PPQ*bpm)*pitch;
        int frames=Math.toIntExact(Math.max(1,Math.min(duration,(long)Math.ceil((source.wave.frames()-offset)/pitch))));float[] l=new float[frames],r=new float[frames];
        for(int i=0;i<frames&&offset+i*pitch<source.wave.frames();i++){double at=offset+i*pitch;int a=Math.min(source.wave.frames()-1,(int)at),b=Math.min(source.wave.frames()-1,a+1);float fraction=(float)(at-a);l[i]=clamp((source.wave.left[a]*(1-fraction)+source.wave.left[b]*fraction)*source.volume);r[i]=clamp((source.wave.right[a]*(1-fraction)+source.wave.right[b]*fraction)*source.volume);}
        return new Wave(l,r);
    }
    public static String prepareNote(Path projects,SongProject song,Source source,SongProject.Channel c,SongProject.Event event,Path codec,Path published) throws Exception {
        Path dir=ProjectMedia.assets(projects,song);Files.createDirectories(dir);Path wav=Files.createTempFile(dir,"note-",".wav");
        try{wav(wav,noteAudio(source,c,event,song.bpm),1);String key=ProjectMedia.hash(wav);Path ogg=dir.resolve("note-"+key+".ogg");
            if(!Files.exists(ogg)){Path temp=Files.createTempFile(dir,"note-",".ogg");try{run(codec,List.of("-i",wav.toString(),"-c:a","libvorbis","-q:a","5",temp.toString()));Files.move(temp,ogg,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
            String hash=ProjectMedia.hash(ogg);Files.createDirectories(published);Path target=published.resolve(hash+".ogg");if(!Files.exists(target))Files.copy(ogg,target);return hash;
        }finally{Files.deleteIfExists(wav);}
    }
    private static float clamp(float x){return Math.max(-1,Math.min(1,x));}
    public static void wav(Path output,Wave wave,float scale) throws Exception {
        Files.createDirectories(output.toAbsolutePath().getParent());int size=wave.frames()*4;try(var out=new BufferedOutputStream(Files.newOutputStream(output))){ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);h.put("RIFF".getBytes()).putInt(36+size).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)2).putInt(RATE).putInt(RATE*4).putShort((short)4).putShort((short)16).put("data".getBytes()).putInt(size);out.write(h.array());ByteBuffer block=ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<wave.frames();i++){if(block.remaining()<4){out.write(block.array(),0,block.position());block.clear();}block.putShort((short)Math.round(clamp(wave.left[i]*scale)*32767));block.putShort((short)Math.round(clamp(wave.right[i]*scale)*32767));}out.write(block.array(),0,block.position());}
    }
    public static Mix mix(SongProject song,Provider provider,int pattern,boolean patternOnly,java.util.function.BooleanSupplier cancelled) throws Exception {
        return mixWindow(song,provider,pattern,patternOnly,0,patternOnly?song.patterns.get(pattern).length:song.endPpq,cancelled);
    }
    public static Provider cached(Provider provider){
        Map<String,Source> sources=new HashMap<>();
        return c->{Source source=sources.get(c.id);if(source==null){source=provider.source(c);sources.put(c.id,source);}return source;};
    }
    /** Decoded source duration, pitch, note gate and crop offset determine the last audible frame. */
    public static int fullEndPpq(SongProject song,Provider provider) throws Exception {
        return fullEndPpq(song,provider,false);
    }
    public static int blueprintEndPpq(SongProject song,Provider provider) throws Exception {
        return fullEndPpq(song,provider,true);
    }
    private static int fullEndPpq(SongProject song,Provider provider,boolean rawCommands) throws Exception {
        song.validate();provider=cached(provider);Map<String,Integer> sourceEnds=new HashMap<>();double unit=60.0*RATE/(SongProject.PPQ*song.bpm);long end=0;
        for(var e:song.events(0,false)){
            checkCancelled(()->false);
            var c=song.voice(e.channel());if(c.volume==0)continue;Source source=provider.source(c);
            float volume=c.volume*e.velocity()/127f*source.volume;if(volume==0)continue;
            Integer sourceEnd=sourceEnds.get(c.id);if(sourceEnd==null){sourceEnd=source.wave.frames();while(sourceEnd>0&&source.wave.left[sourceEnd-1]==0&&source.wave.right[sourceEnd-1]==0)sourceEnd--;sourceEnds.put(c.id,sourceEnd);}
            double pitch=source.pitch*MusicalPitch.ratio(e.pitch(),c.baseMidi,c.tunable),offset=e.offset()*unit*pitch;
            boolean natural=BlueprintAudio.noteBlock(c)&&e.duration()==SongProject.VANILLA_NOTE_DURATION&&e.offset()==0||rawCommands&&!BlueprintAudio.noteBlock(c)&&!BlueprintAudio.needsAudio(c,e);
            long n=Math.max(0,(long)Math.ceil((sourceEnd-offset)/pitch));if(!natural)n=Math.min(n,Math.round(e.duration()*unit));
            while(n>0){if((n&16383)==0)checkCancelled(()->false);double at=offset+(n-1)*pitch;int a=Math.min(source.wave.frames()-1,(int)at),b=Math.min(source.wave.frames()-1,a+1);float f=(float)(at-a);
                if(Math.round(clamp((source.wave.left[a]*(1-f)+source.wave.left[b]*f)*volume)*32767)!=0||Math.round(clamp((source.wave.right[a]*(1-f)+source.wave.right[b]*f)*volume)*32767)!=0)break;n--;}
            if(n>0)end=Math.max(end,Math.round(e.start()*unit)+n);
        }
        return (int)Math.max(1,Math.min(SongProject.MAX_SONG_PPQ,Math.ceil(end/unit)));
    }
    public static Mix mixFullExport(SongProject song,Provider provider) throws Exception {
        Provider sources=cached(provider);int end=fullEndPpq(song,sources);
        Mix mixed=mixWindow(song,sources,0,false,0,end,()->false,true);Wave wave=mixed.wave;int frames=wave.frames();
        while(frames>1&&Math.round(clamp(wave.left[frames-1]*mixed.scale)*32767)==0&&Math.round(clamp(wave.right[frames-1]*mixed.scale)*32767)==0)frames--;
        return frames==wave.frames()?mixed:new Mix(new Wave(Arrays.copyOf(wave.left,frames),Arrays.copyOf(wave.right,frames)),mixed.peak,mixed.scale);
    }
    /** Mix only the selected PCM window, including tails that started before its left boundary. */
    public static Mix mixWindow(SongProject song,Provider provider,int pattern,boolean patternOnly,int from,int to,java.util.function.BooleanSupplier cancelled) throws Exception {
        return mixWindow(song,provider,pattern,patternOnly,from,to,cancelled,false);
    }
    private static Mix mixWindow(SongProject song,Provider provider,int pattern,boolean patternOnly,int from,int to,java.util.function.BooleanSupplier cancelled,boolean fullTail) throws Exception {
        song.validate();if(from<0||to<=from||to>SongProject.MAX_SONG_PPQ)throw new StudioException("error.export_range");
        var events=song.events(pattern,patternOnly);int period=fullTail?to:patternOnly?song.patterns.get(pattern).length:song.endPpq;double unit=60.0*RATE/(SongProject.PPQ*song.bpm),seconds=(to-from)*60.0/(SongProject.PPQ*song.bpm);
        checkCancelled(cancelled);int frames=Math.toIntExact((long)Math.ceil(seconds*RATE));long first=Math.round(from*unit);float[] l=new float[frames],r=new float[frames];
        Map<Integer,Source> sources=new HashMap<>();
        for(var e:events){var c=song.voice(e.channel());if(fullTail&&c.volume==0)continue;boolean natural=BlueprintAudio.noteBlock(c)&&e.duration()==SongProject.VANILLA_NOTE_DURATION&&e.offset()==0;int end=natural?period:e.start()+e.duration();if(e.start()>=to||end<=from)continue;
            Source source=sources.get(e.channel());if(source==null){source=provider.source(c);sources.put(e.channel(),source);}
            double pitch=source.pitch*MusicalPitch.ratio(e.pitch(),c.baseMidi,c.tunable);float volume=c.volume*e.velocity()/127f*source.volume;
            long eventFrame=Math.round(e.start()*unit),skipped=Math.max(0,first-eventFrame);int start=(int)Math.max(0,eventFrame-first);long duration=natural?Math.round(period*unit)-eventFrame:Math.round(e.duration()*unit);
            double offset=(e.offset()*unit+skipped)*pitch;int n=(int)Math.min(Math.min(duration-skipped,frames-start),Math.ceil((source.wave.frames()-offset)/pitch));
            checkCancelled(cancelled);for(int i=0;i<n;i++){if((i&16383)==0)checkCancelled(cancelled);double at=offset+i*pitch;int a=Math.min(source.wave.frames()-1,(int)at),b=Math.min(source.wave.frames()-1,a+1);float fraction=(float)(at-a);l[start+i]+=(source.wave.left[a]*(1-fraction)+source.wave.left[b]*fraction)*volume;r[start+i]+=(source.wave.right[a]*(1-fraction)+source.wave.right[b]*fraction)*volume;}
        }
        float peak=0;for(int i=0;i<frames;i++){if((i&16383)==0)checkCancelled(cancelled);peak=Math.max(peak,Math.max(Math.abs(l[i]),Math.abs(r[i])));}return new Mix(new Wave(l,r),peak,peak>.98f?.98f/peak:1);
    }
    private static void checkCancelled(java.util.function.BooleanSupplier cancelled){if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();}
    public static Rendered render(SongProject song,Provider provider,Path destination,String format,Path codec) throws Exception {
        song.validate();var range=ExportRange.of(song);if(range.full()&&song.events(0,false).isEmpty())throw new StudioException("error.blueprint_empty");Mix mixed=range.full()?mixFullExport(song,provider):mixWindow(song,provider,0,false,range.start(),range.end(),()->false);Wave wave=mixed.wave();float peak=mixed.peak(),scale=mixed.scale();int frames=wave.frames();Path dir=destination.toAbsolutePath().getParent();Files.createDirectories(dir);Path wav=Files.createTempFile(dir,"mix-",".wav"),encoded=Files.createTempFile(dir,"encode-","."+format);
        try{wav(wav,wave,scale);if(format.equals("wav"))Files.move(wav,destination,StandardCopyOption.REPLACE_EXISTING);else{List<String> args=new ArrayList<>(List.of("-i",wav.toString(),"-map_metadata","-1","-c:a",format.equals("mp3")?"libmp3lame":"libvorbis",format.equals("mp3")?"-b:a":"-q:a",format.equals("mp3")?"192k":"5",encoded.toString()));if(!Set.of("mp3","ogg").contains(format))throw new StudioException("error.audio_format");run(codec,args);Files.move(encoded,destination,StandardCopyOption.REPLACE_EXISTING);}return new Rendered(destination,peak,scale,frames);}
        finally{Files.deleteIfExists(wav);Files.deleteIfExists(encoded);}
    }
    public static String prepareSample(Path projects,SongProject song,SongProject.Channel c,Path codec,Path published) throws Exception {
        var a=ProjectMedia.asset(song,c.sampleId);Path dir=ProjectMedia.assets(projects,song);Files.createDirectories(dir);String key=ProjectMedia.effectKey(c);Path ogg=dir.resolve("effect-"+key+".ogg");
        if(!Files.exists(ogg)){Path wav=Files.createTempFile(dir,"effect-",".wav"),temp=Files.createTempFile(dir,"effect-",".ogg");try{wav(wav,effects(read(ProjectMedia.wav(projects,song,a.id)),c),1);run(codec,List.of("-i",wav.toString(),"-c:a","libvorbis","-q:a","5",temp.toString()));Files.move(temp,ogg,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(wav);Files.deleteIfExists(temp);}}
        String hash=ProjectMedia.hash(ogg);Files.createDirectories(published);Path target=published.resolve(hash+".ogg");if(!Files.exists(target))Files.copy(ogg,target);return hash;
    }
}
