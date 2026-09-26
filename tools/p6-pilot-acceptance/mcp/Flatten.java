import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Screenshots from the bridge carry alpha=0 on every pixel; drop the alpha channel in place. */
public class Flatten {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            BufferedImage in = ImageIO.read(new File(path));
            BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < in.getHeight(); y++)
                for (int x = 0; x < in.getWidth(); x++)
                    out.setRGB(x, y, in.getRGB(x, y) & 0xFFFFFF);
            ImageIO.write(out, "png", new File(path));
            System.out.println("flattened " + path + " " + in.getWidth() + "x" + in.getHeight());
        }
    }
}
