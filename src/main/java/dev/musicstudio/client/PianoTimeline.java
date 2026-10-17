package dev.musicstudio.client;

import dev.musicstudio.song.SongProject;
import dev.musicstudio.song.SongProject.Clip;
import java.util.OptionalDouble;

/** Maps a Pattern's local time to its placed, cropped and repeated Playlist instances. */
public final class PianoTimeline {
    private PianoTimeline(){}
    public static double position(SongProject song,int pattern,double songTime,String preferredClip){
        Clip best=null;
        for(Clip c:song.clips)if(!c.audio()&&c.pattern==pattern){
            double source=songTime-c.start-c.contentOffset;
            if(songTime<c.start||songTime>=Math.min(song.endPpq,c.start+c.duration)||source<0||source>=song.clipSourceLength(c))continue;
            if(best==null||c.id.equals(preferredClip))best=c;
            if(c.id.equals(preferredClip))break;
        }
        return best==null?Double.NaN:(songTime-best.start-best.contentOffset)%song.patterns.get(pattern).length;
    }
    public static OptionalDouble seek(SongProject song,int pattern,int local,double around,String preferredClip){
        double best=0,distance=Double.POSITIVE_INFINITY;boolean found=false,preferred=false;int length=song.patterns.get(pattern).length;
        for(Clip c:song.clips)if(!c.audio()&&c.pattern==pattern){
            long origin=(long)c.start+c.contentOffset;
            double left=Math.max(c.start,origin),right=Math.min(Math.min(song.endPpq,c.start+c.duration),origin+song.clipSourceLength(c));
            long first=Math.max(0,(long)Math.ceil((left-origin-local)/length)),last=(long)Math.ceil((right-origin-local)/length)-1;
            if(first>last)continue;
            long cycle=Math.max(first,Math.min(last,Math.round((around-origin-local)/length)));double at=origin+cycle*(double)length+local,delta=Math.abs(at-around);boolean current=c.id.equals(preferredClip);
            if(!found||current&&!preferred||current==preferred&&delta<distance){best=at;distance=delta;found=true;preferred=current;}
        }
        return found?OptionalDouble.of(best):OptionalDouble.empty();
    }
}
