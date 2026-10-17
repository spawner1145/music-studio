package dev.musicstudio.media;

import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/** Signed stereo peak envelope of the actual PCM sum. Kept instead of the full decoded song. */
public final class SongWaveform {
    public static final int FRAMES_PER_BIN=32;
    private final float[] low,high;
    private final int frames;
    private final float scale;
    private SongWaveform(float[] low,float[] high,int frames,float scale){this.low=low;this.high=high;this.frames=frames;this.scale=scale;}
    public int frames(){return frames;}
    public double seconds(){return frames/(double)AudioFiles.RATE;}
    public float scale(){return scale;}
    public static SongWaveform from(AudioFiles.Mix mix,BooleanSupplier cancelled){
        var wave=mix.wave();int count=(wave.frames()+FRAMES_PER_BIN-1)/FRAMES_PER_BIN;float[] low=new float[count],high=new float[count];
        for(int bin=0;bin<count;bin++){if((bin&255)==0&&(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean()))throw new CancellationException();
            int end=Math.min(wave.frames(),(bin+1)*FRAMES_PER_BIN);for(int i=bin*FRAMES_PER_BIN;i<end;i++){float l=wave.left()[i]*mix.scale(),r=wave.right()[i]*mix.scale();low[bin]=Math.min(low[bin],Math.min(l,r));high[bin]=Math.max(high[bin],Math.max(l,r));}
        }return new SongWaveform(low,high,wave.frames(),mix.scale());
    }
    public record Columns(float[] low,float[] high){}
    /** Empty ranges remain zero; stopped silence and rests never acquire decorative movement. */
    public Columns columns(double startSeconds,double endSeconds,int width){
        if(width<1||width>8192||!Double.isFinite(startSeconds)||!Double.isFinite(endSeconds)||endSeconds<=startSeconds)throw new IllegalArgumentException("Invalid waveform range");
        float[] l=new float[width],h=new float[width];double a=startSeconds*AudioFiles.RATE/FRAMES_PER_BIN,span=(endSeconds-startSeconds)*AudioFiles.RATE/FRAMES_PER_BIN;
        for(int x=0;x<width;x++){int first=(int)Math.floor(a+span*x/width),last=(int)Math.ceil(a+span*(x+1)/width);for(int i=Math.max(0,first);i<Math.min(low.length,last);i++){l[x]=Math.min(l[x],low[i]);h[x]=Math.max(h[x],high[i]);}}return new Columns(l,h);
    }
}
