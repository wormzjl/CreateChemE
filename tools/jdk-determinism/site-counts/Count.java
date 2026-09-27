package siteprobe;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
/** One-off call-site counter for the JDK determinism study (not in the repo). */
public final class Count {
    private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            String out = System.getProperty("sitecount.out");
            if (out == null) return;
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, LongAdder> e : new TreeMap<>(COUNTS).entrySet()) b.append(e.getValue().sum()).append(' ').append(e.getKey()).append('\n');
            try { java.nio.file.Files.writeString(java.nio.file.Path.of(out), b.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch (Exception ex) { throw new RuntimeException(ex); }
        }));
    }
    private static void hit(String s) { COUNTS.computeIfAbsent(s, k -> new LongAdder()).increment(); }
    public static double log(String s, double x) { hit(s); return Math.log(x); }
    public static double exp(String s, double x) { hit(s); return Math.exp(x); }
    public static double pow(String s, double x, double y) { hit(s); return Math.pow(x, y); }
    public static double cbrt(String s, double x) { hit(s); return Math.cbrt(x); }
    public static double log1p(String s, double x) { hit(s); return Math.log1p(x); }
    public static double expm1(String s, double x) { hit(s); return Math.expm1(x); }
    public static double cos(String s, double x) { hit(s); return Math.cos(x); }
    public static double log10(String s, double x) { hit(s); return Math.log10(x); }
    public static double acos(String s, double x) { hit(s); return Math.acos(x); }
}
