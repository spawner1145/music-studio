package dev.musicstudio.song;

import java.util.*;

/** Editor positions use integer PPQ; never screen pixels or accumulated floating-point delays. */
public final class SongProject {
    public static final int PPQ = 960, STEP = PPQ / 4, MAX_EVENTS = 8192;
    public static final int MAX_SONG_PPQ = 131072 * 4 * PPQ;
    public static final int VANILLA_NOTE_DURATION = STEP, VANILLA_NOTE_VELOCITY = 127;
    public int schemaVersion = 1;
    public String projectId = UUID.randomUUID().toString();
    /** Runtime binding only. Portable scores never depend on an absolute path or folder name. */
    public transient java.nio.file.Path projectDirectory;
    public String title = "夜航";
    public int bpm = 120, endPpq = 16 * 4 * PPQ;
    /** Equal cursors mean whole-song export; selection can include silence past the project end. */
    public int exportStart,exportEnd;
    public boolean loop = true;
    public int maxX = 48, maxZ = 32, maxY = 12;
    public boolean commandSamples;
    public boolean noteBlockPitchHook;
    public List<Channel> channels = new ArrayList<>();
    /** Playlist lanes have no sound binding. Independent audio sources never add Pattern note lanes. */
    public List<Track> tracks = new ArrayList<>();
    public List<Channel> audioSources = new ArrayList<>();
    public List<Pattern> patterns = new ArrayList<>();
    public List<Clip> clips = new ArrayList<>();
    public List<Asset> assets = new ArrayList<>();
    /** Only used by an exported building snapshot; normal score/audio exports keep their source channels. */
    public Map<String,String> blueprintAudio = new TreeMap<>();

    public static final class Asset {
        public String id="",name="",original="";
        public int frames;
        public Asset(){}
        public Asset(String id,String name,String original,int frames){this.id=id;this.name=name;this.original=original;this.frames=frames;}
    }

