package dev.musicstudio.song;

/** Half-open export range; order-independent cursors and exact leading/trailing rests. */
public record ExportRange(int start,int end,boolean full) {
    public int duration(){return end-start;}
    public static ExportRange of(SongProject song){return song.exportStart==song.exportEnd?new ExportRange(0,song.endPpq,true):new ExportRange(Math.min(song.exportStart,song.exportEnd),Math.max(song.exportStart,song.exportEnd),false);}
    public static int contentEnd(SongProject song){return song.events(0,false).stream().mapToInt(e->e.start()+e.duration()).max().orElse(1);}
    public static SongProject snapshot(SongProject source){
        return snapshot(source,of(source).full?contentEnd(source):source.endPpq);
    }
    /** The resolved window is explicit, so repeated preparation/compilation never trims it again. */
    public static SongProject snapshot(SongProject source,int fullEnd){
        SongProject result=ProjectStore.copy(source);ExportRange requested=of(source);
        ExportRange range=requested.full?new ExportRange(0,Math.max(1,Math.min(SongProject.MAX_SONG_PPQ,fullEnd)),false):requested;
        if(range.start==0&&range.end==source.endPpq){result.exportStart=0;result.exportEnd=range.end;return result;}
        result.upgrade();result.clips.removeIf(c->c.start>=range.end||c.start+c.duration<=range.start);
        for(var c:result.clips){int left=Math.max(c.start,range.start),right=Math.min(c.start+c.duration,range.end),length=result.clipSourceLength(c);c.fixedPatternWindow=result.hasFixedPatternWindow(c);c.contentOffset+=c.start-left;c.start=left-range.start;c.duration=right-left;c.contentLength=length;}
        result.endPpq=range.duration();result.exportStart=0;result.exportEnd=range.duration();if(!requested.full)result.blueprintAudio.clear();result.validate();return result;
    }
}
