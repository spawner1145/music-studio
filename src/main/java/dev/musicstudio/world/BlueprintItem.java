package dev.musicstudio.world;

import dev.musicstudio.blueprint.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;

public final class BlueprintItem extends Item {
    private static final java.util.Map<CustomData,MusicBlueprint> CACHE=java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    public BlueprintItem(Properties p){super(p);}
    public static ItemStack create(byte[] snapshot,MusicBlueprint b){
        var item=new ItemStack(MusicBlocks.BLUEPRINT.get());var tag=new CompoundTag();tag.putInt("version",b.parts().stream().anyMatch(p->p.noteVoice()!=null)?4:3);tag.putByteArray("score",snapshot);tag.putInt("rotation",0);
        item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));item.set(DataComponents.CUSTOM_NAME,Component.literal(b.title()));return item;
    }
    public static CompoundTag data(ItemStack stack){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();}
    public static MusicBlueprint decode(ItemStack stack){CustomData data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY);MusicBlueprint cached=CACHE.get(data);if(cached!=null)return cached;var tag=data.copyTag();if(tag.getInt("version")!=2&&tag.getInt("version")!=3&&tag.getInt("version")!=4)throw new dev.musicstudio.song.StudioException("error.blueprint_data");var song=BlueprintData.unpack(tag.getByteArray("score"));if(tag.getInt("version")<4)song.noteBlockPitchHook=false;MusicBlueprint result=tag.getInt("version")==2?MusicBlueprint.legacySnapshot(song):MusicBlueprint.compile(song);if(CACHE.size()>=32)CACHE.clear();CACHE.put(data,result);return result;}
    public static int rotation(ItemStack stack){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).getUnsafe().getInt("rotation")&3;}
    private void rotate(Player player,InteractionHand hand){try{var stack=player.getItemInHand(hand);var blueprint=decode(stack);CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->{int r=tag.getInt("rotation");do{r=(r+1)&3;}while(!blueprint.fitsRotation(r));tag.putInt("rotation",r);});player.displayClientMessage(Component.translatable("musicstudio.blueprint.rotated",rotation(stack)*90),true);}catch(Exception e){StudioMessages.error(player,e);}}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){if(player.isShiftKeyDown()){if(!level.isClientSide)rotate(player,hand);return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);}return InteractionResultHolder.pass(player.getItemInHand(hand));}
    @Override public InteractionResult useOn(UseOnContext ctx){
        Player player=ctx.getPlayer();if(player==null)return InteractionResult.FAIL;
        if(player.isShiftKeyDown()){if(!ctx.getLevel().isClientSide)rotate(player,ctx.getHand());return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);}
        if(ctx.getLevel() instanceof ServerLevel level){
            if(!player.isCreative()){player.displayClientMessage(Component.translatable("musicstudio.error.blueprint_creative"),true);return InteractionResult.FAIL;}
            try{MusicBlueprint b=decode(ctx.getItemInHand());BlockPos anchor=ctx.getClickedPos().relative(ctx.getClickedFace());BlueprintPlacement.begin(level,(ServerPlayer)player,anchor,b,rotation(ctx.getItemInHand()));}
            catch(Exception e){StudioMessages.error(player,e);return InteractionResult.FAIL;}
        }
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext ctx,List<Component> lines,TooltipFlag flag){
        try{var b=decode(stack);int r=rotation(stack);lines.add(Component.translatable("musicstudio.blueprint.size",b.width(r),b.depth(r),b.y(),b.route().size()));lines.add(Component.translatable(b.loop()?"musicstudio.blueprint.loop":"musicstudio.blueprint.once"));if(b.parts().stream().anyMatch(p->p.noteVoice()!=null))lines.add(Component.translatable("musicstudio.blueprint.hook"));lines.add(Component.translatable("musicstudio.blueprint.instructions"));}
        catch(Exception e){lines.add(Component.translatable("musicstudio.error.blueprint_data"));}
    }
}
