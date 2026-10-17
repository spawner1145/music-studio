package dev.musicstudio.blueprint;

import dev.musicstudio.song.SongProject;

/** Extra playback controls; the actual vanilla block state always keeps a legal 0..24 note. */
public record NoteBlockVoice(int note,int velocity,int durationTicks,float volume) {
    public NoteBlockVoice {
        if(note < -115 || note > 139 || velocity < 1 || velocity > 127 || durationTicks < 0 || durationTicks > 31457280 || !Float.isFinite(volume) || volume < 0 || volume > 1)throw new IllegalArgumentException("Invalid note block voice");
    }
    public static NoteBlockVoice of(SongProject song,SongProject.Channel channel,SongProject.Event event){
        int duration=event.duration()==SongProject.VANILLA_NOTE_DURATION?0:Math.max(1,(int)Math.round(event.duration()*1200.0/(SongProject.PPQ*song.bpm)));
        return new NoteBlockVoice(12+(channel.tunable?event.pitch()-channel.baseMidi:0),event.velocity(),duration,channel.volume);
    }
}
