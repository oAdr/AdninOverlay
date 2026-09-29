import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Exercises the actual Gui4 module controls without a game, native payload, or IO.
 * Deliberately avoids initGui, mouseClicked/keyTyped (which request a saved config),
 * the client tick and sound playback. Real panel click/scroll handlers and the
 * interval renderer run with recorded graphics primitives instead of OpenGL.
 */
public final class AdninModuleIntegrationTest {
    private static final String[] LEGACY = {"anticheat", "ac_flagsound", "ac_autoblock",
        "ac_legitscaff", "ac_scaffold", "ac_scaffoldb", "ac_noslow"};
    private static final String[] OPTIONS = {"flagSound", "autoBlock", "noFall", "noSlow",
        "scaffold", "legitScaffold", "ignoreTeammates", "atlasOnly", "autoReport"};
    private static final int[] ROWS = {28, 76, 76, 104, 104, 132, 196, 224, 252};
    private static final String[] SETTINGS = {"enabled", "flagSound", "autoBlock", "noFall",
        "noSlow", "scaffold", "legitScaffold", "ignoreTeammates", "atlasOnly",
        "autoReport", "intervalSeconds", "ignoredPlayers"};
    private static final int X = 100, Y = 50, WIDTH = 300;
    private static int checks;
    private static Class<?> gui, anticheat, features, sounds;
    private static Object screen;
    private static Method acClicks, inputClicks, utilsClicks, getScroll, setScroll, getHeight, intervalButtons;
    private static Map<String, Object> legacySentinel;

