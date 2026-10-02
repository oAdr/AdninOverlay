/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.FontRenderer
 *  net.minecraft.client.gui.Gui
 *  net.minecraft.client.gui.GuiScreen
 *  org.lwjgl.input.Keyboard
 *  org.lwjgl.input.Mouse
 */
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class AdninGui4
extends GuiScreen {
    public static boolean autoWho = false;
    public static boolean partyDetector = false;
    public static boolean partyQueueDetector = false;
    /** Existing native active-queue bridge; no native declaration or ABI change. */
    public static int pollPartyMode() { return AdninPartyQueueQuery.pollMode(); }
    /** Replaces the original recurring native per-client disk save. */
    public static int nativeRequestConfigSave() { AdninFeatures.requestSave(); return 1; }
    public static boolean bedDisconnectTimer = false;
    public static boolean holdRdEnabled = false;
    public static int holdRdKeyCode = 56;
    public static boolean quickbuyEnabled = false;
    public static int quickbuyDelayMs = 100;
    /** Independent Bed Wars Quick Buy profile copier. This is deliberately
     * separate from quickbuyEnabled/quickbuyBinds, which control hotkeys. */
    public static boolean quickbuyProfileCopier = false;
    public static String quickbuyProfilePlayer = "";
    private static volatile boolean quickbuyProfileLoadRequested = false;
    public static void requestQuickbuyProfileLoad() {
        String player = quickbuyProfilePlayer == null ? "" : quickbuyProfilePlayer.trim();
        if (quickbuyProfileCopier && player.length() > 0) quickbuyProfileLoadRequested = true;
    }
    /** Consumed by the profile-copy state machine from its client-thread pump. */
    public static boolean consumeQuickbuyProfileLoadRequest() {
        if (!quickbuyProfileLoadRequested) return false;
        quickbuyProfileLoadRequested = false;
        return true;
    }
    public static final String[] QUICKBUY_IDS = new String[]{"wool", "stone_sword", "iron_sword", "golden_apple", "fire_charge", "tnt", "ender_pearl", "pickaxe", "axe", "shears", "chainmail_boots", "iron_boots", "upg iron_sword", "upg iron_chestplate", "upg iron_pickaxe", "upg golden_pickaxe", "upg diamond_boots", "diamond_sword", "stick", "arrow", "diamond_boots"};
    public static final String[] QUICKBUY_LABELS = new String[]{"Wool", "Stone Sword", "Iron Sword", "Golden Apple", "Fireball", "TNT", "Ender Pearl", "Pickaxe", "Axe", "Shears", "Chainmail Armor", "Iron Armor", "Sharpness", "Protection", "Mining Fatigue", "Haste", "Feather Falling", "Diamond Sword", "Knockback Stick", "Arrows", "Diamond Armor"};
    public static int[] quickbuyKeys = new int[QUICKBUY_IDS.length];
    public static int[] quickbuySlots = new int[QUICKBUY_IDS.length];
    public static boolean[] quickbuyTurbo = new boolean[QUICKBUY_IDS.length];
    public static String quickbuyBinds = "";
    public static boolean numberDenicker = false;
    public static boolean botDenicker = false;
    public static String botDenickerUrl = "";
    public static boolean clientSideSounds = false;
    public static boolean autoGL = false;
    public static boolean fastBuy = false;
    public static boolean coloredHitboxes = false;
    public static boolean dragonHitboxes = false;
    public static int hitboxThickness = 1;
    public static boolean arrowDistance = false;
    public static boolean tradeIndicator = false;
    public static boolean tabOverlay = true;
    public static boolean overlayResourceHeader = true;
    public static boolean overlayResourceDiamonds = true;
    public static boolean overlayResourceEmeralds = true;
    public static String overlayResourceLocation = "tab";
    public static int overlayOpacity = 60;
    public static String overlayThemeColor = "0F1316";
    public static String configThemeColor = "1A2227";
    public static String configTextColor = "D6DEE4";
    public static int uiScalePercent = 100;
    private static final String[] OVERLAY_GAMEMODES = new String[]{"bedwars", "skywars", "duel", "bedwarsduels"};
    private static final String[] OVERLAY_GAMEMODE_LABELS = new String[]{"Bedwars", "SkyWars", "Duel", "BW Duels"};
    public static String overlayGamemodeEdit = "bedwars";
    public static String overlayGamemodeActive = "unknown";
    private static String overlayColumnsBedwars = "";
    private static String overlayColumnsSkywars = "";
    private static String overlayColumnsDuel = "";
    private static String overlayColumnsBedwarsduels = "";
    private static final int MAX_OVERLAY_COLUMNS = 20;
    private static volatile ColumnStateSnapshot columnStateSnapshot;
    private static final NormalizedColumnSnapshot[] normalizedColumnSnapshots = new NormalizedColumnSnapshot[4];

    /** Immutable snapshots also detect changes made directly through the public JNI arrays. */
    private static final class ColumnStateSnapshot {
        final String mode, columns;
        final boolean[] enabled;
        final int[] order;
        ColumnStateSnapshot(String mode, boolean[] enabled, int[] order, String columns) {
            this.mode = mode; this.enabled = enabled; this.order = order; this.columns = columns;
        }
        boolean matches(String mode, boolean[] enabled, int[] order, int count) {
            if (!this.mode.equals(mode) || this.enabled.length != count) return false;
            for (int i = 0; i < count; i++)
                if (this.enabled[i] != enabled[i] || this.order[i] != order[i]) return false;
            return true;
        }
    }

    private static final class NormalizedColumnSnapshot {
        final String source, columns;
        final boolean urchin, fkLv;
        NormalizedColumnSnapshot(String mode, String source) {
            this.source = source;
            this.columns = AdninColumnOrder.normalize(mode, source);
            this.urchin = columns.endsWith(",urchin") || columns.contains(",urchin,");
            this.fkLv = columns.endsWith(",fklv") || columns.contains(",fklv,");
        }
    }
    private static final String DEFAULT_COLS_BEDWARS = "name,hp,stars,stars_new,fkdr,fklv,wlr,kdr,swordKD,bblr,index,wins,finalKills,bedsBroken,requeuePct,winstreak,seens,session,ping,pingvar,seraph,urchin";
    private static final String DEFAULT_COLS_SKYWARS = "name,hp,sw_stars,sw_kdr,sw_wlr,sw_wins,sw_kills,seens,session,ping,pingvar,seraph,urchin";
    private static final String DEFAULT_COLS_DUEL = "name,hp,duel_wins,duel_wlr,duel_kdr,seens,session,ping,pingvar,seraph,urchin";
    private static final String DEFAULT_COLS_BWDUELS = "name,hp,bwd_wlr,bwd_index,seens,session,ping,pingvar,seraph,urchin";
    private static final String[] BW_COLUMN_IDS = new String[]{"stars", "stars_new", "fkdr", "wlr", "kdr", "swordKD", "bblr", "index", "wins", "finalKills", "bedsBroken", "requeuePct", "winstreak", "seens", "session", "ping", "pingvar", "seraph", "urchin", "fklv"};
    private static final String[] BW_COLUMN_LABELS = new String[]{"Stars", "Stars New", "FKDR", "WLR", "KDR", "SwordKD", "BBLR", "Index", "Wins", "FinalKills", "BedsBroken", "Requeue%", "Winstreak", "Seens", "Session", "Ping", "PingVar", "Seraph", "Urchin", "FK/LV"};
    private static final String[] SW_COLUMN_IDS = new String[]{"sw_stars", "sw_kdr", "sw_wlr", "sw_wins", "sw_kills", "seens", "session", "ping", "pingvar", "seraph", "urchin"};
    private static final String[] SW_COLUMN_LABELS = new String[]{"Stars (SW)", "KDR (SW)", "WLR (SW)", "Wins (SW)", "Kills (SW)", "Seens", "Session", "Ping", "PingVar", "Seraph", "Urchin"};
    private static final String[] DUEL_COLUMN_IDS = new String[]{"duel_wins", "duel_wlr", "duel_kdr", "seens", "session", "ping", "pingvar", "seraph", "urchin"};
    private static final String[] DUEL_COLUMN_LABELS = new String[]{"Wins (Duel)", "WLR (Duel)", "KDR (Duel)", "Seens", "Session", "Ping", "PingVar", "Seraph", "Urchin"};
    private static final String[] BWD_COLUMN_IDS = new String[]{"bwd_wlr", "bwd_index", "seens", "session", "ping", "pingvar", "seraph", "urchin"};
    private static final String[] BWD_COLUMN_LABELS = new String[]{"WLR (BW Duels)", "Index (BW Duels)", "Seens", "Session", "Ping", "PingVar", "Seraph", "Urchin"};
    public static boolean[] overlayColumnEnabled = new boolean[]{true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true};
    public static int[] overlayColumnOrder = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19};
    // Retained only for the recovered JNI/config ABI. Native calls to the old
    // checks are disabled by guarded patches in both payloads.
    public static boolean anticheat = false;
    public static boolean ac_flagsound = true;
    public static boolean ac_autoblock = false;
    public static boolean ac_legitscaff = false;
    public static boolean ac_scaffold = false;
    public static boolean ac_scaffoldb = false;
    public static boolean ac_noslow = false;
    public static String api_hypixel = "";
    public static String api_seraph = "";
    public static String api_aurora = "";
    public static String api_urchin = "";
    public static boolean vegaProxy = false;
    public static String gl_message = "gg";
    public static boolean compactBlacklist = false;
    public static boolean chatOverlay = false;
    public static boolean chatOutput = false;
    public static boolean chatOutputDenick = false;
    public static boolean chatOutputTags = false;
    public static boolean chatOutputTagsSelf = true;
    public static boolean chatOutputTagsTeammates = true;
    public static boolean chatOutputAnticheat = false;
    public static boolean ignoreTeammates = false;
    public static String chatOverlayMinStars = "100";
    public static String chatOverlayMinFkdr = "5";
    public static String chatOverlayMinSwKdr = "1.5";
    public static boolean sessionStats = false;
    public static boolean sessionStatsGame = true;
    public static boolean sessionStatsFinalKills = true;
    public static boolean sessionStatsBeds = true;
    public static boolean sessionStatsKills = true;
    public static boolean sessionStatsWins = true;
    public static boolean sessionStatsWinstreak = true;
    public static boolean sessionStatsSessionGames = true;
    public static boolean sessionStatsGameTime = true;
    public static boolean sessionStatsAvgTime = true;
    public static boolean sessionStatsSessionTime = true;
    public static boolean sessionStatsSlumberTickets = true;
    public static boolean sessionStatsTextShadow = false;
    public static boolean sessionStatsResetPending = false;
    public static int sessionStatsBgOpacity = 100;
    public static int sessionStatsScale = 100;
    public static int sessionStatsPosX = 1000;
    public static int sessionStatsPosY = 1000;
    private static final String[] THEMES = new String[]{"Settings", "Anticheat", "Utils", "Overlay", "Chat Overlay", "Session", "Experimental"};
    public int themeScroll = 0;
    public int selectedTheme = 1;
    public int winX;
    public int winY;
    public int winW;
    public int winH;
    private static FontRenderer cachedFontRenderer = null;
    private int activeInput = 0;
    private float uiScale = 1, animatedYOffset;
    private boolean draggingUiScale;
    private boolean keyboardRepeatCaptured, keyboardRepeatBefore;
    // Input strings can contain credentials. Keep only a small per-screen cache,
    // never a static cache, and release every retained value when the screen closes.
    private final InputClip[] inputClips = new InputClip[10];
    private int nextInputClip;
    private static final class InputClip {
        final String source, visible;
        final int width;
        final boolean reveal, focused, fontFailure;
        InputClip(String source, int width, boolean reveal, boolean focused, boolean fontFailure, String visible) {
            this.source = source; this.width = width; this.reveal = reveal;
            this.focused = focused; this.fontFailure = fontFailure; this.visible = visible;
        }
    }
    private long openedAt, closingAt, lastFrame, pageChangedAt;
    private int drawnTheme = -1;
    private double visualScroll, sidebarSelection;
    private final java.util.Map<String, Float> switchPositions = new java.util.HashMap<String, Float>();
    private float frameSeconds;
    private static final String[] DESCRIPTIONS = {
        "Connections, appearance and language", "Player checks and alert preferences",
        "Everyday tools, made personal", "Choose and arrange player statistics",
        "Local messages and party output", "Your session at a glance", "Additional utilities"
    };

    private int viewportHeight() { return this.winH - HEADER_H - FOOTER_H - PANEL_INSET * 2; }

    // Shared geometry for drawing, clicks and wheel input at every GUI scale.
    void layoutForViewport() {
        this.winW = 620; this.winH = 428;
        uiScalePercent = clampUiScale(uiScalePercent);
        this.uiScale = scaleForPercent(uiScalePercent);
        this.winX = Math.round((this.width / this.uiScale - this.winW) / 2);
        this.winY = Math.round((this.height / this.uiScale - this.winH) / 2);
    }

    public static int clampUiScale(int value) { return Math.max(70, Math.min(140, value)); }

    public static void loadUiSettings(java.util.Properties properties) {
        int value = 100;
        try { value = Integer.parseInt(properties.getProperty("ui.scalePercent", "100")); }
        catch (NumberFormatException invalid) { }
        uiScalePercent = clampUiScale(value);
    }

    public static void saveUiSettings(java.util.Properties properties) {
        properties.setProperty("ui.scalePercent", Integer.toString(clampUiScale(uiScalePercent)));
    }

    private float scaleForPercent(int value) {
        float fit = Math.max(0.02f, Math.min((this.width - 20f) / 620f, (this.height - 20f) / 428f));
        return Math.min(fit, Math.min(1f, fit) * clampUiScale(value) / 100f);
    }

    private int uiScaleTrackRelativeX() { return PANEL_INSET + SIDEBAR_W + CONTENT_GAP + 14; }
    private int uiScaleTrackWidth() { return winW - PANEL_INSET - 20 - (PANEL_INSET + SIDEBAR_W + CONTENT_GAP) - 28; }

    // Resizing moves a centered window's slider. Solve against the candidate
    // geometry in screen coordinates so a held pointer never causes feedback
    // jitter, and drawing, hit-testing and clipping always use the same scale.
    void updateUiScaleFromMouse(int screenX) {
        int best = uiScalePercent;
        float distance = Float.MAX_VALUE;
        for (int value = 70; value <= 140; value++) {
            float candidate = scaleForPercent(value);
            int origin = Math.round((width / candidate - winW) / 2);
            float knob = (origin + uiScaleTrackRelativeX() + uiScaleTrackWidth() * (value - 70) / 70f) * candidate;
            float next = Math.abs(screenX - knob);
            if (next < distance) { distance = next; best = value; }
        }
        if (best != uiScalePercent) {
            uiScalePercent = best;
            layoutForViewport();
            AdninFeatures.requestSave();
        }
    }

    private int visibleScroll() {
        return drawnTheme == selectedTheme ? (int)Math.round(visualScroll) : getPanelScroll(selectedTheme);
    }

    private float switchPosition(String key, boolean on) {
        Float old = switchPositions.get(key);
        float target = on ? 1f : 0f;
        float value = old == null ? target : old + (target - old) * (1f - (float)Math.exp(-18 * frameSeconds));
        switchPositions.put(key, value);
        return value;
    }

    private void drawSwitch(float x, float y, int w, int h, boolean on, boolean enabled, String key) {
        float position = switchPosition(key, on);
        AdninUi.round(x, y, w, h, h / 2f, enabled && on ? AdninUi.GREEN : 0xFF4A4E59);
        AdninUi.round(x + 2 + (w - h) * position, y + 2, h - 4, h - 4, (h - 4) / 2f,
            enabled ? 0xFFFFFFFF : 0xFF999EAA);
    }
    private int settingsScroll = 0;
    private int anticheatScroll = 0;
    private int utilsScroll = 0;
    private int overlayScroll = 0;
    private int chatOverlayScroll = 0;
    private int sessionStatsScroll = 0;
    private int experimentalScroll = 0;
    private int overlaySelectedIndex = -1;
    private boolean showHypixel = false;
    private boolean showSeraph = false;
    private boolean showAurora = false;
    private boolean showUrchin = false;
    private boolean draggingHitboxSlider = false;
    private int hitboxSliderTrackX = 0;
    private int hitboxSliderTrackW = 0;
    private static boolean listeningHoldRdKey = false;
    private static int listeningQuickbuyIndex = -1;
    private static final int HEADER_H = 56;
    private static final int FOOTER_H = 16;
    private static final int SIDEBAR_W = 142;
    private static final int PANEL_INSET = 16;
    private static final int CONTENT_GAP = 20;
    private static final int SCROLLBAR_W = 4;
    private static final int SCROLLBAR_GAP = 8;
    private static final int CONTENT_INNER_GAP = 8;
    private static final int OVERLAY_FIXED_HEADER_H = 104;
    private static final int CLR_ON = 0xFF32D583;
    private static final int CLR_OFF = 0xFF969CA9;
    private static final int CLR_CHOICE = 0xFF529AFF;
    private static final int CLR_ACTION = 0xFF8ABBFF;
    private static final int CLR_MUTED = 0xFF6F7685;
    private static final int UTILS_FEATURES_Y = 4;
    private static final int UTILS_FEATURES_H = 186;
    private static final int UTILS_HITBOX_SLIDER_Y = 90;
    private static final int UTILS_HITBOX_SLIDER_H = 16;
    private static final int UTILS_HITBOX_SLIDER_KNOB = 10;
    private static final int UTILS_SHOT_DISTANCE_Y = 122;
    private static final int UTILS_BED_DC_Y = 154;
    private static final int UTILS_SECTION_GAP = 12;
    private static final int UTILS_TITLE_H = 12;
    private static final int RESOURCE_TIMER_TITLE_Y = 202;
    private static final int RESOURCE_TIMER_SECTION_Y = 218;
    private static final int RESOURCE_TIMER_CARD_H = 96;
    private static final int UTILS_GL_TITLE_Y = 326;
    private static final int UTILS_GL_FIELD_Y = 342;
    // Keep the profile copier in the middle of Utils so the existing
    // Nickname Lookup and Sounds controls remain reachable at the bottom of
    // a compact window after scrolling.
    private static final int UTILS_PROFILE_TITLE_Y = 386;
    private static final int UTILS_PROFILE_CARD_Y = 402;
    private static final int UTILS_PROFILE_CARD_H = 86;
    private static final int UTILS_PROFILE_TOGGLE_Y = 410;
    private static final int UTILS_PROFILE_FIELD_Y = 448;
    private static final int UTILS_BOT_TITLE_Y = 506;
    private static final int UTILS_BOT_CARD_Y = 522;
    private static final int UTILS_BOT_CARD_H = 80;
    private static final int UTILS_BOT_TOGGLE_Y = 530;
    private static final int UTILS_BOT_URL_Y = 574;
    private static final int UTILS_SOUND_TITLE_Y = 626;
    private static final int UTILS_SOUND_CARD_Y = 642;
    private static final int UTILS_SOUND_TOGGLE_Y = 650;
    private static final int AC_IGNORED_INPUT_Y = 362;
    private static final int AC_CONTENT_H = AC_IGNORED_INPUT_Y + 28;
    private static final int AC_INTERVAL_Y = 300;
    private static final int AC_MAIN_Y = 4;
    private static final int AC_MAIN_H = 50;
    private static final int AC_CHECKS_Y = 68;
    private static final int AC_CHECKS_H = 92;
    private static final int CHAT_MAIN_Y = 4;
    private static final int CHAT_MAIN_H = 180;
    private static final int CHAT_OUTPUT_PLAYERS_Y = 36;
    private static final int CHAT_OUTPUT_DENICK_Y = 64;
    private static final int CHAT_OUTPUT_TAGS_Y = 92;
    private static final int CHAT_OUTPUT_TAG_FILTERS_Y = 120;
    private static final int CHAT_OUTPUT_ANTICHEAT_Y = 148;
    private static final int CHAT_THRESH_Y = 204;
    private static final int CHAT_THRESH_H = 144;
    private static final int SESSION_STATS_Y = 4;
    private static final int SESSION_STATS_H = 140;
    private static final int SESSION_DISPLAY_TITLE_Y = 156;
    private static final int SESSION_DISPLAY_SECTION_Y = 172;
    private static final int SESSION_DISPLAY_CARD_H = 148;
    private static final int INTERFACE_CARD_Y = 324;
    private static final int UI_SCALE_TRACK_Y = INTERFACE_CARD_Y + 38;
    private static final int LANGUAGE_CHOICES_Y = INTERFACE_CARD_Y + 88;

    /** Stable owner-class entry point for both native client profiles. */
    public static void setGameActive(boolean active) { AdninFeatures.setGameActive(active); }

    public static boolean isHoldRdHotkeyDown() {
        if (!holdRdEnabled || listeningHoldRdKey || listeningQuickbuyIndex >= 0) {
            return false;
        }
        if (holdRdKeyCode <= 0) {
            return false;
        }
        try {
            return Keyboard.isKeyDown((int)holdRdKeyCode);
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    public static boolean isQuickbuyKeyDown(int n) {
        if (!quickbuyEnabled || listeningQuickbuyIndex >= 0 || listeningHoldRdKey) {
            return false;
        }
        if (n < 0 || n >= quickbuyKeys.length) {
            return false;
        }
        int n2 = quickbuyKeys[n];
        if (n2 <= 0) {
            return false;
        }
        try {
            return Keyboard.isKeyDown((int)n2);
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    private static String quickbuyKeyLabel(int n) {
        if (n < 0 || n >= quickbuyKeys.length || quickbuyKeys[n] <= 0) {
            return "NONE";
        }
        try {
            String string = Keyboard.getKeyName((int)quickbuyKeys[n]);
            if (string != null && string.length() > 0) {
                return string;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return "#" + quickbuyKeys[n];
    }

    private static boolean isQuickbuyUpgrade(int n) {
        return n >= 0 && n < QUICKBUY_IDS.length && QUICKBUY_IDS[n].startsWith("upg ");
    }

    public static String serializeQuickbuyBinds() {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < QUICKBUY_IDS.length; ++i) {
            if (i > 0) {
                stringBuilder.append(';');
            }
            stringBuilder.append(QUICKBUY_IDS[i]).append(':').append(quickbuyKeys[i]).append(':').append(quickbuySlots[i]).append(':').append(quickbuyTurbo[i] ? 1 : 0);
        }
        return stringBuilder.toString();
    }

    public static void applyQuickbuyBinds(String string) {
        String[] stringArray;
        if (string == null || string.isEmpty()) {
            return;
        }
        block2: for (String string2 : stringArray = string.split(";")) {
            String[] stringArray2 = string2.split(":");
            if (stringArray2.length < 4) continue;
            for (int i = 0; i < QUICKBUY_IDS.length; ++i) {
                if (!QUICKBUY_IDS[i].equals(stringArray2[0])) continue;
                try {
                    AdninGui4.quickbuyKeys[i] = Integer.parseInt(stringArray2[1]);
                    AdninGui4.quickbuySlots[i] = Integer.parseInt(stringArray2[2]);
                    AdninGui4.quickbuyTurbo[i] = "1".equals(stringArray2[3]);
                }
                catch (Throwable throwable) {}
                continue block2;
            }
        }
    }

    public static void setQuickbuyBinds(String string) {
        quickbuyBinds = string;
        AdninGui4.applyQuickbuyBinds(string);
    }

    public static void applyQuickbuyBindsFromField() {
        AdninGui4.applyQuickbuyBinds(quickbuyBinds);
    }

    private static void syncQuickbuyBinds() {
        quickbuyBinds = AdninGui4.serializeQuickbuyBinds();
    }

    private static void cycleQuickbuyDelay() {
        int[] nArray = new int[]{0, 50, 100, 150, 200, 250, 300, 350, 400};
        int n = 0;
        for (int i = 0; i < nArray.length; ++i) {
            if (nArray[i] != quickbuyDelayMs) continue;
            n = i;
            break;
        }
        quickbuyDelayMs = nArray[(n + 1) % nArray.length];
    }

    private static int normalizeOpacity(int n) {
        if (n < 0) {
            return 0;
        }
        if (n > 95) {
            return 95;
        }
        return n;
    }

    private static int normalizeHitboxThickness(int n) {
        if (n < 1) {
            return 1;
        }
        if (n > 10) {
            return 10;
        }
        return n;
    }

    private static String sanitizeHexColor(String string) {
        if (string == null) {
            return "0F1316";
        }
        String string2 = string.trim();
        if (string2.startsWith("#")) {
            string2 = string2.substring(1);
        }
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < string2.length(); ++i) {
            boolean bl;
            char c = string2.charAt(i);
            boolean bl2 = bl = c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
            if (!bl) continue;
            stringBuilder.append(Character.toUpperCase(c));
        }
        if (stringBuilder.length() > 6) {
            stringBuilder.setLength(6);
        }
        return stringBuilder.toString();
    }

    private static int parseHexColorInt(String string, int n) {
        String string2 = AdninGui4.sanitizeHexColor(string);
        if (string2.length() != 6) {
            return n;
        }
        try {
            return Integer.parseInt(string2, 16) & 0xFFFFFF;
        }
        catch (Throwable throwable) {
            return n;
        }
    }

    private static int mulRgb(int n, int n2) {
        if (n2 < 0) {
            n2 = 0;
        }
        if (n2 > 200) {
            n2 = 200;
        }
        int n3 = n >> 16 & 0xFF;
        int n4 = n >> 8 & 0xFF;
        int n5 = n & 0xFF;
        n3 = n3 * n2 / 100;
        n4 = n4 * n2 / 100;
        n5 = n5 * n2 / 100;
        if (n3 > 255) {
            n3 = 255;
        }
        if (n4 > 255) {
            n4 = 255;
        }
        if (n5 > 255) {
            n5 = 255;
        }
        return (n3 & 0xFF) << 16 | (n4 & 0xFF) << 8 | n5 & 0xFF;
    }

    private static int darkenRgb(int n, int n2) {
        if (n2 < 0) {
            n2 = 0;
        }
        if (n2 > 100) {
            n2 = 100;
        }
        int n3 = n >> 16 & 0xFF;
        int n4 = n >> 8 & 0xFF;
        int n5 = n & 0xFF;
        n3 = n3 * n2 / 100;
        n4 = n4 * n2 / 100;
        n5 = n5 * n2 / 100;
        return (n3 & 0xFF) << 16 | (n4 & 0xFF) << 8 | n5 & 0xFF;
    }

    private static String[] columnIdsForMode(String string) {
        if ("skywars".equals(string)) {
            return SW_COLUMN_IDS;
        }
        if ("duel".equals(string)) {
            return DUEL_COLUMN_IDS;
        }
        if ("bedwarsduels".equals(string)) {
            return BWD_COLUMN_IDS;
        }
        return BW_COLUMN_IDS;
    }

    private static String[] columnLabelsForMode(String string) {
        if ("skywars".equals(string)) {
            return SW_COLUMN_LABELS;
        }
        if ("duel".equals(string)) {
            return DUEL_COLUMN_LABELS;
        }
        if ("bedwarsduels".equals(string)) {
            return BWD_COLUMN_LABELS;
        }
        return BW_COLUMN_LABELS;
    }

    private static String normalizeColumnIdForMode(String string, String string2) {
        if ("skywars".equals(string)) {
            if ("stars".equals(string2)) {
                return "sw_stars";
            }
            if ("kdr".equals(string2)) {
                return "sw_kdr";
            }
            if ("wlr".equals(string2)) {
                return "sw_wlr";
            }
            if ("wins".equals(string2)) {
                return "sw_wins";
            }
            if ("kills".equals(string2)) {
                return "sw_kills";
            }
        } else if ("duel".equals(string)) {
            if ("wins".equals(string2)) {
                return "duel_wins";
            }
            if ("wlr".equals(string2)) {
                return "duel_wlr";
            }
            if ("kdr".equals(string2)) {
                return "duel_kdr";
            }
        } else if ("bedwarsduels".equals(string)) {
            if ("wlr".equals(string2)) {
                return "bwd_wlr";
            }
            if ("index".equals(string2)) {
                return "bwd_index";
            }
        }
        return string2;
    }

    private int[] getOverlaySortedIndexes() {
        int n;
        int n2 = AdninGui4.columnIdsForMode(overlayGamemodeEdit).length;
        int[] nArray = new int[n2];
        for (n = 0; n < n2; ++n) {
            nArray[n] = n;
        }
        for (n = 0; n < n2 - 1; ++n) {
            for (int i = n + 1; i < n2; ++i) {
                if (overlayColumnOrder[nArray[n]] <= overlayColumnOrder[nArray[i]]) continue;
                int n3 = nArray[n];
                nArray[n] = nArray[i];
                nArray[i] = n3;
            }
        }
        return nArray;
    }

    private int findOverlayIndexByOrder(int n) {
        for (int i = 0; i < AdninGui4.columnIdsForMode(overlayGamemodeEdit).length; ++i) {
            if (overlayColumnOrder[i] != n) continue;
            return i;
        }
        return -1;
    }

    private void moveOverlayColumn(int n, int n2) {
        if (n < 0 || n >= AdninGui4.columnIdsForMode(overlayGamemodeEdit).length) {
            return;
        }
        int n3 = overlayColumnOrder[n];
        int n4 = this.findOverlayIndexByOrder(n3 + n2);
        if (n4 < 0) {
            return;
        }
        int n5 = overlayColumnOrder[n];
        AdninGui4.overlayColumnOrder[n] = overlayColumnOrder[n4];
        AdninGui4.overlayColumnOrder[n4] = n5;
    }

    private static String defaultColumnsForMode(String string) {
        if ("skywars".equals(string)) {
            return DEFAULT_COLS_SKYWARS;
        }
        if ("duel".equals(string)) {
            return DEFAULT_COLS_DUEL;
        }
        if ("bedwarsduels".equals(string)) {
            return DEFAULT_COLS_BWDUELS;
        }
        return DEFAULT_COLS_BEDWARS;
    }

    private static String getStoredColumnsForMode(String string) {
        if ("skywars".equals(string)) {
            return overlayColumnsSkywars.length() > 0 ? overlayColumnsSkywars : DEFAULT_COLS_SKYWARS;
        }
        if ("duel".equals(string)) {
            return overlayColumnsDuel.length() > 0 ? overlayColumnsDuel : DEFAULT_COLS_DUEL;
        }
        if ("bedwarsduels".equals(string)) {
            return overlayColumnsBedwarsduels.length() > 0 ? overlayColumnsBedwarsduels : DEFAULT_COLS_BWDUELS;
        }
        return overlayColumnsBedwars.length() > 0 ? overlayColumnsBedwars : DEFAULT_COLS_BEDWARS;
    }

    private static void setStoredColumnsForMode(String string, String string2) {
        if (string2 == null) {
            string2 = "";
        }
        if ("skywars".equals(string)) {
            overlayColumnsSkywars = string2;
        } else if ("duel".equals(string)) {
            overlayColumnsDuel = string2;
        } else if ("bedwarsduels".equals(string)) {
            overlayColumnsBedwarsduels = string2;
        } else {
            overlayColumnsBedwars = string2;
        }
    }

    private static String buildColumnsConfigFromState() {
        int n;
        int n2;
        int n3;
        String[] stringArray = AdninGui4.columnIdsForMode(overlayGamemodeEdit);
        int n4 = stringArray.length;
        String mode = AdninColumnOrder.mode(overlayGamemodeEdit);
        boolean[] sourceEnabled = overlayColumnEnabled;
        int[] sourceOrder = overlayColumnOrder;
        ColumnStateSnapshot cached = columnStateSnapshot;
        if (cached != null && cached.matches(mode, sourceEnabled, sourceOrder, n4)) return cached.columns;
        boolean[] enabled = new boolean[n4];
        int[] order = new int[n4];
        System.arraycopy(sourceEnabled, 0, enabled, 0, n4);
        System.arraycopy(sourceOrder, 0, order, 0, n4);
        int[] nArray = new int[n4];
        for (n3 = 0; n3 < n4; ++n3) {
            nArray[n3] = n3;
        }
        for (n3 = 0; n3 < n4 - 1; ++n3) {
            for (n2 = n3 + 1; n2 < n4; ++n2) {
                if (order[nArray[n3]] <= order[nArray[n2]]) continue;
                n = nArray[n3];
                nArray[n3] = nArray[n2];
                nArray[n2] = n;
            }
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("name,hp");
        for (n2 = 0; n2 < n4; ++n2) {
            n = nArray[n2];
            if (!enabled[n]) continue;
            stringBuilder.append(',');
            stringBuilder.append(stringArray[n]);
        }
        String columns = stringBuilder.toString();
        columnStateSnapshot = new ColumnStateSnapshot(mode, enabled, order, columns);
        return columns;
    }

    public static void snapshotOverlayColumnsEditMode() {
        AdninGui4.setStoredColumnsForMode(overlayGamemodeEdit, AdninGui4.buildColumnsConfigFromState());
    }

    public static void loadOverlayColumnsForEditMode() {
        AdninGui4.setOverlayColumnsFromConfig(AdninGui4.getStoredColumnsForMode(overlayGamemodeEdit));
    }

    public static void setOverlayGamemodeEdit(String string) {
        if (string == null || string.length() == 0) {
            return;
        }
        AdninGui4.snapshotOverlayColumnsEditMode();
        overlayGamemodeEdit = string;
        AdninGui4.loadOverlayColumnsForEditMode();
    }

    public static void setOverlayGamemodeActive(String string) {
        if (string == null || string.length() == 0) {
            return;
        }
        overlayGamemodeActive = string;
    }

    public static String getOverlayColumnsConfig() {
        return normalizedColumnsSnapshot().columns;
    }

    private static NormalizedColumnSnapshot normalizedColumnsSnapshot() {
        String mode = AdninColumnOrder.mode(overlayGamemodeActive);
        String columns = mode.equals(AdninColumnOrder.mode(overlayGamemodeEdit))
            ? AdninGui4.buildColumnsConfigFromState()
            : AdninGui4.getStoredColumnsForMode(mode);
        int index = "skywars".equals(mode) ? 1 : "duel".equals(mode) ? 2 : "bedwarsduels".equals(mode) ? 3 : 0;
        NormalizedColumnSnapshot cached = normalizedColumnSnapshots[index];
        if (cached == null || !cached.source.equals(columns)) {
            cached = new NormalizedColumnSnapshot(mode, columns);
            normalizedColumnSnapshots[index] = cached;
        }
        return cached;
    }

    public static boolean isUrchinColumnEnabled() {
        return normalizedColumnsSnapshot().urchin;
    }

    public static boolean isFkLvColumnEnabled() {
        if (!"bedwars".equals(AdninColumnOrder.mode(overlayGamemodeActive))) {
            return false;
        }
        return normalizedColumnsSnapshot().fkLv;
    }

    public static void nativeGeneratedEvent(String text, boolean json) {
        AdninFeatures.nativeGeneratedEvent(text, json);
    }

    public static void nativeGeneratedEvent(String text, boolean json, int category) {
        AdninFeatures.nativeGeneratedEvent(text, json, category);
    }

    public static int nativeRenderGeneratedEvent(String text, boolean json, int category) {
        return AdninFeatures.nativeRenderGeneratedEvent(text, json, category);
    }

    public static String nativeOverlayHeader(String text, Object font, int width) {
        return AdninHeaders.translate(text, font, width);
    }

    public static int nativeReplayMode() {
        return AdninReplay.isReplay() ? 1 : 0;
    }

    public static String nativeReplayProfile(String rawTabName) {
        return AdninReplay.profile(rawTabName);
    }

    public static int nativeReplayIsNick(String rawTabName) {
        return AdninReplay.isNick(rawTabName) ? 1 : 0;
    }

    public static int nativeStopGameModules() {
        if (!AdninGameModules.isHotkeyUnloadRequested()) return 0;
        AdninGameModules.stop();
        // The native End-key guard accepts only 1. Never wait for a Netty
        // callback here: defer unloading until its observation has returned.
        return AdninPacketLog.isQuiescent() ? 1 : 0;
    }

    public static int nativeUrchinWidth(int available) {
        return AdninFeatures.getUrchinColumnWidth(available);
    }

    public static void nativeUrchinHeader(float x, float y, float right) {
        AdninFeatures.drawUrchinHeader(x, y, right);
    }

    public static void nativeUrchinRow(String name, float x, float y, float right) {
        AdninFeatures.drawUrchinRow(name, x, y, right);
    }

    public static void nativeOverlayRow(String name, float x, float y, float right, String finalKills, String level) {
        AdninFeatures.drawOverlayRow(name, x, y, right, finalKills, level);
    }

    public static void nativeOrderedOverlayRow(String name, float y, float fkX, float fkWidth,
            float urchinX, float urchinWidth, String finalKills, String level) {
        AdninFeatures.drawOrderedOverlayRow(name, y, fkX, fkWidth, urchinX, urchinWidth, finalKills, level);
    }

    public static String nativeBotProfile(String name) {
        return AdninFeatures.getBotProfile(name);
    }

    /** Same native filtered candidate; skin metadata is resolved on client ticks. */
    public static String nativeDenickerProfile(String name, boolean skinEnabled) {
        AdninSkinDenicker.setEnabled(skinEnabled);
        String skin = skinEnabled ? AdninSkinDenicker.getProfile(name) : "";
        if (skinEnabled && skin.isEmpty() && !AdninSkinDenicker.hasAttempted(name)) return "";
        return skin.isEmpty() ? AdninFeatures.getBotProfile(name) : skin;
    }

    public static void nativeDenickerPublished(String name, String profile) {
        AdninSkinDenicker.markPublished(name, profile);
    }

    public static void nativeMatchStarted() {
        AdninFeatures.matchStarted();
    }

    public static String getOverlayColumnsBedwars() {
        AdninGui4.snapshotOverlayColumnsEditMode();
        return AdninGui4.getStoredColumnsForMode("bedwars");
    }

    public static String getOverlayColumnsSkywars() {
        AdninGui4.snapshotOverlayColumnsEditMode();
        return AdninGui4.getStoredColumnsForMode("skywars");
    }

    public static String getOverlayColumnsDuel() {
        AdninGui4.snapshotOverlayColumnsEditMode();
        return AdninGui4.getStoredColumnsForMode("duel");
    }

    public static String getOverlayColumnsBedwarsduels() {
        AdninGui4.snapshotOverlayColumnsEditMode();
        return AdninGui4.getStoredColumnsForMode("bedwarsduels");
    }

    public static void loadAllOverlayColumnsFromToggles(String string, String string2, String string3, String string4, String string5) {
        if (string != null && string.length() > 0) {
            overlayColumnsBedwars = string;
        } else if (string5 != null && string5.length() > 0) {
            overlayColumnsBedwars = string5;
        }
        if (string2 != null && string2.length() > 0) {
            overlayColumnsSkywars = string2;
        }
        if (string3 != null && string3.length() > 0) {
            overlayColumnsDuel = string3;
        }
        if (string4 != null && string4.length() > 0) {
            overlayColumnsBedwarsduels = string4;
        }
        AdninGui4.loadOverlayColumnsForEditMode();
    }

    public static void setOverlayColumnsFromConfig(String string) {
        int n;
        int n2;
        String[] stringArray = AdninGui4.columnIdsForMode(overlayGamemodeEdit);
        int n3 = stringArray.length;
        if (string == null) {
            string = "";
        }
        for (n2 = 0; n2 < MAX_OVERLAY_COLUMNS; ++n2) {
            AdninGui4.overlayColumnEnabled[n2] = false;
            AdninGui4.overlayColumnOrder[n2] = MAX_OVERLAY_COLUMNS + n2;
        }
        n2 = 0;
        String[] stringArray2 = AdninColumnOrder.enabledIds(overlayGamemodeEdit, string);
        block1: for (n = 0; n < stringArray2.length; ++n) {
            String string2 = AdninGui4.normalizeColumnIdForMode(overlayGamemodeEdit, stringArray2[n].trim());
            if (string2.length() == 0 || "name".equals(string2) || "hp".equals(string2) || "tags".equals(string2)) continue;
            if ("magicRatio".equals(string2)) {
                string2 = "requeuePct";
            }
            for (int i = 0; i < n3; ++i) {
                if (!stringArray[i].equals(string2)) continue;
                AdninGui4.overlayColumnEnabled[i] = true;
                AdninGui4.overlayColumnOrder[i] = n2++;
                continue block1;
            }
        }
        for (n = 0; n < n3; ++n) {
            if (overlayColumnEnabled[n]) continue;
            AdninGui4.overlayColumnOrder[n] = n2++;
        }
    }

    private FontRenderer resolveFontRenderer() {
        if (cachedFontRenderer != null) {
            return cachedFontRenderer;
        }
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft != null && minecraft.fontRendererObj != null) {
                cachedFontRenderer = minecraft.fontRendererObj;
                return cachedFontRenderer;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return null;
    }

    private void drawTextLeft(String string, int n, int n2, int n3) {
        AdninUi.text(AdninLanguage.text(string), n, n2 - 1, n3, 0);
    }

    private void drawTextCentered(String string, int n, int n2, int n3) {
        string = AdninLanguage.text(string);
        AdninUi.text(string, n - AdninUi.width(string, 0) / 2, n2 - 1, n3, 0);
    }

    private int getPanelScroll(int n) {
        if (n == 0) {
            return this.settingsScroll;
        }
        if (n == 1) {
            return this.anticheatScroll;
        }
        if (n == 2) {
            return this.utilsScroll;
        }
        if (n == 3) {
            return this.overlayScroll;
        }
        if (n == 4) {
            return this.chatOverlayScroll;
        }
        if (n == 5) {
            return this.sessionStatsScroll;
        }
        if (n == 6) {
            return this.experimentalScroll;
        }
        return 0;
    }

    private void setPanelScroll(int n, int n2) {
        if (n == 0) {
            this.settingsScroll = n2;
        } else if (n == 1) {
            this.anticheatScroll = this.clampScroll(n2, AC_CONTENT_H, this.viewportHeight() - 5);
        } else if (n == 2) {
            this.utilsScroll = this.clampScroll(n2, AdninGui4.gameplayPanelContentHeight(), this.viewportHeight() - 5);
        } else if (n == 3) {
            this.overlayScroll = n2;
        } else if (n == 4) {
            this.chatOverlayScroll = n2;
        } else if (n == 5) {
            this.sessionStatsScroll = n2;
        } else if (n == 6) {
            this.experimentalScroll = n2;
        }
    }

    private int getPanelContentHeight(int n) {
        if (n == 0) {
            return INTERFACE_CARD_Y + 122;
        }
        if (n == 1) {
            return AC_CONTENT_H;
        }
        if (n == 2) {
            return AdninGui4.gameplayPanelContentHeight();
        }
        if (n == 3) {
            return 128 + AdninGui4.columnIdsForMode(overlayGamemodeEdit).length * 24;
        }
        if (n == 4) {
            return CHAT_THRESH_Y + CHAT_THRESH_H + 8;
        }
        if (n == 5) {
            return AdninGui4.sessionStatsPanelContentHeight();
        }
        if (n == 6) {
            return 72;
        }
        return 0;
    }

    private static int sessionStatsPanelContentHeight() {
        return 328;
    }

    private int clampScroll(int n, int n2, int n3) {
        int n4 = Math.min(0, n3 - n2);
        if (n > 0) {
            n = 0;
        }
        if (n < n4) {
            n = n4;
        }
        return n;
    }

    private boolean isHoveredRect(int n, int n2, int n3, int n4, int n5, int n6) {
        return n5 >= n && n5 <= n + n3 && n6 >= n2 && n6 <= n2 + n4;
    }

    private boolean isRowVisible(int n, int n2, int n3, int n4) {
        return n + n2 > n3 && n < n4;
    }

    private void drawSectionCardClipped(int n, int n2, int n3, int n4, int n5, int n6) {
        if (this.isRowVisible(n2, n4, n5, n6)) this.drawSectionCard(n, n2, n3, n4);
    }

    private void drawContentViewportMask(int n, int n2, int n3, int n4, int n5, int n6) {
        // All descendants are clipped by the real GL scissor in drawScreen.
        // Painting over overflow cannot hide content outside the window.
    }

    private static int gameplayPanelContentHeight() {
        // Include every Utils section, including the Sounds card below the
        // independent Quick Buy Profile copier.  Omitting the lower cards
        // capped scrolling too early and made Bot Denicker/Sounds unreachable
        // in compact windows.
        return UTILS_SOUND_CARD_Y + 38 + 8;
    }

    private void drawScrollBar(int n, int n2, int n3, int n4, int n5) {
        if (n4 <= n3) return;
        int thumb = Math.max(26, n3 * n3 / n4);
        int position = (int)((long)(-n5) * (n3 - thumb) / Math.max(1, n4 - n3));
        AdninUi.round(n, n2 + Math.max(0, Math.min(n3 - thumb, position)), 3, thumb, 1.5f, 0xFF686E7D);
    }

    private void drawThemeItem(int n, int n2, int n3, int n4, String string, boolean bl, int n5, int n6) {
        if (!bl && this.isHoveredRect(n, n2, n3, n4, n5, n6))
            AdninUi.round(n, n2, n3, n4, 7, 0xFF2C3039);
        int color = bl ? 0xFFFFFFFF : 0xFFB1B6C3;
        AdninUi.round(n + 9, n2 + 8, 14, 14, 4, bl ? 0x665C9FFF : 0xFF353A46);
        int icon = 0;
        for (int i = 0; i < THEMES.length; i++) if (THEMES[i].equals(string)) icon = i;
        AdninUi.icon(icon,n + 9,n2 + 8,color);
        this.drawTextLeft(string, n + 32, n2 + 10, color);
    }

    private String maskValue(String string) {
        if (string == null || string.length() == 0) {
            return "";
        }
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < string.length(); ++i) {
            stringBuilder.append('\u2022');
        }
        return stringBuilder.toString();
    }

    private String visibleInput(String source, int width, boolean reveal, boolean focused) {
        source = source == null ? "" : source;
        boolean fontFailure = AdninUi.hasFontFailure();
        for (InputClip cached : inputClips) {
            if (cached != null && cached.width == width && cached.reveal == reveal
                    && cached.focused == focused && cached.fontFailure == fontFailure
                    && cached.source.equals(source)) return cached.visible;
        }
        String value = reveal ? source : this.maskValue(source);
        value = focused ? AdninUi.fitTail(value, width - 20, 0) : AdninUi.fit(value, width - 14, 0);
        if (source.length() <= 2048) {
            inputClips[nextInputClip] = new InputClip(source, width, reveal, focused,
                AdninUi.hasFontFailure(), value);
            nextInputClip = (nextInputClip + 1) % inputClips.length;
        }
        return value;
    }

    private void drawInputField(int n, int n2, int n3, String string, String string2, boolean bl, boolean bl2, int n4, int n5) {
        this.drawTextLeft(string, n + 2, n2 - 12, AdninUi.MUTED);
        boolean hover = this.isHoveredRect(n, n2, n3, 20, n4, n5);
        AdninUi.round(n, n2, n3, 20, 5, bl2 ? AdninUi.ACCENT : AdninUi.BORDER);
        AdninUi.round(n + 1, n2 + 1, n3 - 2, 18, 4, hover ? 0xFF242832 : 0xFF1C1F26);
        if ((string2 == null || string2.length() == 0) && !bl2) {
            this.drawTextLeft("Not configured", n + 7, n2 + 5, 0xFF747C8D);
            return;
        }
        // Show the end while typing, never draw through the reveal/clear controls.
        String value = this.visibleInput(string2, n3, bl, bl2);
        // API keys, URLs, messages and player names are user data, not labels.
        AdninUi.text(value, n + 7, n2 + 4, AdninUi.TEXT, 0);
        if (bl2 && System.currentTimeMillis() / 500L % 2L == 0L) {
            int caret = n + 8 + (int)AdninUi.width(value, 0);
            AdninUi.rect(caret, n2 + 4, caret + 1, n2 + 16, AdninUi.ACCENT);
        }
    }

    private void drawEyeButton(int n, int n2, boolean bl, int n3, int n4) {
        AdninUi.round(n, n2, 20, 20, 5, this.isHoveredRect(n,n2,20,20,n3,n4) ? 0xFF414754 : 0xFF303541);
        AdninUi.round(n + 4, n2 + 6, 12, 8, 4, bl ? AdninUi.ACCENT : AdninUi.MUTED);
        AdninUi.round(n + 8, n2 + 8, 4, 4, 2, 0xFF20242C);
        if (!bl) AdninUi.line(n + 5, n2 + 15, n + 15, n2 + 5, AdninUi.TEXT);
    }

    private void drawClearButton(int n, int n2, int n3, int n4) {
        AdninUi.round(n, n2, 20, 20, 5, this.isHoveredRect(n,n2,20,20,n3,n4) ? 0xFF51373C : 0xFF303541);
        AdninUi.line(n + 7,n2 + 7,n + 13,n2 + 13,0xFFFF7979);
        AdninUi.line(n + 13,n2 + 7,n + 7,n2 + 13,0xFFFF7979);
    }

    private void closeScreenSafe() {
        this.activeInput = 0;
        this.cancelPointerInteraction();
        if (this.closingAt == 0) this.closingAt = System.nanoTime();
    }

    private void cancelPointerInteraction() {
        this.draggingUiScale = false;
        this.draggingHitboxSlider = false;
        this.hitboxSliderTrackX = 0;
        this.hitboxSliderTrackW = 0;
    }

    private void beginKeyboardInput() {
        try {
            if (!Keyboard.isCreated()) return;
            // initGui also runs on F11/resize. Capture the prior owner's state
            // only once, so those calls cannot turn the saved value into true.
            if (!this.keyboardRepeatCaptured) {
                this.keyboardRepeatBefore = Keyboard.areRepeatEventsEnabled();
                this.keyboardRepeatCaptured = true;
            }
            Keyboard.enableRepeatEvents(true);
        } catch (RuntimeException | LinkageError unavailable) { }
    }

    private void endKeyboardInput() {
        try {
            if (this.keyboardRepeatCaptured && Keyboard.isCreated())
                Keyboard.enableRepeatEvents(this.keyboardRepeatBefore);
        } catch (RuntimeException | LinkageError unavailable) { }
        finally { this.keyboardRepeatCaptured = false; }
    }

    @Override
    public void onGuiClosed() {
        AdninFeatures.requestSave();
        this.activeInput = 0;
        java.util.Arrays.fill(this.inputClips, null);
        this.nextInputClip = 0;
        this.cancelPointerInteraction();
        this.endKeyboardInput();
        this.openedAt = 0; this.closingAt = 0; this.lastFrame = 0;
        try { AdninUi.releaseTextures(); }
        catch (RuntimeException | LinkageError unavailable) { /* Retry on the next client lifecycle cleanup. */ }
        super.onGuiClosed();
    }

    private void playUiClickSound() {
        try {
            Object[] objectArray;
            Object object;
            Object object2;
            Object annotatedElement;
            GenericDeclaration genericDeclaration;
            Object object3;
            Object object4 = this.mc;
            Class<?> clazz = null;
            if (object4 != null) {
                clazz = object4.getClass();
            } else {
                clazz = Class.forName("ave");
                object3 = clazz.getMethod("A", new Class[0]);
                object4 = ((Method)object3).invoke(null, new Object[0]);
            }
            if (object4 == null) {
                return;
            }
            object3 = null;
            Method[] methodArray = clazz.getDeclaredMethods();
            for (int i = 0; i < methodArray.length; ++i) {
                genericDeclaration = methodArray[i];
                if (((Method)genericDeclaration).getParameterTypes().length != 0 || ((Method)genericDeclaration).getReturnType() == Void.TYPE || !"P".equals(annotatedElement = ((Method)genericDeclaration).getName()) && !"getSoundHandler".equals(annotatedElement) && ((Method)genericDeclaration).getReturnType().getName().indexOf("SoundHandler") < 0 && !"bqf".equals(((Method)genericDeclaration).getReturnType().getName())) continue;
                ((Method)genericDeclaration).setAccessible(true);
                object2 = ((Method)genericDeclaration).invoke(object4, new Object[0]);
                if (object2 == null) continue;
                object3 = object2;
                break;
            }
            Field[] fieldArray = clazz.getDeclaredFields();
            if (object3 == null) {
                for (int i = 0; i < fieldArray.length; ++i) {
                    annotatedElement = fieldArray[i];
                    if (((Field)annotatedElement).getType() == null || ((Field)annotatedElement).getType().getName().indexOf("SoundHandler") < 0 && !"bqf".equals(((Field)annotatedElement).getType().getName())) continue;
                    ((Field)annotatedElement).setAccessible(true);
                    object3 = ((Field)annotatedElement).get(object4);
                    if (object3 != null) break;
                }
            }
            if (object3 == null) {
                return;
            }
            try {
                genericDeclaration = null;
                try {
                    genericDeclaration = Class.forName("avs");
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
                if (genericDeclaration == null) {
                    try {
                        genericDeclaration = Class.forName("net.minecraft.client.gui.GuiButton");
                    }
                    catch (Throwable throwable) {
                        // empty catch block
                    }
                }
                if (genericDeclaration != null) {
                    annotatedElement = null;
                    object2 = ((Class)genericDeclaration).getConstructors();
                    for (int i = 0; i < ((Constructor<?>[])object2).length; ++i) {
                        Constructor<?> constructor = ((Constructor<?>[])object2)[i];
                        object = constructor.getParameterTypes();
                        objectArray = new Object[((Class<?>[])object).length];
                        boolean bl = true;
                        for (int j = 0; j < ((Class<?>[])object).length; ++j) {
                            if (((Class<?>[])object)[j] == Integer.TYPE || ((Class<?>[])object)[j] == Integer.class) {
                                objectArray[j] = 0;
                                continue;
                            }
                            if (((Class<?>[])object)[j] == Boolean.TYPE || ((Class<?>[])object)[j] == Boolean.class) {
                                objectArray[j] = Boolean.TRUE;
                                continue;
                            }
                            if (((Class<?>[])object)[j] == String.class) {
                                objectArray[j] = "";
                                continue;
                            }
                            bl = false;
                            break;
                        }
                        if (!bl) continue;
                        annotatedElement = constructor.newInstance(objectArray);
                        break;
                    }
                    if (annotatedElement != null) {
                        Method[] methodArray2 = ((Class)genericDeclaration).getDeclaredMethods();
                        for (int i = 0; i < methodArray2.length; ++i) {
                            object = methodArray2[i];
                            objectArray = ((Method)object).getParameterTypes();
                            if (objectArray.length != 1 || !((Class)objectArray[0]).isInstance(object3) || ((Method)object).getReturnType() != Void.TYPE) continue;
                            ((Method)object).setAccessible(true);
                            ((Method)object).invoke(annotatedElement, object3);
                            return;
                        }
                    }
                }
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            genericDeclaration = null;
            annotatedElement = null;
            try {
                genericDeclaration = Class.forName("jy");
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            try {
                annotatedElement = Class.forName("bqh");
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            if (genericDeclaration == null || annotatedElement == null) {
                try {
                    genericDeclaration = Class.forName("net.minecraft.util.ResourceLocation");
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
                try {
                    annotatedElement = Class.forName("net.minecraft.client.audio.PositionedSoundRecord");
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
            }
            if (genericDeclaration == null || annotatedElement == null) {
                return;
            }
            object2 = ((Class)genericDeclaration).getConstructors();
            Object var9_21 = null;
            for (int i = 0; i < ((Constructor<?>[])object2).length; ++i) {
                object = ((Constructor<?>[])object2)[i];
                objectArray = ((Constructor)object).getParameterTypes();
                if (objectArray.length == 1 && objectArray[0] == String.class) {
                    var9_21 = ((Constructor)object).newInstance("gui.button.press");
                    break;
                }
                if (objectArray.length != 2 || objectArray[0] != String.class || objectArray[1] != String.class) continue;
                var9_21 = ((Constructor)object).newInstance("gui", "button.press");
                break;
            }
            if (var9_21 == null) {
                return;
            }
            Object object5 = null;
            object = ((Class<?>)annotatedElement).getDeclaredMethods();
            for (int i = 0; i < ((Method[])object).length; ++i) {
                Object object6 = ((Method[])object)[i];
                if (!Modifier.isStatic(((Method)object6).getModifiers())) continue;
                Class<?>[] classArray = ((Method)object6).getParameterTypes();
                if (classArray.length == 2 && classArray[0] == genericDeclaration && (classArray[1] == Float.TYPE || classArray[1] == Float.class) && ((Class<?>)annotatedElement).isAssignableFrom(((Method)object6).getReturnType())) {
                    ((Method)object6).setAccessible(true);
                    object5 = ((Method)object6).invoke(null, var9_21, new Float(1.0f));
                    break;
                }
                if (classArray.length != 1 || classArray[0] != genericDeclaration || !((Class<?>)annotatedElement).isAssignableFrom(((Method)object6).getReturnType())) continue;
                ((Method)object6).setAccessible(true);
                object5 = ((Method)object6).invoke(null, var9_21);
                break;
            }
            if (object5 == null) {
                return;
            }
            Method[] methodArray3 = object3.getClass().getDeclaredMethods();
            for (int i = 0; i < methodArray3.length; ++i) {
                Method method = methodArray3[i];
                Class<?>[] classArray = method.getParameterTypes();
                if (classArray.length != 1 || !classArray[0].isInstance(object5)) continue;
                method.setAccessible(true);
                method.invoke(object3, object5);
                return;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private void drawToggle(int n, int n2, int n3, String string, boolean bl, boolean bl2, int n4, int n5) {
        this.drawCardToggleRow(n, n2, n3, string, bl, bl2, n4, n5);
    }

    private void drawCardToggleRow(int n, int n2, int n3, String string, boolean bl, boolean bl2, int n4, int n5) {
        boolean hover = bl2 && this.isHoveredRect(n,n2,n3,22,n4,n5);
        AdninUi.round(n,n2,n3,22,6, hover ? 0xFF363B47 : 0xFF2C3039);
        this.drawTextLeft(AdninUi.fit(AdninLanguage.text(string),n3 - 52,0),n + 8,n2 + 6,bl2 ? AdninUi.TEXT : AdninUi.MUTED);
        drawSwitch(n + n3 - 34,n2 + 5,28,12,bl,bl2,selectedTheme + ":" + string);
    }

    private void drawPanelSectionTitle(int n, int n2, String string, int n3, int n4, int n5) {
        if (this.isRowVisible(n2, 14, n4, n5)) {
            this.drawTextLeft(string, n, n2, n3);
        }
    }

    private void drawPanelDivider(int n, int n2, int n3, int n4, int n5) {
        if (this.isRowVisible(n2, 2, n4, n5)) {
            AdninUi.rect((int)n, (int)n2, (int)(n + n3), (int)(n2 + 2), (int)-13816531);
        }
    }

    private boolean hitCardSwitch(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        return n7 >= n4 && n7 < n5 && this.isRowVisible(n3, 22, n4, n5) && this.isHoveredRect(n + n2 - 34, n3 + 5, 28, 12, n6, n7);
    }

    private boolean hitChip(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8) {
        return n8 >= n5 && n8 < n6 && this.isRowVisible(n2, n4, n5, n6) && this.isHoveredRect(n, n2, n3, n4, n7, n8);
    }

    private void drawSectionCard(int n, int n2, int n3, int n4) {
        AdninUi.round(n, n2, n3, n4, 9, AdninUi.BORDER);
        AdninUi.round(n + 0.7f, n2 + 0.7f, n3 - 1.4f, n4 - 1.4f, 8.5f, AdninUi.CARD);
    }

    private void drawMiniSwitch(int n, int n2, int n3, int n4, boolean bl, boolean bl2, int n5, int n6) {
        drawSwitch(n,n2,n3,n4,bl,bl2,selectedTheme + ":mini:" + n + ":" + (n2 - visibleScroll()));
    }

    private static String holdRdKeyLabel() {
        if (holdRdKeyCode <= 0) {
            return "NONE";
        }
        try {
            String string = Keyboard.getKeyName((int)holdRdKeyCode);
            if (string != null && string.length() > 0) {
                return string;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return "#" + holdRdKeyCode;
    }

    private void drawChipToggle(int n, int n2, int n3, int n4, String string, boolean bl, boolean bl2, int n5, int n6) {
        this.drawChipToggle(n, n2, n3, n4, string, bl, bl2, n5, n6, true);
    }

    private void drawChipToggle(int n, int n2, int n3, int n4, String string, boolean bl, boolean bl2, int n5, int n6, boolean bl3) {
        boolean hover = bl2 && this.isHoveredRect(n,n2,n3,n4,n5,n6);
        AdninUi.round(n,n2,n3,n4,6,hover ? 0xFF363C47 : 0xFF2C3039);
        boolean small = n3 < 118;
        int reserve = bl3 ? (small ? 15 : 38) : 8;
        this.drawTextLeft(AdninUi.fit(AdninLanguage.text(string),n3 - reserve - 8,0), n + 8,n2 + (n4 - 11) / 2,bl2 ? AdninUi.TEXT : 0xFF727987);
        if (bl3) {
            if (small) AdninUi.round(n + n3 - 12,n2 + n4 / 2f - 2.5f,5,5,2.5f,bl && bl2 ? AdninUi.GREEN : 0xFF687081);
            else drawSwitch(n + n3 - 31,n2 + (n4 - 12) / 2f,24,12,bl,bl2,selectedTheme + ":" + string);
        }
    }

    private void drawValueChip(int n, int n2, int n3, int n4, String string, int n5, int n6) {
        AdninUi.round(n,n2,n3,n4,6,this.isHoveredRect(n,n2,n3,n4,n5,n6) ? 0xFF35445D : 0xFF2A3548);
        this.drawTextLeft(AdninUi.fit(AdninLanguage.text(string),n3 - 16,0),n + 8,n2 + (n4 - 11) / 2,0xFFA7CCFF);
    }

    private void drawActionChip(int n, int n2, int n3, int n4, String string, int n5, int n6) {
        AdninUi.round(n,n2,n3,n4,6,this.isHoveredRect(n,n2,n3,n4,n5,n6) ? 0xFF414958 : 0xFF323947);
        this.drawTextCentered(AdninUi.fit(AdninLanguage.text(string),n3 - 12,0),n + n3 / 2,n2 + (n4 - 11) / 2,AdninUi.TEXT);
    }

    // Small action controls have one centered label, never a toggle's ON/OFF state.
    // The drawing primitives can be recorded by the offline GUI fixture without GL.
    protected void drawIntervalActionRect(int left, int top, int right, int bottom, int color) {
        AdninUi.round(left,top,right-left,bottom-top,5,color);
    }

    protected void drawIntervalActionLabel(String label, int centerX, int y, int color) {
        this.drawTextCentered(label, centerX, y, color);
    }

    private boolean hitIntervalAction(int x, int y, int top, int bottom, int mouseX, int mouseY) {
        return mouseY >= top && mouseY < bottom && mouseX >= x && mouseX < x + 22
            && mouseY >= y && mouseY < y + 22;
    }

    private void drawIntervalAction(int x, int y, String label, boolean enabled,
            int top, int bottom, int mouseX, int mouseY) {
        if (!this.isRowVisible(y, 22, top, bottom)) return;
        boolean hovered = enabled && this.hitIntervalAction(x, y, top, bottom, mouseX, mouseY);
        int background = !enabled ? 0xFF272C35 : hovered ? 0xFF3E4D65 : 0xFF323947;
        this.drawIntervalActionRect(x, Math.max(y, top), x + 22, Math.min(y + 22, bottom),
            enabled ? CLR_ACTION : CLR_MUTED);
        int innerTop = Math.max(y + 1, top), innerBottom = Math.min(y + 21, bottom);
        if (innerBottom > innerTop)
            this.drawIntervalActionRect(x + 1, innerTop, x + 21, innerBottom, background);
        if (y + 7 >= top && y + 16 <= bottom)
            this.drawIntervalActionLabel(label, x + 11, y + 7, enabled ? CLR_ACTION : -10987432);
    }

    private void drawAnticheatIntervalButtons(int x, int baseY, int width,
            int top, int bottom, int mouseX, int mouseY) {
        int y = baseY + AC_INTERVAL_Y;
        this.drawIntervalAction(x + width - 56, y, "-",
            AdninAnticheat.enabled && AdninAnticheat.intervalSeconds > 0, top, bottom, mouseX, mouseY);
        this.drawIntervalAction(x + width - 28, y, "+",
            AdninAnticheat.enabled && AdninAnticheat.intervalSeconds < 60, top, bottom, mouseX, mouseY);
    }

    private int hitboxSliderTrackX(int n) {
        return n + 110;
    }

    private int hitboxSliderTrackW(int n) {
        return Math.max(40, n - 148);
    }

    private int hitboxThicknessFromMouse(int n, int n2, int n3) {
        int n4 = 10;
        int n5 = Math.max(1, n2 - n4);
        int n6 = n3 - n - n4 / 2;
        if (n6 < 0) {
            n6 = 0;
        }
        if (n6 > n5) {
            n6 = n5;
        }
        float f = (float)n6 / (float)n5;
        return AdninGui4.normalizeHitboxThickness(1 + Math.round(f * 9.0f));
    }

    private void drawHorizontalSlider(int n, int n2, int n3, int n4, int n5, int n6, int n7, boolean bl, boolean bl2, int n8, int n9) {
        int n10;
        int n11 = n2 + n4 / 2 - 1;
        int n12 = n10 = bl ? -15066598 : -15592942;
        if (bl && (bl2 || this.isHoveredRect(n, n2, n3, n4, n8, n9))) {
            n10 = -14408668;
        }
        AdninUi.round(n,n11,n3,3,1.5f,0xFF474F5E);
        float f = n6 > n5 ? (float)(n7 - n5) / (float)(n6 - n5) : 0.0f;
        int n13 = Math.min(10, Math.max(6, n4 - 4));
        int n14 = n2 + (n4 - n13) / 2;
        int n15 = n + n13 / 2 + (int)(f * (float)Math.max(1, n3 - n13));
        int n16 = bl ? -11167267 : -11513776;
        AdninUi.round(n,n11,Math.max(0,n15-n),3,1.5f,AdninUi.ACCENT);
        AdninUi.round(n15-n13/2f,n14,n13,n13,n13/2f,bl?AdninUi.TEXT:AdninUi.MUTED);
    }

    private void drawSegmentedChoice(int n, int n2, int n3, int n4, String[] stringArray, int n5, boolean bl, int n6, int n7) {
        int n8 = 2;
        int n9 = (n3 - n8 * (stringArray.length - 1)) / stringArray.length;
        for (int i = 0; i < stringArray.length; ++i) {
            int n10;
            boolean bl2;
            int n11 = n + i * (n9 + n8);
            boolean bl3 = bl2 = i == n5;
            int n12 = n10 = bl ? (bl2 ? -15460320 : -15724528) : -15987700;
            if (bl && this.isHoveredRect(n11, n2, n9, n4, n6, n7)) {
                n10 = bl2 ? -15065040 : -15329770;
            }
            AdninUi.round(n11,n2,n9,n4,5,bl && bl2 ? 0xFF42516B : 0xFF2B3039);
            int n13 = bl ? (bl2 ? AdninUi.TEXT : AdninUi.MUTED) : 0xFF707787;
            this.drawTextCentered(AdninUi.fit(AdninLanguage.text(stringArray[i]),n9-10,0),n11+n9/2,n2+(n4-11)/2,n13);
        }
    }

    private void drawResourceTimerPanel(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8, int n9) {
        int n10;
        int n11;
        int n12 = n2 + 202;
        this.drawPanelSectionTitle(n, n12, "Resource Timer", n8, n4, n5);
        int n13 = n2 + 218;
        if (!this.isRowVisible(n13, 96, n4, n5)) {
            return;
        }
        this.drawSectionCardClipped(n, n13, n3, 96, n4, n5);
        if (this.isRowVisible(n13 + 6, 18, n4, n5)) {
            this.drawTextLeft("Enabled", n + 8, n13 + 7, overlayResourceHeader ? -11154347 : -3386027);
            this.drawMiniSwitch(n + n3 - 34, n13 + 6, 28, 12, overlayResourceHeader, true, n6, n7);
        }
        int n14 = n13 + 24;
        int n15 = 22;
        int n16 = (n3 - 12) / 2;
        boolean bl = overlayResourceHeader;
        if (this.isRowVisible(n14, n15, n4, n5)) {
            this.drawChipToggle(n + 4, n14, n16, n15, "Diamonds", overlayResourceDiamonds, bl, n6, n7);
            this.drawChipToggle(n + 8 + n16, n14, n16, n15, "Emeralds", overlayResourceEmeralds, bl, n6, n7);
        }
        if (this.isRowVisible(n13 + 50, 12, n4, n5)) {
            this.drawTextLeft("Display", n + 8, n13 + 50, n9);
        }
        if (this.isRowVisible(n11 = n13 + 62, n10 = 20, n4, n5)) {
            boolean bl2 = overlayResourceLocation == null || !"scoreboard".equals(overlayResourceLocation);
            this.drawSegmentedChoice(n + 4, n11, n3 - 8, n10, new String[]{"Tab", "Scoreboard"}, bl2 ? 0 : 1, bl, n6, n7);
        }
    }

    private boolean handleResourceTimerPanelClicks(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        int n8 = n2 + 218;
        int n9 = 96;
        if (this.isRowVisible(n8, 18, n4, n5) && this.isHoveredRect(n + n3 - 34, n8 + 6, 28, 12, n6, n7)) {
            overlayResourceHeader = !overlayResourceHeader;
            return true;
        }
        if (!overlayResourceHeader) {
            return false;
        }
        int n10 = n8 + 24;
        int n11 = 22;
        int n12 = (n3 - 12) / 2;
        if (this.isRowVisible(n10, n11, n4, n5) && this.isHoveredRect(n + 4, n10, n12, n11, n6, n7)) {
            overlayResourceDiamonds = !overlayResourceDiamonds;
            return true;
        }
        if (this.isRowVisible(n10, n11, n4, n5) && this.isHoveredRect(n + 8 + n12, n10, n12, n11, n6, n7)) {
            overlayResourceEmeralds = !overlayResourceEmeralds;
            return true;
        }
        int n13 = n8 + 62;
        int n14 = 20;
        int n15 = (n3 - 8 - 2) / 2;
        if (this.isRowVisible(n13, n14, n4, n5) && this.isHoveredRect(n + 4, n13, n15, n14, n6, n7)) {
            overlayResourceLocation = "tab";
            return true;
        }
        if (this.isRowVisible(n13, n14, n4, n5) && this.isHoveredRect(n + 4 + n15 + 2, n13, n15, n14, n6, n7)) {
            overlayResourceLocation = "scoreboard";
            return true;
        }
        return false;
    }

    private void drawUtilsPanels(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8, int n9) {
        int n10;
        int n11;
        int n12 = n2 + 4;
        if (this.isRowVisible(n12, 186, n4, n5)) {
            int n13;
            int n14;
            this.drawSectionCardClipped(n, n12, n3, 186, n4, n5);
            n11 = 22;
            n10 = (n3 - 12) / 2;
            int n15 = n12 + 8;
            int n16 = n12 + 36;
            int n17 = n12 + 64;
            int n18 = n12 + 122;
            int n19 = n12 + 154;
            if (this.isRowVisible(n15, n11, n4, n5)) {
                this.drawChipToggle(n + 4, n15, n10, n11, "AutoWho", autoWho, true, n6, n7);
                this.drawChipToggle(n + 8 + n10, n15, n10, n11, "Party Detector", partyDetector, true, n6, n7);
            }
            if (this.isRowVisible(n16, n11, n4, n5)) {
                this.drawChipToggle(n + 4, n16, n10, n11, "Number Denicker", numberDenicker, true, n6, n7);
                this.drawChipToggle(n + 8 + n10, n16, n10, n11, "Auto GL", autoGL, true, n6, n7);
            }
            if (this.isRowVisible(n17, n11, n4, n5)) {
                this.drawChipToggle(n + 4, n17, n10, n11, "Fast Buy", fastBuy, true, n6, n7);
                this.drawChipToggle(n + 8 + n10, n17, n10, n11, "Colored Hitboxes", coloredHitboxes, true, n6, n7);
            }
            if (this.isRowVisible(n14 = n12 + 90, n13 = 16, n4, n5)) {
                int n20 = this.hitboxSliderTrackX(n);
                int n21 = this.hitboxSliderTrackW(n3);
                int n22 = coloredHitboxes ? n9 : -10987432;
                this.drawTextLeft("Thickness", n + 8, n14 + 5, n22);
                this.drawHorizontalSlider(n20, n14, n21, n13, 1, 10, hitboxThickness, coloredHitboxes, this.draggingHitboxSlider, n6, n7);
                this.drawTextLeft(AdninGui4.normalizeHitboxThickness(hitboxThickness) + "px", n + n3 - 34, n14 + 5, coloredHitboxes ? -11167267 : -10987432);
            }
            if (this.isRowVisible(n18, n11, n4, n5)) {
                this.drawChipToggle(n + 4, n18, n10, n11, "Dragon Hitbox", dragonHitboxes, true, n6, n7);
                this.drawChipToggle(n + 8 + n10, n18, n10, n11, "Shot Distance", arrowDistance, true, n6, n7);
            }
            if (this.isRowVisible(n19, n11, n4, n5)) {
                this.drawChipToggle(n + 4, n19, n10, n11, "Bed DC Timer", bedDisconnectTimer, true, n6, n7);
                this.drawChipToggle(n + 8 + n10, n19, n10, n11, "Trade Indicator", tradeIndicator, true, n6, n7);
            }
        }
        this.drawResourceTimerPanel(n, n2, n3, n4, n5, n6, n7, n8, n9);
        n11 = n2 + 326;
        this.drawPanelSectionTitle(n, n11, "GL Message", n8, n4, n5);
        n10 = n2 + 342;
        if (this.isRowVisible(n10, 34, n4, n5)) {
            this.drawInputField(n, n10, n3 - 28, "", gl_message, true, this.activeInput == 4, n6, n7);
            this.drawClearButton(n + n3 - 24, n10, n6, n7);
        }
        this.drawPanelSectionTitle(n, n2 + UTILS_BOT_TITLE_Y, "Nickname Lookup", n8, n4, n5);
        this.drawSectionCardClipped(n, n2 + UTILS_BOT_CARD_Y, n3, UTILS_BOT_CARD_H, n4, n5);
        if (this.isRowVisible(n2 + UTILS_BOT_TOGGLE_Y, 22, n4, n5)) {
            this.drawChipToggle(n + 4, n2 + UTILS_BOT_TOGGLE_Y, n3 - 8, 22, "Bot Denicker", botDenicker, true, n6, n7);
        }
        if (this.isRowVisible(n2 + UTILS_BOT_URL_Y - 10, 34, n4, n5)) {
            this.drawInputField(n + 4, n2 + UTILS_BOT_URL_Y, n3 - 36, "API URL", botDenickerUrl, true, this.activeInput == 9, n6, n7);
            this.drawClearButton(n + n3 - 24, n2 + UTILS_BOT_URL_Y, n6, n7);
        }
        this.drawPanelSectionTitle(n, n2 + UTILS_SOUND_TITLE_Y, "Sounds", n8, n4, n5);
        this.drawSectionCardClipped(n, n2 + UTILS_SOUND_CARD_Y, n3, 38, n4, n5);
        if (this.isRowVisible(n2 + UTILS_SOUND_TOGGLE_Y, 22, n4, n5)) {
            this.drawChipToggle(n + 4, n2 + UTILS_SOUND_TOGGLE_Y, n3 - 8, 22, "Client Side Sounds", clientSideSounds, true, n6, n7);
        }
        this.drawPanelSectionTitle(n, n2 + UTILS_PROFILE_TITLE_Y, AdninLanguage.text("Bed Wars Shop Layout"), n8, n4, n5);
        this.drawSectionCardClipped(n, n2 + UTILS_PROFILE_CARD_Y, n3, UTILS_PROFILE_CARD_H, n4, n5);
        if (this.isRowVisible(n2 + UTILS_PROFILE_TOGGLE_Y, 22, n4, n5)) {
            this.drawChipToggle(n + 4, n2 + UTILS_PROFILE_TOGGLE_Y, n3 - 8, 22,
                "Shop Layout Copier", quickbuyProfileCopier, true, n6, n7);
        }
        if (this.isRowVisible(n2 + UTILS_PROFILE_FIELD_Y - 10, 34, n4, n5)) {
            int fieldWidth = Math.max(80, n3 - 106);
            this.drawInputField(n + 4, n2 + UTILS_PROFILE_FIELD_Y, fieldWidth,
                "Target player", quickbuyProfilePlayer, true, this.activeInput == 11, n6, n7);
            boolean enabled = quickbuyProfileCopier && quickbuyProfilePlayer != null
                && quickbuyProfilePlayer.trim().length() > 0;
            this.drawActionChip(n + fieldWidth + 12, n2 + UTILS_PROFILE_FIELD_Y, n3 - fieldWidth - 16, 20,
                "Load Layout", n6, n7);
            if (!enabled) {
                // Keep the action visibly muted while the copier is disabled or
                // no player name has been entered. The click handler also gates
                // the request, so this never sends a command accidentally.
                AdninUi.rect(n + fieldWidth + 12, n2 + UTILS_PROFILE_FIELD_Y,
                    n + n3 - 4, n2 + UTILS_PROFILE_FIELD_Y + 20, 0x33151A22);
            }
            String profileMessage = AdninQuickBuyProfile.message();
            if (profileMessage != null && profileMessage.length() > 0
                    && !AdninQuickBuyProfile.IDLE.equals(AdninQuickBuyProfile.state())) {
                this.drawTextLeft(AdninUi.fit(AdninLanguage.text(profileMessage), n3 - 8, 0),
                    n + 4, n2 + UTILS_PROFILE_FIELD_Y + 24,
                    AdninQuickBuyProfile.FAILED.equals(AdninQuickBuyProfile.state())
                        ? 0xFFFF7777 : AdninUi.MUTED);
            }
        }
    }

    private boolean handleUtilsPanelClicks(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        int n8 = n2 + 4;
        int n9 = 22;
        int n10 = (n3 - 12) / 2;
        int n11 = n8 + 8;
        int n12 = n8 + 36;
        int n13 = n8 + 64;
        int n14 = n8 + 122;
        int n15 = n8 + 154;
        if (this.hitChip(n + 4, n11, n10, n9, n4, n5, n6, n7)) {
            autoWho = !autoWho;
            listeningHoldRdKey = false;
            listeningQuickbuyIndex = -1;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n11, n10, n9, n4, n5, n6, n7)) {
            partyDetector = !partyDetector;
            listeningHoldRdKey = false;
            listeningQuickbuyIndex = -1;
            return true;
        }
        if (this.hitChip(n + 4, n12, n10, n9, n4, n5, n6, n7)) {
            numberDenicker = !numberDenicker;
            listeningHoldRdKey = false;
            listeningQuickbuyIndex = -1;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n12, n10, n9, n4, n5, n6, n7)) {
            autoGL = !autoGL;
            listeningHoldRdKey = false;
            listeningQuickbuyIndex = -1;
            return true;
        }
        if (this.hitChip(n + 4, n13, n10, n9, n4, n5, n6, n7)) {
            fastBuy = !fastBuy;
            listeningHoldRdKey = false;
            listeningQuickbuyIndex = -1;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n13, n10, n9, n4, n5, n6, n7)) {
            coloredHitboxes = !coloredHitboxes;
            listeningHoldRdKey = false;
            listeningQuickbuyIndex = -1;
            return true;
        }
        if (coloredHitboxes) {
            int n16;
            int n17 = n8 + 90;
            int n18 = 16;
            int n19 = this.hitboxSliderTrackX(n);
            if (this.hitChip(n19, n17, n16 = this.hitboxSliderTrackW(n3), n18, n4, n5, n6, n7)) {
                hitboxThickness = this.hitboxThicknessFromMouse(n19, n16, n6);
                this.draggingHitboxSlider = true;
                this.hitboxSliderTrackX = n19;
                this.hitboxSliderTrackW = n16;
                listeningHoldRdKey = false;
                listeningQuickbuyIndex = -1;
                return true;
            }
        }
        if (this.hitChip(n + 4, n14, n10, n9, n4, n5, n6, n7)) {
            dragonHitboxes = !dragonHitboxes;
            listeningHoldRdKey = false;
            listeningQuickbuyIndex = -1;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n14, n10, n9, n4, n5, n6, n7)) {
            arrowDistance = !arrowDistance;
            listeningHoldRdKey = false;
            listeningQuickbuyIndex = -1;
            return true;
        }
        if (this.hitChip(n + 4, n15, n10, n9, n4, n5, n6, n7)) {
            bedDisconnectTimer = !bedDisconnectTimer;
            listeningHoldRdKey = false;
            listeningQuickbuyIndex = -1;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n15, n10, n9, n4, n5, n6, n7)) {
            tradeIndicator = !tradeIndicator;
            listeningHoldRdKey = false;
            listeningQuickbuyIndex = -1;
            return true;
        }
        if (this.hitChip(n + 4, n2 + UTILS_BOT_TOGGLE_Y, n3 - 8, 22, n4, n5, n6, n7)) {
            botDenicker = !botDenicker;
            return true;
        }
        if (this.hitChip(n + 4, n2 + UTILS_SOUND_TOGGLE_Y, n3 - 8, 22, n4, n5, n6, n7)) {
            clientSideSounds = !clientSideSounds;
            return true;
        }
        if (this.hitChip(n + 4, n2 + UTILS_PROFILE_TOGGLE_Y, n3 - 8, 22, n4, n5, n6, n7)) {
            quickbuyProfileCopier = !quickbuyProfileCopier;
            if (!quickbuyProfileCopier) quickbuyProfileLoadRequested = false;
            return true;
        }
        int profileFieldWidth = Math.max(80, n3 - 106);
        if (this.isHoveredRect(n + 4, n2 + UTILS_PROFILE_FIELD_Y, profileFieldWidth, 20, n6, n7)) {
            this.activeInput = 11;
            return true;
        }
        if (this.hitChip(n + profileFieldWidth + 12, n2 + UTILS_PROFILE_FIELD_Y,
                n3 - profileFieldWidth - 16, 20, n4, n5, n6, n7)) {
            requestQuickbuyProfileLoad();
            return true;
        }
        return this.handleResourceTimerPanelClicks(n, n2, n3, n4, n5, n6, n7);
    }

    private void drawExperimentalPanels(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8, int n9) {
        int n10 = n2 + 4;
        int n11 = 56;
        if (this.isRowVisible(n10, n11, n4, n5)) {
            this.drawSectionCard(n, n10, n3, n11);
            this.drawChipToggle(n + 4, n10 + 8, n3 - 8, 22, "Party Detector IDs", partyQueueDetector, true, n6, n7);
            this.drawTextLeft("Queue party size via spawn entity IDs", n + 8, n10 + 36, n9);
        }
    }

    private boolean handleExperimentalPanelClicks(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        int n8 = n2 + 4;
        if (this.hitChip(n + 4, n8 + 8, n3 - 8, 22, n4, n5, n6, n7)) {
            partyQueueDetector = !partyQueueDetector;
            return true;
        }
        return false;
    }

    private void drawAnticheatPanels(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8, int n9) {
        boolean enabled = AdninAnticheat.enabled;
        int main = n2 + AC_MAIN_Y;
        this.drawSectionCardClipped(n, main, n3, AC_MAIN_H, n4, n5);
        if (this.isRowVisible(main, 22, n4, n5)) {
            this.drawTextLeft("Enabled", n + 8, main + 7, enabled ? CLR_ON : CLR_OFF);
            this.drawMiniSwitch(n + n3 - 34, main + 6, 28, 12, enabled, true, n6, n7);
        }
        if (this.isRowVisible(main + 24, 22, n4, n5))
            this.drawChipToggle(n + 4, main + 24, n3 - 8, 22, "Flag Sound", AdninAnticheat.flagSound, enabled, n6, n7);
        this.drawPanelSectionTitle(n, n2 + 56, "Checks", n9, n4, n5);
        this.drawSectionCardClipped(n, n2 + AC_CHECKS_Y, n3, AC_CHECKS_H, n4, n5);
        int half = (n3 - 12) / 2;
        if (this.isRowVisible(n2 + 76, 22, n4, n5)) {
            this.drawChipToggle(n + 4, n2 + 76, half, 22, "Autoblock", AdninAnticheat.autoBlock, enabled, n6, n7);
            this.drawChipToggle(n + 8 + half, n2 + 76, half, 22, "NoFall", AdninAnticheat.noFall, enabled, n6, n7);
        }
        if (this.isRowVisible(n2 + 104, 22, n4, n5)) {
            this.drawChipToggle(n + 4, n2 + 104, half, 22, "NoSlow", AdninAnticheat.noSlow, enabled, n6, n7);
            this.drawChipToggle(n + 8 + half, n2 + 104, half, 22, "Scaffold", AdninAnticheat.scaffold, enabled, n6, n7);
        }
        if (this.isRowVisible(n2 + 132, 22, n4, n5))
            this.drawChipToggle(n + 4, n2 + 132, n3 - 8, 22, "Legit Scaffold", AdninAnticheat.legitScaffold, enabled, n6, n7);
        this.drawPanelSectionTitle(n, n2 + 172, "Alerts", n9, n4, n5);
        this.drawSectionCardClipped(n, n2 + 188, n3, 92, n4, n5);
        String[] labels = {"Ignore teammates", "Only Atlas suspect", "Auto report"};
        boolean[] values = {AdninAnticheat.ignoreTeammates, AdninAnticheat.atlasOnly, AdninAnticheat.autoReport};
        for (int row = 0; row < labels.length; row++) {
            int y = n2 + 196 + row * 28;
            if (this.isRowVisible(y, 22, n4, n5))
                this.drawChipToggle(n + 4, y, n3 - 8, 22, labels[row], values[row], enabled, n6, n7);
        }
        this.drawSectionCardClipped(n, n2 + AC_INTERVAL_Y - 8, n3, 40, n4, n5);
        if (this.isRowVisible(n2 + AC_INTERVAL_Y, 22, n4, n5)) {
            this.drawTextLeft(AdninLanguage.format("Flag interval: %ss", AdninAnticheat.intervalSeconds), n + 8, n2 + AC_INTERVAL_Y + 7, enabled ? n9 : -10987432);
            this.drawAnticheatIntervalButtons(n, n2, n3, n4, n5, n6, n7);
        }
        if (this.isRowVisible(n2 + AC_IGNORED_INPUT_Y - 10, 34, n4, n5)) {
            this.drawInputField(n + 4, n2 + AC_IGNORED_INPUT_Y, n3 - 36, "Ignored players (comma separated)", AdninAnticheat.ignoredPlayers, true, this.activeInput == 10, n6, n7);
            this.drawClearButton(n + n3 - 24, n2 + AC_IGNORED_INPUT_Y, n6, n7);
        }
    }

    private boolean handleAnticheatPanelClicks(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        if (this.hitCardSwitch(n, n3, n2 + AC_MAIN_Y, n4, n5, n6, n7)) {
            AdninAnticheat.enabled = !AdninAnticheat.enabled;
            return true;
        }
        if (!AdninAnticheat.enabled) return false;
        int half = (n3 - 12) / 2;
        if (this.hitChip(n + 4, n2 + 28, n3 - 8, 22, n4, n5, n6, n7)) AdninAnticheat.flagSound = !AdninAnticheat.flagSound;
        else if (this.hitChip(n + 4, n2 + 76, half, 22, n4, n5, n6, n7)) AdninAnticheat.autoBlock = !AdninAnticheat.autoBlock;
        else if (this.hitChip(n + 8 + half, n2 + 76, half, 22, n4, n5, n6, n7)) AdninAnticheat.noFall = !AdninAnticheat.noFall;
        else if (this.hitChip(n + 4, n2 + 104, half, 22, n4, n5, n6, n7)) AdninAnticheat.noSlow = !AdninAnticheat.noSlow;
        else if (this.hitChip(n + 8 + half, n2 + 104, half, 22, n4, n5, n6, n7)) AdninAnticheat.scaffold = !AdninAnticheat.scaffold;
        else if (this.hitChip(n + 4, n2 + 132, n3 - 8, 22, n4, n5, n6, n7)) AdninAnticheat.legitScaffold = !AdninAnticheat.legitScaffold;
        else if (this.hitChip(n + 4, n2 + 196, n3 - 8, 22, n4, n5, n6, n7)) AdninAnticheat.ignoreTeammates = !AdninAnticheat.ignoreTeammates;
        else if (this.hitChip(n + 4, n2 + 224, n3 - 8, 22, n4, n5, n6, n7)) AdninAnticheat.atlasOnly = !AdninAnticheat.atlasOnly;
        else if (this.hitChip(n + 4, n2 + 252, n3 - 8, 22, n4, n5, n6, n7)) AdninAnticheat.autoReport = !AdninAnticheat.autoReport;
        else if (AdninAnticheat.intervalSeconds > 0 && this.hitIntervalAction(n + n3 - 56, n2 + AC_INTERVAL_Y, n4, n5, n6, n7)) AdninAnticheat.intervalSeconds--;
        else if (AdninAnticheat.intervalSeconds < 60 && this.hitIntervalAction(n + n3 - 28, n2 + AC_INTERVAL_Y, n4, n5, n6, n7)) AdninAnticheat.intervalSeconds++;
        else return false;
        return true;
    }

    private boolean handleAnticheatInputClicks(int x, int baseY, int width,
            int top, int bottom, int mouseX, int mouseY) {
        int y = baseY + AC_IGNORED_INPUT_Y;
        if (this.hitChip(x + 4, y, width - 36, 20, top, bottom, mouseX, mouseY)) {
            this.activeInput = 10;
            return true;
        }
        this.activeInput = 0;
        if (this.hitChip(x + width - 24, y, 20, 20, top, bottom, mouseX, mouseY)) {
            AdninAnticheat.ignoredPlayers = "";
            return true;
        }
        return false;
    }

    private void drawSettingsApiPanel(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8, int n9) {
        int n10 = n2 + 16;
        int n11 = 228;
        this.drawPanelSectionTitle(n, n10 - 12, "API Keys", n9, n4, n5);
        if (this.isRowVisible(n10, n11, n4, n5)) this.drawSectionCard(n, n10, n3, n11);
        int n12 = n3 - 56;
        int n13 = n + n12 + 8;
        int n14 = n13 + 24;
        int n15 = n10 + 8;
        if (this.isRowVisible(n15 + 10, 34, n4, n5)) {
            this.drawInputField(n + 4, n15 + 20, n12, "Hypixel API Key", api_hypixel, this.showHypixel, this.activeInput == 1, n6, n7);
            this.drawEyeButton(n13, n15 + 20, this.showHypixel, n6, n7);
            this.drawClearButton(n14, n15 + 20, n6, n7);
        }
        if (this.isRowVisible(n15 + 48, 34, n4, n5)) {
            this.drawInputField(n + 4, n15 + 58, n12, "Seraph API Key", api_seraph, this.showSeraph, this.activeInput == 2, n6, n7);
            this.drawEyeButton(n13, n15 + 58, this.showSeraph, n6, n7);
            this.drawClearButton(n14, n15 + 58, n6, n7);
        }
        if (this.isRowVisible(n15 + 86, 34, n4, n5)) {
            this.drawInputField(n + 4, n15 + 96, n12, "Vega API Key", api_aurora, this.showAurora, this.activeInput == 3, n6, n7);
            this.drawEyeButton(n13, n15 + 96, this.showAurora, n6, n7);
            this.drawClearButton(n14, n15 + 96, n6, n7);
        }
        if (this.isRowVisible(n15 + 124, 34, n4, n5)) {
            this.drawInputField(n + 4, n15 + 134, n12, "Urchin API Key", api_urchin, this.showUrchin, this.activeInput == 8, n6, n7);
            this.drawEyeButton(n13, n15 + 134, this.showUrchin, n6, n7);
            this.drawClearButton(n14, n15 + 134, n6, n7);
        }
        if (this.isRowVisible(n15 + 162, 40, n4, n5)) {
            this.drawChipToggle(n + 4, n15 + 172, n3 - 8, 22, "Vega Proxy", vegaProxy, true, n6, n7);
            this.drawTextLeft("Use proxy statistics without a Hypixel key", n + 8, n15 + 198, n9);
        }
        int statusY = n2 + 252;
        if (this.isRowVisible(statusY, 44, n4, n5)) {
            this.drawSectionCard(n, statusY, n3, 44);
            String provider = AdninFeatures.statsProviderStatus();
            String label = "vega-proxy".equals(provider) ? "Selected source: Vega Proxy"
                : "hypixel-direct".equals(provider) ? "Selected source: Hypixel API"
                : "New statistics disabled: Hypixel key is missing";
            this.drawTextLeft(label, n + 8, statusY + 7,
                "missing-hypixel-key".equals(provider) ? 0xFFFF747C : AdninUi.TEXT);
            this.drawTextLeft("Previously loaded statistics may remain cached.", n + 8, statusY + 24, AdninUi.MUTED);
        }
        drawInterfaceSettings(n, n2, n3, n4, n5, n6, n7);
    }

    private void drawInterfaceSettings(int x, int baseY, int width, int top, int bottom, int mouseX, int mouseY) {
        int y = baseY + INTERFACE_CARD_Y;
        drawPanelSectionTitle(x, y - 16, "Interface", AdninUi.MUTED, top, bottom);
        if (!isRowVisible(y, 114, top, bottom)) return;
        drawSectionCard(x, y, width, 114);
        drawTextLeft("UI Scale", x + 12, y + 12, AdninUi.TEXT);
        String value = uiScalePercent + "%";
        float requested = Math.min(1f, Math.max(0.02f, Math.min((this.width - 20f) / 620f, (this.height - 20f) / 428f))) * uiScalePercent / 100f;
        if (uiScale + 0.0001f < requested) value = AdninLanguage.format("%s (fit to screen)", value);
        AdninUi.text(value, x + width - 12 - AdninUi.width(value, 0), y + 11, AdninUi.MUTED, 0);
        int trackX = x + 14, trackY = baseY + UI_SCALE_TRACK_Y, trackW = width - 28;
        float position = (uiScalePercent - 70) / 70f;
        AdninUi.round(trackX, trackY + 6, trackW, 3, 1.5f, 0xFF474F5E);
        AdninUi.round(trackX, trackY + 6, trackW * position, 3, 1.5f, AdninUi.ACCENT);
        float knob = trackX + trackW * position;
        boolean hover = isHoveredRect(trackX - 6, trackY - 2, trackW + 12, 20, mouseX, mouseY);
        if (draggingUiScale || hover) AdninUi.round(knob - 8, trackY - 1, 16, 16, 8, 0x44529AFF);
        AdninUi.round(knob - 5, trackY + 2, 10, 10, 5, AdninUi.TEXT);
        drawTextLeft("Language", x + 12, y + 66, AdninUi.MUTED);
        String language = AdninLanguage.getLanguage();
        int selected = "zh_CN".equals(language) ? 1 : "zh_TW".equals(language) ? 2 : 0;
        drawSegmentedChoice(x + 8, baseY + LANGUAGE_CHOICES_Y, width - 16, 20,
            new String[]{"English", "\u7b80\u4f53\u4e2d\u6587", "\u7e41\u9ad4\u4e2d\u6587"}, selected, true, mouseX, mouseY);
    }

    private boolean handleInterfaceSettings(int x, int baseY, int width, int top, int bottom,
            int mouseX, int mouseY, int screenX) {
        int trackY = baseY + UI_SCALE_TRACK_Y;
        if (hitChip(x + 8, trackY - 2, width - 16, 20, top, bottom, mouseX, mouseY)) {
            activeInput = 0;
            draggingUiScale = true;
            updateUiScaleFromMouse(screenX);
            return true;
        }
        int choiceY = baseY + LANGUAGE_CHOICES_Y, choiceW = (width - 20) / 3;
        String[] codes = {"en", "zh_CN", "zh_TW"};
        for (int i = 0; i < codes.length; i++) {
            if (hitChip(x + 8 + i * (choiceW + 2), choiceY, choiceW, 20, top, bottom, mouseX, mouseY)) {
                activeInput = 0;
                AdninLanguage.setLanguage(codes[i]);
                return true;
            }
        }
        return false;
    }

    private void drawChatOverlayPanels(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8, int n9) {
        int n10;
        int n11;
        int n12 = n2 + CHAT_MAIN_Y;
        if (this.isRowVisible(n12, CHAT_MAIN_H, n4, n5)) {
            this.drawSectionCard(n, n12, n3, CHAT_MAIN_H);
            n11 = 22;
            n10 = (n3 - 16) / 3;
            this.drawChipToggle(n + 4, n12 + 8, n10, n11, "Compact Bl", compactBlacklist, true, n6, n7);
            this.drawChipToggle(n + 8 + n10, n12 + 8, n10, n11, "In Chat", chatOverlay, true, n6, n7);
            this.drawChipToggle(n + 12 + n10 * 2, n12 + 8, n10, n11, "Teammate", !ignoreTeammates, true, n6, n7);
            this.drawChipToggle(n + 4, n12 + CHAT_OUTPUT_PLAYERS_Y, n3 - 8, n11, "Output: Player Data", chatOutput, true, n6, n7);
            this.drawChipToggle(n + 4, n12 + CHAT_OUTPUT_DENICK_Y, n3 - 8, n11, "Output: Nick / Denick", chatOutputDenick, true, n6, n7);
            this.drawChipToggle(n + 4, n12 + CHAT_OUTPUT_TAGS_Y, n3 - 8, n11, "Output: Seraph / Urchin Tags", chatOutputTags, true, n6, n7);
            int filterWidth = (n3 - 32) / 2;
            this.drawChipToggle(n + 20, n12 + CHAT_OUTPUT_TAG_FILTERS_Y, filterWidth, n11,
                "Include Self", chatOutputTagsSelf, chatOutputTags, n6, n7);
            this.drawChipToggle(n + 24 + filterWidth, n12 + CHAT_OUTPUT_TAG_FILTERS_Y, filterWidth, n11,
                "Include Teammates", chatOutputTagsTeammates, chatOutputTags, n6, n7);
            this.drawChipToggle(n + 4, n12 + CHAT_OUTPUT_ANTICHEAT_Y, n3 - 8, n11, "Output: Anticheat", chatOutputAnticheat, true, n6, n7);
        }
        n11 = n2 + CHAT_THRESH_Y;
        this.drawPanelSectionTitle(n, n11 - 12, "Thresholds", n9, n4, n5);
        if (!this.isRowVisible(n11, 144, n4, n5)) {
            return;
        }
        this.drawSectionCard(n, n11, n3, 144);
        n10 = n3 - 36;
        int n13 = n11 + 8;
        if (this.isRowVisible(n13 + 10, 34, n4, n5)) {
            this.drawInputField(n + 4, n13 + 20, n10, "Bedwars Min Stars", chatOverlayMinStars, true, this.activeInput == 5, n6, n7);
            this.drawClearButton(n + n10 + 8, n13 + 20, n6, n7);
        }
        if (this.isRowVisible(n13 + 48, 34, n4, n5)) {
            this.drawInputField(n + 4, n13 + 58, n10, "Bedwars Min FKDR", chatOverlayMinFkdr, true, this.activeInput == 6, n6, n7);
            this.drawClearButton(n + n10 + 8, n13 + 58, n6, n7);
        }
        if (this.isRowVisible(n13 + 86, 34, n4, n5)) {
            this.drawInputField(n + 4, n13 + 96, n10, "SkyWars Min KDR", chatOverlayMinSwKdr, true, this.activeInput == 7, n6, n7);
            this.drawClearButton(n + n10 + 8, n13 + 96, n6, n7);
        }
    }

    private boolean handleChatOverlayPanelClicks(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        int n8 = n2 + CHAT_MAIN_Y;
        int n9 = (n3 - 16) / 3;
        int n10 = 22;
        if (this.hitChip(n + 4, n8 + 8, n9, n10, n4, n5, n6, n7)) {
            compactBlacklist = !compactBlacklist;
            return true;
        }
        if (this.hitChip(n + 8 + n9, n8 + 8, n9, n10, n4, n5, n6, n7)) {
            chatOverlay = !chatOverlay;
            return true;
        }
        if (this.hitChip(n + 12 + n9 * 2, n8 + 8, n9, n10, n4, n5, n6, n7)) {
            ignoreTeammates = !ignoreTeammates;
            return true;
        }
        if (this.hitChip(n + 4, n8 + CHAT_OUTPUT_PLAYERS_Y, n3 - 8, n10, n4, n5, n6, n7)) {
            chatOutput = !chatOutput;
            if (chatOutput) chatOverlay = true;
            AdninFeatures.outputSettingsChanged();
            return true;
        }
        if (this.hitChip(n + 4, n8 + CHAT_OUTPUT_DENICK_Y, n3 - 8, n10, n4, n5, n6, n7)) {
            chatOutputDenick = !chatOutputDenick;
            if (chatOutputDenick) chatOverlay = true;
            AdninFeatures.outputSettingsChanged();
            return true;
        }
        if (this.hitChip(n + 4, n8 + CHAT_OUTPUT_TAGS_Y, n3 - 8, n10, n4, n5, n6, n7)) {
            chatOutputTags = !chatOutputTags;
            if (chatOutputTags) chatOverlay = true;
            AdninFeatures.outputSettingsChanged();
            return true;
        }
        int filterWidth = (n3 - 32) / 2;
        if (chatOutputTags && this.hitChip(n + 20, n8 + CHAT_OUTPUT_TAG_FILTERS_Y, filterWidth, n10, n4, n5, n6, n7)) {
            chatOutputTagsSelf = !chatOutputTagsSelf;
            AdninFeatures.outputSettingsChanged();
            return true;
        }
        if (chatOutputTags && this.hitChip(n + 24 + filterWidth, n8 + CHAT_OUTPUT_TAG_FILTERS_Y, filterWidth, n10, n4, n5, n6, n7)) {
            chatOutputTagsTeammates = !chatOutputTagsTeammates;
            AdninFeatures.outputSettingsChanged();
            return true;
        }
        if (this.hitChip(n + 4, n8 + CHAT_OUTPUT_ANTICHEAT_Y, n3 - 8, n10, n4, n5, n6, n7)) {
            chatOutputAnticheat = !chatOutputAnticheat;
            AdninFeatures.outputSettingsChanged();
            return true;
        }
        return false;
    }

    private static int clampSessionOpacity(int n) {
        if (n < 0) {
            return 0;
        }
        if (n > 255) {
            return 255;
        }
        return n;
    }

    private static int clampSessionScale(int n) {
        if (n < 50) {
            return 50;
        }
        if (n > 150) {
            return 150;
        }
        return n;
    }

    private static int clampSessionPos(int n) {
        if (n < 0) {
            return 0;
        }
        if (n > 1000) {
            return 1000;
        }
        return n;
    }

    private void drawSessionStatsMainPanel(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        int n8 = n2 + 4;
        if (!this.isRowVisible(n8, 140, n4, n5)) {
            return;
        }
        this.drawSectionCardClipped(n, n8, n3, 140, n4, n5);
        if (this.isRowVisible(n8 + 6, 18, n4, n5)) {
            this.drawTextLeft("Enabled", n + 8, n8 + 7, sessionStats ? -11154347 : -3386027);
            this.drawMiniSwitch(n + n3 - 34, n8 + 6, 28, 12, sessionStats, true, n6, n7);
        }
        int n9 = 22;
        int n10 = (n3 - 12) / 2;
        int n11 = n8 + 28;
        int n12 = n8 + 54;
        int n13 = n8 + 80;
        int n14 = n8 + 106;
        if (this.isRowVisible(n11, n9, n4, n5)) {
            this.drawChipToggle(n + 4, n11, n10, n9, "Game Stats", sessionStatsGame, sessionStats, n6, n7);
            this.drawChipToggle(n + 8 + n10, n11, n10, n9, "Final Kills", sessionStatsFinalKills, sessionStats, n6, n7);
        }
        if (this.isRowVisible(n12, n9, n4, n5)) {
            this.drawChipToggle(n + 4, n12, n10, n9, "Beds", sessionStatsBeds, sessionStats, n6, n7);
            this.drawChipToggle(n + 8 + n10, n12, n10, n9, "Kills", sessionStatsKills, sessionStats, n6, n7);
        }
        if (this.isRowVisible(n13, n9, n4, n5)) {
            this.drawChipToggle(n + 4, n13, n10, n9, "Wins", sessionStatsWins, sessionStats, n6, n7);
            this.drawChipToggle(n + 8 + n10, n13, n10, n9, "Winstreak", sessionStatsWinstreak, sessionStats, n6, n7);
        }
        if (this.isRowVisible(n14, n9, n4, n5)) {
            this.drawChipToggle(n + 4, n14, n10, n9, "Slumber Tickets", sessionStatsSlumberTickets, sessionStats, n6, n7);
            this.drawChipToggle(n + 8 + n10, n14, n10, n9, "Session Games", sessionStatsSessionGames, sessionStats, n6, n7);
        }
    }

    private void drawSessionDisplayPanel(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8) {
        int n9;
        int n10;
        int n11;
        int n12;
        int n13;
        int n14 = n2 + 156;
        this.drawPanelSectionTitle(n, n14, "Display", n8, n4, n5);
        int n15 = n2 + 172;
        if (!this.isRowVisible(n15, 148, n4, n5)) {
            return;
        }
        this.drawSectionCardClipped(n, n15, n3, 148, n4, n5);
        int n16 = 22;
        int n17 = (n3 - 12) / 2;
        int n18 = n15 + 8;
        int n19 = n15 + 36;
        int n20 = n15 + 64;
        if (this.isRowVisible(n18, n16, n4, n5)) {
            this.drawChipToggle(n + 4, n18, n17, n16, "Game Time", sessionStatsGameTime, sessionStats, n6, n7);
            this.drawChipToggle(n + 8 + n17, n18, n17, n16, "Avg Time", sessionStatsAvgTime, sessionStats, n6, n7);
        }
        if (this.isRowVisible(n19, n16, n4, n5)) {
            this.drawChipToggle(n + 4, n19, n17, n16, "Session Time", sessionStatsSessionTime, sessionStats, n6, n7);
            this.drawChipToggle(n + 8 + n17, n19, n17, n16, "Text Shadow", sessionStatsTextShadow, sessionStats, n6, n7);
        }
        if (this.isRowVisible(n20, n16, n4, n5)) {
            this.drawActionChip(n + 4, n20, n3 - 8, n16, "Reset Session", n6, n7);
        }
        if (this.isRowVisible(n13 = n15 + 92, 22, n4, n5)) {
            n12 = 48;
            n11 = n + 86;
            n10 = n11 + n12 + 6;
            n9 = n10 + 26;
            this.drawTextLeft("BG Opacity", n + 8, n13 + 5, n8);
            this.drawValueChip(n11, n13, n12, 22, AdninGui4.clampSessionOpacity(sessionStatsBgOpacity) + "", n6, n7);
            this.drawActionChip(n10, n13, 24, 22, "-", n6, n7);
            this.drawActionChip(n9, n13, 24, 22, "+", n6, n7);
        }
        if (this.isRowVisible(n12 = n15 + 118, 22, n4, n5)) {
            n11 = 48;
            n10 = n + 70;
            n9 = n10 + n11 + 6;
            int n21 = n9 + 26;
            this.drawTextLeft("Scale", n + 8, n12 + 5, n8);
            this.drawValueChip(n10, n12, n11, 22, AdninGui4.clampSessionScale(sessionStatsScale) + "%", n6, n7);
            this.drawActionChip(n9, n12, 24, 22, "-", n6, n7);
            this.drawActionChip(n21, n12, 24, 22, "+", n6, n7);
        }
    }

    private void drawSessionStatsPanels(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8, int n9) {
        this.drawSessionStatsMainPanel(n, n2, n3, n4, n5, n6, n7);
        this.drawSessionDisplayPanel(n, n2, n3, n4, n5, n6, n7, n9);
    }

    private boolean handleSessionStatsMainPanelClicks(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        int n8 = n2 + 4;
        if (this.hitCardSwitch(n, n3, n8, n4, n5, n6, n7)) {
            sessionStats = !sessionStats;
            return true;
        }
        int n9 = 22;
        int n10 = (n3 - 12) / 2;
        int n11 = n8 + 28;
        int n12 = n8 + 54;
        int n13 = n8 + 80;
        int n14 = n8 + 106;
        if (this.hitChip(n + 4, n11, n10, n9, n4, n5, n6, n7)) {
            sessionStatsGame = !sessionStatsGame;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n11, n10, n9, n4, n5, n6, n7)) {
            sessionStatsFinalKills = !sessionStatsFinalKills;
            return true;
        }
        if (this.hitChip(n + 4, n12, n10, n9, n4, n5, n6, n7)) {
            sessionStatsBeds = !sessionStatsBeds;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n12, n10, n9, n4, n5, n6, n7)) {
            sessionStatsKills = !sessionStatsKills;
            return true;
        }
        if (this.hitChip(n + 4, n13, n10, n9, n4, n5, n6, n7)) {
            sessionStatsWins = !sessionStatsWins;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n13, n10, n9, n4, n5, n6, n7)) {
            sessionStatsWinstreak = !sessionStatsWinstreak;
            return true;
        }
        if (this.hitChip(n + 4, n14, n10, n9, n4, n5, n6, n7)) {
            sessionStatsSlumberTickets = !sessionStatsSlumberTickets;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n14, n10, n9, n4, n5, n6, n7)) {
            sessionStatsSessionGames = !sessionStatsSessionGames;
            return true;
        }
        return false;
    }

    private boolean handleSessionDisplayPanelClicks(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        int n8 = n2 + 172;
        int n9 = 22;
        int n10 = (n3 - 12) / 2;
        int n11 = n8 + 8;
        int n12 = n8 + 36;
        int n13 = n8 + 64;
        if (this.hitChip(n + 4, n11, n10, n9, n4, n5, n6, n7)) {
            sessionStatsGameTime = !sessionStatsGameTime;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n11, n10, n9, n4, n5, n6, n7)) {
            sessionStatsAvgTime = !sessionStatsAvgTime;
            return true;
        }
        if (this.hitChip(n + 4, n12, n10, n9, n4, n5, n6, n7)) {
            sessionStatsSessionTime = !sessionStatsSessionTime;
            return true;
        }
        if (this.hitChip(n + 8 + n10, n12, n10, n9, n4, n5, n6, n7)) {
            sessionStatsTextShadow = !sessionStatsTextShadow;
            return true;
        }
        if (this.hitChip(n + 4, n13, n3 - 8, n9, n4, n5, n6, n7)) {
            sessionStatsResetPending = true;
            return true;
        }
        int n14 = n8 + 92;
        int n15 = 48;
        int n16 = n + 86;
        int n17 = n16 + n15 + 6;
        int n18 = n17 + 26;
        if (this.hitChip(n17, n14, 24, 22, n4, n5, n6, n7)) {
            sessionStatsBgOpacity = AdninGui4.clampSessionOpacity(sessionStatsBgOpacity - 10);
            return true;
        }
        if (this.hitChip(n18, n14, 24, 22, n4, n5, n6, n7)) {
            sessionStatsBgOpacity = AdninGui4.clampSessionOpacity(sessionStatsBgOpacity + 10);
            return true;
        }
        int n19 = n8 + 118;
        n16 = n + 70;
        n17 = n16 + n15 + 6;
        n18 = n17 + 26;
        if (this.hitChip(n17, n19, 24, 22, n4, n5, n6, n7)) {
            sessionStatsScale = AdninGui4.clampSessionScale(sessionStatsScale - 5);
            return true;
        }
        if (this.hitChip(n18, n19, 24, 22, n4, n5, n6, n7)) {
            sessionStatsScale = AdninGui4.clampSessionScale(sessionStatsScale + 5);
            return true;
        }
        return false;
    }

    private boolean handleSessionStatsPanelClicks(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        if (this.handleSessionStatsMainPanelClicks(n, n2, n3, n4, n5, n6, n7)) {
            return true;
        }
        return this.handleSessionDisplayPanelClicks(n, n2, n3, n4, n5, n6, n7);
    }

    private void drawOverlayHeaderCard(int n, int n2, int n3, int n4, int n5, int n6, int n7, int n8, int n9) {
        if (!this.isRowVisible(n2, 104, n4, n5)) {
            return;
        }
        this.drawSectionCard(n, n2, n3, 104);
        this.drawTextLeft("Enabled", n + 8, n2 + 7, tabOverlay ? -11154347 : -3386027);
        this.drawMiniSwitch(n + n3 - 34, n2 + 6, 28, 12, tabOverlay, true, n6, n7);
        this.drawTextLeft("Opacity", n + 8, n2 + 28, n9);
        int n10 = 72;
        this.drawValueChip(n + 4, n2 + 40, n10, 22, AdninGui4.normalizeOpacity(overlayOpacity) + "%", n6, n7);
        int n11 = n + n10 + 10;
        int n12 = n11 + 28;
        this.drawActionChip(n11, n2 + 40, 24, 22, "-", n6, n7);
        this.drawActionChip(n12, n2 + 40, 24, 22, "+", n6, n7);
        String string = overlayGamemodeActive == null || "unknown".equals(overlayGamemodeActive) ? "unknown" : overlayGamemodeActive;
        this.drawTextLeft(AdninLanguage.format("Active: %s", AdninLanguage.text(string)), n + 8, n2 + 68, n9);
        int n13 = 0;
        for (int i = 0; i < OVERLAY_GAMEMODES.length; ++i) {
            if (!OVERLAY_GAMEMODES[i].equals(overlayGamemodeEdit)) continue;
            n13 = i;
        }
        this.drawSegmentedChoice(n + 4, n2 + 80, n3 - 8, 20, OVERLAY_GAMEMODE_LABELS, n13, true, n6, n7);
    }

    private boolean handleOverlayHeaderClicks(int n, int n2, int n3, int n4, int n5, int n6, int n7) {
        if (this.hitCardSwitch(n, n3, n2, n4, n5, n6, n7)) {
            tabOverlay = !tabOverlay;
            return true;
        }
        int n8 = 72;
        int n9 = n + n8 + 10;
        int n10 = n9 + 28;
        if (this.hitChip(n9, n2 + 40, 24, 22, n4, n5, n6, n7)) {
            overlayOpacity = AdninGui4.normalizeOpacity(overlayOpacity - 5);
            return true;
        }
        if (this.hitChip(n10, n2 + 40, 24, 22, n4, n5, n6, n7)) {
            overlayOpacity = AdninGui4.normalizeOpacity(overlayOpacity + 5);
            return true;
        }
        int n11 = n2 + 80;
        int n12 = 20;
        int n13 = (n3 - 8 - 6) / OVERLAY_GAMEMODES.length;
        for (int i = 0; i < OVERLAY_GAMEMODES.length; ++i) {
            int n14 = n + 4 + i * (n13 + 2);
            if (!this.hitChip(n14, n11, n13, n12, n4, n5, n6, n7)) continue;
            AdninGui4.setOverlayGamemodeEdit(OVERLAY_GAMEMODES[i]);
            return true;
        }
        return false;
    }

    private void drawTogglePair(int n, int n2, int n3, String string, boolean bl, boolean bl2, int n4, int n5) {
        this.drawToggle(n, n2, n3, string, bl, bl2, n4, n5);
    }

    public void initGui() {
        AdninFeatures.ensureInitialized();
        api_hypixel = api_hypixel == null ? "" : api_hypixel.trim();
        api_seraph = api_seraph == null ? "" : api_seraph.trim();
        api_aurora = api_aurora == null ? "" : api_aurora.trim();
        api_urchin = api_urchin == null ? "" : api_urchin.trim();
        this.buttonList.clear();
        this.cancelPointerInteraction();
        this.beginKeyboardInput();
        this.layoutForViewport();
        // Reinitializing the same open screen must not cancel its closing fade.
        if (this.openedAt == 0) this.openedAt = System.nanoTime();
        this.lastFrame = 0;
        this.drawnTheme = -1; this.sidebarSelection = selectedTheme * 34;
        this.switchPositions.clear();
        for (int panel = 0; panel < THEMES.length; panel++)
            this.setPanelScroll(panel,this.clampScroll(this.getPanelScroll(panel),this.getPanelContentHeight(panel),this.viewportHeight() - 5));
    }

    public void handleMouseInput() {
        super.handleMouseInput();
        if (closingAt != 0 || !Mouse.isCreated()) return;
        int wheel = Mouse.getEventDWheel();
        int mx = Math.round(Mouse.getEventX() * this.width / (float)Math.max(1,org.lwjgl.opengl.Display.getWidth()) / uiScale);
        int my = Math.round((this.height - Mouse.getEventY() * this.height / (float)Math.max(1,org.lwjgl.opengl.Display.getHeight()) - 1) / uiScale - animatedYOffset);
        int contentX = winX + PANEL_INSET + SIDEBAR_W + CONTENT_GAP;
        int top = winY + HEADER_H + PANEL_INSET;
        if (wheel != 0 && !draggingUiScale && isHoveredRect(contentX,top,winW - (contentX - winX) - PANEL_INSET,viewportHeight(),mx,my)) {
            int next = getPanelScroll(selectedTheme) + (wheel > 0 ? 30 : -30);
            setPanelScroll(selectedTheme,clampScroll(next,getPanelContentHeight(selectedTheme),viewportHeight() - 5));
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int button) {
        super.mouseReleased(mouseX, mouseY, button);
        if (button == 0) this.cancelPointerInteraction();
    }

    protected void mouseClicked(int n, int n2, int n3) {
        int n4;
        int n5;
        super.mouseClicked(n, n2, n3);
        if (closingAt != 0) return;
        int screenX = n;
        n = Math.round(n / uiScale); n2 = Math.round(n2 / uiScale - animatedYOffset);
        if (n3 == 0 && isHoveredRect(winX + winW - 34,winY + 15,18,18,n,n2)) { closeScreenSafe(); return; }
        if (n3 != 0) {
            return;
        }
        boolean bl = false;
        int n6 = this.winX + PANEL_INSET;
        int n7 = this.winY + HEADER_H + PANEL_INSET;
        int n8 = SIDEBAR_W;
        int n9 = this.viewportHeight();
        int n10 = 30;
        for (n5 = 0; n5 < THEMES.length; ++n5) {
            n4 = n7 + n5 * 34;
            if (!this.isHoveredRect(n6, n4, n8, n10, n, n2)) continue;
            this.selectedTheme = n5;
            this.activeInput = 0; this.draggingHitboxSlider = false; this.draggingUiScale = false;
            if (n5 == 2) {
                int n11 = this.viewportHeight() - 5;
                this.utilsScroll = this.clampScroll(this.utilsScroll, AdninGui4.gameplayPanelContentHeight(), n11);
            }
            this.playUiClickSound();
            return;
        }
        n5 = n6 + n8 + CONTENT_GAP;
        n4 = n7 + 5;
        int n12 = this.winX + this.winW - PANEL_INSET;
        int n13 = n12 - 4 - 8;
        int n14 = n13 - 8 - n5;
        int n15 = n7;
        int n16 = n7 + n9;
        if (n2 < n15 || n2 >= n16 || n < n5 || n >= n12) return;
        int n17 = this.visibleScroll();
        int n18 = n4 + n17;
        int n19 = n14 - 28;
        int n20 = n5 + n19 + 4;
        if (this.selectedTheme == 1) {
            bl = this.handleAnticheatInputClicks(n5, n18, n14, n15, n16, n, n2);
            if (!bl && this.handleAnticheatPanelClicks(n5, n18, n14, n15, n16, n, n2)) {
                bl = true;
            }
        } else if (this.selectedTheme == 0) {
            if (handleInterfaceSettings(n5, n18, n14, n15, n16, n, n2, screenX)) {
                AdninFeatures.requestSave();
                this.playUiClickSound();
                return;
            }
            n19 = n14 - 56;
            n20 = n5 + n19 + 8;
            int n21 = n20 + 24;
            int n22 = n18 + 16;
            int n23 = n22 + 8;
            if (this.isRowVisible(n23 + 10, 34, n15, n16) && this.isHoveredRect(n5 + 4, n23 + 20, n19, 20, n, n2)) {
                this.activeInput = 1;
                bl = true;
            } else if (this.isRowVisible(n23 + 48, 34, n15, n16) && this.isHoveredRect(n5 + 4, n23 + 58, n19, 20, n, n2)) {
                this.activeInput = 2;
                bl = true;
            } else if (this.isRowVisible(n23 + 86, 34, n15, n16) && this.isHoveredRect(n5 + 4, n23 + 96, n19, 20, n, n2)) {
                this.activeInput = 3;
                bl = true;
            } else if (this.isRowVisible(n23 + 124, 34, n15, n16) && this.isHoveredRect(n5 + 4, n23 + 134, n19, 20, n, n2)) {
                this.activeInput = 8;
                bl = true;
            } else {
                this.activeInput = 0;
            }
            if (this.isRowVisible(n23 + 10, 34, n15, n16) && this.isHoveredRect(n20, n23 + 20, 20, 20, n, n2)) {
                this.showHypixel = !this.showHypixel;
                bl = true;
            }
            if (this.isRowVisible(n23 + 48, 34, n15, n16) && this.isHoveredRect(n20, n23 + 58, 20, 20, n, n2)) {
                this.showSeraph = !this.showSeraph;
                bl = true;
            }
            if (this.isRowVisible(n23 + 86, 34, n15, n16) && this.isHoveredRect(n20, n23 + 96, 20, 20, n, n2)) {
                this.showAurora = !this.showAurora;
                bl = true;
            }
            if (this.isRowVisible(n23 + 124, 34, n15, n16) && this.isHoveredRect(n20, n23 + 134, 20, 20, n, n2)) {
                this.showUrchin = !this.showUrchin;
                bl = true;
            }
            if (this.hitChip(n5 + 4, n23 + 172, n14 - 8, 22, n15, n16, n, n2)) {
                vegaProxy = !vegaProxy;
                bl = true;
            }
            if (this.isRowVisible(n23 + 10, 34, n15, n16) && this.isHoveredRect(n21, n23 + 20, 20, 20, n, n2)) {
                api_hypixel = "";
                this.activeInput = 0;
                bl = true;
            }
            if (this.isRowVisible(n23 + 48, 34, n15, n16) && this.isHoveredRect(n21, n23 + 58, 20, 20, n, n2)) {
                api_seraph = "";
                this.activeInput = 0;
                bl = true;
            }
            if (this.isRowVisible(n23 + 86, 34, n15, n16) && this.isHoveredRect(n21, n23 + 96, 20, 20, n, n2)) {
                api_aurora = "";
                this.activeInput = 0;
                bl = true;
            }
            if (this.isRowVisible(n23 + 124, 34, n15, n16) && this.isHoveredRect(n21, n23 + 134, 20, 20, n, n2)) {
                api_urchin = "";
                this.activeInput = 0;
                bl = true;
            }
        } else if (this.selectedTheme == 2) {
            int n24 = n18 + 342;
            if (this.isRowVisible(n24, 34, n15, n16) && this.isHoveredRect(n5, n24, n19, 20, n, n2)) {
                this.activeInput = 4;
                bl = true;
            } else if (this.isRowVisible(n18 + UTILS_BOT_URL_Y - 10, 34, n15, n16) && this.isHoveredRect(n5 + 4, n18 + UTILS_BOT_URL_Y, n14 - 36, 20, n, n2)) {
                this.activeInput = 9;
                bl = true;
            } else {
                this.activeInput = 0;
            }
            if (this.isRowVisible(n24, 34, n15, n16) && this.isHoveredRect(n5 + n19 + 4, n24, 20, 20, n, n2)) {
                gl_message = "";
                this.activeInput = 0;
                bl = true;
            }
            if (this.isRowVisible(n18 + UTILS_BOT_URL_Y - 10, 34, n15, n16) && this.isHoveredRect(n5 + n14 - 24, n18 + UTILS_BOT_URL_Y, 20, 20, n, n2)) {
                botDenickerUrl = "";
                this.activeInput = 0;
                bl = true;
            }
            if (!bl && this.handleUtilsPanelClicks(n5, n18, n14, n15, n16, n, n2)) {
                bl = true;
            }
        } else if (this.selectedTheme == 3) {
            int n25;
            int n26 = n4;
            int n27 = n4 + 104 + n17;
            this.activeInput = 0;
            if (this.handleOverlayHeaderClicks(n5, n26, n14, n15, n16, n, n2)) {
                bl = true;
            }
            if ((n25 = n4 + 104) < n15) {
                n25 = n15;
            }
            int n28 = n27 + 20;
            int n29 = 20;
            int[] nArray = this.getOverlaySortedIndexes();
            for (int i = 0; i < nArray.length; ++i) {
                if (bl || n2 < n25 || n2 >= n16) break;
                int n30 = nArray[i];
                int n31 = n28 + i * 24;
                if (!this.isRowVisible(n31, n29, n25, n16)) continue;
                int n32 = n5 + n14 - 34;
                int n33 = n5 + n14 - 17;
                if (this.isHoveredRect(n32, n31, 14, n29, n, n2)) {
                    this.moveOverlayColumn(n30, -1);
                    this.overlaySelectedIndex = n30;
                    bl = true;
                } else if (this.isHoveredRect(n33, n31, 14, n29, n, n2)) {
                    this.moveOverlayColumn(n30, 1);
                    this.overlaySelectedIndex = n30;
                    bl = true;
                } else {
                    if (!this.isHoveredRect(n5, n31, n14 - 36, n29, n, n2)) continue;
                    AdninGui4.overlayColumnEnabled[n30] = !overlayColumnEnabled[n30];
                    this.overlaySelectedIndex = n30;
                    bl = true;
                }
                break;
            }
        } else if (this.selectedTheme == 4) {
            n19 = n14 - 28;
            int n34 = n18 + CHAT_THRESH_Y;
            int n35 = n34 + 8;
            if (this.isRowVisible(n35 + 10, 34, n15, n16) && this.isHoveredRect(n5 + 4, n35 + 20, n14 - 36, 20, n, n2)) {
                this.activeInput = 5;
                bl = true;
            } else if (this.isRowVisible(n35 + 48, 34, n15, n16) && this.isHoveredRect(n5 + 4, n35 + 58, n14 - 36, 20, n, n2)) {
                this.activeInput = 6;
                bl = true;
            } else if (this.isRowVisible(n35 + 86, 34, n15, n16) && this.isHoveredRect(n5 + 4, n35 + 96, n14 - 36, 20, n, n2)) {
                this.activeInput = 7;
                bl = true;
            } else {
                this.activeInput = 0;
            }
            if (this.isRowVisible(n35 + 10, 34, n15, n16) && this.isHoveredRect(n5 + n14 - 24, n35 + 20, 20, 20, n, n2)) {
                chatOverlayMinStars = "";
                this.activeInput = 0;
                bl = true;
            }
            if (this.isRowVisible(n35 + 48, 34, n15, n16) && this.isHoveredRect(n5 + n14 - 24, n35 + 58, 20, 20, n, n2)) {
                chatOverlayMinFkdr = "";
                this.activeInput = 0;
                bl = true;
            }
            if (this.isRowVisible(n35 + 86, 34, n15, n16) && this.isHoveredRect(n5 + n14 - 24, n35 + 96, 20, 20, n, n2)) {
                chatOverlayMinSwKdr = "";
                this.activeInput = 0;
                bl = true;
            }
            if (!bl && this.handleChatOverlayPanelClicks(n5, n18, n14, n15, n16, n, n2)) {
                bl = true;
            }
        } else if (this.selectedTheme == 5) {
            this.activeInput = 0;
            if (!bl && this.handleSessionStatsPanelClicks(n5, n18, n14, n15, n16, n, n2)) {
                bl = true;
            }
        } else if (this.selectedTheme == 6) {
            this.activeInput = 0;
            if (!bl && this.handleExperimentalPanelClicks(n5, n18, n14, n15, n16, n, n2)) {
                bl = true;
            }
        }
        if (bl) {
            AdninFeatures.requestSave();
            this.playUiClickSound();
        }
    }

    protected void keyTyped(char c, int n) {
        if (closingAt != 0) return;
        boolean bl;
        if (listeningHoldRdKey) {
            if (n == 1) {
                holdRdKeyCode = 0;
                listeningHoldRdKey = false;
                AdninFeatures.requestSave();
                return;
            }
            if (n > 0) {
                holdRdKeyCode = n;
                listeningHoldRdKey = false;
                AdninFeatures.requestSave();
            }
            return;
        }
        if (listeningQuickbuyIndex >= 0) {
            if (n == 1) {
                AdninGui4.quickbuyKeys[AdninGui4.listeningQuickbuyIndex] = 0;
                listeningQuickbuyIndex = -1;
                AdninGui4.syncQuickbuyBinds();
                AdninFeatures.requestSave();
                return;
            }
            if (n > 0) {
                AdninGui4.quickbuyKeys[AdninGui4.listeningQuickbuyIndex] = n;
                listeningQuickbuyIndex = -1;
                AdninGui4.syncQuickbuyBinds();
                AdninFeatures.requestSave();
            }
            return;
        }
        if (n == 1) {
            this.activeInput = 0;
            this.closeScreenSafe();
            return;
        }
        boolean bl2 = this.selectedTheme == 0 && (this.activeInput >= 1 && this.activeInput <= 3 || this.activeInput == 8);
        boolean bl3 = this.selectedTheme == 2 && (this.activeInput == 4 || this.activeInput == 9 || this.activeInput == 11);
        boolean bl4 = bl = this.selectedTheme == 4 && this.activeInput >= 5 && this.activeInput <= 7;
        boolean acInput = this.selectedTheme == 1 && this.activeInput == 10;
        if (!bl2 && !bl3 && !bl && !acInput || this.activeInput == 0) {
            return;
        }
        if (n == 28 || n == Keyboard.KEY_NUMPADENTER) {
            this.activeInput = 0;
            return;
        }
        String string = "";
        if (this.activeInput == 1) {
            string = api_hypixel;
        } else if (this.activeInput == 2) {
            string = api_seraph;
        } else if (this.activeInput == 3) {
            string = api_aurora;
        } else if (this.activeInput == 4) {
            string = gl_message;
        } else if (this.activeInput == 5) {
            string = chatOverlayMinStars;
        } else if (this.activeInput == 6) {
            string = chatOverlayMinFkdr;
        } else if (this.activeInput == 7) {
            string = chatOverlayMinSwKdr;
        } else if (this.activeInput == 8) {
            string = api_urchin;
        } else if (this.activeInput == 9) {
            string = botDenickerUrl;
        } else if (this.activeInput == 11) {
            string = quickbuyProfilePlayer;
        } else if (this.activeInput == 10) {
            string = AdninAnticheat.ignoredPlayers;
        }
        if (n == 14) {
            if (string.length() > 0) {
                string = string.substring(0, string.offsetByCodePoints(string.length(), -1));
            }
        } else if ((Keyboard.isKeyDown((int)29) || Keyboard.isKeyDown((int)157)) && n == 47) {
            try {
                String string2 = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor).toString();
                if (string2 != null) {
                    string2 = string2.replace("\n", "").replace("\r", "");
                    string = string + string2;
                }
            }
            catch (Throwable throwable) {}
        } else if (c >= ' ' && c <= '~' || c == '.' || c == '-') {
            if (this.activeInput >= 5 && this.activeInput <= 7) {
                if (c >= '0' && c <= '9' || c == '.' || c == '-' && string.length() == 0) {
                    string = string + c;
                }
            } else if (c >= ' ' && c <= '~') {
                string = string + c;
            }
        }
        int inputLimit = this.activeInput == 9 ? 2048 : this.activeInput == 8 || this.activeInput == 10 ? 512 : this.activeInput == 11 ? 16 : 128;
        if (string.length() > inputLimit) {
            string = string.substring(0, inputLimit > 0 && Character.isHighSurrogate(string.charAt(inputLimit - 1)) ? inputLimit - 1 : inputLimit);
        }
        if (this.activeInput == 1) {
            api_hypixel = string.trim();
        } else if (this.activeInput == 2) {
            api_seraph = string.trim();
        } else if (this.activeInput == 3) {
            api_aurora = string.trim();
        } else if (this.activeInput == 4) {
            gl_message = string;
        } else if (this.activeInput == 5) {
            chatOverlayMinStars = string;
        } else if (this.activeInput == 6) {
            chatOverlayMinFkdr = string;
        } else if (this.activeInput == 7) {
            chatOverlayMinSwKdr = string;
        } else if (this.activeInput == 8) {
            api_urchin = string.trim();
        } else if (this.activeInput == 9) {
            botDenickerUrl = string;
        } else if (this.activeInput == 11) {
            // Minecraft names are ASCII and at most 16 characters. Keep this
            // field independent from the existing hotkey quick-buy settings.
            quickbuyProfilePlayer = string.trim();
        } else if (this.activeInput == 10) {
            AdninAnticheat.ignoredPlayers = string;
        }
        AdninFeatures.requestSave();
    }

    public void drawScreen(int n, int n2, float f) {
        holdRdEnabled = false; quickbuyEnabled = false;
        listeningHoldRdKey = false; listeningQuickbuyIndex = -1;
        if (selectedTheme < 0 || selectedTheme >= THEMES.length) selectedTheme = 0;
        long now = System.nanoTime();
        if (openedAt == 0) openedAt = now;
        frameSeconds = lastFrame == 0 ? 0 : Math.min(0.05f,(now - lastFrame) / 1e9f); lastFrame = now;
        float appear = Math.min(1,(now - openedAt) / 220000000f);
        appear = 1 - (1 - appear) * (1 - appear) * (1 - appear);
        float close = closingAt == 0 ? 1 : Math.max(0,1 - (now - closingAt) / 150000000f);
        if (close == 0) {
            try { Minecraft minecraft = Minecraft.getMinecraft(); if (minecraft != null) minecraft.displayGuiScreen(null); }
            catch (Throwable unavailable) { }
            return;
        }
        animatedYOffset = 8 * (1 - appear) + 4 * (1 - close);
        boolean mouseDown = Mouse.isCreated() && Mouse.isButtonDown(0);
        if (closingAt == 0 && draggingUiScale && selectedTheme == 0 && mouseDown) updateUiScaleFromMouse(n);
        else if (!mouseDown) draggingUiScale = false;
        layoutForViewport();
        int mouseX = Math.round(n / uiScale), mouseY = Math.round(n2 / uiScale - animatedYOffset);
        if (closingAt == 0 && draggingHitboxSlider && selectedTheme == 2 && coloredHitboxes && mouseDown)
            hitboxThickness = hitboxThicknessFromMouse(hitboxSliderTrackX,hitboxSliderTrackW,mouseX);
        else if (!mouseDown) draggingHitboxSlider = false;
        if (drawnTheme != selectedTheme) {
            drawnTheme = selectedTheme; visualScroll = getPanelScroll(selectedTheme); pageChangedAt = now;
        }
        setPanelScroll(selectedTheme,clampScroll(getPanelScroll(selectedTheme),getPanelContentHeight(selectedTheme),viewportHeight() - 5));
        visualScroll += (getPanelScroll(selectedTheme) - visualScroll) * (1 - Math.exp(-18 * frameSeconds));
        sidebarSelection += (selectedTheme * 34 - sidebarSelection) * (1 - Math.exp(-20 * frameSeconds));
        AdninUi.begin(this.width,this.height,uiScale,animatedYOffset,appear * close);
        try {
            AdninUi.rect(0,-20,(int)(this.width/uiScale),(int)(this.height/uiScale)+20,0x77090B10);
            for (int spread = 14; spread >= 2; spread -= 2)
                AdninUi.round(winX-spread,winY-spread/2f+5,winW+spread*2,winH+spread,18+spread,0x09000000);
            AdninUi.round(winX,winY,winW,winH,15,0xFF424752);
            AdninUi.round(winX+0.7f,winY+0.7f,winW-1.4f,winH-1.4f,14.5f,0xFF1C1F26);
            AdninUi.text("Adnin",winX+24,winY+17,0xFFFF656A,2);
            AdninUi.text("v24",winX+32+AdninUi.width("Adnin",2),winY+24,AdninUi.MUTED,0);
            int left = winX + PANEL_INSET, top = winY + HEADER_H + PANEL_INSET;
            int contentX = left + SIDEBAR_W + CONTENT_GAP;
            AdninUi.text(AdninLanguage.text(THEMES[selectedTheme]),contentX,winY+12,AdninUi.TEXT,2);
            AdninUi.text(AdninLanguage.text(DESCRIPTIONS[selectedTheme]),contentX,winY+39,AdninUi.MUTED,0);
            AdninUi.rect(winX+PANEL_INSET,winY+HEADER_H,winX+winW-PANEL_INSET,winY+HEADER_H+1,0xFF30343E);
            AdninUi.round(winX+winW-30,winY+19,11,11,5.5f,0xFFFF6058);
            if (isHoveredRect(winX+winW-34,winY+15,18,18,mouseX,mouseY)) {
                AdninUi.line(winX+winW-27,winY+22,winX+winW-22,winY+27,0xFF762425);
                AdninUi.line(winX+winW-22,winY+22,winX+winW-27,winY+27,0xFF762425);
            }
            AdninUi.clip(left,top,SIDEBAR_W,viewportHeight());
            try {
                AdninUi.round(left,top+(float)sidebarSelection,SIDEBAR_W,30,7,0xFF304B72);
                for (int i=0;i<THEMES.length;i++) drawThemeItem(left,top+i*34,SIDEBAR_W,30,THEMES[i],i==selectedTheme,mouseX,mouseY);
            } finally { AdninUi.unclip(); }
            int right = winX+winW-PANEL_INSET, scrollbar = right-12;
            int panelWidth = scrollbar-8-contentX, bottom = top+viewportHeight();
            int base = top+5, scroll = visibleScroll(), text = AdninUi.TEXT, muted = AdninUi.MUTED;
            float oldOpacity = AdninUi.opacity(appear*close*Math.min(1,0.45f+(now-pageChangedAt)/160000000f));
            AdninUi.clip(contentX-1,top,panelWidth+2,viewportHeight());
            try {
                int y=base+scroll;
                switch(selectedTheme) {
                    case 0: drawSettingsApiPanel(contentX,y,panelWidth,top,bottom,mouseX,mouseY,text,muted); break;
                    case 1: drawAnticheatPanels(contentX,y,panelWidth,top,bottom,mouseX,mouseY,text,muted); break;
                    case 2: drawUtilsPanels(contentX,y,panelWidth,top,bottom,mouseX,mouseY,text,muted); break;
                    case 3:
                        int rowsTop=base+OVERLAY_FIXED_HEADER_H;
                        AdninUi.clip(contentX,rowsTop,panelWidth,Math.max(0,bottom-rowsTop));
                        try {
                            drawTextLeft("Click a row to toggle. Use arrows to reorder.",contentX,rowsTop+scroll,muted);
                            int[] indexes=getOverlaySortedIndexes();
                            for (int i=0;i<indexes.length;i++) {
                                int id=indexes[i], rowY=rowsTop+scroll+20+i*24;
                                if (!isRowVisible(rowY,20,rowsTop,bottom)) continue;
                                drawChipToggle(contentX,rowY,panelWidth-36,20,columnLabelsForMode(overlayGamemodeEdit)[id],overlayColumnEnabled[id],true,mouseX,mouseY);
                                int up=contentX+panelWidth-34, down=contentX+panelWidth-17;
                                AdninUi.round(up,rowY,14,20,4,0xFF323947); AdninUi.round(down,rowY,14,20,4,0xFF323947);
                                int upColor=i==0 ? 0xFF626A79 : text, downColor=i==indexes.length-1 ? 0xFF626A79 : text;
                                AdninUi.line(up+4,rowY+11,up+7,rowY+8,upColor); AdninUi.line(up+7,rowY+8,up+10,rowY+11,upColor);
                                AdninUi.line(down+4,rowY+8,down+7,rowY+11,downColor); AdninUi.line(down+7,rowY+11,down+10,rowY+8,downColor);
                            }
                        } finally { AdninUi.unclip(); }
                        drawOverlayHeaderCard(contentX,base,panelWidth,top,bottom,mouseX,mouseY,text,muted);
                        break;
                    case 4: drawChatOverlayPanels(contentX,y,panelWidth,top,bottom,mouseX,mouseY,text,muted); break;
                    case 5: drawSessionStatsPanels(contentX,y,panelWidth,top,bottom,mouseX,mouseY,text,muted); break;
                    case 6: drawExperimentalPanels(contentX,y,panelWidth,top,bottom,mouseX,mouseY,text,muted); break;
                }
            } finally { AdninUi.unclip(); AdninUi.opacity(oldOpacity); }
            drawScrollBar(scrollbar,top+2,viewportHeight()-4,getPanelContentHeight(selectedTheme),scroll);
        } finally { AdninUi.end(); }
        // The game renderer changes GlStateManager's Java-side cache. Use it
        // only after the raw GL scope has restored every driver attribute.
        if (AdninUi.hasFontFailure()) {
            FontRenderer renderer = resolveFontRenderer();
            if (renderer != null) this.drawCenteredString(renderer,
                AdninLanguage.text("Interface font unavailable. Press Esc to close."),this.width/2,this.height/2,0xFFFF5555);
        }
    
    }

    static {
        overlayOpacity = AdninGui4.normalizeOpacity(overlayOpacity);
        hitboxThickness = AdninGui4.normalizeHitboxThickness(hitboxThickness);
        if ((overlayThemeColor = AdninGui4.sanitizeHexColor(overlayThemeColor)).length() == 0) {
            overlayThemeColor = "0F1316";
        }
        if ((configThemeColor = AdninGui4.sanitizeHexColor(configThemeColor)).length() == 0) {
            configThemeColor = "1A2227";
        }
        if ((configTextColor = AdninGui4.sanitizeHexColor(configTextColor)).length() == 0) {
            configTextColor = "D6DEE4";
        }
        if (overlayResourceLocation == null || !"scoreboard".equals(overlayResourceLocation) && !"tab".equals(overlayResourceLocation)) {
            overlayResourceLocation = "tab";
        }
        sessionStatsBgOpacity = AdninGui4.clampSessionOpacity(sessionStatsBgOpacity);
        sessionStatsScale = AdninGui4.clampSessionScale(sessionStatsScale);
        sessionStatsPosX = AdninGui4.clampSessionPos(sessionStatsPosX);
        sessionStatsPosY = AdninGui4.clampSessionPos(sessionStatsPosY);
        AdninGui4.loadOverlayColumnsForEditMode();
        AdninSharedConfig.rememberDefaults();
    }
}
