package dev.musicstudio.mixin;

import dev.musicstudio.world.DiscPlayback;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxMixin {
    @Inject(method="setTheItem",at=@At("TAIL"))
    private void musicstudio$changed(ItemStack item,CallbackInfo callback){DiscPlayback.changed((JukeboxBlockEntity)(Object)this);}
}