    public static void main(String[] args) throws Exception {
        gui = Class.forName("AdninGui4");
        anticheat = Class.forName("AdninAnticheat");
        features = Class.forName("AdninFeatures");
        sounds = Class.forName("AdninClientSounds");
        screen = new RecordingGui();
        acClicks = method("handleAnticheatPanelClicks", ints(7));
        inputClicks = method("handleAnticheatInputClicks", ints(7));
        utilsClicks = method("handleUtilsPanelClicks", ints(7));
        getScroll = method("getPanelScroll", int.class);
        setScroll = method("setPanelScroll", int.class, int.class);
        getHeight = method("getPanelContentHeight", int.class);
        intervalButtons = method("drawAnticheatIntervalButtons", ints(7));
        Map<String, Object> originalLegacy = snapshot(gui, LEGACY);
        Map<String, Object> originalSettings = snapshot(anticheat, SETTINGS);
        Map<String, Object> originalUtils = snapshot(gui, "clientSideSounds", "botDenicker");
        try {
            checkOfflineState();
            check(!bool(anticheat, "enabled"), "new AntiCheat defaults disabled");
            check(!bool(anticheat, "autoReport"), "automatic report defaults disabled");
            check(!bool(gui, "clientSideSounds"), "client side sounds defaults disabled");
            for (int i = 0; i < LEGACY.length; i++) {
                Field field = field(gui, LEGACY[i]);
                int modifiers = field.getModifiers();
                check(field.getType() == boolean.class && Modifier.isPublic(modifiers)
                        && Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers),
                        "native ABI retains public writable static boolean " + LEGACY[i]);
                field.setBoolean(null, (i & 1) == 0);
            }
            legacySentinel = snapshot(gui, LEGACY);
            testAnticheatSwitchAndOptions();
            testDisabledControls();
            testInterval();
            testIntervalDrawing();
            testIgnoredInput();
            testClipping();
            testScrollAndSounds();
            testSettingsRoundTrip();
            unchanged(legacySentinel, gui, "all new module interactions preserve legacy JNI fields");
            checkOfflineState();
            System.out.println("AdninModuleIntegrationTest: " + checks
                + " checks passed; actual Gui4 AC options, disabled controls, interval limits,"
                + " actual +/- drawing/hover, scroll/clipping, sounds, settings and legacy ABI; no game, OpenGL, native code or IO");
        } finally {
            restore(gui, originalLegacy);
            restore(gui, originalUtils);
            restore(anticheat, originalSettings);
        }
    }

    private static void testAnticheatSwitchAndOptions() throws Exception {
        set(anticheat, "enabled", false);
        int mainY = constant("AC_MAIN_Y");
        check(click(acClicks, Y, 0, 1000, X + WIDTH - 20, Y + mainY + 11), "main switch consumes click");
        check(bool(anticheat, "enabled"), "main switch enables new AntiCheat");
        check(!click(acClicks, Y, 0, 1000, X + 10, Y + mainY + 11), "main card text is not a switch");
        for (int i = 0; i < OPTIONS.length; i++) {
            set(anticheat, OPTIONS[i], false);
            Map<String, Object> before = snapshot(anticheat, SETTINGS);
            check(click(acClicks, Y, 0, 1000, optionX(i), Y + ROWS[i] + 11), OPTIONS[i] + " consumes click");
            before.put(OPTIONS[i], true);
            unchanged(before, anticheat, OPTIONS[i] + " changes only its setting");
            check(click(acClicks, Y, 0, 1000, optionX(i), Y + ROWS[i] + 11), OPTIONS[i] + " second click consumed");
            check(!bool(anticheat, OPTIONS[i]), OPTIONS[i] + " can be disabled again");
            unchanged(legacySentinel, gui, OPTIONS[i] + " does not write old native AC fields");
        }
        check(!click(acClicks, Y, 0, 1000, X - 1, Y + 80), "outside horizontal card boundary is ignored");
        check(!click(acClicks, Y, 0, 1000, X + WIDTH / 2, Y + 178), "gap between AC sections is ignored");
        Map<String, Object> after = snapshot(anticheat, SETTINGS);
        check(!click(acClicks, Y, 0, 1000, X + 14, Y + 291), "old fourth alert row is now a non-interactive gap");
        unchanged(after, anticheat, "removed row leaves no hidden setting hitbox");
    }

    private static void testDisabledControls() throws Exception {
        set(anticheat, "enabled", false);
        Map<String, Object> before = snapshot(anticheat, SETTINGS);
        for (int i = 0; i < OPTIONS.length; i++)
            check(!click(acClicks, Y, 0, 1000, optionX(i), Y + ROWS[i] + 11),
                "disabled module ignores " + OPTIONS[i]);
        check(!click(acClicks, Y, 0, 1000, X + WIDTH - 45, intervalCenter(Y)), "disabled interval decrement ignored");
        check(!click(acClicks, Y, 0, 1000, X + WIDTH - 17, intervalCenter(Y)), "disabled interval increment ignored");
        unchanged(before, anticheat, "disabled options leave all new settings unchanged");
    }

    private static void testInterval() throws Exception {
        set(anticheat, "enabled", true);
        set(anticheat, "intervalSeconds", 0);
        check(!click(acClicks, Y, 0, 1000, X + WIDTH - 45, intervalCenter(Y)), "minus is disabled at zero");
        eq(0, get(anticheat, "intervalSeconds"), "interval does not fall below zero");
        check(click(acClicks, Y, 0, 1000, X + WIDTH - 17, intervalCenter(Y)), "zero interval plus consumes click");
        eq(1, get(anticheat, "intervalSeconds"), "interval increments by one second");
        set(anticheat, "intervalSeconds", 60);
        check(!click(acClicks, Y, 0, 1000, X + WIDTH - 17, intervalCenter(Y)), "plus is disabled at maximum interval");
        eq(60, get(anticheat, "intervalSeconds"), "interval does not exceed sixty seconds");
        check(click(acClicks, Y, 0, 1000, X + WIDTH - 45, intervalCenter(Y)), "maximum interval minus consumes click");
        eq(59, get(anticheat, "intervalSeconds"), "interval decrements by one second");
        check(!click(acClicks, Y, 0, 1000, X + WIDTH - 34, intervalCenter(Y)), "right edge is outside the decrement action");
        check(!click(acClicks, Y, 0, 1000, X + WIDTH - 6, intervalCenter(Y)), "right edge is outside the increment action");
        check(!click(acClicks, Y, 0, 1000, X + WIDTH - 17, Y + constant("AC_INTERVAL_Y") + 22), "bottom edge is outside the interval action");
    }

    private static void testIntervalDrawing() throws Exception {
        RecordingGui recorder = (RecordingGui) screen;
        int row = Y + constant("AC_INTERVAL_Y");
        set(anticheat, "enabled", true);
        set(anticheat, "intervalSeconds", 20);
        renderInterval(Y, 0, 1000, -1, -1);
        eq(2, recorder.labels.size(), "real interval drawing produces exactly two labels, with no ON/OFF adornment");
        label(recorder.labels.get(0), "-", X + WIDTH - 45, row + 7);
        label(recorder.labels.get(1), "+", X + WIDTH - 17, row + 7);
        int normal = recorder.rects.get(1).color;
        int activeColor = recorder.labels.get(0).color;
        eq(activeColor, recorder.labels.get(1).color, "both ordinary interval actions have the same enabled style");
        eq(4, recorder.rects.size(), "two action rectangles use a border and fill each");
        Rect minus = recorder.rects.get(0), plus = recorder.rects.get(2);
        check(minus.left == X + WIDTH - 56 && minus.right - minus.left == 22
            && minus.top == row && minus.bottom - minus.top == 22, "decrement drawing matches its click rectangle");
        check(plus.left == X + WIDTH - 28 && plus.top == minus.top && plus.bottom == minus.bottom,
            "actions align horizontally with a six-pixel gap");
        renderInterval(Y, 0, 1000, X + WIDTH - 45, row + 11);
        check(recorder.rects.get(1).color != normal, "enabled decrement responds to hover");
        eq(normal, recorder.rects.get(3).color, "hover only affects the targeted action");
        renderInterval(Y, 0, 1000, X + WIDTH - 17, row + 11);
        check(recorder.rects.get(3).color != normal, "enabled increment responds to hover");

        set(anticheat, "intervalSeconds", 0);
        renderInterval(Y, 0, 1000, -1, -1);
        int disabledColor = recorder.labels.get(0).color;
        int disabledBackground = recorder.rects.get(1).color;
        check(disabledColor != activeColor, "lower-bound action is visibly disabled");
        eq(activeColor, recorder.labels.get(1).color, "increment remains enabled at zero");
        renderInterval(Y, 0, 1000, X + WIDTH - 45, row + 11);
        eq(disabledBackground, recorder.rects.get(1).color, "disabled lower-bound action has no hover effect");
        set(anticheat, "intervalSeconds", 60);
        renderInterval(Y, 0, 1000, X + WIDTH - 17, row + 11);
        eq(activeColor, recorder.labels.get(0).color, "decrement remains enabled at sixty");
        eq(disabledColor, recorder.labels.get(1).color, "upper-bound action is visibly disabled");
        eq(disabledBackground, recorder.rects.get(3).color, "disabled upper-bound action has no hover effect");
        set(anticheat, "enabled", false);
        set(anticheat, "intervalSeconds", 20);
        renderInterval(Y, 0, 1000, X + WIDTH - 17, row + 11);
        for (Label drawn : recorder.labels) eq(disabledColor, drawn.color, "module-disabled actions are visibly muted");
        eq(disabledBackground, recorder.rects.get(3).color, "module-disabled action has no hover effect");
        set(anticheat, "enabled", true);

        renderInterval(Y, row + 22, row + 100, X + WIDTH - 17, row + 11);
        check(recorder.labels.isEmpty() && recorder.rects.isEmpty(), "fully clipped interval actions draw nothing");
        renderInterval(Y, row - 100, row, X + WIDTH - 17, row + 11);
        check(recorder.labels.isEmpty() && recorder.rects.isEmpty(), "actions below viewport draw nothing");
        renderInterval(Y, row + 9, row + 22, X + WIDTH - 17, row + 5);
        check(recorder.labels.isEmpty(), "partially hidden glyphs are not drawn across the top boundary");
        for (Rect drawn : recorder.rects) check(drawn.top >= row + 9 && drawn.bottom <= row + 22,
            "top-clipped action backgrounds stay inside viewport");
        eq(normal, recorder.rects.get(3).color, "hidden pointer position cannot light the action");
        check(!click(acClicks, Y, row + 9, row + 22, X + WIDTH - 17, row + 5), "hidden action segment ignores click");
        check(click(acClicks, Y, row + 9, row + 22, X + WIDTH - 17, row + 11), "visible clipped segment remains clickable");
        renderInterval(Y, row, row + 12, X + WIDTH - 17, row + 11);
        check(recorder.labels.isEmpty(), "partially hidden glyphs are not drawn across the bottom boundary");
        for (Rect drawn : recorder.rects) check(drawn.top >= row && drawn.bottom <= row + 12,
            "bottom-clipped backgrounds stay inside viewport");
    }

    private static void testClipping() throws Exception {
        int top = 100, bottom = 250;
        set(anticheat, "enabled", true);
        set(anticheat, "flagSound", false);
        check(!click(acClicks, top - 28 - 30, top, bottom, X + 15, top - 19), "fully hidden AC chip above viewport ignored");
        check(!click(acClicks, bottom - 28 + 1, top, bottom, X + 15, bottom + 12), "fully hidden AC chip below viewport ignored");
        int partialTop = top - 28 - 7;
        check(!click(acClicks, partialTop, top, bottom, X + 15, top - 1), "hidden part of partially clipped AC chip ignored");
        check(!bool(anticheat, "flagSound"), "clipped chip did not mutate setting");
        check(click(acClicks, partialTop, top, bottom, X + 15, top + 3), "visible part of top-clipped AC chip works");
        check(bool(anticheat, "flagSound"), "visible top-clipped chip toggles setting");
        int partialBottom = bottom - 28 - 10;
        check(!click(acClicks, partialBottom, top, bottom, X + 15, bottom + 1), "hidden part below viewport ignored");
        check(click(acClicks, partialBottom, top, bottom, X + 15, bottom - 3), "visible part of bottom-clipped AC chip works");
        int mainY = constant("AC_MAIN_Y");
        int clippedSwitch = top - mainY - 10;
        check(!click(acClicks, clippedSwitch, top, bottom, X + WIDTH - 20, top - 1), "hidden portion of main switch ignored");
        check(bool(anticheat, "enabled"), "clipped main switch leaves module enabled");
        check(click(acClicks, clippedSwitch, top, bottom, X + WIDTH - 20, top + 2), "visible main switch works after scrolling");
        check(!bool(anticheat, "enabled"), "visible main switch disables new module");
    }

    private static void testIgnoredInput() throws Exception {
        int y = Y + constant("AC_IGNORED_INPUT_Y");
        set(anticheat, "ignoredPlayers", "Alice,Bob");
        check(click(inputClicks, Y, 0, 1000, X + 15, y + 10), "relocated ignored-players field receives focus");
        eq(10, field(gui, "activeInput").get(screen), "new input hitbox selects ignored-player editing");
        check(!click(inputClicks, Y, 0, 1000, X + 15, Y + 400), "old input position is no longer interactive");
        eq(0, field(gui, "activeInput").get(screen), "click outside the new field clears focus");
        check(!click(inputClicks, Y, y + 8, y + 20, X + WIDTH - 14, y + 5), "hidden portion of clear button ignores clicks");
        eq("Alice,Bob", get(anticheat, "ignoredPlayers"), "clipped clear button preserves input");
        check(click(inputClicks, Y, y + 8, y + 20, X + 15, y + 10), "visible part of clipped input receives focus");
        check(click(inputClicks, Y, y + 8, y + 20, X + WIDTH - 14, y + 10), "visible part of relocated clear button works");
        eq("", get(anticheat, "ignoredPlayers"), "clear button only resets ignored-player text");
        eq(0, field(gui, "activeInput").get(screen), "clear button removes input focus");
    }

    private static void testScrollAndSounds() throws Exception {
        field(gui, "winH").setInt(screen, 285);
        eq(56, constant("HEADER_H"), "modern window reserves the real title header height");
        eq(16, constant("FOOTER_H"), "modern window reserves its footer height");
        eq(16, constant("PANEL_INSET"), "modern panels use the shared inset");
        int viewport = 285 - constant("HEADER_H") - constant("FOOTER_H") - constant("PANEL_INSET") * 2;
        eq(constant("AC_CONTENT_H"), getHeight.invoke(screen, 1), "AC panel advertises complete new content height");
        eq(390, getHeight.invoke(screen, 1), "removed alert row and watermark no longer reserve scroll height");
        eq(362, constant("AC_IGNORED_INPUT_Y"), "ignored players input moves up with compacted panel");
        eq(300, constant("AC_INTERVAL_Y"), "interval actions move up after removing enemy option");
        check((Integer) getHeight.invoke(screen, 1) > constant("AC_IGNORED_INPUT_Y") + 20,
            "ignored players input lies within AC scroll content");
        check((Integer) getHeight.invoke(screen, 2) >= constant("UTILS_SOUND_TOGGLE_Y") + 22,
            "Utils scroll content includes entire sounds switch");
        for (int panel : new int[]{1, 2}) {
            int content = (Integer) getHeight.invoke(screen, panel);
            setScroll.invoke(screen, panel, 1000);
            eq(0, getScroll.invoke(screen, panel), "panel " + panel + " scroll cannot exceed top");
            setScroll.invoke(screen, panel, -1000000);
            eq(Math.min(0, viewport - 5 - content), getScroll.invoke(screen, panel), "panel " + panel + " scroll includes its five-pixel content origin");
            setScroll.invoke(screen, panel, -18);
            eq(-18, getScroll.invoke(screen, panel), "panel " + panel + " preserves ordinary scroll step");
        }
        setScroll.invoke(screen, 1, -1000000);
        int acScroll = (Integer) getScroll.invoke(screen, 1);
        int top = 35, base = top + 5, bottom = top + viewport;
        set(anticheat, "enabled", true);
        set(anticheat, "intervalSeconds", 20);
        renderInterval(base + acScroll, top, bottom, -1, -1);
        RecordingGui recorder = (RecordingGui) screen;
        eq(2, recorder.labels.size(), "both interval labels draw at bottom scroll");
        label(recorder.labels.get(1), "+", X + WIDTH - 17, base + acScroll + constant("AC_INTERVAL_Y") + 7);
        for (Rect drawn : recorder.rects) check(drawn.top >= top && drawn.bottom <= bottom,
            "scrolled interval drawing remains inside viewport");
        check(click(acClicks, base + acScroll, top, bottom, X + WIDTH - 17, intervalCenter(base + acScroll)),
            "interval remains reachable at AC bottom scroll");
        eq(21, get(anticheat, "intervalSeconds"), "scrolled interval clicks translated row");
        int inputY = base + acScroll + constant("AC_IGNORED_INPUT_Y");
        check(inputY >= top && inputY + 20 < bottom, "entire compacted input remains reachable at bottom scroll");
        check(click(inputClicks, base + acScroll, top, bottom, X + 15, inputY + 10), "scrolled input hitbox follows compacted layout");
        eq(10, field(gui, "activeInput").get(screen), "scrolled input focuses ignored-player editing");

        set(gui, "clientSideSounds", false);
        Map<String, Object> acBefore = snapshot(anticheat, SETTINGS);
        Map<String, Object> utilsBefore = snapshot(gui, "clientSideSounds", "botDenicker");
        int soundY = constant("UTILS_SOUND_TOGGLE_Y");
        check(!click(utilsClicks, base, top, bottom, X + 15, base + soundY + 11), "unscrolled hidden sounds toggle ignored");
        setScroll.invoke(screen, 2, -1000000);
        int utilsScroll = (Integer) getScroll.invoke(screen, 2);
        check(base + utilsScroll + soundY >= top && base + utilsScroll + soundY + 22 < bottom,
            "whole sounds toggle reachable inside viewport at bottom scroll");
        check(click(utilsClicks, base + utilsScroll, top, bottom, X + 15, base + utilsScroll + soundY + 11),
            "scrolled sounds toggle consumes click");
        check(bool(gui, "clientSideSounds"), "scrolled sounds toggle enables sounds");
        utilsBefore.put("clientSideSounds", true);
        unchanged(utilsBefore, gui, "sounds toggle leaves Bot Denicker unchanged");
        unchanged(acBefore, anticheat, "sounds toggle leaves every AC setting unchanged");
        check(click(utilsClicks, base + utilsScroll, top, bottom, X + 15, base + utilsScroll + soundY + 11),
            "second sounds click consumed");
        check(!bool(gui, "clientSideSounds"), "sounds toggle can be disabled");
        int partial = top - soundY - 7;
        check(!click(utilsClicks, partial, top, bottom, X + 15, top - 1), "hidden part of partially clipped sounds chip ignored");
        check(click(utilsClicks, partial, top, bottom, X + 15, top + 3), "visible part of partially clipped sounds chip works");
        set(gui, "clientSideSounds", false);
        set(gui, "botDenicker", false);
        int botY = constant("UTILS_BOT_TOGGLE_Y");
        check(click(utilsClicks, base + utilsScroll, top, bottom, X + 15, base + utilsScroll + botY + 11),
            "neighboring Bot Denicker remains reachable after panel expansion");
        check(bool(gui, "botDenicker") && !bool(gui, "clientSideSounds"), "Bot Denicker and sounds stay independent");
        field(gui, "winH").setInt(screen, 1000);
        for (int panel : new int[]{1, 2}) {
            setScroll.invoke(screen, panel, -1000000);
            eq(0, getScroll.invoke(screen, panel), "panel " + panel + " fits tall window without negative scroll");
        }
    }

    private static void testSettingsRoundTrip() throws Exception {
        // Exercise only the adapter's in-memory Properties API. No config path is used.
        for (int i = 0; i < SETTINGS.length; i++) {
            String name = SETTINGS[i];
            if (field(anticheat, name).getType() == boolean.class) set(anticheat, name, (i & 1) == 0);
        }
        set(anticheat, "intervalSeconds", 37);
        set(anticheat, "ignoredPlayers", "Alice,Bob");
        Properties saved = new Properties();
        anticheat.getMethod("saveSettings", Properties.class).invoke(null, saved);
        eq("37", saved.getProperty("anticheat.intervalSeconds"), "new interval persists under its namespace");
        check(saved.getProperty("anticheat.ignoredPlayers").toLowerCase(java.util.Locale.ROOT).contains("alice"),
            "ignored players option persists without game initialization");
        for (String name : SETTINGS) {
            if (field(anticheat, name).getType() == boolean.class)
                eq(String.valueOf(get(anticheat, name)), saved.getProperty("anticheat." + name), name + " persists independently");
        }
        anticheat.getMethod("loadSettings", Properties.class).invoke(null, saved);
        Properties again = new Properties();
        anticheat.getMethod("saveSettings", Properties.class).invoke(null, again);
        eq(saved, again, "every new AC option survives in-memory save/load round trip");
        unchanged(legacySentinel, gui, "new settings persistence never mutates old ABI fields");
    }

    private static void checkOfflineState() throws Exception {
        check(!bool(features, "initialized"), "feature worker remains uninitialized");
        check(get(features, "settingsPath") == null, "no settings file path is read or created");
        check(get(features, "world") == null, "feature module has no game world");
        check(get(anticheat, "world") == null && get(anticheat, "localPlayer") == null,
            "AntiCheat has no world or player");
        check(get(sounds, "active") == null, "no sound or Netty session starts");
    }

    private static int optionX(int index) {
        return index == 2 || index == 4 ? X + 8 + (WIDTH - 12) / 2 + 10 : X + 14;
    }
    private static int intervalCenter(int baseY) throws Exception { return baseY + constant("AC_INTERVAL_Y") + 11; }
    private static void renderInterval(int baseY, int top, int bottom, int mouseX, int mouseY) throws Exception {
        RecordingGui recorder = (RecordingGui) screen;
        recorder.labels.clear(); recorder.rects.clear();
        intervalButtons.invoke(screen, X, baseY, WIDTH, top, bottom, mouseX, mouseY);
    }
    private static void label(Label actual, String expected, int centerX, int y) {
        eq(expected, actual.text, "drawn interval action label");
        check(actual.centerX == centerX && actual.y == y, "action glyph is centered inside the actual click rectangle");
    }
    private static final class Rect {
        final int left, top, right, bottom, color;
        Rect(int left, int top, int right, int bottom, int color) {
            this.left=left; this.top=top; this.right=right; this.bottom=bottom; this.color=color;
        }
    }
    private static final class Label {
        final String text;
        final int centerX, y, color;
        Label(String text, int centerX, int y, int color) { this.text=text; this.centerX=centerX; this.y=y; this.color=color; }
    }
    private static final class RecordingGui extends AdninGui4 {
        final List<Rect> rects = new ArrayList<Rect>();
        final List<Label> labels = new ArrayList<Label>();
        @Override protected void drawIntervalActionRect(int left, int top, int right, int bottom, int color) {
            rects.add(new Rect(left, top, right, bottom, color));
        }
        @Override protected void drawIntervalActionLabel(String label, int centerX, int y, int color) {
            labels.add(new Label(label, centerX, y, color));
        }
    }
    private static boolean click(Method handler, int baseY, int top, int bottom, int mouseX, int mouseY) throws Exception {
        return (Boolean) handler.invoke(screen, X, baseY, WIDTH, top, bottom, mouseX, mouseY);
    }
    private static Class<?>[] ints(int count) { Class<?>[] types = new Class<?>[count]; java.util.Arrays.fill(types, int.class); return types; }
    private static Method method(String name, Class<?>... types) throws Exception { Method m = gui.getDeclaredMethod(name, types); m.setAccessible(true); return m; }
    private static Field field(Class<?> owner, String name) throws Exception { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f; }
    private static Object get(Class<?> owner, String name) throws Exception { return field(owner, name).get(null); }
    private static void set(Class<?> owner, String name, Object value) throws Exception { field(owner, name).set(null, value); }
    private static boolean bool(Class<?> owner, String name) throws Exception { return (Boolean) get(owner, name); }
    private static int constant(String name) throws Exception { return (Integer) get(gui, name); }
    private static Map<String, Object> snapshot(Class<?> owner, String... names) throws Exception {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (String name : names) values.put(name, get(owner, name));
        return values;
    }
    private static void restore(Class<?> owner, Map<String, Object> values) throws Exception {
        for (Map.Entry<String, Object> item : values.entrySet()) set(owner, item.getKey(), item.getValue());
    }
    private static void unchanged(Map<String, Object> expected, Class<?> owner, String reason) throws Exception {
        eq(expected, snapshot(owner, expected.keySet().toArray(new String[0])), reason);
    }
    private static void eq(Object expected, Object actual, String reason) { check(expected.equals(actual), reason + ": expected " + expected + ", actual " + actual); }
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
}
