package com.wormzjl.createcheme.science.material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The 40 groups of the E-PPR78 group-contribution rule for the Peng-Robinson kij(T), scheme {@code eppr78-2022}: the
 * names a {@code groups.counts} object of a {@code spine} record may use and the names of the Akl/Bkl matrix
 * ({@link GroupInteractionMatrix}, record kind {@code group_interactions}); the publisher's matrix (Table S4 of the
 * supporting information of Jaubert et al. 2022) is not available locally, the bundled one is a third-party transcription.
 *
 * <p>Sources, both in {@code research/2026-09-24-coolprop-low-temperature/sources/e-ppr78/} of the main checkout:</p>
 * <ul>
 *   <li>J.-N. Jaubert, J.-W. Qian, S. Lasala, R. Privat, "The impressive impact of including enthalpy and heat
 *       capacity of mixing data when parameterising equations of state", Fluid Phase Equilibria 560 (2022) 113456,
 *       HAL hal-03679277: the 40-group numbering by class in Tables 1 and 2 (alkanes G1-G6, aromatics G7-G9,
 *       naphthenes G10-G11, CO2 G12, N2 G13, H2S G14, mercaptans G15, H2O G16, alkenes G17-G20, H2 G21, freons
 *       G22-G27, CO G28, He G29, Ar G30, SO2 G31, O2 G32, NO G33, COS G34, NH3 G35, NO2 G36, N2O G37, alkynes
 *       G38-G40) and, in the text, the names CH3, CH2, CH, C, G3 = CH (alkanes), G12 = CO2, G13 = N2, G15 = SH and
 *       G20 = CH/C cycloalkenic;</li>
 *   <li>S. Lasala, J.-N. Jaubert, R. Privat, IntechOpen chapter 71837 (2020): CHaro and CH2,cyclic.</li>
 * </ul>
 * <p>The names of G5 (CH4) and G6 (C2H6), whole-molecule groups for methane and ethane, and of G8, G9, G11 and
 * G17-G19 are not spelled out in those two files; they are the PPR78 names of the papers the 2022 article builds on
 * (Jaubert and Mutelet 2004, Vitu et al. 2008, Qian et al. 2013, its references 1, 4 and 11) and are marked
 * {@code literature} below until Table S4 confirms them. The six freon groups G22-G27 and the three alkyne groups
 * G38-G40 have no name in the two papers; P3 names them from the labels of the Clapeyron.jl transcription of the
 * Akl/Bkl matrix (commit {@code 0778184}, the record {@code materials/group_interactions/eppr78_2022.json}), whose
 * 40 groups match the 40 of this list one to one. Their order inside each class (and so their number) follows the
 * transcription's column order and is an assumption until Table S4 is read; the matrix is keyed by name, so the
 * number carries no numeric meaning.</p>
 */
public final class Eppr78Groups {
    /** The value a record's {@code groups.scheme} must carry. */
    public static final String SCHEME = "eppr78-2022";

    /** One group: its E-PPR78 number, the record key (absent while unsourced), its class and where the name comes from. */
    public record Group(int number, Optional<String> name, String family, String nameSource) {}

    private static final List<Group> GROUPS;
    private static final Map<String, Group> BY_NAME;

    static {
        var groups = new ArrayList<Group>();
        String paper = "Jaubert et al. 2022 text", chapter = "Lasala et al. 2020 text", literature = "literature (PPR78 papers cited by Jaubert et al. 2022)";
        add(groups, 1, "CH3", "alkanes", paper);
        add(groups, 2, "CH2", "alkanes", paper);
        add(groups, 3, "CH", "alkanes", paper);
        add(groups, 4, "C", "alkanes", paper);
        add(groups, 5, "CH4", "alkanes", literature);
        add(groups, 6, "C2H6", "alkanes", literature);
        add(groups, 7, "CHaro", "aromatics", chapter);
        add(groups, 8, "Caro", "aromatics", literature);
        add(groups, 9, "Cfused_aromatic", "aromatics", literature);
        add(groups, 10, "CH2_cyclic", "naphthenes", chapter);
        add(groups, 11, "CH_cyclic", "naphthenes", literature);
        add(groups, 12, "CO2", "CO2", paper);
        add(groups, 13, "N2", "N2", paper);
        add(groups, 14, "H2S", "H2S", paper);
        add(groups, 15, "SH", "mercaptans", paper);
        add(groups, 16, "H2O", "H2O", paper);
        add(groups, 17, "C2H4", "alkenes", literature);
        add(groups, 18, "CH2_alkenic", "alkenes", literature);
        add(groups, 19, "C_alkenic", "alkenes", literature);
        add(groups, 20, "CH_cycloalkenic", "alkenes", paper);
        add(groups, 21, "H2", "H2", paper);
        String transcription = "Clapeyron.jl transcription 0778184 (label; number inside the class assumed)";
        add(groups, 22, "C2F6", "freons", transcription);
        add(groups, 23, "CF3", "freons", transcription);
        add(groups, 24, "CF2", "freons", transcription);
        add(groups, 25, "CF_double_bond", "freons", transcription);
        add(groups, 26, "C2H4F2", "freons", transcription);
        add(groups, 27, "C2H2F4", "freons", transcription);
        add(groups, 28, "CO", "CO", paper);
        add(groups, 29, "He", "He", paper);
        add(groups, 30, "Ar", "Ar", paper);
        add(groups, 31, "SO2", "SO2", paper);
        add(groups, 32, "O2", "O2", paper);
        add(groups, 33, "NO", "NO", paper);
        add(groups, 34, "COS", "COS", paper);
        add(groups, 35, "NH3", "NH3", paper);
        add(groups, 36, "NO2", "NO2", paper);
        add(groups, 37, "N2O", "N2O", paper);
        add(groups, 38, "C2H2", "alkynes", transcription);
        add(groups, 39, "CH_alkynic", "alkynes", transcription);
        add(groups, 40, "C_alkynic", "alkynes", transcription);
        GROUPS = List.copyOf(groups);
        var byName = new LinkedHashMap<String, Group>();
        for (Group group : GROUPS) group.name().ifPresent(name -> byName.put(name, group));
        BY_NAME = Collections.unmodifiableMap(byName);
    }

    private Eppr78Groups() {}

    private static void add(List<Group> groups, int number, String name, String family, String source) {
        if (groups.size() + 1 != number) throw new AssertionError("E-PPR78 groups out of order at " + number);
        groups.add(new Group(number, Optional.ofNullable(name), family, source));
    }

    /** All 40 groups in E-PPR78 order. */
    public static List<Group> groups() { return GROUPS; }

    /** The group a record key names; refused for an unknown or not yet sourced name. */
    public static Group require(String name) {
        Group group = BY_NAME.get(name);
        if (group == null) throw new IllegalArgumentException("groups.counts: unknown E-PPR78 group " + name + " (scheme " + SCHEME + "; known names " + BY_NAME.keySet() + ")");
        return group;
    }
}