    public static final class Channel {
        public String id = UUID.randomUUID().toString(),nameKey="";
        public String name, sound, instrument = "";
        public int color, baseMidi = 66;
        public int displayHeight = 65;
        public float volume = .75f;
        public boolean muted, tunable = true;
        public String sampleId="",renderedAudio="";
        public String originAudioId="";
        public float trimStart=0,trimEnd=0,fadeIn=0,fadeOut=0,gain=1;
        public Channel() {}
        public Channel(String name, String sound, int color) { this.name=name; this.sound=sound; this.color=color; }
    }
    public static final class Track {
        public String id=UUID.randomUUID().toString(),name="",nameKey="";
        public int color=0x577e87,displayHeight=65;
        public boolean muted;
        public Track(){}
        public Track(String name,int color){this.name=name;this.color=color;}
    }
    public static final class Note {
        public String id = UUID.randomUUID().toString();
        public int start, duration, pitch, velocity;
        public Note() {}
        public Note(int step, int length, int pitch, int velocity) {
            start=step*STEP; duration=length*STEP; this.pitch=pitch; this.velocity=velocity;
        }
    }
    public static final class Pattern {
        public String id = UUID.randomUUID().toString();
        public String nameKey="";
        public String name;
        public int length = 32 * STEP;
        public int color=0x577e87;
        public List<List<Note>> notes = new ArrayList<>();
        public Pattern() {}
        public Pattern(String name, int channels) { this.name=name; for(int i=0;i<channels;i++) notes.add(new ArrayList<>()); }
    }
    public static final class Clip {
        public String id = UUID.randomUUID().toString();
        public int channel, pattern, start, duration;
        public int track, audioSource=-1;
        public boolean audio(){return audioSource>=0;}
        public static Clip pattern(int track,int pattern,int start,int duration){Clip c=new Clip();c.track=track;c.pattern=pattern;c.start=start;c.duration=duration;c.contentLength=duration;return c;}
        public static Clip audio(int track,int source,int start,int duration){Clip c=pattern(track,-1,start,duration);c.audioSource=source;return c;}
        /** Stored extent for audio/legacy repeats; live Pattern extents use clipSourceLength. Zero is legacy. */
        public int contentOffset, contentLength;
        /** Historical repeated instances keep a fixed source window when their Pattern grows. */
        public boolean fixedPatternWindow;
        public int sourceLength() { return contentLength==0?duration:contentLength; }
        /** Trim/pad without moving the source notes; moving a clip still only changes start. */
        public void resize(int left,int right) {
            if(left<0||right>MAX_SONG_PPQ||right-left<STEP)throw new StudioException("error.clip");
            int source=sourceLength(),offset=contentOffset+start-left;
            if(offset < -MAX_SONG_PPQ||offset > MAX_SONG_PPQ)throw new StudioException("error.clip");
            contentOffset=offset;contentLength=source;start=left;duration=right-left;
        }
        public Clip() {}
        public Clip(int channel, int pattern, int startBar, int bars) {
            this.channel=channel; this.pattern=pattern; start=startBar*4*PPQ; duration=bars*4*PPQ;
        }
    }
    public record Event(int channel,int start,int duration,int pitch,int velocity,int track,int offset) {
        public Event(int channel,int start,int duration,int pitch,int velocity){this(channel,start,duration,pitch,velocity,-1,0);}
    }
    public List<Channel> voices(){List<Channel> all=new ArrayList<>(channels);all.addAll(audioSources);return all;}
    public Channel voice(int index){return index<channels.size()?channels.get(index):audioSources.get(index-channels.size());}
    public int audioLength(Channel c){Asset a=assets.stream().filter(v->v.id.equals(c.sampleId)).findFirst().orElseThrow();double seconds=(c.trimEnd==0?a.frames/44100.0:c.trimEnd)-c.trimStart;return Math.max(1,(int)Math.ceil(seconds*bpm*PPQ/60.0));}
    public boolean hasFixedPatternWindow(Clip c){return c.fixedPatternWindow||schemaVersion==2&&!c.audio()&&c.sourceLength()>patterns.get(c.pattern).length;}
    /** A single Pattern instance follows its live source; its visible crop remains independent. */
    public int clipSourceLength(Clip c){return schemaVersion==2&&!c.audio()&&!hasFixedPatternWindow(c)?patterns.get(c.pattern).length:c.sourceLength();}

    public List<Event> events(int patternIndex, boolean patternOnly) {
        List<Event> result = new ArrayList<>();
        if(patternOnly) {
            Pattern p=patterns.get(patternIndex);
            for(int c=0;c<channels.size();c++) if(!channels.get(c).muted)
                for(Note n:p.notes.get(c)) result.add(new Event(c,n.start,Math.min(n.duration,p.length-n.start),n.pitch,n.velocity));
        } else for(Clip k:clips) {
            if(schemaVersion==1?channels.get(k.channel).muted:tracks.get(k.track).muted) continue;
            result.addAll(clipEvents(k,k.start,Math.min(endPpq,k.start+k.duration)));
            if(result.size()>MAX_EVENTS*32) throw new StudioException("error.expanded_events");
        }
        result.sort(Comparator.comparingInt(Event::start).thenComparingInt(Event::channel));
        return result;
    }

