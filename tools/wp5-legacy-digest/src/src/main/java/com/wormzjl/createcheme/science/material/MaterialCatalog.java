package com.wormzjl.createcheme.science.material;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Immutable validated SI database. Resource keys are distinct from plain component IDs. */
public final class MaterialCatalog {
    public record Pr78(double criticalTemperature, double criticalPressure, double acentricFactor) {}
    public record NrtlPair(String first, String second, double a12, double b12, double a21,
            double b21, double alpha, double minimumTemperature, double maximumTemperature) {}
    /**
     * {@code lowTemperatureCp} is an optional second ideal-gas heat-capacity segment, used below
     * {@link CpSegment#belowKelvin()} with the enthalpy continued from the main fit's value there; {@code null} for a
     * record with one fit (every record but nitrogen), which keeps their serialized form, and so every physics
     * fingerprint that hashes it, exactly what it was.
     *
     * <p>{@code cp} is empty for a record without {@code ideal_gas_cp}, which only a package with a reference spine may
     * use (D6: its ideal gas comes from the spine; {@code referenceTemperature} is then 298.15). {@code volumeTranslation}
     * is the record's Tr = 0.8 saturated-liquid anchor ({@link VolumeTranslation}, P3), {@code null} when absent, which
     * again keeps every existing serialized form and fingerprint unchanged.</p>
     */
    public record Property(String id, String component, double molecularWeight, double normalBoilingPoint,
            double density, double standardTemperature, double standardPressure, double minimumTemperature,
            double maximumTemperature, double referenceTemperature, List<Double> cp, Pr78 pr, boolean estimated,
            CpSegment lowTemperatureCp, VolumeTranslation volumeTranslation) {
        public Property { cp = List.copyOf(cp); }
    }
    /** Six coefficients in powers of {@code (T - 298.15 K)}, the main fit's form, valid below {@code belowKelvin}. */
    public record CpSegment(double belowKelvin, List<Double> coefficients) {
        public CpSegment { coefficients = List.copyOf(coefficients); }
    }
    /**
     * A temperature and pressure range the fluid network may evaluate a component (or, for a package, any state) in,
     * with the evidence it rests on. Read from the {@code fluid_domain} object of a property or package record. It is
     * kept beside the records rather than in them: the records' own ranges are the column's, and they and every other
     * numeric field are hashed into the physics fingerprints the column's neural initializer and the fluid presets
     * pin, which this must not move. The fluid network's thermodynamic revision hashes it instead
     * ({@link #fluidThermoFingerprint}).
     */
    public record Validity(double minimumTemperature, double maximumTemperature, double minimumPressure, double maximumPressure,
            String evidence) {
        public Validity {
            if (!(minimumTemperature > 0) || !(maximumTemperature > minimumTemperature) || !Double.isFinite(maximumTemperature)
                    || !(minimumPressure > 0) || !(maximumPressure > minimumPressure) || !Double.isFinite(maximumPressure))
                throw new IllegalArgumentException("fluid_domain: invalid temperature or pressure range");
            Objects.requireNonNull(evidence);
        }
        public boolean within(Validity envelope) {
            return minimumTemperature >= envelope.minimumTemperature && maximumTemperature <= envelope.maximumTemperature
                    && minimumPressure >= envelope.minimumPressure && maximumPressure <= envelope.maximumPressure;
        }
        List<Double> numbers() { return List.of(minimumTemperature, maximumTemperature, minimumPressure, maximumPressure); }
    }
    public record Water(String revision, double molarMass, double triplePoint, double criticalTemperature,
            double criticalPressure, double maximumTemperature, double boilingTemperature,
            double vaporizationEnthalpy, double watsonExponent, List<Double> saturation, List<Double> shomate) {
        public Water { saturation = List.copyOf(saturation); shomate = List.copyOf(shomate); }
    }
    public record Assay(String id, String basis, List<String> components, List<Double> amounts,
            double volumeScale, double amountTotal) {
        public Assay { components = List.copyOf(components); amounts = List.copyOf(amounts); }
    }
    /**
     * {@code spine} lists the reference-spine record of each component in component order, or is empty for a package
     * without one; {@code crystals} lists the crystal records the package admits. {@code spineFingerprint} hashes the
     * numeric content of both ({@link ReferenceSpine}, {@link CrystalReference}); it is empty when the package has
     * neither, and it is kept apart from {@code fingerprint}, which does not see these records, so adding a spine to a
     * package never moves its physics fingerprint.
     *
     * <p>{@code groupContributions} is {@code null} unless the package's interactions record carries a {@code rule}
     * (E-PPR78, P3): it then holds the temperature-dependent pairs resolved from the group matrix and the spines'
     * group counts ({@link GroupContributionInteractions}). For such a pair the constant {@code interactions} entry is
     * 0.0 and is not the pair's kij: a consumer takes kij(T) from {@code groupContributions} for every pair it lists
     * and the constant matrix for the others. Its content is hashed into {@code fingerprint} and
     * {@link #physicsFingerprint} only when it is present, so packages without a rule keep their pins.</p>
     */
    public record Package(String id, String revision, String fingerprint, String model, List<String> components,
            List<Property> properties, List<List<Double>> interactions, List<NrtlPair> nrtlPairs,
            Map<String, String> aliases, Map<String, Assay> assays, Water water,
            double minimumTemperature, double maximumTemperature, double minimumPressure,
            double maximumPressure, List<String> evidence, List<String> spine, List<String> crystals, String spineFingerprint,
            GroupContributionInteractions groupContributions) {
        public Package {
            components = List.copyOf(components); properties = List.copyOf(properties);
            interactions = interactions.stream().map(List::copyOf).toList(); nrtlPairs = List.copyOf(nrtlPairs);
            aliases = Map.copyOf(aliases); assays = Map.copyOf(assays); evidence = List.copyOf(evidence);
            spine = List.copyOf(spine); crystals = List.copyOf(crystals); Objects.requireNonNull(spineFingerprint);
        }
        /** The rule-resolved temperature-dependent pairs, empty for a package whose interactions are all constants. */
        public Optional<GroupContributionInteractions> temperatureDependentInteractions() { return Optional.ofNullable(groupContributions); }
        public String canonicalId(String id) { return aliases.getOrDefault(id, id); }
        public String scientificRevision() { return revision + ":" + fingerprint; }
    }
    private final SolidMaterialCatalog solids;
    public SolidMaterialCatalog solids(){return solids;}
    private final MaterialFluidData fluidData;
    private final Map<String, MaterialName> names;
    private final Map<String, Package> packages;
    private final Map<String, String> resources;
    private final Map<String, Map<ViscosityCorrelation.Phase, ViscosityCorrelation>> viscosities;
    private final Map<String, String> waterModels;
    private final Map<String, LiquidMixtureCorrection> liquidMixtures;
    private final MaterialPresets presets;
    private final Map<String, FluidAppearance> assayAppearances;
    private final Map<String, FluidAppearance> componentAppearances;
    /** Fluid-network validity by property record id and by package id; see {@link Validity}. */
    private final Map<String, Validity> propertyValidity, packageValidity;
    /** Reference-spine and crystal records by record id (kinds {@code spine} and {@code crystals}). */
    private final Map<String, ReferenceSpine> spines;
    private final Map<String, CrystalReference> crystals;
    /** Group-interaction matrices by record id (kind {@code group_interactions}). */
    private final Map<String, GroupInteractionMatrix> groupInteractions;
    private MaterialCatalog(Map<String, MaterialName> names, Map<String, Package> packages, Map<String, String> resources,
            Map<String, Map<ViscosityCorrelation.Phase, ViscosityCorrelation>> viscosities, Map<String, String> waterModels,
            Map<String, FluidAppearance> assayAppearances, Map<String, FluidAppearance> componentAppearances, Map<String, LiquidMixtureCorrection> liquidMixtures, MaterialPresets presets, MaterialFluidData fluidData,
            Map<String, Validity> propertyValidity, Map<String, Validity> packageValidity,
            Map<String, ReferenceSpine> spines, Map<String, CrystalReference> crystals, Map<String, GroupInteractionMatrix> groupInteractions) {
        this.propertyValidity = Map.copyOf(propertyValidity); this.packageValidity = Map.copyOf(packageValidity);
        this.spines = Map.copyOf(spines); this.crystals = Map.copyOf(crystals); this.groupInteractions = Map.copyOf(groupInteractions);
        this.solids=SolidMaterialCatalog.parse(resources);
        this.fluidData=Objects.requireNonNull(fluidData);
        this.liquidMixtures = Map.copyOf(liquidMixtures);
        this.presets=Objects.requireNonNull(presets);
        this.names = Map.copyOf(names); this.packages = Map.copyOf(packages); this.resources = Map.copyOf(resources);
        this.viscosities = Map.copyOf(viscosities); this.waterModels = Map.copyOf(waterModels);
        this.assayAppearances = Map.copyOf(assayAppearances);
        this.componentAppearances = Map.copyOf(componentAppearances);
    }
    public Package requirePackage(String id) {
        Package p = packages.get(id);
        if (p == null) throw new IllegalArgumentException("Unknown material package: " + id);
        return p;
    }
    public MaterialName name(String id) { MaterialName n=names.get(id); return n==null?MaterialName.chemical(id):n; }
    public Map<String, Package> packages() { return packages; }
    /** Reference-spine records by id. */
    public Map<String, ReferenceSpine> spines() { return spines; }
    /** Crystal records by id. */
    public Map<String, CrystalReference> crystals() { return crystals; }
    /** Group-interaction matrices by record id ({@link GroupInteractionMatrix}). */
    public Map<String, GroupInteractionMatrix> groupInteractions() { return groupInteractions; }
    /** The reference spine of a component in a package that declares one. */
    public ReferenceSpine requireSpine(String packageId, String componentId) {
        var p=requirePackage(packageId);
        if(p.spine().isEmpty())throw new IllegalArgumentException("Package declares no reference spine: "+packageId);
        int i=p.components().indexOf(p.canonicalId(componentId));
        if(i<0)throw new IllegalArgumentException("Component outside package: "+componentId);
        return spines.get(p.spine().get(i));
    }
    public MaterialFluidData fluidData(){return fluidData;}
    /** A component's Tr = 0.8 volume anchor in a package: its property record's {@code volume_translation}, if any (P3). */
    public Optional<VolumeTranslation> volumeTranslation(String packageId,String componentId) {
        var p=requirePackage(packageId);int i=p.components().indexOf(p.canonicalId(componentId));
        if(i<0)throw new IllegalArgumentException("Component outside package: "+componentId);
        return Optional.ofNullable(p.properties().get(i).volumeTranslation());
    }
    /**
     * The fluid network's thermodynamic identity of a package: its physics fingerprint, the liquid volume references,
     * and the fluid-network validity of the package and of every component ({@link Validity}), so a changed domain is a
     * changed thermodynamic revision (a fresh world, like any other property change).
     */
    public String fluidThermoFingerprint(String packageId) {
        var p=requirePackage(packageId);var points=new TreeMap<String,MaterialFluidData.VolumeReference>();
        // A component whose property record carries its own volume_translation is anchored by it (hashed through the
        // physics fingerprint), not by the global calibration point: one anchor per species per package.
        for(int i=0;i<p.components().size();i++) {
            String id=p.components().get(i);
            if(p.properties().get(i).volumeTranslation()==null&&fluidData.volumeReferences().containsKey(id))points.put(id,fluidData.volumeReferences().get(id));
        }
        var domains=new ArrayList<Object>();
        domains.add(fluidValidity(packageId).map(Validity::numbers).orElse(List.of()));
        for(String id:p.components())domains.add(fluidValidity(packageId,id).map(Validity::numbers).orElse(List.of()));
        return hash(new Gson().toJson(List.of(physicsFingerprint(packageId,p.components()),points,domains)));
    }
    /** The fluid network's envelope for a package: its {@code fluid_domain}, absent for a package the network cannot be built on. */
    public Optional<Validity> fluidValidity(String packageId) {
        requirePackage(packageId);return Optional.ofNullable(packageValidity.get(packageId));
    }
    /** A component's own fluid-network range in a package: its property record's {@code fluid_domain}, if it declares one. */
    public Optional<Validity> fluidValidity(String packageId,String componentId) {
        var p=requirePackage(packageId);int i=p.components().indexOf(p.canonicalId(componentId));
        if(i<0)throw new IllegalArgumentException("Component outside package: "+componentId);
        return Optional.ofNullable(propertyValidity.get(p.properties().get(i).id()));
    }
    public MaterialPresets presets(){return presets;}
    /** Convenience for the unique authored preset of a package; multiple choices require an explicit preset ID. */
    private MaterialPresets.Column packagePreset(String packageId) {
        requirePackage(packageId);
        var choices=presets.columns().values().stream().filter(c->c.descriptor().packageId().equals(packageId)).toList();
        if(choices.size()!=1)throw new IllegalArgumentException("Select an explicit column preset for "+packageId);
        return choices.getFirst();
    }
    public List<Double> columnSideDrawRates(String packageId) {return packagePreset(packageId).draws().stream().map(com.wormzjl.createcheme.science.column.v3.V3SideDrawSpec::molarFlowMolPerSecond).toList();}
    public double columnFeedStandardVolume(String packageId) {return packagePreset(packageId).feedVolume();}
    public LiquidMixtureCorrection liquidMixture(String packageId) { requirePackage(packageId); return liquidMixtures.get(packageId); }
    /** Immutable source strings for tooling and isolated override tests. */
    public Map<String, String> resources() { return resources; }

