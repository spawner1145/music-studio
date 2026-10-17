package dev.musicstudio.blueprint;

import dev.musicstudio.song.SongProject;
import java.util.*;

/** Immutable, server-recompiled vanilla block plan. No playback service is involved. */
public record MusicBlueprint(String title,int x,int y,int z,boolean loop,int period,
        List<Voice> voices,List<Note> notes,List<Cell> route,double maxErrorMs,int shortened,int pitchLimited,
        List<Part> parts,List<Output> outputs,Cell lever,int maxX,int maxY,int maxZ) {
    public static final int MAX_BLOCKS=32768,MAX_NOTES=4096,MAX_VOLUME=131072;
    public record Cell(int x,int y,int z) {
        public int distance(Cell c){return Math.abs(x-c.x)+Math.abs(y-c.y)+Math.abs(z-c.z);}
        public Cell offset(int x,int y,int z){return new Cell(this.x+x,this.y+y,this.z+z);}
    }
    public record Voice(String sound,float volume,int baseMidi,boolean tunable,boolean command) {}
    public record Note(int voice,int tick,int duration,int pitch,int velocity) {}
    public record Part(Cell pos,String block,String facing,int value,String instrument,String command,NoteBlockVoice noteVoice) {
        public Part(Cell pos,String block,String facing,int value,String instrument,String command){this(pos,block,facing,value,instrument,command,null);}
    }
    public record Output(Cell pos,int tick,String instrument,int note,boolean command) {}
    public Cell rotate(Cell c,int r){return switch(r&3){case 1->new Cell(z-1-c.z,c.y,c.x);case 2->new Cell(x-1-c.x,c.y,z-1-c.z);case 3->new Cell(c.z,c.y,x-1-c.x);default->c;};}
    public int width(int r){return (r&1)==0?x:z;}
    public int depth(int r){return (r&1)==0?z:x;}
    public boolean fitsRotation(int r){return width(r)<=maxX&&depth(r)<=maxZ&&y<=maxY;}
    public static MusicBlueprint compile(SongProject song){if(song.schemaVersion==1&&dev.musicstudio.song.ExportRange.of(song).full()&&song.events(0,false).isEmpty())throw new dev.musicstudio.song.StudioException("error.blueprint_empty");return VanillaCompiler.compile(dev.musicstudio.song.ExportRange.snapshot(song));}
    public static MusicBlueprint preview(SongProject song){return VanillaCompiler.compile(dev.musicstudio.song.ExportRange.snapshot(song),false);}
    public static boolean noteBlockSound(String sound){return !VanillaCompiler.instrument(sound).isEmpty();}
    public static MusicBlueprint legacySnapshot(SongProject song){return VanillaCompiler.compile(song,false,false);}
}
