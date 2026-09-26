import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Prints the family triple point the engine computed for each crystal (reflection; exploratory). */
public final class P5TripleProbe {
    public static void main(String[] args) throws Exception {
        var s = P5Probe.S;
        Field f = s.getClass().getDeclaredField("crystals");
        f.setAccessible(true);
        for (Object c : (List<?>) f.get(s)) {
            Method t = c.getClass().getDeclaredMethod("tripleTemperature");
            Method p = c.getClass().getDeclaredMethod("triplePressure");
            t.setAccessible(true);
            p.setAccessible(true);
            System.out.printf("family triple point: %.12f K, %.6f Pa%n", (double) t.invoke(c), (double) p.invoke(c));
        }
    }
}