    /** Liquid visual settings for a component, including package aliases and the water model. */
    public FluidAppearance componentAppearance(String packageId, String componentId) {
        Package p = requirePackage(packageId);
        String id = p.canonicalId(Objects.requireNonNull(componentId, "componentId"));
        if (!p.components().contains(id) && !id.equals("Water"))
            throw new IllegalArgumentException("Component is outside package: " + componentId);
        return componentAppearances.getOrDefault(id, FluidAppearance.DEFAULT);
    }

    /** Whole-feed liquid visual settings; no component-color mixing rule is implied. */
    public FluidAppearance assayAppearance(String packageId, String assayId) {
        if (!requirePackage(packageId).assays().containsKey(assayId))
            throw new IllegalArgumentException("Assay is outside package: " + assayId);
        // Assay identity is (package, id) everywhere else in this class, so the appearance is keyed
        // that way too: one composition may be declared on two packages with different bases.
        return assayAppearances.getOrDefault(packageId + "/" + assayId, FluidAppearance.DEFAULT);
    }

    /** Selected pure-component viscosity data, absent when that phase has no dataset. No mixing rule is implied. */
    public Optional<ViscosityCorrelation> viscosity(String packageId, String componentId, ViscosityCorrelation.Phase phase) {
        Objects.requireNonNull(phase, "phase");
        Package p = requirePackage(packageId);
        String id = p.canonicalId(Objects.requireNonNull(componentId, "componentId"));
        int index = p.components().indexOf(id);
        String key;
        if (index >= 0) key = "properties/" + p.properties().get(index).id();
        else if (id.equals("Water")) key = "water/" + waterModels.get(packageId);
        else throw new IllegalArgumentException("Component is outside package: " + componentId);
        return Optional.ofNullable(viscosities.getOrDefault(key, Map.of()).get(phase));
    }

