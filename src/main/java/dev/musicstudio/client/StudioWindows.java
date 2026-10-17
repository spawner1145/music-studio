package dev.musicstudio.client;

import java.util.*;

/** Window state is separate from song data and undo history. */
public final class StudioWindows {
    public record Bounds(float x,float y,float width,float height){}
    public static final class State { public boolean folded,maximized;public Bounds normal;public String title=""; }
    private final Map<String,State> states=new LinkedHashMap<>();
    public State get(String id){return states.computeIfAbsent(id,key->new State());}
    public Bounds bounds(String id,float x,float y,float width,float height){State s=get(id);s.normal=new Bounds(x,y,width,height);return s.maximized?new Bounds(0,0,1280,806):s.normal;}
    public void toggleMaximize(String id){State s=get(id);s.folded=false;s.maximized=!s.maximized;}
    public void toggleFold(String id){State s=get(id);s.folded=!s.folded;}
    public void close(String id){get(id).folded=false;}
    public List<String> folded(){return states.entrySet().stream().filter(e->e.getValue().folded).map(Map.Entry::getKey).toList();}
}