    /** Shared source window for playback, exports and the Playlist miniature. Range only filters events. */
    public List<Event> clipEvents(Clip clip,int from,int to) {
        if(schemaVersion==1)return sourceEvents(clip,from,to,clip.channel,-1);
        List<Event> result=new ArrayList<>();
        if(clip.audio()){
            Channel c=audioSources.get(clip.audioSource);if(c.muted)return result;
            int origin=clip.start+clip.contentOffset,start=Math.max(origin,clip.start),end=Math.min(Math.min(origin+Math.min(clip.sourceLength(),audioLength(c)),clip.start+clip.duration),endPpq);
            if(start<to&&end>from&&end>start)result.add(new Event(channels.size()+clip.audioSource,start,end-start,c.baseMidi,127,clip.track,start-origin));
        }else for(int c=0;c<channels.size();c++)if(!channels.get(c).muted)result.addAll(sourceEvents(clip,from,to,c,clip.track));
        if(result.size()>MAX_EVENTS*32)throw new StudioException("error.expanded_events");return result;
    }
    private List<Event> sourceEvents(Clip clip,int from,int to,int channel,int track) {
        List<Event> result=new ArrayList<>();Pattern p=patterns.get(clip.pattern);
        int origin=clip.start+clip.contentOffset,sourceLength=clipSourceLength(clip);
        int left=Math.max(clip.start,from),right=Math.min(Math.min(clip.start+clip.duration,endPpq),to);
        if(left>=right||p.notes.get(channel).isEmpty())return result;
        int first=Math.max(0,Math.floorDiv(left-origin,p.length)*p.length);
        for(int offset=first;offset<sourceLength&&origin+offset<right;offset+=p.length)
            for(Note n:p.notes.get(channel)) {
                if(offset+n.start>=sourceLength)continue;
                int raw=origin+offset+n.start,start=Math.max(raw,clip.start);
                int end=Math.min(Math.min(raw+n.duration,origin+sourceLength),Math.min(clip.start+clip.duration,endPpq));
                if(start>=right||end<=left||end<=start)continue;
                result.add(new Event(channel,start,end-start,n.pitch,n.velocity,track,Math.max(0,start-raw)));
                if(result.size()>MAX_EVENTS*32)throw new StudioException("error.expanded_events");
            }
        return result;
    }

