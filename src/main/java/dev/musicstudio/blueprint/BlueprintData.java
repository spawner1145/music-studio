package dev.musicstudio.blueprint;

import dev.musicstudio.song.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Portable immutable song snapshot; never contains arbitrary blocks, NBT or commands. */
public final class BlueprintData {
    public static final int MAX_PACKED=256*1024,MAX_RAW=2*1024*1024;
    private BlueprintData(){}
    public static byte[] pack(SongProject song){
        byte[] raw=ProjectStore.encode(song).getBytes(StandardCharsets.UTF_8);
        if(raw.length>MAX_RAW)throw new StudioException("error.blueprint_data");
        try{var out=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(out)){gzip.write(raw);}byte[] packed=out.toByteArray();
            if(packed.length>MAX_PACKED)throw new StudioException("error.blueprint_data");return packed;
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    public static SongProject unpack(byte[] bytes){
        if(bytes.length==0||bytes.length>MAX_PACKED)throw new StudioException("error.blueprint_data");
        try(var gzip=new GZIPInputStream(new ByteArrayInputStream(bytes))){byte[] raw=gzip.readNBytes(MAX_RAW+1);
            if(raw.length>MAX_RAW)throw new StudioException("error.blueprint_data");return ProjectStore.decode(new String(raw,StandardCharsets.UTF_8));
        }catch(IOException|IllegalArgumentException e){throw new StudioException("error.blueprint_data");}
    }
}
