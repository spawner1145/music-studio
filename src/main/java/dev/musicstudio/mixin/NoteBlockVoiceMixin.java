package dev.musicstudio.mixin;

import dev.musicstudio.network.NoteVoiceNetwork;
import dev.musicstudio.blueprint.NoteBlockVoice;
import dev.musicstudio.world.ExtendedNoteData;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import com.llamalad7.mixinextras.sugar.Local;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(NoteBlock.class)
public abstract class NoteBlockVoiceMixin {
    @Redirect(method="useWithoutItem",at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/common/CommonHooks;onNoteChange(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)I"))
    private int musicstudio$tune(Level level,BlockPos pos,BlockState state,int oldNote,int nextNote){
        var data=level instanceof ServerLevel server?ExtendedNoteData.get(server):null;var voice=data==null?null:data.voice(pos);
        if(voice==null)return net.neoforged.neoforge.common.CommonHooks.onNoteChange(level,pos,state,oldNote,nextNote);
        int raw=voice.note()==139?-115:voice.note()+1,clamped=Math.clamp(raw,0,24);
        int changed=net.neoforged.neoforge.common.CommonHooks.onNoteChange(level,pos,state,oldNote,clamped);
        if(changed>=0&&changed<=24)data.put(pos,new NoteBlockVoice(changed==clamped?raw:changed,voice.velocity(),voice.durationTicks(),voice.volume()));
        return changed;
    }
    @Redirect(method="triggerEvent",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V"))
    private void musicstudio$play(Level level,Player player,double x,double y,double z,Holder<SoundEvent> sound,SoundSource source,float volume,float pitch,long seed,@Local NoteBlockInstrument instrument){
        BlockPos pos=BlockPos.containing(x,y,z);
        var voice=level instanceof ServerLevel server?ExtendedNoteData.get(server).voice(pos):null;
        if(voice==null){level.playSeededSound(player,x,y,z,sound,source,volume,pitch,seed);return;}
        if(instrument.isTunable())pitch*=(float)Math.pow(2,(voice.note()-Math.clamp(voice.note(),0,24))/12.0);
        volume*=voice.volume()*voice.velocity()/127f;
        var event=EventHooks.onPlaySoundAtPosition(level,x,y,z,sound,source,volume,pitch);
        if(event.isCanceled()||event.getSound()==null)return;
        sound=event.getSound();source=event.getSource();volume=event.getNewVolume();pitch=event.getNewPitch();
        PacketDistributor.sendToPlayersNear((ServerLevel)level,null,x,y,z,sound.value().getRange(volume),new NoteVoiceNetwork.Play(sound.value().getLocation(),source,x,y,z,volume,pitch,seed,voice.durationTicks()));
    }
}
