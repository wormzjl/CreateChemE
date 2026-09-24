package com.wormzjl.createcheme.science.fluid.thermo;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The dedicated thermo-domain error (F4, T2) and the per-component rule it enforces (T1): the package range is the outer
 * envelope, each component's range lies inside it, and a state is valid only inside the range of every component it
 * carries. See documentation/fluid-followups/THERMO_DOMAIN_ERROR.md.
 */
class ThermoDomainViolationTest {
    private static final String NETWORK="createcheme:tjl20_methane_nitrogen";
    private final MaterialCatalog catalog=MaterialCatalog.bundled();
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(catalog,NETWORK,1e-9);
    private int index(String component){return model.components().indexOf(component);}
    private double[] overall(Object... pairs){double[] n=new double[model.componentCount()];for(int i=0;i<pairs.length;i+=2)n[index((String)pairs[i])]=(Double)pairs[i+1];return n;}

    @Test void theViolationCarriesItsFieldsAndReadsAsTheErrorItIs() {
        var v=new ThermoDomainViolation(NETWORK,"Nitrogen",ThermoDomainViolation.Property.TEMPERATURE,270.12,273.16,900);
        assertEquals("Thermo domain: Nitrogen at 270.12 K is below its valid range 273.16..900 K (package createcheme:tjl20_methane_nitrogen); the state cannot be evaluated",v.getMessage());
        assertEquals(NETWORK,v.packageId());assertEquals("Nitrogen",v.component());assertEquals(ThermoDomainViolation.Property.TEMPERATURE,v.property());
        assertEquals(270.12,v.value());assertEquals(273.16,v.minimum());assertEquals(900,v.maximum());assertEquals(273.16,v.bound());
        assertEquals(ThermoDomainViolation.Code.THERMO_DOMAIN_TEMPERATURE_BELOW,v.code());assertEquals(ThermoDomainViolation.NO_NODE,v.node());
        assertInstanceOf(IllegalArgumentException.class,v,"every refusal path that catches a state outside the domain still catches it");
        var at=v.at(17);assertEquals(17,at.node());assertTrue(at.getMessage().contains("(package createcheme:tjl20_methane_nitrogen) at node 17;"));
        assertSame(at,at.at(18),"a violation that names its node keeps it");
        assertEquals("Nitrogen at 270.12 K is below 273.16 K in tank at 1, 64, 0",v.sentence("tank at 1, 64, 0"));
        assertEquals("273.16..900 K",v.range());
        var high=new ThermoDomainViolation(NETWORK,ThermoDomainViolation.PACKAGE,ThermoDomainViolation.Property.PRESSURE,2.5e6,100,2e6);
        assertEquals(ThermoDomainViolation.Code.THERMO_DOMAIN_PRESSURE_ABOVE,high.code());
        assertTrue(high.getMessage().startsWith("Thermo domain: the package envelope at 2500000 Pa is above its valid range 100..2000000 Pa"),high.getMessage());
        assertThrows(IllegalArgumentException.class,()->new ThermoDomainViolation(NETWORK,"Nitrogen",ThermoDomainViolation.Property.TEMPERATURE,300,273.16,900),"a value inside its range is no violation");
    }

    @Test void theRejectionKeyIsOnePerComponentPropertyAndSide() {
        var a=new ThermoDomainViolation(NETWORK,"Nitrogen",ThermoDomainViolation.Property.TEMPERATURE,270.12,273.16,900).at(3);
        var b=new ThermoDomainViolation(NETWORK,"Nitrogen",ThermoDomainViolation.Property.TEMPERATURE,251.7,273.16,900).at(3);
        assertEquals("thermo-domain: Nitrogen temperature < 273.16 K",a.reasonKey());
        assertEquals(a.reasonKey(),b.reasonKey(),"the value is not part of the key, so the map stays small");
        assertTrue(a.sameAs(b),"the same boundary at the same node");
        assertFalse(a.sameAs(new ThermoDomainViolation(NETWORK,"Nitrogen",ThermoDomainViolation.Property.TEMPERATURE,270.12,273.16,900).at(4)),"another node");
        assertFalse(a.sameAs(new ThermoDomainViolation(NETWORK,"Water",ThermoDomainViolation.Property.TEMPERATURE,270.12,273.16,900).at(3)),"another component");
        assertTrue(a.reasonKey().startsWith(ThermoDomainViolation.REASON_PREFIX));
    }

    /** A value just past its bound reads differently from the bound. */
    @Test void aValueJustPastItsBoundIsPrintedWithTheDigitsThatShowIt() {
        var v=new ThermoDomainViolation(NETWORK,"Nitrogen",ThermoDomainViolation.Property.TEMPERATURE,298.0499,298.05,900);
        assertTrue(v.getMessage().contains("Nitrogen at 298.0499 K is below its valid range 298.05..900 K"),v.getMessage());
    }

    @Test void aNitrogenStateAt200KelvinIsAcceptedAndOnePercentOfACrudeFractionIsRefusedNamingIt() {
        var gas=assertDoesNotThrow(()->model.flashTP(200,101325,overall("Nitrogen",1.0),()->{}));
        assertEquals(200,gas.temperature());
        var refused=assertThrows(ThermoDomainViolation.class,()->model.flashTP(200,101325,overall("Nitrogen",.99,"crude_pc03",.01),()->{}));
        assertEquals("crude_pc03",refused.component());assertEquals(ThermoDomainViolation.Code.THERMO_DOMAIN_TEMPERATURE_BELOW,refused.code());
        assertEquals(293.15,refused.minimum());assertEquals(200,refused.value());
        assertTrue(refused.getMessage().startsWith("Thermo domain: crude_pc03 at 200.00 K is below its valid range 293.15..900 K"),refused.getMessage());
        // The same rule on a direct state, the path every Newton trial takes.
        double[] vapor=new double[model.hydrocarbon.componentCount()];vapor[index("Nitrogen")]=.99;vapor[index("crude_pc03")]=.01;
        var direct=assertThrows(ThermoDomainViolation.class,()->model.state(200,101325,new double[vapor.length],vapor,0,0,101325));
        assertEquals("crude_pc03",direct.component());
    }

