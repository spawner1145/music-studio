package dev.musicstudio.song;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public final class ProjectStore {
    public static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    public ProjectStore(Path directory) { this.directory=directory.toAbsolutePath().normalize(); }
    public Path directory() { return directory; }
    public static SongProject decode(String text) {
        if(text==null)throw new StudioException("error.empty_project");
        SongProject p=GSON.fromJson(text,SongProject.class);
        if(p==null)throw new StudioException("error.empty_project");
        p.validate(); return p;
    }
    public static String encode(SongProject p) { p.validate(); return GSON.toJson(p); }
    public static SongProject copy(SongProject p) { var copy=decode(encode(p));copy.projectDirectory=p.projectDirectory;return copy; }
    public Path path(String name) {
        if(!name.matches("[a-zA-Z0-9_\u4e00-\u9fff .-]{1,80}")||name.startsWith(".")||name.endsWith(".")||name.endsWith(" ")||name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))throw new StudioException("error.filename");
        Path p=directory.resolve(name+".json").normalize();
        if(!p.getParent().equals(directory))throw new StudioException("error.project_path");
        return p;
    }
    public void save(String name,SongProject project) throws IOException {
        byte[] bytes=encode(project).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(directory);
        Path target=path(name),temp=Files.createTempFile(directory,"save-",".tmp");
        try {
            Files.write(temp,bytes);
            Path owned=dev.musicstudio.media.ProjectMedia.draftFolder(directory,project);Files.createDirectories(owned);
            dev.musicstudio.media.ProjectFiles.copyAssets(dev.musicstudio.media.ProjectMedia.assets(directory,project),owned.resolve("assets"),project);
            Path ownedTemp=Files.createTempFile(owned,"project-",".tmp");try{Files.write(ownedTemp,bytes);Files.move(ownedTemp,owned.resolve("project.json"),StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(ownedTemp);}
            if(Files.exists(target))Files.copy(target,target.resolveSibling(target.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ex) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    public SongProject load(String name) throws IOException {
        Path p=path(name);
        return decode(Files.readString(p));
    }
    public List<String> list() throws IOException {
        if(!Files.isDirectory(directory))return List.of();
        try(var files=Files.list(directory)) { return files.filter(p->Files.isRegularFile(p)&&p.getFileName().toString().endsWith(".json")||!p.getFileName().toString().startsWith(".")&&dev.musicstudio.media.ProjectFiles.isProjectFolder(p)).map(p->Files.isDirectory(p)?p.getFileName().toString():p.getFileName().toString().replaceFirst("\\.json$","")).distinct().sorted().toList(); }
    }
}
