import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import net.minecraft.client.Minecraft;

/** Owned-memory Bot cache regression checks. No worker, game initialization, settings, network or sends. */
public final class AdninBotCacheTest {
    private static final long NOW = 1000000L, SUCCESS = 600000L, FAILURE = 45000L;
    private static final int EPOCH = 29;
    private static final String NAME = "UnitNick", REAL = "RealPlayer";
    private static final String UUID4 = "12345678-1234-4234-8234-123456789abc";
    private static final String PROVIDER = "https://fixture.invalid/users?q=<>&page=0";
    private static final String OTHER = "https://other.invalid/users?q=<>&page=0";
    private static int checks;
    private static final Method APPLY = method("applyBotResult", String[].class, long.class, Minecraft.class);
    private static final Method CACHED = method("cachedBotMessage", String.class, long.class);
    private static final Method SCHEDULE = method("schedule", String.class, String.class, String.class, String.class, long.class);
    private static final Method RETIRE = method("retireFailedPublication", String[].class);
    private static final Method PUBLISH = method("publishResult", String[].class, String[].class);

    public static void main(String[] args) throws Exception {
        offline();
        timeToLive();
        responseOwnership();
        matchPresentation();
        nativeCacheReads();
        admissionAndRetirement();
        boundedIdentityRecords();
        offline();
        shutdown();
        offline();
        System.out.println("AdninBotCacheTest: " + checks
                + " checks passed; real result/cache/scheduler paths, precise TTL boundaries, cross-match presentation, provider isolation and bounded unload; no IO or sends");
    }

    private static void timeToLive() throws Exception {
        for (String[] outcome : new String[][] {
                {"", REAL, UUID4, "verified", Long.toString(SUCCESS)},
                {"", "", "", "empty", Long.toString(SUCCESS)},
                {"request-failed", "", "", "failure", Long.toString(FAILURE)},
                {"profile-unavailable", REAL, "", "failure", Long.toString(FAILURE)},
                {"", REAL, "not-a-uuid", "failure", Long.toString(FAILURE)}
        }) {
            reset();
            long ttl = Long.parseLong(outcome[4]);
            apply(result(NAME, outcome[0], outcome[1], outcome[2], PROVIDER), NOW);
            Object entry = cache().get("unitnick");
            check(entry != null, "Every authoritative outcome has one atomic cache record");
            eq(outcome[3], entry(entry, "status"), "Outcome retains its status with its lifetime");
            eq(PROVIDER, entry(entry, "provider"), "Outcome retains its originating provider");
            eq(NOW + ttl, entry(entry, "expires"), "Success/empty use ten minutes and failures use forty-five seconds");
            eq(NOW - SUCCESS + ttl, requested().get("bot:unitnick"), "Scheduler and cache use identical expiry");
            eq("verified".equals(outcome[3]) ? REAL + "|" + UUID4 : "", entry(entry, "profile"), "Only verified online identities are exposed");
            eq("", cached(NAME, NOW + ttl - 1), "Fresh departed identity is a hit without presentation");
            eq(null, cached(NAME, NOW + ttl), "Cache expires at the exact TTL boundary");
            schedule(NAME, NOW + ttl - 1);
            check(requests().isEmpty(), "No request before the exact TTL boundary");
            schedule(NAME, NOW + ttl);
            eq(1, requests().size(), "One retry is admitted at the exact TTL boundary");
            String[] job = requests().poll();
            eq(Integer.toString(EPOCH), job[0], "Retry uses the current Bot epoch");
            eq(PROVIDER, job[4], "Retry keeps its current provider");
            schedule(NAME, NOW + ttl + 1);
            check(requests().isEmpty(), "A queued retry does not admit a duplicate");
        }
    }

