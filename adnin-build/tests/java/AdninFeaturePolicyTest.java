import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Deque;
import java.util.Map;
import java.util.HashSet;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Pure scheduler boundaries and production-default guards; no Minecraft init. */
public final class AdninFeaturePolicyTest {
    private static int checks;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected source and classes directories");
        Path source = Paths.get(args[0]);
        String gui = text(source.resolve("AdninGui4.java"));
        String featureSource = text(source.resolve("AdninFeatures.java"));
        check(matches(gui, "public\\s+static\\s+String\\s+api_urchin\\s*=\\s*\"\"\\s*;"), "Urchin key default must be blank");
        check(matches(gui, "public\\s+static\\s+String\\s+botDenickerUrl\\s*=\\s*\"\"\\s*;"), "Bot URL default must be blank");
        check(matches(gui, "public\\s+static\\s+boolean\\s+botDenicker\\s*=\\s*false\\s*;"), "Bot default must be disabled");
        check(matches(gui, "public\\s+static\\s+boolean\\s+chatOutput\\s*=\\s*false\\s*;"), "Party output default must be disabled");
        for (String provider : new String[]{"hypixel", "seraph", "aurora", "urchin"}) {
            check(matches(gui, "public\\s+static\\s+String\\s+api_" + provider + "\\s*=\\s*\"\"\\s*;"),
                    "Every API key must start blank");
        }
        providerSelection();
        check(featureSource.contains("AdninGui4.botDenickerUrl = bounded(p.getProperty(\"botDenicker.url\", \"\"), 2048)"),
                "Bot URL is loaded only from the user's setting with an empty fallback");
        check(featureSource.contains("AdninGui4.api_urchin = bounded(p.getProperty(\"urchin.apiKey\", \"\"), 512)"),
                "Urchin key is loaded only from the user's setting with an empty fallback");
        check(featureSource.contains("AdninApi.errorCode(failure)"),
                "Urchin worker preserves the classified error category");
        check(featureSource.contains("AdninApi.urchinErrorMessage("),
                "Urchin chat uses fixed safe messages rather than exception text");
        int diagnosticsStart = featureSource.indexOf("private static void writeDiagnostics()");
        int diagnosticsEnd = featureSource.indexOf("\n    private ", diagnosticsStart + 1);
        int nextPublic = featureSource.indexOf("\n    public ", diagnosticsStart + 1);
        if (diagnosticsEnd < 0 || nextPublic >= 0 && nextPublic < diagnosticsEnd) diagnosticsEnd = nextPublic;
        check(diagnosticsStart >= 0 && diagnosticsEnd > diagnosticsStart, "Diagnostic method is present");
        String diagnostics = featureSource.substring(diagnosticsStart, diagnosticsEnd);
        HashSet<String> diagnosticNames = new HashSet<String>(Arrays.asList("initialized", "ticks", "apiCompleted",
                "nativeEvents", "tagRowsDrawn", "matchStarts", "urchinSucceeded", "urchinFailed", "urchinLastError"));
        Matcher fields = Pattern.compile("p\\.setProperty\\(\"([^\"]+)\",\\s*([^;]+)\\);").matcher(diagnostics);
        while (fields.find()) {
            check(diagnosticNames.remove(fields.group(1)), "Diagnostic fields contain only approved counters and fixed error categories");
            String value = fields.group(2);
            check(value.equals("\"true\"") || value.equals("urchinLastError")
                    || value.equals("Long.toString(matchStarts.get())")
                    || value.matches("(?:Long|Integer)\\.toString\\([A-Za-z]+\\)"),
                    "Diagnostic values never contain a player, API key, or URL");
        }
        check(diagnosticNames.isEmpty(), "All approved Urchin diagnostic counters are written");
        java.util.Properties moduleDiagnostics = new java.util.Properties();
        AdninReplay.diagnostics(moduleDiagnostics);
        AdninAnticheat.diagnostics(moduleDiagnostics);
        HashSet<String> moduleKeys = new HashSet<String>(Arrays.asList(
            "replayStatus", "replayObserved", "replayObservations", "replayFailures", "replayActors",
            "replayTabProfiles", "replayFormattedTabProfiles", "replayValidTabProfiles",
            "replayProfileRequests", "replayProfileCompleted", "replayProfileFailed",
            "replayProfileAbsent", "replayProfileLastError", "anticheatStatus",
            "replayRosterWorldActors", "replayRejectedSelf", "replayRejectedDead",
            "replayRejectedViewer", "replayRejectedType", "replayRejectedMissingProfile",
            "replayRejectedUnmatched", "replayRejectedConflictingAlias", "replayRejectedDuplicate",
            "replayAmbiguousTabAliases",
            "anticheatEnabled", "anticheatReplay", "anticheatTickCalls", "anticheatSampledTicks",
            "anticheatWorldActors", "anticheatAcceptedActors", "anticheatFlagDecisions",
            "anticheatReportDecisions", "anticheatCooldownEntries", "anticheatLastSampleAgeMs"));
        for (String name : moduleDiagnostics.stringPropertyNames()) {
            check(moduleKeys.remove(name), "Replay and detector diagnostics use an exact field allowlist");
            check(moduleDiagnostics.getProperty(name).matches("true|false|[a-z]+(?:-[a-z]+)*|-?[0-9]+"),
                "Module diagnostics contain only fixed statuses and scalar counters");
        }
        check(moduleKeys.isEmpty(), "All approved module diagnostics are present");
        Class<?> features = Class.forName("AdninFeatures", true, AdninFeaturePolicyTest.class.getClassLoader());
        Field requestedField = features.getDeclaredField("requested"); requestedField.setAccessible(true);
        Field queueField = features.getDeclaredField("requests"); queueField.setAccessible(true);
        Field cacheField = features.getDeclaredField("CACHE_MS"); cacheField.setAccessible(true);
        Map<String, Long> requested = (Map<String, Long>) requestedField.get(null);
        BlockingQueue<String[]> queue = (BlockingQueue<String[]>) queueField.get(null);
        Method schedule = features.getDeclaredMethod("schedule", String.class, String.class, String.class, String.class, long.class);
        schedule.setAccessible(true);
        Method currentJob = features.getDeclaredMethod("currentJob", String[].class); currentJob.setAccessible(true);
        Method matchStarted = features.getDeclaredMethod("matchStarted"); matchStarted.setAccessible(true);
        Field generation = field(features, "generation"), botGeneration = field(features, "botGeneration");
        Field currentMatch = field(features, "currentMatch");
        AtomicLong matchStarts = (AtomicLong) field(features, "matchStarts").get(null);
        Deque<String[]> matchRequests = (Deque<String[]>) field(features, "matchRequests").get(null);
        int oldGeneration = generation.getInt(null), oldBotGeneration = botGeneration.getInt(null);
        long oldMatch = currentMatch.getLong(null), oldStarts = matchStarts.get();
        long cache = cacheField.getLong(null), now = 1000000L;
        check(cache == 300000L, "Bot successful lookup cache remains five minutes");
        try {
            generation.setInt(null, 7); botGeneration.setInt(null, 11); currentMatch.setLong(null, 42);
            requested.clear(); queue.clear();
            schedule.invoke(null, "bot", "UnitPlayer", "UnitPlayer", "https://example.test/?q=<>", now);
            check(queue.size() == 1, "First Bot lookup enters queue");
            check(queue.peek().length == 6, "Bot jobs carry the six-field protocol");
            check("11".equals(queue.peek()[0]), "Bot job captures its independent generation");
            check("".equals(queue.peek()[5]), "Bot jobs have no match token dependency");
            queue.clear();
            schedule.invoke(null, "bot", "UNITPLAYER", "UnitPlayer", "https://example.test/?q=<>", now + cache - 1);
            check(queue.isEmpty(), "Bot success cached until five minutes");
            schedule.invoke(null, "bot", "UnitPlayer", "UnitPlayer", "https://example.test/?q=<>", now + cache);
            check(queue.size() == 1, "Bot success cache expires at five minutes");
            requested.clear(); queue.clear();
            // Seed an error-result Bot stamp and exercise the real scheduler's
            // timing boundary without initializing a Minecraft world.
            requested.put("bot:unitplayer", now - cache + 30000L);
            schedule.invoke(null, "bot", "UnitPlayer", "UnitPlayer", "https://example.test/?q=<>", now + 29999L);
            check(queue.isEmpty(), "Failed Bot lookup waits through 29,999 ms");
            schedule.invoke(null, "bot", "UnitPlayer", "UnitPlayer", "https://example.test/?q=<>", now + 30000L);
            check(queue.size() == 1, "Failed Bot lookup retries at 30,000 ms");
            check("https://example.test/?q=<>".equals(queue.peek()[4]), "Bot scheduler passes only its captured fixture URL");

            requested.clear(); queue.clear();
            schedule.invoke(null, "urchin", "UnitPlayer", "UnitPlayer", "unit-test-not-secret", now);
            schedule.invoke(null, "urchin", "UnitPlayer", "UnitPlayer", "unit-test-not-secret", now + 900000L);
            check(queue.isEmpty(), "Generic scheduler cannot request Urchin even after its TTL");
            check(requested.isEmpty(), "Rejected Urchin scheduling cannot create generic cache stamps");

            String[] urchin = {"7", "urchin", "UnitPlayer", "UnitPlayer", "unit-test-not-secret", "42"};
            String[] bot = {"11", "bot", "UnitPlayer", "UnitPlayer", "https://example.test/?q=<>", ""};
            check(job(currentJob, urchin), "Current Urchin epoch and captured match accepted");
            check(job(currentJob, bot), "Current Bot epoch accepted independently of match");
            currentMatch.setLong(null, 43);
            check(!job(currentJob, urchin), "Previous match Urchin job rejected before HTTP");
            check(job(currentJob, bot), "Match transition does not reject a current Bot job");
            currentMatch.setLong(null, 42);
            botGeneration.setInt(null, 12);
            check(job(currentJob, urchin), "Bot settings change does not invalidate Urchin batch");
            check(!job(currentJob, bot), "Bot settings change rejects old Bot job");
            botGeneration.setInt(null, 11); generation.setInt(null, 8);
            check(!job(currentJob, urchin), "Urchin key epoch change rejects old credential job");
            check(job(currentJob, bot), "Urchin key epoch does not invalidate a current Bot job");
            generation.setInt(null, 7);

            check(!field(features, "initialized").getBoolean(null), "Policy checks do not initialize Minecraft integration");
            check(field(features, "settingsPath").get(null) == null, "Policy checks do not access settings files");
            matchStarts.set(100);
            matchRequests.clear();
            String[] frozen = {"7", "urchin", "FrozenPlayer", "FrozenPlayer", "unit-test-not-secret", "42"};
            matchRequests.addLast(frozen);
            matchStarted.invoke(null);
            check(matchStarts.get() == 101, "Native match callback advances AtomicLong serial once");
            check(currentMatch.getLong(null) == 42, "Native callback leaves active client-thread match untouched");
            check(generation.getInt(null) == 7 && botGeneration.getInt(null) == 11, "Native callback cannot alter credential epochs");
            check(queue.isEmpty(), "Native callback cannot queue network requests");
            check(matchRequests.size() == 1 && matchRequests.peekFirst() == frozen, "Native callback cannot rebuild frozen roster batch");
            check(!field(features, "initialized").getBoolean(null), "Native callback does not start API worker");
            check(field(features, "settingsPath").get(null) == null, "Native callback does not initialize file persistence");
            matchStarted.invoke(null);
            check(matchStarts.get() == 102 && queue.isEmpty(), "Repeated callbacks remain serial-only until client tick");
            check(features.getDeclaredField("pendingSave").getType() == java.util.concurrent.atomic.AtomicReference.class,
                    "Pending config writes use atomic compare-and-clear storage");
        } finally {
            requested.clear(); queue.clear(); matchRequests.clear();
            generation.setInt(null, oldGeneration); botGeneration.setInt(null, oldBotGeneration);
            currentMatch.setLong(null, oldMatch); matchStarts.set(oldStarts);
        }
        System.out.println("AdninFeaturePolicyTest: " + checks + " checks passed; Bot-only scheduler, independent epochs, Urchin match gates and serial-only native callbacks verified without network or game initialization");
    }

    private static void providerSelection() {
        String hypixel = AdninGui4.api_hypixel, seraph = AdninGui4.api_seraph;
        String aurora = AdninGui4.api_aurora, urchin = AdninGui4.api_urchin;
        boolean proxy = AdninGui4.vegaProxy;
        try {
            AdninGui4.vegaProxy = false;
            AdninGui4.api_seraph = AdninGui4.api_aurora = AdninGui4.api_urchin = "test-key";
            for (String missing : new String[]{null, "", " ", "\t\n\r", "  \t  "}) {
                AdninGui4.api_hypixel = missing;
                check("missing-hypixel-key".equals(AdninFeatures.statsProviderStatus()),
                        "Other provider credentials cannot enable direct Hypixel stats");
                check(AdninGui4.api_hypixel == missing, "Status never changes the visible credential");
            }
            for (int whitespace = 0; whitespace <= 32; whitespace++) {
                AdninGui4.api_hypixel = String.valueOf((char) whitespace);
                check("missing-hypixel-key".equals(AdninFeatures.statsProviderStatus()),
                        "Readiness uses the same ASCII trim range as visible key normalization");
            }
            for (String configured : new String[]{"test-key", "  test-key\t", "\u00a0"}) {
                AdninGui4.api_hypixel = configured;
                check("hypixel-direct".equals(AdninFeatures.statsProviderStatus()),
                        "Nonempty trimmed field selects direct but makes no authentication claim");
                check(AdninGui4.api_hypixel == configured, "Status never retains a normalized key copy");
            }
            AdninGui4.api_hypixel = "test-key";
            AdninGui4.vegaProxy = true;
            check("vega-proxy".equals(AdninFeatures.statsProviderStatus()),
                    "Explicit proxy overrides a configured direct key");
            AdninGui4.api_hypixel = null;
            check("vega-proxy".equals(AdninFeatures.statsProviderStatus()),
                    "Explicit proxy selection does not need the direct Hypixel field");
            AdninGui4.vegaProxy = false;
            check("missing-hypixel-key".equals(AdninFeatures.statsProviderStatus()),
                    "Disabling proxy with no direct key disables new stats requests");
            AdninGui4.api_hypixel = "test-key";
            check("hypixel-direct".equals(AdninFeatures.statsProviderStatus()), "Direct status uses the current field");
            AdninGui4.api_hypixel = AdninGui4.api_seraph = AdninGui4.api_aurora = AdninGui4.api_urchin = "";
            check("missing-hypixel-key".equals(AdninFeatures.statsProviderStatus()),
                    "Clearing every visible key cannot leave a hidden status credential");
        } finally {
            AdninGui4.api_hypixel = hypixel; AdninGui4.api_seraph = seraph;
            AdninGui4.api_aurora = aurora; AdninGui4.api_urchin = urchin;
            AdninGui4.vegaProxy = proxy;
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception { Field value = owner.getDeclaredField(name); value.setAccessible(true); return value; }
    private static boolean job(Method method, String[] value) throws Exception { return ((Boolean) method.invoke(null, (Object) value)).booleanValue(); }

    private static String text(Path file) throws IOException { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
    private static boolean matches(String input, String regex) { return Pattern.compile(regex).matcher(input).find(); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
