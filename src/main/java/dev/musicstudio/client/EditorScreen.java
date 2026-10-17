package dev.musicstudio.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import net.minecraft.network.chat.Component;

/** Native LDLib2 UI. All workstation rendering is in one virtualized UIElement. */
public final class EditorScreen extends ModularUIScreen {
    public final EditorCanvas canvas;
    private EditorScreen(EditorCanvas canvas) {
        super(new ModularUI(UI.of(canvas)).shouldCloseOnKeyInventory(false),Component.literal("Music Studio"));
        this.canvas=canvas;
    }
    public static EditorScreen create() { return new EditorScreen(new EditorCanvas()); }
    @Override public void init() {
        canvas.getLayout().width(width).height(height);
        super.init();
    }
    @Override public boolean mouseClicked(double x,double y,int button) { return canvas.click(x,y,button); }
    @Override public boolean mouseReleased(double x,double y,int button) { canvas.release();return true; }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) { canvas.drag(x,y);return true; }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) { canvas.scroll(x,y,vertical);return true; }
    @Override public boolean keyPressed(int key,int scan,int modifiers) { return canvas.key(key,modifiers)||super.keyPressed(key,scan,modifiers); }
    @Override public boolean charTyped(char ch,int modifiers) { return canvas.character(ch)||super.charTyped(ch,modifiers); }
    @Override public void tick() { super.tick();canvas.tickEditor(); }
    @Override public void onFilesDrop(java.util.List<java.nio.file.Path> paths){canvas.filesDropped(paths,net.minecraft.client.Minecraft.getInstance().mouseHandler.xpos()*width/net.minecraft.client.Minecraft.getInstance().getWindow().getScreenWidth(),net.minecraft.client.Minecraft.getInstance().mouseHandler.ypos()*height/net.minecraft.client.Minecraft.getInstance().getWindow().getScreenHeight());}
    @Override public void removed() { canvas.close();super.removed(); }
    @Override public void onClose() { if(canvas.escape())return;super.onClose(); }
}
