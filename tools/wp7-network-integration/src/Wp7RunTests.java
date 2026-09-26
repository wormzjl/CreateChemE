import java.io.PrintWriter;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

/** WP7 scratch: runs JUnit 5 test classes (or Class#method) outside Gradle and prints the summary. */
public class Wp7RunTests {
    public static void main(String[] args) throws Exception {
        var builder = LauncherDiscoveryRequestBuilder.request();
        for (String name : args) builder.selectors(name.contains("#") ? selectMethod(name) : selectClass(Class.forName(name)));
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(builder.build(), listener);
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.out), 30);
    }
}
