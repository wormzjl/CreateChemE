package net.minecraft.resources;

/** Harness stand-in for Minecraft's ResourceLocation: namespace:path with Minecraft's character rules and value equality. */
public final class ResourceLocation implements Comparable<ResourceLocation> {
    public static final String DEFAULT_NAMESPACE = "minecraft";
    private final String namespace, path;
    private ResourceLocation(String namespace, String path) {
        if (!namespace.chars().allMatch(c -> c == '_' || c == '-' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.'))
            throw new IllegalArgumentException("Non [a-z0-9_.-] character in namespace of location: " + namespace + ":" + path);
        if (!path.chars().allMatch(c -> c == '_' || c == '-' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '/' || c == '.'))
            throw new IllegalArgumentException("Non [a-z0-9/._-] character in path of location: " + namespace + ":" + path);
        this.namespace = namespace; this.path = path;
    }
    public static ResourceLocation fromNamespaceAndPath(String namespace, String path) { return new ResourceLocation(namespace, path); }
    public static ResourceLocation withDefaultNamespace(String path) { return new ResourceLocation(DEFAULT_NAMESPACE, path); }
    public static ResourceLocation parse(String location) {
        int colon = location.indexOf(':');
        return colon >= 0 ? new ResourceLocation(colon == 0 ? DEFAULT_NAMESPACE : location.substring(0, colon), location.substring(colon + 1))
                : new ResourceLocation(DEFAULT_NAMESPACE, location);
    }
    public static ResourceLocation tryParse(String location) { try { return parse(location); } catch (IllegalArgumentException e) { return null; } }
    public String getNamespace() { return namespace; }
    public String getPath() { return path; }
    @Override public String toString() { return namespace + ":" + path; }
    @Override public boolean equals(Object other) { return this == other || other instanceof ResourceLocation r && namespace.equals(r.namespace) && path.equals(r.path); }
    @Override public int hashCode() { return 31 * namespace.hashCode() + path.hashCode(); }
    @Override public int compareTo(ResourceLocation other) { int c = path.compareTo(other.path); return c != 0 ? c : namespace.compareTo(other.namespace); }
}
