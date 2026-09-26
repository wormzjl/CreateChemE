import com.wormzjl.createcheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.createcheme.science.fluid.thermo.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
/** WP3: vapour share of the crude preset at the pump fixtures' conditions, and its bubble point (bisection on P). */
public class CrudeFlashProbe {
    public static void main(String[] a) {
        var catalog=MaterialCatalog.bundled();var model=FluidThermodynamics.forNetwork(catalog,FluidMaterialCatalog.networkPackage(catalog),1e-9);
        var composition=FluidPresetCatalog.resolve(catalog).stream().filter(p->p.id().equals("createcheme:tia_juana_light_methane")).findFirst().orElseThrow().moleFractions();
        for(double t:new double[]{298.15,350}) {
            for(double p:new double[]{101325,150000,200000,300000,500000,1e6,2e6}) {
                var s=model.flashTP(t,p,composition,()->{});
                double alpha=s.vaporVolume()/(s.vaporVolume()+s.liquidVolume()+s.waterVolume());
                System.out.printf(Locale.ROOT,"T=%.2f P=%.0f vaporVolumeShare=%.6e vapourMoleFraction=%.6e%n",t,p,alpha,Arrays.stream(s.vapor()).sum()/(Arrays.stream(s.vapor()).sum()+Arrays.stream(s.liquid()).sum()));
            }
            double lo=101325/10.0,hi=5e6;
            for(int i=0;i<80;i++){double mid=Math.sqrt(lo*hi);var s=model.flashTP(t,mid,composition,()->{});if(s.vaporVolume()>0)lo=mid;else hi=mid;}
            System.out.printf(Locale.ROOT,"T=%.2f bubble point %.1f Pa%n",t,hi);
        }
    }
}
