import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline match-gated Urchin cache tests. No game classes or HTTP calls. */
public final class AdninUrchinCacheTest {
    private static int checks;
    private static final String UUID_A = "12345678-1234-4234-8234-123456789abc";
    private static final String UUID_B = "22345678-1234-4234-9234-123456789abc";
    private static final String UUID_C = "32345678-1234-4234-a234-123456789abc";

    public static void main(String[] args) {
        successExpiry();
        negativeAndFailureExpiry();
        identitiesAndAliases();
        sameMatchAndStaleCompletion();
        clearPolicies();
        leastRecentlyUsed();
        inFlightAcrossMatches();
        System.out.println("AdninUrchinCacheTest: " + checks + " checks passed; Seraph 10min/45sec policy, stale-readable content, identity/in-flight dedup, cross-world/key reuse and bounded LRU");
    }

    private static void successExpiry() {
        AdninUrchinCache cache = new AdninUrchinCache(4);
        Map<String, String> requests = cache.beginMatch(1, roster("Alice", UUID_A), 1000);
        eq(1, requests.size(), "first match requests missing identity");
        check(cache.complete(1, onlyLookup(requests), tags("sniper: example reason"), true, 1000), "current result accepted");
        eq(tags("sniper: example reason"), cache.visibleTags().get("alice"), "full text retained");
        eq(1, cache.size(), "successful identity cached");
        eq(0, cache.beginMatch(2, roster("ALICE", UUID_A), 601000).size(), "exact 10 minute boundary still fresh");
        eq(tags("sniper: example reason"), cache.visibleTags().get("alice"), "fresh value reused in next match");
        eq(1, cache.beginMatch(3, roster("Alice", UUID_A), 601001).size(), "one millisecond after 10 minutes expires at match start");
        eq(tags("sniper: example reason"), cache.visibleTags().get("alice"), "Stale successful content stays readable during refresh like Seraph");
    }

    private static void negativeAndFailureExpiry() {
        AdninUrchinCache empty = new AdninUrchinCache(4);
        Map<String, String> requests = empty.beginMatch(10, roster("Alice", UUID_A), 1000);
        check(empty.complete(10, onlyLookup(requests), Collections.<String>emptyList(), true, 1000), "empty successful result accepted");
        eq(1, empty.size(), "no-tag success occupies negative cache");
        eq(0, empty.beginMatch(11, roster("Alice", UUID_A), 601000).size(), "no-tag success cached for full success TTL");
        check(!empty.visibleTags().containsKey("alice") || empty.visibleTags().get("alice").isEmpty(), "negative cache never invents tags");
        eq(1, empty.beginMatch(12, roster("Alice", UUID_A), 601001).size(), "negative success expires after 10 minutes");

        AdninUrchinCache failed = new AdninUrchinCache(4);
        requests = failed.beginMatch(20, roster("Alice", UUID_A), 1000);
        check(failed.complete(20, onlyLookup(requests), Collections.<String>emptyList(), false, 1000), "current failed result records cooldown");
        eq(1, failed.size(), "failure cooldown tracked");
        eq(0, failed.beginMatch(21, roster("Alice", UUID_A), 46000).size(), "exact 45 second failure boundary still fresh");
        eq(1, failed.beginMatch(22, roster("Alice", UUID_A), 46001).size(), "failed request retries only at later match after cooldown");
    }

    private static void identitiesAndAliases() {
        AdninUrchinCache cache = new AdninUrchinCache(4);
        Map<String, String> sameIdentity = roster("Alice", UUID_A.toUpperCase(java.util.Locale.ROOT),
                "NickAlias", UUID_A.replace("-", ""));
        Map<String, String> requests = cache.beginMatch(30, sameIdentity, 1000);
        eq(1, requests.size(), "UUID case and hyphens share one request");
        check(cache.complete(30, onlyLookup(requests), tags("blatant_cheater: complete reason"), true, 1000), "aliased identity completes");
        eq(tags("blatant_cheater: complete reason"), cache.visibleTags().get("alice"), "first alias displays shared result");
        eq(tags("blatant_cheater: complete reason"), cache.visibleTags().get("nickalias"), "second alias displays shared result");
        eq(1, cache.size(), "aliases use one cache slot");
        eq(0, cache.beginMatch(31, roster("AnotherAlias", UUID_A), 2000).size(), "new alias reuses UUID cache");
        eq(tags("blatant_cheater: complete reason"), cache.visibleTags().get("anotheralias"), "new match only current aliases shown");
        check(!cache.visibleTags().containsKey("alice"), "previous match view discarded");

        AdninUrchinCache names = new AdninUrchinCache(4);
        requests = names.beginMatch(40, roster("NickOne", "Adnin", "NickTwo", "ADNIN"), 1000);
        eq(1, requests.size(), "lookup names are case insensitive");
        check(names.complete(40, onlyLookup(requests), tags("sniper"), true, 1000), "name result accepted");
        eq(tags("sniper"), names.visibleTags().get("nickone"), "first nickname uses lookup name");
        eq(tags("sniper"), names.visibleTags().get("nicktwo"), "second nickname uses lookup name");
        eq(0, names.beginMatch(41, roster("NickThree", "adnin"), 2000).size(), "lowercase lookup reuses name cache");
    }