    /** Numeric property identity; authoring record IDs and provenance text do not alter physics. */
    public String propertyPhysicsFingerprint(String packageId,String componentId) {
        var p=requirePackage(packageId);int i=p.components().indexOf(componentId);
        if(i<0)throw new IllegalArgumentException("Component outside package: "+componentId);
        return hash(propertyScience(p.properties().get(i)).toString());
    }
    private static JsonObject propertyScience(Property property) {
        var json=new Gson().toJsonTree(property).getAsJsonObject();json.remove("id");return json;
    }
    /** Ordered numerical sub-basis, excluding assays, names, transport, record IDs and declared revisions. */
    public String physicsFingerprint(String packageId,List<String> axis) {
        var p=requirePackage(packageId);new MaterialAxis(axis);
        var indices=axis.stream().map(id->{int i=p.components().indexOf(id);if(i<0)throw new IllegalArgumentException("Component outside package: "+id);return i;}).toList();
        var properties=indices.stream().map(i->propertyScience(p.properties().get(i))).toList();
        var matrix=indices.stream().map(i->indices.stream().map(j->p.interactions().get(i).get(j)).toList()).toList();
        var nrtl=p.nrtlPairs().stream().filter(pair->axis.contains(pair.first())&&axis.contains(pair.second()))
                .sorted(Comparator.comparing(NrtlPair::first).thenComparing(NrtlPair::second)).toList();
        var water=new Gson().toJsonTree(p.water()).getAsJsonObject();water.remove("revision");
        var hashed=new ArrayList<Object>(List.of(p.model(),axis,properties,matrix,nrtl,water,
                p.minimumTemperature(),p.maximumTemperature(),p.minimumPressure(),p.maximumPressure()));
        // Only a package with a group-contribution rule hashes it, so every other package keeps its exact pin.
        if(p.groupContributions()!=null)hashed.add(p.groupContributions().science(p.components(),axis));
        return hash(new Gson().toJson(hashed));
    }

    /**
     * Immutable PR view for a qualified inference axis. Missing zero species can use an explicitly
     * selected reference package; the caller must verify the complete projected physics fingerprint.
     * This does not convert saved inventories or publish a replacement runtime catalog.
     */
    public MaterialCatalog inferenceView(String packageId,List<String> axis,String referencePackage,boolean allowMissing) {
        var source=requirePackage(packageId);new MaterialAxis(axis);
        if(source.components().equals(axis))return this;
        if(!source.model().equals("pr78"))throw new IllegalArgumentException("Inference projection requires PR78");
        if(source.groupContributions()!=null)throw new IllegalArgumentException("Inference projection of a package with temperature-dependent interactions is not supported: "+packageId);
        var reference=requirePackage(referencePackage);
        var properties=new ArrayList<Property>();
        for(String id:axis) {
            int i=source.components().indexOf(id);
            if(i>=0)properties.add(source.properties().get(i));
            else {
                int j=reference.components().indexOf(id);
                if(!allowMissing||j<0)throw new IllegalArgumentException("Unqualified missing inference species: "+id);
                properties.add(reference.properties().get(j));
            }
        }
        var interactions=new ArrayList<List<Double>>();
        for(String a:axis) {
            var row=new ArrayList<Double>();
            for(String b:axis) {
                int i=source.components().indexOf(a),j=source.components().indexOf(b);
                if(i>=0&&j>=0)row.add(source.interactions().get(i).get(j));
                else {
                    int ri=reference.components().indexOf(a),rj=reference.components().indexOf(b);
                    if(ri<0||rj<0)throw new IllegalArgumentException("Missing qualified inference interaction: "+a+"/"+b);
                    row.add(reference.interactions().get(ri).get(rj));
                }
            }
            interactions.add(row);
        }
        // A projected inference view is a PR78 axis only: it carries no reference spine or crystals.
        var view=new Package(source.id(),source.revision(),source.fingerprint(),source.model(),axis,properties,
                interactions,List.of(),Map.of(),Map.of(),source.water(),source.minimumTemperature(),
                source.maximumTemperature(),source.minimumPressure(),source.maximumPressure(),source.evidence(),List.of(),List.of(),"",null);
        var projected=new HashMap<>(packages);projected.put(packageId,view);
        return new MaterialCatalog(names,projected,resources,viscosities,waterModels,assayAppearances,
                componentAppearances,liquidMixtures,presets,fluidData,propertyValidity,packageValidity,spines,crystals,groupInteractions);
    }

