import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** Owned temporary profiles only; no user settings, game, HTTP or message delivery. */
public final class AdninSharedConfigTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Properties defaults = AdninSharedConfig.capture();
        for (String name : credentials()) eq("", defaults.getProperty(name), "Blank production credential");
        Set<String> transientFields = new HashSet<String>(Arrays.asList("sessionStatsResetPending", "overlayGamemodeActive"));
        for (Field f : AdninGui4.class.getDeclaredFields()) {
            int modifiers = f.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)
                    && !transientFields.contains(f.getName()))
                check(defaults.containsKey(f.getName()), "Every public client setting is captured");
        }
        Path root = Files.createTempDirectory("adnin-shared-owned-");
        try {
            Path common = root.resolve("common/config.properties");
            Path lunar = root.resolve("lunar/adnin-features.properties");
            Path badlion = root.resolve("badlion/adnin-features.properties");
            Properties legacy = new Properties();
            Path payloads = root.resolve("common/payload");
            StringBuilder hash = new StringBuilder();
            for (int i = 0; i < 64; i++) hash.append('a');
            Path original = payloads.resolve(hash.toString()).resolve("toggles.json");
            Files.createDirectories(original.getParent());
            String json = "{\"api_hypixel\":\"test-api-key\",\"api_seraph\":\"test-secret\","
                + "\"api_aurora\":\"synthetic-key\",\"partyQueueDetector\":true,"
                + "\"autoglEnabled\":true,\"glMessage\":\"fixture message\",\"overlayColumnsBedwars\":\"name,hp,ping,fkdr\"}";
            Files.write(original, json.getBytes(StandardCharsets.UTF_8));
            Path older = payloads.resolve(hash.toString().replace('a', 'b')).resolve("toggles.json");
            Files.createDirectories(older.getParent());
            Files.write(older, "{\"api_hypixel\":\"fixture-key\"}".getBytes(StandardCharsets.UTF_8));
            Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1000));
            legacy.setProperty("urchin.apiKey", "test-key");
            String endpoint = "https://example.test/api/users?q=<>&page=0";
            legacy.setProperty("botDenicker.url", endpoint);
            legacy.setProperty("botDenicker.enabled", "true");
            legacy.setProperty("sounds.clientSide", "true");
            legacy.setProperty("chat.output.players", "true");
            legacy.setProperty("chat.output.denick", "false");
            legacy.setProperty("ui.scalePercent", "115");
            legacy.setProperty("ui.language", "zh_TW");
            legacy.setProperty("anticheat.enabled", "true");
            check(AdninSharedConfig.write(lunar, legacy), "Owned Lunar legacy fixture");
            legacy.setProperty("urchin.apiKey", "fixture-key");
            check(AdninSharedConfig.write(badlion, legacy), "Owned Badlion legacy fixture");
            // A changed DLL hash starts without native credentials in its new directory.
            check(AdninSharedConfig.load(common, lunar), "Missing common profile requires one migration");
            eq("test-key", AdninGui4.api_urchin, "Urchin migrated");
            eq(endpoint, AdninGui4.botDenickerUrl, "URL placeholder preserved");
            check(AdninGui4.botDenicker && AdninGui4.clientSideSounds, "Legacy toggles migrated");
            eq("test-api-key", AdninGui4.api_hypixel, "Native credential preserved during migration");
            check(AdninGui4.partyQueueDetector, "Native option preserved during migration");
            check(AdninGui4.autoGL, "Legacy native JSON alias migrated");
            eq("fixture message", AdninGui4.gl_message, "Legacy message alias migrated");
            eq("name,hp,ping,fkdr", AdninGui4.getOverlayColumnsBedwars(), "Legacy native columns migrated");
            check(AdninGui4.chatOutput && !AdninGui4.chatOutputDenick, "Output categories migrated independently");
            eq(115, AdninGui4.uiScalePercent, "UI scale migrated");
            eq("zh_TW", AdninLanguage.getLanguage(), "Language migrated");
            AdninGui4.overlayGamemodeEdit = "skywars";
            AdninGui4.loadAllOverlayColumnsFromToggles("name,hp,urchin,fklv,fkdr",
                "name,hp,urchin,sw_kdr", "name,hp,seraph,duel_wins", "name,hp,ping,bwd_index", "");
            AdninGui4.quickbuyKeys[0] = 45;
            AdninGui4.quickbuySlots[0] = 3;
            AdninGui4.quickbuyTurbo[0] = true;
            AdninGui4.sessionStatsScale = 125;
            Properties saved = AdninSharedConfig.capture();
            check(AdninSharedConfig.write(common, saved), "Unified atomic save");
            Properties read = AdninSharedConfig.read(common);
            check(saved.equals(read), "On-disk round trip retains URLs, arrays and all namespaces");
            AdninGui4.api_urchin = "fixture-key";
            AdninGui4.api_hypixel = "fixture-key";
            AdninGui4.overlayGamemodeEdit = "bedwars";
            AdninGui4.quickbuyKeys[0] = 0;
            check(!AdninSharedConfig.load(common, badlion), "Existing common file skips Badlion migration");
            check(saved.equals(AdninSharedConfig.capture()), "Other client restores the same complete configuration");
            eq("test-key", AdninGui4.api_urchin, "Different client's legacy Urchin cannot override shared key");
            eq(45, AdninGui4.quickbuyKeys[0], "Quickbuy state restored");
            eq("name,hp,urchin,fklv,fkdr", AdninGui4.getOverlayColumnsBedwars(), "Bedwars order shared");
            eq("name,hp,urchin,sw_kdr", AdninGui4.getOverlayColumnsSkywars(), "Skywars order shared");
            eq("name,hp,seraph,duel_wins", AdninGui4.getOverlayColumnsDuel(), "Duel order shared");
            eq("name,hp,ping,bwd_index", AdninGui4.getOverlayColumnsBedwarsduels(), "BW Duel order shared");
            for (String name : credentials()) read.setProperty(name, "");
            check(AdninSharedConfig.write(common, read), "Cleared credentials saved");
            for (String name : credentials()) AdninGui4.class.getField(name).set(null, "fixture-key");
            AdninSharedConfig.load(common, badlion);
            for (String name : credentials()) eq("", AdninGui4.class.getField(name).get(null), "Cleared common credential wins");
            // A partial or corrupt existing file must never revive legacy credentials.
            Properties partial = new Properties(); partial.setProperty("partyQueueDetector", "true");
            check(AdninSharedConfig.write(common, partial), "Owned partial profile");
            AdninGui4.api_urchin = "fixture-key";
            AdninSharedConfig.load(common, lunar);
            for (String name : credentials()) eq("", AdninGui4.class.getField(name).get(null), "Missing keys default blank");
            check(AdninGui4.partyQueueDetector && !AdninGui4.botDenicker, "Partial profile applies safe defaults");
            eq("en", AdninLanguage.getLanguage(), "Absent shared language uses default");
            Files.write(common, "api_urchin=\\uBADZ\n".getBytes(StandardCharsets.US_ASCII));
            AdninGui4.api_hypixel = "fixture-key";
            check(!AdninSharedConfig.load(common, lunar), "Invalid existing profile never migrates");
            for (String name : credentials()) eq("", AdninGui4.class.getField(name).get(null), "Malformed file clears legacy credentials");
            Files.write(common, new byte[65537]);
            check(AdninSharedConfig.read(common) == null, "Oversized profile rejected");
            AdninGui4.api_urchin = "fixture-key";
            AdninSharedConfig.load(common, lunar);
            eq("", AdninGui4.api_urchin, "Oversized file cannot restore legacy secrets");
            Properties invalid = new Properties();
            invalid.setProperty("ui.scalePercent", "999999");
            invalid.setProperty("sessionStatsScale", "99999");
            invalid.setProperty("sessionStatsBgOpacity", "220");
            invalid.setProperty("hitboxThickness", "8");
            invalid.setProperty("overlayOpacity", "-100");
            invalid.setProperty("overlayResourceLocation", "other");
            invalid.setProperty("overlayGamemodeEdit", "other");
            invalid.setProperty("overlayThemeColor", "invalid");
            invalid.setProperty("quickbuyKeys", "1,wrong");
            invalid.setProperty("quickbuyBinds", "wool:999:999:1");
            invalid.setProperty("botDenicker", "not-bool");
            AdninSharedConfig.apply(invalid, true);
            eq(140, AdninGui4.uiScalePercent, "Scale clamped");
            eq(150, AdninGui4.sessionStatsScale, "Session scale clamped to menu range");
            eq(220, AdninGui4.sessionStatsBgOpacity, "Valid existing opacity preserved");
            eq(8, AdninGui4.hitboxThickness, "Valid existing thickness preserved");
            eq(0, AdninGui4.overlayOpacity, "Opacity clamped");
            eq("tab", AdninGui4.overlayResourceLocation, "Location validated");
            eq("bedwars", AdninGui4.overlayGamemodeEdit, "Mode validated");
            eq(defaults.getProperty("overlayThemeColor"), AdninGui4.overlayThemeColor, "Invalid color defaults");
            check(!AdninGui4.botDenicker, "Invalid boolean cannot enable feature");
            eq(255, AdninGui4.quickbuyKeys[0], "Binding key clamped");
            eq(8, AdninGui4.quickbuySlots[0], "Binding slot clamped");
            Properties text = new Properties();
            text.setProperty("gl_message", "fixture\nline=two\u4e2d\u6587");
            check(AdninSharedConfig.write(common, text), "Escaped settings written");
            check(text.equals(AdninSharedConfig.read(common)), "Unicode and multiline round trip");
            check(Files.exists(lunar) && Files.exists(badlion), "Legacy files preserved");
            check(Files.exists(original) && Files.exists(older), "Old payload settings preserved");
            Files.write(original, "not-json".getBytes(StandardCharsets.UTF_8));
            eq("fixture-key", AdninSharedConfig.readLegacyNative(payloads).getProperty("api_hypixel"),
                "Malformed newest native file falls back during first migration only");
            try (java.util.stream.Stream<Path> files = Files.list(common.getParent())) {
                check(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Atomic temporary files retired");
            }
            check(AdninSharedConfig.path() == null, "Production user path disabled in tests");
        } finally {
            AdninSharedConfig.apply(defaults, true);
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path path : (Iterable<Path>)files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        System.out.println("AdninSharedConfigTest: " + checks + " checks passed; shared migration, credential authority, full client options, columns, validation and atomic persistence; owned files only");
    }
    private static String[] credentials() { return new String[]{"api_hypixel", "api_seraph", "api_aurora", "api_urchin", "botDenickerUrl"}; }
    private static void eq(Object a, Object b, String why) { check(a.equals(b), why); }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
