import com.wormzjl.createcheme.science.thermo.*;
public class Four { public static void main(String[] a) {
  var k = Scratch.kernel(new double[]{190.564, 305.32, 369.83, 126.192}, new double[]{4599200.0, 4872000.0, 4248000.0, 3395800}, new double[]{0.01142, 0.099, 0.152, 0.0372});
  var s = new TangentPlaneStability(k);
  double[] z = {0.4, 0.15, 0.15, 0.3};
  for (double[] tp : new double[][]{{150,1e6},{200,2e6},{250,3e6},{300,2e6},{120,2e6},{180,5e6},{220,6e6},{350,10e6}}) {
    var r = s.test(tp[0], tp[1], z);
    System.out.printf("T=%.0f P=%.1f %s tm=%+.3e feed=%s trial=%s trials=%d it=%d ev=%d dv=%d%n", tp[0], tp[1]/1e6, r.verdict(), r.minimumTangentPlaneDistance(), r.feedRoot(), r.trialRoot(), r.trials(), r.iterations(), r.kernelEvaluations(), r.derivativeEvaluations());
  }
}}
