package dev.musicstudio.song;

/** Language-independent failure that the client translates at the presentation boundary. */
public final class StudioException extends IllegalArgumentException {
    public final String key;
    public final Object[] arguments;
    public StudioException(String key,Object... arguments){super("musicstudio."+key);this.key=key;this.arguments=arguments.clone();}
}
