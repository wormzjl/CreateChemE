package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiquidMixtureCorrectionTest {
    private static final List<Double> COEFFICIENTS=List.of(.76,.46,.08,.05);
    private static LiquidMixtureCorrection model() {
        return new LiquidMixtureCorrection(COEFFICIENTS,List.of(
                new LiquidMixtureCorrection.Descriptor(1,0,3),
                new LiquidMixtureCorrection.Descriptor(1,.2,5),
                new LiquidMixtureCorrection.Descriptor(1,1,8),
                LiquidMixtureCorrection.Descriptor.NONE),323.15,373.15);
    }
    @Test void factoredRuleMatchesIndependentSymmetricPairSum() {
        var model=model();var random=new Random(19278);
        for (int sample=0;sample<100;sample++) {
            double[] n=random.doubles(4,.0001,10).toArray();double sum=java.util.Arrays.stream(n).sum();
            double temperature=293.15+sample*5;double expected=0,q=1000*(1/Math.min(temperature,373.15)-1/323.15);
            for (int i=0;i<n.length;i++) for (int j=i+1;j<n.length;j++) {
                var a=model.descriptors().get(i);var b=model.descriptors().get(j);
                double h=a.residueRetention()*b.residueRetention(),e=(a.activationSlope()+b.activationSlope())/2;
                double interaction=COEFFICIENTS.get(0)*(1-h)+COEFFICIENTS.get(1)*h
                        +q*e*(COEFFICIENTS.get(2)*(1-h)+COEFFICIENTS.get(3)*h);
                expected+=2*n[i]/sum*n[j]/sum*a.participation()*b.participation()*interaction;
            }
            assertEquals(expected,model.logCorrection(temperature,n),2e-14);
        }
    }
    @Test void pureLimitsAndInertDilutionDoNotChangePureTransport() {
        for (int i=0;i<4;i++) {double[] n=new double[4];n[i]=7;assertEquals(0,model().logCorrection(293.15,n),1e-14);}
        double base=model().logCorrection(313.15,new double[]{1,1,0,0});
        assertEquals(base/4,model().logCorrection(313.15,new double[]{1,1,0,2}),1e-14);
        assertEquals(base,model().logCorrection(313.15,new double[]{5,5,0,0}),1e-14);
    }
    @Test void malformedDescriptorsAndMixturesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new LiquidMixtureCorrection.Descriptor(0,.1,0));
        assertThrows(IllegalArgumentException.class,()->new LiquidMixtureCorrection.Descriptor(1,1,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->model().logCorrection(300,new double[4]));
        assertThrows(IllegalArgumentException.class,()->model().logCorrection(300,new double[]{1,0,-1,0}));
    }
    @Test void transportEditChangesOnlyTransportFingerprintAndNamesResourceOnError() {
        String packageId="createcheme:tjl20_methane",path="data/createcheme/materials/packages/tjl20.json";
        var baseline=MaterialCatalog.bundled();var resources=new HashMap<>(baseline.resources());
        resources.keySet().removeIf(key->key.contains("/networks/")); // Isolated column transport override, not a shared network preset.
        var record=JsonParser.parseString(resources.get(path)).getAsJsonObject();
        record.add("liquid_mixture",JsonParser.parseString("{\"type\":\"symmetric_pair_groups_v1\",\"coefficients\":[1,2,3,4],\"reference_kelvin\":323.15,\"slope_cap_kelvin\":373.15}"));
        resources.put(path,record.toString());var changed=MaterialCatalog.parse(resources);
        assertEquals(baseline.requirePackage(packageId).fingerprint(),changed.requirePackage(packageId).fingerprint());
        assertNotEquals(baseline.viscosityFingerprint(packageId),changed.viscosityFingerprint(packageId));
        record.getAsJsonObject("liquid_mixture").addProperty("slope_cap_kelvin",-1);
        resources.put(path,record.toString());
        assertTrue(assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources)).getMessage().contains(path));
    }
}
