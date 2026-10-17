package dev.musicstudio.client;

import dev.musicstudio.song.SongProject;

/** View limits are numerical PPQ precision, independent of the current song end. */
public final class PlaylistView {
    public static final double BAR=4.0*SongProject.PPQ,MIN_BARS=1/BAR,MAX_BARS=SongProject.MAX_SONG_PPQ/BAR;
    public double start,bars=12,vertical,trackZoom=1;
    public void pan(double value){start=Math.max(0,Math.min(MAX_BARS-bars,value));}
    public void zoom(double wheel,double anchor){
        anchor=Math.max(0,Math.min(1,anchor));double time=start+bars*anchor;
        bars=Math.max(MIN_BARS,Math.min(MAX_BARS,bars*Math.pow(1.2,-wheel)));pan(time-bars*anchor);
    }
    public void range(double left,double right){bars=Math.max(MIN_BARS,Math.min(MAX_BARS,right-left));pan(left);}
    public double extent(double songBars){return Math.min(MAX_BARS,Math.max(songBars,Math.max(bars*4,start+bars*3)));}
    public static double thumb(double length,double visible,double total){return Math.min(length,Math.max(18,length*visible/Math.max(visible,total)));}
}
