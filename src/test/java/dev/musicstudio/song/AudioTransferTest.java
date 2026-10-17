package dev.musicstudio.song;

import dev.musicstudio.media.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioTransferTest {
    @TempDir Path temp;
    private Path source(int size) throws Exception {
        Path file=temp.resolve("source.ogg");byte[] block=new byte[65536];new Random(42).nextBytes(block);
        try(var output=Files.newOutputStream(file)){output.write(new byte[]{'O','g','g','S'});for(int remaining=size-4;remaining>0;remaining-=Math.min(block.length,remaining))output.write(block,0,Math.min(block.length,remaining));}return file;
    }
    @Test void fileAboveEightMiBTransfersInBoundedChunksAndPreservesEveryByte() throws Exception {
        Path original=source(9*1024*1024+37),target=temp.resolve("received.ogg");String hash=ProjectMedia.hash(original);int count=0;
        try(var reader=new AudioTransfer.Source(original);var receiver=new AudioTransfer.Receiver(temp)){
            while(!reader.complete()){var part=reader.next();assertTrue(part.data().length<=AudioTransfer.CHUNK);receiver.accept(part.index(),part.total(),part.data());count++;}assertTrue(count>350);assertTrue(receiver.complete());receiver.finish(hash,target);
        }assertEquals(-1,Files.mismatch(original,target));try(var files=Files.list(temp)){assertFalse(files.anyMatch(p->p.toString().endsWith(".tmp")));}
    }
    @Test void corruptPayloadCannotReplaceAnExistingFileAndTemporaryFilesAreRemoved() throws Exception {
        Path original=source(40000),target=temp.resolve("received.ogg");Files.writeString(target,"keep");
        try(var reader=new AudioTransfer.Source(original);var receiver=new AudioTransfer.Receiver(temp)){
            while(!reader.complete()){var part=reader.next();receiver.accept(part.index(),part.total(),part.data());}
            assertThrows(IOException.class,()->receiver.finish("0".repeat(64),target));
        }assertEquals("keep",Files.readString(target));try(var files=Files.list(temp)){assertFalse(files.anyMatch(p->p.toString().endsWith(".tmp")));}
    }
    @Test void interruptedOutOfOrderAndNonOggTransfersCannotBePublished() throws Exception {
        Path original=source(40000);try(var reader=new AudioTransfer.Source(original);var receiver=new AudioTransfer.Receiver(temp)){
            var first=reader.next();assertThrows(IOException.class,()->receiver.accept(1,first.total(),first.data()));receiver.accept(first.index(),first.total(),first.data());assertThrows(IOException.class,()->receiver.accept(0,first.total(),first.data()));assertThrows(IOException.class,()->receiver.finish(ProjectMedia.hash(original),temp.resolve("no.ogg")));
        }
        try(var receiver=new AudioTransfer.Receiver(temp)){assertThrows(IOException.class,()->receiver.accept(0,1,new byte[32]));}
        try(var files=Files.list(temp)){assertFalse(files.anyMatch(p->p.toString().endsWith(".tmp")));}
    }
}
