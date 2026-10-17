package dev.musicstudio.mixin;

import dev.musicstudio.world.StudioDisc;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Exported discs use their own duration instead of the registry placeholder's ten minutes. */
@Mixin(JukeboxSongPlayer.class)
public abstract class JukeboxDurationMixin {
    @Shadow @Final private BlockPos blockPos;
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/JukeboxSong;hasFinished(J)Z"))
    private boolean musicstudio$finished(JukeboxSong song,long elapsed,LevelAccessor level,BlockState state){
        if(level.getBlockEntity(blockPos) instanceof JukeboxBlockEntity box&&!StudioDisc.hash(box.getTheItem()).isEmpty())return elapsed>=StudioDisc.ticks(box.getTheItem());
        return song.hasFinished(elapsed);
    }
    @Redirect(method="setSongWithoutPlaying",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/JukeboxSong;hasFinished(J)Z"))
    private boolean musicstudio$restore(JukeboxSong song,long elapsed,Holder<JukeboxSong> holder,long storedTicks){
        return !holder.is(StudioDisc.SONG)&&song.hasFinished(elapsed);
    }
}
