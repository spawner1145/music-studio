package dev.musicstudio.blueprint;

import dev.musicstudio.song.*;
import dev.musicstudio.media.BlueprintAudio;
import dev.musicstudio.blueprint.MusicBlueprint.*;
import java.util.*;

/** Repeater delay is always measured in redstone ticks (two game ticks). */
final class VanillaCompiler {
    private static final Map<String,String> BASES=Map.ofEntries(
        Map.entry("harp","dirt"),Map.entry("basedrum","stone"),Map.entry("snare","sand"),Map.entry("hat","glass"),
        Map.entry("bass","oak_planks"),Map.entry("flute","clay"),Map.entry("bell","gold_block"),Map.entry("guitar","white_wool"),
        Map.entry("chime","packed_ice"),Map.entry("xylophone","bone_block"),Map.entry("iron_xylophone","iron_block"),
        Map.entry("cow_bell","soul_sand"),Map.entry("didgeridoo","pumpkin"),Map.entry("bit","emerald_block"),Map.entry("banjo","hay_block"),Map.entry("pling","glowstone"));
    private static final Map<String,String> HEADS=Map.of("zombie","zombie_head","skeleton","skeleton_skull","creeper","creeper_head","ender_dragon","dragon_head","wither_skeleton","wither_skeleton_skull","piglin","piglin_head");
    static String instrument(String sound){
        if(!sound.startsWith("minecraft:block.note_block."))return "";
        String name=sound.substring("minecraft:block.note_block.".length());
        if(BASES.containsKey(name))return name;
        if(name.startsWith("imitate.")&&HEADS.containsKey(name.substring(8))){String mob=name.substring(8);return mob.equals("ender_dragon")?"dragon":mob;}
        return "";
    }
    static int redTick(int ppq,int bpm){long d=(long)SongProject.PPQ*bpm;return (int)(((long)ppq*600+d/2)/d);}
    static MusicBlueprint compile(SongProject song){
        return compile(song,true);
    }
    static MusicBlueprint compile(SongProject song,boolean prepared){
        return compile(song,prepared,true);
    }
    static MusicBlueprint compile(SongProject song,boolean prepared,boolean hybrid){
        song.validate();var events=song.events(0,false);
        if(events.isEmpty()&&song.schemaVersion==1)throw new StudioException("error.blueprint_empty");
        if(events.size()>MusicBlueprint.MAX_NOTES)throw new StudioException("error.blueprint_notes",MusicBlueprint.MAX_NOTES);
        int period=song.loop?Math.max(12,4*(int)Math.ceil(song.endPpq*300.0/(SongProject.PPQ*song.bpm))):Math.max(2,2*(int)Math.ceil(song.endPpq*600.0/(SongProject.PPQ*song.bpm)));
        List<Voice> voices=new ArrayList<>();Map<Voice,Integer> voiceIds=new LinkedHashMap<>();
        Map<Note,NoteBlockVoice> controls=new IdentityHashMap<>();List<Note> notes=new ArrayList<>();SortedMap<Integer,List<Note>> groups=new TreeMap<>();double error=0;int clipped=0,changed=0;
        for(var e:events){var channel=song.voice(e.channel());boolean nativeSound=BlueprintAudio.noteBlock(channel),altered=hybrid&&BlueprintAudio.altered(song,channel,e);
            if(!nativeSound&&!song.commandSamples)throw new StudioException("error.vanilla_sample",channel.name,channel.sound);
            String sound=channel.sound;int base=channel.baseMidi;boolean tunable=channel.tunable;
            if(hybrid&&BlueprintAudio.needsAudio(song,channel,e)&&(song.schemaVersion>=2||nativeSound||e.offset()>0)){String hash=BlueprintAudio.prepared(song,channel,e);if(hash==null){if(prepared)throw new StudioException("error.blueprint_prepare");}else{sound="musicstudio:sample/"+hash;base=66;tunable=false;}}
            var voice=new Voice(sound,channel.volume,base,tunable,!nativeSound||altered);int voiceId=voiceIds.computeIfAbsent(voice,v->{voices.add(v);return voices.size()-1;});
            int on=Math.min(period-2,2*redTick(e.start(),song.bpm));
            int duration=Math.max(2,Math.min(period-on,2*(redTick(e.start()+e.duration(),song.bpm)-redTick(e.start(),song.bpm))));
            var n=new Note(voiceId,on,duration,e.pitch(),e.velocity());notes.add(n);if(hybrid&&BlueprintAudio.hooked(song,channel,e))controls.put(n,NoteBlockVoice.of(song,channel,e));groups.computeIfAbsent(on,k->new ArrayList<>()).add(n);
            double ideal=e.start()*1200.0/(SongProject.PPQ*song.bpm);error=Math.max(error,Math.abs(on+(voice.command()?1:0)-ideal)*50);
            int pitch=12+(voice.tunable()?e.pitch()-voice.baseMidi():0);if(!voice.command()&&!controls.containsKey(n)&&(pitch<0||pitch>24))clipped++;
            if(altered)changed++;
        }
        int chord=groups.values().stream().mapToInt(List::size).max().orElse(1);
        if(chord>16)throw new StudioException("error.vanilla_chord",chord,16);
        int height=Math.max(4,(chord+1)/2),stripe=height+3;
        int rows=(song.maxZ-2-height)/stripe+1,layers=(song.maxY-4)/4+1;
        if(song.maxX<16||rows<1||song.maxY<4)throw new StudioException("error.vanilla_space",song.maxX,song.maxZ,song.maxY);
        Builder b=new Builder(song,voices,notes,height,stripe,rows,layers);
        b.controls.putAll(controls);b.source(period);
        for(var group:groups.entrySet()){b.to(group.getKey()/2);int index=0;for(Note n:group.getValue())b.output(n,index++);}
        int sx=b.parts.keySet().stream().mapToInt(Cell::x).max().orElse(0)+1;
        int sy=Math.max(b.topAir,b.parts.keySet().stream().mapToInt(Cell::y).max().orElse(0)+1);
        int sz=b.parts.keySet().stream().mapToInt(Cell::z).max().orElse(0)+1;
        if(sx>song.maxX||sy>song.maxY||sz>song.maxZ||(long)sx*sy*sz>MusicBlueprint.MAX_VOLUME)throw new StudioException("error.vanilla_space",song.maxX,song.maxZ,song.maxY);
        List<Part> parts=new ArrayList<>(b.parts.values());
        // Supports before components; the start control remains unpowered throughout placement.
        parts.sort(Comparator.comparingInt((Part p)->p.pos().y()).thenComparingInt(p->p.block().equals("redstone_wire")?2:p.block().equals("note_block")||p.block().equals("command_block")?1:0));
        return new MusicBlueprint(song.title,sx,sy,sz,song.loop,period,List.copyOf(voices),List.copyOf(notes),parts.stream().map(Part::pos).toList(),error,changed,clipped,List.copyOf(parts),List.copyOf(b.outputs),new Cell(0,3,2),song.maxX,song.maxY,song.maxZ);
    }
    private static final class Builder {
        final SongProject song;final List<Voice> voices;final List<Note> notes;final int height,stripe,rows,layers,left=9,right;
        final Map<Note,NoteBlockVoice> controls=new IdentityHashMap<>();final Map<Cell,Part> parts=new LinkedHashMap<>();final List<Output> outputs=new ArrayList<>();
        int x=9,y=3,z=1,dx=1,dz=1,row,layer,time,topAir=4,maxDelay=4;
        Builder(SongProject song,List<Voice> voices,List<Note> notes,int height,int stripe,int rows,int layers){this.song=song;this.voices=voices;this.notes=notes;this.height=height;this.stripe=stripe;this.rows=rows;this.layers=layers;right=left+4*((song.maxX-3-left)/4);}
        void put(Cell p,String block,String facing,int value,String instrument,String command){
            if(p.x()<0||p.y()<0||p.z()<0||p.x()>=song.maxX||p.y()>=song.maxY||p.z()>=song.maxZ)throw new StudioException("error.vanilla_space",song.maxX,song.maxZ,song.maxY);
            Part part=new Part(p,block,facing,value,instrument,command),old=parts.putIfAbsent(p,part);
            if(old!=null&&!old.equals(part))throw new StudioException("error.vanilla_layout");
            if(parts.size()>MusicBlueprint.MAX_BLOCKS)throw new StudioException("error.vanilla_blocks",MusicBlueprint.MAX_BLOCKS);
        }
        void solid(Cell p,String block){put(p,block,"",0,"","");}
        void dust(Cell p){solid(p.offset(0,-1,0),"stone");put(p,"redstone_wire","",0,"","");}
        void repeater(Cell p,String facing,int delay){solid(p.offset(0,-1,0),"stone");put(p,"repeater",facing,delay,"","");}
        void source(int period){
            if(song.loop)maxDelay=Math.min(4,period/4);
            Cell lever=new Cell(0,3,2);solid(lever.offset(0,-1,0),"stone");put(lever,song.loop?"lever":"stone_button","north",0,"","");dust(new Cell(1,3,2));
            Cell comp=new Cell(2,3,2);solid(comp.offset(0,-1,0),"stone");put(comp,"comparator","east",song.loop?1:0,"","");
            dust(new Cell(3,3,2));dust(new Cell(4,3,2));for(int i=4;i<=7;i++)dust(new Cell(i,3,1));repeater(new Cell(8,3,1),"east",1);dust(new Cell(9,3,1));
            if(song.loop){
                int delay=period/4-1,pairs=(delay+7)/8,len=pairs+3,count=pairs*2;
                if(len>=song.maxZ)throw new StudioException("error.vanilla_clock",len+1,song.maxZ);
                List<Cell> chain=new ArrayList<>();for(int zz=3;zz<len;zz++)chain.add(new Cell(4,3,zz));for(int zz=len-1;zz>=3;zz--)chain.add(new Cell(2,3,zz));
                int remaining=delay;for(int i=0;i<count;i++){int d=Math.min(4,remaining-(count-i-1));repeater(chain.get(i),i<pairs?"south":"north",d);remaining-=d;}
                dust(new Cell(4,3,len));dust(new Cell(3,3,len));dust(new Cell(2,3,len));
            }
        }
        void to(int target){while(time<target){int delay=Math.min(maxDelay,target-time);advance(delay);time+=delay;}}
        void advance(int delay){
            if(x+dx*4>=left&&x+dx*4<=right){dust(new Cell(x+dx,y,z));repeater(new Cell(x+2*dx,y,z),dx>0?"east":"west",delay);dust(new Cell(x+3*dx,y,z));x+=dx*4;dust(new Cell(x,y,z));return;}
            dust(new Cell(x+dx,y,z));dust(new Cell(x+2*dx,y,z));
            if(row+1<rows){int target=z+dz*stripe;for(int zz=z+dz;zz!=target+dz;zz+=dz)dust(new Cell(x+2*dx,y,zz));z=target;row++;}
            else{
                if(layer+1>=layers)throw new StudioException("error.vanilla_space",song.maxX,song.maxZ,song.maxY);
                int oldY=y;for(int step=1;step<=height;step++)dust(new Cell(x+2*dx,oldY+Math.min(4,step),z+dz*step));z+=dz*height;y+=4;layer++;row=0;dz=-dz;topAir=Math.max(topAir,y+1);
            }
            repeater(new Cell(x+dx,y,z),dx>0?"west":"east",delay);dust(new Cell(x,y,z));dx=-dx;
        }
        void output(Note n,int index){
            int offset=index/2+1;for(int i=1;i<=offset;i++)dust(new Cell(x,y,z+dz*i));
            Cell p=new Cell(x+(index%2==0?1:-1),y-1,z+dz*offset);Voice v=voices.get(n.voice());
            int pitch=Math.max(0,Math.min(24,12+(v.tunable()?n.pitch()-v.baseMidi():0)));String instrument=instrument(v.sound());
            if(v.command()){
                // New extended notes are baked samples at pitch 1; legacy snapshots keep vanilla limits.
                solid(p.offset(0,-1,0),"stone");String command=String.format(Locale.ROOT,"playsound %s record @a[distance=..64] ~ ~ ~ %.6f %.6f 0",v.sound(),v.volume()*n.velocity()/127f,Math.max(.5,Math.min(2,v.tunable()?Math.pow(2,(n.pitch()-v.baseMidi())/12.0):1)));
                put(p,"command_block","up",0,"",command);
            }else{
                String mob=instrument.equals("dragon")?"ender_dragon":instrument;
                solid(p.offset(0,-2,0),"stone");solid(p.offset(0,-1,0),BASES.getOrDefault(instrument,"dirt"));put(p,"note_block","",pitch,instrument,"");
                if(controls.containsKey(n))parts.put(p,new Part(p,"note_block","",pitch,instrument,"",controls.get(n)));
                if(HEADS.containsKey(mob))solid(p.offset(0,1,0),HEADS.get(mob));
            }
            topAir=Math.max(topAir,y+(!v.command()&&HEADS.containsKey(instrument.equals("dragon")?"ender_dragon":instrument)?2:1));
            outputs.add(new Output(p,n.tick(),instrument,pitch,v.command()));
        }
    }
}
