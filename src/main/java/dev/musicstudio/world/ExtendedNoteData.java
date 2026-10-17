package dev.musicstudio.world;

import dev.musicstudio.blueprint.NoteBlockVoice;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Per-dimension controls survive saves and chunk unloads without changing vanilla block states. */
public final class ExtendedNoteData extends SavedData {
    private final Map<Long,NoteBlockVoice> voices=new HashMap<>();
    public static ExtendedNoteData get(ServerLevel level){return level.getDataStorage().computeIfAbsent(new Factory<>(ExtendedNoteData::new,ExtendedNoteData::load),"musicstudio_note_voices");}
    public NoteBlockVoice voice(BlockPos pos){return voices.get(pos.asLong());}
    public void put(BlockPos pos,NoteBlockVoice voice){if(voice==null){if(voices.remove(pos.asLong())!=null)setDirty();}else if(!voice.equals(voices.put(pos.asLong(),voice)))setDirty();}
    public static ExtendedNoteData load(CompoundTag tag,HolderLookup.Provider registries){
        var data=new ExtendedNoteData();for(var entry:tag.getList("voices",Tag.TAG_COMPOUND)){var n=(CompoundTag)entry;try{data.voices.put(n.getLong("pos"),new NoteBlockVoice(n.getInt("note"),n.getInt("velocity"),n.getInt("duration"),n.getFloat("volume")));}catch(IllegalArgumentException ignored){}}
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        var list=new ListTag();voices.forEach((pos,v)->{var n=new CompoundTag();n.putLong("pos",pos);n.putInt("note",v.note());n.putInt("velocity",v.velocity());n.putInt("duration",v.durationTicks());n.putFloat("volume",v.volume());list.add(n);});tag.put("voices",list);return tag;
    }
}
