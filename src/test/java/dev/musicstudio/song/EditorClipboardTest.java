package dev.musicstudio.song;

import dev.musicstudio.song.SongProject.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorClipboardTest {
    private Set<String> firstNotes(SongProject s) {return Set.of(s.patterns.getFirst().notes.get(2).get(0).id,s.patterns.getFirst().notes.get(2).get(1).id);}
    @Test void noteGroupPreservesTimingIntervalsPitchVelocityAndIndependentIds() {
        SongProject s=SongProject.legacyDemo();var source=s.patterns.getFirst().notes.get(2);var ids=firstNotes(s);
        var clip=EditorClipboard.copyNotes(source,ids);int before=source.size();
        var pasted=clip.pasteNotes(s,0,2,16*SongProject.STEP);
        assertEquals(2,pasted.size());assertEquals(before+2,source.size());assertTrue(Collections.disjoint(ids,pasted));
        var added=source.stream().filter(n->pasted.contains(n.id)).toList();
        assertEquals(16*SongProject.STEP,added.get(0).start);assertEquals(19*SongProject.STEP,added.get(1).start);
        assertEquals(67,added.get(0).pitch);assertEquals(72,added.get(1).pitch);
        assertEquals(100,added.get(1).velocity);assertEquals(2*SongProject.STEP,added.get(1).duration);s.validate();
    }
    @Test void cutClipboardSurvivesSourceDeletionMutationAndUndoSnapshot() {
        SongProject s=SongProject.legacyDemo();var source=s.patterns.getFirst().notes.get(2);var ids=firstNotes(s);
        var captured=EditorClipboard.copyNotes(source,ids);String undo=ProjectStore.encode(s);
        source.getFirst().pitch=99;source.removeIf(n->ids.contains(n.id));
        var pasted=captured.pasteNotes(s,0,2,0);
        assertEquals(67,s.patterns.getFirst().notes.get(2).stream().filter(n->pasted.contains(n.id)&&n.start==0).findFirst().orElseThrow().pitch);
        SongProject restored=ProjectStore.decode(undo);int count=restored.patterns.getFirst().notes.get(2).size();captured.pasteNotes(restored,0,2,0);assertEquals(count+2,restored.patterns.getFirst().notes.get(2).size());
    }
    @Test void notePasteCanTargetAnotherChannelAndAnotherProject() {
        SongProject s=SongProject.legacyDemo(),other=SongProject.legacyDemo();var clip=EditorClipboard.copyNotes(s.patterns.getFirst().notes.get(2),firstNotes(s));
        int untouched=other.patterns.getFirst().notes.get(2).size();int before=other.patterns.get(1).notes.get(5).size();
        clip.pasteNotes(other,1,5,SongProject.STEP);
        assertEquals(before+2,other.patterns.get(1).notes.get(5).size());assertEquals(untouched,other.patterns.getFirst().notes.get(2).size());other.validate();
    }
    @Test void outOfBoundsNotePasteDoesNotPartiallyPasteOrChangeSource() {
        SongProject s=SongProject.legacyDemo();var clip=EditorClipboard.copyNotes(s.patterns.getFirst().notes.get(2),firstNotes(s));String before=ProjectStore.encode(s);
        assertThrows(IllegalArgumentException.class,()->clip.pasteNotes(s,0,2,31*SongProject.STEP));assertEquals(before,ProjectStore.encode(s));
        assertThrows(IllegalArgumentException.class,()->clip.pasteNotes(s,0,2,-1));assertEquals(before,ProjectStore.encode(s));
    }
    @Test void copiedClipsRetainTracksRelativeStartsAndLivePatternReferences() {
        SongProject s=SongProject.legacyDemo();var a=s.clips.get(0);var b=s.clips.get(4);var captured=EditorClipboard.copyClips(s,Set.of(a.id,b.id));int patterns=s.patterns.size();
        var created=captured.pasteClips(s,8*4*SongProject.PPQ);var added=s.clips.stream().filter(c->created.contains(c.id)).toList();
        assertEquals(2,added.size());assertEquals(a.channel,added.get(0).channel);assertEquals(b.channel,added.get(1).channel);
        assertEquals(b.start-a.start,added.get(1).start-added.get(0).start);assertEquals(a.pattern,added.get(0).pattern);assertEquals(patterns,s.patterns.size());assertNotEquals(a.id,added.get(0).id);s.validate();
    }
    @Test void stablePatternIdentitySurvivesUndoAndPatternIndexReorder() {
        SongProject s=SongProject.legacyDemo();Clip source=s.clips.getFirst();String patternId=s.patterns.get(source.pattern).id;
        var captured=EditorClipboard.copyClips(s,Set.of(source.id));SongProject restored=ProjectStore.copy(s);
        Collections.swap(restored.patterns,0,1);restored.clips.forEach(c->c.pattern=1-c.pattern);
        var ids=captured.pasteClips(restored,8*4*SongProject.PPQ);Clip pasted=restored.clips.stream().filter(c->ids.contains(c.id)).findFirst().orElseThrow();
        assertEquals(patternId,restored.patterns.get(pasted.pattern).id);restored.validate();
    }
    @Test void missingPatternRestoresASnapshotWithFreshNoteIds() {
        SongProject s=SongProject.legacyDemo();Clip source=s.clips.getFirst();String patternId=s.patterns.get(source.pattern).id;
        var captured=EditorClipboard.copyClips(s,Set.of(source.id));
        s.clips.clear();s.patterns.removeFirst();
        var ids=captured.pasteClips(s,0);Clip pasted=s.clips.stream().filter(c->ids.contains(c.id)).findFirst().orElseThrow();
        assertEquals(patternId,s.patterns.get(pasted.pattern).id);assertFalse(s.patterns.get(pasted.pattern).notes.getFirst().isEmpty());s.validate();
    }
    @Test void crossProjectAndOverflowClipPastesAreAtomic() {
        SongProject s=SongProject.legacyDemo();var captured=EditorClipboard.copyClips(s,Set.of(s.clips.getFirst().id));
        SongProject other=SongProject.legacyDemo();String before=ProjectStore.encode(other);assertThrows(IllegalArgumentException.class,()->captured.pasteClips(other,0));assertEquals(before,ProjectStore.encode(other));
        before=ProjectStore.encode(s);assertThrows(IllegalArgumentException.class,()->captured.pasteClips(s,s.endPpq-SongProject.STEP));assertEquals(before,ProjectStore.encode(s));
    }
    @Test void emptySelectionDoesNotCreateContentAndKindIsEnforced() {
        SongProject s=SongProject.legacyDemo();assertEquals(EditorClipboard.Kind.EMPTY,EditorClipboard.copyNotes(s.patterns.getFirst().notes.getFirst(),Set.of()).kind());
        var clip=EditorClipboard.copyClips(s,Set.of(s.clips.getFirst().id));String before=ProjectStore.encode(s);assertThrows(IllegalArgumentException.class,()->clip.pasteNotes(s,0,0,0));assertEquals(before,ProjectStore.encode(s));
    }
    @Test void legacyProjectsAcquireStableIdentityOnFirstSave() {
        String json=ProjectStore.encode(SongProject.legacyDemo());var obj=ProjectStore.GSON.fromJson(json,com.google.gson.JsonObject.class);obj.remove("projectId");obj.getAsJsonArray("patterns").forEach(p->p.getAsJsonObject().remove("id"));
        var loaded=ProjectStore.decode(obj.toString());var copied=ProjectStore.copy(loaded);
        assertEquals(loaded.projectId,copied.projectId);assertEquals(loaded.patterns.getFirst().id,copied.patterns.getFirst().id);
    }
}
