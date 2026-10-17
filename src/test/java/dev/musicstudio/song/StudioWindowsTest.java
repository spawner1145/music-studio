package dev.musicstudio.song;

import dev.musicstudio.client.StudioWindows;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StudioWindowsTest {
    @Test void panelsMaximizeAndRestoreIndependentlyWithExactNormalBounds(){
        var windows=new StudioWindows();var original=windows.bounds("rack",190,140,650,323);windows.toggleMaximize("rack");var full=windows.bounds("rack",190,140,650,323);assertEquals(1280,full.width());assertEquals(806,full.height());assertFalse(windows.get("media").maximized);windows.toggleMaximize("rack");assertEquals(original,windows.bounds("rack",190,140,650,323));
    }
    @Test void foldedPanelsCanRestoreMaximizeOrCloseWithoutLosingOtherPanels(){
        var windows=new StudioWindows();windows.toggleFold("media");windows.toggleFold("sounds");assertEquals(2,windows.folded().size());windows.toggleMaximize("media");assertFalse(windows.get("media").folded);assertTrue(windows.get("media").maximized);assertEquals(1,windows.folded().size());windows.close("sounds");assertTrue(windows.folded().isEmpty());assertTrue(windows.get("media").maximized);
    }
}
