package com.wormzjl.createcheme.science.fluid.thermo;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Reproducible feasibility audit, not a claimed passing empty-vessel gameplay case. */
class EmptyWaterFillingAuditTest {
    @Test void identifyWhetherAnInitiallyEvacuatedVesselNeedsAnAdditionalEnergyStore() throws Exception {
        var model=new FluidThermodynamics(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
        double volume=1,mass=.01,moles=mass/model.waterMolecularWeight;
        double incomingEnergy=moles*model.waterLiquid(298.15,101325).molarEnthalpy();
        double minimumEnergy=Double.POSITIVE_INFINITY,minimumT=0;
        var rows=new ArrayList<Map<String,Double>>();
        for(double t:new double[]{273.16,275,280,285,290,295,298.15,300,350,375}) {
            double ps=model.saturationPressure(t),vl=model.waterLiquid(t,ps).molarVolume(),vg=FluidThermodynamics.R*t/ps;
            double nv=(volume-moles*vl)/(vg-vl),nl=moles-nv,p=ps;
            if(nl<0){nv=moles;nl=0;p=moles*FluidThermodynamics.R*t/volume;}
            var state=model.state(t,p,new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE],new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE],nl,nv,0);
            if(state.internalEnergy()<minimumEnergy){minimumEnergy=state.internalEnergy();minimumT=t;}
            rows.add(Map.of("temperatureKelvin",t,"pressurePascal",p,"internalEnergyJoule",state.internalEnergy()));
        }
        var report=new LinkedHashMap<String,Object>();report.put("reservoirVolumeCubicMetres",volume);report.put("initialWaterChargeKg",mass);
        report.put("inletTemperatureKelvin",298.15);report.put("inletPressurePascal",101325);report.put("availableEnergyJoule",incomingEnergy);
        report.put("minimumSampledSupportedEnergyJoule",minimumEnergy);report.put("minimumTemperatureKelvin",minimumT);
        report.put("energyDeficitJoule",minimumEnergy-incomingEnergy);report.put("samples",rows);
        report.put("status","FEASIBILITY_GAP: this small liquid charge has no supported fluid-only equilibrium state in the sampled liquid/vapor domain. No inventory is committed by this audit.");
        Files.createDirectories(Path.of("build/reports/fluid"));Files.writeString(Path.of("build/reports/fluid/M2-empty-water-feasibility.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        assertTrue(minimumEnergy>incomingEnergy);assertEquals(273.16,minimumT);
    }
}
