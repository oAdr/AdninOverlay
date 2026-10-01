import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.DirectoryStream;
import java.util.Properties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** One user configuration shared by Lunar, Badlion and Vanilla profiles. */
public final class AdninSharedConfig {
    private static final String DIRECTORY = "Adnin";
    private static final String FILE = "config.properties";
    private static final String[] GUI_FIELDS = new String[]{
        "autoWho", "partyDetector", "partyQueueDetector", "bedDisconnectTimer", "holdRdEnabled",
        "holdRdKeyCode", "quickbuyEnabled", "quickbuyDelayMs", "quickbuyBinds", "quickbuyKeys",
        "quickbuySlots", "quickbuyTurbo", "numberDenicker", "botDenicker", "botDenickerUrl",
        "clientSideSounds", "autoGL", "fastBuy", "coloredHitboxes", "dragonHitboxes", "hitboxThickness",
        "arrowDistance", "tradeIndicator", "tabOverlay", "overlayResourceHeader", "overlayResourceDiamonds",
        "overlayResourceEmeralds", "overlayResourceLocation", "overlayOpacity", "overlayThemeColor",
        "configThemeColor", "configTextColor", "overlayColumnEnabled", "overlayColumnOrder", "anticheat",
        "ac_flagsound", "ac_autoblock", "ac_legitscaff", "ac_scaffold", "ac_scaffoldb", "ac_noslow",
        "api_hypixel", "api_seraph", "api_aurora", "api_urchin", "vegaProxy", "gl_message", "compactBlacklist",
        "chatOverlay", "chatOutput", "chatOutputDenick", "chatOutputTags", "chatOutputTagsSelf",
        "chatOutputTagsTeammates", "chatOutputAnticheat", "ignoreTeammates", "chatOverlayMinStars",
        "chatOverlayMinFkdr", "chatOverlayMinSwKdr", "sessionStats", "sessionStatsGame", "sessionStatsFinalKills",
        "sessionStatsBeds", "sessionStatsKills", "sessionStatsWins", "sessionStatsWinstreak",
        "sessionStatsSessionGames", "sessionStatsGameTime", "sessionStatsAvgTime", "sessionStatsSessionTime",
        "sessionStatsSlumberTickets", "sessionStatsTextShadow", "sessionStatsBgOpacity",
        "sessionStatsScale", "sessionStatsPosX", "sessionStatsPosY", "uiScalePercent", "overlayGamemodeEdit",
        "ui.language"
    };
    private static final int MAX_VALUE = 4096;
    private static final Map<String, Field> FIELDS = fields();
    private static Properties defaults;

    private AdninSharedConfig() { }

