/** Owned lifecycle fixture: no game classes, packets, or native calls. */
public final class AdninGameModules {
    public static volatile boolean stopped;
    public static void stop() { stopped = true; }
}
