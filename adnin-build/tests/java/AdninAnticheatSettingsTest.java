import java.util.Properties;
import net.minecraft.util.ChatComponentText;

/** Settings-only adapter checks: no game initialization, chat or network activity. */
public final class AdninAnticheatSettingsTest {
    private static int checks;
    public static void main(String[] args) {
        Properties original = new Properties();
        AdninAnticheat.saveSettings(original);
        try {
            AdninAnticheat.loadSettings(new Properties());
            check(!AdninAnticheat.enabled && !AdninAnticheat.autoReport,
                    "Detection and auto report default off");
            check(AdninAnticheat.flagSound && AdninAnticheat.autoBlock && AdninAnticheat.noFall
                    && AdninAnticheat.noSlow && AdninAnticheat.scaffold && AdninAnticheat.legitScaffold,
                    "All original detector toggles and alert sound default on");
            check(!AdninAnticheat.ignoreTeammates && !AdninAnticheat.atlasOnly,
                    "Additional exemptions default off");
            check(AdninAnticheat.intervalSeconds == 20 && AdninAnticheat.ignoredPlayers.isEmpty(),
                    "Interval and friend defaults");
            Properties p = new Properties();
            p.setProperty("anticheat.enabled", "true");
            p.setProperty("anticheat.autoReport", "true");
            p.setProperty("anticheat.flagSound", "false");
            p.setProperty("anticheat.noFall", "false");
            p.setProperty("anticheat.intervalSeconds", "61");
            p.setProperty("anticheat.ignoredPlayers", "PlayerA, playera, Friend_2, /wdr x");
            p.setProperty("anticheat.addEnemies", "true");
            AdninAnticheat.loadSettings(p);
            check(AdninAnticheat.enabled && AdninAnticheat.autoReport, "Explicit opt-in options load");
            check(!AdninAnticheat.flagSound && !AdninAnticheat.noFall, "Disabled checks load");
            check(AdninAnticheat.intervalSeconds == 60, "Interval high bound enforced");
            check("playera,friend_2".equals(AdninAnticheat.ignoredPlayers), "Ignored names canonicalized");
            Properties saved = new Properties(); saved.setProperty("unrelated.fixture", "preserve");
            AdninAnticheat.saveSettings(saved);
            check("preserve".equals(saved.getProperty("unrelated.fixture")), "Save preserves unrelated properties");
            check(saved.size() == 13, "Only the twelve supported settings are saved");
            check(!saved.containsKey("anticheat.addEnemies"), "Fresh save cannot restore the removed enemy option");
            p.setProperty("unrelated.fixture", "preserve");
            AdninAnticheat.saveSettings(p);
            check(!p.containsKey("anticheat.addEnemies"), "Saving a reused old Properties object removes the retired key");
            check("preserve".equals(p.getProperty("unrelated.fixture")), "Old-key migration preserves unrelated settings");
            for (java.lang.reflect.Field field : AdninAnticheat.class.getDeclaredFields())
                check(!field.getName().equals("addEnemies"), "Adapter has no enemy configuration field");
            for (java.lang.reflect.Method method : AdninAnticheat.class.getDeclaredMethods())
                check(!method.getName().equals("isEnemy") && !method.getName().equals("enemyNames"),
                        "Adapter has no enemy API");
            AdninAnticheat.loadSettings(saved);
            Properties roundtrip = new Properties(); AdninAnticheat.saveSettings(roundtrip);
            saved.remove("unrelated.fixture");
            check(roundtrip.equals(saved), "Settings round trip preserves every setting");
            p = new Properties();
            p.setProperty("anticheat.enabled", "not-a-boolean");
            p.setProperty("anticheat.flagSound", "not-a-boolean");
            p.setProperty("anticheat.intervalSeconds", "NaN");
            AdninAnticheat.loadSettings(p);
            check(!AdninAnticheat.enabled && AdninAnticheat.flagSound && AdninAnticheat.intervalSeconds == 20,
                    "Malformed values restore safe defaults");
            p.setProperty("anticheat.intervalSeconds", "-2"); AdninAnticheat.loadSettings(p);
            check(AdninAnticheat.intervalSeconds == 0, "Zero interval allowed and lower bound enforced");
            AdninAnticheat.loadSettings(null);
            check(!AdninAnticheat.enabled, "Null properties safe defaults");
            AdninAnticheat.saveSettings(null);
            AdninAnticheat.shutdown();
            check(AdninAnticheat.nameColor(new ChatComponentText("\u00a7a[VIP] \u00a79VIP"), "VIP") == '9',
                    "Teammate color uses actual name rather than the matching rank text");
            check(AdninAnticheat.nameColor(new ChatComponentText("\u00a7cPrefixPlayerA \u00a7aPlayerA"), "PlayerA") == 'a',
                    "Teammate color uses a full account-name token");
            check(AdninAnticheat.nameColor(new ChatComponentText("\u00a7cPlayerAB"), "PlayerA") == 0,
                    "Partial name cannot establish teammate color");
            check(AdninAnticheat.nameColor(new ChatComponentText("PlayerA"), "PlayerA") == 0,
                    "Uncolored text cannot establish a team");
            check(AdninAnticheat.nameColor(new ChatComponentText("\u00a7a[VIP] \u00a7rPlayerA"), "PlayerA") == 0,
                    "Format reset prevents inherited rank color from becoming a team");
            clockAndHistory();
            System.out.println("AdninAnticheatSettingsTest: " + checks + " checks passed");
        } finally { AdninAnticheat.loadSettings(original); }
    }
    private static void clockAndHistory() {
        check(AdninAnticheat.elapsedMillis(-9000000000L, -8999000000L) == 2L,
                "Elapsed clock permits negative raw nanoTime origins");
        check(AdninAnticheat.elapsedMillis(Long.MAX_VALUE - 500000L, Long.MIN_VALUE + 499999L) == 2L,
                "Elapsed clock subtraction safely handles nanoTime wrapping across signed zero");
        check(AdninAnticheat.elapsedMillis(5000000L, 5000000L) == 1L,
                "Packet sentinel zero remains distinct from the first elapsed millisecond");
        long before = AdninAnticheat.monotonicMillis();
        AdninAnticheat.packetReceived();
        long packet = ((Long) field("lastClientBoundPacket")).longValue();
        long after = AdninAnticheat.monotonicMillis();
        check(packet > 0 && packet >= before && packet <= after,
                "Packet callback and detector clock use the same positive elapsed timebase");
        AdninAnticheat.shutdown();
        check(((Long) field("lastClientBoundPacket")).longValue() == 0,
                "Disconnect/shutdown clears old packet freshness");
        Properties p = new Properties(); p.setProperty("anticheat.enabled", "true");
        p.setProperty("anticheat.autoReport", "true");
        AdninAnticheat.loadSettings(p);
        AdninAnticheatCore.Engine engine = (AdninAnticheatCore.Engine) field("engine");
        AdninAnticheatCore.Settings cfg = new AdninAnticheatCore.Settings();
        cfg.enabled = cfg.autoReport = true;
        java.util.UUID id = java.util.UUID.fromString("12345678-1234-4234-9234-123456789abc");
        Object identity = new Object();
        AdninAnticheatCore.Alert result = null;
        for (int tick = 1; tick <= 10; tick++) {
            AdninAnticheatCore.Snapshot s = new AdninAnticheatCore.Snapshot();
            s.tick = tick; s.now = 10000 + tick * 50; s.name = "Fixture";
            s.currentTab = s.realProfile = s.swinging = s.blocking = true;
            result = engine.sample(id, identity, s, cfg);
        }
        check(result != null && result.autoReport && engine.cooldownPlayers() == 1,
                "Owned pure fixture seeds one adapter-engine report history without sending anything");
        AdninAnticheat.packetReceived();
        p.setProperty("anticheat.flagSound", "false");
        AdninAnticheat.loadSettings(p);
        check(engine.trackedPlayers() == 0 && engine.cooldownPlayers() == 1,
                "Actual loadSettings clears evidence but preserves active-world report history");
        check(((Long) field("lastClientBoundPacket")).longValue() == 0,
                "Applying settings clears packet evidence before a new baseline");
        p.setProperty("anticheat.enabled", "false"); AdninAnticheat.loadSettings(p);
        p.setProperty("anticheat.enabled", "true"); AdninAnticheat.loadSettings(p);
        check(engine.cooldownPlayers() == 1,
                "Actual settings off/on cannot erase UUID report cooldowns");
        AdninAnticheat.resetEvidence();
        check(engine.cooldownPlayers() == 1 && engine.trackedPlayers() == 0,
                "Actual exception recovery retains cooldowns and retires sampling");
        Properties diagnostic = new Properties(); AdninAnticheat.diagnostics(diagnostic);
        check("evidence-reset".equals(diagnostic.getProperty("anticheatStatus"))
                && "1".equals(diagnostic.getProperty("anticheatCooldownEntries")),
                "Diagnostics expose accurate local state counts");
        check(!diagnostic.toString().contains("Fixture") && !diagnostic.toString().contains(id.toString()),
                "Diagnostics contain neither player identity nor commands");
        AdninAnticheat.diagnostics(null);
        AdninAnticheat.shutdown();
        check(engine.cooldownPlayers() == 0, "A real lifecycle shutdown retires all histories");
    }
    private static Object field(String name) {
        try {
            java.lang.reflect.Field field = AdninAnticheat.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
