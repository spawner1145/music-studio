package dev.musicstudio.client;

import dev.musicstudio.media.*;
import dev.musicstudio.song.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Client resource resolution is captured before asynchronous decoding/mixing starts. */
public final class ClientMedia {
    public static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"musicstudio-media");t.setDaemon(true);return t;});
    public static Path game(){return Minecraft.getInstance().gameDirectory.toPath();}
    public static Path projects(){return game().resolve("musicstudio/projects");}
    public static Path published(){return game().resolve("musicstudio/media");}
    public static Path codec(){return AudioFiles.codec(game());}
    public static SongProject prepare(SongProject original) throws Exception {
        SongProject song=ProjectStore.copy(original);for(var c:song.voices())if(!c.muted&&!c.sampleId.isEmpty()){c.renderedAudio=AudioFiles.prepareSample(projects(),song,c,codec(),published());c.sound="musicstudio:sample/"+c.renderedAudio;c.instrument="";}return song;
    }
    /** Call capture on the client thread; all decoding/encoding below runs on IO. */
    public static AudioFiles.Provider captureBlueprint(SongProject song){
        boolean full=ExportRange.of(song).full();song=full?ProjectStore.copy(song):ExportRange.snapshot(song);SongProject selected=ProjectStore.copy(song);Set<Integer> needed=new HashSet<>();for(var e:song.events(0,false))if(full||BlueprintAudio.needsAudio(song,song.voice(e.channel()),e))needed.add(e.channel());
        for(int i=0;i<selected.voices().size();i++)selected.voice(i).muted=!needed.contains(i);return capture(selected);
    }
    public static SongProject prepareBlueprint(SongProject original,AudioFiles.Provider provider) throws Exception {
        provider=AudioFiles.cached(provider);int end=ExportRange.of(original).full()?Math.max(ExportRange.contentEnd(original),AudioFiles.blueprintEndPpq(original,provider)):original.endPpq;
        SongProject song=ExportRange.snapshot(original,end);song.blueprintAudio.clear();Map<Integer,AudioFiles.Source> sources=new HashMap<>();
        for(var event:song.events(0,false)){var c=song.voice(event.channel());if(!BlueprintAudio.needsAudio(song,c,event))continue;String key=BlueprintAudio.key(song,c,event);if(song.blueprintAudio.containsKey(key))continue;
            AudioFiles.Source source=sources.get(event.channel());if(source==null){source=provider.source(c);sources.put(event.channel(),source);}
            song.blueprintAudio.put(key,AudioFiles.prepareNote(projects(),song,source,c,event,codec(),published()));
        }return song;
    }
    public static AudioFiles.Provider capture(SongProject song){
        return capture(song,0,false);
    }
    public static AudioFiles.Provider captureExport(SongProject song){return capture(song,0,false,true);}
    public static AudioFiles.Provider capture(SongProject song,int pattern,boolean patternMode){return capture(song,pattern,patternMode,false);}
    private static AudioFiles.Provider capture(SongProject song,int pattern,boolean patternMode,boolean selection){
        var mc=Minecraft.getInstance();var manager=mc.getResourceManager();Map<String,Sound> files=new HashMap<>();Map<String,float[]> parameters=new HashMap<>();
        Set<Integer> needed=new HashSet<>();var range=ExportRange.of(song);for(var e:song.events(pattern,patternMode)){var c=song.voice(e.channel());int end=BlueprintAudio.noteBlock(c)&&e.duration()==SongProject.VANILLA_NOTE_DURATION&&e.offset()==0?song.endPpq:e.start()+e.duration();if(!selection||e.start()<range.end()&&end>range.start())needed.add(e.channel());}
        for(int index:needed){var c=song.voice(index);if(c.sampleId.isEmpty()){
            var event=mc.getSoundManager().getSoundEvent(ResourceLocation.parse(c.sound));if(event==null||event.getWeight()==0)throw new StudioException("audio.unavailable",c.sound);
            var random=RandomSource.create(31L*c.sound.hashCode());Sound sound=event.getSound(random);files.put(c.id,sound);parameters.put(c.id,new float[]{sound.getVolume().sample(random),sound.getPitch().sample(random)});
        }}
        return c->{
            if(!c.sampleId.isEmpty())return new AudioFiles.Source(AudioFiles.effects(AudioFiles.read(ProjectMedia.wav(projects(),song,c.sampleId)),c),1,1);
            Sound sound=files.get(c.id);if(sound==null)throw new StudioException("audio.unavailable",c.sound);Path cache=game().resolve("musicstudio/cache/audio");Files.createDirectories(cache);
            Path staged=Files.createTempFile(cache,"resource-",".ogg"),wav;
            try{try(var input=manager.open(sound.getPath())){Files.copy(input,staged,StandardCopyOption.REPLACE_EXISTING);}String hash=ProjectMedia.hash(staged);Path original=cache.resolve(hash+".ogg");wav=cache.resolve(hash+".wav");if(!Files.exists(wav)){Files.move(staged,original,StandardCopyOption.REPLACE_EXISTING);Path temp=Files.createTempFile(cache,"decode-",".wav");try{AudioFiles.run(codec(),List.of("-i",original.toString(),"-vn","-ar","44100","-ac","2","-c:a","pcm_s16le",temp.toString()));Files.move(temp,wav,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}}finally{Files.deleteIfExists(staged);}
            float[] p=parameters.get(c.id);return new AudioFiles.Source(AudioFiles.read(wav),p[0],p[1]);
        };
    }
    public static SongProject preparePreview(SongProject original,int pattern,boolean patternMode,double start,AudioFiles.Provider provider) throws Exception {
        SongProject song=ProjectStore.copy(original);song.blueprintAudio.clear();Map<Integer,AudioFiles.Source> sources=new HashMap<>();
        List<SongProject.Event> events=new ArrayList<>(song.events(pattern,patternMode));events.addAll(PreviewEngine.eventsFrom(song,pattern,patternMode,start));
        for(var event:events)if(event.offset()>0){var c=song.voice(event.channel());var source=sources.get(event.channel());if(source==null){source=provider.source(c);sources.put(event.channel(),source);}String key=BlueprintAudio.key(song,c,event);if(!song.blueprintAudio.containsKey(key))song.blueprintAudio.put(key,AudioFiles.prepareNote(projects(),song,source,c,event,codec(),published()));}return song;
    }
    private static CompletableFuture<Void> reload=CompletableFuture.completedFuture(null);
    public static synchronized CompletableFuture<Void> refreshPack(){
        var result=new CompletableFuture<Void>();Minecraft mc=Minecraft.getInstance();
        reload=reload.handle((v,e)->null).thenCompose(v->{var next=new CompletableFuture<Void>();mc.execute(()->{try{
            Path pack=game().resolve("resourcepacks/musicstudio-generated"),sounds=pack.resolve("assets/musicstudio/sounds/media");Files.createDirectories(sounds);Files.createDirectories(published());Map<String,Object> events=new TreeMap<>();try(var files=Files.list(published())){for(Path file:files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.ogg")).toList()){
                String hash=file.getFileName().toString().substring(0,64);Path target=sounds.resolve(file.getFileName());if(!Files.exists(target)||Files.size(target)!=Files.size(file))Files.copy(file,target,StandardCopyOption.REPLACE_EXISTING);events.put("sample/"+hash,Map.of("sounds",List.of(Map.of("name","musicstudio:media/"+hash,"stream",true))));}}
            Files.writeString(pack.resolve("pack.mcmeta"),"{\"pack\":{\"pack_format\":34,\"description\":\"Music Studio project audio\"}}");Files.writeString(pack.resolve("assets/musicstudio/sounds.json"),ProjectStore.GSON.toJson(events));
            String id="file/musicstudio-generated";mc.getResourcePackRepository().reload();if(!mc.options.resourcePacks.contains(id)){mc.options.resourcePacks.add(id);mc.options.save();}mc.getResourcePackRepository().setSelected(mc.options.resourcePacks);mc.reloadResourcePacks().whenComplete((done,error)->{if(error!=null)next.completeExceptionally(error);else next.complete(null);});
        }catch(Exception error){next.completeExceptionally(error);}});return next;});
        reload.whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);else result.complete(null);});return result;
    }
}
