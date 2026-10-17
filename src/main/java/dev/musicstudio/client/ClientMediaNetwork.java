package dev.musicstudio.client;

import dev.musicstudio.media.AudioTransfer;
import dev.musicstudio.network.MediaNetwork;
import dev.musicstudio.song.StudioException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class ClientMediaNetwork {
    private record Upload(UUID request,String hash,AudioTransfer.Source source){}
    private record Disc(SoundInstance sound,long end){}
    private static final Map<UUID,CompletableFuture<Void>> PENDING=new HashMap<>();
    private static final Map<UUID,Long> STARTED=new HashMap<>();
    private static final Deque<Upload> UPLOADS=new ArrayDeque<>();
    private static final Deque<String> DOWNLOADS=new ArrayDeque<>();
    private static final Set<String> SERVER=new HashSet<>();
    private static final Map<BlockPos,Disc> DISCS=new HashMap<>();
    private static final Map<BlockPos,MediaNetwork.Action> WAITING=new HashMap<>();
    private static String downloading="";private static UUID downloadId;private static AudioTransfer.Receiver downloaded;private static long clock,lastDownload,lastUpload;
    public static int playedDiscs;
    public static int downloadedFiles;
    public static int activeDiscs(){return DISCS.size();}
    public static void init(){MediaNetwork.clientAction=ClientMediaNetwork::action;MediaNetwork.clientChunk=ClientMediaNetwork::chunk;}
    private static CompletableFuture<Void> pending(UUID id){var future=new CompletableFuture<Void>();PENDING.put(id,future);STARTED.put(id,System.currentTimeMillis());return future;}
    public static CompletableFuture<Void> upload(String hash){var result=new CompletableFuture<Void>();ClientMedia.IO.execute(()->{try{var source=new AudioTransfer.Source(ClientMedia.published().resolve(hash+".ogg"));Minecraft.getInstance().execute(()->{if(SERVER.contains(hash)){close(source);result.complete(null);return;}UUID id=UUID.randomUUID();pending(id).whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);else result.complete(null);});UPLOADS.addLast(new Upload(id,hash,source));});}catch(Exception e){result.completeExceptionally(e);}});return result;}
    public static CompletableFuture<Void> uploadSamples(dev.musicstudio.song.SongProject song){Set<String> hashes=new LinkedHashSet<>(song.blueprintAudio.values());for(var c:song.voices())if(!c.muted&&!c.sampleId.isEmpty())hashes.add(c.renderedAudio);CompletableFuture<Void> result=CompletableFuture.completedFuture(null);for(String hash:hashes)result=result.thenCompose(v->upload(hash));return result;}
    public static CompletableFuture<Void> disc(String hash,String title,int ticks){UUID request=UUID.randomUUID();var future=pending(request);PacketDistributor.sendToServer(new MediaNetwork.Action("disc",request,hash,title,ticks,List.of()));return future;}
    private static void action(MediaNetwork.Action p){
        if(p.op().equals("error")&&p.request().equals(downloadId)){String hash=downloading;clearDownload();WAITING.values().removeIf(waiting->waiting.hash().equals(hash));Minecraft.getInstance().gui.setOverlayMessage(Component.literal(StudioText.error(new StudioException(p.text()))),false);return;}
        if(p.op().equals("error"))UPLOADS.removeIf(upload->{if(!upload.request.equals(p.request()))return false;close(upload.source);return true;});
        var mc=Minecraft.getInstance();if(p.op().equals("ok")||p.op().equals("error")){if(p.op().equals("ok")&&!p.hash().isEmpty())SERVER.add(p.hash());var future=PENDING.remove(p.request());STARTED.remove(p.request());if(future!=null){if(p.op().equals("error"))future.completeExceptionally(new StudioException(p.text()));else future.complete(null);}return;}
        if(p.op().equals("manifest")||p.op().equals("manifest_add")){if(p.op().equals("manifest"))SERVER.clear();SERVER.addAll(p.ids());boolean missing=false;for(String hash:p.ids())if(hash.matches("[a-f0-9]{64}")){if(!Files.isRegularFile(ClientMedia.published().resolve(hash+".ogg"))&&!DOWNLOADS.contains(hash)&&!hash.equals(downloading))DOWNLOADS.addLast(hash);else if(Files.isRegularFile(ClientMedia.published().resolve(hash+".ogg"))&&Minecraft.getInstance().getSoundManager().getSoundEvent(ResourceLocation.fromNamespaceAndPath("musicstudio","sample/"+hash))==null)missing=true;}if(missing)ClientMedia.refreshPack();return;}
        if(p.op().equals("disc_stop")||p.op().equals("disc_play")){if(p.ids().size()!=1||!p.hash().matches("[a-f0-9]{64}"))return;BlockPos pos;try{pos=BlockPos.of(Long.parseLong(p.ids().getFirst()));}catch(NumberFormatException e){return;}Disc old=DISCS.remove(pos);if(old!=null)mc.getSoundManager().stop(old.sound);WAITING.remove(pos);if(p.op().equals("disc_stop"))return;WAITING.put(pos,p);if(!Files.exists(ClientMedia.published().resolve(p.hash()+".ogg"))&&!DOWNLOADS.contains(p.hash()))DOWNLOADS.addFirst(p.hash());else ready();}
    }
    private static boolean reloading;
    private static void ready(){if(WAITING.isEmpty()||reloading)return;var mc=Minecraft.getInstance();boolean missing=WAITING.values().stream().anyMatch(p->Files.exists(ClientMedia.published().resolve(p.hash()+".ogg"))&&mc.getSoundManager().getSoundEvent(ResourceLocation.fromNamespaceAndPath("musicstudio","sample/"+p.hash()))==null);
        if(missing){reloading=true;ClientMedia.refreshPack().whenComplete((v,e)->mc.execute(()->{reloading=false;if(e==null)playWaiting();}));}else playWaiting();
    }
    private static void playWaiting(){var mc=Minecraft.getInstance();for(var it=WAITING.entrySet().iterator();it.hasNext();){var entry=it.next();var p=entry.getValue();if(mc.level==null||mc.player==null||!Files.exists(ClientMedia.published().resolve(p.hash()+".ogg")))continue;var id=ResourceLocation.fromNamespaceAndPath("musicstudio","sample/"+p.hash());if(mc.getSoundManager().getSoundEvent(id)==null)continue;BlockPos pos=entry.getKey();var sound=new SimpleSoundInstance(id,SoundSource.RECORDS,1,1,SoundInstance.createUnseededRandom(),false,0,SoundInstance.Attenuation.LINEAR,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,false);mc.getSoundManager().play(sound);mc.gui.setNowPlaying(Component.literal(p.text()));DISCS.put(pos,new Disc(sound,clock+Math.max(1,p.value())));playedDiscs++;it.remove();}}
    private static void chunk(MediaNetwork.Chunk p){
        if(!p.request().equals(downloadId)||!p.hash().equals(downloading))return;
        try{downloaded.accept(p.part(),p.total(),p.data());lastDownload=clock;
            if(downloaded.complete()){var transfer=downloaded;String hash=downloading;downloading="";downloaded=null;downloadId=null;ClientMedia.IO.execute(()->{try{transfer.finish(hash,ClientMedia.published().resolve(hash+".ogg"));Minecraft.getInstance().execute(()->{downloadedFiles++;ClientMedia.refreshPack();ready();});}catch(Exception e){dev.musicstudio.MusicStudio.LOGGER.error("Audio download failed",e);}finally{close(transfer);}});}
        }catch(Exception e){clearDownload();dev.musicstudio.MusicStudio.LOGGER.warn("Audio transfer failed",e);}
    }
    private static void close(AutoCloseable transfer){if(transfer!=null)try{transfer.close();}catch(Exception e){dev.musicstudio.MusicStudio.LOGGER.warn("Could not close audio transfer",e);}}
    private static void clearDownload(){close(downloaded);downloading="";downloadId=null;downloaded=null;}
    public static void tick(){clock++;Minecraft mc=Minecraft.getInstance();if(mc.player==null)return;
        if(!UPLOADS.isEmpty()&&(clock-lastUpload>=8||UPLOADS.peekFirst().source.part()>0)){int budget=4;while(budget-->0&&!UPLOADS.isEmpty()){Upload u=UPLOADS.removeFirst();try{var part=u.source.next();STARTED.put(u.request,System.currentTimeMillis());PacketDistributor.sendToServer(new MediaNetwork.Chunk(u.request,u.hash,part.index(),part.total(),part.data()));if(!u.source.complete())UPLOADS.addFirst(u);else{close(u.source);lastUpload=clock;break;}}catch(Exception e){close(u.source);var future=PENDING.remove(u.request);STARTED.remove(u.request);if(future!=null)future.completeExceptionally(e);}}}
        if(!downloading.isEmpty()&&clock-lastDownload>2400)clearDownload();
        if(downloading.isEmpty()&&!DOWNLOADS.isEmpty()){try{downloading=DOWNLOADS.removeFirst();downloadId=UUID.randomUUID();downloaded=new AudioTransfer.Receiver(ClientMedia.published());lastDownload=clock;PacketDistributor.sendToServer(new MediaNetwork.Action("download",downloadId,downloading,"",0,List.of()));}catch(Exception e){clearDownload();dev.musicstudio.MusicStudio.LOGGER.warn("Could not start audio download",e);}}
        DISCS.entrySet().removeIf(entry->{var d=entry.getValue();if(clock>=d.end||mc.level==null||!mc.level.getBlockState(entry.getKey()).is(net.minecraft.world.level.block.Blocks.JUKEBOX)||mc.player.blockPosition().distSqr(entry.getKey())>128*128){mc.getSoundManager().stop(d.sound);return true;}return false;});
        for(var it=STARTED.entrySet().iterator();it.hasNext();){var entry=it.next();if(System.currentTimeMillis()-entry.getValue()>120000){UUID id=entry.getKey();UPLOADS.removeIf(u->{if(!u.request.equals(id))return false;close(u.source);return true;});var future=PENDING.remove(id);it.remove();if(future!=null)future.completeExceptionally(new StudioException("error.media_transfer"));}}
    }
    public static void logout(){var mc=Minecraft.getInstance();DISCS.values().forEach(d->mc.getSoundManager().stop(d.sound));DISCS.clear();WAITING.clear();PENDING.values().forEach(f->f.completeExceptionally(new StudioException("error.media_transfer")));PENDING.clear();STARTED.clear();UPLOADS.forEach(u->close(u.source));UPLOADS.clear();DOWNLOADS.clear();SERVER.clear();clearDownload();}
}
