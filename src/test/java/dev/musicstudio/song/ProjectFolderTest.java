package dev.musicstudio.song;

import dev.musicstudio.blueprint.BlueprintData;
import dev.musicstudio.media.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectFolderTest {
    @TempDir Path temp;
    private Path cache(){return temp.resolve("cache");}
    private SongProject song() throws Exception {
        var s=SongProject.legacyDemo();s.title="Song title differs from filename";s.loop=false;s.channels=new ArrayList<>(List.of(s.channels.getFirst()));s.channels.getFirst().muted=false;
        var p=new SongProject.Pattern("Sample",1);p.notes.getFirst().add(new SongProject.Note(0,1,66,127));s.patterns=new ArrayList<>(List.of(p));s.clips=new ArrayList<>(List.of(new SongProject.Clip(0,0,0,1)));s.endPpq=4*SongProject.PPQ;
        Path source=temp.resolve("recording.wav");float[] pcm=new float[4410];Arrays.fill(pcm,.2f);AudioFiles.wav(source,new AudioFiles.Wave(pcm,pcm.clone()),1);
        var asset=AudioFiles.importAsset(source,cache(),s,AudioFiles.codec(temp));s.assets.add(asset);s.channels.getFirst().sampleId=asset.id;return s;
    }
    private Path save(SongProject s,String name) throws Exception {return ProjectFiles.saveAs(temp.resolve("projects").resolve(name+".musicstudio"),cache(),s);}
    private void copyFolder(Path from,Path to) throws Exception {
        try(var paths=Files.walk(from)){for(var path:paths.toList()){Path target=to.resolve(from.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);}}
    }
    @Test void firstSaveCreatesOneNamedFolderWithAdjacentScoreAndSources() throws Exception {
        var s=song();Path file=save(s,"夜航");assertEquals(temp.resolve("projects/夜航/夜航.musicstudio"),file);assertFalse(Files.exists(temp.resolve("projects/夜航.musicstudio")));
        assertEquals(file.getParent(),ProjectMedia.folder(cache(),s));assertFalse(ProjectFiles.isArchive(file));assertTrue(Files.isRegularFile(file.getParent().resolve("assets").resolve(s.assets.getFirst().original)));
        assertEquals(4410,AudioFiles.read(ProjectMedia.wav(cache(),s,s.assets.getFirst().id)).frames());assertFalse(Files.exists(file.getParent().resolve("project.json")));assertFalse(Files.readString(file).contains(temp.toString()));
        assertEquals(List.of("夜航"),new ProjectStore(temp.resolve("projects")).list());
    }
    @Test void copiedFolderSurvivesIndependentFolderAndScoreRenamesWithoutOriginalCache() throws Exception {
        var s=song();Path file=save(s,"夜航"),destination=temp.resolve("recipient/外层文件夹已改名");copyFolder(file.getParent(),destination);
        Files.move(destination.resolve(file.getFileName()),destination.resolve("内部文件也改名.musicstudio"));Files.move(temp.resolve("projects"),temp.resolve("original-project-unavailable"));Files.move(cache(),temp.resolve("original-cache-unavailable"));Files.move(temp.resolve("recording.wav"),temp.resolve("source-unavailable.wav"));
        Path emptyCache=temp.resolve("recipient-cache");var opened=ProjectFiles.open(destination,emptyCache);assertEquals(ProjectStore.encode(s),ProjectStore.encode(opened));assertEquals(destination,opened.projectDirectory);
        var mix=AudioFiles.mixFullExport(opened,c->new AudioFiles.Source(AudioFiles.read(ProjectMedia.wav(emptyCache,opened,c.sampleId)),1,1));assertEquals(4410,mix.wave().frames());assertFalse(Files.exists(emptyCache));
        opened.bpm=145;Path renamed=destination.resolve("内部文件也改名.musicstudio");ProjectFiles.save(renamed,emptyCache,opened);assertEquals(145,ProjectFiles.open(renamed,emptyCache).bpm);assertTrue(Files.isRegularFile(renamed.resolveSibling("内部文件也改名.musicstudio.bak")));assertEquals(renamed,ProjectFiles.newProjectFile(renamed));assertFalse(Files.exists(destination.resolve("内部文件也改名")));
    }
    @Test void saveAsClonesAssetsAndEachOpenKeepsItsOwnFolderEvenWithTheSameProjectId() throws Exception {
        var s=song();Path a=save(s,"A");var clone=ProjectStore.copy(s);Path b=save(clone,"B");
        assertEquals(a.getParent(),s.projectDirectory);assertEquals(b.getParent(),clone.projectDirectory);assertEquals(s.projectId,clone.projectId);
        var first=ProjectFiles.open(a,cache());var second=ProjectFiles.open(b,cache());assertEquals(a.getParent(),ProjectStore.copy(first).projectDirectory);assertEquals(b.getParent(),ProjectStore.copy(second).projectDirectory);
        Path newSource=temp.resolve("extra.wav");float[] pcm=new float[2205];Arrays.fill(pcm,.4f);AudioFiles.wav(newSource,new AudioFiles.Wave(pcm,pcm.clone()),1);var extra=AudioFiles.importAsset(newSource,cache(),second,AudioFiles.codec(temp));second.assets.add(extra);ProjectFiles.save(b,cache(),second);
        assertFalse(Files.exists(a.getParent().resolve("assets").resolve(extra.original)));assertTrue(Files.isRegularFile(b.getParent().resolve("assets").resolve(extra.original)));assertEquals(1,ProjectFiles.open(a,cache()).assets.size());assertEquals(2,ProjectFiles.open(b,cache()).assets.size());
    }
    @Test void autosaveRecoversAssetsWithoutWritingAnotherScoreIntoTheDistributedFolder() throws Exception {
        var s=song();Path file=save(s,"Recovery");var store=new ProjectStore(cache());s.bpm=146;store.save("autosave",s);
        assertFalse(Files.exists(file.getParent().resolve("project.json")));Files.move(file.getParent(),temp.resolve("project-folder-is-unavailable"));
        var recovered=store.load("autosave");assertEquals(146,recovered.bpm);assertNull(recovered.projectDirectory);assertEquals(4410,AudioFiles.read(ProjectMedia.wav(cache(),recovered,recovered.assets.getFirst().id)).frames());assertTrue(ProjectMedia.folder(cache(),recovered).startsWith(cache().resolve(".drafts")));
    }
    @Test void bundledLegacyProjectsMigrateWithoutOverwritingTheOriginalArchive() throws Exception {
        var s=song();Path legacy=temp.resolve("old.musicstudio");try(var zip=new ZipOutputStream(Files.newOutputStream(legacy))){zip.putNextEntry(new ZipEntry("project.json"));zip.write(ProjectStore.encode(s).getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();for(var asset:s.assets)for(String name:List.of(asset.original,asset.id+".wav")){zip.putNextEntry(new ZipEntry("assets/"+name));Files.copy(ProjectMedia.assets(cache(),s).resolve(name),zip);zip.closeEntry();}}
        byte[] before=Files.readAllBytes(legacy);var opened=ProjectFiles.open(legacy,temp.resolve("legacy-cache"));assertNull(opened.projectDirectory);Path migrated=ProjectFiles.saveAs(legacy,temp.resolve("legacy-cache"),opened);
        assertEquals(temp.resolve("old/old.musicstudio"),migrated);assertArrayEquals(before,Files.readAllBytes(legacy));assertEquals(ProjectStore.encode(s),ProjectStore.encode(ProjectFiles.open(migrated,temp.resolve("clean"))));
    }
    @Test void missingAdjacentMediaFailsEvenWhenTheOldCacheStillHasItAndFailedSaveKeepsScore() throws Exception {
        var s=song();Path file=save(s,"Missing");byte[] score=Files.readAllBytes(file);Files.delete(ProjectMedia.wav(cache(),s,s.assets.getFirst().id));
        assertEquals("error.project_assets",assertThrows(StudioException.class,()->ProjectFiles.open(file,cache())).key);s.bpm=147;assertThrows(StudioException.class,()->ProjectFiles.save(file,cache(),s));assertArrayEquals(score,Files.readAllBytes(file));
    }
    @Test void folderNamesDoNotLeakIntoScoresBlueprintsOrTheSerializedUndoState() throws Exception {
        var s=song();Path file=save(s,"No absolute paths");assertEquals(file.getParent(),ProjectStore.copy(s).projectDirectory);assertNull(ProjectStore.decode(ProjectStore.encode(s)).projectDirectory);assertNull(BlueprintData.unpack(BlueprintData.pack(s)).projectDirectory);
        var json=com.google.gson.JsonParser.parseString(ProjectStore.encode(s)).getAsJsonObject();json.addProperty("projectDirectory","C:/untrusted/path");assertNull(ProjectStore.decode(json.toString()).projectDirectory);
    }
    @Test void ambiguousFoldersAndAnotherProjectInTheDestinationAreRejected() throws Exception {
        var s=song();Path file=save(s,"Conflict");Files.copy(file,file.resolveSibling("different.musicstudio"));assertEquals("error.project_folder_ambiguous",assertThrows(StudioException.class,()->ProjectFiles.resolveFile(file.getParent())).key);
        assertEquals("error.project_folder_used",assertThrows(StudioException.class,()->ProjectFiles.newProjectFile(temp.resolve("projects/Conflict.musicstudio"))).key);assertEquals(file,ProjectFiles.resolveFile(file));
    }
    @Test void saveAsCannotSilentlyOverwriteAnExistingNestedDestination() throws Exception {
        var s=song();Path file=save(s,"Existing");byte[] original=Files.readAllBytes(file);var different=song();different.title="A different score";
        assertEquals("error.project_folder_used",assertThrows(StudioException.class,()->save(different,"Existing")).key);assertArrayEquals(original,Files.readAllBytes(file));assertEquals(file,ProjectFiles.newProjectFile(file));
    }
    @Test void saveAsKeepsSourceFilesForAnImportThatCanStillBeRedone() throws Exception {
        var s=song();Path original=save(s,"Before");Path audio=temp.resolve("undone.wav");float[] pcm=new float[2205];Arrays.fill(pcm,.3f);AudioFiles.wav(audio,new AudioFiles.Wave(pcm,pcm.clone()),1);
        var undone=AudioFiles.importAsset(audio,cache(),s,AudioFiles.codec(temp));assertEquals(1,s.assets.size());var copy=ProjectStore.copy(s);Path cloned=save(copy,"After");assertTrue(Files.isRegularFile(ProjectMedia.wav(cache(),copy,undone.id)));copy.assets.add(undone);ProjectFiles.save(cloned,cache(),copy);
        assertEquals(2,ProjectFiles.open(cloned,temp.resolve("empty")).assets.size());assertEquals(1,ProjectFiles.open(original,temp.resolve("empty")).assets.size());
    }
}
