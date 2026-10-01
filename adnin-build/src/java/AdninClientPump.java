/*
 * Decompiled with CFR 0.152.
 */
public class AdninClientPump
implements Runnable {
    @Override
    public void run() {
        try {
            AdninFeatures.refreshIgnoredPlayers(net.minecraft.client.Minecraft.getMinecraft());
        } catch (Exception | LinkageError unavailable) {
            // Optional read-only roster work cannot interrupt the native pump.
        }
        AdninClientPump.nativeClientPump();
        try {
            AdninFeatures.tick();
        } catch (Exception failure) {
            // A failed optional feature must not stop the native client pump.
        }
        try {
            // Lunar's recovered native loop omits Vanilla's delayed mode query.
            AdninPartyQueueQuery.tick();
        } catch (Exception | LinkageError unavailable) {
            // Optional queue detection must not interrupt the client task loop.
        }
    }

    private static native void nativeClientPump();
}