    private static void responseOwnership() throws Exception {
        reset();
        String[] stale = result(NAME, "", REAL, UUID4, PROVIDER);
        stale[0] = Integer.toString(EPOCH - 1);
        apply(stale, NOW);
        assertNoIdentity("A prior epoch result cannot populate any identity state");
        apply(result(NAME, "", REAL, UUID4, OTHER), NOW);
        assertNoIdentity("A different provider result cannot populate identity state");
        apply(result(NAME, "", REAL, UUID4.toUpperCase(java.util.Locale.ROOT), PROVIDER), NOW);
        eq(REAL + "|" + UUID4, profiles().get("unitnick"), "Verified UUID is normalized once for the native mirror");
        check(announced().isEmpty(), "A departed player result is cached without an announcement");
        eq(null, AdninFeatures.pollPartyCommand(NOW), "A departed player result creates no party output");
        check(requests().isEmpty() && hints().isEmpty(), "Applying a departed result creates no additional work");
        Object accepted = cache().get("unitnick");
        field("urlSnapshot").set(null, OTHER);
        eq(null, cached(NAME, NOW + 1), "A setting change hides the old provider immediately");
        apply(result(NAME, "", "AnotherPlayer", UUID4, PROVIDER), NOW + 1);
        check(cache().get("unitnick") == accepted, "Late result cannot replace another provider's current cache record");
        field("urlSnapshot").set(null, "");
        apply(result(NAME, "", "AnotherPlayer", UUID4, PROVIDER), NOW + 2);
        check(cache().get("unitnick") == accepted, "Clearing a URL rejects a late response containing the old URL");
        field("urlSnapshot").set(null, OTHER);
        apply(result(NAME, "", "AnotherPlayer", UUID4, OTHER), NOW + 3);
        eq(OTHER, entry(cache().get("unitnick"), "provider"), "A current provider result replaces the complete atomic record");
        eq("AnotherPlayer|" + UUID4, profiles().get("unitnick"), "Mirror follows the accepted provider result");
    }

    private static void matchPresentation() throws Exception {
        reset();
        apply(result(NAME, "", REAL, UUID4, PROVIDER), NOW);
        Object accepted = cache().get("unitnick");
        present().add("unitnick");
        names().put("unitnick", "\u00a7a" + NAME);
        AdninFeatures.setGameActive(false);
        eq("", cached(NAME, NOW + 1), "A cached identity does not announce outside a game or Replay");
        check(announced().isEmpty(), "A lobby cache read does not consume later game presentation");
        AdninFeatures.matchStarted();
        String first = cached(NAME, NOW + 2);
        check(first != null && first.contains(NAME) && first.contains(REAL), "A re-observed cached identity produces its existing colored message");
        eq("", cached(NAME, NOW + 3), "The same identity presents at most once in one match");
        eq(1, announced().size(), "Presentation uses a single identity/outcome marker");
        schedule(NAME, NOW + 4);
        check(requests().isEmpty(), "Presentation-only cache reuse schedules no HTTP work");
        candidates().put("unitnick", NOW);
        hints().offer(new String[]{Integer.toString(EPOCH), NAME});
        AdninFeatures.matchStarted();
        check(cache().get("unitnick") == accepted, "A new match preserves the exact completed cache entry");
        check(candidates().isEmpty() && announced().isEmpty() && hints().isEmpty(),
                "New match clears presentation throttles, announcements and unconsumed prior-roster hints");
        eq(first, cached(NAME, NOW + 5), "The later match can present the cached result once again");
        eq("", cached(NAME, NOW + 6), "The later match also deduplicates its presentation");
        check(requests().isEmpty() && hints().isEmpty(), "Cross-match presentation itself remains entirely in memory");
        eq(null, AdninFeatures.pollPartyCommand(NOW + 6), "Message formatting never enqueues or sends on its own");
    }

    private static void nativeCacheReads() throws Exception {
        for (String error : new String[]{"", "request-failed", "profile-unavailable"}) {
            reset();
            long now = System.currentTimeMillis();
            apply(result(NAME, error, "profile-unavailable".equals(error) ? REAL : "", "", PROVIDER), now);
            for (int i = 0; i < 100; i++) eq("", AdninFeatures.getBotProfile(NAME), "Negative native reads expose no identity");
            check(hints().isEmpty() && requests().isEmpty() && candidates().isEmpty(), "Repeated negative cache reads generate no hints or requests");
        }
        reset();
        long now = System.currentTimeMillis();
        apply(result(NAME, "", REAL, UUID4, PROVIDER), now);
        present().add("unitnick");
        eq(REAL + "|" + UUID4, AdninFeatures.getBotProfile(NAME), "Verified fresh identity remains immediately available to native consumers");
        eq(1, hints().size(), "A cached success requests only one client presentation hint");
        check(requests().isEmpty(), "Native cached identity read never schedules a network request");
        check(cached(NAME, now + 1).length() > 0, "Owned client presentation can consume that hint without Minecraft");
        hints().clear();
        for (int i = 0; i < 20; i++) eq(REAL + "|" + UUID4, AdninFeatures.getBotProfile(NAME), "Presentation does not consume the cached identity");
        check(hints().isEmpty(), "An announced verified success stops producing hints for this match");
        AdninFeatures.matchStarted();
        eq(REAL + "|" + UUID4, AdninFeatures.getBotProfile(NAME), "Cached identity survives a new match");
        eq(1, hints().size(), "New match requests a new presentation hint without another HTTP request");
        field("urlSnapshot").set(null, OTHER);
        eq("", AdninFeatures.getBotProfile(NAME), "Native lookup cannot expose an identity from a previous URL");
        reset();
        apply(result(NAME, "", REAL, UUID4, PROVIDER), System.currentTimeMillis() - SUCCESS);
        eq("", AdninFeatures.getBotProfile(NAME), "Expired identity is never handed to native consumers");
        check(cache().isEmpty() && profiles().isEmpty(), "Expiring a profile removes its value and compatibility mirror together");
        eq(1, hints().size(), "An expired identity may request one client-thread retry hint");
        check(requests().isEmpty(), "Even an expired native read does not perform or directly queue HTTP");
    }

