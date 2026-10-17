package dev.musicstudio.client;

import dev.musicstudio.song.SongProject;

/** Window geometry and independent musical viewport; no score mutations. */
public final class PianoView {
    public static final float MIN_WIDTH=720,MIN_HEIGHT=360,AREA_WIDTH=1280,AREA_HEIGHT=806;
    public static final double MIN_SPAN=1.0/SongProject.STEP,MAX_TIME=SongProject.MAX_SONG_PPQ/(double)SongProject.STEP;
    public float x=230,y=305,width=920,height=461;
    public double start,span=32,top=48,rows=16;
    public boolean maximized;
    private float savedX=x,savedY=y,savedWidth=width,savedHeight=height;
    public float gridX(){return x+49;}public float gridY(){return y+81;}
    public float gridWidth(){return width-70;}public float gridHeight(){return height-209;}
    public float horizontalY(){return y+height-84;}
    public float verticalX(){return x+width-18;}
    public boolean contains(float px,float py){return px>=x&&px<x+width&&py>=y&&py<y+height;}
    public void toggleMaximize(){
        if(!maximized){savedX=x;savedY=y;savedWidth=width;savedHeight=height;x=y=0;width=AREA_WIDTH;height=AREA_HEIGHT;}
        else{x=savedX;y=savedY;width=savedWidth;height=savedHeight;}maximized=!maximized;
    }
    public void bounds(float left,float upper,float w,float h){
        width=clamp(w,MIN_WIDTH,AREA_WIDTH);height=clamp(h,MIN_HEIGHT,AREA_HEIGHT);x=clamp(left,0,AREA_WIDTH-width);y=clamp(upper,0,AREA_HEIGHT-height);
    }
    public void resize(float left,float upper,float w,float h,int edges,float dx,float dy){
        if(maximized)return;float right=left+w,bottom=upper+h;
        if((edges&1)!=0)left=clamp(left+dx,0,right-MIN_WIDTH);
        if((edges&2)!=0)right=clamp(right+dx,left+MIN_WIDTH,AREA_WIDTH);
        if((edges&4)!=0)upper=clamp(upper+dy,0,bottom-MIN_HEIGHT);
        if((edges&8)!=0)bottom=clamp(bottom+dy,upper+MIN_HEIGHT,AREA_HEIGHT);
        bounds(left,upper,right-left,bottom-upper);
    }
    public void clamp(int length){span=Math.max(MIN_SPAN,Math.min(MAX_TIME,span));start=Math.max(0,Math.min(MAX_TIME-span,start));rows=Math.max(3,Math.min(128,rows));top=Math.max(0,Math.min(128-rows,top));}
    public double extent(int length){return Math.min(MAX_TIME,Math.max(length,Math.max(span*4,start+span*3)));}
    public void zoomTime(double wheel,double anchor,int length){anchor=Math.max(0,Math.min(1,anchor));double time=start+span*anchor;span*=Math.pow(1.2,-wheel);clamp(length);start=time-span*anchor;clamp(length);}
    public void zoomPitch(double wheel,double anchor,int length){anchor=Math.max(0,Math.min(1,anchor));double pitch=top+rows*anchor;rows*=Math.pow(1.15,-wheel);clamp(length);top=pitch-rows*anchor;clamp(length);}
    public void timeRange(double left,double right,int length){span=right-left;start=left;clamp(length);}
    public void pitchRange(double upper,double lower,int length){rows=lower-upper;top=upper;clamp(length);}
    public double timeAt(double px){return start+(px-gridX())/gridWidth()*span;}
    public int pitchAt(double py){return Math.max(0,Math.min(127,127-(int)Math.floor(top+(py-gridY())/gridHeight()*rows)));}
    public float timeX(double step){return gridX()+(float)((step-start)/span*gridWidth());}
    public float pitchY(int pitch){return gridY()+(float)((127-pitch-top)/rows*gridHeight());}
    private static float clamp(float n,float min,float max){return Math.max(min,Math.min(max,n));}
}
