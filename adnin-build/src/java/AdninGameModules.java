import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import org.lwjgl.input.Keyboard;

/** Client-thread entry shared by the native pump and HUD. No Forge dependency. */
public final class AdninGameModules {
    private static final Object LIFECYCLE = new Object();
    private static boolean stopped;
    private static long nextSoundTick = Long.MIN_VALUE;
    private static boolean soundFailure, anticheatFailure;
    private static Object uiWorld;

    private AdninGameModules() { }

    public static void tick(Minecraft mc) {
        tick(mc, false);
    }

    /** The fixed-phase GuiIngame.updateTick hook; includes every catch-up tick. */
    public static void gameTick(Minecraft mc) {
        tick(mc, true);
    }

    /** Hold the unload barrier through both native preparation and native drawing. */
    public static void drawSessionHud(Minecraft mc, Runnable nativeStatsTick) {
        synchronized (LIFECYCLE) {
            if (stopped || !AdninGui4.sessionStats) {
                AdninSessionHud.enabled = false;
                return;
            }
            if (mc == null || !mc.isCallingFromMinecraftThread()) return;
            nativeStatsTick.run();
            AdninSessionHud.draw(mc);
        }
    }

    private static void tick(Minecraft mc, boolean gameTick) {
        if (mc == null || !mc.isCallingFromMinecraftThread()) return;
        // Keep stop's optional-worker barrier outside LIFECYCLE: the Features
        // callback holds its own monitor while entering this tick.
        if (Keyboard.isCreated() && Keyboard.isKeyDown(Keyboard.KEY_END)) {
            stop();
            return;
        }
        synchronized (LIFECYCLE) {
            if (stopped) return;
            if (uiWorld != mc.theWorld) {
                try {
                    AdninUi.releaseTextures();
                    uiWorld = mc.theWorld;
                } catch (RuntimeException unavailable) { /* Retry on the next client tick. */ }
                  catch (LinkageError unavailable) { /* Optional GL cleanup cannot abort the HUD tick. */ }
            }
            AdninReplay.tick(mc);
            long now = System.nanoTime();
            if (now >= nextSoundTick) {
                nextSoundTick = now + 5000000L;
                try {
                    AdninClientSounds.tick(mc, AdninGui4.clientSideSounds, AdninAnticheat.enabled);
                    soundFailure = false;
                } catch (Exception | LinkageError failure) {
                    AdninClientSounds.shutdown();
                    if (!soundFailure) error(mc, "[Adnin] Sound and packet hooks are unavailable in this client.");
                    soundFailure = true;
                }
            }
            // A scheduled/frame callback must never claim a tick before its
            // fixed-phase HUD callback: entity movement and animation can still
            // be between updates. Sampling only here also observes every
            // catch-up tick without synthesizing evidence for missing ticks.
            if (!gameTick) return;
            try {
                AdninAnticheat.tick(mc);
                anticheatFailure = false;
            } catch (Exception | LinkageError failure) {
                AdninAnticheat.resetEvidence();
                if (!anticheatFailure) error(mc, "[Anticheat] Detection is unavailable in this client.");
                anticheatFailure = true;
            }
        }
    }

    /** Called by the existing unload barrier; safe on its native worker thread. */
    public static void stop() {
        synchronized (LIFECYCLE) {
            if (stopped) return;
            stopped = true;
            AdninSessionHud.enabled = false;
            uiWorld = null;
            AdninReplay.shutdown();
            AdninClientSounds.shutdown();
            AdninAnticheat.shutdown();
        }
        AdninFeatures.shutdown();
        // OpenGL deletion must run on the client thread even when the native
        // unload barrier is entered by its worker. This callback is Java-only.
        try {
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                if (mc.isCallingFromMinecraftThread()) disposeUiSafely();
                else mc.addScheduledTask(new Runnable() {
                    @Override public void run() { disposeUiSafely(); }
                });
            }
        } catch (RuntimeException unavailable) { }
          catch (LinkageError unavailable) { }
    }

    private static void disposeUiSafely() {
        try { AdninUi.dispose(); }
        catch (RuntimeException unavailable) { /* The client may already have closed its GL context. */ }
        catch (LinkageError unavailable) { }
    }

    private static void error(Minecraft mc, String message) {
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7c" + message + "\u00a7r"));
    }
}
