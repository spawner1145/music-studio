package dev.musicstudio.media;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** File-backed transfers keep only one network packet in memory, regardless of asset size. */
public final class AudioTransfer {
    public static final int CHUNK=24000;
    private AudioTransfer(){}
    public record Part(int index,int total,byte[] data){}
    public static final class Source implements AutoCloseable {
        private final InputStream input;
        private final long size;
        private final int total;
        private int part;
        public Source(Path file) throws IOException {
            size=Files.size(file);if(size<=0)throw new IOException("Empty audio file");
            total=Math.toIntExact((size-1)/CHUNK+1);input=new BufferedInputStream(Files.newInputStream(file));
        }
        public int part(){return part;}
        public boolean complete(){return part==total;}
        public Part next() throws IOException {
            if(complete())throw new EOFException("Audio transfer is complete");
            int length=(int)Math.min(CHUNK,size-(long)part*CHUNK);byte[] data=input.readNBytes(length);
            if(data.length!=length)throw new EOFException("Audio file changed during transfer");
            return new Part(part++,total,data);
        }
        @Override public void close() throws IOException {input.close();}
    }
    public static final class Receiver implements AutoCloseable {
        private final Path temporary;
        private final OutputStream output;
        private final MessageDigest digest;
        private int part,total;
        private long size;
        private boolean closed,finished;
        public Receiver(Path directory) throws IOException {
            try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            Files.createDirectories(directory);temporary=Files.createTempFile(directory,"transfer-",".tmp");output=new BufferedOutputStream(Files.newOutputStream(temporary));
        }
        public void accept(int index,int count,byte[] data) throws IOException {
            if(closed||count<1||index!=part||index>=count||part>0&&total!=count||data.length<1||data.length>CHUNK||index<count-1&&data.length!=CHUNK)throw new IOException("Invalid audio chunk sequence");
            if(part==0&&(data.length<4||data[0]!='O'||data[1]!='g'||data[2]!='g'||data[3]!='S'))throw new IOException("Invalid OGG header");
            total=count;output.write(data);digest.update(data);size+=data.length;part++;
        }
        public boolean complete(){return total>0&&part==total;}
        public void finish(String expectedHash,Path target) throws IOException {
            if(closed||!complete()||size<32)throw new IOException("Incomplete audio transfer");
            output.close();closed=true;if(!HexFormat.of().formatHex(digest.digest()).equals(expectedHash))throw new IOException("Audio hash mismatch");
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);finished=true;
        }
        @Override public void close() throws IOException {
            try{if(!closed){closed=true;output.close();}}finally{if(!finished)Files.deleteIfExists(temporary);}
        }
    }
}
