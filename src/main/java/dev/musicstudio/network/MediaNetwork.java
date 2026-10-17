package dev.musicstudio.network;

import dev.musicstudio.MusicStudio;
import dev.musicstudio.media.AudioTransfer;
import dev.musicstudio.song.StudioException;
import dev.musicstudio.world.StudioDisc;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Content-addressed audio transfer. No client filenames, commands or executable bytes are accepted. */
public final class MediaNetwork {
    public static final int CHUNK=AudioTransfer.CHUNK;
    public static Consumer<Action> clientAction=p->{};
    public static Consumer<Chunk> clientChunk=p->{};
    public record Action(String op,UUID request,String hash,String text,int value,List<String> ids) implements CustomPacketPayload {
        public static final Type<Action> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MusicStudio.ID,"media_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Action> CODEC=StreamCodec.of((b,p)->{b.writeUtf(p.op,32);b.writeUUID(p.request);b.writeUtf(p.hash,64);b.writeUtf(p.text,256);b.writeVarInt(p.value);b.writeVarInt(p.ids.size());for(String id:p.ids)b.writeUtf(id,64);},b->{String op=b.readUtf(32);UUID id=b.readUUID();String hash=b.readUtf(64),text=b.readUtf(256);int value=b.readVarInt(),n=b.readVarInt();if(n<0||n>b.readableBytes())throw new IllegalArgumentException("Invalid media manifest");List<String> ids=new ArrayList<>();for(int i=0;i<n;i++)ids.add(b.readUtf(64));return new Action(op,id,hash,text,value,ids);});
        public Type<Action> type(){return TYPE;}
    }
    public record Chunk(UUID request,String hash,int part,int total,byte[] data) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MusicStudio.ID,"media_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Chunk> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.request);b.writeUtf(p.hash,64);b.writeVarInt(p.part);b.writeVarInt(p.total);b.writeByteArray(p.data);},b->new Chunk(b.readUUID(),b.readUtf(64),b.readVarInt(),b.readVarInt(),b.readByteArray(CHUNK)));
        public Type<Chunk> type(){return TYPE;}
    }
    private static final class Incoming {final UUID request;final String hash;final AudioTransfer.Receiver receiver;long progress=System.nanoTime();Incoming(Chunk c,Path directory) throws IOException {request=c.request;hash=c.hash;receiver=new AudioTransfer.Receiver(directory);}}
    private record Outgoing(ServerPlayer player,UUID request,String hash,AudioTransfer.Source source){}
    private static final Map<UUID,Incoming> INCOMING=new HashMap<>();
    private static final Deque<Outgoing> OUTGOING=new ArrayDeque<>();
    public static Path directory(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT).resolve("musicstudio-media");}
    public static Path file(MinecraftServer server,String hash){checkHash(hash);return directory(server).resolve(hash+".ogg");}
    private static void checkHash(String hash){if(!hash.matches("[a-f0-9]{64}"))throw new StudioException("error.asset");}
    public static void register(RegisterPayloadHandlersEvent e){var r=e.registrar("2");r.playBidirectional(Chunk.TYPE,Chunk.CODEC,(p,c)->{if(c.flow()==net.minecraft.network.protocol.PacketFlow.CLIENTBOUND)clientChunk.accept(p);else receive((ServerPlayer)c.player(),p);});r.playBidirectional(Action.TYPE,Action.CODEC,(p,c)->{if(c.flow()==net.minecraft.network.protocol.PacketFlow.CLIENTBOUND)clientAction.accept(p);else action((ServerPlayer)c.player(),p);});}
    public static void send(ServerPlayer player,Action p){PacketDistributor.sendToPlayer(player,p);}
    private static List<String> ids(MinecraftServer server) throws IOException {Path dir=directory(server);if(!Files.isDirectory(dir))return List.of();try(var files=Files.list(dir)){return files.map(p->p.getFileName().toString()).filter(s->s.matches("[a-f0-9]{64}\\.ogg")).map(s->s.substring(0,64)).toList();}}
    public static void manifest(ServerPlayer p){try{var all=ids(p.server);UUID request=UUID.randomUUID();send(p,new Action("manifest",request,"","",0,List.of()));for(int from=0;from<all.size();from+=128)send(p,new Action("manifest_add",request,"","",0,all.subList(from,Math.min(from+128,all.size()))));}catch(Exception ex){MusicStudio.LOGGER.warn("Could not send media manifest",ex);}}
    private static void action(ServerPlayer player,Action p){try{switch(p.op){
        case "manifest"->manifest(player);
        case "download"->{checkHash(p.hash);if(OUTGOING.stream().anyMatch(o->o.player==player&&o.request.equals(p.request)))return;OUTGOING.add(new Outgoing(player,p.request,p.hash,new AudioTransfer.Source(file(player.server,p.hash))));}
        case "disc"->{if(!player.isCreative())throw new StudioException("error.blueprint_creative");if(p.text.length()>100||p.value<1||!Files.isRegularFile(file(player.server,p.hash)))throw new StudioException("error.asset");if(player.getInventory().getFreeSlot()<0)throw new StudioException("error.blueprint_inventory");player.getInventory().add(StudioDisc.create(p.hash,p.text,p.value));player.containerMenu.broadcastChanges();send(player,new Action("ok",p.request,p.hash,"media.disc_ready",0,List.of()));}
        default->throw new StudioException("error.asset");
    }}catch(Exception ex){error(player,p.request,ex);}}
    private static void receive(ServerPlayer player,Chunk p){try{
        if(!player.isCreative())throw new StudioException("error.blueprint_creative");checkHash(p.hash);if(p.total<1||p.part<0||p.part>=p.total||p.data.length<1||p.data.length>CHUNK)throw new StudioException("error.asset");
        long now=System.nanoTime();if(p.part==0){closeIncoming(INCOMING.remove(player.getUUID()));INCOMING.put(player.getUUID(),new Incoming(p,directory(player.server)));}
        Incoming in=INCOMING.get(player.getUUID());if(in==null||!in.request.equals(p.request)||!in.hash.equals(p.hash))throw new StudioException("error.asset");in.receiver.accept(p.part,p.total,p.data);in.progress=now;
        if(in.receiver.complete()){try{in.receiver.finish(p.hash,file(player.server,p.hash));}finally{closeIncoming(INCOMING.remove(player.getUUID()));}send(player,new Action("ok",p.request,p.hash,"media.upload_ready",0,List.of()));for(ServerPlayer peer:player.server.getPlayerList().getPlayers())manifest(peer);}
    }catch(Exception ex){closeIncoming(INCOMING.remove(player.getUUID()));error(player,p.request,ex);}}
    private static void error(ServerPlayer player,UUID request,Exception ex){String key=ex instanceof StudioException s?s.key:"error.media_transfer";send(player,new Action("error",request,"",key,0,List.of()));}
    private static void close(AutoCloseable value){if(value!=null)try{value.close();}catch(Exception ex){MusicStudio.LOGGER.warn("Could not close audio transfer",ex);}}
    private static void closeIncoming(Incoming value){if(value!=null)close(value.receiver);}
    public static void tick(){int budget=8;while(budget-->0&&!OUTGOING.isEmpty()){var o=OUTGOING.removeFirst();if(o.player.hasDisconnected()){close(o.source);continue;}try{var part=o.source.next();PacketDistributor.sendToPlayer(o.player,new Chunk(o.request,o.hash,part.index(),part.total(),part.data()));if(o.source.complete())close(o.source);else OUTGOING.addLast(o);}catch(Exception ex){close(o.source);error(o.player,o.request,ex);}}long now=System.nanoTime();INCOMING.values().removeIf(i->{if(now-i.progress<=120_000_000_000L)return false;closeIncoming(i);return true;});}
    public static void logout(UUID id){closeIncoming(INCOMING.remove(id));OUTGOING.removeIf(o->{if(!o.player.getUUID().equals(id))return false;close(o.source);return true;});}
    public static void stop(){INCOMING.values().forEach(MediaNetwork::closeIncoming);INCOMING.clear();OUTGOING.forEach(o->close(o.source));OUTGOING.clear();}
}