    /** Resolve only the shared profile location; never falls back to a client directory. */
    public static Path path() {
        if ("false".equals(System.getProperty("adnin.config.shared"))) return null;
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && local.length() > 0) return Paths.get(local, DIRECTORY, FILE);
        String home = System.getProperty("user.home", "");
        return home.length() == 0 ? null : Paths.get(home, ".config", DIRECTORY, FILE);
    }

    public static Properties read(Path path) {
        try {
            byte[] bytes = readBytes(path);
            if (bytes == null) return null;
            Properties p = new Properties();
            p.load(new ByteArrayInputStream(bytes));
            return p;
        } catch (Exception ignored) { return null; }
    }

    private static byte[] readBytes(Path path) {
        if (path == null) return null;
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 65536L) return null;
            byte[] bytes = new byte[65537];
            int size = 0;
            try (InputStream in = Files.newInputStream(path)) {
                int count;
                while (size < bytes.length && (count = in.read(bytes, size, bytes.length - size)) != -1) size += count;
            }
            if (size > 65536) return null;
            return java.util.Arrays.copyOf(bytes, size);
        } catch (Exception ignored) { return null; }
    }

    /** Import the current client's legacy settings only before the shared file exists. */
    public static boolean load(Path shared, Path legacy) {
        return load(shared, legacy, shared.getParent().resolve("payload"));
    }

    static boolean load(Path shared, Path legacy, Path legacyPayloadRoot) {
        boolean sharedExists = !Files.notExists(shared);
        Properties p = read(shared);
        if (p == null && !sharedExists) {
            // Previous standalone builds saved original keys beside a
            // hash-specific DLL. A new hash cannot use the old native loader.
            p = readLegacyNative(legacyPayloadRoot);
            Properties features = read(legacy);
            if (features != null) {
                if (p == null) p = new Properties();
                p.putAll(features);
            }
            AdninLanguage.loadSharedPreference();
        }
        apply(p, sharedExists);
        return !sharedExists;
    }

    private static final class LegacyFile {
        final Path path;
        final long modified;
        LegacyFile(Path path, long modified) { this.path = path; this.modified = modified; }
    }

    /** Bounded, one-level application-cache migration; never scans game folders. */
    static Properties readLegacyNative(Path root) {
        if (root == null || !Files.isDirectory(root)) return null;
        List<LegacyFile> candidates = new ArrayList<LegacyFile>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
            int count = 0;
            for (Path directory : directories) {
                if (++count > 256) break;
                String name = directory.getFileName().toString();
                if (!name.matches("[0-9a-fA-F]{64}") || !Files.isDirectory(directory) || Files.isSymbolicLink(directory)) continue;
                Path file = directory.resolve("toggles.json");
                if (Files.isRegularFile(file) && !Files.isSymbolicLink(file) && Files.size(file) <= 65536)
                    candidates.add(new LegacyFile(file, Files.getLastModifiedTime(file).toMillis()));
            }
        } catch (Exception ignored) { return null; }
        Collections.sort(candidates, new Comparator<LegacyFile>() {
            public int compare(LegacyFile a, LegacyFile b) { return Long.compare(b.modified, a.modified); }
        });
        for (LegacyFile candidate : candidates) {
            try {
                byte[] bytes = readBytes(candidate.path);
                if (bytes == null) continue;
                JsonElement parsed = new JsonParser().parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) continue;
                JsonObject json = parsed.getAsJsonObject();
                Properties p = new Properties();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    String name = entry.getKey();
                    if ("autoglEnabled".equals(name)) name = "autoGL";
                    if ("glMessage".equals(name)) name = "gl_message";
                    if (!(FIELDS.containsKey(name) || name.startsWith("overlayColumns")) || !entry.getValue().isJsonPrimitive()) continue;
                    String value = entry.getValue().getAsString();
                    if (value.length() <= MAX_VALUE) p.setProperty(name, value);
                }
                if (!p.isEmpty()) return p;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Called at the end of Gui4 initialization, before the legacy native loader. */
    public static void rememberDefaults() {
        if (defaults == null) defaults = captureGui();
    }

    /** An existing shared file is authoritative, including missing or invalid keys. */
    public static void apply(Properties p, boolean sharedExists) {
        if (sharedExists) {
            if (defaults == null) rememberDefaults();
            applyGui(defaults);
            AdninFeatures.loadOutputSettings(new Properties());
            AdninGui4.loadUiSettings(new Properties());
            AdninLanguage.load(new Properties());
            AdninAnticheat.loadSettings(new Properties());
        }
        if (p == null) return;
        applyGui(p);
        if (p.containsKey("chat.output") || p.containsKey("chat.output.players")) AdninFeatures.loadOutputSettings(p);
        if (p.containsKey("ui.scalePercent")) AdninGui4.loadUiSettings(p);
        if (p.containsKey("ui.language")) AdninLanguage.load(p);
        if (p.containsKey("anticheat.enabled")) AdninAnticheat.loadSettings(p);
    }

    private static void applyGui(Properties p) {
        if (p == null) return;
        for (String name : GUI_FIELDS) {
            if ("ui.language".equals(name)) continue;
            String value = p.getProperty(name);
            if (value != null) setField(name, value);
        }
        // Legacy keys used by the first Urchin/Bot persistence implementation.
        setIfPresent(p, "urchin.apiKey", "api_urchin");
        setIfPresent(p, "botDenicker.url", "botDenickerUrl");
        setIfPresent(p, "botDenicker.enabled", "botDenicker");
        setIfPresent(p, "sounds.clientSide", "clientSideSounds");
        if (p.containsKey("quickbuyBinds")) AdninGui4.setQuickbuyBinds(p.getProperty("quickbuyBinds", ""));
        normalize();
        AdninGui4.loadAllOverlayColumnsFromToggles(
            p.getProperty("overlayColumnsBedwars", ""), p.getProperty("overlayColumnsSkywars", ""),
            p.getProperty("overlayColumnsDuel", ""), p.getProperty("overlayColumnsBedwarsduels", ""),
            p.getProperty("overlayColumnsConfig", ""));
    }

    private static void setIfPresent(Properties p, String source, String target) {
        String value = p.getProperty(source);
        if (value != null && !p.containsKey(target)) setField(target, value);
    }

    private static Map<String, Field> fields() {
        Map<String, Field> fields = new LinkedHashMap<String, Field>();
        for (String name : GUI_FIELDS) {
            if ("ui.language".equals(name)) continue;
            try {
                Field f = AdninGui4.class.getDeclaredField(name);
                if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())) fields.put(name, f);
            } catch (Exception ignored) { }
        }
        return fields;
    }

    private static void setField(String name, String raw) {
        if (raw == null || raw.length() > MAX_VALUE) return;
        try {
            Field f = FIELDS.get(name);
            if (f == null) return;
            Class<?> type = f.getType();
            if (type == String.class) f.set(null, raw);
            else if (type == boolean.class) {
                if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) f.setBoolean(null, Boolean.parseBoolean(raw));
            }
            else if (type == int.class) f.setInt(null, Integer.parseInt(raw.trim()));
            else if (type == int[].class) f.set(null, parseInts(raw, (int[])f.get(null)));
            else if (type == boolean[].class) f.set(null, parseBools(raw, (boolean[])f.get(null)));
        } catch (Exception ignored) { }
    }

    private static int[] parseInts(String raw, int[] previous) {
        String[] values = raw.split(",", -1); int[] result = previous.clone();
        if (values.length != result.length) return result;
        for (int i = 0; i < result.length; i++) result[i] = Integer.parseInt(values[i].trim());
        return result;
    }

    private static boolean[] parseBools(String raw, boolean[] previous) {
        String[] values = raw.split(",", -1); boolean[] result = previous.clone();
        if (values.length != result.length) return result;
        for (int i = 0; i < result.length; i++) {
            String value = values[i].trim();
            if (!("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) return previous.clone();
            result[i] = Boolean.parseBoolean(value);
        }
        return result;
    }

    private static void normalize() {
        AdninGui4.uiScalePercent = AdninGui4.clampUiScale(AdninGui4.uiScalePercent);
        AdninGui4.overlayOpacity = clamp(AdninGui4.overlayOpacity, 0, 95);
        AdninGui4.hitboxThickness = clamp(AdninGui4.hitboxThickness, 1, 10);
        AdninGui4.quickbuyDelayMs = clamp(AdninGui4.quickbuyDelayMs, 0, 400);
        AdninGui4.holdRdKeyCode = clamp(AdninGui4.holdRdKeyCode, 0, 255);
        AdninGui4.sessionStatsBgOpacity = clamp(AdninGui4.sessionStatsBgOpacity, 0, 255);
        AdninGui4.sessionStatsScale = clamp(AdninGui4.sessionStatsScale, 50, 150);
        AdninGui4.sessionStatsPosX = clamp(AdninGui4.sessionStatsPosX, 0, 1000);
        AdninGui4.sessionStatsPosY = clamp(AdninGui4.sessionStatsPosY, 0, 1000);
        for (int i = 0; i < AdninGui4.quickbuyKeys.length; i++) {
            AdninGui4.quickbuyKeys[i] = clamp(AdninGui4.quickbuyKeys[i], 0, 255);
            AdninGui4.quickbuySlots[i] = clamp(AdninGui4.quickbuySlots[i], 0, 8);
        }
        AdninGui4.quickbuyBinds = AdninGui4.serializeQuickbuyBinds();
        AdninGui4.api_hypixel = AdninGui4.api_hypixel.trim();
        AdninGui4.api_seraph = AdninGui4.api_seraph.trim();
        AdninGui4.api_aurora = AdninGui4.api_aurora.trim();
        AdninGui4.api_urchin = AdninGui4.api_urchin.trim();
        AdninGui4.botDenickerUrl = AdninGui4.botDenickerUrl.trim();
        AdninGui4.overlayGamemodeEdit = AdninColumnOrder.mode(AdninGui4.overlayGamemodeEdit);
        if (!"tab".equals(AdninGui4.overlayResourceLocation) && !"scoreboard".equals(AdninGui4.overlayResourceLocation))
            AdninGui4.overlayResourceLocation = "tab";
        for (String name : new String[]{"overlayThemeColor", "configThemeColor", "configTextColor"}) {
            try {
                Field f = FIELDS.get(name); String value = (String)f.get(null);
                if (!value.matches("[0-9A-Fa-f]{6}")) f.set(null, defaults.getProperty(name));
            } catch (Exception ignored) { }
        }
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    /** Capture every client setting plus the legacy native/feature namespaces. */
    public static Properties capture() {
        Properties p = captureGui();
        AdninFeatures.saveOutputSettings(p);
        AdninGui4.saveUiSettings(p);
        AdninLanguage.save(p);
        AdninAnticheat.saveSettings(p);
        p.setProperty("sounds.clientSide", Boolean.toString(AdninGui4.clientSideSounds));
        return p;
    }

    private static Properties captureGui() {
        Properties p = new Properties();
        p.setProperty("overlayColumnsBedwars", AdninGui4.getOverlayColumnsBedwars());
        p.setProperty("overlayColumnsSkywars", AdninGui4.getOverlayColumnsSkywars());
        p.setProperty("overlayColumnsDuel", AdninGui4.getOverlayColumnsDuel());
        p.setProperty("overlayColumnsBedwarsduels", AdninGui4.getOverlayColumnsBedwarsduels());
        for (String name : GUI_FIELDS) {
            if ("ui.language".equals(name)) continue;
            try {
                Field f = FIELDS.get(name); Object value = f.get(null);
                if (value instanceof int[]) p.setProperty(name, join((int[])value));
                else if (value instanceof boolean[]) p.setProperty(name, join((boolean[])value));
                else if (value != null) p.setProperty(name, String.valueOf(value));
            } catch (Exception ignored) { }
        }
        p.setProperty("quickbuyBinds", AdninGui4.serializeQuickbuyBinds());
        return p;
    }

    private static String join(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) { if (i != 0) out.append(','); out.append(values[i]); }
        return out.toString();
    }
    private static String join(boolean[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) { if (i != 0) out.append(','); out.append(values[i]); }
        return out.toString();
    }

    public static boolean write(Path path, Properties p) {
        if (path == null || p == null) return false;
        Path temp = null;
        try {
            Files.createDirectories(path.getParent());
            temp = Files.createTempFile(path.getParent(), "config-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) { p.store(out, "Adnin shared configuration"); }
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (Exception ignored) {
            try { if (temp != null) Files.deleteIfExists(temp); } catch (Exception ignoredAgain) { }
            return false;
        }
    }
}