    public void validate() {
        require(schemaVersion==1||schemaVersion==2,"error.project_version");
        require(projectId!=null&&!projectId.isBlank()&&projectId.length()<=64,"error.project_id");
        require(title!=null&&title.length()<=100,"error.title");
        require(bpm>=20&&bpm<=400,"error.bpm");
        require(endPpq>=(schemaVersion==1?4*PPQ:1)&&endPpq<=MAX_SONG_PPQ,"error.song_length");
        require(exportStart>=0&&exportStart<=MAX_SONG_PPQ&&exportEnd>=0&&exportEnd<=MAX_SONG_PPQ,"error.export_range");
        require(maxX>=1&&maxX<=256&&maxZ>=1&&maxZ<=256&&maxY>=1&&maxY<=256,"error.dimensions");
        require(channels!=null&&channels.size()>=1&&channels.size()<=(schemaVersion==1?32:128),"error.channel_count");
        require(audioSources!=null,"error.channel_count");
        require(tracks!=null&&(schemaVersion==1||!tracks.isEmpty())&&tracks.size()<=256,"error.track_count");
        Set<String> trackIds=new HashSet<>();for(Track t:tracks)require(t!=null&&t.id!=null&&trackIds.add(t.id)&&t.id.length()<=64&&t.name!=null&&t.name.length()<=80&&t.nameKey!=null&&t.nameKey.length()<=128&&t.displayHeight>=32&&t.displayHeight<=600,"error.track");
        require(blueprintAudio!=null&&blueprintAudio.size()<=4096,"error.asset_limit");
        for(var entry:blueprintAudio.entrySet())require(entry.getKey()!=null&&entry.getKey().matches("[a-f0-9]{64}")&&entry.getValue()!=null&&entry.getValue().matches("[a-f0-9]{64}"),"error.asset");
        require(assets!=null,"error.asset");
        Set<String> assetIds=new HashSet<>();for(Asset a:assets)require(a!=null&&a.id!=null&&a.id.matches("[a-f0-9]{64}")&&assetIds.add(a.id)&&a.name!=null&&a.name.length()<=160&&a.original!=null&&a.original.matches("[a-f0-9]{64}-source\\.(wav|ogg|mp3|flac|aif|aiff|m4a)")&&a.frames>0,"error.asset");
        Set<String> channelIds=new HashSet<>();for(Channel c:voices()) {
            require(c!=null&&c.name!=null&&c.name.length()<=80,"error.channel_name");
            require(c.id!=null&&!c.id.isBlank()&&c.id.length()<=64&&channelIds.add(c.id),"error.channel_id");
            require(c.originAudioId!=null&&c.originAudioId.length()<=64,"error.channel_id");
            require(c.nameKey!=null&&c.nameKey.length()<=128,"error.channel_name");
            require(c.displayHeight>=32&&c.displayHeight<=600,"error.channel_height");
            require(c.sound!=null&&c.sound.length()<=256&&c.sound.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"),"error.sound_id");
            require(c.instrument!=null&&c.instrument.length()<=100,"error.instrument");
            require(Float.isFinite(c.volume)&&c.volume>=0&&c.volume<=1&&c.baseMidi>=0&&c.baseMidi<=127,"error.channel_parameters");
            require(c.sampleId!=null&&(c.sampleId.isEmpty()||assetIds.contains(c.sampleId))&&c.renderedAudio!=null&&(c.renderedAudio.isEmpty()||c.renderedAudio.matches("[a-f0-9]{64}")),"error.asset");
            require(Float.isFinite(c.trimStart)&&Float.isFinite(c.trimEnd)&&Float.isFinite(c.fadeIn)&&Float.isFinite(c.fadeOut)&&Float.isFinite(c.gain)&&c.trimStart>=0&&c.trimEnd>=0&&c.fadeIn>=0&&c.fadeOut>=0&&c.gain>=0&&c.gain<=4&&(c.trimEnd==0||c.trimEnd>c.trimStart),"error.sample_settings");
            if(!c.sampleId.isEmpty()){Asset a=assets.stream().filter(asset->asset.id.equals(c.sampleId)).findFirst().orElseThrow();float seconds=a.frames/44100f;require(c.trimStart<seconds&&(c.trimEnd==0||c.trimEnd<=seconds+.001f),"error.sample_settings");}
        }
        require(patterns!=null&&!patterns.isEmpty()&&patterns.size()<=(schemaVersion==1?128:4096),"error.pattern_count");
        int count=0; Set<String> ids=new HashSet<>(),patternIds=new HashSet<>();
        for(Pattern p:patterns) {
            require(p!=null&&p.name!=null&&p.name.length()<=80&&p.length>=STEP&&p.length<=MAX_SONG_PPQ,"error.pattern");
            require(p.nameKey!=null&&p.nameKey.length()<=128,"error.pattern");
            require(p.id!=null&&!p.id.isBlank()&&p.id.length()<=64&&patternIds.add(p.id),"error.pattern_id");
            require(p.notes!=null&&p.notes.size()==channels.size(),"error.pattern_channels");
            for(List<Note> list:p.notes) {
                require(list!=null&&list.size()<=MAX_EVENTS,"error.note_limit");
                for(Note n:list) {
                    require(n!=null&&n.id!=null&&n.id.length()<=64&&ids.add(n.id),"error.note_id");
                    require(n.start>=0&&n.start<p.length&&n.duration>0&&n.duration<=p.length-n.start,"error.note_time");
                    require(n.pitch>=0&&n.pitch<=127&&n.velocity>=1&&n.velocity<=127,"error.note_parameters");
                    count++;
                }
            }
        }
        require(count<=MAX_EVENTS,"error.note_limit");
        require(clips!=null&&clips.size()<=4096,"error.clip_limit");
        Set<String> clipIds=new HashSet<>();
        for(Clip c:clips) {
            require(c!=null&&c.id!=null&&c.id.length()<=64&&clipIds.add(c.id)&&c.start>=0&&c.start<endPpq&&c.duration>0&&c.duration<=endPpq-c.start&&c.contentLength>=0&&c.contentLength<=MAX_SONG_PPQ&&c.contentOffset>=-MAX_SONG_PPQ&&c.contentOffset<=MAX_SONG_PPQ&&(c.contentLength!=0||c.contentOffset==0),"error.clip");
            require(schemaVersion==1?c.channel>=0&&c.channel<channels.size()&&c.pattern>=0&&c.pattern<patterns.size()&&!c.audio():c.track>=0&&c.track<tracks.size()&&(c.audio()?c.audioSource<audioSources.size()&&c.pattern==-1:c.pattern>=0&&c.pattern<patterns.size()&&c.audioSource==-1),"error.clip");
            if(c.audio())require(!audioSources.get(c.audioSource).sampleId.isEmpty(),"error.asset");
        }
    }
    private static void require(boolean ok,String text) { if(!ok)throw new StudioException(text); }
    /** A whole silent bar after the latest note, rounded up, without shortening manual space. */
    public static int suggestedPatternLength(long noteEnd){
        require(noteEnd>=0&&noteEnd<=MAX_SONG_PPQ,"error.note_time");long bar=4L*PPQ;
        return (int)Math.min(MAX_SONG_PPQ,Math.max(2*bar,((noteEnd+bar-1)/bar+1)*bar));
    }
    public void growPattern(int index){
        Pattern p=patterns.get(index);int old=p.length;long end=p.notes.stream().flatMap(List::stream).mapToLong(n->(long)n.start+n.duration).max().orElse(0);
        int length=Math.max(old,suggestedPatternLength(end));if(length==old)return;
        for(Clip c:clips)if(!c.audio()&&c.pattern==index){
            if(schemaVersion==2&&hasFixedPatternWindow(c)){c.fixedPatternWindow=true;continue;}
            if(schemaVersion==1&&c.sourceLength()!=old)continue;
            boolean whole=c.contentOffset>=0&&(long)c.duration>=c.contentOffset+(long)old;
            c.contentLength=length;
            if(whole){
                c.duration=(int)Math.min(MAX_SONG_PPQ-(long)c.start,c.duration+(long)length-old);
                endPpq=Math.max(endPpq,(int)Math.min(MAX_SONG_PPQ,((c.start+(long)c.duration+4L*PPQ-1)/(4L*PPQ))*(4L*PPQ)));
            }
        }
        p.length=length;
    }
    /** An independent clip can also be played by notes, without sharing mutable channel settings. */
    public int instrumentFromAudio(String audioId){
        Channel source=audioSources.stream().filter(c->c.id.equals(audioId)).findFirst().orElseThrow(()->new StudioException("error.channel_missing"));
        for(int i=0;i<channels.size();i++)if(channels.get(i).originAudioId.equals(audioId))return i;
        require(channels.size()<128,"error.channel_count");require(!source.sampleId.isEmpty(),"error.asset");
        Channel c=ProjectStore.GSON.fromJson(ProjectStore.GSON.toJson(source),Channel.class);c.id=UUID.randomUUID().toString();c.originAudioId=audioId;c.muted=false;c.tunable=true;
        channels.add(c);patterns.forEach(p->p.notes.add(new ArrayList<>()));return channels.size()-1;
    }
    public void removeChannel(int index){
        if(index<0||index>=channels.size())throw new StudioException("error.channel_missing");
        if(channels.size()==1)throw new StudioException("error.last_channel");
        validate();
        channels.remove(index);for(Pattern p:patterns)p.notes.remove(index);
        if(schemaVersion==1){clips.removeIf(c->c.channel==index);for(Clip c:clips)if(c.channel>index)c.channel--;}
    }
    /** Delete independent placements; sampler voices and shared media remain available for playback and undo. */
    public void removeAudioSource(int index){
        require(index>=0&&index<audioSources.size(),"error.audio_missing");validate();
        clips.removeIf(c->c.audio()&&c.audioSource==index);
        for(Clip c:clips)if(c.audio()&&c.audioSource>index)c.audioSource--;
        audioSources.remove(index);
    }
    public void removeTrack(int index){if(index<0||index>=tracks.size()||tracks.size()==1)throw new StudioException("error.track_count");clips.removeIf(c->c.track==index);for(Clip c:clips)if(c.track>index)c.track--;tracks.remove(index);}
    /** Move an item to its final index, keeping every other item's relative order. */
    public static int reorderedIndex(int index,int from,int to){return index==from?to:from<to&&index>from&&index<=to?index-1:from>to&&index>=to&&index<from?index+1:index;}
    private static <T> List<T> reordered(List<T> list,int from,int to){var result=new ArrayList<>(list);result.add(to,result.remove(from));return result;}
    private void checkReorder(int size,int from,int to){require(from>=0&&from<size&&to>=0&&to<size,"error.reorder");validate();}
    public void reorderPattern(int from,int to){checkReorder(patterns.size(),from,to);if(from==to)return;patterns=reordered(patterns,from,to);for(var c:clips)if(!c.audio())c.pattern=reorderedIndex(c.pattern,from,to);}
    public void reorderTrack(int from,int to){checkReorder(tracks.size(),from,to);if(from==to)return;tracks=reordered(tracks,from,to);for(var c:clips)c.track=reorderedIndex(c.track,from,to);}
    public void reorderChannel(int from,int to){checkReorder(channels.size(),from,to);if(from==to)return;channels=reordered(channels,from,to);for(var p:patterns)p.notes=reordered(p.notes,from,to);if(schemaVersion==1)for(var c:clips)c.channel=reorderedIndex(c.channel,from,to);}
    public void reorderAudioSource(int from,int to){checkReorder(audioSources.size(),from,to);if(from==to)return;audioSources=reordered(audioSources,from,to);for(var c:clips)if(c.audio())c.audioSource=reorderedIndex(c.audioSource,from,to);}
    public void removePattern(int index){
        if(index<0||index>=patterns.size())throw new StudioException("error.pattern");
        clips.removeIf(c->!c.audio()&&c.pattern==index);for(Clip c:clips)if(!c.audio()&&c.pattern>index)c.pattern--;
        patterns.remove(index);if(patterns.isEmpty())patterns.add(new Pattern("Pattern 1",channels.size()));
    }
    /** Rebind a sampler while keeping its identity, notes, name and arrangement references. */
    public void replaceSampler(String sourceId,String assetId){
        if(assets.stream().noneMatch(a->a.id.equals(assetId)))throw new StudioException("error.asset");
        Channel c=channels.stream().filter(v->v.id.equals(sourceId)).findFirst().orElseThrow(()->new StudioException("error.channel_missing"));
        c.sampleId=assetId;c.sound="musicstudio:sample/"+assetId;c.renderedAudio="";c.instrument="";c.tunable=true;c.baseMidi=66;c.trimStart=c.trimEnd=c.fadeIn=c.fadeOut=0;c.gain=1;
    }
    /** Preserve old single-source arrangements by moving each source's notes into a complete linked Pattern. */
    public void upgrade(){
        if(schemaVersion==2)return;validate();List<Pattern> migrated=new ArrayList<>();int[][] refs=new int[patterns.size()][channels.size()];
        for(Channel c:channels){Track t=new Track(c.name,c.color);t.nameKey=c.nameKey;t.displayHeight=c.displayHeight;t.muted=false;tracks.add(t);}
        for(int pi=0;pi<patterns.size();pi++){Pattern old=patterns.get(pi);for(int c=0;c<channels.size();c++){
            Pattern p=new Pattern((old.name+" / "+channels.get(c).name).substring(0,Math.min(80,(old.name+" / "+channels.get(c).name).length())),channels.size());p.length=old.length;p.color=channels.get(c).color;if(c==0)p.id=old.id;p.notes.set(c,old.notes.get(c));refs[pi][c]=migrated.size();migrated.add(p);
        }}
        for(Clip c:clips){c.track=c.channel;c.pattern=refs[c.pattern][c.channel];c.channel=0;}
        patterns=migrated;schemaVersion=2;validate();
    }
    public static long quantizeTick(int position, int bpm) {
        if(position<0||bpm<20||bpm>400)throw new StudioException("error.musical_time");
        long denominator=(long)PPQ*bpm;
        return ((long)position*1200+denominator/2)/denominator;
    }

    public static SongProject legacyDemo() {
        SongProject s=new SongProject();
        String[] names={"Kick","Percussion","Pluck","Pads","Bass","Chords","Ambience","Foley"};
        String[] sounds={"block.note_block.basedrum","block.note_block.hat","block.note_block.harp","block.note_block.flute","block.note_block.bass","block.note_block.bell","ambient.cave","block.grass.step"};
        int[] colors={0x697e79,0x876d80,0x577e87,0x856878,0x8a7864,0x867a68,0x78658a,0x5a8584};
        for(int c=0;c<8;c++) { Channel ch=new Channel(names[c],"minecraft:"+sounds[c],colors[c]); ch.nameKey="channel."+names[c].toLowerCase(Locale.ROOT);ch.tunable=c<6; if(c<6)ch.instrument=sounds[c].substring("block.note_block.".length()); s.channels.add(ch); }
        for(int v=0;v<2;v++) {
            Pattern p=new Pattern(v==0?"Main pattern":"Variation",8);
            p.nameKey=v==0?"pattern.main":"pattern.variation";
            for(int i=0;i<8;i++) { p.notes.get(0).add(new Note(i*4,1,66,110)); p.notes.get(1).add(new Note(i*4+2,1,66,90)); }
            int[] melody={67,72,74,76,74,72,69,72,76,79,76,72},positions={0,3,6,8,11,14,16,19,22,24,27,30};
            for(int i=0;i<melody.length;i++) p.notes.get(2).add(new Note(positions[i],2,melody[i]-(v==1?2:0),100));
            for(int t:new int[]{0,16}) for(int n:new int[]{60,64,67,71}) p.notes.get(3).add(new Note(t,15,n-v*2,75));
            for(int i=0;i<8;i++) p.notes.get(4).add(new Note(i*4,3,new int[]{54,54,61,61,59,59,61,61}[i],104));
            for(int t:new int[]{0,8,16,24})for(int n:new int[]{60,64,67})p.notes.get(5).add(new Note(t,2,n,88));
            p.notes.get(6).add(new Note(0,32,66,50));
            for(int t:new int[]{0,8,16,24})p.notes.get(7).add(new Note(t,1,66,65));
            s.patterns.add(p);
        }
        for(int c=0;c<8;c++) {
            int length=(c==3||c>=6)?8:2,spacing=(c==3||c>=6)?8:4;
            for(int bar=0;bar<16;bar+=spacing) s.clips.add(new Clip(c,(bar/spacing)%2,bar,length));
        }
        s.validate(); return s;
    }
    public static SongProject demo(){
        SongProject s=legacyDemo();s.schemaVersion=2;s.clips.clear();
        for(int i=0;i<8;i++){Track t=new Track("Track "+(i+1),s.channels.get(i).color);t.nameKey="track.default";s.tracks.add(t);}
        s.patterns.get(0).color=0x577e87;s.patterns.get(1).color=0x876d80;
        for(int bar=0;bar<16;bar+=4)s.clips.add(Clip.pattern((bar/4)%3,(bar/4)%2,bar*4*PPQ,s.patterns.getFirst().length));
        for(int i=0;i<s.patterns.size();i++)s.growPattern(i);
        s.validate();return s;
    }
}
