package dev.musicstudio.mixin;

import dev.musicstudio.world.ExtendedNoteData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockBehaviour.class)
public abstract class NoteVoiceCleanupMixin {
    @Inject(method="onRemove",at=@At("HEAD"))
    private void musicstudio$remove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moving,CallbackInfo ci){
        if(state.is(Blocks.NOTE_BLOCK)&&!next.is(Blocks.NOTE_BLOCK)&&level instanceof ServerLevel server)ExtendedNoteData.get(server).put(pos,null);
    }
}
