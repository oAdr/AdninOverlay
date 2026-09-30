import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/** Actual scaled Gui4 handlers with owned in-memory save state; no initGui or GL. */
public final class AdninUiInteractionTest {
    private static int checks;
    private static final Class<?> GUI = AdninGui4.class;
    private static final Class<?> FEATURES = AdninFeatures.class;
    private static final String[] GUI_FIELDS = {"api_hypixel", "api_seraph", "api_aurora", "api_urchin",
        "vegaProxy", "listeningHoldRdKey", "holdRdKeyCode", "listeningQuickbuyIndex",
        "overlayGamemodeEdit", "overlayColumnsBedwars", "overlayColumnsSkywars", "overlayColumnsDuel",
        "overlayColumnsBedwarsduels", "overlayColumnEnabled", "overlayColumnOrder", "uiScalePercent",
        "chatOutput", "chatOutputDenick", "chatOutputTags", "chatOutputTagsSelf", "chatOutputTagsTeammates",
        "chatOutputAnticheat", "chatOverlay", "chatOverlayMinStars", "chatOverlayMinFkdr", "chatOverlayMinSwKdr"};
    private static final Method LAYOUT = method("layoutForViewport");
    private static final Method CLICK = method("mouseClicked", int.class, int.class, int.class);
    private static final Method KEY = method("keyTyped", char.class, int.class);
    private static final Method SET_SCROLL = method("setPanelScroll", int.class, int.class);
    private static final Method GET_SCROLL = method("getPanelScroll", int.class);
    private static final Method HEIGHT = method("getPanelContentHeight", int.class);
    private static final Method CLAMP = method("clampScroll", int.class, int.class, int.class);
    private static AtomicReference<Properties> saves;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String, Object> original = snapshot(GUI, GUI_FIELDS);
        boolean initialized = field(FEATURES, "initialized").getBoolean(null);
        long saveAt = field(FEATURES, "saveAt").getLong(null);
        boolean acEnabled = AdninAnticheat.enabled;
        String ignored = AdninAnticheat.ignoredPlayers;
        String language = AdninLanguage.getLanguage();
        saves = (AtomicReference<Properties>) field(FEATURES, "pendingSave").get(null);
        Properties pending = saves.get();
        check(!initialized && field(FEATURES, "settingsPath").get(null) == null,
            "isolated fixture starts without an API worker or settings path");
        // Successful production clicks still request saves, but this owned state
        // prevents ensureInitialized from consulting Minecraft or starting a daemon.
        field(FEATURES, "initialized").setBoolean(null, true);
        try {
            AdninGui4.uiScalePercent = 100;
            AdninLanguage.setLanguage("en");
            for (int[] size : new int[][]{{800, 560}, {320, 180}}) {
                AdninGui4 screen = new AdninGui4();
                screen.width = size[0]; screen.height = size[1];
                LAYOUT.invoke(screen);
                layout(screen, size[0], size[1]);
                scaledPanelActions(screen);
                clippedActions(screen);
                bottomReach(screen);
                overlayHeaderClipping(screen);
                apiControls(screen);
                outputControls(screen);
                closing(screen);
            }
            uiScaleAndLanguage();
            check(field(FEATURES, "settingsPath").get(null) == null,
                "actual GUI interactions never acquire a personal settings path");
            check(field(FEATURES, "world").get(null) == null,
                "actual GUI interactions never acquire a game world");
            check(((java.util.Collection<?>) field(FEATURES, "requests").get(null)).isEmpty(),
                "actual GUI interactions never queue API work");
            System.out.println("AdninUiInteractionTest: " + checks
                + " checks passed; real layout and transformed click handlers, clipping, scroll reach,"
                + " API clearing, close hitbox and closing input freeze; no settings, network, game, GL or native payload");
        } finally {
            restore(GUI, original);
            AdninAnticheat.enabled = acEnabled; AdninAnticheat.ignoredPlayers = ignored;
            AdninLanguage.setLanguage(language);
            saves.set(pending);
            field(FEATURES, "saveAt").setLong(null, saveAt);
            field(FEATURES, "initialized").setBoolean(null, initialized);
        }
    }

    private static void layout(AdninGui4 screen, int width, int height) throws Exception {
        eq(620, screen.winW, "shared logical window width remains stable across screen sizes");
        eq(428, screen.winH, "shared logical window height remains stable across screen sizes");
        float scale = scale(screen);
        check(scale > 0 && scale <= 1, "window uses a positive bounded scale");
        if (width == 800) {
            check(scale == 1, "large viewport preserves normal UI scale");
            eq(90, screen.winX, "normal layout centers horizontally");
            eq(66, screen.winY, "normal layout centers vertically");
        } else {
            check(Math.abs(scale - 160f / 428f) < 0.00001f, "small viewport scales to available height");
            check(scale < 0.5f, "small viewport exercises real fractional click transforms");
        }
        check(screen.winX * scale >= 9 && screen.winY * scale >= 9,
            "window leaves visible top and left margins");
        check((screen.winX + screen.winW) * scale <= width - 9
            && (screen.winY + screen.winH) * scale <= height - 9,
            "complete window remains inside the small or large viewport");
        check(Math.abs((screen.winX + screen.winW / 2f) * scale - width / 2f) <= 0.6f,
            "scaled window is horizontally centered after rounding");
        check(Math.abs((screen.winY + screen.winH / 2f) * scale - height / 2f) <= 0.6f,
            "scaled window is vertically centered after rounding");
        for (int panel = 0; panel < 7; panel++) {
            int targetY = top(screen) + panel * 34 + 15;
            check(targetY < bottom(screen), "every sidebar row fits inside its clipped viewport");
            click(screen, screen.winX + constant("PANEL_INSET") + 40, targetY, 0);
            eq(panel, screen.selectedTheme, "scaled sidebar click chooses its actual panel");
        }
    }

    private static void outputControls(AdninGui4 screen) throws Exception {
        screen.selectedTheme = 4;
        field(GUI, "drawnTheme").setInt(screen, -1);
        SET_SCROLL.invoke(screen, 4, 0);
        AdninGui4.chatOutput = false; AdninGui4.chatOutputDenick = false;
        AdninGui4.chatOutputTags = false; AdninGui4.chatOutputAnticheat = false;
        AdninGui4.chatOutputTagsSelf = true; AdninGui4.chatOutputTagsTeammates = true;
        AdninGui4.chatOverlay = false;
        int x = contentX(screen), width = panelWidth(screen), base = top(screen) + 5;
        int card = base + constant("CHAT_MAIN_Y");
        int subY = card + constant("CHAT_OUTPUT_TAG_FILTERS_Y") + 11;
        int filterWidth = (width - 32) / 2;
        int selfX = x + 20 + filterWidth / 2, teamX = x + 24 + filterWidth + filterWidth / 2;
        saves.set(null);
        click(screen, selfX, subY, 0); click(screen, teamX, subY, 0);
        check(AdninGui4.chatOutputTagsSelf && AdninGui4.chatOutputTagsTeammates,
            "disabled tag filters retain their preferences");
        check(saves.get() == null, "disabled tag filter clicks neither save nor invalidate output");
        click(screen, x + 40, card + constant("CHAT_OUTPUT_DENICK_Y") + 11, 0);
        check(AdninGui4.chatOutputDenick && !AdninGui4.chatOutput && !AdninGui4.chatOutputTags
            && !AdninGui4.chatOutputAnticheat, "Nick/Denick toggles independently at actual scaled coordinates");
        check(AdninGui4.chatOverlay, "enabling Nick/Denick enables its local source");
        eq("true", saves.get().getProperty("chat.output.denick"), "Nick/Denick reaches production settings save");
        click(screen, x + 40, card + constant("CHAT_OUTPUT_PLAYERS_Y") + 11, 0);
        check(AdninGui4.chatOutput && AdninGui4.chatOutputDenick, "player output preserves independent denick output");
        click(screen, x + 40, card + constant("CHAT_OUTPUT_TAGS_Y") + 11, 0);
        check(AdninGui4.chatOutputTags, "tag output enables at its expanded row position");
        click(screen, selfX, subY, 0);
        check(!AdninGui4.chatOutputTagsSelf && AdninGui4.chatOutputTagsTeammates,
            "Self tag filter only changes itself");
        eq("false", saves.get().getProperty("chat.output.tags.self"), "Self filter reaches production save");
        click(screen, teamX, subY, 0);
        check(!AdninGui4.chatOutputTagsSelf && !AdninGui4.chatOutputTagsTeammates,
            "Teammates tag filter only changes itself");
        eq("false", saves.get().getProperty("chat.output.tags.teammates"), "Teammates filter reaches production save");
        click(screen, x + 40, card + constant("CHAT_OUTPUT_ANTICHEAT_Y") + 11, 0);
        check(AdninGui4.chatOutputAnticheat && AdninGui4.chatOutput && AdninGui4.chatOutputDenick
            && AdninGui4.chatOutputTags, "all four output categories enable independently");
        click(screen, x + 40, card + constant("CHAT_OUTPUT_TAGS_Y") + 11, 0);
        Properties saved = saves.get();
        click(screen, selfX, subY, 0); click(screen, teamX, subY, 0);
        check(!AdninGui4.chatOutputTagsSelf && !AdninGui4.chatOutputTagsTeammates && saved == saves.get(),
            "disabled tag filters preserve explicit off preferences as well");
        click(screen, x + 40, card + constant("CHAT_OUTPUT_TAGS_Y") + 11, 0);
        check(!AdninGui4.chatOutputTagsSelf && !AdninGui4.chatOutputTagsTeammates,
            "reenabling tag output does not silently reset child filters");
        for (String language : new String[]{"zh_CN", "zh_TW"}) {
            AdninLanguage.setLanguage(language);
            for (String label : new String[]{"Output: Nick / Denick", "Include Self", "Include Teammates"})
                check(!label.equals(AdninLanguage.text(label)), "new output label is localized in " + language);
        }
        AdninLanguage.setLanguage("en");
        int cardBottom = constant("CHAT_MAIN_Y") + constant("CHAT_MAIN_H");
        check(cardBottom < constant("CHAT_THRESH_Y") - 12, "expanded outputs never overlap Thresholds title");
        int content = (Integer) HEIGHT.invoke(screen, 4);
        int scroll = (Integer) CLAMP.invoke(screen, -10000, content, viewport(screen) - 5);
        SET_SCROLL.invoke(screen, 4, scroll);
        int lastInputY = top(screen) + 5 + scroll + constant("CHAT_THRESH_Y") + 8 + 96;
        click(screen, x + 14, lastInputY + 10, 0);
        eq(7, field(GUI, "activeInput").getInt(screen), "last threshold remains reachable after output card expansion");
        AdninGui4.chatOverlayMinSwKdr = "3.2";
        click(screen, x + width - 14, lastInputY + 10, 0);
        eq("", AdninGui4.chatOverlayMinSwKdr, "expanded bottom threshold clear button remains aligned");
    }

    private static void uiScaleAndLanguage() throws Exception {
        Properties settings = new Properties();
        AdninGui4.api_hypixel = "test-key";
        AdninGui4.uiScalePercent = 140;
        AdninGui4.saveUiSettings(settings);
        eq("140", settings.getProperty("ui.scalePercent"), "scale preference saves its visible percentage");
        AdninGui4.uiScalePercent = 70;
        AdninGui4.loadUiSettings(settings);
        eq(140, AdninGui4.uiScalePercent, "scale survives a settings roundtrip");
        for (String invalid : new String[]{"NaN", "100.1", ""}) {
            settings.setProperty("ui.scalePercent", invalid);
            AdninGui4.loadUiSettings(settings);
            eq(100, AdninGui4.uiScalePercent, "malformed preference returns to the safe default");
        }
        settings.setProperty("ui.scalePercent", "9999"); AdninGui4.loadUiSettings(settings);
        eq(140, AdninGui4.uiScalePercent, "oversized preference clamps");
        settings.setProperty("ui.scalePercent", "-3"); AdninGui4.loadUiSettings(settings);
        eq(70, AdninGui4.uiScalePercent, "negative preference clamps");
        eq("test-key", AdninGui4.api_hypixel, "loading UI preferences does not overwrite existing credentials");
        for (int[] size : new int[][]{{1280, 800}, {800, 560}, {320, 180}}) {
            for (int percent : new int[]{70, 100, 140}) {
                AdninGui4.uiScalePercent = percent;
                AdninGui4 screen = new AdninGui4(); screen.width = size[0]; screen.height = size[1];
                LAYOUT.invoke(screen);
                float scale = scale(screen);
                check(screen.winX * scale >= 9 && screen.winY * scale >= 9
                    && (screen.winX + screen.winW) * scale <= size[0] - 9
                    && (screen.winY + screen.winH) * scale <= size[1] - 9,
                    "70/100/140 percent windows fit every viewport");
                scaledPanelActions(screen);
                clippedActions(screen);
                bottomReach(screen);
                overlayHeaderClipping(screen);
                apiControls(screen);
                outputControls(screen);
            }
            AdninGui4.uiScalePercent = 100;
            AdninGui4 screen = new AdninGui4(); screen.width = size[0]; screen.height = size[1];
            LAYOUT.invoke(screen); screen.selectedTheme = 0;
            int scroll = (Integer)CLAMP.invoke(screen, -10000, HEIGHT.invoke(screen, 0), viewport(screen) - 5);
            SET_SCROLL.invoke(screen, 0, scroll);
            int trackY = top(screen) + 5 + scroll + constant("UI_SCALE_TRACK_Y") + 7;
            int trackX = contentX(screen) + 14, trackW = panelWidth(screen) - 28;
            click(screen, Math.round(trackX + trackW * 30f / 70f), trackY, 1);
            check(!field(GUI, "draggingUiScale").getBoolean(screen), "right click cannot start a scale drag");
            click(screen, Math.round(trackX + trackW * 30f / 70f), trackY, 0);
            check(field(GUI, "draggingUiScale").getBoolean(screen), "actual scaled slider click captures dragging");
            eq(100, AdninGui4.uiScalePercent, "grabbing the current thumb does not jump its value");
            screen.updateUiScaleFromMouse(-100);
            eq(70, AdninGui4.uiScalePercent, "drag past the left end reaches the minimum");
            float small = scale(screen);
            screen.updateUiScaleFromMouse(size[0] + 100);
            eq(140, AdninGui4.uiScalePercent, "drag past the right end reaches the maximum");
            check(scale(screen) > small, "scale slider visibly resizes even a compact viewport");
            float large = scale(screen);
            int fixedX = Math.round((contentX(screen) + 14 + trackW) * large);
            for (int i = 0; i < 20; i++) screen.updateUiScaleFromMouse(fixedX);
            eq(140, AdninGui4.uiScalePercent, "stationary held pointer never oscillates the resized window");
            eq("140", saves.get().getProperty("ui.scalePercent"), "dragged percentage reaches the production pending save");
            field(GUI, "draggingUiScale").setBoolean(screen, false);
            for (int i = 0; i < 3; i++) {
                int choiceW = (panelWidth(screen) - 20) / 3;
                int choiceY = top(screen) + 5 + scroll + constant("LANGUAGE_CHOICES_Y") + 10;
                click(screen, contentX(screen) + 8 + i * (choiceW + 2) + choiceW / 2, choiceY, 0);
                String code = new String[]{"en", "zh_CN", "zh_TW"}[i];
                eq(code, AdninLanguage.getLanguage(), "actual language segment selects its own locale");
                eq(code, saves.get().getProperty("ui.language"), "locale reaches the production pending save");
                check("en".equals(code) || !"Settings".equals(AdninLanguage.text("Settings")), "language changes visible UI labels");
                eq("test-key", AdninLanguage.text("test-key"), "language never rewrites arbitrary user data");
            }
        }
        AdninGui4.uiScalePercent = 100; AdninLanguage.setLanguage("en");
    }

    private static void scaledPanelActions(AdninGui4 screen) throws Exception {
        screen.selectedTheme = 1;
        field(GUI, "drawnTheme").setInt(screen, -1);
        field(GUI, "animatedYOffset").setFloat(screen, 8f);
        SET_SCROLL.invoke(screen, 1, 0);
        AdninAnticheat.enabled = false;
        saves.set(null);
        int x = contentX(screen) + panelWidth(screen) - 20;
        int y = top(screen) + 5 + constant("AC_MAIN_Y") + 11;
        click(screen, x, y, 0);
        check(AdninAnticheat.enabled, "actual handler toggles the rendered switch after scale and animation transforms");
        check(saves.get() != null, "successful actual click requests only an in-memory save");
        Properties saved = saves.get();
        click(screen, x, y, 1);
        check(AdninAnticheat.enabled && saves.get() == saved,
            "right mouse button cannot toggle settings or schedule a new save");
        // The pointer follows the visible interpolated row, not the queued target.
        SET_SCROLL.invoke(screen, 1, -70);
        field(GUI, "drawnTheme").setInt(screen, 1);
        field(GUI, "visualScroll").setDouble(screen, 0);
        click(screen, x, y, 0);
        check(!AdninAnticheat.enabled, "input follows the visible animated scroll rather than target scroll");
        field(GUI, "drawnTheme").setInt(screen, -1);
    }

    private static void clippedActions(AdninGui4 screen) throws Exception {
        SET_SCROLL.invoke(screen, 1, -18);
        AdninAnticheat.enabled = false;
        saves.set(null);
        int x = contentX(screen) + panelWidth(screen) - 20;
        for (int[] point : new int[][]{{x, top(screen) - 4}, {x, bottom(screen) + 4},
                {contentX(screen) - 8, top(screen) + 6},
                {screen.winX + screen.winW - constant("PANEL_INSET") + 5, top(screen) + 6}}) {
            click(screen, point[0], point[1], 0);
            check(!AdninAnticheat.enabled && saves.get() == null,
                "pointer outside the actual clipped content panel cannot activate its switch");
        }
        click(screen, x, top(screen) + 5, 0);
        check(AdninAnticheat.enabled, "visible segment of the top-clipped switch remains interactive");
    }

    private static void bottomReach(AdninGui4 screen) throws Exception {
        for (int panel = 0; panel < 7; panel++) {
            int content = (Integer) HEIGHT.invoke(screen, panel);
            int floor = Math.min(0, viewport(screen) - 5 - content);
            int target = (Integer) CLAMP.invoke(screen, -1000000, content, viewport(screen) - 5);
            SET_SCROLL.invoke(screen, panel, target);
            eq(floor, GET_SCROLL.invoke(screen, panel), "scroll floor includes the content's five-pixel top origin");
            check(top(screen) + 5 + floor + content <= bottom(screen),
                "complete advertised panel content fits at bottom scroll");
        }
        screen.selectedTheme = 1;
        AdninAnticheat.enabled = true;
        int scroll = (Integer) GET_SCROLL.invoke(screen, 1);
        int y = top(screen) + 5 + scroll + constant("AC_IGNORED_INPUT_Y") + 10;
        click(screen, contentX(screen) + 14, y, 0);
        eq(10, field(GUI, "activeInput").getInt(screen), "actual bottom-scrolled input receives focus");
        int utilsBefore = (Integer) GET_SCROLL.invoke(screen, 2);
        click(screen, screen.winX + constant("PANEL_INSET") + 40, top(screen) + 2 * 34 + 15, 0);
        eq(utilsBefore, GET_SCROLL.invoke(screen, 2), "switching back to Utils does not lose five pixels of bottom reach");
    }

    private static void apiControls(AdninGui4 screen) throws Exception {
        screen.selectedTheme = 0;
        SET_SCROLL.invoke(screen, 0, 0);
        AdninGui4.api_hypixel = AdninGui4.api_seraph = AdninGui4.api_aurora = AdninGui4.api_urchin = "test-key";
        AdninGui4.vegaProxy = false;
        String[] keys = {"api_hypixel", "api_seraph", "api_aurora", "api_urchin"};
        int[] inputs = {1, 2, 3, 8};
        for (int i = 0; i < keys.length; i++) {
            field(GUI, keys[i]).set(null, "  test-key  ");
            field(GUI, "activeInput").setInt(screen, inputs[i]);
            // Backspace takes the production editing path without consulting
            // native keyboard state or the system clipboard.
            KEY.invoke(screen, '\b', 14);
            eq("test-key", field(GUI, keys[i]).get(null),
                "production key editing normalizes the visible field before native synchronization");
        }
        int clearX = contentX(screen) + panelWidth(screen) - 14;
        for (int i = 0; i < keys.length; i++) {
            click(screen, clearX, top(screen) + 5 + 44 + i * 38 + 10, 0);
            eq("", field(GUI, keys[i]).get(null), "scaled clear button clears exactly its current API field");
            for (int later = i + 1; later < keys.length; later++)
                eq("test-key", field(GUI, keys[later]).get(null), "clear click preserves other credentials");
        }
        eq("missing-hypixel-key", AdninFeatures.statsProviderStatus(), "cleared GUI state immediately reports direct key missing");
        click(screen, contentX(screen) + 20, top(screen) + 5 + 207, 0);
        check(AdninGui4.vegaProxy, "actual scaled proxy control enables explicit selection");
        eq("vega-proxy", AdninFeatures.statsProviderStatus(), "status reports the visibly selected proxy");
        click(screen, contentX(screen) + 20, top(screen) + 5 + 207, 0);
        eq("missing-hypixel-key", AdninFeatures.statsProviderStatus(), "disabling explicit proxy does not restore a hidden key");
    }

    private static void overlayHeaderClipping(AdninGui4 screen) throws Exception {
        screen.selectedTheme = 3;
        AdninGui4.setOverlayGamemodeEdit("bedwars");
        AdninGui4.setOverlayColumnsFromConfig("name,hp,stars,fkdr,urchin");
        SET_SCROLL.invoke(screen, 3, -30);
        field(GUI, "overlaySelectedIndex").setInt(screen, -1);
        boolean[] enabled = AdninGui4.overlayColumnEnabled.clone();
        int[] order = AdninGui4.overlayColumnOrder.clone();
        int rowsTop = top(screen) + 5 + constant("OVERLAY_FIXED_HEADER_H");
        saves.set(null);
        // The first row overlaps the fixed header before scissoring. Its hidden
        // portion occupies the same logical coordinates as the Bedwars selector.
        click(screen, contentX(screen) + 20, rowsTop - 6, 0);
        check(saves.get() != null, "visible fixed header mode button handles the actual click");
        eq("bedwars", AdninGui4.overlayGamemodeEdit, "mode selector remains on the selected Bedwars mode");
        check(Arrays.equals(enabled, AdninGui4.overlayColumnEnabled),
            "clicking fixed header cannot toggle a row hidden beneath its separate clip");
        check(Arrays.equals(order, AdninGui4.overlayColumnOrder),
            "clicking fixed header cannot reorder a hidden overlay row");
        eq(-1, field(GUI, "overlaySelectedIndex").getInt(screen),
            "header handling does not select a second hidden row action");
        click(screen, contentX(screen) + 20, rowsTop + 4, 0);
        enabled[0] = !enabled[0];
        check(Arrays.equals(enabled, AdninGui4.overlayColumnEnabled),
            "visible part of partially clipped overlay row still toggles exactly one column");
        check(Arrays.equals(order, AdninGui4.overlayColumnOrder), "column toggle leaves ordering intact");
        eq(0, field(GUI, "overlaySelectedIndex").getInt(screen), "visible row click selects that column");
    }

    private static void closing(AdninGui4 screen) throws Exception {
        int closeX = screen.winX + screen.winW - 25, closeY = screen.winY + 24;
        click(screen, closeX, closeY, 1);
        eq(0L, field(GUI, "closingAt").getLong(screen), "right click does not begin closing");
        click(screen, closeX - 20, closeY, 0);
        eq(0L, field(GUI, "closingAt").getLong(screen), "adjacent header area has no close hitbox");
        click(screen, closeX, closeY, 0);
        long closingAt = field(GUI, "closingAt").getLong(screen);
        check(closingAt != 0, "scaled close button begins the close animation");
        AdninGui4.api_hypixel = "test-key";
        field(GUI, "activeInput").setInt(screen, 1);
        Properties saved = saves.get();
        click(screen, contentX(screen) + panelWidth(screen) - 14, top(screen) + 59, 0);
        eq("test-key", AdninGui4.api_hypixel, "mouse input cannot clear credentials during closing");
        KEY.invoke(screen, '\b', 14);
        eq("test-key", AdninGui4.api_hypixel, "keyboard input cannot edit credentials during closing");
        field(GUI, "listeningHoldRdKey").setBoolean(null, true);
        AdninGui4.holdRdKeyCode = 0;
        KEY.invoke(screen, 'x', 45);
        eq(0, AdninGui4.holdRdKeyCode, "closing guard precedes keybind capture as well as text edits");
        field(GUI, "listeningHoldRdKey").setBoolean(null, false);
        int selected = screen.selectedTheme;
        click(screen, screen.winX + constant("PANEL_INSET") + 40, top(screen) + 34 + 15, 0);
        eq(selected, screen.selectedTheme, "closing screen cannot change sidebar panel");
        click(screen, closeX, closeY, 0);
        eq(closingAt, field(GUI, "closingAt").getLong(screen), "repeated close click does not restart the animation");
        check(saved == saves.get(), "closing input never schedules a new save");
    }

    private static void click(AdninGui4 screen, int x, int y, int button) throws Exception {
        float scale = scale(screen), offset = field(GUI, "animatedYOffset").getFloat(screen);
        CLICK.invoke(screen, Math.round(x * scale), Math.round((y + offset) * scale), button);
    }
    private static float scale(AdninGui4 screen) throws Exception { return field(GUI, "uiScale").getFloat(screen); }
    private static int top(AdninGui4 screen) throws Exception { return screen.winY + constant("HEADER_H") + constant("PANEL_INSET"); }
    private static int viewport(AdninGui4 screen) throws Exception { return screen.winH - constant("HEADER_H") - constant("FOOTER_H") - constant("PANEL_INSET") * 2; }
    private static int bottom(AdninGui4 screen) throws Exception { return top(screen) + viewport(screen); }
    private static int contentX(AdninGui4 screen) throws Exception { return screen.winX + constant("PANEL_INSET") + constant("SIDEBAR_W") + constant("CONTENT_GAP"); }
    private static int panelWidth(AdninGui4 screen) throws Exception { return screen.winX + screen.winW - constant("PANEL_INSET") - 20 - contentX(screen); }
    private static int constant(String name) throws Exception { return field(GUI, name).getInt(null); }
    private static Field field(Class<?> owner, String name) throws Exception { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f; }
    private static Method method(String name, Class<?>... parameters) { try { Method m = GUI.getDeclaredMethod(name, parameters); m.setAccessible(true); return m; } catch (Exception failure) { throw new AssertionError(failure); } }
    private static Map<String, Object> snapshot(Class<?> owner, String... names) throws Exception {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (String name : names) {
            Object value = field(owner, name).get(null);
            if (value instanceof boolean[]) value = ((boolean[]) value).clone();
            if (value instanceof int[]) value = ((int[]) value).clone();
            values.put(name, value);
        }
        return values;
    }
    private static void restore(Class<?> owner, Map<String, Object> values) throws Exception { for (Map.Entry<String, Object> entry : values.entrySet()) field(owner, entry.getKey()).set(null, entry.getValue()); }
    private static void eq(Object expected, Object actual, String message) { check(expected.equals(actual), message); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
