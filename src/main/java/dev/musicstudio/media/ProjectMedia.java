package dev.musicstudio.media;

import dev.musicstudio.song.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Project-owned assets use content IDs, never paths supplied by song JSON. */
public final class ProjectMedia {
    private ProjectMedia(){}
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String hash(Path file) throws java.io.IOException {
        try{var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static Path folder(Path projects,SongProject song){return song.projectDirectory==null?draftFolder(projects,song):song.projectDirectory.toAbsolutePath().normalize();}
    public static Path draftFolder(Path projects,SongProject song){String id=song.projectId;String name=id.matches("[a-fA-F0-9-]{36}")?id:"project-"+hash(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));Path root=projects.toAbsolutePath().normalize(),legacy=root.resolve(name);return Files.isDirectory(legacy)?legacy:root.resolve(".drafts").resolve(name);}
    public static Path assets(Path projects,SongProject song){return folder(projects,song).resolve("assets");}
    public static SongProject.Asset asset(SongProject song,String id){return song.assets.stream().filter(a->a.id.equals(id)).findFirst().orElseThrow(()->new StudioException("error.asset"));}
    public static Path wav(Path projects,SongProject song,String id){if(!id.matches("[a-f0-9]{64}"))throw new StudioException("error.asset");return assets(projects,song).resolve(id+".wav");}
    public static String effectKey(SongProject.Channel c){return hash((c.sampleId+":"+c.trimStart+":"+c.trimEnd+":"+c.fadeIn+":"+c.fadeOut+":"+c.gain).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
