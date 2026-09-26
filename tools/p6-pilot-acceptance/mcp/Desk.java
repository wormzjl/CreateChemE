import java.awt.*;
import java.io.File;
import javax.imageio.ImageIO;

/** Whole-desktop capture, so I can see where the client window actually is. */
public class Desk {
    public static void main(String[] args) throws Exception {
        var size = Toolkit.getDefaultToolkit().getScreenSize();
        var image = new Robot().createScreenCapture(new Rectangle(size));
        ImageIO.write(image, "png", new File(args[0]));
        System.out.println("desktop " + size.width + "x" + size.height + " -> " + args[0]);
    }
}