    /** Presets may share a network only when the selected sub-basis has the same physics and transport. */
    public void requireSharedFluidPhysics(String sourceId,String targetId) {
        var source=requirePackage(sourceId);var target=requirePackage(targetId);var axis=source.components();
        if(!target.components().containsAll(axis)||!physicsFingerprint(sourceId,axis).equals(physicsFingerprint(targetId,axis)))
            throw new IllegalArgumentException("Fluid preset physics differ from network: "+sourceId);
        var a=liquidMixture(sourceId);var b=liquidMixture(targetId);
        if(!a.coefficients().equals(b.coefficients())||a.referenceKelvin()!=b.referenceKelvin()||a.slopeCapKelvin()!=b.slopeCapKelvin())
            throw new IllegalArgumentException("Fluid preset mixture correction differs from network: "+sourceId);
        for(int i=0;i<axis.size();i++) {
            int j=target.components().indexOf(axis.get(i));
            if(!a.descriptors().get(i).equals(b.descriptors().get(j))
                    ||!viscosityScience("properties/"+source.properties().get(i).id()).equals(viscosityScience("properties/"+target.properties().get(j).id())))
                throw new IllegalArgumentException("Fluid preset transport differs from network: "+sourceId+"/"+axis.get(i));
            if(!Objects.equals(fluidValidity(sourceId,axis.get(i)).map(Validity::numbers),fluidValidity(targetId,axis.get(i)).map(Validity::numbers)))
                throw new IllegalArgumentException("Fluid preset fluid_domain differs from network: "+sourceId+"/"+axis.get(i));
        }
        if(!viscosityScience("water/"+waterModels.get(sourceId)).equals(viscosityScience("water/"+waterModels.get(targetId))))
            throw new IllegalArgumentException("Fluid preset water transport differs from network: "+sourceId);
    }

    /** Transport cache key; viscosity changes do not invalidate PR/enthalpy seeds which never consume viscosity. */
    public String viscosityFingerprint(String packageId) {
        Package p = requirePackage(packageId);
        Map<String, Object> selected = new TreeMap<>();
        for (Property property : p.properties()) selected.put(property.id(), viscosityScience("properties/" + property.id()));
        selected.put("liquid_mixture", liquidMixture(packageId));
        selected.put("molecular_weights",p.properties().stream().map(property->List.of(property.component(),property.molecularWeight())).toList());
        selected.put("water_molecular_weight",p.water().molarMass());
        var conditional=new TreeMap<String,Object>();
        for(String id:p.components()) {
            var curve=fluidData.conditionalLiquidViscosities().get(id);
            if(curve!=null)conditional.put(id,List.of(curve.minimumTemperatureKelvin(),curve.maximumTemperatureKelvin(),
                    curve.minimumPressurePascal(),curve.temperaturesKelvin(),curve.coefficients()));
        }
        selected.put("conditional_solutes",conditional);
        selected.put("water/" + waterModels.get(packageId), viscosityScience("water/" + waterModels.get(packageId)));
        return hash(new Gson().toJson(selected));
    }

    private Map<String, Object> viscosityScience(String key) {
        Map<String, Object> data = new TreeMap<>();
        viscosities.getOrDefault(key, Map.of()).forEach((phase, v) -> data.put(phase.name(), List.of(v.model(),
                v.minimumTemperatureKelvin(), v.maximumTemperatureKelvin(), v.minimumPressurePascal(), v.maximumPressurePascal(),
                v.referenceTemperatureKelvin(), v.coefficients(), v.exponents(), v.temperaturesKelvin(), v.estimated())));
        return data;
    }