    private static void admissionAndRetirement() throws Exception {
        reset();
        while (requests().offer(job("Filler"))) { }
        schedule(NAME, NOW);
        check(!requested().containsKey("bot:unitnick"), "A full request queue cannot reserve ten minutes for rejected work");
        requests().clear();
        schedule(NAME, NOW);
        eq(1, requests().size(), "Rejected work can be admitted as soon as capacity exists");
        requests().clear();
        long before = System.currentTimeMillis();
        RETIRE.invoke(null, (Object) job(NAME));
        long after = System.currentTimeMillis();
        verifyRetired(before, after, "Unexpected worker failure");
        reset();
        schedule(NAME, NOW);
        requests().clear();
        while (results().offer(result("Filler", "", "", "", PROVIDER))) { }
        before = System.currentTimeMillis();
        PUBLISH.invoke(null, (Object) job(NAME), (Object) result(NAME, "", REAL, UUID4, PROVIDER));
        after = System.currentTimeMillis();
        eq(128, results().size(), "A full result queue stays bounded when publication fails");
        verifyRetired(before, after, "Result publication failure");
        results().clear();
        reset();
        requested().put("bot:unitnick", 123L);
        String[] oldJob = job(NAME); oldJob[0] = Integer.toString(EPOCH - 1);
        RETIRE.invoke(null, (Object) oldJob);
        RETIRE.invoke(null, (Object) null);
        eq(123L, requested().get("bot:unitnick"), "Retiring stale or absent work cannot mutate current epoch admission");
        PUBLISH.invoke(null, (Object) oldJob, (Object) result(NAME, "", REAL, UUID4, PROVIDER));
        check(results().isEmpty(), "Stale work cannot publish a response into the current queue");
    }

    private static void verifyRetired(long before, long after, String reason) throws Exception {
        Long stamp = requested().get("bot:unitnick");
        check(stamp != null && stamp >= before - SUCCESS + FAILURE && stamp <= after - SUCCESS + FAILURE,
                reason + " receives only a forty-five-second scheduler cooldown");
        check(cache().isEmpty() && profiles().isEmpty(), reason + " cannot invent a successful identity cache entry");
        long exact = stamp + SUCCESS;
        schedule(NAME, exact - 1);
        check(requests().isEmpty(), reason + " remains suppressed immediately before its retry deadline");
        schedule(NAME, exact);
        eq(1, requests().size(), reason + " admits a retry at the precise forty-five-second boundary");
        requests().clear();
    }

    private static void boundedIdentityRecords() throws Exception {
        reset();
        for (int i = 0; i < 700; i++) {
            apply(result("Nick" + i, "", REAL, UUID4, PROVIDER), NOW + i);
            check(cache().size() <= 512 && profiles().size() <= 512 && requested().size() <= 512,
                    "Authoritative entries, native mirrors and admission stamps are bounded during churn");
            check(cache().keySet().equals(profiles().keySet()), "Every successful eviction removes the corresponding mirror entry");
        }
        eq(512, cache().size(), "Identity churn retains at most five hundred twelve completed entries");
        String replaced = cache().keySet().iterator().next();
        apply(result(replaced, "", "", "", PROVIDER), NOW + 1000);
        eq(512, cache().size(), "Replacing a success with an authoritative empty result needs no extra cache slot");
        eq(511, profiles().size(), "An authoritative empty result removes the former successful mirror");
        check(!profiles().containsKey(replaced), "Negative cache entry cannot leave a stale native identity behind");
        for (String key : profiles().keySet())
            eq(profiles().get(key), entry(cache().get(key), "profile"), "Each surviving mirror matches its complete atomic record");
    }

