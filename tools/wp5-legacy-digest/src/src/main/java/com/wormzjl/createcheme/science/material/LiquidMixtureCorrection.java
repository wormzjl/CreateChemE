package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonObject;
import java.util.List;

/** Immutable empirical transport correction. It carries no elemental or reactive state. */
public record LiquidMixtureCorrection(List<Double> coefficients, List<Descriptor> descriptors,
        double referenceKelvin, double slopeCapKelvin) {
    public record Descriptor(double participation, double residueRetention, double activationSlope) {
        public static final Descriptor NONE = new Descriptor(0, 0, 0);
        public Descriptor {
            if (!Double.isFinite(participation) || participation < 0 || participation > 1
                    || !Double.isFinite(residueRetention) || residueRetention < 0 || residueRetention > 1
                    || !Double.isFinite(activationSlope) || activationSlope < 0
                    || participation == 0 && (residueRetention != 0 || activationSlope != 0))
                throw new IllegalArgumentException("liquid_mixture_descriptor: invalid participation, retention or slope");
        }
        static Descriptor read(JsonObject property) {
            if (!property.has("liquid_mixture_descriptor")) return NONE;
            var d = property.getAsJsonObject("liquid_mixture_descriptor");
            return new Descriptor(number(d,"participation"), number(d,"residue_retention"), number(d,"activation_slope"));
        }
    }
    public LiquidMixtureCorrection {
        coefficients = List.copyOf(coefficients); descriptors = List.copyOf(descriptors);
        if (coefficients.size() != 4 || coefficients.stream().anyMatch(v -> !Double.isFinite(v))
                || !Double.isFinite(referenceKelvin) || referenceKelvin <= 0
                || !Double.isFinite(slopeCapKelvin) || slopeCapKelvin < referenceKelvin)
            throw new IllegalArgumentException("liquid_mixture: invalid coefficients or temperature references");
    }
    static LiquidMixtureCorrection read(JsonObject record, List<Descriptor> descriptors) {
        if (!record.has("liquid_mixture")) return new LiquidMixtureCorrection(List.of(0.,0.,0.,0.), descriptors,323.15,373.15);
        var o = record.getAsJsonObject("liquid_mixture");
        if (!o.has("type") || !o.get("type").getAsString().equals("symmetric_pair_groups_v1"))
            throw new IllegalArgumentException("liquid_mixture.type: unsupported correction");
        if (!o.has("coefficients") || !o.get("coefficients").isJsonArray())
            throw new IllegalArgumentException("liquid_mixture.coefficients: required array");
        var c = new java.util.ArrayList<Double>();
        for (var value : o.getAsJsonArray("coefficients")) c.add(value.getAsDouble());
        return new LiquidMixtureCorrection(c,descriptors,number(o,"reference_kelvin"),number(o,"slope_cap_kelvin"));
    }
    private static double number(JsonObject o,String name) {
        if (!o.has(name) || !o.get(name).isJsonPrimitive() || !o.getAsJsonPrimitive(name).isNumber())
            throw new IllegalArgumentException("liquid_mixture."+name+": required number");
        double value=o.get(name).getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("liquid_mixture."+name+": nonfinite");
        return value;
    }
    /** Pair sums factored to O(n). Nonparticipants still dilute the participating mole fractions. */
    public double logCorrection(double temperature, double[] amounts) {
        if (!Double.isFinite(temperature) || temperature <= 0 || amounts.length != descriptors.size())
            throw new IllegalArgumentException("Invalid liquid correction temperature or basis");
        double total=0;
        for (double n:amounts) {
            if (!Double.isFinite(n) || n<0) throw new IllegalArgumentException("Invalid liquid correction amount");
            total+=n;
        }
        if (!(total>0) || !Double.isFinite(total)) throw new IllegalArgumentException("Empty liquid correction mixture");
        double x=0,x2=0,xh=0,xh2=0,xe=0,xhe=0,x2e=0,xh2e=0;
        for (int i=0;i<amounts.length;i++) {
            var d=descriptors.get(i);double v=amounts[i]/total*d.participation(),h=v*d.residueRetention(),e=d.activationSlope();
            x+=v;x2+=v*v;xh+=h;xh2+=h*h;xe+=v*e;xhe+=h*e;x2e+=v*v*e;xh2e+=h*h*e;
        }
        double fa=x*x-x2,fh=xh*xh-xh2,ea=x*xe-x2e,eh=xh*xhe-xh2e;
        double q=1000*(1/Math.min(temperature,slopeCapKelvin)-1/referenceKelvin);
        return coefficients.get(0)*(fa-fh)+coefficients.get(1)*fh
                +coefficients.get(2)*(ea-eh)*q+coefficients.get(3)*eh*q;
    }
}
