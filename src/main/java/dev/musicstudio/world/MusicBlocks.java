package dev.musicstudio.world;

import dev.musicstudio.MusicStudio;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

/** The only new content is the blueprint item. Generated playback blocks are vanilla. */
public final class MusicBlocks {
    public static final DeferredRegister<Item> ITEMS=DeferredRegister.create(Registries.ITEM,MusicStudio.ID);
    public static final DeferredHolder<Item,BlueprintItem> BLUEPRINT=ITEMS.register("music_blueprint",()->new BlueprintItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item,Item> DISC=ITEMS.register("studio_disc",()->new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS=DeferredRegister.create(Registries.SOUND_EVENT,MusicStudio.ID);
    public static final DeferredHolder<net.minecraft.sounds.SoundEvent,net.minecraft.sounds.SoundEvent> DISC_SOUND=SOUNDS.register("exported_disc",()->net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MusicStudio.ID,"exported_disc")));
    public static void register(IEventBus bus){ITEMS.register(bus);SOUNDS.register(bus);}
}
