package dev.musicstudio.network;

import dev.musicstudio.MusicStudio;
import dev.musicstudio.blueprint.*;
import dev.musicstudio.song.*;
import dev.musicstudio.world.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

public final class StudioNetwork {
    public static Consumer<Reply> replyHandler=p->{};
    private static final Map<UUID,UploadState> UPLOADS=new HashMap<>();
    private static final Map<UUID,Long> LAST=new HashMap<>();
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name){return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MusicStudio.ID,name));}
    public record Upload(UUID request,int part,int total,byte[] data) implements CustomPacketPayload {
        public static final Type<Upload> TYPE=StudioNetwork.type("blueprint_upload");
        public static final StreamCodec<RegistryFriendlyByteBuf,Upload> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.request);b.writeVarInt(p.part);b.writeVarInt(p.total);b.writeByteArray(p.data);},b->new Upload(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readByteArray(24000)));
        public Type<Upload> type(){return TYPE;}
    }
    public record Reply(UUID request,boolean ok,String key,List<String> args) implements CustomPacketPayload {
        public static final Type<Reply> TYPE=StudioNetwork.type("blueprint_reply");
        public static final StreamCodec<RegistryFriendlyByteBuf,Reply> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.request);b.writeBoolean(p.ok);b.writeUtf(p.key,128);b.writeVarInt(p.args.size());for(String s:p.args)b.writeUtf(s,512);},b->{UUID id=b.readUUID();boolean ok=b.readBoolean();String key=b.readUtf(128);int n=b.readVarInt();if(n<0||n>8)throw new IllegalArgumentException("Invalid reply");List<String> args=new ArrayList<>();for(int i=0;i<n;i++)args.add(b.readUtf(512));return new Reply(id,ok,key,args);});
        public Type<Reply> type(){return TYPE;}
    }
    private static final class UploadState {
        final UUID request;final int total;final long created=System.nanoTime();int next;final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        UploadState(Upload p){request=p.request;total=p.total;}
    }
    public static void register(RegisterPayloadHandlersEvent e){var r=e.registrar("3");
        r.playToServer(Upload.TYPE,Upload.CODEC,(p,c)->receive((ServerPlayer)c.player(),p));
        r.playToClient(Reply.TYPE,Reply.CODEC,(p,c)->replyHandler.accept(p));
    }
    public static void logout(UUID player){UPLOADS.remove(player);LAST.remove(player);}
    public static void expire(){long now=System.nanoTime();UPLOADS.values().removeIf(s->now-s.created>15_000_000_000L);}
    public static void receive(ServerPlayer player,Upload p){
        try{
            if(!player.isCreative())throw new StudioException("error.blueprint_creative");
            if(p.total<1||p.total>16||p.part<0||p.part>=p.total||p.data.length<1||p.data.length>24000)throw new StudioException("error.blueprint_data");
            long now=System.nanoTime();expire();
            if(p.part==0){
                if(now-LAST.getOrDefault(player.getUUID(),0L)<2_000_000_000L)throw new StudioException("error.blueprint_cooldown");
                LAST.put(player.getUUID(),now);if(UPLOADS.size()>=64)throw new StudioException("error.blueprint_busy");UPLOADS.put(player.getUUID(),new UploadState(p));
            }
            var state=UPLOADS.get(player.getUUID());
            if(state==null||!state.request.equals(p.request)||state.total!=p.total||state.next!=p.part||state.bytes.size()+p.data.length>BlueprintData.MAX_PACKED)throw new StudioException("error.blueprint_data");
            state.bytes.writeBytes(p.data);state.next++;
            if(state.next==state.total){UPLOADS.remove(player.getUUID());byte[] bytes=state.bytes.toByteArray();SongProject song=BlueprintData.unpack(bytes);for(var channel:song.voices())if(!channel.muted&&!channel.sampleId.isEmpty()&&(!channel.sound.equals("musicstudio:sample/"+channel.renderedAudio)||!java.nio.file.Files.isRegularFile(MediaNetwork.file(player.server,channel.renderedAudio))))throw new StudioException("error.sample_server");MusicBlueprint result=MusicBlueprint.compile(song);
                for(var voice:result.voices())if(voice.sound().startsWith("musicstudio:sample/")&&!java.nio.file.Files.isRegularFile(MediaNetwork.file(player.server,voice.sound().substring("musicstudio:sample/".length()))))throw new StudioException("error.sample_server");
                if(result.outputs().stream().anyMatch(MusicBlueprint.Output::command)&&(!player.hasPermissions(2)||!player.server.isCommandBlockEnabled()))throw new StudioException("error.blueprint_commands");
                if(player.getInventory().getFreeSlot()<0)throw new StudioException("error.blueprint_inventory");
                if(!player.getInventory().add(BlueprintItem.create(bytes,result)))throw new StudioException("error.blueprint_inventory");player.containerMenu.broadcastChanges();
                sendReply(player,new Reply(p.request,true,"status.blueprint_exported",List.of(""+result.x(),""+result.z(),""+result.y(),""+result.route().size())));
            }
        }catch(Exception ex){UPLOADS.remove(player.getUUID());String key=ex instanceof StudioException s?s.key:"error.blueprint_internal";List<String> args=ex instanceof StudioException s?Arrays.stream(s.arguments).map(String::valueOf).toList():List.of();sendReply(player,new Reply(p.request,false,key,args));}
    }
    private static void sendReply(ServerPlayer p,Reply r){PacketDistributor.sendToPlayer(p,r);}
}
