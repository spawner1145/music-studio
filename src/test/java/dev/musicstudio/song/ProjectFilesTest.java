package dev.musicstudio.song;

import dev.musicstudio.media.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectFilesTest {
    @TempDir Path temp;
    @Test void portableSaveAsAndOpenCarryOriginalAndCanonicalAudioAndKeepBackup() throws Exception {
        var s=SongProject.legacyDemo();s.channels.getFirst().name="独奏 renamed";s.channels.getFirst().nameKey="";s.channels.getFirst().color=0xaabbcc;s.channels.getFirst().displayHeight=132;
        Path projects=temp.resolve("origin"),other=temp.resolve("other"),source=temp.resolve("tone.wav");float[] pcm=new float[4410];AudioFiles.wav(source,new AudioFiles.Wave(pcm,pcm.clone()),1);var asset=AudioFiles.importAsset(source,projects,s,AudioFiles.codec(temp));s.assets.add(asset);s.channels.getFirst().sampleId=asset.id;
        Path file=temp.resolve("任意目录/project.musicstudio");ProjectFiles.save(file,projects,s);s.title="Second save";ProjectFiles.save(file,projects,s);assertTrue(Files.isRegularFile(file.resolveSibling("project.musicstudio.bak")));
        var loaded=ProjectFiles.open(file,other);assertEquals(ProjectStore.encode(s),ProjectStore.encode(loaded));assertArrayEquals(Files.readAllBytes(source),Files.readAllBytes(ProjectMedia.assets(other,loaded).resolve(asset.original)));assertEquals(4410,AudioFiles.read(ProjectMedia.wav(other,loaded,asset.id)).frames());assertEquals(132,loaded.channels.getFirst().displayHeight);
    }
    @Test void traversalArchivesAreRejectedBeforeWritingAssets() throws Exception {Path file=temp.resolve("bad.musicstudio");try(var zip=new ZipOutputStream(Files.newOutputStream(file))){zip.putNextEntry(new ZipEntry("project.json"));zip.write(ProjectStore.encode(SongProject.legacyDemo()).getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();zip.putNextEntry(new ZipEntry("../escaped.txt"));zip.write(new byte[]{1});zip.closeEntry();}assertThrows(StudioException.class,()->ProjectFiles.open(file,temp.resolve("projects")));assertFalse(Files.exists(temp.resolve("escaped.txt")));}
    @Test void legacyJsonCanStillBeOpenedAndMissingMediaDoesNotPretendToLoad() throws Exception {var s=SongProject.legacyDemo();Path file=temp.resolve("legacy.json");Files.writeString(file,ProjectStore.encode(s));assertEquals(s.title,ProjectFiles.open(file,temp.resolve("projects")).title);}
}
