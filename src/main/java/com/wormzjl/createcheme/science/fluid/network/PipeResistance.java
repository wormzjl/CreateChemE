package com.wormzjl.createcheme.science.fluid.network;

/** Homogeneous Darcy-Weisbach loss with a C1 laminar/turbulent blend over Reynolds 2000..4000. */
public final class PipeResistance {
    /** Fixed steel-wall roughness for player-built piping. */
    public static final double DEFAULT_ROUGHNESS_METRES=0.000045;
    private PipeResistance() {}
    public record Geometry(double length,double diameter,double roughness,double minorLoss) {
        public Geometry {
            if(!Double.isFinite(length)||length<=0||!Double.isFinite(diameter)||diameter<=0
                    ||!Double.isFinite(roughness)||roughness<0||roughness>=diameter
                    ||!Double.isFinite(minorLoss)||minorLoss<0)throw new IllegalArgumentException("Invalid SI pipe geometry");
        }
        public double area(){return Math.PI*diameter*diameter/4;}
    }
    public record Loss(double pressureDrop,double massFlowDerivative,double reynolds) {}
    public static Loss evaluate(Geometry pipe,double massFlow,double density,double viscosity) {
        double[] rest=new double[2];
        return new Loss(pressureDrop(pipe,massFlow,density,viscosity,rest),rest[0],rest[1]);
    }
    /** The pressure drop alone, which is all the residual reads, without a record per section per
     * edge per evaluation. Same arithmetic and same checks as {@link #evaluate}. */
    public static double pressureDrop(Geometry pipe,double massFlow,double density,double viscosity) {
        return pressureDrop(pipe,massFlow,density,viscosity,null);
    }
    /** {@code rest}, when present, receives the mass-flow derivative and the Reynolds number. */
    private static double pressureDrop(Geometry pipe,double massFlow,double density,double viscosity,double[] rest) {
        if(!Double.isFinite(massFlow)||!Double.isFinite(density)||density<=0||!Double.isFinite(viscosity)||viscosity<=0) {
            throw new IllegalArgumentException("Invalid pipe transport properties");
        }
        double q=Math.abs(massFlow),reSlope=4/(Math.PI*viscosity*pipe.diameter),re=q*reSlope;
        double area=pipe.area(),coefficient=1/(2*density*area*area);
        double laminarSlope=128*viscosity*pipe.length/(Math.PI*density*Math.pow(pipe.diameter,4));
        double loss=laminarSlope*q,derivative=laminarSlope;
        if(re>2000) {
            double argument=pipe.roughness/(3.7*pipe.diameter)+5.74/Math.pow(re,.9);
            double logarithm=Math.log10(argument),friction=.25/(logarithm*logarithm);
            double df=-.5/Math.pow(logarithm,3)*(-.9*5.74/Math.pow(re,1.9))/(argument*Math.log(10));
            double turbulent=friction*pipe.length/pipe.diameter*coefficient*q*q;
            double turbulentSlope=pipe.length/pipe.diameter*coefficient*(2*q*friction+q*q*df*reSlope);
            double s=Math.min(1,(re-2000)/2000),weight=s*s*(3-2*s),weightSlope=6*s*(1-s)*reSlope/2000;
            derivative=derivative*(1-weight)+turbulentSlope*weight+(turbulent-loss)*weightSlope;
            loss=loss*(1-weight)+turbulent*weight;
        }
        loss+=pipe.minorLoss*coefficient*q*q;derivative+=2*pipe.minorLoss*coefficient*q;
        if(!Double.isFinite(loss)||!Double.isFinite(derivative)||derivative<=0)throw new IllegalArgumentException("Nonfinite pipe pressure loss");
        if(rest!=null){rest[0]=derivative;rest[1]=re;}
        return Math.copySign(loss,massFlow);
    }
}
