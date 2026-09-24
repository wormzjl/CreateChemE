package com.wormzjl.createcheme.science.fluid.thermo;

import com.wormzjl.createcheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.createcheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.createcheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmbientCrudePresetTest {
    @Test void crudePresetsFlashAndHaveFiniteTransportAtAmbientTemperature() {
        var catalog=MaterialCatalog.bundled();
        var model=FluidThermodynamics.forNetwork(catalog,FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        for (var preset:FluidPresetCatalog.resolve(catalog).subList(2,5)) {
            for (double t:new double[]{293.15,298,298.15,300,350}) {
                var state=assertDoesNotThrow(()->model.flashTP(t,101325,preset.moleFractions(),()->{}),preset.name()+" at "+t+" K");
                assertEquals(t,state.temperature());
                assertTrue(state.volume()>0);
                assertTrue(Double.isFinite(state.internalEnergy()));
                assertTrue(model.viscosity.liquid(t,state.liquid()).pascalSeconds()>0);
                if(state.vaporVolume()>0)assertTrue(model.viscosity.vapor(t,state.vapor(),state.waterVapor())>0);
            }
        }
    }

    @Test void actualGeneratorInitializationAndPipeFlowAccept298Kelvin() {
        var catalog=MaterialCatalog.bundled();
        var model=FluidThermodynamics.forNetwork(catalog,FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        var geometry=new PipeResistance.Geometry(1,.05,.000045,0);
        var device=new PhysicalFluidTopology.Device(1,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),
                TopologyCompiler.Kind.GENERATOR,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive());
        for(var preset:FluidPresetCatalog.resolve(catalog).subList(2,5)) {
            var generator=new FluidDeviceSpec(1,298,200000,preset.moleFractions()).initialize(device,model,()->{});
            assertEquals(298,generator.state().temperature());
            var liquid=generator.state().liquid();
            var viscosity=model.viscosity.liquid(298,liquid).pascalSeconds();
            var density=generator.state().mass()/generator.state().volume();
            assertTrue(PipeResistance.evaluate(geometry,.01,density,viscosity).pressureDrop()>0);
        }
    }

    /**
     * Colder than a component's declared range is refused, with the thermo-domain error naming it. Since F4 the range is
     * the record's own data - its fluid_domain block, 293.15 K for the bundled crude records (the ambient continuation) -
     * rather than a code rule that qualified only the exact bundled record: an author who overrides a fit declares the
     * range the new fit is valid over, and that range is enforced (documentation/fluid-followups/THERMO_DOMAIN_ERROR.md).
     */
    @Test void colderStatesAndUnqualifiedPropertyOverridesAreNotSilentlyExtrapolated() {
        var catalog=MaterialCatalog.bundled();
        var original=new HydrocarbonModel(catalog,FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        double[] n=new double[original.componentCount()];n[10]=1;
        var colder=assertThrows(ThermoDomainViolation.class,()->original.phase(293,101325,n,com.wormzjl.createcheme.science.thermo.PhaseRoot.LIQUID));
        assertEquals(original.components().get(10),colder.component());assertEquals(293.15,colder.minimum());
        var resources=new java.util.HashMap<>(catalog.resources());
        String path="data/createcheme/materials/properties/crude_pc04.json";
        var p=com.google.gson.JsonParser.parseString(resources.get(path)).getAsJsonObject();
        var cp=p.getAsJsonObject("ideal_gas_cp").getAsJsonArray("coefficients");
        cp.set(0,new com.google.gson.JsonPrimitive(cp.get(0).getAsDouble()*1.001));
        // The override's author qualifies the new fit over its own range only, and says so in its data.
        p.getAsJsonObject("fluid_domain").addProperty("temperature_min_kelvin",298.15);resources.put(path,p.toString());
        var edited=new HydrocarbonModel(MaterialCatalog.parse(resources),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        var refused=assertThrows(ThermoDomainViolation.class,()->edited.phase(298,101325,n,com.wormzjl.createcheme.science.thermo.PhaseRoot.LIQUID));
        assertEquals("crude_pc04",refused.component());assertEquals(298.15,refused.minimum());
        assertDoesNotThrow(()->edited.phase(300,101325,n,com.wormzjl.createcheme.science.thermo.PhaseRoot.LIQUID));
    }
}
