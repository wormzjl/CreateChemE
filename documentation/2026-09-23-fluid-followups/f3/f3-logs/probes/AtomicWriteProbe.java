import java.nio.*;import java.nio.channels.*;import java.nio.file.*;import java.util.*;
public class AtomicWriteProbe {
    static void atomic(Path target,byte[] bytes) throws Exception {
        Path temp=Files.createTempFile(target.getParent(),target.getFileName().toString(),".tmp");
        try(var ch=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){ch.write(ByteBuffer.wrap(bytes));ch.force(true);}
        try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    public static void main(String[] a) throws Exception {
        Path dir=Path.of(a[0]);Files.createDirectories(dir);var rnd=new Random(1);
        for(int[] c:new int[][]{{100,5_000},{1000,5_000},{100,50_000},{1,5_000_000},{1,50_000_000}}) {
            int n=c[0],size=c[1];byte[] b=new byte[size];rnd.nextBytes(b);
            for(int rep=0;rep<2;rep++){
                long t0=System.nanoTime();for(int i=0;i<n;i++)atomic(dir.resolve("u"+i+".dat"),b);long t1=System.nanoTime();
                System.out.printf(Locale.ROOT,"%d files x %d bytes: %.1f ms total, %.3f ms per file%n",n,size,(t1-t0)/1e6,(t1-t0)/1e6/n);
            }
            long t0=System.nanoTime();try(var s=Files.list(dir)){for(var p:s.toList()){Files.readAllBytes(p);}}long t1=System.nanoTime();
            System.out.printf(Locale.ROOT,"  read back all: %.1f ms%n",(t1-t0)/1e6);
            try(var s=Files.list(dir)){for(var p:s.toList())Files.delete(p);}
        }
    }
}
