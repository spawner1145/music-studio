package dev.musicstudio.song;

/** Ruler seeks use the same cell-start convention as Playlist clip placement. */
public final class TimelineSnap {
    private TimelineSnap(){}
    public static int position(double ppq,int steps,int endPpq){
        if(!Double.isFinite(ppq)||steps<1||endPpq<1)throw new IllegalArgumentException("Invalid ruler range");
        int interval=steps*SongProject.STEP,last=(endPpq-1)/interval*interval;
        return (int)Math.min(last,Math.floor(Math.max(0,ppq)/interval)*interval);
    }
    public static int at(double x,double left,double width,int start,int span,int steps,int endPpq){
        if(width<=0||span<1)throw new IllegalArgumentException("Invalid ruler geometry");
        double ratio=Math.max(0,Math.min(1,(x-left)/width));
        return position(start+ratio*span,steps,Math.min(endPpq,start+span));
    }
}
