/**
 * Checks the chain-100 fixture input of SolverRegressionHarness / BitwiseProbe: pressure_i = 150000 + 1000 cos(0.7 i),
 * i = 0..99, with Math.cos and with StrictMath.cos. Prints the cosines that differ and whether any pressure differs
 * bitwise. Run: java CosChain.java (warmed 20000 times so the JIT intrinsic is in use; also try -Xint).
 */
public class CosChain {
    public static void main(String[] args) {
        int cosDiffer = 0, pressureDiffer = 0;
        for (int r = 0; r < 20000; r++) {
            cosDiffer = 0;
            pressureDiffer = 0;
            for (int i = 0; i < 100; i++) {
                double m = Math.cos(i * .7), s = StrictMath.cos(i * .7);
                if (Double.doubleToLongBits(m) != Double.doubleToLongBits(s)) {
                    cosDiffer++;
                    if (r == 19999) {
                        System.out.println("i=" + i + " Math.cos=" + m + " StrictMath.cos=" + s);
                    }
                }
                if (Double.doubleToLongBits(150000 + 1000 * m) != Double.doubleToLongBits(150000 + 1000 * s)) {
                    pressureDiffer++;
                }
            }
        }
        System.out.println(System.getProperty("java.runtime.version") + ": cosines differing " + cosDiffer
                + " of 100, pressures differing " + pressureDiffer + " of 100");
    }
}
