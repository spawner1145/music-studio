package dev.musicstudio.world;

import dev.musicstudio.MusicStudio;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;

public final class StudioDisc {
    public static final ResourceKey<JukeboxSong> SONG=ResourceKey.create(Registries.JUKEBOX_SONG,ResourceLocation.fromNamespaceAndPath(MusicStudio.ID,"exported_disc"));
    private StudioDisc(){}
    public static ItemStack create(String hash,String title,int ticks){var item=new ItemStack(MusicBlocks.DISC.get());var tag=new CompoundTag();tag.putString("audio",hash);tag.putInt("ticks",ticks);item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));item.set(DataComponents.CUSTOM_NAME,Component.literal(title));item.set(DataComponents.JUKEBOX_PLAYABLE,new JukeboxPlayable(new EitherHolder<>(SONG),true));return item;}
    public static String hash(ItemStack item){if(!item.is(MusicBlocks.DISC.get()))return "";String hash=item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("audio");return hash.matches("[a-f0-9]{64}")?hash:"";}
    public static int ticks(ItemStack item){return Math.max(1,item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getInt("ticks"));}
}