    private static void shutdown() throws Exception {
        requests().offer(job(NAME)); results().offer(result(NAME, "", REAL, UUID4, PROVIDER));
        hints().offer(new String[]{Integer.toString(EPOCH), NAME});
        present().add("unitnick"); names().put("unitnick", NAME);
        check(!cache().isEmpty() && !profiles().isEmpty(), "Unload fixture begins with retained identity content");
        AdninFeatures.shutdown();
        for (String name : new String[]{"botCache", "botProfiles", "candidateTimes", "requested", "announced", "present", "nametagNames", "requests", "results", "nickHints"}) {
            Object value = field(name).get(null);
            check(value instanceof Map ? ((Map<?, ?>) value).isEmpty() : ((Collection<?>) value).isEmpty(), "Shutdown releases " + name);
        }
        eq("", AdninFeatures.getBotProfile(NAME), "Native lookup cannot repopulate identity work after shutdown");
        check(hints().isEmpty(), "Stopped native lookup creates no late hint");
    }

    private static void reset() throws Exception {
        cache().clear(); profiles().clear(); requested().clear(); candidates().clear();
        announced().clear(); present().clear(); names().clear(); requests().clear(); results().clear(); hints().clear();
        field("botGeneration").setInt(null, EPOCH);
        field("urlSnapshot").set(null, PROVIDER);
        AdninGui4.botDenicker = true; AdninGui4.chatOutputDenick = true;
        AdninFeatures.setGameActive(true); AdninFeatures.clearPartyQueue();
    }
    private static void assertNoIdentity(String reason) throws Exception {
        check(cache().isEmpty() && profiles().isEmpty() && requested().isEmpty() && announced().isEmpty(), reason);
    }
    private static void offline() throws Exception {
        check(!field("initialized").getBoolean(null), "Fixture never initializes game feature integration");
        check(field("worker").get(null) == null, "Fixture never starts an HTTP/config worker");
        check(field("settingsPath").get(null) == null, "Fixture never opens a personal settings path");
        check(field("world").get(null) == null, "Fixture never initializes a Minecraft world");
        check(field("apiCompleted").getLong(null) == 0, "Fixture performs no API requests");
    }
    private static String[] result(String name, String error, String real, String uuid, String provider) {
        return new String[]{Integer.toString(EPOCH), "bot", name, error, name, "", real, uuid, "\u0001" + provider};
    }
    private static String[] job(String name) { return new String[]{Integer.toString(EPOCH), "bot", name, name, PROVIDER, ""}; }
    private static void apply(String[] result, long now) throws Exception { APPLY.invoke(null, (Object) result, now, null); }
    private static String cached(String name, long now) throws Exception { return (String) CACHED.invoke(null, name, now); }
    private static void schedule(String name, long now) throws Exception { SCHEDULE.invoke(null, "bot", name, name, PROVIDER, now); }
    private static Object entry(Object entry, String name) throws Exception {
        Field field = entry.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(entry);
    }
    private static Field field(String name) throws Exception {
        Field field = AdninFeatures.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(String name, Class<?>... types) {
        try { Method method = AdninFeatures.class.getDeclaredMethod(name, types); method.setAccessible(true); return method; }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> cache() throws Exception { return (Map<String, Object>) field("botCache").get(null); }
    @SuppressWarnings("unchecked") private static Map<String, String> profiles() throws Exception { return (Map<String, String>) field("botProfiles").get(null); }
    @SuppressWarnings("unchecked") private static Map<String, Long> requested() throws Exception { return (Map<String, Long>) field("requested").get(null); }
    @SuppressWarnings("unchecked") private static Map<String, Long> candidates() throws Exception { return (Map<String, Long>) field("candidateTimes").get(null); }
    @SuppressWarnings("unchecked") private static Map<String, String> names() throws Exception { return (Map<String, String>) field("nametagNames").get(null); }
    @SuppressWarnings("unchecked") private static Set<String> present() throws Exception { return (Set<String>) field("present").get(null); }
    @SuppressWarnings("unchecked") private static Set<String> announced() throws Exception { return (Set<String>) field("announced").get(null); }
    @SuppressWarnings("unchecked") private static BlockingQueue<String[]> requests() throws Exception { return (BlockingQueue<String[]>) field("requests").get(null); }
    @SuppressWarnings("unchecked") private static BlockingQueue<String[]> results() throws Exception { return (BlockingQueue<String[]>) field("results").get(null); }
    @SuppressWarnings("unchecked") private static BlockingQueue<String[]> hints() throws Exception { return (BlockingQueue<String[]>) field("nickHints").get(null); }
    private static void eq(Object expected, Object actual, String reason) { check(expected == null ? actual == null : expected.equals(actual), reason + ": expected " + expected + ", got " + actual); }
    private static void check(boolean condition, String reason) { checks++; if (!condition) throw new AssertionError(reason); }
}
