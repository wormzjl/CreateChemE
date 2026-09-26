package net.minecraft.network.chat;

/** Harness stand-in: compile-time only. */
public interface Component {
    static Component literal(String text) { return new Component() { @Override public String toString() { return text; } }; }
}
