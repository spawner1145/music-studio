package dev.musicstudio;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import net.neoforged.bus.api.IEventBus;
import dev.musicstudio.world.*;
import dev.musicstudio.network.StudioNetwork;
import dev.musicstudio.network.MediaNetwork;

@Mod(MusicStudio.ID)
public final class MusicStudio {
    public static final String ID = "musicstudio";
    public static final String VERSION = "0.5.16";
    public static final Logger LOGGER = LogUtils.getLogger();
    public MusicStudio(IEventBus bus) { MusicBlocks.register(bus);bus.addListener(StudioNetwork::register);bus.addListener(MediaNetwork::register);bus.addListener(dev.musicstudio.network.NoteVoiceNetwork::register);StudioServer.register();DiscPlayback.register();LOGGER.info("Music Studio {} initialized",VERSION); }
}
