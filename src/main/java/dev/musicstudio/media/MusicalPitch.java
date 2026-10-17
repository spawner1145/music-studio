package dev.musicstudio.media;

/** MIDI pitch ratios are independent of Minecraft's note-block and sound-engine ranges. */
public final class MusicalPitch {
    private MusicalPitch(){}
    public static double ratio(int note,int base,boolean tunable){return tunable?Math.pow(2,(note-base)/12.0):1;}
    public static boolean requiresSample(int note,int base,boolean tunable){double ratio=ratio(note,base,tunable);return ratio<.5||ratio>2;}
}
