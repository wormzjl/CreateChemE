package com.wormzjl.createcheme.science.fluid.state;

import com.wormzjl.createcheme.science.material.SolidMaterial;
import java.util.*;

/** Immutable exact conserved populations. Trace masking never deletes an entry or frees a slot. */
public record SolidInventory(List<Population> populations) {
    public static final int MAXIMUM_POPULATIONS = 64;
    public static final SolidInventory EMPTY = new SolidInventory(List.of());
    public record Key(String material, ParticleSize size) implements Comparable<Key> {
        public Key { Objects.requireNonNull(material); Objects.requireNonNull(size); }
        @Override public int compareTo(Key other) {
            int result = material.compareTo(other.material);
            return result == 0 ? size.compareTo(other.size) : result;
        }
    }
    public record Population(SolidMaterial material, ParticleSize size, double massKg) {
        public Population {
            Objects.requireNonNull(material); Objects.requireNonNull(size);
            if (!Double.isFinite(massKg) || massKg < 0) throw new IllegalArgumentException("Invalid particle mass");
        }
        public Key key() { return new Key(material.id(), size); }
        public double volume() { return massKg / material.density(); }
    }
    public record Moments(double mass, double volume, double heatCapacity) {
        public static final Moments ZERO = new Moments(0, 0, 0);
        public Moments {
            if (!Double.isFinite(mass) || !Double.isFinite(volume) || !Double.isFinite(heatCapacity)
                    || mass < 0 || volume < 0 || heatCapacity < 0)
                throw new IllegalArgumentException("Invalid solid moments");
        }
        public double internalEnergy(double temperature) { return heatCapacity * (temperature - SolidMaterial.REFERENCE_TEMPERATURE); }
        public double enthalpy(double temperature, double pressure) { return internalEnergy(temperature) + pressure * volume; }
        public double[] values() { return new double[]{mass, volume, heatCapacity}; }
    }
    public SolidInventory {
        Objects.requireNonNull(populations);
        var combined = new TreeMap<Key, Population>();
        for (var population : populations) {
            Objects.requireNonNull(population);
            if (population.massKg() == 0) continue;
            combined.merge(population.key(), population, (a, b) -> {
                if (!a.material().equals(b.material())) throw new IllegalArgumentException("Incompatible solid properties: " + a.material().id());
                return new Population(a.material(), a.size(), a.massKg() + b.massKg());
            });
            if (combined.size() > MAXIMUM_POPULATIONS) throw new IllegalArgumentException("Solid population limit exceeded (64)");
        }
        populations = List.copyOf(combined.values());
        double mass = 0, volume = 0, capacity = 0;
        for (var p : populations) { mass += p.massKg(); volume += p.volume(); capacity += p.massKg() * p.material().heatCapacity(); }
        if (!Double.isFinite(mass) || !Double.isFinite(volume) || !Double.isFinite(capacity)
                || mass > 0 && (volume == 0 || capacity == 0))
            throw new IllegalArgumentException("Solid inventory overflow/underflow");
    }
    public boolean empty() { return populations.isEmpty(); }
    public Moments moments() {
        double mass = 0, volume = 0, capacity = 0;
        for (var p : populations) { mass += p.massKg(); volume += p.volume(); capacity += p.massKg() * p.material().heatCapacity(); }
        return new Moments(mass, volume, capacity);
    }
    public double massKg() { return moments().mass(); }
    public double volume() { return moments().volume(); }
    public double mass(Key key) { for (var p : populations) if (p.key().equals(key)) return p.massKg(); return 0; }
    public SolidInventory plus(SolidInventory other) {
        var merged = new ArrayList<>(populations); merged.addAll(other.populations);
        return new SolidInventory(merged);
    }
    public SolidInventory scale(double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0) throw new IllegalArgumentException("Invalid solid scaling");
        if (fraction == 0 || empty()) return EMPTY;
        return new SolidInventory(populations.stream().map(p -> new Population(p.material(), p.size(), p.massKg() * fraction)).toList());
    }
    /** Worker-local signed accumulation; validation and population bounds apply to the completed stock. */
    public static final class Accumulator {
        private final Map<Key,Population> basis=new TreeMap<>();
        private final Map<Key,Double> masses=new TreeMap<>();
        public void add(SolidInventory stock,double factor) {
            if(!Double.isFinite(factor))throw new IllegalArgumentException("Invalid solid weight");
            for(var p:stock.populations){var old=basis.putIfAbsent(p.key(),p);if(old!=null&&!old.material().equals(p.material()))throw new IllegalArgumentException("Incompatible solid properties");masses.merge(p.key(),factor*p.massKg(),Double::sum);}
        }
        public SolidInventory finish() {var result=new ArrayList<Population>();for(var entry:masses.entrySet()){var p=basis.get(entry.getKey());result.add(new Population(p.material(),p.size(),entry.getValue()));}return new SolidInventory(result);}
    }
    public static SolidInventory combine(SolidInventory first,double firstWeight,SolidInventory second,double secondWeight) {
        var total=new Accumulator();total.add(first,firstWeight);total.add(second,secondWeight);return total.finish();
    }
    public record Portion(SolidInventory delivered, SolidInventory remaining) {}
    public Portion takeFraction(double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) throw new IllegalArgumentException("Invalid solid withdrawal");
        var delivered = new ArrayList<Population>(); var remaining = new ArrayList<Population>();
        for (var p : populations) {
            double mass = p.massKg() * fraction;
            delivered.add(new Population(p.material(), p.size(), mass));
            remaining.add(new Population(p.material(), p.size(), p.massKg() - mass));
        }
        return new Portion(new SolidInventory(delivered), new SolidInventory(remaining));
    }
}