package dev.musicstudio.song;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChannelRemovalTest {
    @Test void removesAllPatternRowsAndClipsWithoutChangingOtherVoices() {
        var s=SongProject.legacyDemo();var removed=s.channels.get(2).id;
        var surviving=s.channels.get(3);var notes=s.patterns.get(1).notes.get(3);
        int expectedClips=(int)s.clips.stream().filter(c->c.channel!=2).count();
        var clip=s.clips.stream().filter(c->c.channel==3).findFirst().orElseThrow();String clipId=clip.id;
        s.removeChannel(2);
        assertEquals(7,s.channels.size());assertTrue(s.channels.stream().noneMatch(c->c.id.equals(removed)));
        assertSame(surviving,s.channels.get(2));assertSame(notes,s.patterns.get(1).notes.get(2));
        assertEquals(expectedClips,s.clips.size());assertEquals(2,s.clips.stream().filter(c->c.id.equals(clipId)).findFirst().orElseThrow().channel);
        for(var p:s.patterns)assertEquals(7,p.notes.size());s.validate();
    }
    @Test void removalCanBeRestoredExactlyIncludingIdsMusicAndLanguageLabels() {
        var s=SongProject.legacyDemo();String before=ProjectStore.encode(s);s.removeChannel(0);s.validate();
        var undo=ProjectStore.decode(before);assertEquals(before,ProjectStore.encode(undo));
        assertEquals("channel.kick",undo.channels.getFirst().nameKey);assertEquals(8,undo.channels.size());
    }
    @Test void copiedClipsFollowStableChannelIdentityWhenEarlierTrackIsRemoved() {
        var s=SongProject.legacyDemo();var source=s.clips.stream().filter(c->c.channel==3).findFirst().orElseThrow();String voice=s.channels.get(3).id;
        var clipboard=EditorClipboard.copyClips(s,Set.of(source.id));s.removeChannel(0);
        var ids=clipboard.pasteClips(s,0);var pasted=s.clips.stream().filter(c->ids.contains(c.id)).findFirst().orElseThrow();
        assertEquals(2,pasted.channel);assertEquals(voice,s.channels.get(pasted.channel).id);s.validate();
    }
    @Test void pastingADeletedChannelIsRejectedWithoutWritingToItsSuccessor() {
        var s=SongProject.legacyDemo();var source=s.clips.stream().filter(c->c.channel==2).findFirst().orElseThrow();var copied=EditorClipboard.copyClips(s,Set.of(source.id));
        s.removeChannel(2);String before=ProjectStore.encode(s);
        assertThrows(StudioException.class,()->copied.pasteClips(s,0));assertEquals(before,ProjectStore.encode(s));
    }
    @Test void firstLastAndFinalRemainingChannelHaveDefinedBehavior() {
        var s=SongProject.legacyDemo();s.removeChannel(s.channels.size()-1);s.validate();
        while(s.channels.size()>1)s.removeChannel(0);
        String before=ProjectStore.encode(s);assertThrows(StudioException.class,()->s.removeChannel(0));assertEquals(before,ProjectStore.encode(s));
        assertThrows(StudioException.class,()->s.removeChannel(-1));assertThrows(StudioException.class,()->s.removeChannel(1));
    }
    @Test void oldProjectsAcquireUniqueStableChannelIdsOnFirstSave() {
        var json=ProjectStore.GSON.fromJson(ProjectStore.encode(SongProject.legacyDemo()),com.google.gson.JsonObject.class);
        json.getAsJsonArray("channels").forEach(c->{c.getAsJsonObject().remove("id");c.getAsJsonObject().remove("nameKey");});
        var old=ProjectStore.decode(json.toString());assertEquals(old.channels.size(),old.channels.stream().map(c->c.id).distinct().count());
        var saved=ProjectStore.copy(old);assertEquals(old.channels.getFirst().id,saved.channels.getFirst().id);assertEquals("",saved.channels.getFirst().nameKey);
    }
}