    @Test void theLargestCarriedComponentOutOfItsRangeIsNamed() {
        var refused=assertThrows(ThermoDomainViolation.class,()->model.flashTP(250,101325,overall("crude_pc03",.4,"crude_pc05",.6),()->{}));
        assertEquals("crude_pc05",refused.component());
    }

    @Test void freeWaterBelowItsTriplePointIsRefusedNamingWater() {
        double[] none=new double[model.hydrocarbon.componentCount()];double[] nitrogen=none.clone();nitrogen[index("Nitrogen")]=1;
        var refused=assertThrows(ThermoDomainViolation.class,()->model.state(270,101325,none,nitrogen,1,0,101325));
        assertEquals("Water",refused.component());assertEquals(273.16,refused.minimum());assertEquals(ThermoDomainViolation.Code.THERMO_DOMAIN_TEMPERATURE_BELOW,refused.code());
        assertEquals("Water",assertThrows(ThermoDomainViolation.class,()->model.saturationPressure(270)).component());
        assertEquals("Water",assertThrows(ThermoDomainViolation.class,()->model.waterLiquid(270,101325)).component());
        assertEquals("Water",assertThrows(ThermoDomainViolation.class,()->model.flashTP(270,101325,overall("Water",1.0),()->{})).component());
        // Liquid water below its saturation pressure is outside IF97 Region 1: the water model's own bound.
        var region=assertThrows(ThermoDomainViolation.class,()->WaterRegion1.evaluate(NETWORK,catalog.requirePackage(NETWORK).water(),350,1000));
        assertEquals(ThermoDomainViolation.Property.PRESSURE,region.property());assertEquals("Water",region.component());
    }

    @Test void pressureAndTheEnvelopeAreCheckedAndMalformedInputStaysAPlainRefusal() {
        double[] none=new double[model.hydrocarbon.componentCount()];double[] nitrogen=none.clone();nitrogen[index("Nitrogen")]=1;
        var low=assertThrows(ThermoDomainViolation.class,()->model.state(298.15,50,none,nitrogen,0,0,50));
        assertEquals("Nitrogen",low.component());assertEquals(ThermoDomainViolation.Property.PRESSURE,low.property());assertEquals(100,low.minimum());
        var dry=assertThrows(ThermoDomainViolation.class,()->model.domain().checkEnvelope(50,101325));
        assertEquals(ThermoDomainViolation.PACKAGE,dry.component());assertEquals(63.151,dry.minimum());
        var malformed=assertThrows(IllegalArgumentException.class,()->model.state(298.15,101325,new double[2],new double[2],0,0,101325));
        assertFalse(malformed instanceof ThermoDomainViolation,"a wrong basis is malformed input");
        var nan=assertThrows(IllegalArgumentException.class,()->model.state(Double.NaN,101325,none,nitrogen,0,0,101325));
        assertFalse(nan instanceof ThermoDomainViolation,"a non-finite temperature is malformed input");
        // The old fixed ceiling is gone: the package and its components declare 900 K.
        assertDoesNotThrow(()->model.flashTP(700,101325,overall("Nitrogen",1.0),()->{}));
    }

    /** The domain is data: a package without an envelope cannot be evaluated, and a component range outside its
     * package's envelope, or beyond its own vapour viscosity data, is refused when the catalog is read. */
    @Test void theDomainIsReadFromDataAndValidatedWhenTheCatalogLoads() {
        var resources=new HashMap<>(catalog.resources());
        String pkg="data/createcheme/materials/packages/tjl20.json";
        var o=JsonParser.parseString(resources.get(pkg)).getAsJsonObject();String id=o.get("id").getAsString();o.remove("fluid_domain");resources.put(pkg,o.toString());
        var withoutEnvelope=MaterialCatalog.parse(resources);
        var missing=assertThrows(IllegalArgumentException.class,()->new FluidThermodynamics(withoutEnvelope,id,1e-9));
        assertTrue(missing.getMessage().contains("declares no fluid_domain"),missing.getMessage());

        var outside=new HashMap<>(catalog.resources());String nitrogen="data/createcheme/materials/properties/nitrogen.json";
        var n=JsonParser.parseString(outside.get(nitrogen)).getAsJsonObject();n.getAsJsonObject("fluid_domain").addProperty("pressure_max_pascal",3e6);outside.put(nitrogen,n.toString());
        var envelope=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(outside));
        assertTrue(envelope.getMessage().contains("lies outside the package envelope"),envelope.getMessage());

        var unsupported=new HashMap<>(catalog.resources());String methane="data/createcheme/materials/properties/tjl20_methane.json";
        var m=JsonParser.parseString(unsupported.get(methane)).getAsJsonObject();m.getAsJsonObject("fluid_domain").addProperty("temperature_min_kelvin",150);unsupported.put(methane,m.toString());
        var viscosity=assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(unsupported));
        assertTrue(viscosity.getMessage().contains("exceeds the vapor viscosity data"),viscosity.getMessage());
    }
}
