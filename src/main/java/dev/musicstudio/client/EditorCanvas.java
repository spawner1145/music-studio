package dev.musicstudio.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.LDLibFonts;
import net.minecraft.network.chat.Component;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import dev.musicstudio.MusicStudio;
import dev.musicstudio.song.ProjectStore;
import dev.musicstudio.song.SongProject;
import dev.musicstudio.song.SongProject.*;
import dev.musicstudio.song.EditorClipboard;
import dev.musicstudio.song.TimelineSnap;
import dev.musicstudio.song.ExportRange;
import dev.musicstudio.song.StudioException;
import dev.musicstudio.media.*;
import static dev.musicstudio.client.StudioText.tr;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Dense DAW work surface. Grid cells and notes are virtual drawing/hit-test data, not thousands of widgets. */
public final class EditorCanvas extends UIElement {
    private static final int W=1280,H=830,BG=0xff39464d,PANEL=0xff48545b,EDGE=0xff29343a,TEXT=0xffeef2f3,MUTED=0xffc2cdd2,GREEN=0xffb3d96e;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"musicstudio-projects");t.setDaemon(true);return t;});
    private final Minecraft mc=Minecraft.getInstance();
    private final ProjectStore store=new ProjectStore(mc.gameDirectory.toPath().resolve("musicstudio/projects"));
    private final PreviewEngine audio=new PreviewEngine();
    private final WaveformPreview waveform=new WaveformPreview();
    private final AudioClipWaveforms clipWaves=new AudioClipWaveforms();
    private boolean waveformOverview;
    private SongProject song=StudioClient.project;
    private final List<Hit> hits=new ArrayList<>();
    private final Map<String,String> tooltips=new HashMap<>();
    private String hoverId="",deleteChannelId="",deleteAudioId="";
    private long hoverSince;
    private record History(String score,Path file,Path directory){}
    private final Deque<History> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private int channel=2,pattern,snap=16,stepPage;
    private final PianoView roll=new PianoView();
    private final StudioWindows windows=new StudioWindows();
    private boolean editorFolded,editorMaximized=true;
    private int activeTrack,browserTab,browserScroll;
    private String selectedAudio="",sampleSourceId="";
    private final PlaylistView viewport=new PlaylistView();
    private Path projectFile=StudioClient.projectFile;
        private int clipDepth;private float clipLeft,clipTop,clipRight,clipBottom;
    private String channelSettingsId="";
    private int lastPianoChannel=-1,lastPianoPattern=-1;
    private double cursor=4*4*SongProject.PPQ;
    private double playbackOrigin=cursor;
    private boolean transportActive,playbackPending,restartAfterSeek;
    private int playbackPattern,playbackNoteOrigin;
    private long playbackRequest;
    private boolean patternMode,rack=true,piano,dirty,closed,loadedDraft;
    private float rackX=184,rackY=10,originX,originY,scale=1;
    private String modal="",query="",selectedNote="",selectedClip="",status=tr("status.ready"),fileName="夜航";
    private static EditorClipboard clipboard=EditorClipboard.EMPTY;
    private final Set<String> noteSelection=new LinkedHashSet<>(),clipSelection=new LinkedHashSet<>();
    private boolean notesContext,pointerCtrl,pointerShift;
    private int selectionChannel=-1,selectionPattern=-1,notePastePpq;
    private boolean noteFilter=true;
    private int soundScroll,fileScroll,soundSelected;
    private List<String> files=List.of();
    private List<SoundCatalog.Entry> results=List.of();
    private long revision,lastEdit,lastClickTime,lastCatalogRevision=-1;
    private dev.musicstudio.blueprint.MusicBlueprint exportPreview;
    private long exportRevision=-1,exportStarted;
    private String exportError="";
    private UUID exportRequest;
    private boolean mediaBusy;
    private String mediaMessage="";
    private AudioFiles.Wave sampleWave;
    private float[] samplePeaks;
    private int rackScroll;
    private String pianoPicker="",pianoPickerQuery="";
    private int pianoPickerScroll,pianoPickerIndex;
    private float pickerX,pickerY,pickerW,pickerH;
    private record PianoChoice(String id,String name,String kind,int color,int count,String search){}
    private String lastHit="",focus="",fieldText="";
    private Consumer<String> fieldCommit;
    private boolean selectAll;
    private Drag drag;
    private GuiGraphics g;
    private float mouseX,mouseY;

    private record Hit(String id,float x,float y,float w,float h,Runnable click,Runnable right,Runnable doubleClick) {
        boolean contains(float px,float py){return px>=x&&py>=y&&px<x+w&&py<y+h;}
    }
    private static final class Drag {
        String type,id,before;float x,y,left,top,pointerX,pointerY;double extent,anchor,original;int start,pitch,duration,limit,edge;boolean resize,committed,resume;
        Drag(String type,float x,float y){this.type=type;this.x=this.pointerX=x;this.y=this.pointerY=y;}
        Map<String,int[]> originals=new LinkedHashMap<>();
        Set<String> initialSelection=Set.of();
        boolean additive,moved;
    }
    public EditorCanvas() {
        normalize();
        loadedDraft=projectFile!=null;
        getLayout().width(mc.getWindow().getGuiScaledWidth()).height(mc.getWindow().getGuiScaledHeight());
        getStyle().overflowVisible(true);
    }
    private List<Note> notes(){return song.patterns.get(pattern).notes.get(channel);}
    private Note note(){return notes().stream().filter(n->n.id.equals(selectedNote)).findFirst().orElse(null);}
    private void syncSelection(){
        if(selectionChannel!=channel||selectionPattern!=pattern){if(selectionPattern!=pattern)notePastePpq=patternMode?(int)cursor:0;noteSelection.clear();selectedNote="";selectionChannel=channel;selectionPattern=pattern;}
        noteSelection.removeIf(id->notes().stream().noneMatch(n->n.id.equals(id)));
        clipSelection.removeIf(id->song.clips.stream().noneMatch(c->c.id.equals(id)));
        if(!noteSelection.contains(selectedNote))selectedNote=noteSelection.stream().findFirst().orElse("");
        if(!clipSelection.contains(selectedClip))selectedClip=clipSelection.stream().findFirst().orElse("");
    }
    private void clearSelection(){noteSelection.clear();clipSelection.clear();selectedNote="";selectedClip="";}
    private List<Note> selectedNotes(){syncSelection();return notes().stream().filter(n->noteSelection.contains(n.id)).toList();}
    private List<Clip> selectedClips(){syncSelection();return song.clips.stream().filter(c->clipSelection.contains(c.id)).toList();}
    private void chooseNote(String id){syncSelection();if(!noteSelection.contains(id)&&!pointerShift)noteSelection.clear();noteSelection.add(id);selectedNote=id;notesContext=true;}
    private void chooseClip(String id){if(!clipSelection.contains(id)&&!pointerShift)clipSelection.clear();clipSelection.add(id);selectedClip=id;notesContext=false;}
    private int endSteps(){return song.endPpq/SongProject.STEP;}
    private void extendSong(int end){song.endPpq=Math.max(song.endPpq,Math.min(SongProject.MAX_SONG_PPQ,(int)(Math.ceil(end/PlaylistView.BAR)*PlaylistView.BAR)));}
    private History history(){return new History(ProjectStore.encode(song),projectFile,song.projectDirectory);}
    private void restore(History entry){String source=song.channels.get(channel).id,phrase=song.patterns.get(pattern).id,lane=song.tracks.get(activeTrack).id;var restored=ProjectStore.decode(entry.score);if(restored.projectId.equals(song.projectId))restored.projectDirectory=song.projectDirectory;else{restored.projectDirectory=entry.directory;projectFile=entry.file;}song=restored;restoreOrderFocus(source,phrase,lane);StudioClient.projectFile=projectFile;}
    private void remember(){undo.push(history());while(undo.size()>48)undo.removeLast();redo.clear();}
    private void changed(){dirty=true;lastEdit=System.currentTimeMillis();revision++;StudioClient.project=song;waveform.invalidate();}
    private void edit(Runnable r){remember();stopPreview();r.run();changed();}
    private void undo(){if(undo.isEmpty())return;stopPreview();redo.push(history());restore(undo.pop());clearSelection();normalize();changed();status=tr("status.undo");}
    private void redo(){if(redo.isEmpty())return;stopPreview();undo.push(history());restore(redo.pop());clearSelection();normalize();changed();status=tr("status.redo");}
    private void normalize(){song.upgrade();channel=Mth.clamp(channel,0,song.channels.size()-1);pattern=Mth.clamp(pattern,0,song.patterns.size()-1);activeTrack=Mth.clamp(activeTrack,0,song.tracks.size()-1);viewport.pan(viewport.start);fileName=song.title;if(song.audioSources.stream().noneMatch(c->c.id.equals(selectedAudio)))selectedAudio="";}
    private void resetFocus(){if(!focus.isEmpty())commitField();}
    private void field(String id,String value,Consumer<String> commit){if(!focus.equals(id)){resetFocus();focus=id;fieldText=value;fieldCommit=commit;selectAll=true;}}
    private void commitField(){String text=fieldText;Consumer<String> action=fieldCommit;focus="";fieldCommit=null;selectAll=false;if(action!=null)try{action.accept(text);}catch(Exception ex){status=tr("status.invalid_input",StudioText.error(ex));}}
    private void stopPreview(){playbackRequest++;audio.stop();if(transportActive){cursor=playbackOrigin;notePastePpq=playbackNoteOrigin;}transportActive=playbackPending=restartAfterSeek=false;if(drag!=null)drag.resume=false;}
    private void stop(){stopPreview();status=tr("status.stopped");}
    private void togglePiano(){if(!piano||windows.get("piano").folded){piano=true;windows.get("piano").folded=false;}else piano=false;notesContext=piano;}
    private void toggleRack(){if(!rack||windows.get("rack").folded){rack=true;windows.get("rack").folded=false;}else rack=false;}
    private void openPanel(String id){resetFocus();windows.get(id).folded=false;modal=id;}
    private void cycleSnap(){
        snap=snap==16?4:snap==4?1:16;
        if(!audio.playing())cursor=TimelineSnap.position(cursor,snap,patternMode?song.patterns.get(pattern).length:song.endPpq);
        notePastePpq=TimelineSnap.position(notePastePpq,snap,SongProject.MAX_SONG_PPQ);
    }
    private void beginSeek(boolean notes,float left,float width,int start,int span,int limit){
        boolean resume=transportActive,modeChanged=!notes&&patternMode;beginTransportSeek();if(modeChanged){patternMode=false;transportActive=false;}
        notesContext=notes;drag=new Drag(notes?"seek-notes":"seek-playlist",mouseX,mouseY);drag.resume=resume;
        drag.left=left;drag.top=width;drag.start=start;drag.duration=span;drag.limit=limit;updateSeek();
        if(resume&&modeChanged){playbackOrigin=cursor;transportActive=true;}
    }
    private void updateSeek(){
        int position=TimelineSnap.at(mouseX,drag.left,drag.top,drag.start,drag.duration,snap,drag.limit);
        if(drag.type.equals("seek-notes")){notePastePpq=position;status=tr("status.insert_position",position/SongProject.STEP+1);}else cursor=position;
    }
    private void beginTransportSeek(){playbackRequest++;audio.stop();playbackPending=restartAfterSeek=false;}
    private void play(){resetFocus();if(transportActive)stop();else if(!mediaBusy)startPlayback(false);}
    private void startPlayback(boolean preserveOrigin){
        if(!preserveOrigin){playbackOrigin=cursor;playbackNoteOrigin=notePastePpq;}transportActive=playbackPending=true;restartAfterSeek=false;
        long request=++playbackRequest,rev=revision;int selected=pattern;boolean mode=patternMode;double start=cursor;playbackPattern=selected;
        try{withPrepared(prepared->{
            if(!playbackValid(request,rev))return;
            if(PreviewEngine.eventsFrom(prepared,selected,mode,start).stream().noneMatch(e->e.offset()>0)){startPreview(prepared,selected,mode,start,request,rev);return;}
            var provider=ClientMedia.capture(prepared,selected,mode);mediaTask(()->ClientMedia.preparePreview(prepared,selected,mode,start,provider),snapshot->{if(!playbackValid(request,rev))return;mediaBusy=true;ClientMedia.refreshPack().whenComplete((v,e)->mc.execute(()->{mediaBusy=false;if(e!=null)mediaFailure(e);else startPreview(snapshot,selected,mode,start,request,rev);}));});
        });}catch(Exception error){mediaFailure(error);}
    }
    private boolean playbackValid(long request,long rev){return !closed&&transportActive&&playbackRequest==request&&revision==rev;}
    private void startPreview(SongProject prepared,int selected,boolean mode,double start,long request,long rev){if(!playbackValid(request,rev))return;audio.start(prepared,selected,mode,start);playbackPending=false;cursor=audio.position();status=tr("status.playing");}
    private double pianoPlayhead(){
        if(patternMode)return transportActive&&playbackPattern!=pattern?Double.NaN:cursor;
        double local=PianoTimeline.position(song,pattern,cursor,selectedClip);return Double.isNaN(local)&&!transportActive?notePastePpq:local;
    }

    @Override public void drawBackgroundAdditional(GUIContext context) {
        g=context.graphics;
        if(!piano||windows.get("piano").folded||editorFolded||!modal.isEmpty())closePianoPicker();
        if(audio.playing())cursor=audio.position();
        scale=Math.min(getContentWidth()/W,getContentHeight()/H)*(editorMaximized?1:.86f);
        originX=getContentX()+(getContentWidth()-W*scale)/2;
        originY=getContentY()+(getContentHeight()-H*scale)/2;
        if(scale<=0)return;
        mouseX=(context.mouseX-originX)/scale;mouseY=(context.mouseY-originY)/scale;
        if(editorMaximized&&!editorFolded)g.fill((int)getContentX(),(int)getContentY(),(int)(getContentX()+getContentWidth()),(int)(getContentY()+getContentHeight()),0xff24343d);
        g.pose().pushPose();g.pose().translate(originX,originY,0);g.pose().scale(scale,scale,1);
        syncSelection();hits.clear();tooltips.clear();
        if(editorFolded){rect(0,0,W,31,PANEL);text("◆ MUSIC STUDIO",13,11,TEXT);drawEditorButtons();drawTooltip();g.pose().popPose();return;}
        rect(0,0,W,H,BG);drawToolbar();drawEditorButtons();drawBrowser();drawPlaylist();
        if(rack)drawRack();if(piano)drawPiano();drawStatus();drawFoldedModals();
        if(!modal.isEmpty()){rect(0,0,W,H,0xbb14232d);hits.clear();tooltips.clear();switch(modal){case "sounds"->drawSoundPicker();case "projects"->drawProjectPicker();case "channel-settings"->drawChannelSettings();case "track-settings"->drawTrackSettings();case "add-source"->drawAddSource();case "pattern-settings"->drawPatternSettings();case "export"->drawExport();case "delete-channel"->drawDeleteChannel();case "delete-audio"->drawDeleteAudio();case "media"->drawMedia();case "sample"->drawSample();default->{}}}
        if(!pianoPicker.isEmpty())drawPianoPicker();drawTooltip();
        g.pose().popPose();
    }
    private void rect(float x,float y,float w,float h,int color){if(w>0&&h>0)g.fill((int)x,(int)y,(int)(x+w),(int)(y+h),color);}
    private void line(float x,float y,float w,int color){rect(x,y,w,1,color);}
    private void border(float x,float y,float w,float h,int color){line(x,y,w,color);line(x,y+h-1,w,color);rect(x,y,1,h,color);rect(x+w-1,y,1,h,color);}
    private void text(String s,float x,float y,int color){text(s,x,y,color,1);}
    private float fontScale(float requested){
        float device=Math.max(.01f,scale*(float)mc.getWindow().getGuiScale());
        float desired=12*Math.max(.85f,requested)*StudioText.zoom()*device;
        int pixels=Math.max(14,Math.round(desired));
        return pixels/(9*device);
    }
    private float textHeight(float requested){return 9*fontScale(requested);}
    private float textWidth(String s,float requested){return LDLibFonts.font().width(s)*fontScale(requested);}
    private float snapPixel(float value,float origin){float gui=(float)mc.getWindow().getGuiScale();return (Math.round((origin+value*scale)*gui)/gui-origin)/scale;}
    private void text(String s,float x,float y,int color,float size){
        float font=fontScale(size);g.pose().pushPose();g.pose().translate(snapPixel(x,originX),snapPixel(y,originY),0);g.pose().scale(font,font,1);
        LDLibFonts.drawText(g,LDLibFonts.font(),Component.literal(s),0,0,color,false);g.pose().popPose();
    }
    private String fit(String s,int width){return fit(s,width,1);}
    private String fit(String s,int width,float requested){
        var font=LDLibFonts.font();int allowed=Math.max(0,(int)(width/fontScale(requested)));
        if(font.width(s)<=allowed)return s;String suffix="…";if(font.width(suffix)>allowed)return "";
        return font.plainSubstrByWidth(s,allowed-font.width(suffix))+suffix;
    }
    private int shade(int c,float f){return 0xff000000|Mth.clamp((int)(((c>>16)&255)*f),0,255)<<16|Mth.clamp((int)(((c>>8)&255)*f),0,255)<<8|Mth.clamp((int)((c&255)*f),0,255);}
    private void hit(String id,float x,float y,float w,float h,Runnable click){hit(id,x,y,w,h,click,null,null);}
    private void hit(String id,float x,float y,float w,float h,Runnable click,Runnable right,Runnable doubleClick){
        if(clipDepth>0){float rightEdge=Math.min(x+w,clipRight),bottomEdge=Math.min(y+h,clipBottom);x=Math.max(x,clipLeft);y=Math.max(y,clipTop);w=rightEdge-x;h=bottomEdge-y;}if(w>0&&h>0)hits.add(new Hit(id,x,y,w,h,click,right,doubleClick));
    }
    private void clip(float x,float y,float width,float height){
        clipDepth++;clipLeft=x;clipTop=y;clipRight=x+width;clipBottom=y+height;g.flush();
        var matrix=g.pose().last().pose();var a=matrix.transformPosition(x,y,0,new org.joml.Vector3f());var b=matrix.transformPosition(x+width,y+height,0,new org.joml.Vector3f());
        g.enableScissor((int)Math.floor(a.x),(int)Math.floor(a.y),(int)Math.ceil(b.x),(int)Math.ceil(b.y));
    }
    private void unclip(){g.flush();g.disableScissor();clipDepth--;}

    private void button(String id,String label,float x,float y,float w,float h,boolean active,Runnable action){
        boolean hover=mouseX>=x&&mouseY>=y&&mouseX<x+w&&mouseY<y+h;
        rect(x,y,w,h,active?0xff66764f:hover?0xff617179:0xff4b5b64);line(x,y,w,active?0xff94a874:0xff687a83);line(x,y+h-1,w,EDGE);rect(x+w-1,y,1,h,EDGE);
        float labelSize=1;
        if(textWidth(label,labelSize)>w-8)labelSize=.9f;
        if(textWidth(label,labelSize)>w-8)labelSize=.85f;
        String displayed=fit(label,(int)w-8,labelSize);text(displayed,x+5,y+(h-textHeight(labelSize))/2,active?0xffe1f0bc:TEXT,labelSize);
        hit(id,x,y,w,h,action);
        if(!displayed.equals(label))tooltips.put(id,label);
    }
    private void input(String id,String value,float x,float y,float w,float h,Consumer<String> commit){
        rect(x,y,w,h,0xff344148);border(x,y,w,h,focus.equals(id)?0xffb6cf89:0xff29373e);
        String s=focus.equals(id)?fieldText:value;
        if(focus.equals(id)&&selectAll)rect(x+4,y+5,Math.min(w-8,textWidth(s,1)+1),h-10,0xff526d7b);
        text(fit(s+(focus.equals(id)&&System.currentTimeMillis()%1000<500?"|":""),(int)w-8),x+5,y+(h-textHeight(1))/2,TEXT);
        hit(id,x,y,w,h,()->field(id,value,commit));
    }
    private StudioWindows.Bounds frame(String id,float x,float y,float w,float h){return windows.bounds(id,x,y,w,h);}
    private String windowId(String dragType){return dragType==null?modal:dragType;}
    private void drawEditorButtons(){
        button("minimize-editor",editorFolded?"+":"−",W-76,5,20,20,editorFolded,()->editorFolded=!editorFolded);
        button("maximize-editor",editorMaximized?"▣":"□",W-51,5,20,20,editorMaximized,()->{editorFolded=false;editorMaximized=!editorMaximized;});
        button("close-editor","x",W-26,5,20,20,false,()->{resetFocus();mc.setScreen(null);});
        tooltips.put("minimize-editor",tr("window.fold"));tooltips.put("maximize-editor",tr(editorMaximized?"window.restore":"window.maximize"));tooltips.put("close-editor",tr("window.close"));
    }
    private void toggleWindowMax(String id){resetFocus();if(id.equals("piano")){windows.get(id).folded=false;roll.toggleMaximize();}else windows.toggleMaximize(id);if(!id.equals("piano")&&!id.equals("rack"))modal=id;}
    private void toggleWindowFold(String id){resetFocus();windows.toggleFold(id);if(!id.equals("rack")&&!id.equals("piano"))modal=windows.get(id).folded?"":id;}
    private boolean window(float x,float y,float w,float h,String title,Runnable close,String dragType){
        String id=windowId(dragType);var state=windows.get(id);state.title=title;
        boolean folded=state.folded;float shownW=folded?Math.min(380,w):w,shownH=folded?28:h;
        hit("window-body-"+id,x,y,shownW,shownH,()->{});
        rect(x+4,y+5,shownW,shownH,0x6609161f);rect(x,y,shownW,shownH,0xff515e64);border(x,y,shownW,shownH,0xff293a43);line(x,y,shownW,0xff718087);rect(x+1,y+1,shownW-2,27,0xff3e4d55);text(fit(title,(int)shownW-(id.equals("piano")&&!folded?330:96)),x+10,y+(28-textHeight(1))/2,TEXT);
        if(dragType!=null)hit("window-"+id,x,y,shownW-80,28,()->{drag=new Drag(dragType,mouseX,mouseY);drag.left=x;drag.top=y;});
        button("minimize-"+id,folded?"+":"−",x+shownW-75,y+5,20,18,folded,()->toggleWindowFold(id));
        String maxId=id.equals("piano")?"piano-maximize":"maximize-"+id;boolean max=id.equals("piano")?roll.maximized:state.maximized;
        button(maxId,max?"▣":"□",x+shownW-50,y+5,20,18,max,()->toggleWindowMax(id));
        button("close-"+id,"x",x+shownW-25,y+5,19,18,false,()->{resetFocus();windows.close(id);if(id.equals("rack")||id.equals("piano"))close.run();else modal="";});
        tooltips.put("minimize-"+id,tr(folded?"window.unfold":"window.fold"));tooltips.put(maxId,tr(max?"window.restore":"window.maximize"));tooltips.put("close-"+id,tr("window.close"));
        return !folded;
    }
    private void drawFoldedModals(){
        if(!modal.isEmpty())return;int row=0;
        for(String id:windows.folded())if(!id.equals("rack")&&!id.equals("piano")){var state=windows.get(id);float x=8+(row%3)*390,y=772-(row/3)*32;row++;String previous=modal;modal=id;window(x,y,380,28,state.title,()->modal="",null);modal=previous;}
    }
    private void drawToolbar(){
        rect(0,0,W,31,0xff465158);text("◆ MUSIC STUDIO",13,11,TEXT);text("NEOFORGE / LDLib2",1044,11,MUTED,.7f);
        button("file",tr("action.file"),170,5,46,22,false,()->openProjects());button("edit",tr("action.undo"),220,5,48,22,false,this::undo);
        button("rack-menu",tr("action.channels"),272,5,83,22,rack,this::toggleRack);button("piano-menu",tr("action.piano"),359,5,94,22,piano,this::togglePiano);
        button("sounds-menu",tr("action.sounds"),457,5,67,22,false,()->openSounds(false));button("export-menu",tr("action.export"),528,5,67,22,false,()->openPanel("export"));
        button("delete-channel-menu",tr("channel.remove"),601,5,105,22,false,()->requestDeleteChannel(channel));
        button("language",tr("language."+StudioText.mode()),713,5,120,22,false,()->{resetFocus();StudioText.cycleLanguage();refreshSearch();status=tr("status.ready");});
        button("font-down","A−",843,5,36,22,false,()->StudioText.adjustZoom(-1));
        text(Math.round(StudioText.zoom()*100)+"%",889,12,MUTED,.85f);
        button("font-up","A+",947,5,36,22,false,()->StudioText.adjustZoom(1));
        button("media-menu",tr("media.title"),990,5,51,22,false,this::openMedia);
        rect(0,31,W,61,0xff4c5b63);line(0,91,W,EDGE);
        button("mode",patternMode?tr("mode.pattern"):tr("mode.song"),12,42,45,37,!patternMode,()->{stopPreview();patternMode=!patternMode;cursor=0;});
        button("play",transportActive?"■":">",62,42,37,37,transportActive,this::play);button("stop","■",103,42,37,37,false,this::stop);
        tooltips.put("play",tr("transport.play_hint"));tooltips.put("stop",tr("transport.stop_hint"));
        button("loop",tr("action.loop"),145,42,51,37,song.loop,()->edit(()->song.loop=!song.loop));
        input("bpm",Integer.toString(song.bpm),210,42,70,37,s->{int bpm=Integer.parseInt(s);if(bpm<20||bpm>400)throw new StudioException("error.bpm");edit(()->song.bpm=bpm);});text("BPM",234,81,MUTED,.7f);
        rect(294,39,194,45,0xff35464e);border(294,39,194,45,0xff687b85);
        int steps=(int)(cursor/SongProject.STEP);String pos=String.format(Locale.ROOT,"%02d:%02d:%02d",steps/16+1,steps%16/4+1,(int)(cursor%SongProject.STEP*24/SongProject.STEP));
        text(pos,306,45,0xffc1d2d8,2.05f);text(tr("transport.units"),327,74,0xff90aab5,.65f);
        drawSongWaveform();
        button("rack",tr("action.rack"),665,45,57,31,rack,this::toggleRack);button("piano",tr("action.roll"),727,45,57,31,piano,this::togglePiano);
        button("undo","<",795,45,31,31,false,this::undo);button("redo",">",831,45,31,31,false,this::redo);
        button("save",tr("action.save"),875,45,59,31,false,()->save(false));button("load",tr("action.open"),939,45,59,31,false,this::openNativeProject);
        button("export",tr("action.export_blueprint"),1130,44,134,34,false,()->{resetFocus();windows.get("export").folded=false;modal="export";});
        rect(0,92,W,43,PANEL);
        input("title",song.title,12,98,635,29,s->{if(s.isBlank()||s.length()>80)throw new StudioException("error.title_length");edit(()->{song.title=s;fileName=s;});});
        button("snap",tr("transport.snap",tr(snap==16?"snap.bar":snap==4?"snap.beat":"snap.sixteenth")),660,98,104,29,false,this::cycleSnap);
        button("pattern-prev","−",779,98,24,29,false,()->{selectPattern((pattern+song.patterns.size()-1)%song.patterns.size());});
        button("pattern",fit(patternName(song.patterns.get(pattern)),139),807,98,155,29,false,()->{selectPattern((pattern+1)%song.patterns.size());});
        button("pattern-next","+",966,98,24,29,false,this::newPattern);
        button("sound",tr("action.choose_voice"),1005,98,131,29,false,()->openSounds(true));
        text(tr("shortcut.windows"),1146,109,MUTED,.7f);line(0,134,W,EDGE);
    }
    private boolean browserGroup(String id,String key,float y,boolean expanded,Runnable toggle){
        button(id,(expanded?"▼ ":"▶ ")+tr(key),8,y,154,26,false,toggle);return expanded;
    }
    private void drawSongWaveform(){
        final int x=508,width=129;rect(503,43,139,35,0xff34454d);line(x,60,width,0xff506d70);
        var data=waveform.waveform();boolean live=audio.playing()&&!waveformOverview;
        if(data!=null){
            double seconds=cursor*60.0/(song.bpm*SongProject.PPQ);var columns=live?data.columns(seconds-.2,seconds,width):waveform.overview();
            for(int i=0;i<width;i++){float high=columns.high()[i],low=columns.low()[i];if(high-low>.00001f)rect(x+i,60-high*13,1,Math.max(1,(high-low)*13),live?0xffa4d695:0xff7faaa4);}
            if(!live&&seconds>=0&&seconds<data.seconds())rect(x+(float)(seconds/data.seconds())*(width-1),46,1,29,GREEN);
        }else if(waveform.pending())text("…",566,49,MUTED,.85f);else if(!waveform.error().isEmpty())text("!",568,49,0xffe5cb8c,.85f);
        hit("song-waveform",503,43,139,35,()->waveformOverview=!waveformOverview);
        tooltips.put("song-waveform",!waveform.error().isEmpty()?tr("waveform.failed",waveform.error()):waveform.pending()?tr("waveform.processing"):tr(live?"waveform.live":"waveform.overview"));
    }
    private void selectPattern(int index){pattern=index;selectedAudio="";selectedNote="";notesContext=false;}
    private void restoreOrderFocus(String source,String phrase,String lane){
        int oldChannel=channel,oldPattern=pattern;channel=Mth.clamp(channel,0,song.channels.size()-1);pattern=Mth.clamp(pattern,0,song.patterns.size()-1);activeTrack=Mth.clamp(activeTrack,0,song.tracks.size()-1);
        for(int i=0;i<song.channels.size();i++)if(song.channels.get(i).id.equals(source)){channel=i;break;}
        for(int i=0;i<song.patterns.size();i++)if(song.patterns.get(i).id.equals(phrase)){pattern=i;break;}
        for(int i=0;i<song.tracks.size();i++)if(song.tracks.get(i).id.equals(lane)){activeTrack=i;break;}
        if(lastPianoChannel==oldChannel)lastPianoChannel=channel;if(lastPianoPattern==oldPattern)lastPianoPattern=pattern;selectionChannel=channel;selectionPattern=pattern;
    }
    private void beginOrder(String type,String id){drag=new Drag(type,mouseX,mouseY);drag.id=id;}
    private boolean orderMoved(){return drag!=null&&Math.abs(mouseX-drag.x)+Math.abs(mouseY-drag.y)>4;}
    private String orderKind(Drag d){return switch(d.type){case "browser-pattern"->"pattern";case "browser-source","order-rack"->"channel";case "browser-audio"->"audio";case "order-track"->"track";default->null;};}
    private record OrderTarget(String kind,String region,int from,int to,float x,float y,float width){}
    private StudioWindows.Bounds rackBounds(){return frame("rack",rackX,135+rackY,650,91+Math.min(12,song.channels.size())*29);}
    private OrderTarget orderTarget(){
        if(drag==null)return null;String kind=orderKind(drag);if(kind==null)return null;
        List<String> ids=switch(kind){case "pattern"->song.patterns.stream().map(p->p.id).toList();case "channel"->song.channels.stream().map(c->c.id).toList();case "track"->song.tracks.stream().map(t->t.id).toList();default->song.audioSources.stream().map(c->c.id).toList();};int from=ids.indexOf(drag.id);if(from<0)return null;
        int boundary;float lineY,x,width;String region;
        if(drag.type.startsWith("browser-")){
            int tab=kind.equals("pattern")?0:kind.equals("channel")?1:2;if(browserTab!=tab||mouseX<5||mouseX>=165||mouseY<233||mouseY>=713)return null;
            boundary=Mth.clamp(browserScroll+(int)Math.floor((mouseY-235+19)/34),0,ids.size());lineY=235+(boundary-browserScroll)*34-2;x=8;width=153;region="browser";
        }else if(drag.type.equals("order-rack")){
            var b=rackBounds();int visible=Math.min(ids.size(),Math.max(1,(int)(b.height()-91)/29));float top=b.y()+53;
            if(mouseX<b.x()||mouseX>=b.x()+b.width()||mouseY<top-4||mouseY>=top+visible*29+1)return null;
            boundary=Mth.clamp(rackScroll+(int)Math.floor((mouseY-top+17.5)/29),0,ids.size());lineY=top+(boundary-rackScroll)*29-2;x=b.x()+10;width=b.width()-20;region="rack";
        }else{
            if(mouseX<TRACK_X||mouseX>=1265||mouseY<TRACK_TOP||mouseY>=TRACK_BOTTOM)return null;
            lineY=TRACK_TOP-(float)viewport.vertical;boundary=0;for(var track:song.tracks){float height=trackHeight(track);if(mouseY<lineY+height/2)break;lineY+=height;boundary++;}
            lineY=Math.max(TRACK_TOP,Math.min(TRACK_BOTTOM-2,lineY));x=TRACK_X;width=1265-TRACK_X;region="track";
        }
        return new OrderTarget(kind,region,from,Mth.clamp(boundary>from?boundary-1:boundary,0,ids.size()-1),x,lineY,width);
    }
    private void drawOrderMarker(String region){if(!orderMoved())return;var target=orderTarget();if(target!=null&&target.region.equals(region)&&target.from!=target.to){rect(target.x,target.y,target.width,2,GREEN);rect(target.x,target.y-3,4,8,GREEN);}}
    private void reorder(OrderTarget target){
        syncSelection();String source=song.channels.get(channel).id,phrase=song.patterns.get(pattern).id,lane=song.tracks.get(activeTrack).id;
        edit(()->{switch(target.kind){case "pattern"->song.reorderPattern(target.from,target.to);case "channel"->song.reorderChannel(target.from,target.to);case "track"->song.reorderTrack(target.from,target.to);case "audio"->song.reorderAudioSource(target.from,target.to);default->throw new IllegalStateException();}restoreOrderFocus(source,phrase,lane);});
        if(target.region.equals("track"))revealTrack(activeTrack);status=tr("status.reordered");
    }
    private void autoScrollOrder(){
        if(drag==null||orderKind(drag)==null||!orderMoved())return;
        if(drag.type.startsWith("browser-")&&mouseX>=5&&mouseX<165&&mouseY>=223&&mouseY<723){if(++drag.limit%4!=0)return;int count=browserTab==0?song.patterns.size():browserTab==1?song.channels.size():song.audioSources.size();if(mouseY<250)browserScroll--;else if(mouseY>695)browserScroll++;browserScroll=Mth.clamp(browserScroll,0,Math.max(0,count-14));}
        else if(drag.type.equals("order-rack")){var b=rackBounds();int visible=Math.min(song.channels.size(),Math.max(1,(int)(b.height()-91)/29));float top=b.y()+53,bottom=top+visible*29;if(mouseX>=b.x()&&mouseX<b.x()+b.width()&&mouseY>=top-10&&mouseY<=bottom+10&&++drag.limit%4==0){if(mouseY<top+14)rackScroll--;else if(mouseY>bottom-14)rackScroll++;rackScroll=Mth.clamp(rackScroll,0,Math.max(0,song.channels.size()-visible));}}
        else if(drag.type.equals("order-track")&&mouseX>=TRACK_X&&mouseX<1265){if(mouseY>=TRACK_TOP-10&&mouseY<TRACK_TOP+18)viewport.vertical-=12;else if(mouseY>TRACK_BOTTOM-18&&mouseY<=TRACK_BOTTOM+10)viewport.vertical+=12;clampVertical();}
    }
    private void drawBrowser(){
        rect(0,135,174,670,0xff35434b);rect(171,135,3,670,EDGE);
        String[] tabs={"browser.patterns","browser.sources","browser.audio"};
        String[] tabLabels={"browser.pattern_tab","browser.source_tab","browser.audio_tab"};
        for(int i=0;i<3;i++){int tab=i;button("browser-tab-"+i,tr(tabLabels[i]),5+i*55,140,53,25,browserTab==i,()->{browserTab=tab;browserScroll=0;});tooltips.put("browser-tab-"+i,tr(tabs[i]));}
        button("project-browser",tr("browser.projects"),8,174,101,24,false,this::openProjects);
        button("browser-add","+",116,174,45,24,false,()->{if(browserTab==0)newPattern();else if(browserTab==1){windows.get("add-source").folded=false;modal="add-source";}else chooseAudioFile(false);});
        int count=browserTab==0?song.patterns.size():browserTab==1?song.channels.size():song.audioSources.size();browserScroll=Mth.clamp(browserScroll,0,Math.max(0,count-14));
        text(tr(tabs[browserTab])+" · "+count,12,210,MUTED,.85f);
        clip(5,233,160,480);
        for(int i=browserScroll;i<Math.min(count,browserScroll+14);i++){
            int index=i;float y=235+(i-browserScroll)*34;boolean selected=browserTab==0?pattern==i&&selectedAudio.isEmpty():browserTab==1?channel==i:song.audioSources.get(i).id.equals(selectedAudio);
            int color=browserTab==0?song.patterns.get(i).color:browserTab==1?song.channels.get(i).color:song.audioSources.get(i).color;
            String name=browserTab==0?patternName(song.patterns.get(i)):channelName(browserTab==1?song.channels.get(i):song.audioSources.get(i));
            rect(8,y,153,30,selected?0xff5b6c72:0xff43545e);rect(8,y,3,30,0xff000000|color);text(fit(name,browserTab==2?106:128,.9f),25,y+8,TEXT,.9f);for(int dot=0;dot<6;dot++)rect(15+dot%2*3,y+10+dot/2*4,1,1,MUTED);
            String id="browser-item-"+i;tooltips.put(id,name+" · "+tr(browserTab==1?"order.source_browser_hint":"order.browser_hint")+" · "+tr(browserTab==0?"browser.pattern_hint":browserTab==1?"browser.source_hint":"browser.audio_hint"));
            if(browserTab==0)hit(id,8,y,153,30,()->{selectPattern(index);drag=new Drag("browser-pattern",mouseX,mouseY);drag.id=song.patterns.get(index).id;},()->{selectPattern(index);windows.get("pattern-settings").folded=false;modal="pattern-settings";},()->{selectPattern(index);rack=true;});
            else if(browserTab==1)hit(id,8,y,153,30,()->{channel=index;selectedNote="";rack=true;beginOrder("browser-source",song.channels.get(index).id);},()->openChannelSettings(index),()->{channel=index;piano=true;notesContext=true;});
            else {
                hit(id,8,y,128,30,()->{selectedAudio=song.audioSources.get(index).id;drag=new Drag("browser-audio",mouseX,mouseY);drag.id=selectedAudio;},()->openAudioSample(index),()->openAudioSample(index));
                button("browser-delete-audio-"+i,"×",138,y+5,20,20,false,()->requestDeleteAudio(song.audioSources.get(index).id));
                tooltips.put("browser-delete-audio-"+i,tr("audio.remove"));
            }
        }drawOrderMarker("browser");unclip();
        if(browserTab==1){button("notes-browser",tr("browser.note_blocks"),8,723,153,26,false,()->openSounds(true));button("all-sounds",tr("browser.all_sounds"),8,755,153,26,false,()->openSounds(false));}
        else if(browserTab==2)button("import-audio-browser",tr("media.import_audio"),8,746,153,30,false,()->chooseAudioFile(false));
        else button("browser-pattern-new",tr("browser.new_pattern"),8,746,153,30,false,this::newPattern);
    }
    private static final int TRACK_X=174,TIME_X=286,TIME_W=978,TRACK_TOP=209,TRACK_BOTTOM=784,TRACK_AREA=TRACK_BOTTOM-TRACK_TOP;
    private float trackHeight(Track c){return (float)Math.max(24,c.displayHeight*viewport.trackZoom);}
    private float tracksHeight(){float n=44;for(var c:song.tracks)n+=trackHeight(c);return n;}
    private void clampVertical(){viewport.vertical=Math.max(0,Math.min(viewport.vertical,Math.max(0,tracksHeight()-TRACK_AREA)));}
    private void revealTrack(int index){double top=0;for(int i=0;i<index;i++)top+=trackHeight(song.tracks.get(i));double bottom=top+trackHeight(song.tracks.get(index));if(top<viewport.vertical)viewport.vertical=top;else if(bottom>viewport.vertical+TRACK_AREA)viewport.vertical=bottom-TRACK_AREA;clampVertical();}
    private int trackAt(float y){if(y<TRACK_TOP||y>=TRACK_BOTTOM)return -1;float top=TRACK_TOP-(float)viewport.vertical;for(int i=0;i<song.tracks.size();i++){float h=trackHeight(song.tracks.get(i));if(y>=top&&y<top+h)return i;top+=h;}return -1;}
    private int selectedAudioIndex(){for(int i=0;i<song.audioSources.size();i++)if(song.audioSources.get(i).id.equals(selectedAudio))return i;return -1;}
    private String trackName(Track t){return t.nameKey.equals("track.default")?tr("track.default",song.tracks.indexOf(t)+1):StudioText.named(t.nameKey,t.name);}
    private void placeClip(int lane,int ppq){if(lane<0||song.clips.size()>=4096)return;int source=selectedAudioIndex();int length=source<0?song.patterns.get(pattern).length:song.audioLength(song.audioSources.get(source));length=Math.min(length,SongProject.MAX_SONG_PPQ-ppq);if(length<=0)return;Clip c=source<0?Clip.pattern(lane,pattern,ppq,length):Clip.audio(lane,source,ppq,length);edit(()->{extendSong(c.start+c.duration);song.clips.add(c);clipSelection.clear();chooseClip(c.id);activeTrack=lane;});}
    private int mousePosition(){return TimelineSnap.position((viewport.start+(mouseX-TIME_X)/TIME_W*viewport.bars)*PlaylistView.BAR,snap,SongProject.MAX_SONG_PPQ);}
    private float timeX(double bar){return TIME_X+(float)((bar-viewport.start)/viewport.bars*TIME_W);}
    private void drawHorizontal(String id,float x,float y,float width,float height){
        double total=drag!=null&&drag.type.startsWith("scroll-h")?drag.extent:viewport.extent(song.endPpq/PlaylistView.BAR);
        float thumb=(float)PlaylistView.thumb(width,viewport.bars,total),left=x+(float)(viewport.start/Math.max(.0001,total-viewport.bars))*(width-thumb);
        rect(x,y,width,height,0xff233842);rect(left,y+2,thumb,height-4,0xff607b88);rect(left,y+2,3,height-4,0xff94a9ae);rect(left+thumb-3,y+2,3,height-4,0xff94a9ae);
        hit(id,x,y,width,height,()->{drag=new Drag("scroll-h",mouseX,mouseY);drag.left=x;drag.top=width;drag.extent=total;drag.original=viewport.start;drag.anchor=mouseX>=left&&mouseX<=left+thumb?mouseX-left:thumb/2;updateScroll();});
        hit(id+"-left",left,y,5,height,()->{drag=new Drag("scroll-h-left",mouseX,mouseY);drag.left=x;drag.top=width;drag.extent=total;drag.original=viewport.start+viewport.bars;drag.anchor=viewport.start;});
        hit(id+"-right",left+thumb-5,y,5,height,()->{drag=new Drag("scroll-h-right",mouseX,mouseY);drag.left=x;drag.top=width;drag.extent=total;drag.original=viewport.start;drag.anchor=viewport.start+viewport.bars;});
        tooltips.put(id,tr("playlist.horizontal_hint"));
    }
    private void drawVertical(){
        clampVertical();double total=tracksHeight();float size=(float)PlaylistView.thumb(TRACK_AREA,TRACK_AREA,total),top=TRACK_TOP+(float)(viewport.vertical/Math.max(1,total-TRACK_AREA))*(TRACK_AREA-size);
        rect(1266,TRACK_TOP,14,TRACK_AREA,0xff233842);rect(1268,top,10,size,0xff667e87);
        hit("playlist-scroll-v",1266,TRACK_TOP,14,TRACK_AREA,()->{double current=tracksHeight(),thumb=PlaylistView.thumb(TRACK_AREA,TRACK_AREA,current),offset=TRACK_TOP+viewport.vertical/Math.max(1,current-TRACK_AREA)*(TRACK_AREA-thumb);drag=new Drag("scroll-v",mouseX,mouseY);drag.top=TRACK_TOP;drag.left=TRACK_AREA;drag.extent=current;drag.anchor=mouseY>=offset&&mouseY<=offset+thumb?mouseY-offset:thumb/2;updateScroll();});tooltips.put("playlist-scroll-v",tr("playlist.vertical_hint"));
    }
    private void updateScroll(){
        if(drag.type.equals("scroll-h")){double size=PlaylistView.thumb(drag.top,viewport.bars,drag.extent);viewport.pan(Math.max(0,Math.min(1,(mouseX-drag.left-drag.anchor)/Math.max(1,drag.top-size)))*(drag.extent-viewport.bars));}
        else if(drag.type.startsWith("scroll-h-")){double change=(mouseX-drag.x)/drag.top*drag.extent;if(drag.type.endsWith("left"))viewport.range(Math.max(0,Math.min(drag.original-PlaylistView.MIN_BARS,drag.anchor+change)),drag.original);else viewport.range(drag.original,Math.max(drag.original+PlaylistView.MIN_BARS,drag.anchor+change));}
        else{double size=PlaylistView.thumb(drag.left,TRACK_AREA,drag.extent);viewport.vertical=Math.max(0,Math.min(1,(mouseY-drag.top-drag.anchor)/Math.max(1,drag.left-size)))*Math.max(0,drag.extent-TRACK_AREA);clampVertical();}
    }
    private void beginTrackResize(int index,boolean upper){
        drag=new Drag("track-height",mouseX,mouseY);drag.id=song.tracks.get(index).id;drag.duration=song.tracks.get(index).displayHeight;drag.resize=upper;drag.before=ProjectStore.encode(song);
    }
    private void drawPlaylist(){
        int x=TRACK_X,w=W-x,head=TIME_X-x;double viewEnd=viewport.start+viewport.bars;
        rect(x,135,w,29,0xff3c4b53);text(tr("playlist.title"),x+10,145,TEXT);
        button("range-start-at-cursor",tr("range.start"),400,140,70,19,false,()->edit(()->song.exportStart=rangePosition(cursor)));button("range-end-at-cursor",tr("range.end"),475,140,70,19,false,()->edit(()->song.exportEnd=rangePosition(cursor)));button("range-reset",tr("range.full"),550,140,70,19,false,()->edit(()->song.exportStart=song.exportEnd=0));text(fit(rangeSummary(),190,.75f),630,146,MUTED,.75f);
        button("copy-clips",tr("action.copy"),824,140,69,19,false,()->{notesContext=false;copySelection(false);});button("cut-clips",tr("action.cut"),898,140,63,19,false,()->{notesContext=false;copySelection(true);});button("paste-clips",tr("action.paste"),966,140,70,19,false,()->{notesContext=false;pasteSelection();});
        button("prev-bars","<",1041,140,26,19,false,()->viewport.pan(viewport.start-viewport.bars*.75));text(String.format(Locale.ROOT,"%.1f — %.1f",viewport.start+1,viewEnd+1),1073,147,MUTED,.75f);button("next-bars",">",1235,140,26,19,false,()->viewport.pan(viewport.start+viewport.bars*.75));
        drawHorizontal("playlist-scroll-top",TIME_X,165,TIME_W,18);rect(x,184,w,24,0xff273a45);text(tr("playlist.tracks"),x+10,192,0xff819da9,.8f);
        button("tracks-up","↑",x+70,185,18,20,false,()->{viewport.vertical-=160;clampVertical();});button("tracks-down","↓",x+91,185,18,20,false,()->{viewport.vertical+=160;clampVertical();});
        double grid=Math.pow(2,Math.ceil(Math.log(Math.max(snap/16.0,viewport.bars*7/TIME_W))/Math.log(2)));grid=Math.max(1/16.0,grid);
        double labelStep=Math.max(1,Math.pow(2,Math.ceil(Math.log(viewport.bars*55/TIME_W)/Math.log(2))));
        clip(TIME_X,184,TIME_W,24);
        for(double bar=Math.ceil(viewport.start/labelStep)*labelStep;bar<viewEnd;bar+=labelStep)text(Long.toString(Math.round(bar)+1),timeX(bar)+5,192,MUTED,.8f);
        for(double bar=Math.ceil(viewport.start/grid)*grid;bar<viewEnd;bar+=grid)rect(timeX(bar),202,1,6,Math.abs(bar-Math.rint(bar))<1e-6?0xff819da9:0xff526c78);unclip();
        hit("playlist-seek",TIME_X,184,TIME_W,24,()->beginSeek(false,TIME_X,TIME_W,(int)(viewport.start*PlaylistView.BAR),Math.max(1,(int)(viewport.bars*PlaylistView.BAR)),SongProject.MAX_SONG_PPQ));
        tooltips.put("playlist-seek",tr("transport.seek_hint"));
        clampVertical();clip(TRACK_X,TRACK_TOP,1265-TRACK_X,TRACK_AREA);rect(TRACK_X,TRACK_TOP,1265-TRACK_X,TRACK_AREA,0xff2a3e49);
        float yy=TRACK_TOP-(float)viewport.vertical;
        for(int c=0;c<song.tracks.size();c++){
            final int ci=c;Track lane=song.tracks.get(c);float rowH=trackHeight(lane),y=yy;yy+=rowH;if(y+rowH<TRACK_TOP||y>=TRACK_BOTTOM)continue;int color=0xff000000|lane.color;
            rect(x,y,head-2,rowH-1,shade(color,c==activeTrack?1:.82f));rect(x,y,3,rowH-1,color);text(fit(trackName(lane),68,.95f),x+18,y+8,TEXT,.95f);for(int dot=0;dot<6;dot++)rect(x+6+dot%2*3,y+10+dot/2*4,1,1,MUTED);if(rowH>=48)text(tr("track.contents"),x+11,y+29,0xffbbcace,.75f);
            hit("track-"+c,x,y,head-20,rowH,()->{activeTrack=ci;beginOrder("order-track",song.tracks.get(ci).id);},()->openTrackSettings(ci),()->resetTrackHeight(ci));tooltips.put("track-"+c,trackName(lane)+" · "+tr("order.track_hint"));
            button("track-settings-"+c,"…",x+head-23,y+3,19,17,false,()->openTrackSettings(ci));button("mute-"+c,lane.muted?"o":"•",x+head-22,y+rowH-21,15,15,!lane.muted,()->edit(()->lane.muted=!lane.muted));
            rect(TIME_X,y,TIME_W,rowH-1,c%2==0?0xff2d414d:0xff2b3e49);for(double bar=Math.ceil(viewport.start/grid)*grid;bar<viewEnd;bar+=grid)rect(timeX(bar),y,1,rowH-1,Math.abs(bar-Math.rint(bar))<1e-6?0xff1c3341:0xff2a3d49);
            hit("lane-"+c,TIME_X,y,TIME_W,rowH,()->placeClip(ci,mousePosition()));
            for(Clip cl:song.clips)if(cl.track==c&&cl.start/PlaylistView.BAR<viewEnd&&(cl.start+cl.duration)/PlaylistView.BAR>viewport.start){
                int a=Math.max(cl.start,(int)(viewport.start*PlaylistView.BAR)),b=Math.min(cl.start+cl.duration,(int)Math.ceil(viewEnd*PlaylistView.BAR));float left=timeX(a/PlaylistView.BAR),width=(float)((b-a)/PlaylistView.BAR/viewport.bars*TIME_W);
                int cc=0xff000000|(cl.audio()?song.audioSources.get(cl.audioSource).color:song.patterns.get(cl.pattern).color);if(lane.muted)cc=shade(cc,.55f);
                String name=cl.audio()?channelName(song.audioSources.get(cl.audioSource)):patternName(song.patterns.get(cl.pattern));
                rect(left+1,y+3,Math.max(1,width-2),rowH-7,shade(cc,.8f));rect(left+1,y+3,Math.max(1,width-2),Math.min(22,rowH-7),shade(cc,1.02f));if(width>20)text(fit(name,(int)width-10,1),left+5,y+7,TEXT,1);if(width>6)drawMini(cl,left+3,y+29,width-5,rowH-36,a,b);
                tooltips.put("clip-"+cl.id,name+" · "+tr("playlist.clip_edges_hint"));if(clipSelection.contains(cl.id))border(left+1,y+3,width-2,rowH-7,0xffd5c39b);
                float actualLeft=timeX(cl.start/PlaylistView.BAR),actualRight=timeX((cl.start+cl.duration)/PlaylistView.BAR);
                if(clipSelection.contains(cl.id)&&width>=18){if(actualLeft>=TIME_X)rect(actualLeft+3,y+rowH/2-5,2,10,0xffd5c39b);if(actualRight<=TIME_X+TIME_W)rect(actualRight-5,y+rowH/2-5,2,10,0xffd5c39b);}
                hit("clip-"+cl.id,left+1,y+3,Math.max(1,width-2),rowH-7,()->beginClipDrag(cl,actualLeft,actualRight),()->edit(()->{song.clips.remove(cl);clipSelection.remove(cl.id);}),()->{activeTrack=cl.track;if(cl.audio())openAudioSample(cl.audioSource);else{selectPattern(cl.pattern);piano=true;windows.get("piano").folded=false;notesContext=true;}});
            }
            hit("track-height-top-"+c,x,y,head,3,()->beginTrackResize(ci,true),null,()->resetTrackHeight(ci));hit("track-height-bottom-"+c,x,y+rowH-4,1265-x,5,()->beginTrackResize(ci,false),null,()->resetTrackHeight(ci));tooltips.put("track-height-bottom-"+c,tr("channel.height_hint"));
        }
        if(yy<TRACK_BOTTOM){rect(x,yy,head-2,40,0xff354851);text("+",x+50,yy+13,MUTED);hit("plus-track",x,yy,head-2,40,this::newTrack);}
        if(cursor/PlaylistView.BAR>=viewport.start&&cursor/PlaylistView.BAR<viewEnd){float cx=timeX(cursor/PlaylistView.BAR);rect(cx,TRACK_TOP,1,TRACK_AREA,GREEN);}drawOrderMarker("track");unclip();
        if(cursor/PlaylistView.BAR>=viewport.start&&cursor/PlaylistView.BAR<viewEnd){float cx=timeX(cursor/PlaylistView.BAR);rect(cx,199,1,10,GREEN);text("▼",cx-4,192,GREEN,.8f);}
        drawExportCursors();
        drawHorizontal("playlist-scroll-bottom",TIME_X,788,TIME_W,17);drawVertical();
        if(drag!=null&&drag.type.equals("box-clips"))drawSelectionBox();
    }
    private int rangePosition(double ppq){int unit=snap*SongProject.STEP;return Mth.clamp((int)Math.round(ppq/unit)*unit,0,SongProject.MAX_SONG_PPQ);}
    private String musicalPosition(int ppq){return (ppq/(4*SongProject.PPQ)+1)+":"+(ppq/SongProject.PPQ%4+1)+":"+(ppq/SongProject.STEP%4+1);}
    private String rangeSummary(){var r=ExportRange.of(song);return r.full()?tr("range.full"):tr("range.summary",musicalPosition(r.start()),musicalPosition(r.end()));}
    private void drawExportCursors(){
        var range=ExportRange.of(song);if(!range.full()){float a=Math.max(TIME_X,timeX(range.start()/PlaylistView.BAR)),b=Math.min(TIME_X+TIME_W,timeX(range.end()/PlaylistView.BAR));if(b>a)rect(a,184,b-a,24,0x447ea48a);}
        for(int i=0;i<2;i++){final int marker=i;int at=i==0?song.exportStart:song.exportEnd;float x=timeX(at/PlaylistView.BAR);if(x<TIME_X||x>TIME_X+TIME_W)continue;int color=i==0?0xff87d6cc:0xffe6bd7a;float y=i==0?184:197;
            rect(x,TRACK_TOP,1,TRACK_AREA,color);for(int n=0;n<6;n++)rect(x-5+n,y+n,11-2*n,1,color);
            String id=i==0?"range-start":"range-end";hit(id,Math.max(TIME_X,x-7),y,Math.min(14,TIME_X+TIME_W-Math.max(TIME_X,x-7)),11,()->{stopPreview();drag=new Drag("export-marker",mouseX,mouseY);drag.edge=marker;drag.start=at;drag.anchor=viewport.start+(mouseX-TIME_X)/TIME_W*viewport.bars;drag.before=ProjectStore.encode(song);});tooltips.put(id,tr(i==0?"range.start_hint":"range.end_hint"));
        }
    }
    private void beginClipDrag(Clip cl,float actualLeft,float actualRight){
        stopPreview();chooseClip(cl.id);activeTrack=cl.track;if(cl.audio())selectedAudio=song.audioSources.get(cl.audioSource).id;else selectPattern(cl.pattern);
        drag=new Drag("clip",mouseX,mouseY);drag.id=cl.id;drag.start=cl.start;drag.pitch=cl.track;drag.duration=cl.duration;drag.anchor=viewport.start+(mouseX-TIME_X)/TIME_W*viewport.bars;
        float handle=Math.min(9,(actualRight-actualLeft)/3);drag.edge=actualLeft>=TIME_X&&mouseX<actualLeft+handle?-1:actualRight<=TIME_X+TIME_W&&mouseX>actualRight-handle?1:0;drag.before=ProjectStore.encode(song);
        for(Clip old:selectedClips())drag.originals.put(old.id,new int[]{old.start,old.duration,old.contentOffset,old.track});
    }
    private void drawMini(Clip cl,float x,float y,float width,float height,int a,int b){
        if(height<=0)return;
        if(cl.audio()){
            var c=song.audioSources.get(cl.audioSource);var peaks=clipWaves.get(song,c);if(peaks==null)return;int origin=cl.start+cl.contentOffset;double sourcePpq=song.audioLength(c);float mid=y+height/2;
            for(int i=0;i<(int)width;i++){double time=a+(b-a)*(i+.5)/width,position=(time-origin)/sourcePpq;if(position<0||position>=1||time>=origin+cl.sourceLength())continue;float peak=peaks[Math.min(peaks.length-1,(int)(position*peaks.length))];rect(x+i,mid-peak*height*.45f,1,Math.max(1,peak*height*.9f),0xffcad2c7);}return;
        }
        var ns=song.patterns.get(cl.pattern).notes.stream().flatMap(List::stream).toList();if(ns.isEmpty())return;
        int min=ns.stream().mapToInt(n->n.pitch).min().orElse(60),max=Math.max(min+12,ns.stream().mapToInt(n->n.pitch).max().orElse(72));
        for(var n:song.clipEvents(cl,a,b)){int time=n.start(),finish=time+n.duration();float nx=x+(Math.max(time,a)-a)/(float)(b-a)*width,nw=(Math.min(finish,b)-Math.max(time,a))/(float)(b-a)*width;float ny=y+height-(n.pitch()-min)/(float)(max-min+2)*height-3;rect(nx,ny,Math.max(1,nw-1),2,0xffcad2c7);}
    }
    private void drawRack(){
        int baseVisible=Math.min(12,song.channels.size());var bounds=frame("rack",rackX,135+rackY,650,91+baseVisible*29);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();int visible=Math.min(song.channels.size(),Math.max(1,(int)(h-91)/29));rackScroll=Mth.clamp(rackScroll,0,Math.max(0,song.channels.size()-visible));
        if(!window(x,y,w,h,tr("rack.title",patternName(song.patterns.get(pattern))),()->rack=false,"rack"))return;
        text(patternName(song.patterns.get(pattern)),x+10,y+36,MUTED,.8f);text(tr("rack.steps",Math.min(32,song.patterns.get(pattern).length/SongProject.STEP)),x+445,y+36,MUTED,.8f);button("rack-up","↑",x+590,y+30,22,20,false,()->rackScroll=Math.max(0,rackScroll-6));button("rack-down","↓",x+618,y+30,22,20,false,()->rackScroll=Math.min(Math.max(0,song.channels.size()-12),rackScroll+6));
        for(int c=rackScroll;c<rackScroll+visible;c++){
            int ci=c;Channel ch=song.channels.get(c);float yy=y+53+(c-rackScroll)*29;
            button("rack-mute-"+c,ch.muted?"o":"•",x+10,yy+3,13,17,!ch.muted,()->edit(()->ch.muted=!ch.muted));
            for(int dot=0;dot<6;dot++)rect(x+31+dot%2*4,yy+6+dot/2*4,2,2,MUTED);
            hit("rack-reorder-"+c,x+27,yy,16,23,()->{channel=ci;beginOrder("order-rack",ch.id);});tooltips.put("rack-reorder-"+c,tr("order.hint"));
            rect(x+45,yy+6,10,10,0xff34434a);border(x+45,yy+6,10,10,0xff697b82);rect(x+50,yy+7,1,4,MUTED);
            button("channel-"+c,fit(channelName(ch),85),x+63,yy,105,23,c==channel,()->{channel=ci;selectedNote="";},null);
            rect(x+63,yy,3,23,0xff000000|ch.color);
            // Replace channel hit with both selection and a double-click instrument browser.
            hits.removeLast();hit("channel-"+c,x+63,yy,105,23,()->{channel=ci;selectedNote="";beginOrder("order-rack",song.channels.get(ci).id);},()->openChannelSettings(ci),()->{channel=ci;openSounds(false);});
            int nsteps=Math.min(32,song.patterns.get(pattern).length/SongProject.STEP);
            for(int t=0;t<nsteps;t++) {
                int st=t;float cell=(w-194)/32;float xx=x+180+t*cell;boolean on=song.patterns.get(pattern).notes.get(c).stream().anyMatch(n->n.start==st*SongProject.STEP),alt=t/4%2==1;
                int color=on?(alt?0xffc69aa5:0xffbdc5bd):(alt?0xff5e5661:0xff4e6069);
                rect(xx,yy+3,11,18,color);line(xx,yy+3,11,shade(color,1.2f));line(xx,yy+20,11,0xff2d414b);border(xx,yy+3,11,18,shade(color,.8f));
                hit("step-"+c+"-"+t,xx,yy,13,24,()->edit(()->{
                    List<Note> list=song.patterns.get(pattern).notes.get(ci);
                    if(list.stream().anyMatch(n->n.start==st*SongProject.STEP))list.removeIf(n->n.start==st*SongProject.STEP);else if(totalNotes()<SongProject.MAX_EVENTS)list.add(BlueprintAudio.drawnNote(song.channels.get(ci),st,song.channels.get(ci).baseMidi,song.patterns.get(pattern).length-st*SongProject.STEP,1));
                    song.growPattern(pattern);
                }));
            }
        }
        drawOrderMarker("rack");float yy=y+h-29;rect(x+1,yy,w-2,28,0xff43535b);line(x,yy,w,0xff687980);
        button("rack-roll",tr("action.piano_short"),x+10,yy+4,96,21,false,()->piano=true);
        button("rack-sound",tr("action.voice"),x+115,yy+4,106,21,false,()->{if(song.channels.get(channel).sampleId.isEmpty())openSounds(false);else openSample();});
        button("rack-delete-channel",tr("channel.remove"),x+230,yy+4,112,21,false,()->requestDeleteChannel(channel));
        text(fit(song.channels.get(channel).sound,230,.85f),x+354,yy+8,MUTED,.85f);button("rack-add-source","+",x+w-40,yy+4,30,21,false,()->openPanel("add-source"));
    }
    // Overload used when the button's hit will be replaced by a richer interaction.
    private void button(String id,String label,float x,float y,float w,float h,boolean active,Runnable action,Object ignored){button(id,label,x,y,w,h,active,action);}
    private int rollLength(){return song.patterns.get(pattern).length/SongProject.STEP;}
    private void drawPiano(){
        syncSelection();int length=rollLength();
        if(lastPianoChannel!=channel||lastPianoPattern!=pattern){int max=notes().stream().mapToInt(n->n.pitch).max().orElse(song.channels.get(channel).baseMidi);roll.top=127-max-roll.rows/3;if(lastPianoPattern!=pattern)roll.start=0;lastPianoChannel=channel;lastPianoPattern=pattern;}
        roll.clamp(length);float x=roll.x,y=roll.y,w=roll.width,h=roll.height,gx=roll.gridX(),gy=roll.gridY(),gw=roll.gridWidth(),gh=roll.gridHeight(),rh=pianoRowHeight(),cw=gw/(float)roll.span;
        if(!window(x,y,w,h,tr("piano.title",channelName(song.channels.get(channel))),()->{piano=false;notesContext=false;},"piano"))return;
        button("piano-channel",fit(channelName(song.channels.get(channel)),105)+" ▾",x+9,y+33,128,23,pianoPicker.equals("source"),()->togglePianoPicker("source"));tooltips.put("piano-channel",tr("piano.pick_source_hint"));
        button("piano-pattern",fit(patternName(song.patterns.get(pattern)),194)+" ▾",x+w-310,y+5,220,18,pianoPicker.equals("pattern"),()->togglePianoPicker("pattern"));tooltips.put("piano-pattern",tr("piano.pick_pattern_hint"));
        button("piano-sound",tr("action.choose_sound"),x+145,y+33,100,23,false,()->{if(song.channels.get(channel).sampleId.isEmpty())openSounds(false);else openSample();});
        button("octave-down",tr("piano.octave_down"),x+253,y+33,58,23,false,()->{roll.top+=12;roll.clamp(length);});button("octave-up",tr("piano.octave_up"),x+316,y+33,58,23,false,()->{roll.top-=12;roll.clamp(length);});
        button("piano-play",transportActive?"■":">",x+382,y+33,30,23,transportActive,this::play);button("piano-stop","■",x+419,y+33,30,23,false,this::stop);tooltips.put("piano-play",tr("transport.play_hint"));tooltips.put("piano-stop",tr("transport.stop_hint"));
        button("roll-page-prev","<",x+w-250,y+33,28,23,false,()->{roll.start-=roll.span;roll.clamp(length);});text(String.format(Locale.ROOT,"%.1f — %.1f",roll.start/16+1,(roll.start+roll.span)/16+1),x+w-214,y+40,MUTED,.8f);button("roll-page-next",">",x+w-45,y+33,28,23,false,()->{roll.start+=roll.span;roll.clamp(length);});
        rect(x+1,y+63,w-2,gh+18,0xff263b47);
        clip(x+1,gy,44,gh);
        for(int row=(int)Math.floor(roll.top);row<Math.min(128,Math.ceil(roll.top+roll.rows));row++){
            int pitch=127-row;float yy=roll.pitchY(pitch);boolean black=Set.of(1,3,6,8,10).contains(pitch%12);
            rect(x+1,yy,44,rh,black?0xff263138:0xffc3ccc6);rect(x+1,yy,black?29:44,Math.max(1,rh-1),black?0xff1b252b:0xffd1d9ca);
            if(pitch%12==0&&rh>=textHeight(.85f))text(noteName(pitch),x+5,yy+(rh-textHeight(.85f))/2,0xff24343b,.85f);
            hit("piano-key-"+pitch,x+1,yy,44,rh,()->auditionPitch(pitch));
        }unclip();
        clip(gx,gy,gw,gh);
        for(int row=(int)Math.floor(roll.top);row<Math.min(128,Math.ceil(roll.top+roll.rows));row++){int pitch=127-row;float yy=roll.pitchY(pitch);boolean black=Set.of(1,3,6,8,10).contains(pitch%12);rect(gx,yy,gw,rh,black?0xff273b47:0xff2d424e);line(gx,yy,gw,0xff233a48);}
        double grid=Math.max(1,Math.pow(2,Math.ceil(Math.log(Math.max(1,roll.span*6/gw))/Math.log(2))));
        for(double t=Math.ceil(roll.start/grid)*grid;t<=roll.start+roll.span;t+=grid)rect(roll.timeX(t),gy,t%16==0?2:1,gh,t%4==0?0xff182f3e:0xff263e4b);
        double playhead=pianoPlayhead()/SongProject.STEP;boolean playheadVisible=playhead>=roll.start&&playhead<roll.start+roll.span;float playheadX=roll.timeX(playhead);
        hit("roll-grid",gx,gy,gw,gh,()->{
            if(totalNotes()>=SongProject.MAX_EVENTS){status=tr("status.note_limit");return;}
            int step=Mth.clamp((int)Math.floor(roll.timeAt(mouseX)),0,(int)PianoView.MAX_TIME-1),pitch=roll.pitchAt(mouseY);
            remember();stopPreview();Note n=BlueprintAudio.drawnNote(song.channels.get(channel),step,pitch,SongProject.MAX_SONG_PPQ-step*SongProject.STEP,2);notes().add(n);song.growPattern(pattern);noteSelection.clear();chooseNote(n.id);changed();
            drag=new Drag("note",mouseX,mouseY);drag.anchor=roll.timeAt(mouseX);drag.id=n.id;drag.start=n.start;drag.pitch=n.pitch;drag.duration=n.duration;drag.committed=true;drag.originals.put(n.id,new int[]{n.start,n.pitch,n.duration});audition(n);
        });
        for(Note n:notes()){
            float xx=roll.timeX(n.start/(double)SongProject.STEP),end=roll.timeX((n.start+n.duration)/(double)SongProject.STEP),yy=roll.pitchY(n.pitch),nw=end-xx;
            if(yy+rh<=gy||yy>=gy+gh||end<=gx||xx>=gx+gw)continue;
            rect(xx+1,yy+1,Math.max(1,nw-2),Math.max(1,rh-2),0xffb9cf9d);if(noteSelection.contains(n.id))border(xx+1,yy+1,Math.max(1,nw-2),Math.max(1,rh-2),0xffedf5cc);
            if(nw>textWidth(noteName(n.pitch),.85f)+8&&rh>=textHeight(.85f)+2)text(noteName(n.pitch),xx+4,yy+(rh-textHeight(.85f))/2,0xff263922,.85f);
            hit("note-"+n.id,xx,yy,Math.max(2,nw),rh,()->{stopPreview();chooseNote(n.id);drag=new Drag("note",mouseX,mouseY);drag.anchor=roll.timeAt(mouseX);drag.id=n.id;drag.start=n.start;drag.pitch=n.pitch;drag.duration=n.duration;drag.resize=end<=gx+gw&&mouseX>end-Math.min(8,nw/3);drag.before=ProjectStore.encode(song);audition(n);for(Note old:selectedNotes())drag.originals.put(old.id,new int[]{old.start,old.pitch,old.duration});},()->edit(()->{notes().remove(n);noteSelection.remove(n.id);}),null);
        }
        if(drag!=null&&drag.type.equals("box-notes"))drawSelectionBox();if(playheadVisible)rect(playheadX,gy,2,gh,GREEN);unclip();
        clip(gx,y+63,gw,18);double label=Math.max(4,Math.pow(2,Math.ceil(Math.log(roll.span*65/gw)/Math.log(2))));for(double step=Math.ceil(roll.start/label)*label;step<roll.start+roll.span;step+=label)text(((int)step/16+1)+":"+((int)step%16/4+1),roll.timeX(step)+4,y+68,MUTED,.75f);
        hit("note-seek-ruler",gx,y+63,gw,18,this::beginRollSeek);tooltips.put("note-seek-ruler",tr("transport.seek_hint"));
        if(playheadVisible){for(int n=0;n<6;n++)rect(playheadX-5+n,y+73+n,11-2*n,1,GREEN);hit("note-seek-playhead",Math.max(gx,playheadX-7),y+63,Math.min(14,gx+gw-Math.max(gx,playheadX-7)),18,this::beginRollSeek);tooltips.put("note-seek-playhead",tr("transport.seek_hint"));}unclip();
        float velocityTop=gy+gh+6,velocityBottom=velocityTop+32;rect(gx,velocityTop,gw,32,0xff2c424b);clip(gx,velocityTop,gw,32);
        for(Note n:notes()){float xx=roll.timeX(n.start/(double)SongProject.STEP);if(xx<gx||xx>=gx+gw)continue;float vh=n.velocity/127f*28;rect(xx+1,velocityBottom-vh,Math.max(2,Math.min(20,cw-3)),vh,noteSelection.contains(n.id)?0xffd7e5b7:0xff8faa79);hit("velocity-"+n.id,xx,velocityTop,Math.max(5,Math.min(22,cw-2)),32,()->{chooseNote(n.id);drag=new Drag("velocity",mouseX,mouseY);drag.id=n.id;drag.top=velocityBottom;drag.before=ProjectStore.encode(song);});}if(playheadVisible)rect(playheadX,velocityTop,2,32,GREEN);unclip();
        drawRollScroll(false,gx,roll.horizontalY(),gw,16);drawRollScroll(true,roll.verticalX(),gy,16,gh);
        rect(x+1,y+h-64,w-2,63,0xff40515a);var selected=note();
        if(selected!=null){
            text(tr("piano.velocity"),x+10,y+h-50,MUTED,.85f);input("velocity",Integer.toString(selected.velocity),x+77,y+h-58,51,25,value->{int v=Integer.parseInt(value);if(v<1||v>127)throw new StudioException("error.velocity");var group=selectedNotes();edit(()->group.forEach(n->n.velocity=v));});
            text(tr("piano.length"),x+145,y+h-50,MUTED,.85f);input("duration",Integer.toString(selected.duration/SongProject.STEP),x+201,y+h-58,52,25,value->{int d=Integer.parseInt(value);var group=selectedNotes();if(d<1||group.stream().anyMatch(n->(long)d*SongProject.STEP>SongProject.MAX_SONG_PPQ-n.start))throw new StudioException("error.group_duration");edit(()->{group.forEach(n->n.duration=d*SongProject.STEP);song.growPattern(pattern);});});
            button("delete-note",tr("action.delete"),x+273,y+h-58,67,25,false,()->{notesContext=true;deleteSelection();});
        }
        button("copy-notes",tr("action.copy"),x+w-248,y+h-58,65,25,false,()->{notesContext=true;copySelection(false);});button("cut-notes",tr("action.cut"),x+w-176,y+h-58,59,25,false,()->{notesContext=true;copySelection(true);});button("paste-notes",tr("action.paste"),x+w-110,y+h-58,65,25,false,()->{notesContext=true;pasteSelection();});
        text(fit(tr("piano.navigation_hint"),(int)w-24,.8f),x+10,y+h-23,MUTED,.8f);
        drawPianoEdges();
    }
    private void togglePianoPicker(String kind){
        if(pianoPicker.equals(kind)){closePianoPicker();return;}resetFocus();pianoPicker=kind;pianoPickerQuery="";pianoPickerScroll=pianoPickerIndex=0;notesContext=true;
        focus="piano-picker-search";fieldText="";selectAll=false;fieldCommit=value->{pianoPickerQuery=value;pianoPickerScroll=pianoPickerIndex=0;};
        var choices=pianoChoices();String current=kind.equals("pattern")?song.patterns.get(pattern).id:song.channels.get(channel).id;
        for(int i=0;i<choices.size();i++)if(choices.get(i).id.equals(current)){pianoPickerIndex=i;pianoPickerScroll=Math.max(0,i-4);break;}
    }
    private void closePianoPicker(){if(!pianoPicker.isEmpty())hits.removeIf(h->h.id.startsWith("piano-pick-")||h.id.startsWith("piano-picker-"));pianoPicker="";if(drag!=null&&drag.type.equals("piano-picker-scroll"))drag=null;if(focus.equals("piano-picker-search")){focus="";fieldCommit=null;selectAll=false;}}
    private List<PianoChoice> pianoChoices(){
        List<PianoChoice> result=new ArrayList<>();String query=pianoPickerQuery.strip().toLowerCase(Locale.ROOT);
        if(pianoPicker.equals("pattern")){for(var p:song.patterns){String name=patternName(p);result.add(new PianoChoice(p.id,name,"pattern",p.color,p.notes.stream().mapToInt(List::size).sum(),name));}}
        else{for(var c:song.channels){String name=channelName(c);int index=song.channels.indexOf(c);result.add(new PianoChoice(c.id,name,c.sampleId.isEmpty()?"instrument":"sampler",c.color,song.patterns.get(pattern).notes.get(index).size(),name+" "+c.sound));}
            for(var c:song.audioSources){String name=channelName(c);result.add(new PianoChoice(c.id,name,"audio",c.color,0,name+" "+c.sound));}}
        return result.stream().filter(c->c.search.toLowerCase(Locale.ROOT).contains(query)).toList();
    }
    private void choosePianoEntry(PianoChoice choice){
        closePianoPicker();selectedAudio="";
        if(choice.kind.equals("pattern")){for(int i=0;i<song.patterns.size();i++)if(song.patterns.get(i).id.equals(choice.id)){if(i!=pattern){stopPreview();selectPattern(i);if(patternMode)cursor=TimelineSnap.position(cursor,snap,song.patterns.get(pattern).length);syncSelection();}break;}}
        else if(choice.kind.equals("audio")){
            int existing=-1;for(int i=0;i<song.channels.size();i++)if(song.channels.get(i).originAudioId.equals(choice.id)){existing=i;break;}
            try{if(existing>=0)channel=existing;else edit(()->channel=song.instrumentFromAudio(choice.id));}catch(IllegalArgumentException ex){status=StudioText.error(ex);return;}
            selectedAudio="";syncSelection();status=tr("piano.audio_instrument_ready",choice.name);
        }else{for(int i=0;i<song.channels.size();i++)if(song.channels.get(i).id.equals(choice.id)){channel=i;break;}syncSelection();}
        notesContext=true;
    }
    private void drawPianoPicker(){
        var choices=pianoChoices();int visible=Math.min(10,Math.max(1,choices.size()));pianoPickerScroll=Mth.clamp(pianoPickerScroll,0,Math.max(0,choices.size()-visible));pianoPickerIndex=Mth.clamp(pianoPickerIndex,0,Math.max(0,choices.size()-1));
        pickerW=420;pickerH=92+visible*30;pickerX=Mth.clamp(pianoPicker.equals("pattern")?roll.x+roll.width-310:roll.x+9,6,W-pickerW-6);pickerY=Math.min(roll.y+(pianoPicker.equals("pattern")?28:59),806-pickerH-6);
        hit("piano-picker-dismiss",0,0,W,H,this::closePianoPicker);rect(pickerX+4,pickerY+5,pickerW,pickerH,0x9909161f);rect(pickerX,pickerY,pickerW,pickerH,0xff354852);border(pickerX,pickerY,pickerW,pickerH,0xff91a8aa);
        text(tr(pianoPicker.equals("pattern")?"piano.pick_pattern":"piano.pick_source",choices.size()),pickerX+10,pickerY+9,TEXT,.85f);
        input("piano-picker-search",pianoPickerQuery,pickerX+8,pickerY+28,pickerW-16,28,value->{pianoPickerQuery=value;pianoPickerScroll=pianoPickerIndex=0;});
        if(pianoPickerQuery.isEmpty()&&(!focus.equals("piano-picker-search")||fieldText.isEmpty()))text(tr("piano.picker_search"),pickerX+14,pickerY+37,MUTED,.8f);
        String selected=pianoPicker.equals("pattern")?song.patterns.get(pattern).id:song.channels.get(channel).id;
        for(int row=0;row<visible;row++){int index=pianoPickerScroll+row;float yy=pickerY+62+row*30;if(index>=choices.size()){text(tr("piano.picker_empty"),pickerX+14,yy+8,MUTED,.85f);continue;}var c=choices.get(index);boolean current=c.id.equals(selected),hover=mouseX>=pickerX+7&&mouseX<pickerX+pickerW-18&&mouseY>=yy&&mouseY<yy+29;
            rect(pickerX+7,yy,pickerW-26,29,index==pianoPickerIndex||hover?0xff587278:row%2==0?0xff3f545e:0xff3b4e58);rect(pickerX+9,yy+4,3,21,0xff000000|c.color);text((current?"• ":"  ")+fit(c.name,238),pickerX+16,yy+8,current?GREEN:TEXT);
            String kind=switch(c.kind){case "instrument"->tr("piano.kind_instrument");case "sampler"->tr("piano.kind_sampler");case "audio"->tr("piano.kind_audio");default->tr("piano.kind_pattern");};text(fit(kind,124,.75f),pickerX+276,yy+9,MUTED,.75f);
            hit("piano-pick-"+c.id,pickerX+7,yy,pickerW-26,29,()->choosePianoEntry(c));tooltips.put("piano-pick-"+c.id,c.name+" · "+(c.kind.equals("audio")?tr("piano.audio_to_instrument_hint"):tr("piano.source_note_count",c.count)));
        }
        if(choices.size()>visible){float y=pickerY+62,height=visible*30;double size=PlaylistView.thumb(height,visible,choices.size()),at=pianoPickerScroll/(double)(choices.size()-visible)*(height-size);rect(pickerX+pickerW-15,y,8,height,0xff243945);rect(pickerX+pickerW-15,y+(float)at,8,(float)size,0xff8ca5ab);
            hit("piano-picker-scroll",pickerX+pickerW-18,y,14,height,()->{drag=new Drag("piano-picker-scroll",mouseX,mouseY);drag.left=y;drag.top=height;drag.duration=visible;drag.limit=choices.size();drag.anchor=mouseY>=y+at&&mouseY<=y+at+size?mouseY-y-at:size/2;updatePianoPickerScroll();});}
        text(tr("piano.picker_keys"),pickerX+10,pickerY+pickerH-19,MUTED,.75f);
    }
    private void updatePianoPickerScroll(){double size=PlaylistView.thumb(drag.top,drag.duration,drag.limit);pianoPickerScroll=(int)Math.round(Mth.clamp((mouseY-drag.left-drag.anchor)/Math.max(1,drag.top-size),0,1)*(drag.limit-drag.duration));}
    private void movePianoChoice(int delta){var choices=pianoChoices();pianoPickerIndex=Mth.clamp(pianoPickerIndex+delta,0,Math.max(0,choices.size()-1));if(pianoPickerIndex<pianoPickerScroll)pianoPickerScroll=pianoPickerIndex;if(pianoPickerIndex>=pianoPickerScroll+10)pianoPickerScroll=pianoPickerIndex-9;}
    private void auditionPitch(int pitch){notesContext=true;Note n=BlueprintAudio.drawnNote(song.channels.get(channel),0,pitch,song.patterns.get(pattern).length,4);audition(n);}
    private void beginRollSeek(){boolean resume=transportActive;beginTransportSeek();notesContext=true;drag=new Drag("roll-seek",mouseX,mouseY);drag.resume=resume;updateRollSeek();}
    private void updateRollSeek(){
        notePastePpq=TimelineSnap.position(roll.timeAt(Math.max(roll.gridX(),Math.min(roll.gridX()+roll.gridWidth(),mouseX)))*SongProject.STEP,snap,SongProject.MAX_SONG_PPQ);
        if(patternMode)cursor=notePastePpq;
        else{var at=PianoTimeline.seek(song,pattern,notePastePpq,cursor,selectedClip);if(at.isPresent())cursor=at.getAsDouble();else{patternMode=true;cursor=notePastePpq;if(transportActive){playbackOrigin=cursor;playbackNoteOrigin=notePastePpq;}}}
        status=tr("status.insert_position",notePastePpq/SongProject.STEP+1);
    }
    private void drawPianoEdges(){
        if(roll.maximized)return;float x=roll.x,y=roll.y,w=roll.width,h=roll.height;
        hit("piano-edge-left",x,y+4,4,h-8,()->beginPianoResize(1));hit("piano-edge-right",x+w-4,y+4,4,h-8,()->beginPianoResize(2));hit("piano-edge-top",x+4,y,w-8,4,()->beginPianoResize(4));hit("piano-edge-bottom",x+4,y+h-4,w-8,4,()->beginPianoResize(8));
        hit("piano-corner-tl",x,y,8,8,()->beginPianoResize(5));hit("piano-corner-tr",x+w-8,y,8,8,()->beginPianoResize(6));hit("piano-corner-bl",x,y+h-8,8,8,()->beginPianoResize(9));hit("piano-corner-br",x+w-8,y+h-8,8,8,()->beginPianoResize(10));rect(x+w-10,y+h-7,6,1,0xffa2b4bb);rect(x+w-7,y+h-10,1,6,0xffa2b4bb);
    }
    private void beginPianoResize(int edges){drag=new Drag("piano-resize",mouseX,mouseY);drag.edge=edges;drag.left=roll.x;drag.top=roll.y;drag.extent=roll.width;drag.original=roll.height;}
    private void drawRollScroll(boolean vertical,float x,float y,float w,float h){
        String id=vertical?"roll-scroll-v":"roll-scroll-h";double total=vertical?128:drag!=null&&(drag.type.equals("roll-scroll-h")||drag.type.equals("roll-range-h"))?Math.max(roll.start+roll.span,drag.extent):roll.extent(rollLength()),visible=vertical?roll.rows:roll.span,position=vertical?roll.top:roll.start,length=vertical?h:w;
        double size=PlaylistView.thumb(length,visible,total),at=(total<=visible?0:position/(total-visible))*(length-size);rect(x,y,w,h,0xff233842);if(vertical)rect(x+2,y+(float)at,w-4,(float)size,0xff718991);else rect(x+(float)at,y+2,(float)size,h-4,0xff718991);
        hit(id,x,y,w,h,()->{drag=new Drag(vertical?"roll-scroll-v":"roll-scroll-h",mouseX,mouseY);drag.left=vertical?y:x;drag.top=(float)length;drag.extent=total;double mouse=vertical?mouseY:mouseX;drag.anchor=mouse>=drag.left+at&&mouse<=drag.left+at+size?mouse-drag.left-at:size/2;updateRollScroll();});
        if(vertical){rect(x+2,y+(float)at, w-4,3,0xffb4c5c9);rect(x+2,y+(float)(at+size)-3,w-4,3,0xffb4c5c9);}else{rect(x+(float)at,y+2,3,h-4,0xffb4c5c9);rect(x+(float)(at+size)-3,y+2,3,h-4,0xffb4c5c9);}
        for(int side=0;side<2;side++){int edge=side;float edgeAt=(float)(at+(side==0?0:size-4));hit(id+(side==0?"-start":"-end"),vertical?x:x+edgeAt,vertical?y+edgeAt:y,vertical?w:4,vertical?4:h,()->{drag=new Drag(vertical?"roll-range-v":"roll-range-h",mouseX,mouseY);drag.edge=edge;drag.top=(float)length;drag.extent=total;drag.anchor=position;drag.original=position+visible;});}tooltips.put(id,tr(vertical?"piano.vertical_hint":"piano.horizontal_hint"));
    }
    private void updateRollScroll(){boolean vertical=drag.type.endsWith("v");double visible=vertical?roll.rows:roll.span,size=PlaylistView.thumb(drag.top,visible,drag.extent),mouse=vertical?mouseY:mouseX;double position=Mth.clamp((mouse-drag.left-drag.anchor)/Math.max(1,drag.top-size),0,1)*(drag.extent-visible);if(vertical)roll.top=position;else roll.start=position;roll.clamp(rollLength());}
    private void drawStatus(){rect(0,806,W,24,0xff3b4951);line(0,806,W,0xff627079);rect(10,815,5,5,GREEN);text(fit(status,1050),23,814,MUTED,.8f);text("LDLib2 / "+MusicStudio.VERSION,1152,815,0xff90a5ad,.75f);}

    public void openSounds(boolean noteBlocks){resetFocus();stopPreview();windows.get("sounds").folded=false;modal="sounds";noteFilter=noteBlocks;query="";soundScroll=0;soundSelected=0;SoundCatalog.INSTANCE.scan();refreshSearch();focus="search";fieldText="";fieldCommit=null;selectAll=false;}
    private void refreshSearch(){results=SoundCatalog.INSTANCE.search(query,noteFilter);soundScroll=Mth.clamp(soundScroll,0,Math.max(0,results.size()-15));soundSelected=Mth.clamp(soundSelected,0,Math.max(0,results.size()-1));lastCatalogRevision=SoundCatalog.INSTANCE.revision();}
    private void bind(SoundCatalog.Entry e){
        if(!e.playable()){status=tr("status.sound_missing",e.id());return;}
        edit(()->{Channel ch=song.channels.get(channel);ch.sound=e.id().toString();ch.instrument=e.instrument();ch.tunable=e.tunable();ch.baseMidi=66;ch.sampleId="";ch.renderedAudio="";});
        status=tr("status.sound_bound",channelName(song.channels.get(channel)),e.id());
        auditionEntry(e);modal="";focus="";fieldCommit=null;
    }
    private void auditionEntry(SoundCatalog.Entry e){stopPreview();audio.trigger(e.id().toString(),66,66,false,100,.7f,2*SongProject.PPQ,song.bpm);status=tr("status.audition",e.id());}
    private void audition(Note n){Channel c=song.channels.get(channel);if(!c.sampleId.isEmpty()&&(c.renderedAudio.isEmpty()||mc.getSoundManager().getSoundEvent(net.minecraft.resources.ResourceLocation.parse(c.sound))==null)){withPrepared(s->audition(n),true);return;}audio.trigger(c.sound,n.pitch,c.baseMidi,c.tunable,n.velocity,c.volume,n.duration,song.bpm);}
    private void drawSoundPicker(){
        var bounds=frame("sounds",184,72,912,684);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();if(!window(x,y,w,h,tr("sounds.title"),()->{modal="";focus="";},null))return;
        button("filter-note",tr("browser.note_blocks"),x+16,y+41,127,28,noteFilter,()->{noteFilter=true;soundScroll=0;refreshSearch();});
        button("filter-all",tr("sounds.all"),x+151,y+41,155,28,!noteFilter,()->{noteFilter=false;soundScroll=0;refreshSearch();});
        button("rescan",tr("sounds.rescan"),x+w-101,y+41,84,28,false,()->{SoundCatalog.INSTANCE.scan();refreshSearch();});
        input("search",query,x+16,y+80,w-32,30,s->{query=s;refreshSearch();});
        text(tr("sounds.search_hint"),x+25,y+119,MUTED,.8f);
        rect(x+16,y+140,w-32,24,0xff364750);text(tr("sounds.event_header"),x+26,y+149,MUTED,.8f);text(tr("sounds.source_header"),x+660,y+149,MUTED,.8f);
        for(int row=0;row<15;row++){
            int index=soundScroll+row;if(index>=results.size())break;var e=results.get(index);float yy=y+165+row*29;
            rect(x+16,yy,w-32,28,index==soundSelected?0xff526b70:row%2==0?0xff40535e:0xff3c4f59);
            text(fit(e.label(),620),x+26,yy+10,e.playable()?TEXT:0xffd8a48d,.9f);
            text(e.registered()?tr("sounds.registry"):tr("sounds.pack_only"),x+663,yy+11,MUTED,.7f);text(e.playable()?tr("sounds.ready"):tr("sounds.missing"),x+784,yy+11,e.playable()?GREEN:0xffd5a18d,.7f);
            hit("sound-row-"+index,x+16,yy,w-32,28,()->{soundSelected=index;auditionEntry(e);},null,()->bind(e));
        }
        int total=results.size();text(tr("sounds.page",total==0?0:soundScroll+1,Math.min(soundScroll+15,total),total),x+21,y+612,MUTED,.85f);
        button("sound-page-prev","<",x+630,y+604,27,25,false,()->soundScroll=Math.max(0,soundScroll-15));button("sound-page-next",">",x+662,y+604,27,25,false,()->soundScroll=Math.min(Math.max(0,results.size()-15),soundScroll+15));
        line(x+1,y+641,w-2,0xff718087);text(tr("sounds.hint",channelName(song.channels.get(channel))),x+20,y+657,MUTED,.8f);
        button("bind-sound",tr("sounds.bind"),x+w-124,y+649,105,26,true,()->{if(!results.isEmpty())bind(results.get(soundSelected));});
    }
    private void openProjects(){resetFocus();stopPreview();windows.get("projects").folded=false;modal="projects";fileScroll=0;files=List.of();IO.execute(()->{try{var result=store.list();mc.execute(()->files=result);}catch(Exception ex){report(ex);}});}
    private void drawProjectPicker(){
        var bounds=frame("projects",330,160,620,476);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();if(!window(x,y,w,h,tr("files.title"),()->{modal="";focus="";},null))return;
        text(fit(projectFile==null?tr("files.unsaved"):projectFile.toString(),570),x+20,y+48,MUTED,.85f);
        text(fit(tr("files.folder_hint"),570,.8f),x+20,y+64,MUTED,.8f);
        button("file-save",tr("action.save"),x+20,y+79,178,32,true,()->{save(false);modal="";});button("file-save-as",tr("files.save_as"),x+211,y+79,178,32,false,this::saveAs);button("file-open-native",tr("files.open_system"),x+402,y+79,198,32,false,this::openNativeProject);
        text(tr("files.recent"),x+20,y+130,TEXT);
        for(int row=0;row<7;row++){int index=fileScroll+row;if(index>=files.size())break;String f=files.get(index);float yy=y+157+row*29;rect(x+20,yy,w-40,28,f.equals(fileName)?0xff526b70:0xff3e515b);text(fit(f,540),x+30,yy+7,TEXT,.9f);hit("file-"+f,x+20,yy,w-40,28,()->fileName=f,null,()->load(f));}
        button("files-prev","<",x+20,y+373,30,25,false,()->fileScroll=Math.max(0,fileScroll-7));button("files-next",">",x+56,y+373,30,25,false,()->fileScroll=Math.min(Math.max(0,files.size()-7),fileScroll+7));text(tr("files.count",files.size()),x+101,y+380,MUTED,.8f);
        button("load-project",tr("files.load"),x+20,y+427,180,31,false,()->load(fileName));button("new-project",tr("files.new"),x+216,y+427,180,31,false,()->edit(()->{song=SongProject.demo();song.patterns.forEach(p->p.notes.forEach(List::clear));song.clips.clear();song.title="Untitled";projectFile=null;StudioClient.projectFile=null;viewport.start=0;viewport.vertical=0;normalize();modal="";}));
    }
    private void drawExport(){
        var bounds=frame("export",417,82,446,720);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();if(!window(x,y,w,h,tr("export.title"),()->{modal="";focus="";},null))return;
        text(tr("export.backend",tr(song.loop?"blueprint.lever":"blueprint.button")),x+22,y+49,TEXT);text(fit(rangeSummary(),(int)w-44,.8f),x+22,y+66,MUTED,.8f);text(tr("export.max_size"),x+22,y+83,MUTED);
        String[] names={tr("export.x"),tr("export.z"),tr("export.y")};int[] values={song.maxX,song.maxZ,song.maxY};
        for(int i=0;i<3;i++){final int axis=i;text(names[i],x+22+i*135,y+106,MUTED,.85f);input("size-"+i,Integer.toString(values[i]),x+22+i*135,y+125,120,31,s->{int n=Integer.parseInt(s);if(n<1||n>256)throw new StudioException("error.dimensions");edit(()->{if(axis==0)song.maxX=n;else if(axis==1)song.maxZ=n;else song.maxY=n;});});}
        button("export-loop",(song.loop?"[x] ":"[ ] ")+tr("export.loop"),x+22,y+166,w-44,30,song.loop,()->edit(()->song.loop=!song.loop));
        text(tr("export.loop_hint"),x+30,y+201,MUTED,.85f);
        button("export-command",(song.commandSamples?"[x] ":"[ ] ")+tr("export.command"),x+22,y+223,w-44,30,song.commandSamples,()->edit(()->song.commandSamples=!song.commandSamples));
        text(tr("export.sample_hint"),x+30,y+258,MUTED,.85f);
        button("export-note-hook",(song.noteBlockPitchHook?"[x] ":"[ ] ")+tr("export.note_hook"),x+22,y+280,w-44,30,song.noteBlockPitchHook,()->edit(()->song.noteBlockPitchHook=!song.noteBlockPitchHook));
        text(fit(tr("export.note_hook_hint"),(int)w-60,.8f),x+30,y+316,MUTED,.8f);
        tooltips.put("export-note-hook",tr("export.note_hook_help"));
        rect(x+22,y+352,w-44,134,0xff2d424f);for(int i=0;i<33;i++)rect(x+22+i*12,y+352,1,134,0xff344c59);for(int i=0;i<11;i++)line(x+22,y+352+i*12,w-44,0xff344c59);
        if(exportRevision!=revision){exportRevision=revision;exportPreview=null;exportError="";try{exportPreview=dev.musicstudio.blueprint.MusicBlueprint.preview(song);}catch(Exception ex){exportError=StudioText.error(ex);}}
        if(exportPreview!=null){var b=exportPreview;float sx=Math.min((w-64)/b.x(),100f/b.z());
            for(var part:b.parts()){var c=part.pos();int color=switch(part.block()){case "lever","stone_button"->GREEN;case "redstone_wire"->0xffc56f67;case "repeater","comparator"->0xffe5cb8c;case "note_block"->0xffc49a72;case "command_block"->0xffaa94c9;default->0xff627984;};rect(x+30+c.x()*sx,y+363+c.z()*sx,Math.max(1,sx-1),Math.max(1,sx-1),color);}
            text(tr("export.actual",b.x(),b.z(),b.y(),b.parts().size()),x+22,y+500,TEXT,.85f);
            text(tr("export.timing",String.format(Locale.ROOT,"%.1f",b.maxErrorMs()),b.period()),x+22,y+524,MUTED,.85f);
            text(fit(tr("export.native_detail",b.shortened(),b.pitchLimited()),(int)w-44,.85f),x+22,y+548,0xffe5cb8c,.85f);
        }else wrapped(exportError,x+30,y+371,w-60,0xffffc59c);
        text(fit(tr("export.native_warning"),(int)w-44,.85f),x+22,y+576,0xffe5cb8c,.85f);
        wrapped(tr(song.loop?"export.reset_hint":"export.reset_hint_once"),x+22,y+600,w-44,MUTED);
        button("save-export",exportRequest==null?tr("export.create"):tr("export.pending"),x+211,y+675,214,31,true,this::exportBlueprint);
    }
    private void exportBlueprint(){
        resetFocus();if(exportRequest!=null||mediaBusy)return;
        try{if(mc.player==null||!mc.player.isCreative())throw new StudioException("error.blueprint_creative");dev.musicstudio.blueprint.MusicBlueprint.preview(song);stopPreview();withPrepared(prepared->{SongProject snapshot=ProjectStore.copy(prepared);long rev=revision;var provider=ClientMedia.captureBlueprint(snapshot);
            mediaTask(()->ClientMedia.prepareBlueprint(snapshot,provider),building->{if(rev!=revision){status=tr("status.load_changed");return;}mediaBusy=true;
                boolean missing=building.blueprintAudio.values().stream().anyMatch(hash->mc.getSoundManager().getSoundEvent(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("musicstudio","sample/"+hash))==null);
                var ready=missing?ClientMedia.refreshPack():CompletableFuture.<Void>completedFuture(null);
                ready.thenCompose(v->ClientMediaNetwork.uploadSamples(building)).whenComplete((v,e)->mc.execute(()->{mediaBusy=false;if(e!=null){mediaFailure(e);return;}if(closed)return;if(rev!=revision){status=tr("status.load_changed");return;}try{exportRequest=BlueprintClient.export(building);exportStarted=System.currentTimeMillis();status=tr("export.pending");}catch(Exception ex){mediaFailure(ex);}}));
            });
        });}catch(Exception ex){mediaFailure(ex);}
    }
    void smokeExport(SongProject project){song=ProjectStore.copy(project);loadedDraft=true;normalize();windows.get("export").folded=false;modal="export";revision++;}
    void smokeCreateBlueprint(){exportBlueprint();}
    void smokeMediaHome(SongProject project){smokeExport(project);windows.get("media").folded=false;modal="media";cursor=0;}
    void smokeMediaDialog(){windows.get("media").folded=false;modal="media";}
    boolean smokeMediaIdle(){return !mediaBusy;}
    SongProject smokeMediaSong(){return ProjectStore.copy(song);}
    void smokeProjectSave(Path path,boolean saveAs){saveProjectFile(path,saveAs);}
    void smokeProjectOpen(Path path){openProjectFile(path);}
    Path smokeProjectFile(){return projectFile;}
    void smokeProjectHistory(){Path folder=song.projectDirectory;int bpm=song.bpm;edit(()->song.bpm=bpm+1);undo();if(song.bpm!=bpm||!Objects.equals(folder,song.projectDirectory))throw new IllegalStateException("Project folder lost during undo");redo();if(song.bpm!=bpm+1||!Objects.equals(folder,song.projectDirectory))throw new IllegalStateException("Project folder lost during redo");}
    void smokeMediaStems(Path path){exportMidi(path);}
    void smokeSamplePreview(){Hit h=hits.stream().filter(v->v.id.equals("sample-preview")).findFirst().orElseThrow();click(originX+(h.x+h.w/2)*scale,originY+(h.y+h.h/2)*scale,0);release();}
    void smokeMediaExportCurrent(){song.commandSamples=true;revision++;windows.get("export").folded=false;modal="export";}
    void smokeOpenImportedAudio(){openAudioSample(song.audioSources.size()-1);}
    void smokeSampleEdits(){
        String before=ProjectStore.encode(song);Hit fade=hits.stream().filter(h->h.id.equals("sample-fade-in")).findFirst().orElseThrow();click(originX+(fade.x+fade.w/3)*scale,originY+(fade.y+fade.h/2)*scale,0);drag(originX+(fade.x+fade.w*.5f)*scale,originY+(fade.y+fade.h/2)*scale);release();if(sampleChannel().fadeIn<=0)throw new IllegalStateException("Native fade drag failed");key(GLFW.GLFW_KEY_Z,GLFW.GLFW_MOD_CONTROL);if(!before.equals(ProjectStore.encode(song)))throw new IllegalStateException("Sample edit undo changed source assets");key(GLFW.GLFW_KEY_Y,GLFW.GLFW_MOD_CONTROL);if(sampleChannel().fadeIn<=0)throw new IllegalStateException("Sample redo failed");sampleChange(()->{var c=sampleChannel();c.trimStart=.1f;c.trimEnd=1f;c.fadeIn=.15f;c.fadeOut=.2f;c.gain=.7f;});
    }
    public void blueprintReply(dev.musicstudio.network.StudioNetwork.Reply reply){if(exportRequest!=null&&exportRequest.equals(reply.request())){exportRequest=null;status=tr(reply.key(),reply.args().toArray());if(reply.ok())modal="";}}
    private void openMedia(){resetFocus();stopPreview();windows.get("media").folded=false;modal="media";}
    private <T> void mediaTask(java.util.concurrent.Callable<T> task,Consumer<T> complete){
        if(mediaBusy)return;mediaBusy=true;mediaMessage=tr("media.processing");status=mediaMessage;
        ClientMedia.IO.execute(()->{try{T result=task.call();mc.execute(()->{mediaBusy=false;if(!closed)try{complete.accept(result);}catch(Exception ex){mediaFailure(ex);}});}catch(Exception ex){mc.execute(()->mediaFailure(ex));}});
    }
    private void mediaFailure(Throwable error){while(error instanceof java.util.concurrent.CompletionException&&error.getCause()!=null)error=error.getCause();if(playbackPending)stopPreview();mediaBusy=false;mediaMessage=StudioText.error(error);status=mediaMessage;MusicStudio.LOGGER.warn("Media operation failed",error);}
    private void withPrepared(Consumer<SongProject> complete){withPrepared(complete,false);}
    private void withPrepared(Consumer<SongProject> complete,boolean includeSelected){
        Channel selectedSource=includeSelected&&modal.equals("sample")?sampleChannel():song.channels.get(channel);String selected=selectedSource.id;boolean wasMuted=selectedSource.muted;
        if(mediaBusy)return;if(song.voices().stream().noneMatch(c->(!c.muted||includeSelected&&c.id.equals(selected))&&!c.sampleId.isEmpty())){complete.accept(song);return;}
        SongProject snapshot=ProjectStore.copy(song);if(includeSelected)snapshot.voices().stream().filter(c->c.id.equals(selected)).forEach(c->c.muted=false);long rev=revision;
        mediaTask(()->ClientMedia.prepare(snapshot),prepared->{if(includeSelected)prepared.voices().stream().filter(c->c.id.equals(selected)).forEach(c->c.muted=wasMuted);if(revision!=rev){mediaMessage=tr("status.load_changed");return;}song=prepared;StudioClient.project=song;dirty=true;mediaBusy=true;
            boolean missing=prepared.voices().stream().anyMatch(c->(!c.muted||includeSelected&&c.id.equals(selected))&&!c.sampleId.isEmpty()&&mc.getSoundManager().getSoundEvent(net.minecraft.resources.ResourceLocation.parse(c.sound))==null);
            var ready=missing?ClientMedia.refreshPack():java.util.concurrent.CompletableFuture.<Void>completedFuture(null);ready.whenComplete((v,e)->mc.execute(()->{mediaBusy=false;if(e!=null)mediaFailure(e);else if(!closed)complete.accept(song);}));});
    }
    private void drawMedia(){
        var bounds=frame("media",280,160,720,480);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();if(!window(x,y,w,h,tr("media.title"),()->modal="",null))return;
        text(tr("media.track_filter",song.tracks.stream().filter(c->!c.muted).count(),song.tracks.size()),x+22,y+50,TEXT);
        button("import-midi",tr("media.import_midi"),x+22,y+85,326,35,false,()->{if(mediaBusy)return;Path path=MediaDialogs.open(true);if(path!=null)importMidi(path);});
        button("export-midi",tr("media.export_midi"),x+368,y+85,326,35,false,()->{if(mediaBusy)return;Path dir=MediaDialogs.folder();if(dir!=null)exportMidi(dir);});
        String[] formats={"wav","mp3","ogg"};for(int i=0;i<3;i++){String format=formats[i];button("export-"+format,tr("media.export_format",format.toUpperCase(Locale.ROOT)),x+22+i*230,y+142,212,36,false,()->{if(mediaBusy)return;Path path=MediaDialogs.save(song.title,format);if(path!=null)exportAudio(path,format,false);});}
        button("export-disc",tr("media.export_disc"),x+22,y+200,326,36,true,()->{if(mediaBusy)return;if(mc.player==null||!mc.player.isCreative()){mediaFailure(new StudioException("error.blueprint_creative"));return;}Path path=ProjectMedia.folder(store.directory(),song).resolve("exports/disc-"+System.currentTimeMillis()+".ogg");exportAudio(path,"ogg",true);});
        button("import-sample",tr("media.import_sample"),x+368,y+200,326,36,false,()->{if(mediaBusy)return;Path path=MediaDialogs.open(false);if(path!=null)importSample(path);});
        button("edit-sample",tr("media.edit_sample"),x+22,y+259,326,36,false,this::openSample);
        button("media-project-folder",tr("media.project_folder"),x+368,y+259,326,36,false,()->{try{Path path=ProjectMedia.folder(store.directory(),song);Files.createDirectories(path);net.minecraft.Util.getPlatform().openFile(path.toFile());}catch(Exception e){mediaFailure(e);}});
        text(fit(rangeSummary(),(int)w-44,.8f),x+22,y+308,TEXT,.8f);wrapped(tr("media.midi_hint"),x+22,y+334,w-44,MUTED);wrapped(mediaBusy?tr("media.processing"):mediaMessage,x+22,y+398,w-44,0xffd9dca8);
    }
    void importMidi(Path path){if(mediaBusy)return;long rev=revision;mediaTask(()->MidiFiles.read(path),result->{if(revision!=rev){mediaMessage=tr("status.load_changed");return;}remember();stopPreview();song=result.song();channel=0;pattern=0;cursor=0;viewport.start=0;viewport.vertical=0;roll.start=0;projectFile=null;StudioClient.projectFile=null;rackScroll=0;clearSelection();normalize();changed();mediaMessage=tr("media.midi_loaded",song.channels.size(),totalNotes(),result.tempoChanges(),result.ignored());status=mediaMessage;});}
    private void exportMidi(Path directory){SongProject snapshot=ProjectStore.copy(song);Path target=directory.resolve("MIDI-"+System.currentTimeMillis());mediaTask(()->MidiFiles.exportTracks(snapshot,target),files->{mediaMessage=tr("media.midi_saved",files.size(),target);status=mediaMessage;});}
    void exportAudio(Path path,String format,boolean disc){if(mediaBusy)return;try{SongProject snapshot=ProjectStore.copy(song);AudioFiles.Provider provider=ClientMedia.captureExport(snapshot);mediaTask(()->AudioFiles.render(snapshot,provider,path,format,ClientMedia.codec()),result->{mediaMessage=tr("media.audio_saved",result.file(),String.format(Locale.ROOT,"%.3f",result.scale()));status=mediaMessage;if(disc){mediaBusy=true;ClientMedia.IO.execute(()->{try{String hash=ProjectMedia.hash(result.file());Files.createDirectories(ClientMedia.published());Files.copy(result.file(),ClientMedia.published().resolve(hash+".ogg"),StandardCopyOption.REPLACE_EXISTING);mc.execute(()->ClientMedia.refreshPack().thenCompose(v->ClientMediaNetwork.upload(hash)).whenComplete((v,e)->mc.execute(()->{if(e!=null){mediaFailure(e);return;}ClientMediaNetwork.disc(hash,snapshot.title,(int)Math.ceil(result.frames()*20.0/AudioFiles.RATE)).whenComplete((done,error)->mc.execute(()->{if(error!=null)mediaFailure(error);else{mediaBusy=false;mediaMessage=tr("media.disc_ready");status=mediaMessage;}}));})));}catch(Exception e){mc.execute(()->mediaFailure(e));}});}});}catch(Exception e){mediaFailure(e);}}
    private void chooseAudioFile(boolean instrument){if(mediaBusy)return;Path path=MediaDialogs.open(false);if(path!=null)importAudio(path,instrument,activeTrack,(int)cursor);}
    void importSample(Path path){importAudio(path,false,activeTrack,(int)cursor);}
    private void importAudio(Path path,boolean instrument,int lane,int position){
        if(mediaBusy)return;if(instrument&&song.channels.size()>=128||song.clips.size()>=4096){mediaFailure(new StudioException("error.channel_count"));return;}
        SongProject snapshot=ProjectStore.copy(song);long rev=revision;
        mediaTask(()->AudioFiles.importAsset(path,store.directory(),snapshot,ClientMedia.codec()),asset->{
            if(revision!=rev){mediaMessage=tr("status.load_changed");return;}
            edit(()->{if(song.assets.stream().noneMatch(a->a.id.equals(asset.id)))song.assets.add(asset);
                Channel c=new Channel(asset.name.substring(0,Math.min(80,asset.name.length())),"musicstudio:sample/"+asset.id,0x7d7297);c.sampleId=asset.id;c.tunable=instrument;c.volume=1;sampleSourceId=c.id;
                if(instrument){song.channels.add(c);song.patterns.forEach(p->p.notes.add(new ArrayList<>()));channel=song.channels.size()-1;rackScroll=Math.max(0,channel-11);browserTab=1;rack=true;}
                else{song.audioSources.add(c);selectedAudio=c.id;browserTab=2;int start=TimelineSnap.position(position,snap,SongProject.MAX_SONG_PPQ),length=Math.min(song.audioLength(c),SongProject.MAX_SONG_PPQ-start);if(length>0){Clip clip=Clip.audio(Mth.clamp(lane,0,song.tracks.size()-1),song.audioSources.size()-1,start,length);extendSong(start+length);song.clips.add(clip);clearSelection();chooseClip(clip.id);}}
            });mediaMessage=tr("media.sample_loaded",asset.name);status=mediaMessage;modal="";
        });
    }
    public void filesDropped(List<Path> paths,double x,double y){
        if(paths.isEmpty()||mediaBusy)return;float px=(float)(x-originX)/scale,py=(float)(y-originY)/scale;
        int lane=trackAt(py);if(lane<0)lane=activeTrack;double ppq=px>=TIME_X?(viewport.start+(px-TIME_X)/TIME_W*viewport.bars)*PlaylistView.BAR:cursor;
        Path file=paths.getFirst();String name=file.toString().toLowerCase(Locale.ROOT);if(Files.isDirectory(file)||name.endsWith(".musicstudio")||name.endsWith(".json"))openProjectFile(file);else if(name.endsWith(".mid")||name.endsWith(".midi"))importMidi(file);else importAudio(file,false,lane,TimelineSnap.position(ppq,snap,SongProject.MAX_SONG_PPQ));
    }
    private Channel sampleChannel(){return song.voices().stream().filter(c->c.id.equals(sampleSourceId)).findFirst().orElse(song.channels.get(channel));}
    private void openAudioSample(int index){sampleSourceId=song.audioSources.get(index).id;openSampleSource();}
    private void openSample(){sampleSourceId=song.channels.get(channel).id;openSampleSource();}
    private void openSampleSource(){
        resetFocus();Channel source=sampleChannel();if(source.sampleId.isEmpty()){mediaMessage=tr("media.select_sample");status=mediaMessage;windows.get("media").folded=false;modal="media";return;}
        windows.get("sample").folded=false;modal="sample";sampleWave=null;samplePeaks=null;var snapshot=ProjectStore.copy(song);String id=source.sampleId,sourceId=source.id;
        ClientMedia.IO.execute(()->{try{var wave=AudioFiles.read(ProjectMedia.wav(store.directory(),snapshot,id));float[] peaks=new float[756];for(int i=0;i<peaks.length;i++){int a=(int)((long)i*wave.frames()/peaks.length),b=(int)((long)(i+1)*wave.frames()/peaks.length);for(int f=a;f<b;f++)peaks[i]=Math.max(peaks[i],Math.max(Math.abs(wave.left()[f]),Math.abs(wave.right()[f])));}mc.execute(()->{if(!closed&&sampleSourceId.equals(sourceId)){sampleWave=wave;samplePeaks=peaks;}});}catch(Exception e){mc.execute(()->mediaFailure(e));}});
    }
    private void sampleChange(Runnable change){edit(()->{change.run();sampleChannel().renderedAudio="";});}
    private void drawSample(){
        var bounds=frame("sample",240,145,800,525);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();var c=sampleChannel();if(c.sampleId.isEmpty()){windows.get("media").folded=false;modal="media";return;}var asset=ProjectMedia.asset(song,c.sampleId);float seconds=asset.frames/(float)AudioFiles.RATE;float end=c.trimEnd==0?seconds:c.trimEnd;
        if(!window(x,y,w,h,tr("media.sample_title",asset.name),()->modal="media",null))return;text(tr("media.non_destructive"),x+22,y+47,MUTED,.85f);
        float wx=x+22,wy=y+80,ww=w-44,wh=142;rect(wx,wy,ww,wh,0xff203641);if(samplePeaks!=null){for(int i=0;i<(int)ww;i++){float peak=samplePeaks[Math.min(samplePeaks.length-1,i)];float at=i/ww*seconds;if(at>=c.trimStart&&at<=end){float envelope=c.gain;if(c.fadeIn>0)envelope*=Math.min(1,(at-c.trimStart)/c.fadeIn);if(c.fadeOut>0)envelope*=Math.min(1,(end-at)/c.fadeOut);peak=Math.min(1,peak*envelope);}rect(wx+i,wy+wh/2-peak*(wh/2-5),1,Math.max(1,peak*(wh-10)),0xff9ab4aa);}}
        float left=wx+c.trimStart/seconds*ww,right=wx+end/seconds*ww;rect(wx,wy,Math.max(0,left-wx),wh,0xaa13222d);rect(right,wy,Math.max(0,wx+ww-right),wh,0xaa13222d);border(left,wy,Math.max(1,right-left),wh,0xffc0db87);
        rect(left,wy,4,wh,0xffb9d776);rect(right-4,wy,4,wh,0xffd7c58d);hit("sample-start",left-6,wy,14,wh,()->beginSampleDrag("sample-start",wx,ww,seconds));hit("sample-end",right-8,wy,14,wh,()->beginSampleDrag("sample-end",wx,ww,seconds));
        text(String.format(Locale.ROOT,"%.3f s — %.3f s / %.3f s",c.trimStart,end,seconds),x+22,y+238,TEXT,.9f);
        String[] labels={tr("media.trim_start"),tr("media.trim_end"),tr("media.fade_in"),tr("media.fade_out"),tr("media.gain")};float[] values={c.trimStart,end,c.fadeIn,c.fadeOut,c.gain};
        for(int i=0;i<5;i++){int field=i;float xx=x+22+i*151;text(labels[i],xx,y+272,MUTED,.85f);input("sample-value-"+i,String.format(Locale.ROOT,"%.3f",values[i]),xx,y+294,133,30,value->{float n=Float.parseFloat(value);if(!Float.isFinite(n)||n<0||(field==0&&n>=end)||(field==1&&(n<=c.trimStart||n>seconds))||(field>=2&&field<4&&n>end-c.trimStart)||(field==4&&n>4))throw new StudioException("error.sample_settings");sampleChange(()->{switch(field){case 0->c.trimStart=n;case 1->c.trimEnd=n;case 2->c.fadeIn=n;case 3->c.fadeOut=n;default->c.gain=n;}});});}
        float fy=y+354;drawSampleSlider("sample-fade-in",tr("media.fade_in"),x+22,fy,235,c.fadeIn/Math.max(.001f,end-c.trimStart));drawSampleSlider("sample-fade-out",tr("media.fade_out"),x+282,fy,235,c.fadeOut/Math.max(.001f,end-c.trimStart));drawSampleSlider("sample-gain",tr("media.gain"),x+542,fy,235,c.gain/4);
        button("sample-preview",tr("media.preview"),x+22,y+445,218,33,true,()->withPrepared(prepared->{var current=sampleChannel();stopPreview();int ppq=(int)Math.ceil((end-current.trimStart)*song.bpm*SongProject.PPQ/60.0);audio.trigger(current.sound,current.baseMidi,current.baseMidi,false,127,current.volume,ppq,song.bpm);},true));
        button("sample-stop",tr("action.stop"),x+256,y+445,139,33,false,this::stop);
        if(song.audioSources.stream().anyMatch(source->source.id.equals(c.id)))button("sample-remove-audio",tr("audio.remove"),x+410,y+445,135,33,false,()->requestDeleteAudio(c.id));
        button("sample-back",tr("action.close"),x+560,y+445,218,33,false,()->modal="media");
    }
    private void beginSampleDrag(String type,float left,float width,float seconds){stopPreview();drag=new Drag(type,mouseX,mouseY);drag.left=left;drag.top=width;drag.duration=Math.round(seconds*AudioFiles.RATE);drag.before=ProjectStore.encode(song);}
    private void drawSampleSlider(String id,String label,float x,float y,float width,float ratio){text(label,x,y-10,MUTED,.85f);rect(x,y+12,width,6,0xff293c45);rect(x,y+12,width*Mth.clamp(ratio,0,1),6,0xff9abb84);rect(x+width*Mth.clamp(ratio,0,1)-3,y+7,6,16,0xffd6dfbc);hit(id,x-4,y,width+8,29,()->{beginSampleDrag(id,x,width,0);drag(mouseX*scale+originX,mouseY*scale+originY);});}
    private String channelName(Channel c){return StudioText.named(c.nameKey,c.name);}
    private float pianoRowHeight(){return roll.gridHeight()/(float)roll.rows;}
    private String patternName(Pattern p){return StudioText.named(p.nameKey,p.name);}
    private void requestDeleteChannel(int index){
        resetFocus();if(index<0||index>=song.channels.size())return;
        if(song.channels.size()==1){status=tr("error.last_channel");return;}
        stopPreview();deleteChannelId=song.channels.get(index).id;windows.get("delete-channel").folded=false;modal="delete-channel";
    }
    private void confirmDeleteChannel(){
        int index=-1;for(int i=0;i<song.channels.size();i++)if(song.channels.get(i).id.equals(deleteChannelId)){index=i;break;}
        if(index<0){status=tr("error.channel_missing");modal="";return;}
        if(song.channels.size()==1){status=tr("error.last_channel");modal="";return;}
        int removed=index;String name=channelName(song.channels.get(index));
        edit(()->{song.removeChannel(removed);if(channel>removed)channel--;else if(channel==removed)channel=Math.min(removed,song.channels.size()-1);clearSelection();selectionChannel=-1;lastPianoChannel=-1;drag=null;normalize();});
        modal="";deleteChannelId="";status=tr("status.channel_removed",name);
    }
    private void requestDeleteAudio(String id){
        resetFocus();if(song.audioSources.stream().noneMatch(c->c.id.equals(id))){status=tr("error.audio_missing");return;}
        stopPreview();drag=null;deleteAudioId=id;windows.get("delete-audio").folded=false;modal="delete-audio";
    }
    private void confirmDeleteAudio(){
        int index=-1;for(int i=0;i<song.audioSources.size();i++)if(song.audioSources.get(i).id.equals(deleteAudioId)){index=i;break;}
        if(index<0){status=tr("error.audio_missing");modal="";return;}
        int removed=index;String id=deleteAudioId,name=channelName(song.audioSources.get(index));
        edit(()->{song.removeAudioSource(removed);if(selectedAudio.equals(id))selectedAudio="";if(sampleSourceId.equals(id)){sampleSourceId="";sampleWave=null;samplePeaks=null;windows.close("sample");}clearSelection();drag=null;closePianoPicker();normalize();});
        modal="";deleteAudioId="";status=tr("status.audio_removed",name);
    }
    private void drawDeleteAudio(){
        var bounds=frame("delete-audio",345,245,590,285);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();if(!window(x,y,w,h,tr("audio.remove"),()->modal="",null))return;
        var source=song.audioSources.stream().filter(c->c.id.equals(deleteAudioId)).findFirst().orElse(null);if(source==null){text(tr("error.audio_missing"),x+22,y+52,TEXT);return;}
        int index=song.audioSources.indexOf(source);long count=song.clips.stream().filter(c->c.audio()&&c.audioSource==index).count();
        wrapped(tr("audio.remove_question",channelName(source)),x+22,y+52,w-44,TEXT);
        wrapped(tr("audio.remove_contents",count),x+22,y+107,w-44,MUTED);
        wrapped(tr("audio.remove_undo"),x+22,y+164,w-44,MUTED);
        button("cancel-remove-audio",tr("action.cancel"),x+230,y+h-47,149,31,false,()->modal="");
        button("confirm-remove-audio",tr("action.delete"),x+390,y+h-47,178,31,true,this::confirmDeleteAudio);
    }
    private void drawDeleteChannel(){
        var bounds=frame("delete-channel",345,245,590,285);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();if(!window(x,y,w,h,tr("channel.remove_title"),()->modal="",null))return;
        int index=-1;for(int i=0;i<song.channels.size();i++)if(song.channels.get(i).id.equals(deleteChannelId)){index=i;break;}
        if(index<0){text(tr("error.channel_missing"),x+20,y+54,TEXT);return;}
        int target=index,count=song.patterns.stream().mapToInt(p->p.notes.get(target).size()).sum();long clips=0;
        wrapped(tr("channel.remove_question",channelName(song.channels.get(index))),x+22,y+52,w-44,TEXT);
        wrapped(tr("channel.remove_contents",count,clips),x+22,y+107,w-44,MUTED);
        wrapped(tr("channel.remove_undo"),x+22,y+164,w-44,MUTED);
        button("cancel-remove-channel",tr("action.cancel"),x+230,y+h-47,149,31,false,()->modal="");
        button("confirm-remove-channel",tr("action.delete"),x+390,y+h-47,178,31,true,this::confirmDeleteChannel);
    }
    private void wrapped(String s,float x,float y,float maxWidth,int color){
        int width=Math.max(1,(int)(maxWidth/fontScale(1)));String remaining=s;
        while(!remaining.isEmpty()){
            String part=LDLibFonts.font().plainSubstrByWidth(remaining,width);if(part.isEmpty())part=remaining.substring(0,1);
            text(part,x,y,color);remaining=remaining.substring(part.length()).stripLeading();y+=textHeight(1)+5;
        }
    }
    private void drawTooltip(){
        Hit hover=null;for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).contains(mouseX,mouseY)){hover=hits.get(i);break;}
        String id=hover==null?"":hover.id;
        if(!id.equals(hoverId)){hoverId=id;hoverSince=System.currentTimeMillis();return;}
        if(System.currentTimeMillis()-hoverSince<450||!tooltips.containsKey(id)||drag!=null)return;
        String label=fit(tooltips.get(id),W-42);float width=textWidth(label,1)+16,height=textHeight(1)+12;
        float x=Mth.clamp(mouseX+12,8,W-width-8),y=Mth.clamp(mouseY+18,8,H-height-8);
        rect(x,y,width,height,0xff172b36);border(x,y,width,height,0xff7e9ba9);text(label,x+8,y+6,TEXT);
    }
    private int totalNotes(){return song.patterns.stream().flatMap(p->p.notes.stream()).mapToInt(List::size).sum();}
    private void newChannel(){if(song.channels.size()>=128){status=tr("status.channel_limit");return;}edit(()->{Channel c=new Channel(tr("channel.new",song.channels.size()+1),"minecraft:block.note_block.harp",0x577e87);song.channels.add(c);song.patterns.forEach(p->p.notes.add(new ArrayList<>()));channel=song.channels.size()-1;rackScroll=Math.max(0,channel-11);});}
    private void newTrack(){if(song.tracks.size()>=256){status=tr("error.track_count");return;}edit(()->{Track t=new Track("Track "+(song.tracks.size()+1),0x577e87);t.nameKey="track.default";song.tracks.add(t);activeTrack=song.tracks.size()-1;revealTrack(activeTrack);});}
    private void openTrackSettings(int index){resetFocus();activeTrack=index;channelSettingsId=song.tracks.get(index).id;windows.get("track-settings").folded=false;modal="track-settings";}
    private void drawTrackSettings(){
        Track t=song.tracks.stream().filter(v->v.id.equals(channelSettingsId)).findFirst().orElse(null);if(t==null){modal="";return;}
        var bounds=frame("track-settings",370,210,540,300);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();if(!window(x,y,w,h,tr("track.settings"),()->modal="",null))return;
        text(tr("track.name"),x+22,y+49,TEXT);input("track-name",trackName(t),x+22,y+74,w-44,32,value->{if(value.isBlank()||value.length()>80)throw new StudioException("error.channel_name");edit(()->{t.name=value;t.nameKey="";});});
        text(tr("channel.color"),x+22,y+132,TEXT);input("track-color",String.format(Locale.ROOT,"#%06X",t.color&0xffffff),x+332,y+125,184,30,value->{String v=value.strip().replaceFirst("^#","");if(!v.matches("[0-9a-fA-F]{6}"))throw new StudioException("error.channel_color");edit(()->t.color=Integer.parseInt(v,16));});
        wrapped(tr("track.independent"),x+22,y+180,w-44,MUTED);
        button("track-remove",tr("track.remove"),x+22,y+h-50,155,32,false,()->{if(song.tracks.size()==1){status=tr("error.track_count");return;}edit(()->{song.removeTrack(song.tracks.indexOf(t));normalize();clearSelection();});modal="";});button("track-done",tr("action.close"),x+350,y+h-50,166,32,true,()->{resetFocus();modal="";});
    }
    private void drawPatternSettings(){
        Pattern p=song.patterns.get(pattern);var bounds=frame("pattern-settings",370,230,540,240);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();if(!window(x,y,w,h,tr("browser.patterns"),()->modal="",null))return;
        input("pattern-name",patternName(p),x+22,y+58,w-44,32,value->{if(value.isBlank()||value.length()>80)throw new StudioException("error.title_length");edit(()->{p.name=value;p.nameKey="";});});
        text(tr("channel.color"),x+22,y+114,TEXT);input("pattern-color",String.format(Locale.ROOT,"#%06X",p.color&0xffffff),x+332,y+107,184,30,value->{String v=value.strip().replaceFirst("^#","");if(!v.matches("[0-9a-fA-F]{6}"))throw new StudioException("error.channel_color");edit(()->p.color=Integer.parseInt(v,16));});
        button("pattern-delete",tr("pattern.delete"),x+22,y+h-50,230,32,false,this::deletePattern);
        tooltips.put("pattern-delete",tr("pattern.delete_hint",song.clips.stream().filter(c->!c.audio()&&c.pattern==pattern).count()));
        button("pattern-settings-done",tr("action.close"),x+350,y+h-50,166,32,true,()->{resetFocus();modal="";});
    }
    private void drawAddSource(){
        var bounds=frame("add-source",380,230,520,270);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();if(!window(x,y,w,h,tr("source.add"),()->modal="",null))return;
        wrapped(tr("source.add_hint"),x+22,y+52,w-44,MUTED);
        button("add-event-source",tr("source.event"),x+22,y+112,w-44,40,false,()->{newChannel();openSounds(false);});
        button("add-sampler-source",tr("source.sampler"),x+22,y+166,w-44,40,false,()->chooseAudioFile(true));
    }
    private void openChannelSettings(int index){resetFocus();channel=index;channelSettingsId=song.channels.get(index).id;windows.get("channel-settings").folded=false;modal="channel-settings";}
    private void renameChannel(String id,String name){if(name.isBlank()||name.length()>80)throw new StudioException("error.channel_name");edit(()->{var c=song.channels.stream().filter(v->v.id.equals(id)).findFirst().orElseThrow();c.name=name;c.nameKey="";});}
    private void colorChannel(String id,int color){edit(()->song.channels.stream().filter(c->c.id.equals(id)).findFirst().orElseThrow().color=color&0xffffff);}
    private void drawChannelSettings(){
        var c=song.channels.stream().filter(v->v.id.equals(channelSettingsId)).findFirst().orElse(null);if(c==null){modal="";return;}
        var bounds=frame("channel-settings",370,170,540,430);float x=bounds.x(),y=bounds.y(),w=bounds.width(),h=bounds.height();if(!window(x,y,w,h,tr("channel.settings"),()->modal="",null))return;
        text(tr("channel.name"),x+22,y+49,TEXT);input("channel-name",channelName(c),x+22,y+74,w-44,32,s->renameChannel(c.id,s));
        text(tr("channel.color"),x+22,y+124,TEXT);input("channel-color",String.format(Locale.ROOT,"#%06X",c.color&0xffffff),x+332,y+117,184,30,s->{String value=s.strip().replaceFirst("^#","");if(!value.matches("[0-9a-fA-F]{6}"))throw new StudioException("error.channel_color");colorChannel(c.id,Integer.parseInt(value,16));});
        int[] colors={0x697e79,0x577e87,0x876d80,0x8a7864,0x867a68,0x78658a,0x5a8584,0xa04d5d,0xc38045,0xb4a66b,0x76a76a,0x568bad,0x726fbd,0xb87eaf,0xb8bfc3,0x46545e};
        for(int i=0;i<colors.length;i++){int color=colors[i];float xx=x+22+i%8*62,yy=y+164+i/8*39;rect(xx,yy,51,30,0xff000000|color);if((c.color&0xffffff)==color)border(xx,yy,51,30,GREEN);hit("channel-palette-"+i,xx,yy,51,30,()->colorChannel(c.id,color));}
        button("channel-replace-event",tr("source.replace_event"),x+22,y+262,235,33,false,()->openSounds(false));
        button("channel-replace-audio",tr("source.replace_audio"),x+276,y+262,240,33,false,this::chooseReplacementAudio);
        tooltips.put("channel-replace-audio",tr("source.replace_hint"));
        button("channel-remove",tr("channel.remove"),x+22,y+h-51,155,32,false,()->requestDeleteChannel(song.channels.indexOf(c)));button("channel-done",tr("action.close"),x+350,y+h-51,166,32,true,()->{resetFocus();modal="";});
    }
    private void resetTrackHeight(int index){if(song.tracks.get(index).displayHeight!=65)edit(()->{song.tracks.get(index).displayHeight=65;clampVertical();});}
    private void deletePattern(){int removed=pattern;edit(()->{song.removePattern(removed);pattern=Math.min(removed,song.patterns.size()-1);clearSelection();lastPianoPattern=-1;normalize();});modal="";}
    private void chooseReplacementAudio(){if(mediaBusy)return;Path path=MediaDialogs.open(false);if(path!=null)replaceAudio(path,song.channels.get(channel).id);}
    void replaceAudio(Path path,String sourceId){if(mediaBusy)return;var snapshot=ProjectStore.copy(song);long rev=revision;mediaTask(()->AudioFiles.importAsset(path,store.directory(),snapshot,ClientMedia.codec()),asset->{if(revision!=rev){mediaMessage=tr("status.load_changed");return;}edit(()->{if(song.assets.stream().noneMatch(a->a.id.equals(asset.id)))song.assets.add(asset);song.replaceSampler(sourceId,asset.id);});status=tr("source.replaced");modal="";});}
    private void newPattern(){if(song.patterns.size()>=4096)return;edit(()->{song.patterns.add(new Pattern(tr("pattern.new",song.patterns.size()+1),song.channels.size()));pattern=song.patterns.size()-1;selectedAudio="";browserTab=0;selectedNote="";});}
    private String noteName(int p){return new String[]{"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"}[p%12]+(p/12-1);}
    private void report(Exception ex){MusicStudio.LOGGER.error("Music Studio operation failed",ex);mc.execute(()->status=tr("status.failed",StudioText.error(ex)));}
    private void save(boolean draft){
        resetFocus();SongProject snapshot=ProjectStore.copy(song);
        if(!draft){if(projectFile==null){saveAs();return;}saveProjectFile(projectFile);return;}
        dirty=false;IO.execute(()->{try{store.save("autosave",snapshot);}catch(Exception ex){mc.execute(()->{dirty=true;report(ex);});}});
    }
    private void saveAs(){resetFocus();if(mediaBusy)return;Path path=MediaDialogs.project(true,projectFile,song.title);if(path!=null)saveProjectFile(path,true);}
    private void saveProjectFile(Path path){saveProjectFile(path,false);}
    private void saveProjectFile(Path path,boolean saveAs){
        if(mediaBusy)return;SongProject snapshot=ProjectStore.copy(song);long rev=revision;mediaBusy=true;status=tr("status.saving");IO.execute(()->{try{Path actual;if(saveAs||Files.isRegularFile(path)&&ProjectFiles.isArchive(path))actual=ProjectFiles.saveAs(path,store.directory(),snapshot);else{actual=path.toAbsolutePath().normalize();ProjectFiles.save(actual,store.directory(),snapshot);}
            mc.execute(()->{mediaBusy=false;if(!closed&&song.projectId.equals(snapshot.projectId)){projectFile=actual;song.projectDirectory=actual.getParent();StudioClient.projectFile=projectFile;StudioClient.project=song;if(rev==revision)dirty=false;status=tr("status.saved",actual);modal="";}});
        }catch(Exception ex){mc.execute(()->{mediaBusy=false;dirty=true;report(ex);});}});
    }
    private void openNativeProject(){resetFocus();Path path=MediaDialogs.project(false,projectFile,song.title);if(path!=null)openProjectFile(path);}
    private record Opened(Path file,SongProject song){}
    private void openProjectFile(Path path){long rev=revision;mediaTask(()->{Path actual=ProjectFiles.resolveFile(path);return new Opened(actual,ProjectFiles.open(actual,store.directory()));},opened->{if(revision!=rev){status=tr("status.load_changed");return;}applyLoaded(opened.song);projectFile=opened.file.toString().toLowerCase(Locale.ROOT).endsWith(".musicstudio")?opened.file:null;StudioClient.projectFile=projectFile;status=tr("status.loaded",opened.file);});}
    private void applyLoaded(SongProject loaded){remember();stopPreview();song=loaded;channel=0;pattern=0;viewport.start=0;viewport.vertical=0;cursor=0;clearSelection();normalize();modal="";changed();}
    private void load(String name){resetFocus();Path folder=store.directory().resolve(name).normalize();if(folder.getParent().equals(store.directory())&&Files.isDirectory(folder)){openProjectFile(folder);return;}long rev=revision;IO.execute(()->{try{SongProject loaded=store.load(name);mc.execute(()->{if(closed)return;if(rev!=revision){status=tr("status.load_changed");return;}applyLoaded(loaded);projectFile=null;StudioClient.projectFile=null;fileName=name;status=tr("status.loaded",name);});}catch(Exception ex){report(ex);}});}
    public void tickEditor(){
        if(drag!=null){mouseX=drag.pointerX;mouseY=drag.pointerY;autoScrollOrder();}
        if(restartAfterSeek&&!mediaBusy&&drag==null)startPlayback(true);
        waveform.update(song,revision,pattern,patternMode,mediaBusy||drag!=null||!focus.isEmpty());
        if(drag!=null&&drag.type.equals("note")&&(mouseX<roll.gridX()+10||mouseX>roll.gridX()+roll.gridWidth()-10)){roll.start+=(mouseX<roll.gridX()+10?-1:1)*roll.span*.025;roll.clamp(rollLength());drag(originX+mouseX*scale,originY+mouseY*scale);}
        if(drag!=null&&drag.type.equals("roll-scroll-h")&&mouseX>=drag.left+drag.top-8){drag.extent=Math.min(PianoView.MAX_TIME,drag.extent+roll.span*.08);updateRollScroll();}
        if(drag!=null&&drag.type.equals("roll-seek")&&(mouseX<roll.gridX()+10||mouseX>roll.gridX()+roll.gridWidth()-10)){roll.start+=(mouseX<roll.gridX()+10?-1:1)*roll.span*.025;roll.clamp(rollLength());updateRollSeek();}
        if(drag!=null&&(drag.type.equals("clip")&&drag.edge==0||drag.type.startsWith("browser-"))&&mouseX>=TIME_X&&(mouseY<TRACK_TOP+14||mouseY>TRACK_BOTTOM-14)){
            viewport.vertical+=mouseY<TRACK_TOP+14?-12:12;clampVertical();drag(originX+mouseX*scale,originY+mouseY*scale);
        }
        if(drag!=null&&(drag.type.equals("clip")||drag.type.startsWith("browser-")&&mouseX>=TRACK_X||drag.type.equals("export-marker")||drag.type.equals("seek-playlist"))&&(mouseX<TIME_X+12||mouseX>TIME_X+TIME_W-12)){
            viewport.pan(viewport.start+(mouseX<TIME_X+12?-1:1)*viewport.bars*.025);
            if(drag.type.equals("seek-playlist")){drag.start=(int)(viewport.start*PlaylistView.BAR);drag.duration=Math.max(1,(int)(viewport.bars*PlaylistView.BAR));}
            drag(originX+mouseX*scale,originY+mouseY*scale);
        }
        if(exportRequest!=null&&System.currentTimeMillis()-exportStarted>20000){exportRequest=null;status=tr("error.blueprint_timeout");}
        boolean wasPlaying=audio.playing();audio.tick();if(audio.playing())cursor=audio.position();else if(wasPlaying)stop();
        if(!audio.warning.isBlank()){status=audio.warning;audio.warning="";}
        if(!loadedDraft){loadedDraft=true;if(Files.exists(store.path("autosave"))){long rev=revision;IO.execute(()->{try{SongProject draft=store.load("autosave");mc.execute(()->{if(!closed&&revision==rev){song=draft;normalize();StudioClient.project=song;status=tr("status.draft_restored");}});}catch(Exception ex){report(ex);}});}}
        if(dirty&&System.currentTimeMillis()-lastEdit>1800&&focus.isEmpty()&&drag==null)save(true);
        if(modal.equals("sounds")&&lastCatalogRevision!=SoundCatalog.INSTANCE.revision())refreshSearch();
    }
    public void close(){if(closed)return;closed=true;waveform.close();stopPreview();save(true);StudioClient.project=song;}
    public boolean escape(){if(!pianoPicker.isEmpty()){closePianoPicker();return true;}if(!focus.isEmpty()){commitField();return true;}if(!modal.isEmpty()){modal="";return true;}if(drag!=null){if(drag.resume)stop();drag=null;return true;}if(piano){piano=false;notesContext=false;return true;}return false;}

    public boolean click(double sx,double sy,int button){
        int modifiers=(Screen.hasControlDown()?GLFW.GLFW_MOD_CONTROL:0)|(Screen.hasShiftDown()?GLFW.GLFW_MOD_SHIFT:0);
        return click(sx,sy,button,modifiers);
    }
    private boolean click(double sx,double sy,int button,int modifiers){
        pointerCtrl=(modifiers&GLFW.GLFW_MOD_CONTROL)!=0;pointerShift=(modifiers&GLFW.GLFW_MOD_SHIFT)!=0;
        mouseX=(float)(sx-originX)/scale;mouseY=(float)(sy-originY)/scale;
        for(int i=hits.size()-1;i>=0;i--){Hit h=hits.get(i);if(!h.contains(mouseX,mouseY))continue;
            if(!h.id.equals(focus))resetFocus();
            if(button==0&&pointerCtrl&&modal.isEmpty()&&(h.id.equals("roll-grid")||h.id.startsWith("note-")&&!h.id.startsWith("note-seek-")||h.id.startsWith("lane-")||h.id.startsWith("clip-"))){
                boolean inNotes=h.id.equals("roll-grid")||h.id.startsWith("note-");
                syncSelection();notesContext=inNotes;drag=new Drag(inNotes?"box-notes":"box-clips",mouseX,mouseY);
                drag.left=mouseX;drag.top=mouseY;drag.additive=pointerShift;drag.initialSelection=Set.copyOf(inNotes?noteSelection:clipSelection);
                drag.id=h.id.startsWith(inNotes?"note-":"clip-")?h.id.substring(5):"";
                lastHit="";return true;
            }
            if(h.id.startsWith("window-piano")||h.id.equals("window-body-piano")||h.id.equals("piano")||h.id.equals("piano-menu")||h.id.equals("rack-roll"))notesContext=true;
            else if(h.id.startsWith("track-")||h.id.startsWith("lane-")||h.id.startsWith("clip-"))notesContext=false;
            long now=System.currentTimeMillis();boolean doubleClick=h.id.equals(lastHit)&&now-lastClickTime<270&&button==0;
            lastHit=h.id;lastClickTime=now;
            if(button==1){if(h.right!=null)h.right.run();}else if(button==0){if(doubleClick&&h.doubleClick!=null){drag=null;h.doubleClick.run();}else if(h.click!=null)h.click.run();}
            return true;
        }
        resetFocus();return true;
    }
    public void release(){if(drag!=null){
        if(orderKind(drag)!=null&&orderMoved()){var target=orderTarget();if(target!=null){drag=null;lastHit="";if(target.from!=target.to)reorder(target);return;}}
        if(drag.type.startsWith("order-")){drag=null;return;}
        if(drag.type.startsWith("browser-")){Drag dropped=drag;drag=null;if((dropped.type.equals("browser-pattern")||dropped.type.equals("browser-audio"))&&Math.abs(mouseX-dropped.x)+Math.abs(mouseY-dropped.y)>4&&mouseX>=TIME_X&&mouseX<TIME_X+TIME_W){int lane=trackAt(mouseY);if(lane>=0){if(dropped.type.equals("browser-pattern")){for(int i=0;i<song.patterns.size();i++)if(song.patterns.get(i).id.equals(dropped.id))selectPattern(i);}else selectedAudio=dropped.id;placeClip(lane,mousePosition());}}return;}

        if(drag.type.startsWith("box-")){
            Set<String> selection=drag.type.equals("box-notes")?noteSelection:clipSelection;
            if(!drag.moved){selection.clear();selection.addAll(drag.initialSelection);if(drag.id.isEmpty()){if(!drag.additive)selection.clear();}else if(!selection.remove(drag.id))selection.add(drag.id);}
            syncSelection();status=tr("status.selection",selection.size(),tr(notesContext?"type.notes":"type.clips"));
        }
        if(drag.committed)changed();boolean resume=drag.resume;drag=null;if(resume){restartAfterSeek=true;if(!mediaBusy)startPlayback(true);}
    }}
    public void drag(double sx,double sy){
        if(drag==null)return;mouseX=(float)(sx-originX)/scale;mouseY=(float)(sy-originY)/scale;
        drag.pointerX=mouseX;drag.pointerY=mouseY;
        if(drag.type.equals("piano-picker-scroll")){updatePianoPickerScroll();return;}
        float dx=mouseX-drag.x,dy=mouseY-drag.y;
        if(drag.type.equals("piano-resize")){roll.resize(drag.left,drag.top,(float)drag.extent,(float)drag.original,drag.edge,dx,dy);return;}
        if(drag.type.equals("roll-seek")){updateRollSeek();return;}
        if(drag.type.startsWith("roll-scroll-")){updateRollScroll();return;}
        if(drag.type.startsWith("roll-range-")){boolean vertical=drag.type.endsWith("v");double delta=(vertical?dy:dx)/drag.top*drag.extent;double min=vertical?3:PianoView.MIN_SPAN;double left=drag.edge==0?Math.max(0,Math.min(drag.original-min,drag.anchor+delta)):drag.anchor,right=drag.edge==1?Math.min(vertical?128:PianoView.MAX_TIME,Math.max(drag.anchor+min,drag.original+delta)):drag.original;if(vertical)roll.pitchRange(left,right,rollLength());else roll.timeRange(left,right,rollLength());return;}
        if(drag.type.startsWith("scroll-")){updateScroll();return;}if(drag.type.startsWith("browser-"))return;
        if(drag.type.equals("track-height")){var target=song.tracks.stream().filter(c->c.id.equals(drag.id)).findFirst().orElse(null);if(target!=null){int height=Mth.clamp((int)Math.round(drag.duration+(drag.resize?-dy:dy)/viewport.trackZoom),32,600);if(target.displayHeight!=height){dragCheckpoint();target.displayHeight=height;clampVertical();}}return;}
        if(drag.type.startsWith("seek-")){updateSeek();return;}
        if(drag.type.equals("export-marker")){int at=rangePosition(drag.start+(viewport.start+(mouseX-TIME_X)/TIME_W*viewport.bars-drag.anchor)*PlaylistView.BAR);if(at!=(drag.edge==0?song.exportStart:song.exportEnd)){dragCheckpoint();if(drag.edge==0)song.exportStart=at;else song.exportEnd=at;}return;}
        if(drag.type.startsWith("sample-")){var c=sampleChannel();float total=ProjectMedia.asset(song,c.sampleId).frames/(float)AudioFiles.RATE,end=c.trimEnd==0?total:c.trimEnd;dragCheckpoint();float ratio=Mth.clamp((mouseX-drag.left)/drag.top,0,1);switch(drag.type){case "sample-start"->c.trimStart=Math.min(end-1f/AudioFiles.RATE,ratio*total);case "sample-end"->c.trimEnd=Math.max(c.trimStart+1f/AudioFiles.RATE,ratio*total);case "sample-fade-in"->c.fadeIn=ratio*(end-c.trimStart);case "sample-fade-out"->c.fadeOut=ratio*(end-c.trimStart);case "sample-gain"->c.gain=ratio*4;default->{}}c.fadeIn=Math.min(c.fadeIn,(c.trimEnd==0?total:c.trimEnd)-c.trimStart);c.fadeOut=Math.min(c.fadeOut,(c.trimEnd==0?total:c.trimEnd)-c.trimStart);c.renderedAudio="";return;}
        if(drag.type.startsWith("box-")){
            if(Math.abs(dx)+Math.abs(dy)<3)return;drag.moved=true;drag.left=mouseX;drag.top=mouseY;
            float x1=Math.min(drag.x,mouseX),x2=Math.max(drag.x,mouseX),y1=Math.min(drag.y,mouseY),y2=Math.max(drag.y,mouseY);
            boolean isNotes=drag.type.equals("box-notes");Set<String> selection=isNotes?noteSelection:clipSelection;selection.clear();if(drag.additive)selection.addAll(drag.initialSelection);
            String prefix=isNotes?"note-":"clip-";
            for(Hit h:hits)if(h.id.startsWith(prefix)&&!h.id.startsWith("note-seek-")&&h.x<x2&&h.x+h.w>x1&&h.y<y2&&h.y+h.h>y1)selection.add(h.id.substring(prefix.length()));
            syncSelection();return;
        }
        if(drag.type.equals("rack")&&!windows.get("rack").maximized){rackX=Mth.clamp(drag.left+dx,0,W-650);rackY=Mth.clamp(drag.top-135+dy,0,Math.max(0,668-(62+Math.min(12,song.channels.size())*29+29)));}
        else if(drag.type.equals("piano")){if(!roll.maximized)roll.bounds(drag.left+dx,drag.top+dy,roll.width,roll.height);}
        else if(drag.type.equals("clip")){
            int unit=snap*SongProject.STEP,base=drag.start+(drag.edge>0?drag.duration:0);
            double displacement=(viewport.start+(mouseX-TIME_X)/TIME_W*viewport.bars-drag.anchor)*PlaylistView.BAR;
            int delta=(int)Math.round((displacement+(drag.edge==0?0:base))/unit)*unit-(drag.edge==0?0:base);
            int min=Integer.MIN_VALUE,max=Integer.MAX_VALUE;
            for(int[] o:drag.originals.values()) {min=Math.max(min,drag.edge>0?SongProject.STEP-o[1]:-o[0]);max=Math.min(max,drag.edge<0?o[1]-SongProject.STEP:SongProject.MAX_SONG_PPQ-o[0]-o[1]);if(drag.edge<0){min=Math.max(min,o[2]-SongProject.MAX_SONG_PPQ);max=Math.min(max,o[2]+SongProject.MAX_SONG_PPQ);}}
            if(drag.edge!=0){min=(int)(Math.ceil((base+(double)min)/unit)*unit)-base;max=(int)(Math.floor((base+(double)max)/unit)*unit)-base;if(min>max)return;}
            delta=Mth.clamp(delta,min,max);
            int target=trackAt(mouseY),trackDelta=drag.edge==0&&target>=0?target-drag.pitch:0,lo=-256,hi=256;
            for(int[] o:drag.originals.values()){lo=Math.max(lo,-o[3]);hi=Math.min(hi,song.tracks.size()-1-o[3]);}trackDelta=Mth.clamp(trackDelta,lo,hi);
            for(Clip c:song.clips){int[] o=drag.originals.get(c.id);if(o==null)continue;int start=o[0]+(drag.edge<=0?delta:0),duration=o[1]+(drag.edge>0?delta:drag.edge<0?-delta:0);if(c.start!=start||c.duration!=duration||c.track!=o[3]+trackDelta){dragCheckpoint();stopPreview();if(drag.edge==0)c.start=start;else c.resize(start,start+duration);c.track=o[3]+trackDelta;activeTrack=c.track;extendSong(c.start+c.duration);}}
        }else if(drag.type.equals("note")){
            double stepDelta=roll.timeAt(mouseX)-drag.anchor;int delta=(int)Math.max(-SongProject.MAX_SONG_PPQ,Math.min(SongProject.MAX_SONG_PPQ,Math.round(stepDelta)* (long)SongProject.STEP)),pitchDelta=drag.resize?0:-Math.round(dy/pianoRowHeight()),min=Integer.MIN_VALUE,max=Integer.MAX_VALUE,minPitch=-127,maxPitch=127;
            for(int[] o:drag.originals.values()){min=Math.max(min,drag.resize?SongProject.STEP-o[2]:-o[0]);max=Math.min(max,SongProject.MAX_SONG_PPQ-o[0]-o[2]);minPitch=Math.max(minPitch,-o[1]);maxPitch=Math.min(maxPitch,127-o[1]);}
            delta=Mth.clamp(delta,min,max);pitchDelta=Mth.clamp(pitchDelta,minPitch,maxPitch);
            for(Note n:notes()){int[] o=drag.originals.get(n.id);if(o==null)continue;int start=drag.resize?o[0]:o[0]+delta,pitch=o[1]+pitchDelta,duration=drag.resize?o[2]+delta:o[2];if(n.start!=start||n.pitch!=pitch||n.duration!=duration){dragCheckpoint();n.start=start;n.pitch=pitch;n.duration=duration;}}
            song.growPattern(pattern);
        }else if(drag.type.equals("velocity")){var group=selectedNotes();if(!group.isEmpty()){dragCheckpoint();int velocity=Mth.clamp(Math.round((drag.top-mouseY)/28*127),1,127);group.forEach(n->n.velocity=velocity);}}
    }
    private void drawSelectionBox(){float x=Math.min(drag.x,drag.left),y=Math.min(drag.y,drag.top),w=Math.abs(drag.left-drag.x),h=Math.abs(drag.top-drag.y);if(w+h<3)return;rect(x,y,w,h,0x335db6c7);border(x,y,w,h,0xff98cad4);}
    private void copySelection(boolean cut){
        syncSelection();EditorClipboard captured=notesContext?EditorClipboard.copyNotes(notes(),noteSelection):EditorClipboard.copyClips(song,clipSelection);
        if(captured.kind()==EditorClipboard.Kind.EMPTY){status=notesContext?tr("status.select_notes"):tr("status.select_clips");return;}
        clipboard=captured;if(cut)deleteSelection();status=tr("status.copied_cut",tr(cut?"action.cut":"action.copy"),captured.size(),tr(notesContext?"type.notes":"type.clips"));
    }
    private void pasteSelection(){
        syncSelection();if(clipboard.kind()==EditorClipboard.Kind.EMPTY){status=tr("status.clipboard_empty");return;}
        try{
            if(notesContext){
                clipboard.checkNotes(song,pattern,channel,notePastePpq);
                edit(()->{Set<String> created=clipboard.pasteNotes(song,pattern,channel,notePastePpq);noteSelection.clear();noteSelection.addAll(created);selectedNote=created.iterator().next();});
                notePastePpq+=clipboard.span();
            }else{
                int destination=TimelineSnap.position(cursor,snap,SongProject.MAX_SONG_PPQ);var target=ProjectStore.copy(song);target.endPpq=Math.max(target.endPpq,Math.min(SongProject.MAX_SONG_PPQ,(int)(Math.ceil((destination+(double)clipboard.span())/PlaylistView.BAR)*PlaylistView.BAR)));clipboard.checkClips(target,destination);
                edit(()->{extendSong(destination+clipboard.span());Set<String> created=clipboard.pasteClips(song,destination);clipSelection.clear();clipSelection.addAll(created);selectedClip=created.iterator().next();});
                cursor=destination+clipboard.span();
            }
            status=tr("status.pasted",clipboard.size(),tr(notesContext?"type.notes":"type.clips"));
        }catch(IllegalArgumentException ex){status=StudioText.error(ex);}
    }
    private void deleteSelection(){
        syncSelection();if(notesContext){if(noteSelection.isEmpty())return;Set<String> ids=Set.copyOf(noteSelection);edit(()->notes().removeIf(n->ids.contains(n.id)));noteSelection.clear();selectedNote="";}
        else{if(clipSelection.isEmpty())return;Set<String> ids=Set.copyOf(clipSelection);edit(()->song.clips.removeIf(c->ids.contains(c.id)));clipSelection.clear();selectedClip="";}
    }
    private void dragCheckpoint(){if(!drag.committed){undo.push(new History(drag.before,projectFile,song.projectDirectory));while(undo.size()>48)undo.removeLast();redo.clear();drag.committed=true;}}
    public void scroll(double sx,double sy,double delta){
        scroll(sx,sy,delta,Screen.hasControlDown());
    }
    private void scroll(double sx,double sy,double delta,boolean ctrl){
        if(!pianoPicker.isEmpty()){float px=(float)(sx-originX)/scale,py=(float)(sy-originY)/scale;if(px>=pickerX&&px<pickerX+pickerW&&py>=pickerY&&py<pickerY+pickerH){var choices=pianoChoices();pianoPickerScroll=Mth.clamp(pianoPickerScroll-(int)delta*3,0,Math.max(0,choices.size()-10));}return;}
        if(modal.equals("sounds")){soundScroll=Mth.clamp(soundScroll-(int)delta*3,0,Math.max(0,results.size()-15));return;}
        if(modal.equals("projects")){fileScroll=Mth.clamp(fileScroll-(int)delta*3,0,Math.max(0,files.size()-9));return;}
        if(!modal.isEmpty())return;
        float x=(float)(sx-originX)/scale,y=(float)(sy-originY)/scale;
        if(piano&&!windows.get("piano").folded&&roll.contains(x,y)){
            if(x>=roll.verticalX()&&y>=roll.gridY()&&y<roll.gridY()+roll.gridHeight())roll.zoomPitch(delta,(y-roll.gridY())/roll.gridHeight(),rollLength());
            else if(ctrl||x>=roll.gridX()&&x<roll.gridX()+roll.gridWidth()&&y>=roll.horizontalY()&&y<roll.horizontalY()+16)roll.zoomTime(delta,(x-roll.gridX())/roll.gridWidth(),rollLength());
            else{roll.top-=delta*3;roll.clamp(rollLength());}return;
        }
        if(x<TRACK_X&&y>=233&&y<714){browserScroll=Math.max(0,browserScroll-(int)delta*3);return;}
        if(rack&&!windows.get("rack").folded){var bounds=frame("rack",rackX,135+rackY,650,91+Math.min(12,song.channels.size())*29);if(x>=bounds.x()&&x<bounds.x()+bounds.width()&&y>=bounds.y()&&y<bounds.y()+bounds.height()){int visible=Math.max(1,(int)(bounds.height()-91)/29);rackScroll=Mth.clamp(rackScroll-(int)delta*3,0,Math.max(0,song.channels.size()-visible));return;}}
        if(editorFolded)return;
        if(x>=1266&&y>=TRACK_TOP&&y<TRACK_BOTTOM){double anchor=viewport.vertical+(y-TRACK_TOP);double old=viewport.trackZoom;viewport.trackZoom=Math.max(.4,Math.min(8,old*Math.pow(1.15,delta)));viewport.vertical=anchor*viewport.trackZoom/old-(y-TRACK_TOP);clampVertical();return;}
        boolean horizontal=x>=TIME_X&&x<TIME_X+TIME_W&&(y>=165&&y<183||y>=788&&y<805);
        if(x>=TRACK_X&&(ctrl||horizontal)){viewport.zoom(delta,(x-TIME_X)/TIME_W);return;}
        if(x>=TRACK_X&&y>=TRACK_TOP&&y<TRACK_BOTTOM){viewport.vertical-=delta*48;clampVertical();}
    }
    public boolean character(char ch){
        if(!pianoPicker.isEmpty()&&focus.isEmpty()){field("piano-picker-search",pianoPickerQuery,value->pianoPickerQuery=value);selectAll=false;}
        if(focus.isEmpty())return false;if(Character.isISOControl(ch))return true;
        if(selectAll){fieldText="";selectAll=false;}if(fieldText.length()<128)fieldText+=ch;
        updateQuery();return true;
    }
    public boolean key(int key,int modifiers){
        boolean ctrl=(modifiers&GLFW.GLFW_MOD_CONTROL)!=0;
        if(key==GLFW.GLFW_KEY_ESCAPE)return escape();
        if(ctrl&&key==GLFW.GLFW_KEY_S){resetFocus();if((modifiers&GLFW.GLFW_MOD_SHIFT)!=0)saveAs();else save(false);return true;}
        if(ctrl&&key==GLFW.GLFW_KEY_O){openNativeProject();return true;}
        if(!pianoPicker.isEmpty()){
            if(key==GLFW.GLFW_KEY_UP||key==GLFW.GLFW_KEY_DOWN){movePianoChoice(key==GLFW.GLFW_KEY_DOWN?1:-1);return true;}
            if(key==GLFW.GLFW_KEY_PAGE_UP||key==GLFW.GLFW_KEY_PAGE_DOWN){movePianoChoice(key==GLFW.GLFW_KEY_PAGE_DOWN?10:-10);return true;}
            if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){var choices=pianoChoices();if(!choices.isEmpty())choosePianoEntry(choices.get(Mth.clamp(pianoPickerIndex,0,choices.size()-1)));return true;}
            if(!focus.equals("piano-picker-search"))return true;
        }
        if(!focus.isEmpty()){
            if(ctrl&&key==GLFW.GLFW_KEY_A){selectAll=true;return true;}
            if(ctrl&&(key==GLFW.GLFW_KEY_C||key==GLFW.GLFW_KEY_X)){if(selectAll){mc.keyboardHandler.setClipboard(fieldText);if(key==GLFW.GLFW_KEY_X){fieldText="";selectAll=false;updateQuery();}}return true;}
            if(ctrl&&key==GLFW.GLFW_KEY_V){if(selectAll)fieldText="";selectAll=false;fieldText=fitInput(fieldText+mc.keyboardHandler.getClipboard());updateQuery();return true;}
            if(key==GLFW.GLFW_KEY_BACKSPACE){if(selectAll||ctrl)fieldText="";else if(!fieldText.isEmpty())fieldText=fieldText.substring(0,fieldText.offsetByCodePoints(fieldText.length(),-1));selectAll=false;updateQuery();return true;}
            if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){if(focus.equals("search")&&!results.isEmpty()){focus="";bind(results.get(soundSelected));}else commitField();return true;}
            if(key==GLFW.GLFW_KEY_TAB){commitField();return true;}
            if(!focus.equals("search")||!(key==GLFW.GLFW_KEY_UP||key==GLFW.GLFW_KEY_DOWN))return true;
        }
        if(modal.equals("sounds")){
            if(key==GLFW.GLFW_KEY_DOWN||key==GLFW.GLFW_KEY_UP){soundSelected=Mth.clamp(soundSelected+(key==GLFW.GLFW_KEY_DOWN?1:-1),0,Math.max(0,results.size()-1));soundScroll=Mth.clamp(soundSelected<soundScroll?soundSelected:soundSelected>=soundScroll+15?soundSelected-14:soundScroll,0,Math.max(0,results.size()-15));return true;}
            if(key==GLFW.GLFW_KEY_ENTER&&!results.isEmpty()){bind(results.get(soundSelected));return true;}
            if(key==GLFW.GLFW_KEY_SPACE&&!results.isEmpty()){auditionEntry(results.get(soundSelected));return true;}return true;
        }
        if(Set.of("sample","channel-settings","track-settings","pattern-settings").contains(modal)&&ctrl&&key==GLFW.GLFW_KEY_Z){undo();return true;}
        if(Set.of("sample","channel-settings","track-settings","pattern-settings").contains(modal)&&ctrl&&key==GLFW.GLFW_KEY_Y){redo();return true;}
        if(!modal.isEmpty())return true;
        if(key==GLFW.GLFW_KEY_SPACE){play();return true;}if(key==GLFW.GLFW_KEY_F6){toggleRack();return true;}if(key==GLFW.GLFW_KEY_F7){togglePiano();return true;}
        if(ctrl&&key==GLFW.GLFW_KEY_Z){undo();return true;}if(ctrl&&key==GLFW.GLFW_KEY_Y){redo();return true;}
        if(ctrl&&key==GLFW.GLFW_KEY_A){syncSelection();if(notesContext){noteSelection.clear();notes().forEach(n->noteSelection.add(n.id));}else{clipSelection.clear();song.clips.forEach(c->clipSelection.add(c.id));}syncSelection();status=tr("status.select_all");return true;}
        if(ctrl&&(key==GLFW.GLFW_KEY_C||key==GLFW.GLFW_KEY_X)){copySelection(key==GLFW.GLFW_KEY_X);return true;}
        if(ctrl&&key==GLFW.GLFW_KEY_V){pasteSelection();return true;}
        if(key==GLFW.GLFW_KEY_DELETE){deleteSelection();return true;}
        return false;
    }
    private String fitInput(String s){return s.codePoints().filter(c->!Character.isISOControl(c)).limit(128).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();}
    private void updateQuery(){if(focus.equals("search")){query=fieldText;soundScroll=0;soundSelected=0;refreshSearch();}else if(focus.equals("piano-picker-search")){pianoPickerQuery=fieldText;pianoPickerScroll=pianoPickerIndex=0;}}

    private Hit smokeHit(String id){return hits.stream().filter(h->h.id.equals(id)).findFirst().orElseThrow(()->new IllegalStateException("Missing hit: "+id));}
    private String arrangementClip="",arrangementBefore="";private int arrangementCount;
    private void arrangementCheck(boolean ok,String text){if(!ok)throw new IllegalStateException("ARRANGEMENT_SMOKE: "+text);}
    private void arrangementClick(String id){Hit h=smokeHit(id);lastHit="";click(originX+(h.x+h.w/2)*scale,originY+(h.y+h.h/2)*scale,0,0);release();}
    private float arrangementY(int lane){float y=TRACK_TOP-(float)viewport.vertical;for(int i=0;i<lane;i++)y+=trackHeight(song.tracks.get(i));return y+trackHeight(song.tracks.get(lane))/2;}
    private Clip arrangementClip(){return song.clips.stream().filter(c->c.id.equals(arrangementClip)).findFirst().orElseThrow();}
    private String arrangementTimes(Clip c){return song.clipEvents(c,c.start,c.start+c.duration).stream().map(e->e.channel()+":"+e.start()+":"+e.duration()+":"+e.pitch()).sorted().toList().toString();}
    private void arrangementBrowserDrop(int index,int lane,double bar){Hit h=smokeHit("browser-item-"+index);lastHit="";click(originX+(h.x+30)*scale,originY+(h.y+12)*scale,0,0);drag(originX+timeX(bar+.02)*scale,originY+arrangementY(lane)*scale);release();}
    private void arrangementEdge(Clip c,boolean left,double bars){Hit h=smokeHit("clip-"+c.id);float x=left?timeX(c.start/PlaylistView.BAR)+2:timeX((c.start+c.duration)/PlaylistView.BAR)-2;lastHit="";click(originX+x*scale,originY+(h.y+h.h/2)*scale,0,0);arrangementCheck(drag!=null&&drag.type.equals("clip")&&drag.edge==(left?-1:1),"edge did not engage");drag(originX+(x+(float)(bars/viewport.bars*TIME_W))*scale,originY+(h.y+h.h/2)*scale);release();}
    private void smokeHitDrag(String id,float dx,float dy){var h=smokeHit(id);float x=h.x+h.w/2,y=h.y+h.h/2;lastHit="";click(originX+x*scale,originY+y*scale,0,0);drag(originX+(x+dx)*scale,originY+(y+dy)*scale);release();}
    private String reorderBefore,reorderExpected,reorderId;
    private String reorderSignature(SongProject s){var values=new ArrayList<String>();for(var c:s.clips)values.add("clip:"+c.id+":"+s.tracks.get(c.track).id+":"+(c.audio()?s.audioSources.get(c.audioSource).id:s.patterns.get(c.pattern).id)+":"+c.start+":"+c.duration+":"+c.contentOffset+":"+c.contentLength);for(var p:s.patterns)for(int c=0;c<s.channels.size();c++)for(var n:p.notes.get(c))values.add("note:"+p.id+":"+s.channels.get(c).id+":"+n.id+":"+n.start+":"+n.duration+":"+n.pitch+":"+n.velocity);Collections.sort(values);return values.toString();}
    private void reorderInvariant(){arrangementCheck(reorderExpected.equals(reorderSignature(song)),"Reorder changed musical bindings");song.validate();}
    private void holdOrder(String id,float x,float y){var h=smokeHit(id);lastHit="";click(originX+(h.x+h.w/2)*scale,originY+(h.y+h.h/2)*scale,0,0);drag(originX+x*scale,originY+y*scale);}
    boolean smokeReorderStep(int stage,Path root) throws Exception {
        switch(stage){
            case 0->{
                stopPreview();revision++;song=ArrangementSmoke.fixture();while(song.channels.size()<20){song.channels.add(new Channel("Voice "+song.channels.size(),"minecraft:block.note_block.harp",0x577e87));song.patterns.forEach(p->p.notes.add(new ArrayList<>()));}
                var asset=AudioFiles.importAsset(root.resolve("source.wav"),store.directory(),song,ClientMedia.codec());song.assets.add(asset);song.channels.get(19).sampleId=asset.id;song.patterns.getFirst().notes.get(19).add(new Note(8,1,66,127));
                while(song.patterns.size()<20){var p=new Pattern("Pattern "+song.patterns.size(),20);p.notes.get(song.patterns.size()%20).add(new Note(0,1,66,127));song.patterns.add(p);}
                while(song.tracks.size()<20)song.tracks.add(new Track("Track "+song.tracks.size(),0x577e87));song.tracks.get(0).displayHeight=50;song.tracks.get(1).displayHeight=120;song.tracks.get(2).displayHeight=80;
                for(int i=0;i<3;i++){var c=new Channel("Recording "+i,"musicstudio:sample/"+asset.id,0x876d80);c.sampleId=asset.id;c.tunable=false;song.audioSources.add(c);song.clips.add(Clip.audio(i+1,i,(i+1)*4*SongProject.PPQ,SongProject.PPQ));}
                channel=pattern=activeTrack=browserTab=browserScroll=rackScroll=0;viewport.vertical=viewport.start=0;viewport.bars=12;loadedDraft=true;projectFile=null;StudioClient.projectFile=null;rack=piano=false;modal="";clearSelection();normalize();undo.clear();redo.clear();reorderBefore=ProjectStore.encode(song);reorderExpected=reorderSignature(song);clipboard=EditorClipboard.copyClips(song,song.clips.stream().map(c->c.id).collect(java.util.stream.Collectors.toSet()));
            }
            case 1->{reorderId=song.patterns.getFirst().id;smokeHitDrag("browser-item-0",0,68);arrangementCheck(song.patterns.get(2).id.equals(reorderId)&&song.patterns.get(pattern).id.equals(reorderId),"Pattern drag/focus failed");reorderInvariant();}
            case 2->{key(GLFW.GLFW_KEY_Z,GLFW.GLFW_MOD_CONTROL);arrangementCheck(ProjectStore.encode(song).equals(reorderBefore)&&song.patterns.get(pattern).id.equals(reorderId),"Pattern undo lost focus");key(GLFW.GLFW_KEY_Y,GLFW.GLFW_MOD_CONTROL);arrangementCheck(song.patterns.get(pattern).id.equals(reorderId)&&pattern==2,"Pattern redo lost focus");reorderInvariant();}
            case 3->{int count=song.clips.size();arrangementBrowserDrop(2,3,2);arrangementCheck(song.clips.size()==count+1&&!song.clips.getLast().audio()&&song.patterns.get(song.clips.getLast().pattern).id.equals(reorderId),"Pattern-to-playlist drag was broken");undo();reorderInvariant();}
            case 4->{reorderId=song.tracks.getFirst().id;var hit=smokeHit("track-0");smokeHitDrag("track-0",0,arrangementY(2)-(hit.y+hit.h/2));arrangementCheck(activeTrack==2&&song.tracks.get(2).id.equals(reorderId),"Variable-height track drag failed");reorderInvariant();}
            case 5->{undo();arrangementCheck(activeTrack==0&&song.tracks.get(activeTrack).id.equals(reorderId),"Track undo focus");redo();arrangementCheck(activeTrack==2&&song.tracks.get(activeTrack).id.equals(reorderId),"Track redo focus");var h=smokeHit("track-2");lastHit="";for(int i=0;i<2;i++){click(originX+(h.x+h.w/2)*scale,originY+(h.y+h.h/2)*scale,0,0);release();}arrangementCheck(song.tracks.get(2).displayHeight==65,"Track double-click height reset broken");undo();reorderInvariant();browserTab=1;}
            case 6->{reorderId=song.channels.getFirst().id;smokeHitDrag("browser-item-0",0,68);arrangementCheck(channel==2&&song.channels.get(2).id.equals(reorderId),"Source browser reorder failed");reorderInvariant();}
            case 7->{selectedNote=notes().getFirst().id;noteSelection.clear();noteSelection.add(selectedNote);selectionChannel=channel;selectionPattern=pattern;lastPianoChannel=channel;lastPianoPattern=pattern;smokeHitDrag("rack-reorder-2",0,-70);arrangementCheck(channel==0&&song.channels.get(0).id.equals(reorderId)&&noteSelection.contains(notes().getFirst().id),"Rack move lost voice/note focus");reorderInvariant();}
            case 8->{undo();arrangementCheck(channel==2&&song.channels.get(channel).id.equals(reorderId),"Rack undo source focus");redo();arrangementCheck(channel==0&&song.channels.get(channel).id.equals(reorderId),"Rack redo source focus");reorderInvariant();}
            case 9->{var b=rackBounds();reorderId=song.channels.getFirst().id;holdOrder("rack-reorder-0",b.x()+35,b.y()+53+12*29-3);}
            case 10->{if(rackScroll<2)return false;release();arrangementCheck(channel>=12&&song.channels.get(channel).id.equals(reorderId),"Rack edge scrolling failed");reorderInvariant();rack=false;browserTab=2;browserScroll=0;}
            case 11->{reorderId=song.audioSources.getFirst().id;smokeHitDrag("browser-item-0",0,68);arrangementCheck(song.audioSources.get(2).id.equals(reorderId),"Audio browser reorder failed");reorderInvariant();}
            case 12->{int count=song.clips.size();arrangementBrowserDrop(2,1,6);arrangementCheck(song.clips.size()==count+1&&song.clips.getLast().audio()&&song.audioSources.get(song.clips.getLast().audioSource).id.equals(reorderId),"Audio-to-playlist drag was broken");undo();reorderInvariant();browserTab=0;browserScroll=0;}
            case 13->{reorderId=song.patterns.getFirst().id;holdOrder("browser-item-0",80,706);}
            case 14->{if(browserScroll<2)return false;release();arrangementCheck(song.patterns.subList(12,song.patterns.size()).stream().anyMatch(p->p.id.equals(reorderId))&&viewport.start==0,"Pattern edge scrolling moved Playlist or lost source");reorderInvariant();}
            case 15->{int count=song.clips.size();notesContext=false;cursor=8*4*SongProject.PPQ;pasteSelection();arrangementCheck(song.clips.size()==count*2,"Reordered clipboard paste failed");undo();reorderInvariant();saveProjectFile(root.resolve("reordered.musicstudio"),true);}
            case 16->{if(mediaBusy)return false;Path file=root.resolve("reordered/reordered.musicstudio");arrangementCheck(file.equals(projectFile)&&ProjectStore.encode(ProjectFiles.open(file,root.resolve("unused-cache"))).equals(ProjectStore.encode(song)),"Reordered folder save/open failed");rack=true;rackScroll=0;}
            case 17->{String before=ProjectStore.encode(song);int history=undo.size();smokeHitDrag("rack-reorder-0",1400,0);arrangementCheck(before.equals(ProjectStore.encode(song))&&undo.size()==history,"Invalid reorder drop mutated score/history");rack=false;viewport.vertical=0;browserScroll=0;}
            case 18->{reorderId=song.tracks.getFirst().id;holdOrder("track-0",190,TRACK_BOTTOM-3);}
            case 19->{if(viewport.vertical<36)return false;release();arrangementCheck(activeTrack>=5&&song.tracks.get(activeTrack).id.equals(reorderId),"Track edge scrolling failed");reorderInvariant();saveProjectFile(projectFile);}
            case 20->{if(mediaBusy)return false;reorderInvariant();arrangementCheck(ProjectStore.encode(ProjectFiles.open(projectFile,root.resolve("unused-cache"))).equals(ProjectStore.encode(song)),"Final order was not saved");Files.writeString(root.resolve("complete.txt"),"PASS: real mouse Pattern/source/rack/audio/variable-height track reordering, insertion markers, edge scrolling, undo/redo and stable focus, clip placement, clipboard, invalid drops and folder persistence.");}
            case 21->{rack=piano=false;modal="";viewport.vertical=viewport.start=0;windows.get("piano").folded=true;var c=song.clips.stream().filter(v->!v.audio()).findFirst().orElseThrow();viewport.pan(c.start/PlaylistView.BAR);revealTrack(c.track);chooseClip(c.id);reorderBefore=ProjectStore.encode(song);}
            case 22->{var c=song.clips.stream().filter(v->v.id.equals(selectedClip)).findFirst().orElseThrow();var h=smokeHit("clip-"+c.id);lastHit="";for(int i=0;i<2;i++){click(originX+(h.x+h.w/2)*scale,originY+(h.y+h.h/2)*scale,0,0);release();}arrangementCheck(piano&&!windows.get("piano").folded&&!rack&&notesContext&&pattern==c.pattern,"Pattern double-click did not directly open its Piano Roll");arrangementCheck(reorderBefore.equals(ProjectStore.encode(song)),"Opening Piano changed the score");}
            case 23->{arrangementCheck(hits.stream().anyMatch(h->h.id.equals("roll-grid")),"Piano Roll did not render after clip double-click");piano=false;var c=song.clips.stream().filter(Clip::audio).findFirst().orElseThrow();viewport.pan(c.start/PlaylistView.BAR);revealTrack(c.track);chooseClip(c.id);}
            case 24->{var c=song.clips.stream().filter(v->v.id.equals(selectedClip)).findFirst().orElseThrow();var h=smokeHit("clip-"+c.id);lastHit="";for(int i=0;i<2;i++){click(originX+(h.x+h.w/2)*scale,originY+(h.y+h.h/2)*scale,0,0);release();}arrangementCheck(modal.equals("sample")&&sampleSourceId.equals(song.audioSources.get(c.audioSource).id),"Audio double-click did not retain its sample editor");arrangementCheck(reorderBefore.equals(ProjectStore.encode(song)),"Clip double-click changed the score");Files.writeString(root.resolve("clip-open-complete.txt"),"PASS: real mouse Pattern double-click opens/unfolds the correct Piano Roll without opening the rack; independent audio still opens its sample editor; score unchanged.");}
            case 25->{stopPreview();modal="";rack=piano=false;browserTab=2;browserScroll=0;reorderId=song.audioSources.get(1).id;channel=song.instrumentFromAudio(reorderId);song.patterns.getFirst().notes.get(channel).add(new Note(2,2,72,100));clearSelection();selectedAudio=sampleSourceId=reorderId;undo.clear();redo.clear();revision++;reorderBefore=ProjectStore.encode(song);var expected=ProjectStore.copy(song);expected.removeAudioSource(1);reorderExpected=ProjectStore.encode(expected);}
            case 26->{arrangementClick("browser-delete-audio-1");arrangementCheck(modal.equals("delete-audio")&&deleteAudioId.equals(reorderId)&&reorderBefore.equals(ProjectStore.encode(song)),"Audio list delete did not request confirmation without changing score");}
            case 27->{arrangementClick("cancel-remove-audio");arrangementCheck(modal.isEmpty()&&reorderBefore.equals(ProjectStore.encode(song))&&undo.isEmpty(),"Cancelling audio deletion changed score/history");}
            case 28->{var h=smokeHit("browser-item-1");lastHit="";click(originX+(h.x+30)*scale,originY+(h.y+12)*scale,1,0);release();arrangementCheck(modal.equals("sample")&&sampleSourceId.equals(reorderId),"Audio context sample editor broke");}
            case 29->{arrangementClick("sample-remove-audio");arrangementCheck(modal.equals("delete-audio")&&deleteAudioId.equals(reorderId),"Sample editor delete targeted wrong audio");}
            case 30->{arrangementClick("confirm-remove-audio");arrangementCheck(modal.isEmpty()&&selectedAudio.isEmpty()&&sampleSourceId.isEmpty()&&reorderExpected.equals(ProjectStore.encode(song)),"Audio deletion lost surviving references or Pattern sampler");key(GLFW.GLFW_KEY_Z,GLFW.GLFW_MOD_CONTROL);arrangementCheck(reorderBefore.equals(ProjectStore.encode(song)),"Audio deletion undo did not fully restore audio/clips");key(GLFW.GLFW_KEY_Y,GLFW.GLFW_MOD_CONTROL);arrangementCheck(reorderExpected.equals(ProjectStore.encode(song)),"Audio deletion redo failed");saveProjectFile(projectFile);}
            case 31->{if(mediaBusy)return false;song.validate();arrangementCheck(reorderExpected.equals(ProjectStore.encode(ProjectFiles.open(projectFile,root.resolve("unused-cache")))),"Deleted audio returned or surviving media changed after folder reload");Files.writeString(root.resolve("audio-delete-complete.txt"),"PASS: browser delete button, cancellation without history mutation, sample editor delete, removal of all placements, unchanged surviving audio bindings/Pattern sampler/shared assets, undo/redo and portable save/reload.");}
            default->throw new IllegalStateException("Unknown reorder stage");
        }
        return true;
    }
    private boolean near(double a,double b){return Math.abs(a-b)<.1;}
    private String interactionBefore="",interactionNotes="",interactionId="";private int interactionBaseline;
    private double smokeTransportStart,smokeTransportPosition;private String smokeTransportScore;
    private int smokeGrowthBefore;
    private CompletableFuture<Float> smokePitchActual;private float smokePitchExpected;
    private void smokePitchTrigger(int pitch) throws Exception {
        stopPreview();arrangementCheck(audio.trigger("minecraft:block.note_block.harp",pitch,66,true,127,.15f,SongProject.STEP,120),"extended note trigger");smokePitchExpected=(float)MusicalPitch.ratio(pitch,66,true);smokePitchActual=StudioPitchSmoke.actual(audio.lastVoice());
    }
    private boolean smokePitchStep(int step,Path root) throws Exception {
        switch(step){
            case 0->{stop();closePianoPicker();modal="";song=ArrangementSmoke.fixture();pattern=channel=0;piano=true;rack=false;loadedDraft=true;cursor=0;smokePitchTrigger(42);}
            case 1->{if(!smokePitchActual.isDone())return false;arrangementCheck(near(smokePitchActual.join(),smokePitchExpected),"OpenAL clamped low note");smokePitchTrigger(90);}
            case 2->{if(!smokePitchActual.isDone())return false;arrangementCheck(near(smokePitchActual.join(),smokePitchExpected),"OpenAL clamped high note");smokePitchTrigger(30);}
            case 3->{if(!smokePitchActual.isDone())return false;arrangementCheck(Math.abs(smokePitchActual.join()-smokePitchExpected)<.0001,"OpenAL collapsed lower octave");smokePitchTrigger(102);}
            case 4->{if(!smokePitchActual.isDone())return false;arrangementCheck(near(smokePitchActual.join(),smokePitchExpected),"OpenAL collapsed higher octave");smokePitchTrigger(0);}
            case 5->{if(!smokePitchActual.isDone())return false;arrangementCheck(Math.abs(smokePitchActual.join()-smokePitchExpected)<.0001,"OpenAL clamped MIDI zero");smokePitchTrigger(127);}
            case 6->{if(!smokePitchActual.isDone())return false;arrangementCheck(near(smokePitchActual.join(),smokePitchExpected),"OpenAL clamped MIDI 127");var ordinary=new net.minecraft.client.resources.sounds.SimpleSoundInstance(net.minecraft.resources.ResourceLocation.parse("minecraft:block.note_block.harp"),net.minecraft.sounds.SoundSource.RECORDS,.15f,4,net.minecraft.client.resources.sounds.SoundInstance.createUnseededRandom(),false,0,net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE,0,0,0,true);arrangementCheck(StudioPitchSmoke.calculated(ordinary)==2,"Studio pitch override affected unrelated game sounds");stopPreview();song.patterns.getFirst().notes.getFirst().clear();song.patterns.getFirst().notes.getFirst().add(new Note(0,1,90,127));var provider=ClientMedia.captureExport(song);var snapshot=ProjectStore.copy(song);mediaTask(()->AudioFiles.render(snapshot,provider,root.resolve("extended-high.wav"),"wav",ClientMedia.codec()),rendered->mediaMessage="EXTENDED_AUDIO_OK");}
            case 7->{if(mediaBusy)return false;arrangementCheck(Files.isRegularFile(root.resolve("extended-high.wav")),"actual extended audio render");song.patterns.getFirst().notes.getFirst().add(new Note(4,1,42,127));song.patterns.getFirst().notes.getFirst().add(new Note(8,1,66,127));var snapshot=ProjectStore.copy(song);var provider=ClientMedia.captureBlueprint(snapshot);mediaTask(()->{var baked=ClientMedia.prepareBlueprint(snapshot,provider);var b=dev.musicstudio.blueprint.MusicBlueprint.compile(baked);if(b.pitchLimited()!=0||b.outputs().stream().filter(dev.musicstudio.blueprint.MusicBlueprint.Output::command).count()!=2)throw new IllegalStateException("extended blueprint did not use two sampled notes");Files.writeString(root.resolve("extended-blueprint.json"),ProjectStore.encode(baked));return baked;},baked->mediaMessage="EXTENDED_BUILDING_OK");}
            case 8->{if(mediaBusy)return false;arrangementCheck(mediaMessage.equals("EXTENDED_BUILDING_OK"),"extended building bake failed: "+mediaMessage);song.patterns.getFirst().notes.getFirst().clear();song.patterns.getFirst().notes.getFirst().add(new Note(0,1,90,127));song.patterns.getFirst().notes.get(1).clear();patternMode=true;cursor=0;play();}
            case 9->{if(mediaBusy)return false;arrangementCheck(audio.playing(),"extended Pattern playback");smokePitchActual=CompletableFuture.completedFuture(StudioPitchSmoke.calculated(audio.lastVoice()));smokePitchExpected=4;}
            case 10->{if(!smokePitchActual.isDone())return false;arrangementCheck(near(smokePitchActual.join(),4),"Pattern voice calculation clamps pitch");stop();roll.top=127-90-4;roll.rows=16;lastPianoChannel=lastPianoPattern=-1;}
            case 11->{arrangementClick("piano-key-90");smokePitchActual=StudioPitchSmoke.actual(audio.lastVoice());}
            case 12->{if(!smokePitchActual.isDone())return false;arrangementCheck(near(smokePitchActual.join(),4),"Piano keyboard still clamps extended pitch");stop();try{Files.writeString(root.resolve("extended-pitch-complete.txt"),"Actual OpenAL source pitch verified at MIDI 0/30/42/90/102/127, unaffected ordinary sounds, real resource audio/baked blueprints, Pattern playback and Piano-key auditions.");}catch(Exception ex){throw new IllegalStateException(ex);}}
            default->throw new IllegalStateException("Unknown pitch stage "+step);
        }return true;
    }
    private int smokeFullFrames;
    private boolean smokeFullExportStep(int step,Path root) throws Exception {
        int bar=4*SongProject.PPQ;
        switch(step){
            case 0->{
                stop();closePianoPicker();resetFocus();modal="";piano=rack=false;song=ArrangementSmoke.fixture();song.clips.removeLast();
                var p=song.patterns.getFirst();p.length=bar;p.notes.forEach(List::clear);p.notes.getFirst().add(new Note(0,1,54,127));song.channels.getFirst().volume=1;
                var c=song.clips.getFirst();c.duration=c.contentLength=bar;c.resize(0,64*bar);song.endPpq=128*bar;song.exportStart=song.exportEnd=40*bar;
                pattern=channel=0;selectedAudio="";loadedDraft=true;patternMode=false;cursor=0;clearSelection();undo.clear();redo.clear();changed();
                exportAudio(root.resolve("full-auto.wav"),"wav",false);
            }
            case 1->{
                if(mediaBusy)return false;var pcm=AudioFiles.read(root.resolve("full-auto.wav"));smokeFullFrames=pcm.frames();
                arrangementCheck(pcm.frames()>0&&pcm.seconds()<32&&(pcm.left()[pcm.frames()-1]!=0||pcm.right()[pcm.frames()-1]!=0),"full export kept 128-bar padding or cut the audible tail");
                var snapshot=ProjectStore.copy(song);var provider=ClientMedia.captureBlueprint(snapshot);
                mediaTask(()->{var prepared=ClientMedia.prepareBlueprint(snapshot,provider);int expected=Math.max(ExportRange.contentEnd(snapshot),(int)Math.ceil(smokeFullFrames*SongProject.PPQ*(double)snapshot.bpm/(60*AudioFiles.RATE)));
                    var b=dev.musicstudio.blueprint.MusicBlueprint.compile(prepared);if(prepared.endPpq!=expected||b.period()!=2*(int)Math.ceil(expected*600.0/(SongProject.PPQ*prepared.bpm)))throw new IllegalStateException("Building did not use decoded sound end");
                    Files.writeString(root.resolve("full-auto-blueprint.json"),ProjectStore.encode(prepared));return prepared;},prepared->mediaMessage="FULL_AUTO_BUILDING_OK");
            }
            case 2->{
                if(mediaBusy)return false;arrangementCheck(mediaMessage.equals("FULL_AUTO_BUILDING_OK"),"full building tail preparation failed: "+mediaMessage);
                var prepared=ProjectStore.decode(Files.readString(root.resolve("full-auto-blueprint.json")));var restored=dev.musicstudio.blueprint.BlueprintData.unpack(dev.musicstudio.blueprint.BlueprintData.pack(prepared));
                arrangementCheck(dev.musicstudio.blueprint.MusicBlueprint.compile(restored).period()==dev.musicstudio.blueprint.MusicBlueprint.compile(prepared).period(),"repeated snapshot discarded resolved audio tail");
                song.exportStart=0;song.exportEnd=4*bar;exportAudio(root.resolve("full-auto-selection.wav"),"wav",false);
            }
            case 3->{
                if(mediaBusy)return false;var pcm=AudioFiles.read(root.resolve("full-auto-selection.wav"));arrangementCheck(pcm.frames()==8*AudioFiles.RATE&&pcm.left()[pcm.frames()-1]==0,"manual export range lost trailing rest");
                song.exportStart=song.exportEnd=40*bar;exportMidi(root.resolve("full-auto-midi"));
            }
            case 4->{
                if(mediaBusy)return false;try(var paths=Files.walk(root.resolve("full-auto-midi"))){var files=paths.filter(p->p.toString().endsWith(".mid")).toList();arrangementCheck(files.size()==1&&javax.sound.midi.MidiSystem.getSequence(files.getFirst().toFile()).getTickLength()==SongProject.STEP,"MIDI kept empty Pattern/project bars");}
                arrangementCheck(song.endPpq==128*bar&&song.clips.getFirst().duration==64*bar&&song.exportStart==song.exportEnd,"auto export mutated project or cursors");exportAudio(root.resolve("full-auto.mp3"),"mp3",false);
            }
            case 5->{if(mediaBusy)return false;arrangementCheck(Files.isRegularFile(root.resolve("full-auto.mp3")),"full auto MP3 export");Files.writeString(root.resolve("full-auto-complete.txt"),"Real low-pitch resource audio tail, 128-bar empty project/64-bar padded clip exclusion, PCM end, native building resolved duration and packing, manual rest selection, MIDI note-off endpoint, MP3 export and unchanged project/cursors verified. Audio frames="+smokeFullFrames);}
            default->throw new IllegalStateException("Unknown full export stage "+step);
        }return true;
    }
    private boolean smokeClipGrowthStep(int step,Path root) throws Exception {
        int bar=4*SongProject.PPQ;
        switch(step){
            case 0->{
                stop();closePianoPicker();resetFocus();modal="";song=ArrangementSmoke.fixture();song.clips.removeLast();
                var p=song.patterns.getFirst();p.length=5*bar;var c=song.clips.getFirst();c.duration=c.contentLength=p.length;
                channel=pattern=activeTrack=browserTab=browserScroll=0;selectedAudio="";loadedDraft=true;patternMode=false;cursor=notePastePpq=0;
                piano=rack=false;windows.get("piano").folded=false;if(roll.maximized)roll.toggleMaximize();roll.bounds(230,305,920,461);
                roll.start=64;roll.span=48;roll.top=50;roll.rows=18;lastPianoChannel=lastPianoPattern=0;
                viewport.start=viewport.vertical=0;viewport.bars=10;viewport.trackZoom=1;snap=1;undo.clear();redo.clear();clearSelection();syncSelection();
            }
            case 1->{arrangementEdge(song.clips.getFirst(),false,-1);arrangementCheck(song.clips.getFirst().duration==4*bar,"four-bar crop");piano=true;}
            case 2->{
                lastHit="";click(originX+roll.timeX(84.3)*scale,originY+(roll.pitchY(72)+pianoRowHeight()/2)*scale,0,0);release();
                arrangementCheck(note()!=null&&note().start==84*SongProject.STEP&&song.patterns.getFirst().length==7*bar,"sixth-bar mouse note did not grow Pattern");
                arrangementCheck(song.clips.getFirst().duration==4*bar,"editing source changed manual crop");piano=false;
            }
            case 3->{
                arrangementEdge(song.clips.getFirst(),false,3);var c=song.clips.getFirst();
                arrangementCheck(c.duration==7*bar&&song.clipEvents(c,5*bar,6*bar).stream().anyMatch(e->e.pitch()==72),"expanding crop still hides sixth-bar note");
                c.contentLength=5*bar;arrangementCheck(song.clipEvents(c,5*bar,6*bar).size()==1,"old five-bar source window truncated new note");
            }
            case 4->{
                ProjectFiles.save(root.resolve("five-bar-regression.musicstudio"),store.directory(),song);
                song=ProjectFiles.open(root.resolve("five-bar-regression.musicstudio"),root.resolve("reopened-five-bar"));normalize();
                arrangementCheck(song.events(0,false).stream().anyMatch(e->e.start()==84*SongProject.STEP),"portable reload lost sixth bar");
            }
            case 5->{
                arrangementBrowserDrop(0,1,1);var c=song.clips.getLast();
                arrangementCheck(!c.audio()&&c.duration==7*bar&&song.clipEvents(c,c.start,c.start+c.duration).stream().anyMatch(e->e.start()==bar+84*SongProject.STEP),"long Pattern browser placement lost late notes");
            }
            case 6->{
                var c=song.clips.getLast();int local=84*SongProject.STEP;
                arrangementCheck(PianoTimeline.seek(song,0,local,c.start,c.id).orElseThrow()==c.start+local&&PianoTimeline.position(song,0,c.start+local,c.id)==local,"sixth-bar Piano mapping");
                String placed=ProjectStore.encode(song);undo();arrangementCheck(song.clips.size()==1,"long placement undo");redo();arrangementCheck(placed.equals(ProjectStore.encode(song)),"long placement redo");
                var asset=AudioFiles.importAsset(root.resolve("source.wav"),store.directory(),song,ClientMedia.codec());song.assets.add(asset);
                var source=new Channel("Independent selection","musicstudio:sample/"+asset.id,0x827196);source.sampleId=asset.id;song.audioSources.add(source);selectedAudio=source.id;piano=true;
            }
            case 7->{arrangementClick("piano-channel");}
            case 8->{arrangementClick("piano-pick-"+song.channels.getFirst().id);arrangementCheck(selectedAudio.isEmpty(),"Piano instrument selection left audio placement active");piano=false;}
            case 9->{
                lastHit="";click(originX+timeX(.5)*scale,originY+arrangementY(2)*scale,0,0);release();var c=song.clips.getLast();
                arrangementCheck(!c.audio()&&c.track==2&&c.pattern==0&&c.duration==7*bar,"Playlist click placed old audio instead of edited Pattern");
                song.validate();Files.writeString(root.resolve("clip-growth-complete.txt"),"Mouse crop, sixth-bar Piano draw, restoration beyond five bars, old source window, portable reload, browser placement, Piano seek, undo/redo and audio-to-Pattern placement selection verified.");
            }
            default->throw new IllegalStateException("Unknown clip growth stage "+step);
        }return true;
    }
    private String smokePickerScore,smokePickerAudio,smokePickerClips;private int smokePickerCount;
    private void smokePickerQuery(String value){for(char c:value.toCharArray())character(c);}
    private boolean smokePickerStep(int step,Path root) throws Exception {
        switch(step){
            case 0->{stop();closePianoPicker();song=ArrangementSmoke.fixture();var asset=AudioFiles.importAsset(root.resolve("source.wav"),store.directory(),song,ClientMedia.codec());song.assets.add(asset);var sampler=new Channel("Rack sampler","musicstudio:sample/"+asset.id,0x8a7290);sampler.sampleId=asset.id;song.channels.add(sampler);song.patterns.forEach(p->p.notes.add(new ArrayList<>()));var independent=new Channel("Independent recording","musicstudio:sample/"+asset.id,0x827196);independent.sampleId=asset.id;independent.tunable=false;independent.fadeIn=.1f;independent.gain=.7f;song.audioSources.add(independent);song.clips.add(Clip.audio(1,0,2*4*SongProject.PPQ,4*SongProject.PPQ));while(song.channels.size()<24){song.channels.add(new Channel("Voice "+(song.channels.size()+1),"minecraft:block.note_block.harp",0x577e87));song.patterns.forEach(p->p.notes.add(new ArrayList<>()));}pattern=channel=0;cursor=0;patternMode=false;loadedDraft=true;modal="";piano=true;rack=false;windows.get("piano").folded=false;if(roll.maximized)roll.toggleMaximize();roll.bounds(230,305,920,461);lastPianoChannel=lastPianoPattern=0;roll.start=2;roll.span=16;snap=1;clearSelection();syncSelection();undo.clear();redo.clear();smokePickerScore=ProjectStore.encode(song);smokePickerAudio=ProjectStore.GSON.toJson(independent);smokePickerClips=ProjectStore.GSON.toJson(song.clips);}
            case 1->{arrangementClick("piano-channel");arrangementCheck(pianoPicker.equals("source")&&pianoChoices().size()==25,"source picker does not include all sources/audio");}
            case 2->{arrangementClick("piano-pick-"+song.channels.get(1).id);arrangementCheck(channel==1&&pattern==0&&piano&&near(roll.start,2)&&smokePickerScore.equals(ProjectStore.encode(song)),"direct source selection changed Pattern/data/time viewport");}
            case 3->{arrangementClick("piano-channel");}
            case 4->{scroll(originX+(pickerX+100)*scale,originY+(pickerY+90)*scale,-8,false);arrangementCheck(pianoPickerScroll>10&&near(roll.start,2),"picker scroll leaked to Piano viewport");}
            case 5->{arrangementClick("piano-pick-"+song.channels.get(22).id);arrangementCheck(channel==22&&pianoPicker.isEmpty(),"source below first page not selectable");arrangementClick("piano-channel");smokePickerQuery("Rack sampler");arrangementCheck(pianoChoices().size()==1,"source search");}
            case 6->{arrangementClick("piano-pick-"+song.channels.get(2).id);arrangementCheck(channel==2&&!song.channels.get(channel).sampleId.isEmpty()&&smokePickerScore.equals(ProjectStore.encode(song)),"sampler direct selection");arrangementClick("piano-channel");}
            case 7->{key(GLFW.GLFW_KEY_ESCAPE,0);arrangementCheck(piano&&pianoPicker.isEmpty(),"Escape closed Piano instead of picker");arrangementClick("piano-channel");}
            case 8->{var h=smokeHit("roll-grid");int count=totalNotes();lastHit="";click(originX+(h.x+h.w-20)*scale,originY+(h.y+h.h-20)*scale,0,0);release();arrangementCheck(pianoPicker.isEmpty()&&piano&&totalNotes()==count,"outside click leaked into note creation");arrangementClick("piano-pattern");}
            case 9->{arrangementCheck(pianoChoices().size()==song.patterns.size(),"Pattern picker");arrangementClick("piano-pick-"+song.patterns.get(1).id);arrangementCheck(pattern==1&&channel==2&&piano&&notesContext,"Pattern selection lost instrument or Piano context");arrangementClick("piano-pattern");smokePickerQuery("Melody");key(GLFW.GLFW_KEY_ENTER,0);arrangementCheck(pattern==0&&smokePickerScore.equals(ProjectStore.encode(song)),"Pattern search/Enter changed project");}
            case 10->{lastHit="";click(originX+roll.timeX(3.3)*scale,originY+(roll.pitchY(68)+pianoRowHeight()/2)*scale,0,0);release();arrangementCheck(note()!=null&&notes().size()==1&&song.patterns.getFirst().notes.getFirst().size()==1,"Piano did not edit selected sampler in current Pattern");}
            case 11->{if(mediaBusy)return false;undo();arrangementCheck(smokePickerScore.equals(ProjectStore.encode(song)),"sampler note undo");arrangementClick("piano-channel");smokePickerQuery("Independent recording");}
            case 12->{arrangementClick("piano-pick-"+song.audioSources.getFirst().id);arrangementCheck(song.channels.size()==25&&song.channels.get(channel).originAudioId.equals(song.audioSources.getFirst().id)&&song.patterns.stream().allMatch(p->p.notes.size()==25),"audio could not become Pattern sampler");arrangementCheck(smokePickerAudio.equals(ProjectStore.GSON.toJson(song.audioSources.getFirst()))&&smokePickerClips.equals(ProjectStore.GSON.toJson(song.clips)),"independent audio changed when choosing sampler");smokePickerCount=song.channels.size();arrangementClick("piano-channel");smokePickerQuery("Independent recording");}
            case 13->{arrangementClick("piano-pick-"+song.audioSources.getFirst().id);arrangementCheck(song.channels.size()==smokePickerCount,"audio selection created duplicate instruments");undo();arrangementCheck(smokePickerScore.equals(ProjectStore.encode(song)),"audio instrument addition undo");redo();arrangementCheck(song.channels.size()==smokePickerCount&&song.patterns.stream().allMatch(p->p.notes.size()==smokePickerCount),"audio instrument addition redo");arrangementClick("piano-maximize");}
            case 14->{arrangementCheck(roll.maximized,"maximize for picker");arrangementClick("piano-channel");}
            case 15->{key(GLFW.GLFW_KEY_PAGE_UP,0);int target=pianoPickerIndex;key(GLFW.GLFW_KEY_ENTER,0);arrangementCheck(channel==target&&piano&&roll.maximized&&pianoPicker.isEmpty(),"maximized keyboard picker");arrangementClick("piano-channel");smokePickerQuery("not-a-real-source-73498");}
            case 16->{arrangementCheck(pianoChoices().isEmpty(),"empty search");int before=channel;key(GLFW.GLFW_KEY_ENTER,0);arrangementCheck(channel==before&&!pianoPicker.isEmpty(),"empty search selected a source");key(GLFW.GLFW_KEY_ESCAPE,0);arrangementCheck(piano&&roll.maximized,"empty search Escape");arrangementClick("piano-channel");}
            case 17->{smokeHitDrag("piano-picker-scroll",0,100);arrangementCheck(pianoPickerScroll>0,"dropdown scrollbar drag");String before=ProjectStore.encode(song);key(GLFW.GLFW_KEY_DELETE,0);arrangementCheck(before.equals(ProjectStore.encode(song)),"Delete leaked through dropdown");key(GLFW.GLFW_KEY_ESCAPE,0);arrangementClick("piano-maximize");}
            case 18->{arrangementClick("piano-pattern");key(GLFW.GLFW_KEY_DOWN,0);key(GLFW.GLFW_KEY_ENTER,0);arrangementCheck(pattern==1&&piano,"Pattern keyboard switch");}
            case 19->{choosePianoEntry(new PianoChoice(song.channels.get(2).id,"Rack sampler","sampler",0,0,""));arrangementClick("piano-sound");arrangementCheck(modal.equals("sample"),"selected sampler controls did not open its audio editor");modal="";}
            case 20->{if(mediaBusy)return false;try{Files.writeString(root.resolve("piano-picker-complete.txt"),"Native source/Pattern dropdowns; all instrument, sampler and independent audio entries; search, wheel, scrollbar, keyboard, empty results, outside-click/Escape isolation, selected sampler notes and audio controls, independent sample clone/reuse/undo, maximize and time-view preservation verified.");}catch(Exception ex){throw new IllegalStateException(ex);}}
            default->throw new IllegalStateException("Unknown picker stage "+step);
        }return true;
    }
    private void smokeTypeField(String id,String value){arrangementClick(id);key(GLFW.GLFW_KEY_A,GLFW.GLFW_MOD_CONTROL);for(char c:value.toCharArray())character(c);key(GLFW.GLFW_KEY_ENTER,0);}
    private boolean smokeGrowthStep(int step,Path root){
        int bar=4*SongProject.PPQ;
        switch(step){
            case 0->{stop();song=ArrangementSmoke.fixture();song.patterns.getFirst().notes.forEach(List::clear);song.patterns.getFirst().length=2*bar;song.clips.clear();song.clips.add(Clip.pattern(0,0,0,2*bar));song.clips.add(Clip.pattern(1,0,4*bar,2*bar));pattern=channel=activeTrack=0;patternMode=false;piano=true;rack=false;modal="";windows.get("piano").folded=false;if(roll.maximized)roll.toggleMaximize();roll.bounds(230,305,920,461);roll.start=0;roll.span=32;roll.top=50;roll.rows=16;lastPianoChannel=channel;lastPianoPattern=pattern;clearSelection();syncSelection();cursor=notePastePpq=0;snap=1;loadedDraft=true;undo.clear();redo.clear();smokeTransportScore=ProjectStore.encode(song);}
            case 1->{scroll(originX+(roll.gridX()+roll.gridWidth()/2)*scale,originY+(roll.horizontalY()+8)*scale,-12,false);arrangementCheck(roll.span>rollLength()*4,"horizontal scrollbar zoom-out clamped to Pattern");arrangementClick("roll-page-next");}
            case 2->{arrangementCheck(roll.start>rollLength(),"Piano pan stopped at Pattern end");smokeHitDrag("roll-scroll-h",80,0);arrangementCheck(roll.start>rollLength()&&smokeTransportScore.equals(ProjectStore.encode(song)),"navigation changed Pattern data");double span=roll.span;scroll(originX+(roll.gridX()+roll.gridWidth()/2)*scale,originY+(roll.gridY()+20)*scale,20,true);arrangementCheck(roll.span<span,"deep Ctrl zoom");roll.start=32;roll.span=32;}
            case 3->{lastHit="";click(originX+roll.timeX(50.3)*scale,originY+(roll.pitchY(68)+pianoRowHeight()/2)*scale,0,0);release();var n=note();arrangementCheck(n!=null&&n.start==50*SongProject.STEP&&n.duration==SongProject.STEP&&n.velocity==127,"drawing beyond Pattern lost vanilla defaults");interactionId=n.id;arrangementCheck(song.patterns.getFirst().length==5*bar&&song.clips.stream().allMatch(c->c.duration==5*bar)&&song.endPpq==9*bar,"automatic Pattern/instance growth");}
            case 4->{String grown=ProjectStore.encode(song);undo();arrangementCheck(song.patterns.getFirst().length==2*bar&&song.clips.stream().allMatch(c->c.duration==2*bar),"growth undo");redo();arrangementCheck(grown.equals(ProjectStore.encode(song)),"growth redo");chooseNote(interactionId);roll.start=48;roll.span=64;}
            case 5->{smokeHitDrag("note-"+interactionId,roll.gridWidth()/64*40,0);arrangementCheck(note().start==90*SongProject.STEP&&song.patterns.getFirst().length==7*bar,"note drag still capped at old Pattern end");roll.start=80;roll.span=80;}
            case 6->{var n=note();float px=roll.timeX((n.start+n.duration)/(double)SongProject.STEP)-2,py=roll.pitchY(n.pitch)+pianoRowHeight()/2;lastHit="";click(originX+px*scale,originY+py*scale,0,0);arrangementCheck(drag!=null&&drag.resize,"note duration resize hit");drag(originX+(px+roll.gridWidth()/80*40)*scale,originY+py*scale);release();arrangementCheck(n.duration==41*SongProject.STEP&&song.patterns.getFirst().length==10*bar,"note resize still capped at Pattern end");}
            case 7->{smokeTypeField("duration","80");arrangementCheck(note().duration==80*SongProject.STEP&&song.patterns.getFirst().length==12*bar,"numeric duration growth");notesContext=true;copySelection(false);notePastePpq=500*bar;pasteSelection();arrangementCheck(song.patterns.getFirst().length==506*bar&&song.endPpq==510*bar,"paste beyond old 256-bar maximum");smokeGrowthBefore=notes().size();song.validate();}
            case 8->{String grown=ProjectStore.encode(song);undo();arrangementCheck(song.patterns.getFirst().length==12*bar&&notes().size()==smokeGrowthBefore-1,"paste growth undo");redo();arrangementCheck(grown.equals(ProjectStore.encode(song)),"paste growth redo");try{Files.writeString(root.resolve("auto-grown.json"),grown);arrangementCheck(grown.equals(ProjectStore.encode(ProjectStore.decode(grown))),"long Pattern save/open");}catch(Exception ex){throw new IllegalStateException(ex);}smokeTransportScore=grown;}
            case 9->{scroll(originX+(roll.gridX()+roll.gridWidth()/2)*scale,originY+(roll.horizontalY()+8)*scale,-1000,false);arrangementCheck(roll.span==PianoView.MAX_TIME,"zoom-out precision range");scroll(originX+(roll.gridX()+roll.gridWidth()/2)*scale,originY+(roll.gridY()+20)*scale,1000,true);arrangementCheck(roll.span==PianoView.MIN_SPAN,"zoom-in PPQ precision");arrangementCheck(smokeTransportScore.equals(ProjectStore.encode(song)),"extreme zoom mutated score");roll.start=100;roll.span=16;}
            case 10->{smokeHitDrag("roll-scroll-h-end",roll.gridWidth()*2,0);arrangementCheck(roll.span>rollLength(),"thumb edge zoom still capped at content");arrangementCheck(smokeTransportScore.equals(ProjectStore.encode(song)),"thumb edge zoom changed song");roll.start=80;roll.span=32;chooseNote(interactionId);}
            case 11->{var n=note();float px=roll.timeX(92),py=roll.pitchY(n.pitch)+pianoRowHeight()/2;lastHit="";click(originX+px*scale,originY+py*scale,0,0);arrangementCheck(drag!=null&&drag.type.equals("note")&&!drag.resize,"autopan note hit");smokeTransportPosition=roll.start;drag(originX+(roll.gridX()+roll.gridWidth()+15)*scale,originY+py*scale);}
            case 12->{arrangementCheck(roll.start>smokeTransportPosition&&note().start>90*SongProject.STEP,"note edge drag failed to pan past viewport");release();undo();arrangementCheck(smokeTransportScore.equals(ProjectStore.encode(song)),"autopan note drag undo did not restore Pattern and clips");roll.start=590*16;roll.span=256;}
            case 13->{smokeSeek(true,600*16+1.3,600*16+2.3);arrangementCheck(notePastePpq==600*bar+2*SongProject.STEP,"Piano ruler still capped at Pattern length");arrangementCheck(smokeTransportScore.equals(ProjectStore.encode(song)),"ruler navigation extended musical content");}
            case 14->{try{Files.writeString(root.resolve("pattern-growth-complete.txt"),"Piano scroll/zoom beyond content, deep zoom to PPQ, dynamic scrollbar extent/edge zoom, note draw/move/resize/numeric duration/paste automatic growth, multiple linked instances, 500-bar project save, undo/redo, mouse edge autopan and ruler beyond content verified.");}catch(Exception ex){throw new IllegalStateException(ex);}}
            default->throw new IllegalStateException("Unknown Pattern growth stage "+step);
        }return true;
    }
    private void smokeSeek(boolean pianoRuler,double steps,double endSteps){
        Hit h=smokeHit(pianoRuler?"note-seek-ruler":"playlist-seek");float y=h.y+(pianoRuler?8:10);
        float x=pianoRuler?roll.timeX(steps):timeX(steps/16);lastHit="";click(originX+x*scale,originY+y*scale,0,0);
        float end=pianoRuler?roll.timeX(endSteps):timeX(endSteps/16);drag(originX+end*scale,originY+y*scale);release();
    }
    private boolean smokeTransportStep(int step,Path root){
        switch(step){
            case 0->{stop();song=ArrangementSmoke.fixture();song.patterns.getFirst().length=4*4*SongProject.PPQ;song.clips.getFirst().duration=song.clips.getFirst().contentLength=song.patterns.getFirst().length;pattern=channel=0;patternMode=false;cursor=notePastePpq=0;selectedClip=song.clips.getFirst().id;clearSelection();selectedClip=song.clips.getFirst().id;loadedDraft=true;modal="";piano=true;rack=false;windows.get("piano").folded=false;if(roll.maximized)roll.toggleMaximize();roll.bounds(230,305,920,461);roll.start=0;roll.span=32;viewport.start=0;viewport.bars=10;snap=1;syncSelection();smokeTransportScore=ProjectStore.encode(song);}
            case 1->{smokeSeek(true,4.3,5.3);arrangementCheck(cursor==5*SongProject.STEP&&notePastePpq==5*SongProject.STEP&&pianoPlayhead()==cursor&&!audio.playing(),"Piano stopped ruler did not set real start");smokeTransportStart=cursor;arrangementClick("piano-play");}
            case 2->{if(mediaBusy)return false;arrangementCheck(audio.playing()&&cursor>smokeTransportStart&&pianoPlayhead()==cursor,"Piano green line not following actual playback");smokeTransportPosition=cursor;}
            case 3->{arrangementCheck(cursor>smokeTransportPosition,"Piano playhead did not advance");arrangementClick("piano-stop");arrangementCheck(!audio.playing()&&cursor==smokeTransportStart&&pianoPlayhead()==smokeTransportStart,"Piano Stop did not return to stopped start");arrangementClick("piano-play");}
            case 4->{if(mediaBusy)return false;arrangementCheck(audio.playing(),"Piano restart");smokeSeek(true,9.3,10.3);}
            case 5->{if(mediaBusy)return false;arrangementCheck(audio.playing()&&cursor>=10*SongProject.STEP&&playbackOrigin==smokeTransportStart,"Piano playing seek did not continue/preserved wrong origin");key(GLFW.GLFW_KEY_SPACE,0);arrangementCheck(cursor==smokeTransportStart&&!audio.playing(),"Space did not return Piano to origin");piano=false;}
            case 6->{smokeSeek(false,20.3,21.3);arrangementCheck(cursor==21*SongProject.STEP&&!audio.playing(),"Playlist stopped seek");smokeTransportStart=cursor;arrangementClick("play");}
            case 7->{if(mediaBusy)return false;arrangementCheck(audio.playing()&&cursor>smokeTransportStart,"Playlist playhead did not advance");smokeSeek(false,28.3,29.3);}
            case 8->{if(mediaBusy)return false;arrangementCheck(audio.playing()&&cursor>=29*SongProject.STEP&&playbackOrigin==smokeTransportStart,"Playlist playing drag did not continue");arrangementClick("stop");arrangementCheck(!audio.playing()&&cursor==smokeTransportStart,"Playlist Stop lost original start");arrangementClick("play");}
            case 9->{if(mediaBusy)return false;key(GLFW.GLFW_KEY_SPACE,0);arrangementCheck(!audio.playing()&&cursor==smokeTransportStart,"Playlist Space stop lost original start");arrangementClick("stop");arrangementCheck(cursor==smokeTransportStart,"idle Stop moved the green start");arrangementCheck(smokeTransportScore.equals(ProjectStore.encode(song)),"play/seek changed song data");song.clips.clear();song.clips.add(Clip.pattern(0,0,2*4*SongProject.PPQ,song.patterns.getFirst().length));selectedClip=song.clips.getFirst().id;cursor=2*4*SongProject.PPQ;piano=true;}
            case 10->{smokeSeek(true,4.3,5.3);arrangementCheck(!patternMode&&cursor==2*4*SongProject.PPQ+5*SongProject.STEP&&pianoPlayhead()==5*SongProject.STEP,"Piano seek ignored placed clip origin");smokeTransportStart=cursor;arrangementClick("piano-play");}
            case 11->{if(mediaBusy)return false;arrangementCheck(audio.playing()&&near(pianoPlayhead(),cursor-2*4*SongProject.PPQ),"placed clip green line does not map to Playlist");arrangementClick("piano-stop");arrangementCheck(cursor==smokeTransportStart,"placed clip stop origin");song.clips.clear();}
            case 12->{smokeSeek(true,7.3,8.3);arrangementCheck(patternMode&&cursor==8*SongProject.STEP,"unplaced Pattern seek must audition Pattern");smokeTransportStart=cursor;arrangementClick("piano-play");}
            case 13->{if(mediaBusy)return false;arrangementCheck(audio.playing()&&cursor>smokeTransportStart&&pianoPlayhead()==cursor,"Pattern playhead");smokeSeek(true,12.3,13.3);}
            case 14->{if(mediaBusy)return false;arrangementCheck(audio.playing()&&cursor>=13*SongProject.STEP,"Pattern seek");arrangementClick("piano-stop");arrangementCheck(cursor==smokeTransportStart,"Pattern stop origin");song.loop=false;song.patterns.getFirst().length=16*SongProject.STEP;snap=1;}
            case 15->{roll.start=0;roll.span=16;smokeSeek(true,13.3,14.3);smokeTransportStart=cursor;arrangementClick("piano-play");}
            case 16->{if(audio.playing()||mediaBusy)return false;arrangementCheck(!transportActive&&cursor==smokeTransportStart,"natural finish did not return to origin");song.loop=true;arrangementClick("piano-play");}
            case 17->{if(mediaBusy)return false;arrangementCheck(audio.playing()&&cursor<smokeTransportStart,"loop did not wrap green line");arrangementClick("piano-stop");arrangementCheck(cursor==smokeTransportStart,"loop stop origin");song.loop=false;song.patterns.getFirst().length=64*SongProject.STEP;roll.start=0;roll.span=32;song.patterns.getFirst().notes.getFirst().clear();song.patterns.getFirst().notes.getFirst().add(new Note(0,32,66,127));cursor=0;arrangementClick("piano-play");}
            case 18->{if(mediaBusy)return false;arrangementCheck(audio.playing(),"held-note playback");smokeSeek(true,8.3,9.3);arrangementClick("piano-stop");arrangementCheck(!transportActive&&cursor==0,"stop during async seek");}
            case 19->{if(mediaBusy)return false;arrangementCheck(!audio.playing()&&!transportActive&&cursor==0,"stale async audio resumed after Stop");song.clips.add(Clip.pattern(0,0,0,64*SongProject.STEP));piano=false;patternMode=false;cursor=0;arrangementClick("play");}
            case 20->{if(mediaBusy)return false;smokeSeek(false,8.3,9.3);arrangementClick("stop");arrangementCheck(!transportActive&&cursor==0,"Playlist stop during async seek");}
            case 21->{if(mediaBusy)return false;arrangementCheck(!audio.playing()&&!transportActive&&cursor==0,"stale Playlist seek resumed after Stop");piano=true;song.patterns.getFirst().notes.getFirst().clear();song.patterns.getFirst().notes.getFirst().add(new Note(0,1,66,127));song.patterns.getFirst().length=64*SongProject.STEP;snap=4;roll.start=16;roll.span=16;}
            case 22->{smokeSeek(true,21.3,25.3);arrangementCheck(cursor==24*SongProject.STEP&&pianoPlayhead()==cursor,"scrolled Piano ruler ignored beat snap");smokeTransportStart=cursor;arrangementClick("piano-play");}
            case 23->{if(mediaBusy)return false;arrangementCheck(audio.playing()&&cursor>smokeTransportStart,"scrolled Piano playback");arrangementClick("piano-stop");arrangementCheck(cursor==smokeTransportStart,"scrolled Piano return");}
            case 24->{try{Files.writeString(root.resolve("transport-complete.txt"),"Playlist and Piano: stopped start, snapped mouse ruler/green-head seeks, moving playheads, live seek/resume, original start restored by Stop/Space, positioned clips, unplaced Patterns, natural finish, loop wrap, async cancellation and scrolled/zoomed beat snap verified.");}catch(Exception ex){throw new IllegalStateException(ex);}}
            default->throw new IllegalStateException("Unknown transport stage "+step);
        }return true;
    }
    boolean smokeArrangementStep(int step,Path root){
        try{switch(step){
            case 0->{song=ArrangementSmoke.fixture();channel=pattern=activeTrack=0;browserTab=0;loadedDraft=true;dirty=false;cursor=0;viewport.start=0;viewport.bars=10;viewport.vertical=0;rack=true;piano=false;modal="";undo.clear();redo.clear();clearSelection();normalize();changed();}
            case 1->{arrangementClick("rack-add-source");arrangementCheck(modal.equals("add-source"),"rack plus missing source chooser");}
            case 2->{arrangementClick("add-event-source");arrangementCheck(song.channels.size()==3&&song.tracks.size()==8&&song.clips.size()==2,"adding source created lane or clip");}
            case 3->{var sound=SoundCatalog.INSTANCE.find("minecraft:block.note_block.harp").orElseThrow();bind(sound);arrangementCheck(SoundCatalog.INSTANCE.instruments().size()>=22,"note-block catalog");channel=0;selectPattern(0);modal="";}
            case 4->{arrangementClick("step-0-1");arrangementClick("step-1-1");arrangementCheck(song.clipEvents(song.clips.getFirst(),0,4*SongProject.PPQ).size()==4,"one clip did not include both edited sources");}
            case 5->{rack=false;browserTab=0;}
            case 6->{arrangementBrowserDrop(0,1,2);arrangementCheck(song.clips.size()==3&&song.clips.getLast().pattern==0&&song.clips.getLast().track==1,"Pattern browser drop");}
            case 7->{arrangementBrowserDrop(1,1,4);arrangementCheck(song.clips.size()==4&&song.clips.getLast().pattern==1&&song.clips.getLast().track==1,"mixed Patterns on one lane");}
            case 8->{Clip c=song.clips.stream().filter(v->v.track==1&&v.pattern==0).findFirst().orElseThrow();arrangementClip=c.id;var h=smokeHit("clip-"+c.id);lastHit="";click(originX+(h.x+h.w/2)*scale,originY+(h.y+h.h/2)*scale,0,0);drag(originX+(h.x+h.w/2)*scale,originY+arrangementY(3)*scale);release();arrangementCheck(c.track==3&&c.pattern==0,"cross-lane clip drag changed content");}
            case 9->{snap=4;Clip c=arrangementClip();arrangementBefore=arrangementTimes(c);int duration=c.duration;arrangementEdge(c,false,.3);arrangementCheck(c.duration==duration+SongProject.PPQ&&arrangementTimes(c).equals(arrangementBefore),"right padding repeated source");}
            case 10->{Clip c=arrangementClip();int start=c.start,duration=c.duration;arrangementEdge(c,true,-.3);arrangementCheck(c.start==start-SongProject.PPQ&&c.duration==duration+SongProject.PPQ&&arrangementTimes(c).equals(arrangementBefore),"left padding moved notes");String resized=ProjectStore.encode(song);key(GLFW.GLFW_KEY_Z,GLFW.GLFW_MOD_CONTROL);key(GLFW.GLFW_KEY_Y,GLFW.GLFW_MOD_CONTROL);arrangementCheck(resized.equals(ProjectStore.encode(song)),"padding undo/redo");}
            case 11->{Clip c=arrangementClip();notesContext=false;clipSelection.clear();clipSelection.add(c.id);selectedClip=c.id;key(GLFW.GLFW_KEY_C,GLFW.GLFW_MOD_CONTROL);int count=song.clips.size();cursor=5*PlaylistView.BAR;key(GLFW.GLFW_KEY_V,GLFW.GLFW_MOD_CONTROL);arrangementCheck(song.clips.size()==count+1&&song.clips.getLast().pattern==c.pattern&&song.clips.getLast().contentOffset==c.contentOffset,"linked padded paste");key(GLFW.GLFW_KEY_X,GLFW.GLFW_MOD_CONTROL);arrangementCheck(song.clips.size()==count,"cut");key(GLFW.GLFW_KEY_Z,GLFW.GLFW_MOD_CONTROL);arrangementCheck(song.clips.size()==count+1,"cut undo");key(GLFW.GLFW_KEY_Y,GLFW.GLFW_MOD_CONTROL);arrangementCheck(song.clips.size()==count,"cut redo");}
            case 12->{arrangementCount=song.clips.size();filesDropped(List.of(root.resolve("source.wav")),originX+timeX(6.05)*scale,originY+arrangementY(1)*scale);}
            case 13->{if(mediaBusy)return false;arrangementCheck(song.audioSources.size()==1&&song.channels.size()==3&&song.patterns.size()==2&&song.clips.size()==arrangementCount+1,"independent audio created a Pattern or rack source");Clip c=song.clips.stream().filter(Clip::audio).findFirst().orElseThrow();var h=smokeHit("clip-"+c.id);lastHit="";for(int i=0;i<2;i++){click(originX+(h.x+h.w/2)*scale,originY+(h.y+h.h/2)*scale,0,0);release();}arrangementCheck(modal.equals("sample"),"audio double-click did not open sample editor");}
            case 14->{var h=smokeHit("sample-gain");lastHit="";click(originX+(h.x+h.w*.5f)*scale,originY+(h.y+14)*scale,0,0);release();arrangementCheck(song.audioSources.getFirst().gain>1.9f&&song.channels.get(0).gain==1,"audio gain edited Pattern source");key(GLFW.GLFW_KEY_Z,GLFW.GLFW_MOD_CONTROL);arrangementCheck(song.audioSources.getFirst().gain==1,"audio effect undo");key(GLFW.GLFW_KEY_Y,GLFW.GLFW_MOD_CONTROL);modal="";arrangementCount=song.clips.size();importAudio(root.resolve("source.wav"),true,0,0);}
            case 15->{if(mediaBusy)return false;arrangementCheck(song.channels.size()==4&&song.patterns.size()==2&&song.clips.size()==arrangementCount&&song.audioSources.size()==1,"sampler import added Pattern/clip/track");selectPattern(0);channel=3;rack=true;}
            case 16->{arrangementClick("step-3-2");arrangementCheck(song.patterns.get(0).notes.get(3).size()==1&&song.events(0,true).stream().anyMatch(e->e.channel()==3),"sampler not MIDI controlled");rack=false;browserTab=0;channel=0;}
            case 17->{Clip c=song.clips.stream().filter(Clip::audio).findFirst().orElseThrow();arrangementEdge(c,true,.3);arrangementCheck(song.clipEvents(c,c.start,c.start+c.duration).getFirst().offset()==SongProject.PPQ,"independent audio trim lost source offset");}
            case 18->{var h=smokeHit("track-height-bottom-0");lastHit="";click(originX+(h.x+20)*scale,originY+(h.y+2)*scale,0,0);drag(originX+(h.x+20)*scale,originY+(h.y+42)*scale);release();arrangementCheck(song.tracks.get(0).displayHeight==105&&song.channels.get(0).displayHeight==65,"lane size affected source");int sources=song.channels.size();newTrack();arrangementCheck(song.tracks.size()==9&&song.channels.size()==sources,"new lane added sound source");undo();viewport.vertical=0;}
            case 19->{arrangementClick("mute-1");arrangementCheck(song.tracks.get(1).muted&&song.events(0,false).stream().noneMatch(e->e.track()==1)&&song.channels.stream().noneMatch(c->c.muted),"lane mute confused with source mute");}
            case 20->{exportAudio(root.resolve("full.wav"),"wav",false);}
            case 21->{if(mediaBusy)return false;arrangementCheck(Files.isRegularFile(root.resolve("full.wav")),"WAV failed: "+mediaMessage);var pcm=AudioFiles.read(root.resolve("full.wav"));arrangementCheck(pcm.seconds()<16&&(pcm.left()[pcm.frames()-1]!=0||pcm.right()[pcm.frames()-1]!=0),"WAV kept silent project tail");exportAudio(root.resolve("full.mp3"),"mp3",false);}
            case 22->{if(mediaBusy)return false;arrangementCheck(Files.isRegularFile(root.resolve("full.mp3")),"MP3 failed: "+mediaMessage);exportAudio(root.resolve("full.ogg"),"ogg",false);}
            case 23->{if(mediaBusy)return false;arrangementCheck(Files.isRegularFile(root.resolve("full.ogg")),"OGG failed: "+mediaMessage);exportMidi(root);}
            case 24->{if(mediaBusy)return false;try(var paths=Files.walk(root)){long count=paths.filter(p->p.toString().endsWith(".mid")).count();long expected=song.events(0,false).stream().filter(e->e.channel()<song.channels.size()).map(Event::track).distinct().count();arrangementCheck(count==expected,"MIDI did not follow active Playlist lanes");}edit(()->song.tracks.get(1).muted=false);Clip c=song.clips.stream().filter(Clip::audio).findFirst().orElseThrow();cursor=c.start+.75;play();}
            case 25->{if(mediaBusy)return false;arrangementCheck(audio.playing()&&ArrangementSmoke.sampleTriggers>0,"trimmed audio preview did not start: "+mediaMessage);stopPreview();var snapshot=ProjectStore.copy(song);snapshot.commandSamples=true;var provider=ClientMedia.captureBlueprint(snapshot);mediaTask(()->{ProjectFiles.save(root.resolve("mixed.musicstudio"),store.directory(),snapshot);var opened=ProjectFiles.open(root.resolve("mixed.musicstudio"),root.resolve("opened"));if(!ProjectStore.encode(opened).equals(ProjectStore.encode(snapshot)))throw new IllegalStateException("Portable arrangement changed references");var prepared=ClientMedia.prepareBlueprint(snapshot,provider);var building=dev.musicstudio.blueprint.MusicBlueprint.compile(prepared);if(building.notes().size()!=snapshot.events(0,false).size()||building.outputs().stream().filter(dev.musicstudio.blueprint.MusicBlueprint.Output::command).count()<2)throw new IllegalStateException("Building source or audio clip missing");Files.writeString(root.resolve("prepared-blueprint.json"),ProjectStore.encode(prepared));return building.notes().size();},count->mediaMessage="ARRANGEMENT_VERIFIED_"+count);}
            case 26->{if(mediaBusy)return false;arrangementCheck(mediaMessage.startsWith("ARRANGEMENT_VERIFIED_"),"building/portable failed: "+mediaMessage);requestDeleteChannel(0);}
            case 27->{arrangementCount=song.clips.size();arrangementClick("confirm-remove-channel");arrangementCheck(song.channels.size()==3&&song.tracks.size()==8&&song.clips.size()==arrangementCount,"source deletion removed lane/Pattern clip");undo();arrangementCheck(song.channels.size()==4&&song.clips.size()==arrangementCount,"source deletion undo");}
            case 28->{openTrackSettings(1);}
            case 29->{arrangementClick("track-name");key(GLFW.GLFW_KEY_A,GLFW.GLFW_MOD_CONTROL);for(char ch:"混合编曲 / Mixed".toCharArray())character(ch);key(GLFW.GLFW_KEY_ENTER,0);arrangementCheck(song.tracks.get(1).name.equals("混合编曲 / Mixed")&&song.channels.get(1).name.equals("Bass"),"track rename affected sound source");}
            case 30->{arrangementClick("track-color");key(GLFW.GLFW_KEY_A,GLFW.GLFW_MOD_CONTROL);for(char ch:"#AA7744".toCharArray())character(ch);key(GLFW.GLFW_KEY_ENTER,0);arrangementCheck(song.tracks.get(1).color==0xaa7744,"track color field");modal="";viewport.start=0;viewport.bars=10;viewport.vertical=0;browserTab=0;rack=false;piano=false;}
            case 31->{arrangementCheck(clipWaves.get(song,song.audioSources.getFirst())!=null,"real PCM clip waveform unavailable");runTimelineSmokeAssertions();}
            case 32->{StudioText.testLanguage("en_us");rack=true;}
            case 33->{song.validate();}
            case 34->{rack=false;piano=false;modal="";browserTab=0;browserScroll=0;interactionBefore=ProjectStore.encode(song);}
            case 35->{var h=smokeHit("browser-item-0");click(originX+(h.x+20)*scale,originY+(h.y+10)*scale,1,0);release();arrangementCheck(modal.equals("pattern-settings"),"Pattern context settings missing");}
            case 36->{int count=song.clips.size(),removed=(int)song.clips.stream().filter(c->!c.audio()&&c.pattern==0).count();arrangementClick("pattern-delete");arrangementCheck(song.patterns.size()==1&&song.clips.size()==count-removed&&song.clips.stream().anyMatch(Clip::audio),"Pattern delete removed unrelated clips");undo();arrangementCheck(interactionBefore.equals(ProjectStore.encode(song)),"Pattern delete undo");redo();arrangementCheck(song.patterns.size()==1,"Pattern delete redo");undo();rack=true;channel=0;}
            case 37->{var h=smokeHit("channel-0");click(originX+(h.x+20)*scale,originY+(h.y+10)*scale,1,0);release();arrangementCheck(modal.equals("channel-settings"),"rack right-click missing");interactionNotes=ProjectStore.GSON.toJson(song.patterns);interactionId=song.channels.get(0).id;}
            case 38->{arrangementClick("channel-replace-event");arrangementCheck(modal.equals("sounds"),"right-click instrument replacement browser");}
            case 39->{bind(SoundCatalog.INSTANCE.find("minecraft:block.note_block.bass").orElseThrow());arrangementCheck(song.channels.get(0).sound.equals("minecraft:block.note_block.bass")&&interactionNotes.equals(ProjectStore.GSON.toJson(song.patterns)),"instrument replacement changed notes");undo();redo();arrangementCheck(song.channels.get(0).id.equals(interactionId),"instrument replacement changed identity");openChannelSettings(0);}
            case 40->{arrangementCheck(hits.stream().anyMatch(h->h.id.equals("channel-replace-audio")),"audio replacement action missing");replaceAudio(root.resolve("source.wav"),interactionId);}
            case 41->{if(mediaBusy)return false;arrangementCheck(!song.channels.get(0).sampleId.isEmpty()&&song.channels.get(0).id.equals(interactionId)&&interactionNotes.equals(ProjectStore.GSON.toJson(song.patterns)),"audio replacement lost note links");rack=false;piano=true;modal="";roll.bounds(230,200,920,461);channel=0;selectPattern(0);notesContext=true;}
            case 42->{smokeHitDrag("piano-edge-left",-30,0);arrangementCheck(near(roll.x,200)&&near(roll.width,950),"window left edge resize: "+roll.x+", "+roll.width);}
            case 43->{smokeHitDrag("piano-edge-right",40,0);arrangementCheck(near(roll.width,990),"window right edge resize");}
            case 44->{smokeHitDrag("piano-edge-top",0,-30);arrangementCheck(near(roll.y,170)&&near(roll.height,491),"window top edge resize");}
            case 45->{smokeHitDrag("piano-edge-bottom",0,40);arrangementCheck(near(roll.height,531),"window bottom edge resize");}
            case 46->{smokeHitDrag("piano-corner-br",20,20);arrangementCheck(near(roll.width,1010)&&near(roll.height,551),"window corner resize");}
            case 47->{arrangementClick("piano-maximize");arrangementCheck(roll.maximized&&roll.x==0&&roll.y==0&&roll.width==1280&&roll.height==806,"Piano maximize");}
            case 48->{arrangementClick("piano-maximize");arrangementCheck(!roll.maximized&&near(roll.x,200)&&near(roll.y,170)&&near(roll.width,1010)&&near(roll.height,551),"Piano restore bounds");roll.start=0;roll.span=8;roll.top=45;roll.rows=12;roll.clamp(rollLength());}
            case 49->{smokeHitDrag("roll-scroll-h",80,0);arrangementCheck(roll.start>0,"horizontal scrollbar pan");}
            case 50->{double before=roll.span;scroll(originX+(roll.gridX()+roll.gridWidth()/2)*scale,originY+(roll.horizontalY()+8)*scale,2,false);arrangementCheck(roll.span<before,"horizontal scrollbar wheel zoom");}
            case 51->{double before=roll.span;smokeHitDrag("roll-scroll-h-end",40,0);arrangementCheck(roll.span>before,"horizontal thumb edge zoom");}
            case 52->{smokeHitDrag("roll-scroll-v",0,45);arrangementCheck(roll.top>45,"vertical scrollbar pan");}
            case 53->{double before=roll.rows;scroll(originX+(roll.verticalX()+8)*scale,originY+(roll.gridY()+roll.gridHeight()/2)*scale,2,false);arrangementCheck(roll.rows<before,"vertical scrollbar wheel zoom");}
            case 54->{double before=roll.rows;smokeHitDrag("roll-scroll-v-end",0,20);arrangementCheck(roll.rows>before,"vertical thumb edge zoom");}
            case 55->{double top=roll.top,time=roll.span;scroll(originX+(roll.gridX()+100)*scale,originY+(roll.gridY()+100)*scale,-2,false);arrangementCheck(roll.top>top&&roll.span==time,"ordinary Piano wheel leaked to Playlist");scroll(originX+(roll.gridX()+100)*scale,originY+(roll.gridY()+100)*scale,2,true);arrangementCheck(roll.span<time,"Ctrl wheel time zoom");interactionBaseline=ArrangementSmoke.sampleTriggers;}
            case 56->{int pitch=roll.pitchAt(roll.gridY()+roll.gridHeight()/2);arrangementClick("piano-key-"+pitch);}
            case 57->{if(mediaBusy)return false;arrangementCheck(ArrangementSmoke.sampleTriggers>interactionBaseline,"Piano key did not trigger actual sample audio");stopPreview();roll.start=2;roll.span=8;roll.top=52;roll.rows=16;roll.clamp(rollLength());}
            case 58->{int count=notes().size();float x=roll.timeX(3.3),y=roll.pitchY(68)+pianoRowHeight()/2;lastHit="";click(originX+x*scale,originY+y*scale,0,0);release();arrangementCheck(notes().size()==count+1&&note()!=null&&note().start==3*SongProject.STEP&&note().pitch==68,"drawing note after pan/zoom/resize");interactionId=note().id;}
            case 59->{var n=notes().stream().filter(v->v.id.equals(interactionId)).findFirst().orElseThrow();int start=n.start,pitch=n.pitch;smokeHitDrag("note-"+n.id,roll.gridWidth()/(float)roll.span,-pianoRowHeight());arrangementCheck(n.start==start+SongProject.STEP&&n.pitch==pitch+1,"note drag after viewport zoom");}
            case 60->{int count=notes().size();notesContext=true;key(GLFW.GLFW_KEY_C,GLFW.GLFW_MOD_CONTROL);notePastePpq=8*SongProject.STEP;key(GLFW.GLFW_KEY_V,GLFW.GLFW_MOD_CONTROL);arrangementCheck(notes().size()==count+1,"Piano clipboard after resize");undo();arrangementCheck(notes().size()==count,"Piano paste undo");redo();arrangementCheck(notes().size()==count+1,"Piano paste redo");}
            case 61->{snap=1;var h=smokeHit("note-seek-ruler");lastHit="";click(originX+roll.timeX(5.3)*scale,originY+(h.y+8)*scale,0,0);release();arrangementCheck(notePastePpq==5*SongProject.STEP,"Piano ruler wrong after viewport changes");piano=false;rack=false;}
            case 62->{var h=smokeHit("track-0");lastHit="";for(int i=0;i<2;i++){click(originX+(h.x+20)*scale,originY+(h.y+12)*scale,0,0);release();}arrangementCheck(song.tracks.get(0).displayHeight==65,"track double click did not reset height");undo();arrangementCheck(song.tracks.get(0).displayHeight==105,"track height reset undo");redo();arrangementCheck(song.tracks.get(0).displayHeight==65,"track height reset redo");piano=true;}
            case 63->{StudioText.testLanguage("zh_cn");arrangementClick("piano-maximize");}
            case 64->{song.validate();arrangementClick("piano-maximize");StudioText.testLanguage("en_us");}
            case 65->{song.validate();}
            case 66->{piano=false;rack=false;modal="";interactionBefore=ProjectStore.encode(song);arrangementClick("minimize-editor");}
            case 67->{arrangementCheck(editorFolded&&hits.size()==3,"editor did not fold to title bar");arrangementClick("minimize-editor");}
            case 68->{arrangementCheck(!editorFolded&&interactionBefore.equals(ProjectStore.encode(song)),"editor fold changed score");arrangementClick("maximize-editor");}
            case 69->{arrangementCheck(!editorMaximized,"editor restore size");arrangementClick("maximize-editor");piano=true;}
            case 70->{arrangementCheck(editorMaximized,"editor maximize size");arrangementClick("minimize-piano");}
            case 71->{arrangementCheck(windows.get("piano").folded&&hits.stream().noneMatch(h->h.id.equals("roll-grid")),"folded Piano still has note grid");arrangementClick("minimize-piano");}
            case 72->{arrangementCheck(!windows.get("piano").folded,"Piano unfold");arrangementClick("close-piano");rack=true;}
            case 73->{arrangementCheck(!piano,"Piano close");arrangementClick("maximize-rack");}
            case 74->{arrangementCheck(windows.get("rack").maximized&&smokeHit("window-body-rack").w==W,"rack maximize");arrangementClick("minimize-rack");}
            case 75->{arrangementCheck(windows.get("rack").folded&&hits.stream().noneMatch(h->h.id.equals("step-0-0")),"rack fold");arrangementClick("minimize-rack");}
            case 76->{arrangementClick("maximize-rack");arrangementCheck(!windows.get("rack").maximized,"rack restore");}
            case 77->{arrangementClick("close-rack");arrangementCheck(!rack,"rack close");viewport.start=0;viewport.bars=10;snap=4;song.exportStart=song.exportEnd=0;}
            case 78->{arrangementCheck(hits.stream().noneMatch(h->h.id.equals("song-end")||h.id.equals("song-end-at-cursor")),"obsolete Length cursor/control remains");smokeHitDrag("range-start",(float)(2*TIME_W/viewport.bars),0);arrangementCheck(song.exportStart==2*4*SongProject.PPQ&&song.exportEnd==0,"overlapping start marker drag");}
            case 79->{smokeHitDrag("range-end",(float)(6*TIME_W/viewport.bars),0);arrangementCheck(song.exportEnd==6*4*SongProject.PPQ&&ExportRange.of(song).duration()==4*4*SongProject.PPQ,"range end marker drag");}
            case 80->{var preview=ArrangementSmoke.fixture();preview.exportStart=song.exportStart;preview.exportEnd=song.exportEnd;String before=ProjectStore.encode(preview);for(boolean mode:new boolean[]{false,true}){var prepared=ClientMedia.preparePreview(preview,0,mode,0,c->{throw new IllegalStateException("unexpected preview audio decode");});arrangementCheck(before.equals(ProjectStore.encode(prepared)),"export range changed Song/Pattern preview");}exportAudio(root.resolve("selection.wav"),"wav",false);}
            case 81->{if(mediaBusy)return false;arrangementCheck(AudioFiles.read(root.resolve("selection.wav")).frames()==8*AudioFiles.RATE,"selected WAV duration");exportAudio(root.resolve("selection.mp3"),"mp3",false);}
            case 82->{if(mediaBusy)return false;arrangementCheck(Files.isRegularFile(root.resolve("selection.mp3")),"selected MP3");exportAudio(root.resolve("selection.ogg"),"ogg",false);}
            case 83->{if(mediaBusy)return false;arrangementCheck(Files.isRegularFile(root.resolve("selection.ogg")),"selected OGG");exportMidi(root.resolve("range-midi"));}
            case 84->{if(mediaBusy)return false;try(var files=Files.walk(root.resolve("range-midi"))){for(Path path:files.filter(p->p.toString().endsWith(".mid")).toList())arrangementCheck(javax.sound.midi.MidiSystem.getSequence(path.toFile()).getTickLength()==4*4*SongProject.PPQ,"MIDI range length");}
                var cut=ExportRange.snapshot(song);cut.commandSamples=true;var provider=ClientMedia.captureBlueprint(cut);mediaTask(()->{var prepared=ClientMedia.prepareBlueprint(cut,provider);var b=dev.musicstudio.blueprint.MusicBlueprint.compile(prepared);if(b.period()!=160)throw new IllegalStateException("Range building period");Files.writeString(root.resolve("range-blueprint.json"),ProjectStore.encode(prepared));return b;},b->mediaMessage="RANGE_BUILDING_OK");}
            case 85->{if(mediaBusy)return false;arrangementCheck(mediaMessage.equals("RANGE_BUILDING_OK"),"range building failed: "+mediaMessage);smokeHitDrag("range-end",(float)(-4*TIME_W/viewport.bars),0);arrangementCheck(song.exportStart==song.exportEnd&&ExportRange.of(song).full(),"overlapping markers did not select full song");}
            case 86->{exportAudio(root.resolve("whole-again.wav"),"wav",false);}
            case 87->{if(mediaBusy)return false;var pcm=AudioFiles.read(root.resolve("whole-again.wav"));arrangementCheck(pcm.frames()>0&&pcm.frames()<16*AudioFiles.RATE&&(pcm.left()[pcm.frames()-1]!=0||pcm.right()[pcm.frames()-1]!=0),"whole song retained padded project tail");edit(()->{song.exportStart=8*4*SongProject.PPQ+SongProject.PPQ;song.exportEnd=9*4*SongProject.PPQ+SongProject.PPQ;});exportAudio(root.resolve("silence.wav"),"wav",false);}
            case 88->{if(mediaBusy)return false;var pcm=AudioFiles.read(root.resolve("silence.wav"));arrangementCheck(pcm.frames()==2*AudioFiles.RATE,"rest export duration");for(float f:pcm.left())arrangementCheck(f==0,"range beyond song contains unexpected sound");}
            case 89->{arrangementClick("range-reset");arrangementCheck(song.exportStart==song.exportEnd,"full range reset");}
            default->{if(step>=90&&step<140)return smokePanelControls(step-90);if(step>=140&&step<165)return smokeTransportStep(step-140,root);if(step>=165&&step<180)return smokeGrowthStep(step-165,root);if(step>=180&&step<201)return smokePickerStep(step-180,root);if(step>=201&&step<214)return smokePitchStep(step-201,root);if(step>=214&&step<224)return smokeClipGrowthStep(step-214,root);if(step>=224&&step<230)return smokeFullExportStep(step-224,root);throw new IllegalStateException("Unknown arrangement stage "+step);}
        }return true;}catch(Exception ex){throw new IllegalStateException("Arrangement stage "+step,ex);}
    }
    private boolean smokePanelControls(int step){
        String[] panels={"sounds","projects","export","media","sample","delete-channel","track-settings","pattern-settings","add-source","channel-settings"};String id=panels[step/5];
        switch(step%5){
            case 0->{piano=false;rack=false;modal="";windows.get(id).folded=windows.get(id).maximized=false;switch(id){case "sounds"->openSounds(false);case "projects"->openProjects();case "media"->openMedia();case "sample"->openAudioSample(0);case "delete-channel"->requestDeleteChannel(0);case "track-settings"->openTrackSettings(0);case "pattern-settings"->{selectPattern(0);modal=id;}case "channel-settings"->openChannelSettings(0);default->modal=id;}}
            case 1->{arrangementCheck(hits.stream().anyMatch(h->h.id.equals("minimize-"+id))&&hits.stream().anyMatch(h->h.id.equals("close-"+id)),"missing three controls: "+id);arrangementClick("maximize-"+id);}
            case 2->{arrangementCheck(windows.get(id).maximized&&smokeHit("window-body-"+id).w==W,"modal maximize "+id);arrangementClick("minimize-"+id);}
            case 3->{arrangementCheck(modal.isEmpty()&&windows.get(id).folded,"modal fold "+id);arrangementClick("minimize-"+id);}
            case 4->{arrangementCheck(modal.equals(id)&&!windows.get(id).folded&&windows.get(id).maximized,"modal restore "+id);arrangementClick("close-"+id);arrangementCheck(modal.isEmpty(),"modal close "+id);}
        }return true;
    }
    void smokeCloseRoot(){arrangementClick("close-editor");arrangementCheck(!(mc.screen instanceof EditorScreen),"root editor close");}
    public void runTimelineSmokeAssertions(){
        String original=ProjectStore.encode(song);int oldSnap=snap,oldPaste=notePastePpq;double oldView=viewport.start,oldBars=viewport.bars;double oldCursor=cursor;boolean oldContext=notesContext;int history=undo.size();long rev=revision;
        try{
            var ruler=hits.stream().filter(h->h.id.equals("playlist-seek")).findFirst().orElseThrow();var control=hits.stream().filter(h->h.id.equals("snap")).findFirst().orElseThrow();
            viewport.start=0;viewport.bars=12;
            for(int steps:new int[]{16,4,1}){
                while(snap!=steps){click(originX+(control.x+control.w/2)*scale,originY+(control.y+control.h/2)*scale,0,0);release();}
                float at=ruler.x+(float)(ruler.w*21.4/(viewport.bars*16));click(originX+at*scale,originY+(ruler.y+8)*scale,0,0);
                int expected=(int)(21.4/steps)*steps*SongProject.STEP;if(cursor!=expected)throw new IllegalStateException("Ruler ignored snap "+steps+": "+cursor+" vs "+expected);
                float to=ruler.x+(float)(ruler.w*26.7/(viewport.bars*16));drag(originX+to*scale,originY+(ruler.y+90)*scale);release();expected=(int)(26.7/steps)*steps*SongProject.STEP;if(cursor!=expected)throw new IllegalStateException("Ruler drag ignored snap "+steps);
            }
            // The beat under the pointer stays correct after horizontal scroll and zoom.
            viewport.start=4;viewport.bars=4;float at=ruler.x+ruler.w*5.4f/64;click(originX+at*scale,originY+(ruler.y+8)*scale,0,0);if(cursor!=4*4*SongProject.PPQ+5*SongProject.STEP)throw new IllegalStateException("Scrolled/zoomed ruler mapped to wrong musical position");
            drag(originX+(ruler.x-100)*scale,originY+(ruler.y+8)*scale);if(cursor!=4*4*SongProject.PPQ)throw new IllegalStateException("Ruler left bound failed");
            drag(originX+(ruler.x+ruler.w+100)*scale,originY+(ruler.y+8)*scale);release();if(cursor!=8*4*SongProject.PPQ-SongProject.STEP)throw new IllegalStateException("Ruler right bound failed");
            cursor=21*SongProject.STEP;click(originX+(control.x+control.w/2)*scale,originY+(control.y+control.h/2)*scale,0,0);release();if(snap!=16||cursor!=16*SongProject.STEP)throw new IllegalStateException("Changing snap did not align stopped playhead");
            if(!original.equals(ProjectStore.encode(song))||undo.size()!=history||revision!=rev)throw new IllegalStateException("Seeking changed score or undo history");
            MusicStudio.LOGGER.info("STUDIO_SMOKE PASS: native ruler click/drag, bar/beat/sixteenth snapping, scroll/zoom/bounds and stopped playhead alignment");
        }finally{snap=oldSnap;viewport.start=oldView;viewport.bars=oldBars;notePastePpq=oldPaste;cursor=oldCursor;notesContext=oldContext;drag=null;rack=false;}
    }
    void smokeWaveformHome(SongProject project){smokeMediaHome(project);rack=false;piano=false;modal="";patternMode=false;}
    SongWaveform smokeWaveform(){return waveform.waveform();}
    String smokeWaveformError(){return waveform.error();}
    void smokeWaveformPlay(){cursor=0;play();}
    void smokeWaveformStop(){stop();}
    void smokeWaveformClick(String id){var h=smokeHit(id);click(originX+(h.x+h.w/2)*scale,originY+(h.y+h.h/2)*scale,0,0);release();}
    void smokeWaveformHalfVolume(){edit(()->song.channels.getFirst().volume=.4f);}
    public void smokePiano(){focus="";modal="";piano=true;rack=false;roll.top=48;}
}
