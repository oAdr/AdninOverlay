import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Long-lived owned fixtures: no game, network, private settings or message delivery. */
public final class AdninResourceLifecycleTest {
    private static int checks;
    private static void check(boolean yes, String reason) {
        checks++; if (!yes) throw new AssertionError(reason);
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); return f;
    }
    public static void main(String[] args) throws Exception {
        cacheChurn(); replayShutdown(); featuresShutdown();
        System.out.println("AdninResourceLifecycleTest: " + checks
            + " checks; 2000 match transitions, bounded complete tag content, in-flight cancellation and daemon retirement; no IO or messages");
    }
    private static void cacheChurn() {
        AdninUrchinCache cache = new AdninUrchinCache(512);
        for (int match = 1; match <= 2000; match++) {
            String name = "Player" + match;
            cache.beginMatch(match, Collections.singletonMap(name, name), match);
            List<String> values = new ArrayList<String>();
            for (int tag = 0; tag < 64; tag++) {
                char[] body = new char[512]; Arrays.fill(body, (char) ('a' + tag % 26));
                values.add(tag + ":" + new String(body));
            }
            check(cache.complete(match, name, values, true, match), "Current match result accepted");
            check(cache.retainedContentBytes() <= AdninUrchinCache.MAX_CONTENT_BYTES,
                "Count-bounded cache is also bounded by content bytes");
            check(cache.visibleTags().get(name.toLowerCase(Locale.ROOT)).equals(values),
                "Budget does not truncate the current player's reasons");
            check(cache.visibleTags().size() == 1, "Previous match display references retired");
        }
        check(cache.size() < 512, "Verbose entries trigger byte budget before count budget");
        cache.clearMatch();
        check(cache.visibleTags().isEmpty() && cache.size() > 0, "Match reset preserves bounded content cache");
        cache.clear();
        check(cache.retainedContentBytes() == 0 && cache.size() == 0, "Key/unload clear releases complete cache");
    }
    private static void replayShutdown() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final AdninReplayProfiles profiles = new AdninReplayProfiles(new AdninReplayProfiles.Resolver() {
            public String resolve(String name) throws java.io.IOException {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Resolver not released"); }
                catch (InterruptedException e) { throw new java.io.IOException("fixture interrupted"); }
                return "12345678-1234-4234-8234-123456789abc";
            }
        }, false);
        profiles.publish(Collections.singletonMap("Alias", "Player")); profiles.lookup("Alias", 1);
        Thread pending = new Thread(new Runnable() {
            public void run() { try { profiles.resolveOne(1); } catch (Throwable t) { error.set(t); } }
        });
        pending.start(); check(entered.await(2, TimeUnit.SECONDS), "Owned resolver entered");
        profiles.shutdown(); release.countDown(); pending.join(2000);
        check(!pending.isAlive() && error.get() == null, "In-flight result may finish safely after shutdown");
        check(profiles.completions() == 0 && profiles.lookup("Alias", 2).isEmpty(),
            "Retired request cannot repopulate cache");
        profiles.publish(Collections.singletonMap("NewAlias", "NewPlayer"));
        check(profiles.accountName("NewAlias").isEmpty() && !profiles.resolveOne(3), "Shutdown cannot restart admission");
        for (String name : new String[]{"values", "expires", "pending", "queue", "roster", "paused", "pausedValues"}) {
            Object value = field(AdninReplayProfiles.class, name).get(profiles);
            check(value instanceof Map ? ((Map<?,?>)value).isEmpty() : ((Collection<?>)value).isEmpty(),
                "Shutdown releases Replay " + name);
        }
        AdninReplayProfiles automatic = new AdninReplayProfiles(new AdninReplayProfiles.Resolver() {
            public String resolve(String name) { return AdninReplayProfiles.NICK; }
        }, true);
        automatic.publish(Collections.singletonMap("Alias", "Player")); automatic.lookup("Alias", 1);
        Thread daemon = (Thread)field(AdninReplayProfiles.class, "worker").get(automatic);
        automatic.shutdown(); daemon.join(2000);
        check(!daemon.isAlive(), "Replay daemon exits from wait/sleep on shutdown");
    }
    @SuppressWarnings("unchecked")
    private static void featuresShutdown() throws Exception {
        Class<?> owner = AdninFeatures.class;
        field(owner, "initialized").setBoolean(null, true);
        field(owner, "generation").setInt(null, 5);
        field(owner, "botGeneration").setInt(null, 7);
        field(owner, "currentMatch").setLong(null, 20);
        BlockingQueue<String[]> requests = (BlockingQueue<String[]>)field(owner, "requests").get(null);
        BlockingQueue<String[]> results = (BlockingQueue<String[]>)field(owner, "results").get(null);
        for (int i = 0; i < 128; i++) {
            requests.add(new String[]{"4", "urchin", "Player", "Player", "test-key", "19"});
            results.add(new String[]{"4", "urchin", "Player", "", "Player", "19", "old-content"});
        }
        Method discard = owner.getDeclaredMethod("discardStaleJobs"); discard.setAccessible(true); discard.invoke(null);
        check(requests.isEmpty() && results.isEmpty(), "World/key/match transition frees all stale queue content");
        requests.add(new String[]{"7", "bot", "Player", "Player", "", ""});
        requests.add(new String[]{"5", "urchin", "Player", "Player", "test-key", "20"});
        discard.invoke(null); check(requests.size() == 2, "Independent current Bot and Urchin work survives");
        requests.clear();
        AdninUrchinCache content=(AdninUrchinCache)field(owner,"urchinCache").get(null);
        content.beginMatch(20,Collections.singletonMap("Player","Player"),1);
        for(int i=0;i<128;i++) results.add(new String[]{"5","urchin","Other","","Other","20"});
        Method publishFull=owner.getDeclaredMethod("publishResult",String[].class,String[].class);
        publishFull.setAccessible(true);
        publishFull.invoke(null,new String[]{"5","urchin","Player","Player","test-key","20"},
            new String[]{"5","urchin","Player","","Player","20","sniper"});
        check(content.beginMatch(21,Collections.singletonMap("Player","Player"),2).size()==1,
            "Full result queue retires reservation instead of permanently blocking that identity");
        Method retire=owner.getDeclaredMethod("retireFailedPublication",String[].class);retire.setAccessible(true);
        retire.invoke(null,(Object)new String[]{"5","urchin","Player","Player","test-key","21"});
        check(content.beginMatch(22,Collections.singletonMap("Player","Player"),3).size()==1,
            "Unexpected worker failure also retires its own identity reservation");
        results.clear();content.clear();
        field(owner, "world").set(null, new Object());
        ((Map<String, String>)field(owner, "tagLabels").get(null)).put("player", "\u00a76CC\u00a7r");
        field(owner, "keySnapshot").set(null, "test-key");
        field(owner, "urlSnapshot").set(null, "fixture-key");
        Properties settings = new Properties(); settings.setProperty("api_urchin", "test-key");
        field(owner, "settingsSnapshot").set(null, settings);
        Thread daemon = new Thread(new AdninFeatures(), "owned-feature-worker");
        field(owner, "worker").set(null, daemon); daemon.start();
        final Method publish = owner.getDeclaredMethod("publishResult", String[].class, String[].class);
        publish.setAccessible(true);
        final AtomicReference<Throwable> publicationError = new AtomicReference<Throwable>();
        Thread publication = new Thread(new Runnable() {
            public void run() {
                try { publish.invoke(null,
                    new String[]{"5", "urchin", "Player", "Player", "test-key", "20"},
                    new String[]{"5", "urchin", "Player", "", "Player", "20", "late-content"}); }
                catch (Throwable failure) { publicationError.set(failure); }
            }
        }, "owned-late-result");
        final AtomicReference<Throwable> preflightError = new AtomicReference<Throwable>();
        Thread preflight = new Thread(new Runnable() {
            public void run() {
                try { AdninFeatures.refreshIgnoredPlayers(null); }
                catch (Throwable failure) { preflightError.set(failure); }
            }
        }, "owned-gray-preflight");
        final AtomicReference<Throwable> guardError = new AtomicReference<Throwable>();
        Thread guards = new Thread(new Runnable() {
            public void run() {
                try {
                    AdninFeatures.shouldIgnorePlayer("Player");
                    AdninFeatures.shouldIgnorePlayerId(null);
                    AdninFeatures.shouldIgnorePlayerEntityId(1);
                } catch (Throwable failure) { guardError.set(failure); }
            }
        }, "owned-gray-guards");
        synchronized (owner) {
            publication.start(); preflight.start(); guards.start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (publication.getState() != Thread.State.BLOCKED && publication.isAlive()
                    && System.nanoTime() < until) Thread.yield();
            check(publication.getState() == Thread.State.BLOCKED,
                "Result publication uses the same barrier as shutdown");
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (preflight.getState() != Thread.State.BLOCKED && preflight.isAlive()
                    && System.nanoTime() < until) Thread.yield();
            check(preflight.getState() == Thread.State.BLOCKED,
                "Gray preflight shares shutdown's barrier before touching mutable display state");
            guards.join(2000);
            check(!guards.isAlive() && guardError.get() == null,
                "Native and Netty gray guards do not acquire the Features lifecycle monitor");
            AdninFeatures.shutdown();
        }
        daemon.join(2000); publication.join(2000); preflight.join(2000);
        check(!preflight.isAlive() && preflightError.get() == null,
            "A delayed gray preflight exits cleanly after shutdown");
        check(!publication.isAlive() && publicationError.get() == null && results.isEmpty(),
            "A late completed result cannot repopulate the retired queue");
        check(!daemon.isAlive(), "API daemon exits without a game tick or network request");
        check(field(owner, "settingsSnapshot").get(null) == null
            && ((AtomicReference<?>)field(owner, "pendingSave").get(null)).get() == null,
            "Shutdown releases saved credential snapshots after the daemon retires");
        check(field(owner,"world").get(null) == null, "Unload releases the last world reference");
        check("".equals(field(owner,"keySnapshot").get(null)) && "".equals(field(owner,"urlSnapshot").get(null)),
            "Unload releases worker configuration snapshots");
        for (String name : new String[]{"requests","results","nickHints","matchRequests","botCache","botProfiles",
                "candidateTimes","requested","tags","tagLabels","announced","present","displayNames","nametagNames","outbox","sent",
                "ignoredScratch","ignoredIdsScratch","ignoredEntitiesScratch","ignoredRosterScratch","outputPlayerIds","outputPlayerActors"}) {
            Object value = field(owner,name).get(null);
            check(value instanceof Map ? ((Map<?,?>)value).isEmpty() : ((Collection<?>)value).isEmpty(),
                "Unload clears " + name);
        }
        AdninGui4.chatOutput = true;
        AdninFeatures.enqueueParty("owned event", 1);
        AdninFeatures.requestSave(); AdninFeatures.ensureInitialized(); AdninFeatures.shutdown();
        check(AdninFeatures.pollPartyCommand(1) == null && field(owner,"worker").get(null) == null,
            "Retired module cannot restart workers or queue messages");
    }
}
