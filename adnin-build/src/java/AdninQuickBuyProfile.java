import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;

/**
 * Copies a Bed Wars shop-layout profile from another player.  This is deliberately
 * independent from AdninGui4's key-bound quick-buy feature: it never reads or
 * writes quickbuyKeys/quickbuySlots and never synthesizes keyboard input.
 *
 * The network observer only records a bounded, immutable slot snapshot.  All
 * client calls (chat, screen close, and window clicks) happen from the normal
 * Minecraft client pump.  The feature is disabled until explicitly requested.
 */
public final class AdninQuickBuyProfile {
    public static final String IDLE = "idle";
    public static final String LOADING = "loading";
    public static final String VIEWING = "viewing";
    public static final String READY = "ready";
    public static final String APPLYING = "applying";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    private static final int MAX_SLOTS = 54;
    private static final long REQUEST_TIMEOUT_MS = 12000L;
    private static final long CLICK_DELAY_MS = 90L;
    private static final Object LOCK = new Object();
    private static volatile boolean enabled;
    private static volatile String state = IDLE;
    private static volatile String targetName = "";
    private static volatile String targetUuid = "";
    private static volatile String message = "";
    private static volatile long deadline;
    private static volatile int sourceWindow = -1;
    private static volatile int currentWindow = -1;
    private static volatile boolean sourceCaptured;
    private static volatile boolean quickBuyWindow;
    private static volatile long nextClick;
    private static volatile int clickIndex;
    private static volatile long sourceQuietUntil;
    private static volatile int[] profileSlots = new int[0];
    private static final AtomicReference<String> pendingName = new AtomicReference<String>();
    private static volatile Thread resolver;
    private static volatile boolean guiBridgeResolved;
    private static volatile Field guiEnabledField;
    private static volatile Field guiPlayerField;
    private static volatile Method guiConsumeMethod;

    private AdninQuickBuyProfile() { }

    public static boolean isEnabled() { return enabled; }
    public static String state() { return state; }
    public static String message() { return message; }
    public static String targetName() { return targetName; }

    /** Optional GUI bridge resolved by name so older offline fixture Gui4
     * classes remain binary-compatible with the shared feature runtime. */
    public static void syncGuiBridge() {
        resolveGuiBridge();
        Field enabledField = guiEnabledField;
        Field playerField = guiPlayerField;
        Method consume = guiConsumeMethod;
        if (enabledField == null || playerField == null || consume == null) {
            setEnabled(false);
            return;
        }
        try {
            boolean on = enabledField.getBoolean(null);
            setEnabled(on);
            Object requested = consume.invoke(null);
            if (Boolean.TRUE.equals(requested) && on) {
                Object player = playerField.get(null);
                request(player == null ? "" : String.valueOf(player));
            }
        } catch (Throwable ignored) {
            // Older compatibility fixtures and clients simply omit the
            // optional copier controls; leave the feature dormant.
            setEnabled(false);
        }
    }

    private static void resolveGuiBridge() {
        if (guiBridgeResolved) return;
        synchronized (LOCK) {
            if (guiBridgeResolved) return;
            try {
                Class<?> gui = Class.forName("AdninGui4");
                Field enabled = gui.getField("quickbuyProfileCopier");
                Field player = gui.getField("quickbuyProfilePlayer");
                Method consume = gui.getMethod("consumeQuickbuyProfileLoadRequest");
                enabled.setAccessible(true); player.setAccessible(true); consume.setAccessible(true);
                guiEnabledField = enabled; guiPlayerField = player; guiConsumeMethod = consume;
            } catch (Throwable unavailable) {
                guiEnabledField = null; guiPlayerField = null; guiConsumeMethod = null;
            }
            guiBridgeResolved = true;
        }
    }

    public static void shutdown() {
        enabled = false;
        pendingName.set(null);
        Thread t = resolver; resolver = null;
        if (t != null) t.interrupt();
        reset("shutdown");
    }