    /** Parse a complete winning resource set; never publishes partially validated data. */
    public static MaterialCatalog parse(Map<String, String> resources) {
        var groups = new HashMap<String, Map<String, JsonObject>>();
        var origins = new IdentityHashMap<JsonObject, String>();
        for (var entry : new TreeMap<>(resources).entrySet()) {
            try {
                JsonObject o = JsonParser.parseString(entry.getValue()).getAsJsonObject();
                if (number(o,"schema_version") != 1) throw new IllegalArgumentException("schema_version must be 1");
                String path = entry.getKey(); int start = path.indexOf("materials/");
                if (start < 0) throw new IllegalArgumentException("Expected materials resource path");
                String kind = path.substring(start + 10).split("/")[0];
                if (!Set.of("components","properties","interactions","packages","assays","water","bases","transport","presets","networks","solids","spine","crystals","group_interactions").contains(kind))
                    throw new IllegalArgumentException("Unknown record kind: " + kind);
                String id = string(o, "id");
                if (!kind.equals("components") && !id.matches("[a-z][a-z0-9_.:-]{0,127}"))
                    throw new IllegalArgumentException("id: expected lowercase stable identifier");
                if (!id.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("Invalid id");
                String key = kind.equals("assays") ? string(o,"package") + "/" + id : id;
                if (groups.computeIfAbsent(kind, unused -> new LinkedHashMap<>()).putIfAbsent(key,o) != null)
                    throw new IllegalArgumentException("Duplicate " + kind + " id: " + key);
                origins.put(o,path);
            } catch (RuntimeException e) { throw error(entry.getKey(), e); }
        }
        MaterialAuthoring.expand(groups,origins);
        var names = new HashMap<String, MaterialName>();
        var properties = new HashMap<String, Property>();
        var waters = new HashMap<String, Water>();
        var packages = new HashMap<String, Package>();
        var viscosities = new HashMap<String, Map<ViscosityCorrelation.Phase, ViscosityCorrelation>>();
        var waterModels = new HashMap<String, String>();
        var liquidMixtures = new HashMap<String, LiquidMixtureCorrection>();
        var descriptors = new HashMap<String, LiquidMixtureCorrection.Descriptor>();
        var assayAppearances = new HashMap<String, FluidAppearance>();
        var componentAppearances = new HashMap<String, FluidAppearance>();
        var propertyValidity = new HashMap<String, Validity>();
        var packageValidity = new HashMap<String, Validity>();
        var spines = new HashMap<String, ReferenceSpine>();
        var crystals = new HashMap<String, CrystalReference>();
        for (var o : group(groups,"components").values()) checked(origins,o,() -> {
            JsonObject cut = o.has("cut") ? object(o,"cut") : null;
            String id=string(o,"id"), kind=string(o,"kind");
            componentAppearances.put(id, readAppearance(o));
            if (kind.equals("petroleum_fraction")) {
                if (cut == null || !cut.has("lower_kelvin") && !cut.has("upper_kelvin")) throw new IllegalArgumentException("cut boundaries required");
                string(cut,"derivation"); string(cut,"series");
            }
            names.put(id,new MaterialName(id,string(o,"translation_key"),string(o,"fallback"),kind,
                    cut != null && cut.has("lower_kelvin") ? number(cut,"lower_kelvin") : null,
                    cut != null && cut.has("upper_kelvin") ? number(cut,"upper_kelvin") : null,
                    cut != null && bool(cut,"estimated")));
        });
        for (var o : group(groups,"spine").values()) checked(origins,o,() -> {
            var spine=ReferenceSpine.read(o); require(names,spine.component(),"component"); spines.put(spine.id(),spine);
        });
        for (var o : group(groups,"crystals").values()) checked(origins,o,() -> {
            var crystal=CrystalReference.read(o); require(names,crystal.component(),"component"); crystals.put(crystal.id(),crystal);
        });
        var matrices = new HashMap<String, GroupInteractionMatrix>();
        for (var o : group(groups,"group_interactions").values()) checked(origins,o,() -> {
            var matrix=GroupInteractionMatrix.read(o); matrices.put(matrix.id(),matrix);
        });
        for (var o : group(groups,"properties").values()) checked(origins,o,() -> {
            String id=string(o,"id"), component=string(o,"component"); require(names,component,"component");
            viscosities.put("properties/" + id, readViscosities(o));
            descriptors.put(id,LiquidMixtureCorrection.Descriptor.read(o));
            string(o,"revision"); string(o,"source");
            // ideal_gas_cp is optional: a record without it is usable only in a package that declares a reference
            // spine (D6, one ideal-gas source per species; checked per package below). Its cp is then empty.
            JsonObject cp=o.has("ideal_gas_cp")?object(o,"ideal_gas_cp"):null;
            List<Double> coefficients=List.of(); double reference=298.15;
            if (cp!=null) {
                if (!string(cp,"type").equals("shifted_polynomial_5")) throw new IllegalArgumentException("Unsupported ideal_gas_cp.type");
                coefficients=numbers(cp,"coefficients",6); reference=positive(cp,"reference_kelvin");
                if (reference != 298.15) throw new IllegalArgumentException("shifted_polynomial_5.reference_kelvin must be 298.15");
            }
            JsonObject models=object(o,"models"); Pr78 pr=null;
            for (String model : models.keySet()) if (!model.equals("pr78")) throw new IllegalArgumentException("Unsupported pure-component model: " + model);
            if (models.has("pr78")) {
                JsonObject p=object(models,"pr78");
                pr=new Pr78(positive(p,"critical_temperature_kelvin"),positive(p,"critical_pressure_pascal"),number(p,"acentric_factor"));
            }
            double min=positive(o,"temperature_min_kelvin"), max=positive(o,"temperature_max_kelvin"); range(min,max,"temperature");
            CpSegment low=null;
            if (cp!=null && cp.has("below")) {
                JsonObject segment=object(cp,"below"); string(segment,"source");
                double joint=positive(segment,"temperature_kelvin");
                if (joint<=min || joint>max || joint>reference) throw new IllegalArgumentException("ideal_gas_cp.below.temperature_kelvin must lie in (temperature_min_kelvin, 298.15]");
                low=new CpSegment(joint,numbers(segment,"coefficients",6));
            }
            VolumeTranslation translation=o.has("volume_translation")?VolumeTranslation.read(o,pr):null;
            properties.put(id,new Property(id,component,positive(o,"molecular_weight_kg_per_mol"),positive(o,"normal_boiling_point_kelvin"),
                    positive(o,"standard_liquid_density_kg_per_m3"),positive(o,"standard_temperature_kelvin"),positive(o,"standard_pressure_pascal"),
                    min,max,reference,coefficients,pr,bool(o,"estimated_heavy_residue"),low,translation));
            if (o.has("fluid_domain")) {
                var domain=validity(object(o,"fluid_domain"));
                // The vapour viscosity the network evaluates for every vapour it carries must cover the range the
                // component claims, or a state inside it would still fail on a property it has no data for.
                var vapor=viscosities.get("properties/"+id).get(ViscosityCorrelation.Phase.VAPOR);
                if (vapor!=null && (vapor.minimumTemperatureKelvin()>domain.minimumTemperature() || vapor.maximumTemperatureKelvin()<domain.maximumTemperature()))
                    throw new IllegalArgumentException("fluid_domain: temperature range exceeds the vapor viscosity data ("+vapor.minimumTemperatureKelvin()+".."+vapor.maximumTemperatureKelvin()+" K)");
                if (low!=null && domain.minimumTemperature()<min)
                    throw new IllegalArgumentException("fluid_domain: below temperature_min_kelvin of a record with its own low-temperature fit");
                propertyValidity.put(id,domain);
            }
        });
        for (var o : group(groups,"water").values()) checked(origins,o,() -> {
            viscosities.put("water/" + string(o,"id"), readViscosities(o));
            if (!string(o,"type").equals("iapws_shomate_watson")) throw new IllegalArgumentException("Unsupported water.type");
            string(o,"source");
            Water w=new Water(string(o,"revision"),positive(o,"molar_mass"),positive(o,"triple_point"),positive(o,"critical_temperature"),
                    positive(o,"critical_pressure"),positive(o,"max_enthalpy_temperature"),positive(o,"reference_boiling_temperature"),
                    positive(o,"reference_vaporization_enthalpy"),positive(o,"watson_exponent"),numbers(o,"saturation_coefficients",6),numbers(o,"shomate_coefficients",7));
            range(w.triplePoint(),w.boilingTemperature(),"water boiling"); range(w.boilingTemperature(),w.criticalTemperature(),"water critical");
            range(w.triplePoint(),w.maximumTemperature(),"water enthalpy"); require(names,"Water","component"); waters.put(string(o,"id"),w);
        });
        for (var o : group(groups,"interactions").values()) checked(origins,o,() -> validateInteractions(o,names,matrices));
        for (var o : group(groups,"packages").values()) checked(origins,o,() -> {
            String id=string(o,"id"), model=string(o,"model"), policy=string(o,"missing_interactions");
            if(string(o,"revision").length()>63)throw new IllegalArgumentException("revision: maximum 63 characters with fingerprint");
            var evidence=strings(o,"advisory_evidence");
            if(evidence.size()>32 || evidence.stream().anyMatch(e->e.length()>256))throw new IllegalArgumentException("advisory_evidence: exceeds reporting bounds");
            if (!Set.of("pr78","nrtl").contains(model)) throw new IllegalArgumentException("Unsupported model: " + model);
            if (!Set.of("zero","error").contains(policy) || model.equals("nrtl") && !policy.equals("error"))
                throw new IllegalArgumentException("Invalid missing_interactions policy");
            List<String> ids=strings(o,"components"), refs=strings(o,"properties");
            if (ids.isEmpty() || ids.size()>64 || new HashSet<>(ids).size()!=ids.size() || ids.size()!=refs.size())
                throw new IllegalArgumentException("Invalid ordered components/properties");
            var ps=new ArrayList<Property>();
            double tmin=positive(o,"temperature_min_kelvin"),tmax=positive(o,"temperature_max_kelvin"); range(tmin,tmax,"temperature");
            double pmin=positive(o,"pressure_min_pascal"),pmax=positive(o,"pressure_max_pascal"); range(pmin,pmax,"pressure");
            for(int i=0;i<ids.size();i++) {
                require(names,ids.get(i),"component"); Property p=require(properties,refs.get(i),"property");
                if(!p.component().equals(ids.get(i)) || model.equals("pr78") && p.pr()==null) throw new IllegalArgumentException("Property/model does not match component " + ids.get(i));
                if(tmin<p.minimumTemperature() || tmax>p.maximumTemperature()) throw new IllegalArgumentException("Package temperature outside property validity: " + p.id());
                // D6, one ideal-gas source per species: a package with a reference spine takes every component's ideal
                // gas from it, a package without one from the property's shifted_polynomial_5 fit.
                if(p.cp().isEmpty() && !o.has("spine"))
                    throw new IllegalArgumentException("Property "+p.id()+" has no ideal_gas_cp: a package without a reference spine needs a shifted_polynomial_5 fit for "+ids.get(i));
                if(!p.cp().isEmpty() && o.has("spine"))
                    throw new IllegalArgumentException("Property "+p.id()+" carries ideal_gas_cp in a package with a reference spine: one ideal-gas source per species (D6), "+ids.get(i));
                ps.add(p);
            }
            List<String> spineIds=o.has("spine")?strings(o,"spine"):List.of(), crystalIds=o.has("crystals")?strings(o,"crystals"):List.of();
            var spineRecords=new ArrayList<ReferenceSpine>();var spineScience=new ArrayList<Object>();
            if(o.has("spine")) {
                if(spineIds.size()!=ids.size())throw new IllegalArgumentException("spine: expected one reference-spine record per component ("+ids.size()+"), got "+spineIds.size());
                for(int i=0;i<ids.size();i++) {
                    var spine=require(spines,spineIds.get(i),"spine");
                    if(!spine.component().equals(ids.get(i)))throw new IllegalArgumentException("spine: "+spine.id()+" is for "+spine.component()+", not component "+ids.get(i));
                    double difference=Math.abs(spine.molarMass()/ps.get(i).molecularWeight()-1);
                    if(difference>1e-4)throw new IllegalArgumentException("spine: molar mass of "+spine.id()+" ("+spine.molarMass()+" kg/mol) differs from property "
                            +ps.get(i).id()+" ("+ps.get(i).molecularWeight()+" kg/mol) by "+difference+" relative, above 1e-4");
                    spineRecords.add(spine);spineScience.add(spine.science());
                }
            }
            liquidMixtures.put(id,LiquidMixtureCorrection.read(o,refs.stream().map(descriptors::get).toList()));
            JsonObject interaction=require(group(groups,"interactions"),string(o,"interactions"),"interactions");
            if(!model.equals(string(interaction,"model"))) throw new IllegalArgumentException("Interaction model mismatch");
            double[][] matrix=new double[ids.size()][ids.size()]; var nrtl=new ArrayList<NrtlPair>(); var seen=new HashSet<String>();
            boolean[][] constant=new boolean[ids.size()][ids.size()];
            for(var value:array(interaction,"pairs")) {
                JsonObject pair=value.getAsJsonObject(); String a=string(pair,"first"), b=string(pair,"second");
                int i=ids.indexOf(a),j=ids.indexOf(b); if(i<0 || j<0) throw new IllegalArgumentException("Interaction component outside package");
                seen.add(pairKey(a,b));constant[i][j]=constant[j][i]=true;
                if(model.equals("pr78")) matrix[i][j]=matrix[j][i]=number(pair,"kij");
                else {
                    NrtlPair n=nrtl(pair); if(tmin<n.minimumTemperature() || tmax>n.maximumTemperature()) throw new IllegalArgumentException("NRTL temperature validity"); nrtl.add(n);
                }
            }
            GroupContributionInteractions groupContributions=null;
            if(interaction.has("rule")) {
                // Every pair without an explicit constant is resolved by the group-contribution rule from the spines'
                // group counts; its constant matrix entry stays 0.0 and is not its kij (see Package).
                JsonObject rule=object(interaction,"rule");
                if(spineRecords.isEmpty())throw new IllegalArgumentException("interactions rule "+string(rule,"type")+": the package declares no spine, whose groups decompose each component");
                groupContributions=GroupContributionInteractions.resolve(string(rule,"type"),require(matrices,string(rule,"group_interactions"),"rule.group_interactions"),
                        ids,spineRecords,constant);
                for(var resolved:groupContributions.pairs())seen.add(pairKey(ids.get(resolved.first()),ids.get(resolved.second())));
            }
            if(policy.equals("error")) for(int i=0;i<ids.size();i++) for(int j=i+1;j<ids.size();j++)
                if(!seen.contains(pairKey(ids.get(i),ids.get(j)))) throw new IllegalArgumentException("Missing interaction: " + ids.get(i)+" / "+ids.get(j));
            Map<String,String> aliases=new HashMap<>();
            for(var e:object(o,"aliases").entrySet()) {
                String target=e.getValue().getAsString();
                if(!e.getKey().matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}") || !ids.contains(target) || ids.contains(e.getKey()) && !e.getKey().equals(target))
                    throw new IllegalArgumentException("Ambiguous/invalid alias: "+e.getKey());
                aliases.put(e.getKey(),target);
            }
            Water water=require(waters,string(o,"water_model"),"water_model");
            waterModels.put(id, string(o,"water_model"));
            if(o.has("fluid_domain")) {
                // The fluid network's rule: the package range is the outer envelope, every component's own range - and
                // the water model's, from its triple point to its enthalpy limit - lies inside it, and a state is valid
                // only inside the range of every component it carries (science.fluid.thermo.FluidDomain).
                var envelope=validity(object(o,"fluid_domain"));
                for(var p:ps) {
                    var own=propertyValidity.get(p.id());
                    if(own==null)throw new IllegalArgumentException("fluid_domain: component "+p.component()+" ("+p.id()+") declares no fluid_domain");
                    if(!own.within(envelope))throw new IllegalArgumentException("fluid_domain: component "+p.component()+" range lies outside the package envelope");
                }
                if(water.triplePoint()<envelope.minimumTemperature()||water.maximumTemperature()>envelope.maximumTemperature())
                    throw new IllegalArgumentException("fluid_domain: the water model's range "+water.triplePoint()+".."+water.maximumTemperature()+" K lies outside the package envelope");
                packageValidity.put(id,envelope);
            }
            var assays=new HashMap<String,Assay>();
            for(var a:group(groups,"assays").values()) if(string(a,"package").equals(id)) checked(origins,a,() -> {
                assayAppearances.put(id + "/" + string(a,"id"), readAppearance(a));
                List<String> axis; List<Double> amounts;
                if(a.has("amounts_by_component")) {
                    if(a.has("components")||a.has("amounts"))throw new IllegalArgumentException("amounts_by_component: cannot combine sparse and positional forms");
                    var sparse=object(a,"amounts_by_component");
                    for(String key:sparse.keySet())if(!ids.contains(key))throw new IllegalArgumentException("amounts_by_component: unknown component "+key);
                    axis=ids;var ordered=new ArrayList<Double>();
                    for(String component:ids)ordered.add(sparse.has(component)?number(sparse,component):0.0);
                    amounts=List.copyOf(ordered);
                } else {axis=strings(a,"components");amounts=numbers(a,"amounts",ids.size());}
                double total=amounts.stream().mapToDouble(Double::doubleValue).sum();
                if(!axis.equals(ids) || amounts.stream().anyMatch(v->v<0) || total<=0 || !Double.isFinite(total)) throw new IllegalArgumentException("Invalid assay components/amounts");
                String basis=string(a,"basis"); if(!Set.of("mole","mass","standard_liquid_volume").contains(basis)) throw new IllegalArgumentException("Unknown assay basis");
                if(basis.equals("standard_liquid_volume")) for(Property p:ps)
                    if(number(a,"standard_temperature_kelvin")!=p.standardTemperature() || number(a,"standard_pressure_pascal")!=p.standardPressure())
                        throw new IllegalArgumentException("Assay density reference conditions differ");
                assays.put(string(a,"id"),new Assay(string(a,"id"),basis,axis,amounts,a.has("volume_scale")?positive(a,"volume_scale"):1,
                        a.has("amount_total")?positive(a,"amount_total"):1));
            });
            var rows=new ArrayList<List<Double>>(); for(double[] row:matrix) rows.add(Arrays.stream(row).boxed().toList());
            var hashed=new ArrayList<Object>(List.of(model,ids,ps,rows,nrtl,new TreeMap<>(assays),water,tmin,tmax,pmin,pmax));
            // Only a package with a group-contribution rule hashes it, so every other package keeps its exact pin.
            if(groupContributions!=null)hashed.add(groupContributions.science(ids,ids));
            String fingerprint=hash(new Gson().toJson(hashed));
            var crystalScience=new ArrayList<Object>();
            if(new HashSet<>(crystalIds).size()!=crystalIds.size())throw new IllegalArgumentException("crystals: duplicate crystal record");
            for(String crystalId:crystalIds) {
                var crystal=require(crystals,crystalId,"crystals");
                if(!ids.contains(crystal.component()))throw new IllegalArgumentException("crystals: "+crystalId+" is for "+crystal.component()+", outside the package components");
                crystalScience.add(crystal.science());
            }
            String spineFingerprint=spineIds.isEmpty()&&crystalIds.isEmpty()?"":hash(new Gson().toJson(List.of(spineScience,crystalScience)));
            packages.put(id,new Package(id,string(o,"revision"),fingerprint,model,ids,ps,rows,nrtl,aliases,assays,water,tmin,tmax,pmin,pmax,strings(o,"advisory_evidence"),
                    spineIds,crystalIds,spineFingerprint,groupContributions));
        });
        for(var a:group(groups,"assays").values()) checked(origins,a,() -> require(packages,string(a,"package"),"assay package"));
        if(packages.isEmpty()) throw new IllegalArgumentException("Material catalog has no packages");
        var result=new MaterialCatalog(names,packages,resources,viscosities,waterModels,assayAppearances,componentAppearances,liquidMixtures,MaterialPresets.read(group(groups,"presets"),group(groups,"networks"),origins,packages,names.keySet()),MaterialFluidData.read(group(groups,"transport"),origins,names.keySet()),
                propertyValidity,packageValidity,spines,crystals,matrices);
        for(var row:group(groups,"presets").values())if(string(row,"kind").equals("column"))checked(origins,row,()->
                MaterialRuntime.with(result,string(row,"package"),()->{com.wormzjl.createcheme.science.column.v3.V3ColumnProblemResolver.validateInput(result.presets().column(string(row,"id")).input(result));return null;}));
        for(var row:group(groups,"networks").values())checked(origins,row,()->{
            var config=result.presets().network();var network=result.requirePackage(config.packageId());
            for(String id:config.fluidPresets()) {
                var preset=result.presets().fluid(id);
                if(!preset.component().isEmpty()) {
                    if(!preset.component().equals("Water")&&!network.components().contains(preset.component()))throw new IllegalArgumentException("fluid_presets: component outside network "+preset.component());
                } else result.requireSharedFluidPhysics(preset.packageId(),network.id());
            }
            for(var phase:ViscosityCorrelation.Phase.values())if(result.viscosity(network.id(),"Nitrogen",phase).isEmpty())throw new IllegalArgumentException("package: nitrogen transport is required");
            if(result.fluidValidity(network.id()).isEmpty())throw new IllegalArgumentException("package: the network package needs a fluid_domain envelope");
        });
        return result;
    }

    private static FluidAppearance readAppearance(JsonObject record) {
        if (!record.has("appearance")) return FluidAppearance.DEFAULT;
        try {
            JsonObject o = object(record, "appearance");
            String color = string(o, "color");
            if (!color.matches("#[0-9a-fA-F]{6}"))
                throw new IllegalArgumentException("color must be #RRGGBB (alpha is specified by transparency)");
            return new FluidAppearance(Integer.parseInt(color.substring(1), 16), number(o, "transparency"), bool(o, "estimated"));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("appearance: " + invalid.getMessage(), invalid);
        }
    }

    private static Map<ViscosityCorrelation.Phase, ViscosityCorrelation> readViscosities(JsonObject record) {
        if (!record.has("viscosity")) return Map.of();
        var result = new EnumMap<ViscosityCorrelation.Phase, ViscosityCorrelation>(ViscosityCorrelation.Phase.class);
        for (var entry : object(record, "viscosity").entrySet()) {
            try {
                var phase = switch (entry.getKey()) {
                    case "liquid" -> ViscosityCorrelation.Phase.LIQUID;
                    case "vapor" -> ViscosityCorrelation.Phase.VAPOR;
                    default -> throw new IllegalArgumentException("unknown phase");
                };
                JsonObject o = entry.getValue().getAsJsonObject();
                var model = switch (string(o,"type")) {
                    case "andrade" -> ViscosityCorrelation.Model.ANDRADE;
                    case "sutherland" -> ViscosityCorrelation.Model.SUTHERLAND;
                    case "power_sum" -> ViscosityCorrelation.Model.POWER_SUM;
                    case "log_table" -> ViscosityCorrelation.Model.LOG_TABLE;
                    default -> throw new IllegalArgumentException("unsupported type");
                };
                if (model == ViscosityCorrelation.Model.ANDRADE && phase != ViscosityCorrelation.Phase.LIQUID
                        || model == ViscosityCorrelation.Model.SUTHERLAND && phase != ViscosityCorrelation.Phase.VAPOR)
                    throw new IllegalArgumentException("correlation type does not match phase");
                int count = model == ViscosityCorrelation.Model.POWER_SUM || model == ViscosityCorrelation.Model.LOG_TABLE
                        ? array(o,"coefficients").size() : 2;
                result.put(phase, new ViscosityCorrelation(model, positive(o,"temperature_min_kelvin"), positive(o,"temperature_max_kelvin"),
                        positive(o,"pressure_min_pascal"), positive(o,"pressure_max_pascal"), positive(o,"reference_temperature_kelvin"),
                        numbers(o,"coefficients",count), o.has("exponents") ? numbers(o,"exponents",count) : List.of(),
                        string(o,"revision"), string(o,"source"), bool(o,"estimated"),
                        o.has("temperatures_kelvin") ? numbers(o,"temperatures_kelvin",count) : List.of()));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("viscosity." + entry.getKey() + ": " + error.getMessage(), error);
            }
        }
        return Map.copyOf(result);
    }
    private static void validateInteractions(JsonObject o,Map<String,MaterialName> names,Map<String,GroupInteractionMatrix> matrices) {
        String model=string(o,"model"); string(o,"source");
        if(!Set.of("pr78","nrtl").contains(model)) throw new IllegalArgumentException("Unknown interaction model");
        if(o.has("rule")) {
            // A group-contribution rule resolves, per package, every pair without an explicit constant (P3 E-PPR78).
            if(!model.equals("pr78"))throw new IllegalArgumentException("rule: a group-contribution kij rule needs model pr78");
            JsonObject rule=object(o,"rule");String type=string(rule,"type");
            if(!type.equals(GroupContributionInteractions.RULE_EPPR78))throw new IllegalArgumentException("rule.type: expected "+GroupContributionInteractions.RULE_EPPR78+", got "+type);
            require(matrices,string(rule,"group_interactions"),"rule.group_interactions");
        }
        var seen=new HashSet<String>();
        for(var value:array(o,"pairs")) {
            JsonObject p=value.getAsJsonObject(); String a=string(p,"first"),b=string(p,"second"); require(names,a,"first"); require(names,b,"second");
            if(a.equals(b) || !seen.add(pairKey(a,b))) throw new IllegalArgumentException("Duplicate/self interaction pair");
            if(model.equals("pr78")) number(p,"kij"); else nrtl(p);
        }
    }
    private static Validity validity(JsonObject o) {
        try {
            String evidence=string(o,"evidence");
            double tmin=positive(o,"temperature_min_kelvin"),tmax=positive(o,"temperature_max_kelvin");range(tmin,tmax,"temperature");
            double pmin=positive(o,"pressure_min_pascal"),pmax=positive(o,"pressure_max_pascal");range(pmin,pmax,"pressure");
            return new Validity(tmin,tmax,pmin,pmax,evidence);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("fluid_domain: "+invalid.getMessage(),invalid); }
    }
    private static NrtlPair nrtl(JsonObject p) {
        double min=positive(p,"temperature_min_kelvin"),max=positive(p,"temperature_max_kelvin"); range(min,max,"NRTL temperature");
        double alpha=number(p,"alpha"); if(alpha<0 || alpha>1) throw new IllegalArgumentException("NRTL alpha must be in [0,1]");
        return new NrtlPair(string(p,"first"),string(p,"second"),number(p,"a12"),number(p,"b12_kelvin"),number(p,"a21"),number(p,"b21_kelvin"),alpha,min,max);
    }
    private static String pairKey(String a,String b) { return a.compareTo(b)<0?a+"/"+b:b+"/"+a; }
    private static Map<String,JsonObject> group(Map<String,Map<String,JsonObject>> groups,String key) { return groups.getOrDefault(key,Map.of()); }
    private static <T> T require(Map<String,T> map,String id,String field) { T v=map.get(id); if(v==null)throw new IllegalArgumentException(field+": unresolved "+id);return v; }
    static JsonElement required(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull())
            throw new IllegalArgumentException(key + ": required field");
        return o.get(key);
    }
    static JsonObject object(JsonObject o, String key) {
        JsonElement e=required(o,key);
        if(!e.isJsonObject())throw new IllegalArgumentException(key+": expected object");
        return e.getAsJsonObject();
    }
    static JsonArray array(JsonObject o, String key) {
        JsonElement e=required(o,key);
        if(!e.isJsonArray())throw new IllegalArgumentException(key+": expected array");
        return e.getAsJsonArray();
    }
    static String string(JsonObject o,String key) {
        JsonElement e=required(o,key);
        if(!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())throw new IllegalArgumentException(key+": expected string");
        String s=e.getAsString();
        if(s.isBlank() || s.length()>2048)throw new IllegalArgumentException(key+": invalid text");
        return s;
    }
    static boolean bool(JsonObject o,String key) {
        JsonElement e=required(o,key);
        if(!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException(key+": expected boolean");
        return e.getAsBoolean();
    }
    static double number(JsonObject o,String key) {
        JsonElement e=required(o,key);
        if(!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(key+": expected number");
        double d=e.getAsDouble();
        if(!Double.isFinite(d))throw new IllegalArgumentException(key+": nonfinite");
        return d;
    }
    static double positive(JsonObject o,String key) { double d=number(o,key);if(d<=0)throw new IllegalArgumentException(key+": must be positive");return d; }
    static void range(double a,double b,String field) { if(a>=b)throw new IllegalArgumentException(field+": invalid range"); }
    private static List<String> strings(JsonObject o,String key) { var r=new ArrayList<String>();for(var e:array(o,key))r.add(e.getAsString());return List.copyOf(r); }
    private static List<Double> numbers(JsonObject o,String key,int count) {
        var r=new ArrayList<Double>();for(var e:array(o,key)){double d=e.getAsDouble();if(!Double.isFinite(d))throw new IllegalArgumentException(key+": nonfinite");r.add(d);}
        if(r.size()!=count)throw new IllegalArgumentException(key+": expected "+count+" coefficients/amounts");return List.copyOf(r);
    }
    private static void checked(Map<JsonObject,String> origins,JsonObject o,Runnable action) { try{action.run();}catch(RuntimeException e){throw error(origins.get(o),e);} }
    private static IllegalArgumentException error(String path,RuntimeException e) { return new IllegalArgumentException(path+": "+e.getMessage(),e); }
    private static String hash(String s) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    /** Classpath loader shared by standalone tools and regression tests. */
    public static MaterialCatalog bundled() { return Bundled.INSTANCE; }
    private static final class Bundled {
        static final MaterialCatalog INSTANCE=load();
        static MaterialCatalog load() {
            var resources=new LinkedHashMap<String,String>();
            for(var path:JsonParser.parseString(read("materials-index.json")).getAsJsonArray()) {
                String key=path.getAsString();
                if(!key.matches("data/[a-z0-9_.-]+/materials/[a-z0-9_./-]+\\.json")||key.contains("..")||resources.putIfAbsent(key,read(key))!=null)
                    throw new IllegalArgumentException("Invalid or duplicate material index entry: "+key);
            }
            return parse(resources);
        }
        static String read(String path) {
            try(InputStream in=MaterialCatalog.class.getClassLoader().getResourceAsStream(path)) {
                if(in==null)throw new IllegalArgumentException("Missing bundled material resource "+path);
                return new String(in.readAllBytes(),StandardCharsets.UTF_8);
            }catch(IOException e){throw new UncheckedIOException(path,e);}
        }
    }
}
