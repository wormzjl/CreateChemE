import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Stack <out> <x> <y> <w> <h> <in...>: crops the same region of each screenshot and stacks them, labelled, for reading. */
public class Stack {
    public static void main(String[] a) throws Exception {
        int x=Integer.parseInt(a[1]),y=Integer.parseInt(a[2]),w=Integer.parseInt(a[3]),h=Integer.parseInt(a[4]);int n=a.length-5;
        BufferedImage out=new BufferedImage(w+170,h*n,BufferedImage.TYPE_INT_RGB);Graphics2D g=out.createGraphics();g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.PLAIN,14));
        for(int i=0;i<n;i++){File f=new File(a[5+i]);BufferedImage in=ImageIO.read(f);g.drawImage(in.getSubimage(x,y,w,h),170,i*h,null);g.drawString(f.getName().replace(".png",""),4,i*h+h/2+5);}
        ImageIO.write(out,"png",new File(a[0]));System.out.println("stacked "+n);
    }
}