    public static void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (!value) reset("disabled");
    }

    /** Starts an asynchronous Mojang name lookup and requests /viewquickbuy. */
    public static void request(String name) {
        if (!enabled) return;
        String clean = cleanName(name);
        if (clean.length() == 0) { fail("Enter a valid player name."); return; }
        synchronized (LOCK) {
            targetName = clean; targetUuid = ""; sourceWindow = -1; currentWindow = -1;
            sourceCaptured = false; quickBuyWindow = false; clickIndex = 0; sourceQuietUntil = 0L;
            profileSlots = new int[0]; deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;
            state = LOADING; message = "Resolving player profile...";
            pendingName.set(clean);
            if (resolver == null || !resolver.isAlive()) {
                resolver = new Thread(new Runnable() { public void run() { resolveLoop(); } }, "Adnin shop layout profile");
                resolver.setDaemon(true); resolver.start();
            }
        }
    }

    private static void resolveLoop() {
        for (;;) {
            String name = pendingName.getAndSet(null);
            if (name == null) return;
            try {
                String uuid = AdninApi.fetchMojangUuid(name);
                synchronized (LOCK) {
                    if (!enabled || !name.equals(targetName) || !LOADING.equals(state)) continue;
                    targetUuid = uuid; state = VIEWING;
                    message = "Loading shop layout...";
                    deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;
                }
            } catch (Exception failure) {
                fail("Shop profile unavailable.");
            }
        }
    }

    /** Called from the Minecraft client pump; never blocks on network. */
    public static void tick(Minecraft mc) {
        if (!enabled || mc == null || !mc.isCallingFromMinecraftThread()) return;
        String localState = state;
        long now = System.currentTimeMillis();
        if ((LOADING.equals(localState) || VIEWING.equals(localState)) && now > deadline) {
            fail("Shop layout timed out."); return;
        }
        // Some proxies omit S30 and stream only S2F updates.  Wait for a
        // short quiet period before starting clicks so the bounded slot list
        // cannot race the final incremental update.
        if ((VIEWING.equals(localState) || "view-command-sent".equals(localState))
                && sourceCaptured && profileSlots.length > 0 && now >= sourceQuietUntil) {
            synchronized (LOCK) {
                if ((VIEWING.equals(state) || "view-command-sent".equals(state))
                        && sourceCaptured && profileSlots.length > 0) {
                    state = APPLYING; clickIndex = 0; nextClick = 0;
                    message = "Applying shop layout...";
                }
            }
            return;
        }
        if (VIEWING.equals(localState) && targetUuid.length() > 0 && mc.thePlayer != null) {
            // A single command is emitted after UUID resolution.  Do not send
            // repeatedly if the server takes a long time to open the window.
            synchronized (LOCK) {
                if (state.equals(VIEWING)) {
                    state = "view-command-sent";
                    mc.thePlayer.sendChatMessage("/viewquickbuy " + targetUuid);
                }
            }
            return;
        }
        if (APPLYING.equals(localState) && mc.thePlayer != null && now >= nextClick) {
            // Frenchify's protocol clicks each occupied slot from the
            // `/viewquickbuy` container; keep the sequence bounded to the
            // immutable packet snapshot and never emulate keyboard/mouse input.
            if (clickIndex >= profileSlots.length) {
                if (mc.currentScreen != null) mc.displayGuiScreen(null);
                state = DONE; message = "Shop layout applied."; return;
            }
            int slot = profileSlots[clickIndex++];
            try {
                // Resolve by method shape so the obfuscated compatibility
                // runtime does not require a hard-linked descriptor.  The
                // final parameter must accept the actual launcher player type.
                Method click = null;
                for (Method candidate : mc.playerController.getClass().getMethods()) {
                    Class<?>[] p = candidate.getParameterTypes();
                    if (p.length == 5 && p[0] == Integer.TYPE && p[1] == Integer.TYPE
                            && p[2] == Integer.TYPE && p[3] == Integer.TYPE
                            && p[4].isInstance(mc.thePlayer)) { click = candidate; break; }
                }
                if (click == null) throw new NoSuchMethodException("windowClick");
                click.setAccessible(true);
                click.invoke(mc.playerController, sourceWindow >= 0 ? sourceWindow : currentWindow,
                    slot, 0, 0, mc.thePlayer);
                nextClick = now + CLICK_DELAY_MS;
            } catch (Exception failure) {
                fail("Shop layout could not be applied.");
            }
        }
    }

    /** Invoked by AdninPacketLog on Netty's event loop; no Minecraft access. */
    public static void onInboundPacket(Object packet) {
        if (!enabled || packet == null) return;
        try {
            String simple = packet.getClass().getName();
            if (simple.endsWith("S2DPacketOpenWindow")) {
                int id = intValue(packet, "getWindowId", "func_148901_c", "windowId", "a");
                String title = textValue(packet, "getWindowTitle", "func_146284_a", "windowTitle", "b");
                String type = textValue(packet, "getGuiId", "func_148902_e", "windowType", "c");
                boolean qb = isQuickBuy(title) || isQuickBuy(type);
                synchronized (LOCK) {
                    currentWindow = id; quickBuyWindow = qb;
                    if ((VIEWING.equals(state) || "view-command-sent".equals(state)) && qb) {
                        sourceWindow = id; sourceCaptured = false; state = VIEWING;
                    }
                }
            } else if (simple.endsWith("S30PacketWindowItems")) {
                int id = intValue(packet, "getWindowId", "func_148911_c", "windowId", "a");
                if (id == sourceWindow && (VIEWING.equals(state) || "view-command-sent".equals(state))) captureItems(packet);
            } else if (simple.endsWith("S2FPacketSetSlot")) {
                int id = intValue(packet, "getWindowId", "func_149175_c", "windowId", "a");
                if (id == sourceWindow && (VIEWING.equals(state) || "view-command-sent".equals(state))) captureSlot(packet);
            } else if (simple.endsWith("S2EPacketCloseWindow")) {
                int id = intValue(packet, "getWindowId", "func_148889_c", "windowId", "a");
                if (id == sourceWindow && sourceCaptured) synchronized (LOCK) {
                    // A close packet can arrive between individual clicks;
                    // let the client pump finish its bounded sequence rather
                    // than claiming success after the first slot.
                    if (!APPLYING.equals(state) && !DONE.equals(state)) {
                        state = FAILED; message = "Shop layout window closed.";
                    }
                }
            }
        } catch (Throwable ignored) { /* Packet observer must never interrupt Netty. */ }
    }

    private static void captureItems(Object packet) {
        Object list = value(packet, "getItemStacks", "func_148910_d", "itemStacks", "b");
        if (list == null) return;
        ArrayList<Integer> slots = new ArrayList<Integer>();
        int limit = 0;
        if (list instanceof List) limit = Math.min(MAX_SLOTS, ((List<?>)list).size());
        else if (list.getClass().isArray()) limit = Math.min(MAX_SLOTS, java.lang.reflect.Array.getLength(list));
        for (int i = 0; i < limit; i++) {
            Object item = list instanceof List ? ((List<?>)list).get(i) : java.lang.reflect.Array.get(list, i);
            if (item != null) {
                slots.add(i);
            }
        }
        synchronized (LOCK) {
            profileSlots = toArray(slots);
            sourceCaptured = true; sourceQuietUntil = System.currentTimeMillis() + 120L;
            if (profileSlots.length > 0) {
                // Let late S2F updates settle; tick() enters APPLYING after
                // sourceQuietUntil rather than racing the packet stream.
                message = "Shop layout received.";
            } else {
                message = "Shop layout is empty.";
            }
        }
    }

    private static void captureSlot(Object packet) {
        int slot = intValue(packet, "getSlot", "func_149173_d", "slot", "b");
        Object item = value(packet, "getItem", "func_149174_e", "itemStack", "c");
        if (slot < 0 || slot >= MAX_SLOTS) return;
        if (item != null) {
        }
        synchronized (LOCK) {
            ArrayList<Integer> slots = new ArrayList<Integer>();
            for (int old : profileSlots) slots.add(old);
            if (item == null) slots.remove(Integer.valueOf(slot));
            else if (!slots.contains(slot)) slots.add(slot);
            profileSlots = toArray(slots); sourceCaptured = true;
            sourceQuietUntil = System.currentTimeMillis() + 120L;
        }
    }

    /** Kept for integrations that expose the local editor explicitly. */
    public static boolean noticeLocalWindow(int windowId, String title) {
        if (!enabled || !READY.equals(state) || !isQuickBuy(title)) return false;
        synchronized (LOCK) { currentWindow = windowId; clickIndex = 0; nextClick = 0; state = APPLYING; message = "Applying shop layout..."; }
        return true;
    }

    public static void reset(String reason) {
        synchronized (LOCK) {
            state = IDLE; message = reason == null ? "" : reason; pendingName.set(null);
            profileSlots = new int[0]; sourceWindow = -1; currentWindow = -1;
            sourceCaptured = false; quickBuyWindow = false; clickIndex = 0; sourceQuietUntil = 0L;
        }
    }
    private static void fail(String text) { synchronized (LOCK) { state = FAILED; message = text; } }
    private static String cleanName(String value) {
        if (value == null) return ""; String s = value.trim();
        return s.matches("[A-Za-z0-9_]{1,16}") ? s : "";
    }
    private static boolean isQuickBuy(String value) {
        if (value == null) return false; String s = value.toLowerCase(Locale.ROOT);
        return s.contains("quick buy") || s.contains("quickbuy");
    }
    private static int[] toArray(List<Integer> values) { int[] out = new int[Math.min(MAX_SLOTS, values.size())]; for (int i=0;i<out.length;i++) out[i]=values.get(i); return out; }
    private static Object value(Object o, String... names) {
        for (String n : names) try { Method m=o.getClass().getMethod(n); m.setAccessible(true); return m.invoke(o); } catch (Exception ignored) { }
        for (String n : names) try { Field f=o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); } catch (Exception ignored) { }
        return null;
    }
    private static int intValue(Object o, String... names) { Object v=value(o,names); return v instanceof Number ? ((Number)v).intValue() : -1; }
    private static String textValue(Object o, String... names) {
        Object v=value(o,names); if (v == null) return "";
        try { Method m=v.getClass().getMethod("getUnformattedText"); m.setAccessible(true); return String.valueOf(m.invoke(v)); }
        catch (Exception ignored) { }
        try { Method m=v.getClass().getMethod("getUnformattedTextForChat"); m.setAccessible(true); return String.valueOf(m.invoke(v)); }
        catch (Exception ignored) { }
        return String.valueOf(v);
    }
}
