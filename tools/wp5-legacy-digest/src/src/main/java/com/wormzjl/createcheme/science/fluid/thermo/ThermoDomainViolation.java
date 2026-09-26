package com.wormzjl.createcheme.science.fluid.thermo;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * A state the fluid property package cannot evaluate: a component the state carries, or the package's own
 * envelope, is outside the temperature or pressure range its data declares. See
 * {@code documentation/fluid-followups/THERMO_DOMAIN_ERROR.md} for where it surfaces and how a range is declared.
 *
 * <p>It is an {@link IllegalArgumentException}, so every existing refusal path (a rejected Newton trial, a rejected
 * substep, a held interval) still treats it as a state outside the property domain; it is its own type so it is
 * never mistaken for malformed input (a wrong array length, a non-finite number), which stays a plain
 * {@code IllegalArgumentException}, and so a solver failure it caused can say so.
 *
 * <p>{@link #component()} is a basis component id ({@code Nitrogen}, {@code crude_pc03}, {@code Water}) or
 * {@link #PACKAGE} for the package envelope; {@link #property()} is temperature or pressure; {@link #code()} is a
 * stable machine-readable code; {@link #node()} is the network node whose state was refused, or {@link #NO_NODE}
 * where the property evaluation does not know it (the solver attaches it with {@link #at}).
 */
public final class ThermoDomainViolation extends IllegalArgumentException {
    /** The component name of a violation of the package envelope rather than of one component's range. */
    public static final String PACKAGE="package";
    /** The prefix of every rejection-reason key this violation produces. */
    public static final String REASON_PREFIX="thermo-domain: ";
    public static final long NO_NODE=Long.MIN_VALUE;
    public enum Property {
        TEMPERATURE("temperature","K"),PRESSURE("pressure","Pa");
        private final String label,unit;
        Property(String label,String unit){this.label=label;this.unit=unit;}
        public String label(){return label;}
        public String unit(){return unit;}
    }
    /** Stable and machine-readable. */
    public enum Code {
        THERMO_DOMAIN_TEMPERATURE_BELOW,THERMO_DOMAIN_TEMPERATURE_ABOVE,THERMO_DOMAIN_PRESSURE_BELOW,THERMO_DOMAIN_PRESSURE_ABOVE;
        static Code of(Property property,boolean below) {
            return property==Property.TEMPERATURE?(below?THERMO_DOMAIN_TEMPERATURE_BELOW:THERMO_DOMAIN_TEMPERATURE_ABOVE)
                    :(below?THERMO_DOMAIN_PRESSURE_BELOW:THERMO_DOMAIN_PRESSURE_ABOVE);
        }
        public boolean below(){return this==THERMO_DOMAIN_TEMPERATURE_BELOW||this==THERMO_DOMAIN_PRESSURE_BELOW;}
    }
    private final String packageId,component;
    private final Property property;
    private final double value,minimum,maximum;
    private final Code code;
    private final long node;

    public ThermoDomainViolation(String packageId,String component,Property property,double value,double minimum,double maximum) {
        this(packageId,component,property,value,minimum,maximum,NO_NODE);
    }
    private ThermoDomainViolation(String packageId,String component,Property property,double value,double minimum,double maximum,long node) {
        super(message(packageId,component,property,value,minimum,maximum,node));
        this.packageId=Objects.requireNonNull(packageId);this.component=Objects.requireNonNull(component);this.property=Objects.requireNonNull(property);
        if(!Double.isFinite(value)||!(minimum<maximum))throw new IllegalArgumentException("A domain violation needs a finite value and a range");
        if(value>=minimum&&value<=maximum)throw new IllegalArgumentException("Value "+value+" is inside "+minimum+".."+maximum);
        this.value=value;this.minimum=minimum;this.maximum=maximum;this.node=node;
        code=Code.of(property,value<minimum);
    }
    /** The same violation at a known network node; a violation that already names a node keeps it. */
    public ThermoDomainViolation at(long node) {
        return this.node!=NO_NODE||node==NO_NODE?this:new ThermoDomainViolation(packageId,component,property,value,minimum,maximum,node);
    }
    public String packageId(){return packageId;}
    public String component(){return component;}
    public Property property(){return property;}
    public double value(){return value;}
    public double minimum(){return minimum;}
    public double maximum(){return maximum;}
    public Code code(){return code;}
    public long node(){return node;}
    /** The bound the value crossed. */
    public double bound(){return code.below()?minimum:maximum;}
    /**
     * The rejection-reason key of this violation: one key per component, property and side, so a solve that meets the
     * same boundary a thousand times counts it under one key instead of a thousand. The value is not part of the key;
     * it is in {@link #getMessage()} and in the island's status line.
     */
    public String reasonKey() {
        return REASON_PREFIX+component+" "+property.label()+" "+(code.below()?"<":">")+" "+number(bound())+" "+property.unit();
    }
    /** What happened, for a status line: {@code Nitrogen at 270.12 K is below 273.16 K in <where>}. */
    public String sentence(String where) {
        return (component.equals(PACKAGE)?"the package envelope ("+packageId+")":component)+" at "+value(property,value,bound())
                +" is "+(code.below()?"below ":"above ")+number(bound())+" "+property.unit()+(where==null||where.isEmpty()?"":" in "+where);
    }
    /** The declared range as the data states it: {@code 63.151..900 K}. */
    public String range(){return number(minimum)+".."+number(maximum)+" "+property.unit();}
    /** Whether two violations are the same boundary at the same node: a retry reproduced this failure. */
    public boolean sameAs(ThermoDomainViolation other) {
        return other!=null&&component.equals(other.component)&&property==other.property&&code==other.code&&node==other.node&&packageId.equals(other.packageId);
    }

    private static String message(String packageId,String component,Property property,double value,double minimum,double maximum,long node) {
        String subject=component.equals(PACKAGE)?"the package envelope":component;
        return "Thermo domain: "+subject+" at "+value(property,value,value<minimum?minimum:maximum)+" is "+(value<minimum?"below":"above")+" its valid range "
                +number(minimum)+".."+number(maximum)+" "+property.unit()+" (package "+packageId+")"+(node==NO_NODE?"":" at node "+node)
                +"; the state cannot be evaluated";
    }
    /** The value with its unit, to two decimals of a kelvin or whole pascals - or with as many more decimals as it takes
     * to read differently from the bound it crossed, so a trial just past a bound never reads as the bound itself. */
    private static String value(Property property,double value,double bound) {
        if(property==Property.PRESSURE&&Math.abs(value)<1)return String.format(Locale.ROOT,"%.3g Pa",value);
        int decimals=property==Property.TEMPERATURE?2:0;
        String text=String.format(Locale.ROOT,"%."+decimals+"f",value);
        while(decimals<6&&text.equals(String.format(Locale.ROOT,"%."+decimals+"f",bound))){decimals++;text=String.format(Locale.ROOT,"%."+decimals+"f",value);}
        // A Newton trial that only just crossed the bound: every digit, rather than a number that reads as the bound.
        if(text.equals(String.format(Locale.ROOT,"%."+decimals+"f",bound)))text=Double.toString(value);
        return text+" "+property.unit();
    }
    /** A declared bound exactly as the data states it: 63.151, 900, 2000000. */
    static String number(double value) {
        if(!Double.isFinite(value))return Double.toString(value);
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
