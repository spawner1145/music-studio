package dev.musicstudio.song;

import dev.musicstudio.song.SongProject.*;
import java.util.*;

/** Immutable value clipboard. Failed pastes are atomic; no source Note/Clip references escape. */
public final class EditorClipboard {
    public enum Kind { EMPTY, NOTES, CLIPS }
    private record NoteValue(int start,int duration,int pitch,int velocity) {
        static NoteValue of(Note n,int origin) { return new NoteValue(n.start-origin,n.duration,n.pitch,n.velocity); }
        Note create(int origin) { Note n=new Note();n.start=start+origin;n.duration=duration;n.pitch=pitch;n.velocity=velocity;return n; }
    }
    private record ClipValue(String channelId,String trackId,String audioId,String patternId,int start,int duration,int contentOffset,int contentLength,boolean fixedPatternWindow) {}
    private record PatternValue(String id,String name,String nameKey,int length,int color,List<String> sources,List<List<NoteValue>> notes) {
        static PatternValue of(Pattern p,SongProject song) { return new PatternValue(p.id,p.name,p.nameKey,p.length,p.color,song.channels.stream().map(c->c.id).toList(),p.notes.stream().map(a->a.stream().map(n->NoteValue.of(n,0)).toList()).toList()); }
        Pattern create(SongProject song) { Pattern p=new Pattern(name,song.channels.size());p.id=id;p.nameKey=nameKey;p.length=length;p.color=color;for(int i=0;i<song.channels.size();i++){int old=sources.indexOf(song.channels.get(i).id);if(old>=0)p.notes.set(i,new ArrayList<>(notes.get(old).stream().map(n->n.create(0)).toList()));}return p; }
    }
    public static final EditorClipboard EMPTY=new EditorClipboard(Kind.EMPTY,"",List.of(),List.of(),Map.of(),0);
    private final Kind kind;
    private final String projectId;
    private final List<NoteValue> notes;
    private final List<ClipValue> clips;
    private final Map<String,PatternValue> patterns;
    private final int span;
    private EditorClipboard(Kind kind,String projectId,List<NoteValue> notes,List<ClipValue> clips,Map<String,PatternValue> patterns,int span) {
        this.kind=kind;this.projectId=projectId;this.notes=List.copyOf(notes);this.clips=List.copyOf(clips);this.patterns=Map.copyOf(patterns);this.span=span;
    }
    public Kind kind() { return kind; }
    public int size() { return kind==Kind.NOTES?notes.size():clips.size(); }
    public int span() { return span; }
    public static EditorClipboard copyNotes(List<Note> source,Set<String> selection) {
        var selected=source.stream().filter(n->selection.contains(n.id)).toList();
        if(selected.isEmpty())return EMPTY;
        int origin=selected.stream().mapToInt(n->n.start).min().orElseThrow();
        int end=selected.stream().mapToInt(n->n.start+n.duration).max().orElseThrow();
        return new EditorClipboard(Kind.NOTES,"",selected.stream().map(n->NoteValue.of(n,origin)).toList(),List.of(),Map.of(),end-origin);
    }
    public static EditorClipboard copyClips(SongProject song,Set<String> selection) {
        var selected=song.clips.stream().filter(c->selection.contains(c.id)).toList();
        if(selected.isEmpty())return EMPTY;
        int origin=selected.stream().mapToInt(c->c.start).min().orElseThrow(),end=selected.stream().mapToInt(c->c.start+c.duration).max().orElseThrow();
        Map<String,PatternValue> snapshots=new HashMap<>();List<ClipValue> values=new ArrayList<>();
        for(Clip c:selected){Pattern p=c.audio()?null:song.patterns.get(c.pattern);if(p!=null)snapshots.computeIfAbsent(p.id,id->PatternValue.of(p,song));values.add(new ClipValue(song.schemaVersion==1?song.channels.get(c.channel).id:"",song.schemaVersion==2?song.tracks.get(c.track).id:"",c.audio()?song.audioSources.get(c.audioSource).id:"",p==null?"":p.id,c.start-origin,c.duration,c.contentOffset,c.contentLength,song.hasFixedPatternWindow(c)));}
        return new EditorClipboard(Kind.CLIPS,song.projectId,List.of(),values,snapshots,end-origin);
    }
    public void checkNotes(SongProject song,int pattern,int channel,int destination) {
        if(kind!=Kind.NOTES)throw new StudioException("error.clipboard_notes");
        if(pattern<0||pattern>=song.patterns.size()||channel<0||channel>=song.channels.size())throw new StudioException("error.target_channel");
        if(destination<0||(long)destination+span>(song.schemaVersion==1?song.patterns.get(pattern).length:SongProject.MAX_SONG_PPQ))throw new StudioException("error.paste_note_bounds");
        int total=noteCount(song);if((long)total+notes.size()>SongProject.MAX_EVENTS)throw new StudioException("error.paste_note_limit");
    }
    public Set<String> pasteNotes(SongProject song,int pattern,int channel,int destination) {
        checkNotes(song,pattern,channel,destination);
        List<Note> created=notes.stream().map(n->n.create(destination)).toList();
        song.patterns.get(pattern).notes.get(channel).addAll(created);
        if(song.schemaVersion==2)song.growPattern(pattern);
        LinkedHashSet<String> ids=new LinkedHashSet<>();created.forEach(n->ids.add(n.id));return ids;
    }
    public void checkClips(SongProject song,int destination) {
        if(kind!=Kind.CLIPS)throw new StudioException("error.clipboard_clips");
        if(!song.projectId.equals(projectId))throw new StudioException("error.clipboard_project");
        if(destination<0||(long)destination+span>song.endPpq)throw new StudioException("error.paste_clip_bounds");
        if(song.clips.size()+clips.size()>4096)throw new StudioException("error.paste_clip_limit");
        Map<String,Integer> indices=indices(song);int missing=0,additionalNotes=0;
        for(var e:patterns.entrySet())if(!indices.containsKey(e.getKey())) {
            missing++;PatternValue p=e.getValue();
            if(song.schemaVersion==1&&p.notes.size()!=song.channels.size())throw new StudioException("error.clipboard_pattern_channels");
            additionalNotes+=p.notes.stream().mapToInt(List::size).sum();
        }
        if(song.patterns.size()+missing>(song.schemaVersion==1?128:4096)||noteCount(song)+additionalNotes>SongProject.MAX_EVENTS)throw new StudioException("error.clipboard_pattern_limit");
        for(ClipValue c:clips){if(song.schemaVersion==1&&song.channels.stream().noneMatch(ch->ch.id.equals(c.channelId)))throw new StudioException("error.clip_channel_missing");if(song.schemaVersion==2&&song.tracks.stream().noneMatch(t->t.id.equals(c.trackId)))throw new StudioException("error.clip_track_missing");if(!c.audioId.isEmpty()&&song.audioSources.stream().noneMatch(a->a.id.equals(c.audioId)))throw new StudioException("error.clip_audio_missing");}
    }
    /** Clips preserve their original tracks and Pattern references, just like ordinary linked copies. */
    public Set<String> pasteClips(SongProject song,int destination) {
        checkClips(song,destination);Map<String,Integer> indices=indices(song);
        List<Pattern> missing=new ArrayList<>();for(var e:patterns.entrySet())if(!indices.containsKey(e.getKey())){indices.put(e.getKey(),song.patterns.size()+missing.size());missing.add(e.getValue().create(song));}
        Map<String,Integer> channels=new HashMap<>();for(int i=0;i<song.channels.size();i++)channels.put(song.channels.get(i).id,i);
        Map<String,Integer> tracks=new HashMap<>(),audio=new HashMap<>();for(int i=0;i<song.tracks.size();i++)tracks.put(song.tracks.get(i).id,i);for(int i=0;i<song.audioSources.size();i++)audio.put(song.audioSources.get(i).id,i);
        List<Clip> created=new ArrayList<>();for(ClipValue v:clips){Clip c=new Clip();if(song.schemaVersion==1)c.channel=channels.get(v.channelId);else c.track=tracks.get(v.trackId);c.audioSource=v.audioId.isEmpty()?-1:audio.get(v.audioId);c.pattern=c.audio()?-1:indices.get(v.patternId);c.start=destination+v.start;c.duration=v.duration;c.contentOffset=v.contentOffset;c.contentLength=v.contentLength;c.fixedPatternWindow=v.fixedPatternWindow;created.add(c);}
        song.patterns.addAll(missing);song.clips.addAll(created);LinkedHashSet<String> ids=new LinkedHashSet<>();created.forEach(c->ids.add(c.id));return ids;
    }
    private static Map<String,Integer> indices(SongProject song) {Map<String,Integer> result=new HashMap<>();for(int i=0;i<song.patterns.size();i++)result.put(song.patterns.get(i).id,i);return result;}
    private static int noteCount(SongProject song){return song.patterns.stream().flatMap(p->p.notes.stream()).mapToInt(List::size).sum();}
}
