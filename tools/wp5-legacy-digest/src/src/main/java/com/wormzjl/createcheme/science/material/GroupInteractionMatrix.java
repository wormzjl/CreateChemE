package com.wormzjl.createcheme.science.material;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Record kind {@code group_interactions} ({@code materials/group_interactions/<id>.json}): the symmetric group-interaction
 * parameters A_kl and B_kl of a group-contribution kij(T) rule, stored in MPa as the E-PPR78 papers publish them. The
 * bundled record {@code createcheme:eppr78_2022} is the Clapeyron.jl transcription of the 40-group E-PPR78 matrix
 * (P3 WP2, batch 2026-09-24-coolprop-low-temperature); the decomposition of a species into groups is not part of this
 * record but of each species' reference spine ({@code groups} of a {@code spine} record).
 *
 * <p>Fields and rules (each refusal names the field):</p>
 * <ul>
 *   <li>{@code id}, {@code revision} (stable identifier, as for spines), {@code source}, {@code provenance} (an object
 *       of texts; required, not interpreted);</li>
 *   <li>{@code scheme}: a stable identifier; for {@value Eppr78Groups#SCHEME} the {@code groups} list must equal
 *       {@link Eppr78Groups#groups()} number by number and name by name;</li>
 *   <li>{@code units}: exactly {@code MPa};</li>
 *   <li>{@code groups}: 1 to 64 entries {@code {number, name, transcription_label?}}, numbered 1..n in order, names
 *       unique stable identifiers;</li>
 *   <li>{@code pairs}: entries {@code {first, second, a_mpa, b_mpa}} of two different listed groups, finite numbers,
 *       each unordered pair at most once. A pair absent from the list is absent from the matrix: a species pair that
 *       needs it is refused when a package resolves its interactions ({@link GroupContributionInteractions}).</li>
 * </ul>
 * <p>Nothing in this record is hashed by itself: the values a package actually uses enter that package's fingerprints
 * through {@link GroupContributionInteractions}, so an edit of a pair no bundled package uses moves no fingerprint.</p>
 */
public final class GroupInteractionMatrix {
    /** The unit the record's A and B carry; the data object converts to pascal. */
    public static final String UNITS = "MPa";
    private static final int MAXIMUM_GROUPS = 64;

    /** A_kl and B_kl in MPa, as the record carries them. */
    public record Parameters(double aMegapascal, double bMegapascal) {}

    private final String id;
    private final String revision;
    private final String scheme;
    private final List<String> groups;
    private final Map<String, Parameters> pairs;

    private GroupInteractionMatrix(String id, String revision, String scheme, List<String> groups, Map<String, Parameters> pairs) {
        this.id = id; this.revision = revision; this.scheme = scheme; this.groups = groups; this.pairs = pairs;
    }

    public String id() { return id; }
    public String revision() { return revision; }
    public String scheme() { return scheme; }
    /** Group names in number order (number = index + 1). */
    public List<String> groups() { return groups; }
    /** Number of unordered group pairs the record carries. */
    public int pairCount() { return pairs.size(); }

    /** The parameters of an unordered group pair, empty when the matrix does not carry it. */
    public Optional<Parameters> parameters(String first, String second) {
        return Optional.ofNullable(pairs.get(key(first, second)));
    }

    static String key(String a, String b) { return a.compareTo(b) < 0 ? a + "/" + b : b + "/" + a; }

    static GroupInteractionMatrix read(JsonObject o) {
        String id = MaterialCatalog.string(o, "id");
        String revision = MaterialCatalog.string(o, "revision");
        if (!revision.matches("[A-Za-z0-9][A-Za-z0-9_.:+-]{0,62}"))
            throw new IllegalArgumentException("revision: expected a stable identifier of at most 63 characters, got " + revision);
        MaterialCatalog.string(o, "source");
        MaterialCatalog.object(o, "provenance");
        String scheme = MaterialCatalog.string(o, "scheme");
        if (!scheme.matches("[a-z0-9][a-z0-9_.-]{0,62}")) throw new IllegalArgumentException("scheme: expected a stable identifier, got " + scheme);
        String units = MaterialCatalog.string(o, "units");
        if (!units.equals(UNITS)) throw new IllegalArgumentException("units: expected " + UNITS + " (A_kl and B_kl as published), got " + units);
        var groupArray = MaterialCatalog.array(o, "groups");
        if (groupArray.isEmpty() || groupArray.size() > MAXIMUM_GROUPS)
            throw new IllegalArgumentException("groups: expected 1 to " + MAXIMUM_GROUPS + " groups, got " + groupArray.size());
        var names = new ArrayList<String>();
        for (int i = 0; i < groupArray.size(); i++) {
            if (!groupArray.get(i).isJsonObject()) throw new IllegalArgumentException("groups[" + i + "]: expected object");
            JsonObject group = groupArray.get(i).getAsJsonObject();
            double number = MaterialCatalog.number(group, "number");
            if (number != i + 1) throw new IllegalArgumentException("groups[" + i + "].number: expected " + (i + 1) + " (groups in number order), got " + number);
            String name = MaterialCatalog.string(group, "name");
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9_]{0,31}")) throw new IllegalArgumentException("groups[" + i + "].name: expected a stable identifier, got " + name);
            if (names.contains(name)) throw new IllegalArgumentException("groups[" + i + "].name: duplicate group " + name);
            if (group.has("transcription_label")) MaterialCatalog.string(group, "transcription_label");
            names.add(name);
        }
        if (scheme.equals(Eppr78Groups.SCHEME)) {
            var expected = Eppr78Groups.groups();
            if (names.size() != expected.size()) throw new IllegalArgumentException("groups: scheme " + scheme + " has " + expected.size() + " groups, the record lists " + names.size());
            for (int i = 0; i < names.size(); i++) {
                String known = expected.get(i).name().orElse(null);
                if (!names.get(i).equals(known))
                    throw new IllegalArgumentException("groups[" + i + "].name: scheme " + scheme + " names group " + (i + 1) + " " + known + ", the record " + names.get(i));
            }
        }
        var pairs = new LinkedHashMap<String, Parameters>();
        var pairArray = MaterialCatalog.array(o, "pairs");
        for (int i = 0; i < pairArray.size(); i++) {
            if (!pairArray.get(i).isJsonObject()) throw new IllegalArgumentException("pairs[" + i + "]: expected object");
            JsonObject pair = pairArray.get(i).getAsJsonObject();
            String a = MaterialCatalog.string(pair, "first"), b = MaterialCatalog.string(pair, "second");
            if (!names.contains(a)) throw new IllegalArgumentException("pairs[" + i + "].first: unknown group " + a + " (scheme " + scheme + ")");
            if (!names.contains(b)) throw new IllegalArgumentException("pairs[" + i + "].second: unknown group " + b + " (scheme " + scheme + ")");
            if (a.equals(b)) throw new IllegalArgumentException("pairs[" + i + "]: self pair " + a + " (A_kk = 0 by definition)");
            var value = new Parameters(MaterialCatalog.number(pair, "a_mpa"), MaterialCatalog.number(pair, "b_mpa"));
            if (pairs.putIfAbsent(key(a, b), value) != null) throw new IllegalArgumentException("pairs[" + i + "]: duplicate pair " + a + " / " + b);
        }
        return new GroupInteractionMatrix(id, revision, scheme, List.copyOf(names), Collections.unmodifiableMap(new HashMap<>(pairs)));
    }
}
