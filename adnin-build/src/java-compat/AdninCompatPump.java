import net.minecraft.client.Minecraft;

/** Compatibility client-thread scheduler. The daemon only enqueues work. */
public final class AdninCompatPump implements Runnable {
    private static final Object LIFECYCLE = new Object();
    private static boolean started;
    private static boolean stopped;
    private static boolean queued;
    private static Thread daemon;
    private final Minecraft minecraft;
    private final Runnable callback;
    private final boolean clientTask;

    private AdninCompatPump(Minecraft minecraft, Runnable callback, boolean clientTask) {
        this.minecraft = minecraft;
        this.callback = callback;
        this.clientTask = clientTask;
    }

    public static void start(Minecraft minecraft, Runnable callback) {
        if (minecraft == null || callback == null) return;
        synchronized (LIFECYCLE) {
            if (started || stopped) return;
            started = true;
            Runnable delivery = new AdninCompatPump(minecraft, callback, true);
            daemon = new Thread(new AdninCompatPump(minecraft, delivery, false), "Adnin client scheduler");
            daemon.setDaemon(true);
            daemon.start();
        }
    }

    /** Drain an executing delivery and invalidate queued deliveries before native unload. */
    public static void stop() {
        Thread scheduler;
        synchronized (LIFECYCLE) {
            stopped = true;
            scheduler = daemon;
            daemon = null;
        }
        if (scheduler != null) scheduler.interrupt();
        AdninGameModules.stop();
    }

    public static boolean isRunning() {
        synchronized (LIFECYCLE) { return started && !stopped; }
    }

    @Override public void run() {
        if (clientTask) {
            synchronized (LIFECYCLE) {
                // The lock spans the callback, including its native heartbeat.
                // stop() cannot return while native code is still in use.
                try { if (!stopped) callback.run(); }
                finally { queued = false; }
            }
            return;
        }
        for (;;) {
            try {
                synchronized (LIFECYCLE) {
                    if (stopped) return;
                    if (!queued) {
                        queued = true;
                        try { minecraft.addScheduledTask(callback); }
                        catch (RuntimeException unavailable) { queued = false; }
                    }
                }
                Thread.sleep(50L);
            } catch (InterruptedException shutdown) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
