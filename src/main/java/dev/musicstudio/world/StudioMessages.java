package dev.musicstudio.world;

import dev.musicstudio.MusicStudio;
import dev.musicstudio.song.StudioException;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public final class StudioMessages {
    public static Component error(Exception e){if(e instanceof StudioException s)return Component.translatable("musicstudio."+s.key,s.arguments);MusicStudio.LOGGER.error("Blueprint operation failed",e);return Component.translatable("musicstudio.error.blueprint_internal");}
    public static void error(Player player,Exception e){player.displayClientMessage(error(e),false);}
}
