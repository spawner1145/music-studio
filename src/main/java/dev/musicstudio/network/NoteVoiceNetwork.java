package dev.musicstudio.network;

import dev.musicstudio.MusicStudio;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.function.Consumer;

public final class NoteVoiceNetwork {
    public static Consumer<Play> playback=p->{};
    public record Play(ResourceLocation sound,SoundSource source,double x,double y,double z,float volume,float pitch,long seed,int durationTicks) implements CustomPacketPayload {
        public static final Type<Play> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MusicStudio.ID,"note_voice"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Play> CODEC=StreamCodec.of((b,p)->{b.writeResourceLocation(p.sound);b.writeEnum(p.source);b.writeDouble(p.x);b.writeDouble(p.y);b.writeDouble(p.z);b.writeFloat(p.volume);b.writeFloat(p.pitch);b.writeLong(p.seed);b.writeVarInt(p.durationTicks);},b->new Play(b.readResourceLocation(),b.readEnum(SoundSource.class),b.readDouble(),b.readDouble(),b.readDouble(),b.readFloat(),b.readFloat(),b.readLong(),b.readVarInt()));
        public Type<Play> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent e){e.registrar("1").playToClient(Play.TYPE,Play.CODEC,(p,c)->playback.accept(p));}
}