    private static void sameMatchAndStaleCompletion() {
        AdninUrchinCache cache = new AdninUrchinCache(4);
        Map<String, String> requests = cache.beginMatch(50, roster("Alice", UUID_A), 1000);
        String firstLookup = onlyLookup(requests);
        eq(0, cache.beginMatch(50, roster("Bob", UUID_B), 2000).size(), "same token does not request newly joined players");
        check(cache.complete(50, firstLookup, tags("closet_cheater: original reason"), true, 2000), "same token leaves pending original roster intact");
        eq(tags("closet_cheater: original reason"), cache.visibleTags().get("alice"), "same token never resets visible roster");
        check(!cache.visibleTags().containsKey("bob"), "new roster ignored within existing match");
        eq(0, cache.beginMatch(50, roster("Alice", UUID_A), 900000).size(), "TTL expiry alone cannot schedule mid-match requests");
        eq(tags("closet_cheater: original reason"), cache.visibleTags().get("alice"), "mid-match display remains available after TTL");

        requests = cache.beginMatch(51, roster("Bob", UUID_B), 900001);
        eq(1, requests.size(), "next match schedules its own new identity");
        check(!cache.complete(50, firstLookup, tags("must not appear"), true, 900002), "prior match completion rejected");
        check(!cache.visibleTags().containsKey("alice"), "stale completion cannot repopulate old roster");
        check(cache.complete(51, onlyLookup(requests), tags("sniper"), true, 900003), "current match still completes normally");
        eq(tags("sniper"), cache.visibleTags().get("bob"), "current result visible");
    }

    private static void clearPolicies() {
        AdninUrchinCache cache = new AdninUrchinCache(4);
        Map<String, String> requests = cache.beginMatch(60, roster("Alice", UUID_A), 1000);
        String lookup = onlyLookup(requests);
        cache.complete(60, lookup, tags("sniper"), true, 1000);
        cache.clearMatch();
        eq(0, cache.visibleTags().size(), "world change clears match view");
        eq(1, cache.size(), "world change preserves cross-match cache");
        check(!cache.complete(60, lookup, tags("late update"), true, 2000), "world change invalidates pending old token");
        eq(0, cache.beginMatch(61, roster("Alias", UUID_A), 3000).size(), "world change reuses fresh content");
        eq(tags("sniper"), cache.visibleTags().get("alias"), "cached content visible under new roster name");
        cache.clear();
        eq(0, cache.size(), "key change or full reset clears content cache");
        eq(0, cache.visibleTags().size(), "key change or full reset clears current view");
        check(!cache.complete(61, lookup, tags("old credential result"), true, 3001), "key reset rejects old result");
        eq(1, cache.beginMatch(62, roster("Alice", UUID_A), 4000).size(), "next match requests anew after key reset");
    }

    private static void leastRecentlyUsed() {
        AdninUrchinCache cache = new AdninUrchinCache(2);
        Map<String, String> requests = cache.beginMatch(70, roster("Alice", UUID_A), 1000);
        cache.complete(70, onlyLookup(requests), tags("tag-a"), true, 1000);
        requests = cache.beginMatch(71, roster("Bob", UUID_B), 2000);
        cache.complete(71, onlyLookup(requests), tags("tag-b"), true, 2000);
        eq(2, cache.size(), "capacity reached");
        eq(0, cache.beginMatch(72, roster("Alice", UUID_A), 3000).size(), "cache hit touches recency");
        requests = cache.beginMatch(73, roster("Carol", UUID_C), 4000);
        cache.complete(73, onlyLookup(requests), tags("tag-c"), true, 4000);
        eq(2, cache.size(), "insertion never exceeds capacity");
        eq(0, cache.beginMatch(74, roster("Alice", UUID_A), 5000).size(), "recently used identity survives eviction");
        eq(1, cache.beginMatch(75, roster("Bob", UUID_B), 6000).size(), "least recently used identity evicted");
    }

    private static void inFlightAcrossMatches() {
        AdninUrchinCache cache = new AdninUrchinCache(4);
        Map<String, String> jobs = cache.beginMatch(80, roster("Alice", UUID_A), 1000);
        eq(1, jobs.size(), "Original match admits identity once");
        cache.clearMatch();
        eq(0, cache.beginMatch(81, roster("NewAlias", UUID_A), 1001).size(),
            "A world transition does not schedule a duplicate while identity is in flight");
        check(cache.complete(80, UUID_A, tags("sniper"), true, 1002),
            "Prior-match in-flight completion populates identity cache");
        eq(tags("sniper"), cache.visibleTags().get("newalias"), "Completion binds only to the current roster alias");
        check(!cache.visibleTags().containsKey("alice"), "Old roster is not resurrected");
        cache.clearPending(); cache.clearMatch();
        eq(0, cache.beginMatch(82, roster("Alice", UUID_A), 1003).size(),
            "Credential changes preserve completed content just like Seraph");
        cache.beginMatch(83, roster("Bob", UUID_B), 2000);
        cache.cancel(82, UUID_B);
        eq(0, cache.beginMatch(84, roster("Bob", UUID_B), 2001).size(), "Wrong-token cancellation cannot retire in-flight owner");
        cache.cancel(83, UUID_B);
        eq(1, cache.beginMatch(85, roster("Bob", UUID_B), 2002).size(), "Retired unstarted jobs may retry at next game start");
        check(!cache.complete(83, UUID_B, tags("stale"), true, 2003), "Cancelled job cannot overwrite replacement");
        cache.clearPending();
        check(!cache.complete(85, UUID_B, tags("old key"), true, 2004), "Changed-key in-flight response is rejected");
    }

    private static Map<String, String> roster(String... values) {
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        for (int index = 0; index < values.length; index += 2) result.put(values[index], values[index + 1]);
        return result;
    }
    private static List<String> tags(String... values) { return Arrays.asList(values); }
    private static String onlyLookup(Map<String, String> requests) {
        if (requests.size() != 1) throw new AssertionError("Expected one request but got " + requests.size());
        return requests.values().iterator().next();
    }
    private static void eq(Object expected, Object actual, String message) { check(expected.equals(actual), message + ": expected " + expected + " actual " + actual); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
