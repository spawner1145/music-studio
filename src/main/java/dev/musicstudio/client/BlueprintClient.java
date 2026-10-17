package dev.musicstudio.client;

import dev.musicstudio.blueprint.*;
import dev.musicstudio.network.StudioNetwork;
import dev.musicstudio.song.SongProject;
import dev.musicstudio.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

public final class BlueprintClient {
    private static final Minecraft MC=Minecraft.getInstance();
    private static long clock,lastPreviewCheck;
    private static BlockPos lastAnchor;
    private static Object lastData;
    private static int lastRotation;
    private static boolean previewClear;
    public static void init(){
        StudioNetwork.replyHandler=p->{String text=StudioText.tr(p.key(),p.args().toArray());if(MC.screen instanceof EditorScreen s)s.canvas.blueprintReply(p);else if(MC.player!=null)MC.player.displayClientMessage(Component.literal(text),false);};
    }
    public static UUID export(SongProject song){
        if(MC.player==null||!MC.player.isCreative())throw new dev.musicstudio.song.StudioException("error.blueprint_creative");
        MusicBlueprint.compile(song);byte[] bytes=BlueprintData.pack(song);UUID request=UUID.randomUUID();int total=(bytes.length+23999)/24000;
        for(int i=0;i<total;i++)PacketDistributor.sendToServer(new StudioNetwork.Upload(request,i,total,Arrays.copyOfRange(bytes,i*24000,Math.min(bytes.length,(i+1)*24000))));return request;
    }
    public static void tick(){clock++;}
    public static void logout(){lastData=null;lastAnchor=null;}
    private static ItemStack held(){if(MC.player==null)return ItemStack.EMPTY;ItemStack main=MC.player.getMainHandItem();return main.is(MusicBlocks.BLUEPRINT.get())?main:MC.player.getOffhandItem().is(MusicBlocks.BLUEPRINT.get())?MC.player.getOffhandItem():ItemStack.EMPTY;}
    public static void render(RenderLevelStageEvent e){
        if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS||MC.level==null||MC.player==null||!MC.player.isCreative())return;
        ItemStack item=held();if(item.isEmpty()||!(MC.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK)return;
        try{MusicBlueprint b=BlueprintItem.decode(item);int r=BlueprintItem.rotation(item);BlockPos anchor=hit.getBlockPos().relative(hit.getDirection());Object data=item.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if(!anchor.equals(lastAnchor)||data!=lastData||r!=lastRotation||clock-lastPreviewCheck>=10){lastAnchor=anchor;lastData=data;lastRotation=r;lastPreviewCheck=clock;previewClear=b.fitsRotation(r);
                for(int yy=0;yy<b.y()&&previewClear;yy++)for(int zz=0;zz<b.depth(r)&&previewClear;zz++)for(int xx=0;xx<b.width(r);xx++){BlockPos p=anchor.offset(xx,yy,zz);if(MC.level.isOutsideBuildHeight(p)||!MC.level.getWorldBorder().isWithinBounds(p)||!MC.level.hasChunkAt(p)||!MC.level.getBlockState(p).isAir()){previewClear=false;break;}}
            }
            var pose=e.getPoseStack();Vec3 cam=e.getCamera().getPosition();pose.pushPose();pose.translate(-cam.x,-cam.y,-cam.z);var source=MC.renderBuffers().bufferSource();var lines=source.getBuffer(RenderType.lines());
            LevelRenderer.renderLineBox(pose,lines,new AABB(anchor.getX(),anchor.getY(),anchor.getZ(),anchor.getX()+b.width(r),anchor.getY()+b.y(),anchor.getZ()+b.depth(r)),previewClear?.35f:1f,previewClear?1f:.25f,.35f,1);
            var root=b.rotate(b.lever(),r);LevelRenderer.renderLineBox(pose,lines,new AABB(anchor.offset(root.x(),root.y(),root.z())).inflate(.01),1, .8f,.15f,1);
            pose.popPose();source.endBatch(RenderType.lines());
        }catch(Exception ignored){}
    }
    public static void hud(net.minecraft.client.gui.GuiGraphics g,net.minecraft.client.DeltaTracker delta){
        ItemStack item=held();if(item.isEmpty()||MC.player==null||!MC.player.isCreative()||MC.screen!=null)return;
        try{var b=BlueprintItem.decode(item);int r=BlueprintItem.rotation(item);String label=StudioText.tr("blueprint.preview",b.width(r),b.depth(r),b.y(),r*90);int x=(g.guiWidth()-MC.font.width(label))/2,y=g.guiHeight()-71;g.fill(x-5,y-4,x+MC.font.width(label)+5,y+12,0xbb14232d);g.drawString(MC.font,label,x,y,previewClear?0xffb3e77d:0xffff9a7d);}
        catch(Exception ignored){}
    }
}
