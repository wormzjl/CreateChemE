package harness;
import java.math.BigDecimal;
public final class XMath1 {
    public static final String MODE = System.getProperty("xmath.cbrt", "correct");
    public static double abs(double a){return Math.abs(a);}
    public static double acos(double a){return Math.acos(a);}
    public static double clamp(double v,double lo,double hi){return Math.clamp(v,lo,hi);}
    public static double copySign(double a,double b){return Math.copySign(a,b);}
    public static double cos(double a){return Math.cos(a);}
    public static double exp(double a){return Math.exp(a);}
    public static double fma(double a,double b,double c){return Math.fma(a,b,c);}
    public static double log(double a){return Math.log(a);}
    public static double max(double a,double b){return Math.max(a,b);}
    public static double min(double a,double b){return Math.min(a,b);}
    public static double sqrt(double a){return Math.sqrt(a);}
    public static double ulp(double a){return Math.ulp(a);}
    public static double cbrt(double x){
        double y=Math.cbrt(x);
        if(MODE.equals("fdlibm")||x==0||Double.isNaN(x)||Double.isInfinite(x))return y;
        // correctly rounded: pick among neighbours the one closest to the true cube root (compare cubes of midpoints)
        BigDecimal bx=new BigDecimal(x);
        double best=y;
        for(double c:new double[]{Math.nextDown(y),Math.nextUp(y)}){
            // c is better than best iff the true root lies on c's side of their midpoint
            BigDecimal mid=new BigDecimal(best).add(new BigDecimal(c)).divide(BigDecimal.valueOf(2));
            BigDecimal m3=mid.multiply(mid).multiply(mid);
            boolean cAbove=c>best;
            int cmp=bx.compareTo(m3);
            if(cAbove?cmp>0:cmp<0)best=c;
        }
        return best;
    }
}
