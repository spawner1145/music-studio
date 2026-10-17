package dev.musicstudio.media;

import dev.musicstudio.song.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** One portable directory: a score file and adjacent, content-addressed source assets. */
public final class ProjectFiles {
    private ProjectFiles(){}
    public static String defaultName(String title){String name=title.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]","_").strip().replaceAll("[. ]+$","");if(name.isBlank()||name.equals(".")||name.equals(".."))name="Untitled";if(name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))name="_"+name;return name.substring(0,Math.min(80,name.length()));}
    /** Used for first save / Save As. Ordinary Save always uses the opened file verbatim. */
    public static Path newProjectFile(Path selected) throws IOException {
        Path file=selected.toAbsolutePath().normalize();String name=file.getFileName().toString();if(!name.toLowerCase(Locale.ROOT).endsWith(".musicstudio")){name+=".musicstudio";file=file.resolveSibling(name);}
        if(Files.isRegularFile(file)&&!isArchive(file))return file;
        String stem=name.substring(0,name.length()-".musicstudio".length());if(stem.isBlank())throw new StudioException("error.filename");
        Path folder=file.getParent();if(folder.getFileName()==null||!folder.getFileName().toString().equals(stem))folder=folder.resolve(stem);
        Path target=folder.resolve(name);if(!target.equals(file)&&Files.exists(target))throw new StudioException("error.project_folder_used");if(Files.isDirectory(folder)){try(var files=Files.list(folder)){if(files.anyMatch(p->!p.equals(target)&&isProjectFile(p)))throw new StudioException("error.project_folder_used");}}
        return target;
    }
    public static Path resolveFile(Path selected) throws IOException {
        Path path=selected.toAbsolutePath().normalize();if(!Files.isDirectory(path))return path;
        try(var files=Files.list(path)){var scores=files.filter(ProjectFiles::isProjectFile).limit(2).toList();if(scores.size()!=1)throw new StudioException("error.project_folder_ambiguous");return scores.getFirst();}
    }
    private static boolean isProjectFile(Path path){return Files.isRegularFile(path)&&path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".musicstudio");}
    public static boolean isProjectFolder(Path folder){if(!Files.isDirectory(folder))return false;try(var files=Files.list(folder)){return files.anyMatch(ProjectFiles::isProjectFile);}catch(IOException e){return false;}}
    public static boolean isArchive(Path path) throws IOException {try(var input=Files.newInputStream(path)){return input.read()=='P'&&input.read()=='K';}}
    public static Path saveAs(Path selected,Path projects,SongProject song) throws Exception {
        Path file=newProjectFile(selected),source=ProjectMedia.assets(projects,song);
        // Include recently undone imports: redo must still work after switching to the new folder.
        if(Files.isDirectory(source)){try(var files=Files.list(source)){copyAssetNames(source,file.getParent().resolve("assets"),files.filter(Files::isRegularFile).map(p->p.getFileName().toString()).filter(n->n.matches("[a-f0-9]{64}(\\.wav|-source\\.(wav|ogg|mp3|flac|aif|aiff|m4a))")).toList());}}
        save(file,projects,song);return file;
    }
    /** Copy only immutable source media, leaving caches and recovery data out of distributed projects. */
    public static void copyAssets(Path source,Path destination,SongProject song) throws IOException {
        Set<String> names=new LinkedHashSet<>();for(var a:song.assets){names.add(a.original);names.add(a.id+".wav");}copyAssetNames(source,destination,names);
    }
    private static void copyAssetNames(Path source,Path destination,Collection<String> names) throws IOException {
        for(String name:names)if(!Files.isRegularFile(source.resolve(name)))throw new StudioException("error.project_assets");
        Files.createDirectories(destination);if(source.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize()))return;
        List<Path> temporary=new ArrayList<>();Path stage=Files.createTempDirectory(destination,"copy-");
        try{for(String name:names){Path from=source.resolve(name),to=destination.resolve(name);if(Files.isRegularFile(to)&&Files.size(from)==Files.size(to)&&Files.mismatch(from,to)==-1)continue;Path temp=stage.resolve(name);temporary.add(temp);Files.copy(from,temp);}
            for(Path temp:temporary)replace(temp,destination.resolve(temp.getFileName()));
        }finally{for(Path temp:temporary)Files.deleteIfExists(temp);Files.deleteIfExists(stage);}
    }
    private static void replace(Path from,Path to) throws IOException {try{Files.move(from,to,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}}
    public static void save(Path file,Path projects,SongProject song) throws Exception {
        byte[] score=ProjectStore.encode(song).getBytes(StandardCharsets.UTF_8);Path target=file.toAbsolutePath().normalize();Files.createDirectories(target.getParent());Path tmp=Files.createTempFile(target.getParent(),"studio-save-",".tmp");
        try{copyAssets(ProjectMedia.assets(projects,song),target.getParent().resolve("assets"),song);Files.write(tmp,score);
            if(Files.exists(target))Files.copy(target,target.resolveSibling(target.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
            replace(tmp,target);song.projectDirectory=target.getParent();
        }finally{Files.deleteIfExists(tmp);}
    }
    public static SongProject open(Path file,Path projects) throws Exception {
        file=resolveFile(file);
        if(file.toString().toLowerCase(Locale.ROOT).endsWith(".json")){
            SongProject song=ProjectStore.decode(Files.readString(file));Path origin=ProjectMedia.assets(file.toAbsolutePath().getParent(),song),destination=ProjectMedia.assets(projects,song);
            if(!song.assets.isEmpty()&&!origin.equals(destination)&&Files.isDirectory(origin)){Files.createDirectories(destination);for(var asset:song.assets)for(String name:List.of(asset.original,asset.id+".wav"))Files.copy(origin.resolve(name),destination.resolve(name),StandardCopyOption.REPLACE_EXISTING);}
            for(var asset:song.assets)if(!Files.isRegularFile(destination.resolve(asset.id+".wav")))throw new StudioException("error.project_assets");return song;
        }
        if(!isArchive(file)){
            SongProject song=ProjectStore.decode(Files.readString(file));song.projectDirectory=file.getParent();
            Path dir=ProjectMedia.assets(projects,song);for(var a:song.assets){for(String name:List.of(a.original,a.id+".wav"))if(!Files.isRegularFile(dir.resolve(name)))throw new StudioException("error.project_assets");
                if(!ProjectMedia.hash(dir.resolve(a.original)).equals(a.id)||AudioFiles.frameCount(dir.resolve(a.id+".wav"))!=a.frames)throw new StudioException("error.project_archive");}
            return song;
        }
        // Older bundled .musicstudio ZIPs remain readable and migrate on the next explicit save.
        try(var zip=new ZipFile(file.toFile())){
            ZipEntry score=zip.getEntry("project.json");if(score==null)throw new StudioException("error.empty_project");SongProject song;try(var input=zip.getInputStream(score);var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){song=ProjectStore.GSON.fromJson(reader,SongProject.class);}if(song==null)throw new StudioException("error.empty_project");song.validate();
            Set<String> allowed=new HashSet<>(Set.of("project.json"));for(var a:song.assets){allowed.add("assets/"+a.original);allowed.add("assets/"+a.id+".wav");}Set<String> seen=new HashSet<>();var entries=zip.entries();while(entries.hasMoreElements()){String name=entries.nextElement().getName();if(!allowed.contains(name)||!seen.add(name))throw new StudioException("error.project_archive");}if(!seen.containsAll(allowed))throw new StudioException("error.project_assets");
            Path dir=ProjectMedia.assets(projects,song);Files.createDirectories(dir);Path stage=Files.createTempDirectory(dir,"open-");List<Path> temporary=new ArrayList<>();
            try{for(var a:song.assets){for(String name:List.of(a.original,a.id+".wav")){Path out=stage.resolve(name);temporary.add(out);try(var input=zip.getInputStream(zip.getEntry("assets/"+name));var output=Files.newOutputStream(out)){input.transferTo(output);}
                    if(name.equals(a.original)&&!ProjectMedia.hash(out).equals(a.id))throw new StudioException("error.project_archive");
                }if(AudioFiles.frameCount(stage.resolve(a.id+".wav"))!=a.frames)throw new StudioException("error.project_archive");}
                for(Path source:temporary)Files.move(source,dir.resolve(source.getFileName()),StandardCopyOption.REPLACE_EXISTING);return song;
            }finally{for(Path path:temporary)Files.deleteIfExists(path);Files.deleteIfExists(stage);}
        }
    }
}
