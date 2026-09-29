package net.minecraft.client;

/** Initialization must never occur during the isolated packet-limit probe. */
public final class Minecraft {
    static {
        if (Boolean.parseBoolean("true")) {
            throw new AssertionError("Offline packet test must not initialize Minecraft");
        }
    }
}
