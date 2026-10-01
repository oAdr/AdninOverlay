import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Fake account service only; no network, private settings or game initialization. */
public final class AdninReplayProfilesTest {
    private static int checks, calls;
    private static boolean fail, malformed;
    public static void main(String[] args) throws Exception {
        final String id = new UUID(0x4000L, 0x8000000000000001L).toString();
        AdninReplayProfiles profiles = new AdninReplayProfiles(new AdninReplayProfiles.Resolver() {
            public String resolve(String name) throws IOException {
                calls++;
                if (fail) throw new IOException("owned fixture");
                return malformed ? new UUID(0L, 0L).toString() : id;
            }
        }, false);
        Map<String, String> roster = new LinkedHashMap<String, String>();
        roster.put("ReplayBot", "RecordedName");
        profiles.publish(roster);
        check(profiles.lookup("ReplayBot", 0).isEmpty(), "Unresolved row keeps its placeholder");
        check(calls == 0, "Native/render lookup performs no network on the caller");
        for (int i = 0; i < 50; i++) check(profiles.lookup("REPLAYBOT", 0).isEmpty(), "Pending lookups stay empty");
        check(profiles.requests() == 1, "Repeated render callbacks queue one request");
        check(profiles.resolveOne(0), "Worker consumes eligible identity");
        check(profiles.lookup("replaybot", 599999).equals("recordedname|" + id), "Name mapping supplies verified online UUID");
        check(profiles.lookup("AbsentBot", 10).isEmpty(), "Absent Tab aliases never query");
        check(profiles.requests() == 1 && calls == 1, "Success cache avoids extra requests");
        profiles.publish(Collections.<String, String>emptyMap());
        check(profiles.lookup("ReplayBot", 10).isEmpty(), "Leaving Replay cannot expose a cached prior row");
        profiles.publish(roster);
        check(!profiles.lookup("ReplayBot", 10).isEmpty(), "Same account can reuse content after reentry");
        check(profiles.lookup("ReplayBot", 600000).isEmpty(), "Expired profile refreshes off-thread");
        fail = true; profiles.resolveOne(600000);
        check(profiles.lookup("ReplayBot", 644999).isEmpty() && profiles.requests() == 2,
                "Failure has a bounded retry cooldown");
        profiles.lookup("ReplayBot", 645000); fail = false; malformed = true; profiles.resolveOne(645000);
        check(profiles.lookup("ReplayBot", 645001).isEmpty(), "Synthetic/offline UUID cannot enter native stats");
        check(profiles.failures() == 2 && profiles.completions() == 3, "Only fixed counters record failures");
        for (String rejected : new String[]{null,"","[Viewer]","Suspect","Viewer","Spectator","bad/name","a name","abcdefghijklmnopq"})
            check(!AdninReplayProfiles.validName(rejected), "Non-account replay roles rejected");
        check(AdninReplayProfiles.validName("Unit_Player"), "Ordinary account spelling accepted");
        roster.clear();
        for (int i = 0; i < 400; i++) roster.put("Bot" + i, "Player" + i);
        profiles.publish(roster);
        for (int i = 0; i < 400; i++) profiles.lookup("Bot" + i, 700000);
        check(profiles.requests() <= 259, "Current roster and pending requests are bounded");
        profiles.publish(Collections.<String, String>emptyMap());
        check(!profiles.resolveOne(700000), "Departed unstarted requests are removed");
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final AdninReplayProfiles slow = new AdninReplayProfiles(new AdninReplayProfiles.Resolver() {
            public String resolve(String name) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture timeout");
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                return id;
            }
        }, false);
        Map<String, String> single = Collections.singletonMap("ReplayBot", "RecordedName");
        slow.publish(single); slow.lookup("ReplayBot", 0);
        Thread resolving = new Thread(new Runnable() { public void run() { slow.resolveOne(0); } });
        resolving.start();
        try {
            check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "Owned resolver entered in-flight state");
            slow.publish(Collections.<String, String>emptyMap());
            slow.publish(single); slow.lookup("ReplayBot", 1);
            check(slow.requests() == 1, "Departure/reentry cannot duplicate an in-flight request");
        } finally { release.countDown(); resolving.join(3000); }
        check(!resolving.isAlive() && slow.completions() == 1, "Exactly one in-flight request finishes");
        check(!slow.lookup("ReplayBot", 2).isEmpty() && !slow.resolveOne(2), "Reentry uses completed content without a hidden second job");
        nickCache(id);
        completeRecordedName(id);
        grayPause(id);
        System.out.println("AdninReplayProfilesTest: " + checks + " checks passed; synthetic identities, no network");
    }

    private static void grayPause(final String id) throws Exception {
        final String[] answer={id};
        AdninReplayProfiles profiles=new AdninReplayProfiles(new AdninReplayProfiles.Resolver() {
            public String resolve(String name) {return answer[0];}
        },false);
        Map<String,String> roster=Collections.singletonMap("GrayAlias","GrayPlayer");
        java.util.Set<String> gray=Collections.singleton("grayplayer");
        profiles.publish(roster,gray);
        check("GrayPlayer".equals(profiles.accountName("GrayAlias")),"Gray pause preserves the current exact alias");
        for(int i=0;i<50;i++)check(profiles.lookup("GrayAlias",i).isEmpty(),"Unknown gray player stays unknown without a query");
        check(profiles.requests()==0 && !profiles.resolveOne(0),"Gray first observation schedules no work");
        profiles.publish(roster);profiles.lookup("GrayAlias",0);
        profiles.publish(roster,gray);
        check(!profiles.resolveOne(0),"Turning gray cancels an admitted but unstarted request");
        check(profiles.requests()==1 && profiles.completions()==0,"Cancellation invents neither a profile nor a failure");
        profiles.publish(roster);profiles.lookup("GrayAlias",1);profiles.resolveOne(1);
        String known=profiles.lookup("GrayAlias",2);
        check(known.equals("grayplayer|"+id),"A normal name resolves the ordinary exact account");
        profiles.publish(roster,gray);
        check(known.equals(profiles.lookup("GrayAlias",900000)) && profiles.requests()==2,
            "Gray pause preserves the prior same-name UUID after TTL without a refresh");
        profiles.publish(roster);answer[0]="NICK";profiles.lookup("GrayAlias",900001);profiles.resolveOne(900001);
        profiles.publish(roster,gray);
        check("NICK".equals(profiles.lookup("GrayAlias",1900000)) && profiles.requests()==3,
            "Gray pause preserves an established NICK instead of classifying the same name again");
        profiles.publish(Collections.singletonMap("GrayAlias","OtherPlayer"),Collections.singleton("otherplayer"));
        check(profiles.lookup("GrayAlias",1900001).isEmpty(),"A different recorded account never inherits the old paused identity");
        profiles.publish(roster,gray);check("NICK".equals(profiles.lookup("GrayAlias",1900002)),"Same name restores its bounded cached state");
        profiles.shutdown();check(profiles.lookup("GrayAlias",1900003).isEmpty(),"Shutdown clears paused roster access");

        final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        final AdninReplayProfiles inFlight=new AdninReplayProfiles(new AdninReplayProfiles.Resolver() {
            public String resolve(String name) throws IOException {
                entered.countDown();try {if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("fixture timeout");}
                catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IOException("interrupted");}
                return id;
            }
        },false);
        inFlight.publish(roster);inFlight.lookup("GrayAlias",0);
        Thread worker=new Thread(new Runnable(){public void run(){inFlight.resolveOne(0);}});worker.start();
        try {
            check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS),"Owned request began before the gray transition");
            inFlight.publish(roster,gray);
            for(int i=0;i<50;i++)inFlight.lookup("GrayAlias",i);
            check(inFlight.requests()==1,"Gray callbacks never duplicate an already in-flight request");
        } finally {release.countDown();worker.join(3000);}
        check(!worker.isAlive() && inFlight.completions()==1 && inFlight.lookup("GrayAlias",900000).isEmpty(),
            "Already sent result caches without changing an unknown gray player's prior classification");
        check(!inFlight.resolveOne(900001),"Gray pause creates no follow-up query after an in-flight result");
        inFlight.publish(roster);check(known.equals(inFlight.lookup("GrayAlias",1)),"Restoring normal color can reveal the already completed cache result");
        inFlight.shutdown();
    }

    private static void completeRecordedName(final String id) {
        final java.util.List<String> queried = new java.util.ArrayList<String>();
        AdninReplayProfiles profiles = new AdninReplayProfiles(new AdninReplayProfiles.Resolver() {
            public String resolve(String name) {
                queried.add(name);
                return "xiaoshu_sky202".equals(name) ? "NICK" : id;
            }
        }, false);
        profiles.publish(Collections.singletonMap("XiaoShu_SKY202", "XiaoShu_SKY202"));
        profiles.lookup("XiaoShu_SKY202", 0); profiles.resolveOne(0);
        check("NICK".equals(profiles.lookup("XiaoShu_SKY202", 1)), "Fixture first caches absence for the truncated account");
        profiles.publish(Collections.singletonMap("XiaoShu_SKY202", "XiaoShu_SKY2026"));
        check("XiaoShu_SKY2026".equals(profiles.accountName("XiaoShu_SKY202")), "Current full Tab account supersedes its truncated raw alias");
        check("XiaoShu_SKY2026".equals(profiles.accountName("XIAOSHU_SKY2026")), "The observed complete account is also a current exact alias");
        check(profiles.lookup("XiaoShu_SKY202", 2).isEmpty(), "A truncated account's cached NICK cannot classify the corrected full identity");
        check(profiles.lookup("XiaoShu_SKY2026", 2).isEmpty() && profiles.requests() == 2,
                "Raw and full aliases share one new canonical account request");
        profiles.resolveOne(2);
        check(queried.equals(java.util.Arrays.asList("xiaoshu_sky202", "xiaoshu_sky2026")), "Only exact recorded spellings reach the resolver");
        check(("xiaoshu_sky2026|" + id).equals(profiles.lookup("XiaoShu_SKY202", 3)), "Stats receives the full recorded account and its verified UUID");
        check(profiles.lookup("XiaoShu_SKY202", 3).equals(profiles.lookup("XiaoShu_SKY2026", 3)), "Full and truncated caller aliases read the same successful cache entry");
        check(profiles.accountName("XiaoShu_SKY20").isEmpty() && profiles.lookup("XiaoShu_SKY20", 3).isEmpty(),
                "Unobserved partial prefixes are never invented as aliases");
        check(profiles.requests() == 2, "An unobserved prefix schedules no request");
        profiles.publish(Collections.<String, String>emptyMap());
        check(profiles.accountName("XiaoShu_SKY2026").isEmpty() && profiles.lookup("XiaoShu_SKY2026", 4).isEmpty(),
                "Departure removes both raw and complete current-name eligibility");
        Map<String, String> conflict = new LinkedHashMap<String, String>();
        conflict.put("AliasOne", "FullAccount"); conflict.put("FullAccount", "OtherAccount");
        profiles.publish(conflict);
        check(profiles.accountName("FullAccount").isEmpty() && profiles.lookup("FullAccount", 5).isEmpty(),
                "A canonical-name collision with another raw alias fails closed");
    }

    private static void nickCache(final String id) {
        final String[] answer = {AdninReplayProfiles.NICK};
        final IOException[] failure = {null};
        AdninReplayProfiles profiles = new AdninReplayProfiles(new AdninReplayProfiles.Resolver() {
            public String resolve(String name) throws IOException {
                if (failure[0] != null) throw failure[0];
                return answer[0];
            }
        }, false);
        Map<String, String> roster = Collections.singletonMap("ReplayAlias", "AbsentName");
        profiles.publish(roster);
        check("AbsentName".equals(profiles.accountName("REPLAYALIAS")), "Current alias exposes its recorded account");
        check(profiles.requests() == 0 && profiles.completions() == 0, "Reading accountName never queues work");
        for (String invalid : new String[]{null, "", "bad/name", "AbsentAlias", "ReplayAlias\n"})
            check(profiles.accountName(invalid).isEmpty(), "Invalid or departed alias has no account mapping");
        check(profiles.lookup("ReplayAlias", 0).isEmpty(), "Pending does not imply Nick");
        check(profiles.resolveOne(0), "Explicit absent-account outcome completes");
        check("NICK".equals(profiles.lookup("REPLAYALIAS", 599999)), "Exact Nick marker uses the ten-minute absence cache at its last valid millisecond");
        check(profiles.completions() == 1 && profiles.failures() == 1 && profiles.absences() == 1,
                "Absent account is distinct and never counted as a verified UUID");
        check("player-not-found".equals(profiles.lastError()), "Absent outcome exposes only its fixed category");
        check(profiles.lookup("ReplayAlias", 600000).isEmpty() && profiles.requests() == 2,
                "Nick expires at ten minutes and queues one refresh at the exact boundary");
        answer[0] = id;
        check(profiles.resolveOne(600000), "Expired Nick can recover to a real profile");
        check(("absentname|" + id).equals(profiles.lookup("ReplayAlias", 1199999)),
                "Recovered UUID uses the ten-minute success TTL");
        check(profiles.completions() - profiles.failures() == 1 && profiles.absences() == 1,
                "Existing successful-resolution arithmetic remains correct after Nick recovery");
        check("none".equals(profiles.lastError()), "Success clears a prior fixed absence category");
        check(profiles.lookup("ReplayAlias", 1200000).isEmpty(), "Recovered success expires at its own boundary");
        failure[0] = new IOException("API error rate-limited");
        profiles.resolveOne(1200000);
        check(profiles.lookup("ReplayAlias", 1244999).isEmpty(), "Transient error never restores an old Nick marker before its retry boundary");
        check("rate-limited".equals(profiles.lastError()) && profiles.absences() == 1,
                "Transient failure does not increment authoritative absence");
        check(profiles.lookup("ReplayAlias", 1245000).isEmpty() && profiles.requests() == 4,
                "Transient cache expires after exactly 45 seconds and queues one retry");
        failure[0] = null; answer[0] = "NICK"; profiles.resolveOne(1245000);
        check("NICK".equals(profiles.lookup("ReplayAlias", 1245001)), "A later explicit absence may restore Nick");
        profiles.publish(Collections.<String, String>emptyMap());
        check(profiles.accountName("ReplayAlias").isEmpty(), "Roster departure immediately removes its name mapping");
        check(profiles.lookup("ReplayAlias", 1245002).isEmpty(), "Departed aliases cannot expose cached Nick");
        profiles.publish(roster);
        check("NICK".equals(profiles.lookup("ReplayAlias", 1245003)), "Same current account may reuse unexpired absence across roster reentry");
        profiles.publish(Collections.singletonMap("ReplayAlias", "OtherName"));
        check("OtherName".equals(profiles.accountName("ReplayAlias")), "Alias reassignment exposes only the new current account");
        check(profiles.lookup("ReplayAlias", 1245004).isEmpty(), "Alias reassignment cannot inherit another account's Nick");
        answer[0] = id; profiles.resolveOne(1245004);
        check(("othername|" + id).equals(profiles.lookup("ReplayAlias", 1245005)),
                "Reassigned alias resolves only its currently mapped account");

        for (String malformed : new String[]{"nick", "NICK ", "NICK\n", "NICK|" + id, "", "NICK\0"}) {
            final String invalid = malformed;
            AdninReplayProfiles rejected = new AdninReplayProfiles(new AdninReplayProfiles.Resolver() {
                public String resolve(String name) { return invalid; }
            }, false);
            rejected.publish(roster); rejected.lookup("ReplayAlias", 0); rejected.resolveOne(0);
            check(rejected.lookup("ReplayAlias", 44999).isEmpty() && rejected.absences() == 0,
                    "Only the exact reserved marker is an absent-account result");
            check("response-invalid".equals(rejected.lastError()), "Malformed markers remain fixed parse failures");
            check(rejected.lookup("ReplayAlias", 45000).isEmpty() && rejected.requests() == 2,
                    "Malformed markers expire at the 45-second boundary and permit one retry");
        }
        answer[0] = id;
        profiles.publish(Collections.singletonMap("RealNick", "Nick"));
        profiles.lookup("RealNick", 690006); profiles.resolveOne(690006);
        check(("nick|" + id).equals(profiles.lookup("RealNick", 690007)),
                "A real account named Nick is not the standalone Nick sentinel");
    }

    private static void check(boolean result, String text) {
        checks++; if (!result) throw new AssertionError(text);
    }
}
