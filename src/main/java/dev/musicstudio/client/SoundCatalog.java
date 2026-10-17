package dev.musicstudio.client;

import dev.musicstudio.MusicStudio;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import java.util.*;

/** Union of runtime registry and resolved sounds.json events, including resource-pack-only events. */
public final class SoundCatalog {
    public record Entry(ResourceLocation id,String instrument,boolean tunable,boolean registered,boolean loaded,boolean playable) {
        public String instrumentLabel(){return StudioText.named("instrument."+instrument,instrument);}
        public String label() { return instrument.isEmpty()?id.toString():instrumentLabel()+"  ·  "+id; }
    }
    public static final SoundCatalog INSTANCE=new SoundCatalog();
    private List<Entry> events=List.of(),instruments=List.of();
    private boolean dirty=true;
    private long revision;
    public void invalidate() { dirty=true; }
    public long revision() { return revision; }
    public List<Entry> events() { refreshIfNeeded();return events; }
    public List<Entry> instruments() { refreshIfNeeded();return instruments; }
    public void refreshIfNeeded() { if(dirty)scan(); }
    public void scan() {
        Minecraft mc=Minecraft.getInstance();
        Set<ResourceLocation> registered=new HashSet<>(BuiltInRegistries.SOUND_EVENT.keySet());
        Set<ResourceLocation> loaded=new HashSet<>(mc.getSoundManager().getAvailableSounds());
        SortedSet<ResourceLocation> all=new TreeSet<>(Comparator.comparing(ResourceLocation::toString));all.addAll(registered);all.addAll(loaded);
        List<Entry> sounds=new ArrayList<>();
        for(ResourceLocation id:all) sounds.add(new Entry(id,"",true,registered.contains(id),loaded.contains(id),playable(id)));
        List<Entry> notes=new ArrayList<>();
        for(NoteBlockInstrument instrument:NoteBlockInstrument.values()) {
            if(instrument.hasCustomSound())continue; // CUSTOM_HEAD is dynamic, UI_BUTTON_CLICK is only a placeholder.
            ResourceLocation id=instrument.getSoundEvent().value().getLocation();
            notes.add(new Entry(id,instrument.getSerializedName(),instrument.isTunable(),registered.contains(id),loaded.contains(id),playable(id)));
        }
        // Include modded noteblock events that do not add enum instruments.
        Set<ResourceLocation> existing=new HashSet<>();notes.forEach(e->existing.add(e.id()));
        for(Entry e:sounds) if(e.id().getPath().contains("note_block")&&!existing.contains(e.id()))notes.add(new Entry(e.id(),e.id().getPath(),true,e.registered(),e.loaded(),e.playable()));
        events=List.copyOf(sounds);instruments=List.copyOf(notes);dirty=false;revision++;
        MusicStudio.LOGGER.info("Music Studio sound catalog: {} registry events, {} loaded resource events, {} union events, {} note-block voices",registered.size(),loaded.size(),events.size(),instruments.size());
    }
    private boolean playable(ResourceLocation id) {
        var event=Minecraft.getInstance().getSoundManager().getSoundEvent(id);
        return event!=null&&event.getWeight()>0;
    }
    public List<Entry> search(String query,boolean noteBlocks) {
        String q=query.toLowerCase(Locale.ROOT).strip();
        return (noteBlocks?instruments():events()).stream().filter(e->(e.label()+" "+e.instrument()).toLowerCase(Locale.ROOT).contains(q)).toList();
    }
    public Optional<Entry> find(String id) { return events().stream().filter(e->e.id().toString().equals(id)).findFirst(); }
}
