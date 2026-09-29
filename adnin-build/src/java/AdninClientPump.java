/*
 * Decompiled with CFR 0.152.
 */
public class AdninClientPump
implements Runnable {
    @Override
    public void run() {
        AdninClientPump.nativeClientPump();
        try {
            AdninFeatures.tick();
        } catch (Exception failure) {
            // A failed optional feature must not stop the native client pump.
        }
    }

    private static native void nativeClientPump();
}
