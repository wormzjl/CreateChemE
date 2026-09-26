"""One-time additive derivation; never modifies the frozen transformer runtime."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = (root/'tools/neural/V3ColumnTransformerInitializer.java').read_text()
source = source.replace('V3ColumnTransformerInitializer', 'V3HybridResidualInitializer')
source = source.replace('v3-column-transformer-1', 'v3-hybrid-residual-1')
source = source.replace('!("transformer".equals(m.modelType) || "mlp".equals(m.modelType))', '!"hybrid-residual".equals(m.modelType)')
source = source.replace('List.of("xm", "xscale", "ym", "yscale", "gm", "gscale")', 'List.of("xm", "xscale", "ym", "yscale", "gm", "gscale", "bm", "bscale", "dm", "dscale")')
source = source.replace('name.startsWith("y") ? 85 : 74', 'name.startsWith("g") ? 74 : 85')
source = source.replace('checkLinear("embed", 96, 64)', 'checkLinear("embed", 185, 64)')
source = source.replace('m.modelType.equals("transformer")', 'm.modelType.equals("hybrid-residual")')
source = source.replace('model.modelType.equals("transformer")', 'model.modelType.equals("hybrid-residual")')
source = source.replace('? 83800 : 83288', '? 89496 : 83288')
old = '''        double[][] features = V3GeneralNeuralFeatures.nodes(input, V3CondenserPhaseBranch.TWO_PHASE);
        double[][] hidden = new double[features.length][];
        for (int i = 0; i < features.length; i++) hidden[i] = linear(normalize(Arrays.copyOf(features[i], 96), "x"), "embed", control);'''
new = '''        int best = -1;
        boolean reflux = input.specifications().stream().anyMatch(s -> s instanceof V3ColumnSpecification.OrganicRefluxRatio r && r.ratio() > 0);
        for (int i = 0; i < 3; i++) if (model.branchesSeen[i] && !(i == 2 && reflux)
                && (best < 0 || branch[i] > branch[best])) best = i;
        if (best < 0 || !Double.isFinite(branch[best])) throw new IllegalArgumentException("No legal branch");
        var anchor = V3HybridBaseline.build(input, BRANCHES[best], control);
        double[][] features = V3GeneralNeuralFeatures.nodes(input, V3CondenserPhaseBranch.TWO_PHASE);
        double[][] hidden = new double[features.length][];
        for (int i = 0; i < features.length; i++) {
            double[] joined = new double[185];
            System.arraycopy(normalize(Arrays.copyOf(features[i], 96), "x"), 0, joined, 0, 96);
            System.arraycopy(normalize(anchor.values()[i], "b"), 0, joined, 96, 85);
            joined[181 + best] = 1;
            joined[184] = anchor.available() ? 1 : 0;
            hidden[i] = linear(joined, "embed", control);
        }'''
assert old in source
source = source.replace(old, new)
source = source.replace('values[n][i] * model.normalization.get("yscale")[i] + model.normalization.get("ym")[i]', '(i < 43 ? anchor.values()[n][i] : 0) + values[n][i] * model.normalization.get("dscale")[i] + model.normalization.get("dm")[i]')
source = source.replace('/** Offline CPU transformer/MLP candidate.', '/** Offline native-anchor/residual transformer candidate.')
(root/'tools/hybrid-learning/java/V3HybridResidualInitializer.java').write_text(source, encoding='utf-8', newline='\n')
