package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;

/**
 * Flow-fraction analog of Sotelo et al., "Dynamic Simulation of a Crude Oil Distillation Plant Using
 * Aspen-HYSYS", Int. J. Simul. Model. 18 (2019) 229-241, doi:10.2507/IJSIMM18(2)465.
 *
 * <p>The paper reports a 99,000 bbl/day, 29-tray atmospheric column with kerosene, diesel, and AGO draws
 * of 14,000, 20,000, and 5,000 bbl/day from stages 13, 17, and 22. This fixture transfers those volume
 * fractions to the TJL molar feed; it is not a molar reconstruction of the Mexican crude blend. The source
 * column also has side strippers, pumparounds, and stripping steam that dry V3 does not model.</p>
 */
final class V3Sotelo2019SideDrawCase {
    static final String DOI = "10.2507/IJSIMM18(2)465";
    static final double SOURCE_FEED_BARRELS_PER_DAY = 99_000.0;
    static final double SOURCE_KEROSENE_BARRELS_PER_DAY = 14_000.0;
    static final double SOURCE_DIESEL_BARRELS_PER_DAY = 20_000.0;
    static final double SOURCE_AGO_BARRELS_PER_DAY = 5_000.0;
    static final double MODEL_FEED_KMOL_PER_HOUR = 2_610.7;

    /**
     * Draw scale of the qualified dry lane.
     *
     * <p>0.25 is the largest scale a draws-only column at reflux ratio 2.0 and a 400 K condenser supports, and
     * the limit is the internal mass balance rather than the solver. That condenser and reflux send about 59%
     * of the feed moles overhead, so a draw scale of 0.75 (37% of the feed) or 1.0 (49.7%) asks for more
     * product than there is feed: those two specifications are invalid, not merely hard. They also exceed the
     * source arrangements — Sotelo et al. (2019) and Ledezma-Martinez (2019, table A5) both draw about 40 to
     * 46% from the side, and both do it with pumparounds that condense vapour back into the sections the draws
     * empty. Between 0.25 and 1.0 the failure is a starved tray below the lowest draw, which cooling fixes:
     * with the source pumparound arrangement the same column converges at 0.40, which
     * {@code V3SideDrawCalculatorTest.theThesisArrangedFortyPercentDrawsConvergeWithTheirPumparounds}
     * qualifies. Dry V3 has no pumparound in this fixture, so the dry lane stays at 0.25.</p>
     */
    static final double DRY_QUALIFICATION_SCALE = 0.25;

    private V3Sotelo2019SideDrawCase() {}

    /** Source geometry/conditions where represented; feed stage, reflux, and duty remain V3 closure assumptions. */
    static V3ColumnInput sourceGeometryAnalog(double drawScale) {
        return input(drawScale, 611.55, 104_000.0, (198_540.0 - 104_000.0) / 29.0, 350.05);
    }

    /** Qualified dry-V3 thermal lane with the paper's tray count, draw stages, and product-rate proportions. */
    static V3ColumnInput dryQualificationInput() {
        return input(DRY_QUALIFICATION_SCALE, 638.15, 150_000.0, 750.0, 400.0);
    }

    private static V3ColumnInput input(
            double drawScale, double feedTemperatureKelvin, double topPressurePascal,
            double pressureDropPascal, double condenserTemperatureKelvin) {
        if (!Double.isFinite(drawScale) || drawScale <= 0.0) {
            throw new IllegalArgumentException("Sotelo side-draw scale must be finite and positive");
        }
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] feed = crude.moleFractions();
        for (int component = 0; component < feed.length; component++) {
            feed[component] *= MODEL_FEED_KMOL_PER_HOUR / 3.6;
        }
        return new V3ColumnInput(
                V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(), crude.componentBasis(), feed,
                feedTemperatureKelvin, 29, 24, topPressurePascal, pressureDropPascal,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperatureKelvin),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)),
                List.of(draw(13, SOURCE_KEROSENE_BARRELS_PER_DAY, drawScale),
                        draw(17, SOURCE_DIESEL_BARRELS_PER_DAY, drawScale),
                        draw(22, SOURCE_AGO_BARRELS_PER_DAY, drawScale)));
    }

    private static V3SideDrawSpec draw(int tray, double sourceBarrelsPerDay, double scale) {
        double modelKmolPerHour = MODEL_FEED_KMOL_PER_HOUR * sourceBarrelsPerDay
                / SOURCE_FEED_BARRELS_PER_DAY * scale;
        return new V3SideDrawSpec(tray, modelKmolPerHour / 3.6);
    }
}
