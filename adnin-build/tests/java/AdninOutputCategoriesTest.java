import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/** Actual category callbacks, queues, GUI hitboxes and owned Replay caches; no sends or files. */
public final class AdninOutputCategoriesTest {
    private static final long NOW = 1000000L;
    private static int checks;

    public static void main(String[] args) throws Exception {
        checkOffline();
        boolean[] original = switches();
        boolean oldOverlay = AdninGui4.chatOverlay, oldBot = AdninGui4.botDenicker;
        try {
            check(!AdninFeatures.anyOutputEnabled(), "Every new output category defaults disabled");
            settings();
            gameContext();
            pumpTransitions();
            tagFilters();
            categoryRouting();
            queuePolicy();
            guiHitboxes();
            replayBotGate();
            checkOffline();
            System.out.println("AdninOutputCategoriesTest: " + checks
                    + " checks passed; independent categories, owned queues/GUI/Replay fixtures, no network, settings or sends");
        } finally {
            configure(original[0], original[1], original[2], original[3]);
            AdninFeatures.setGameActive(false);
            AdninGui4.chatOverlay = oldOverlay; AdninGui4.botDenicker = oldBot;
            AdninFeatures.clearPartyQueue(); AdninReplay.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static void pumpTransitions() throws Exception {
        Method prepare = method(AdninFeatures.class, "preparePartyQueue", boolean.class);
        Set<String> announced = (Set<String>) feature("announced").get(null);
        Map<String, Long> candidates = (Map<String, Long>) feature("candidateTimes").get(null);
        Field replay = field(AdninReplay.class, "replay");
        configure(false, false, false, false); replay.setBoolean(null, false);
        AdninFeatures.setGameActive(true); prepare.invoke(null, false);
        configure(true, false, false, false);
        AdninFeatures.enqueueParty(0, "first enabled event", NOW);
        prepare.invoke(null, false);
        eq("/pc first enabled event", AdninFeatures.pollPartyCommand(NOW),
                "The first tick after enabling preserves already admitted output");
        AdninFeatures.enqueueParty(0, "disabled before pump", NOW);
        configure(false, false, false, false); prepare.invoke(null, false);
        configure(true, false, false, false); prepare.invoke(null, false);
        eq(null, AdninFeatures.pollPartyCommand(NOW), "All-off still clears pending events before reenable");
        AdninFeatures.setGameActive(false);
        announced.add("bot:unitnick:realplayer:verified");
        candidates.put("unitnick", NOW);
        replay.setBoolean(null, true); prepare.invoke(null, true);
        check(!announced.contains("bot:unitnick:realplayer:verified") && candidates.isEmpty(),
                "Same-world Replay entry permits cached Bot success to present again");
        announced.add("bot:unitnick:realplayer:verified");
        AdninFeatures.enqueueParty(0, "current replay", NOW);
        prepare.invoke(null, true);
        check(announced.contains("bot:unitnick:realplayer:verified"),
                "Repeated Replay ticks preserve per-Replay announcement dedup");
        eq("/pc current replay", AdninFeatures.pollPartyCommand(NOW), "Stable Replay does not discard queued output");
        AdninFeatures.enqueueParty(0, "previous replay", NOW);
        replay.setBoolean(null, false); prepare.invoke(null, false);
        replay.setBoolean(null, true); prepare.invoke(null, true);
        eq(null, AdninFeatures.pollPartyCommand(NOW), "Replay reentry cannot revive previous Replay output");
        replay.setBoolean(null, false); prepare.invoke(null, false);
        announced.clear(); candidates.clear(); AdninFeatures.setGameActive(true);
    }

    private static void settings() {
        Properties empty = new Properties();
        configure(true, true, true);
        AdninFeatures.loadOutputSettings(empty);
        eq(Arrays.asList(false, false, false, false), boxed(), "Missing settings leave every category off");
        Properties legacy = new Properties(); legacy.setProperty("chat.output", "true");
        AdninFeatures.loadOutputSettings(legacy);
        eq(Arrays.asList(true, true, false, true), boxed(), "Legacy output migrates to Players and Tags, never AC");
        legacy.setProperty("chat.output.players", "false");
        legacy.setProperty("chat.output.anticheat", "true");
        AdninFeatures.loadOutputSettings(legacy);
        eq(Arrays.asList(false, true, true, false), boxed(), "Explicit new keys override only their legacy fallback");
        for (int mask = 0; mask < 16; mask++) {
            configure((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
            List<Boolean> wanted = boxed();
            Properties saved = new Properties();
            AdninFeatures.saveOutputSettings(saved);
            for (String key : new String[]{"chat.output.players", "chat.output.tags", "chat.output.anticheat", "chat.output.denick"})
                check(saved.containsKey(key), "Every independent category is persisted");
            eq(Boolean.toString((mask & 1) != 0), saved.getProperty("chat.output"),
                    "Legacy key retains its Players compatibility value");
            configure((mask & 1) == 0, (mask & 2) == 0, (mask & 4) == 0, (mask & 8) == 0);
            AdninFeatures.loadOutputSettings(saved);
            eq(wanted, boxed(), "All sixteen switch combinations round-trip in memory");
        }
    }

    private static void gameContext() throws Exception {
        configure(true,true,true,true);
        AdninFeatures.setGameActive(false);
        for (int category=0; category<4; category++) AdninFeatures.enqueueParty(category,"lobby event",NOW);
        eq(null,AdninFeatures.pollPartyCommand(NOW),"No output can be admitted from lobby/waiting state");
        AdninFeatures.setGameActive(true);
        AdninFeatures.enqueueParty(0,"live event",NOW);
        eq("/pc live event",AdninFeatures.pollPartyCommand(NOW),"Live game output is admitted");
        AdninFeatures.enqueueParty(0,repeat('x',300),NOW);
        check(AdninFeatures.pollPartyCommand(NOW,100)!=null,"First live fragment available");
        AdninFeatures.setGameActive(false); AdninFeatures.setGameActive(true);
        eq(null,AdninFeatures.pollPartyCommand(NOW),"Leaving discards fragments even on immediate reentry");
        AdninFeatures.enqueueParty(0,"previous game",NOW); AdninFeatures.matchStarted();
        eq(null,AdninFeatures.pollPartyCommand(NOW),"Same-world new game clears queued previous game output");
        AdninFeatures.setGameActive(false);
        Field replay=field(AdninReplay.class,"replay"); replay.setBoolean(null,true);
        AdninFeatures.enqueueParty(0,"replay event",NOW);
        eq("/pc replay event",AdninFeatures.pollPartyCommand(NOW),"Replay allows output despite native lobby state");
        AdninFeatures.enqueueParty(0,"old replay",NOW); replay.setBoolean(null,false);
        eq(null,AdninFeatures.pollPartyCommand(NOW),"Leaving Replay closes delivery gate");
        replay.setBoolean(null,true);
        eq(null,AdninFeatures.pollPartyCommand(NOW),"Old Replay output cannot return after reentry");
        replay.setBoolean(null,false); AdninFeatures.setGameActive(true);
    }

    @SuppressWarnings("unchecked")
    private static void tagFilters() throws Exception {
        Set<String> own=(Set<String>)field(AdninMatchTeams.class,"ownNames").get(null);
        Set<String> team=(Set<String>)field(AdninMatchTeams.class,"teamNames").get(null);
        own.add("selfnick");team.add("teamnick");configure(false,true,false,false);
        for(int mask=0;mask<4;mask++) {
            AdninGui4.chatOutputTagsSelf=(mask&1)!=0;
            AdninGui4.chatOutputTagsTeammates=(mask&2)!=0;
            for(String provider:Arrays.asList("Urchin","Seraph")) {
                AdninFeatures.clearPartyQueue();
                for(String name:Arrays.asList("SelfNick","TeamNick","Enemy"))
                    AdninFeatures.enqueueParty(1,"["+provider+"] "+name+": reason mentions SelfNick",NOW);
                List<String> expected=new ArrayList<String>();
                if((mask&1)!=0)expected.add("/pc ["+provider+"] SelfNick: reason mentions SelfNick");
                if((mask&2)!=0)expected.add("/pc ["+provider+"] TeamNick: reason mentions SelfNick");
                expected.add("/pc ["+provider+"] Enemy: reason mentions SelfNick");
                eq(expected,drain(NOW,256),"Independent self/team tag scopes; reason names never determine subject");
            }
            Properties p=new Properties(); AdninFeatures.saveOutputSettings(p);
            AdninGui4.chatOutputTagsSelf=AdninGui4.chatOutputTagsTeammates=true;
            AdninFeatures.loadOutputSettings(p);
            eq((mask&1)!=0,AdninGui4.chatOutputTagsSelf,"Self scope persists");
            eq((mask&2)!=0,AdninGui4.chatOutputTagsTeammates,"Team scope persists");
        }
        eq("TeamNick",AdninFeatures.tagSubject("[Adnin] [Seraph] [MVP+] TeamNick is blacklisted for CHEATER"),"Ranks and outer branding are not subjects");
        AdninGui4.chatOutputTagsSelf=AdninGui4.chatOutputTagsTeammates=true;
        AdninFeatures.enqueueParty(1,"[Urchin] TeamNick: "+repeat('x',400),NOW);
        check(AdninFeatures.pollPartyCommand(NOW,100)!=null,"Tag can span transport fragments");
        AdninGui4.chatOutputTagsTeammates=false; AdninFeatures.outputSettingsChanged();
        eq(null,AdninFeatures.pollPartyCommand(NOW),"Scope change retires rest of an already split tag");
        AdninGui4.chatOutputTagsSelf=AdninGui4.chatOutputTagsTeammates=true;
        AdninFeatures.clearPartyQueue(); own.clear();team.clear();
        AdninGui4.chatOutputTagsSelf=AdninGui4.chatOutputTagsTeammates=false;
        AdninFeatures.enqueueParty(1,"[Urchin] LaterTeam: reason",NOW);
        AdninFeatures.enqueueParty(1,"[Seraph] LaterSelf: reason",NOW);
        AdninFeatures.enqueueParty(1,"[Urchin] Enemy: reason",NOW);
        team.add("laterteam"); own.add("laterself");
        eq(Arrays.asList("/pc [Urchin] Enemy: reason"), drain(NOW,256),
                "Delayed self-Nick/team resolution is rechecked before tag delivery");
        own.clear();team.clear();AdninGui4.chatOutputTagsSelf=AdninGui4.chatOutputTagsTeammates=true;
    }

    private static void categoryRouting() throws Exception {
        Method urchin = AdninFeatures.class.getDeclaredMethod("generatedLocal", String.class);
        urchin.setAccessible(true);
        String seraph = "{\"text\":\"[Seraph] \",\"extra\":[{\"text\":\"UnitPlayer: CHEATER\","
                + "\"clickEvent\":{\"action\":\"run_command\",\"value\":\"DO_NOT_SEND\"}}]}";
        for (int mask = 0; mask < 16; mask++) {
            configure((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
            AdninFeatures.clearPartyQueue();
            for (int category = 0; category < 4; category++)
                eq((mask & (1 << category)) != 0, AdninFeatures.outputEnabled(category), "Switches remain independent");
            eq(mask != 0, AdninFeatures.anyOutputEnabled(), "All-off state is detected");
            AdninGui4.nativeGeneratedEvent("[Adnin] UnitNick -> RealPlayer", false, AdninFeatures.OUTPUT_DENICK);
            AdninGui4.nativeGeneratedEvent("[Adnin] Player stats", false, AdninFeatures.OUTPUT_PLAYERS);
            AdninGui4.nativeGeneratedEvent(seraph, true, AdninFeatures.OUTPUT_TAGS);
            urchin.invoke(null, "[Urchin] UnitPlayer: Confirmed reason");
            AdninFeatures.anticheatGeneratedEvent("[Adnin] UnitPlayer detected for Scaffold");
            List<String> expected = new ArrayList<String>();
            if ((mask & 8) != 0) expected.add("/pc UnitNick -> RealPlayer");
            if ((mask & 1) != 0) expected.add("/pc Player stats");
            if ((mask & 2) != 0) {
                expected.add("/pc [Seraph] UnitPlayer: CHEATER");
                expected.add("/pc [Urchin] UnitPlayer: Confirmed reason");
            }
            if ((mask & 4) != 0) expected.add("/pc UnitPlayer detected for Scaffold");
            eq(expected, drain(System.currentTimeMillis(), 256), "Actual native, Urchin and AC producers use their categories");
        }
        configure(true, true, true);
        AdninFeatures.clearPartyQueue();
        for (int unknown : new int[]{-1, 4, 42, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            check(!AdninFeatures.outputEnabled(unknown), "Unknown categories fail closed");
            AdninFeatures.enqueueParty(unknown, "unknown category", NOW);
            AdninGui4.nativeGeneratedEvent("unknown native category", false, unknown);
        }
        eq(null, AdninFeatures.pollPartyCommand(NOW), "Unknown producer categories cannot enter the party queue");
    }

    private static void queuePolicy() {
        configure(true, true, true);
        for (int category = 0; category < 4; category++) {
            AdninFeatures.clearPartyQueue();
            AdninFeatures.enqueueParty(category, "\u00a7b[Adnin]\u00a7r Same body", NOW);
            AdninFeatures.enqueueParty(category, "Same   body", NOW + 1);
            eq("/pc Same body", AdninFeatures.pollPartyCommand(NOW + 2), "Same-category normalized duplicates deliver once");
            eq(null, AdninFeatures.pollPartyCommand(NOW + 2), "Canonical category duplicate is suppressed");
            AdninFeatures.enqueueParty(category, "Same body", NOW + 29999);
            eq(null, AdninFeatures.pollPartyCommand(NOW + 29999), "Category dedup holds through 29999 ms");
            AdninFeatures.enqueueParty(category, "Same body", NOW + 30000);
            eq("/pc Same body", AdninFeatures.pollPartyCommand(NOW + 30000), "Category dedup expires at 30000 ms");
            AdninFeatures.clearPartyQueue();
            AdninFeatures.enqueueParty(category, "at expiry", NOW);
            eq("/pc at expiry", AdninFeatures.pollPartyCommand(NOW + 15000), "Each category remains fresh at 15000 ms");
            AdninFeatures.enqueueParty(category, "expired", NOW);
            eq(null, AdninFeatures.pollPartyCommand(NOW + 15001), "Each category expires after 15000 ms");
            for (int limit : new int[]{100, 256}) {
                AdninFeatures.clearPartyQueue();
                String body = repeat('x', limit - 5) + "\ud83d\ude00" + repeat('y', 300);
                AdninFeatures.enqueueParty(category, body, NOW);
                List<String> parts = drain(NOW + 1, limit);
                StringBuilder restored = new StringBuilder();
                for (String part : parts) {
                    check(part.startsWith("/pc ") && part.length() <= limit, "Every category obeys the current transport cap");
                    String text = part.substring(4);
                    check(!Character.isLowSurrogate(text.charAt(0))
                            && !Character.isHighSurrogate(text.charAt(text.length() - 1)), "Fragments never split an emoji pair");
                    restored.append(text);
                }
                eq(body, restored.toString(), "Category splitting preserves every character");
            }
        }
        AdninFeatures.clearPartyQueue();
        for (int category = 0; category < 4; category++) AdninFeatures.enqueueParty(category, "shared body", NOW);
        eq(Arrays.asList("/pc shared body", "/pc shared body", "/pc shared body", "/pc shared body"), drain(NOW + 1, 256),
                "Identical bodies in different categories do not suppress one another");

        for (int disabled = 0; disabled < 4; disabled++) {
            configure(true, true, true); AdninFeatures.clearPartyQueue();
            AdninFeatures.enqueueParty(disabled, repeat('x', 300), NOW);
            int firstOther = (disabled + 1) % 4, secondOther = (disabled + 2) % 4;
            AdninFeatures.enqueueParty(firstOther, "first surviving category", NOW);
            AdninFeatures.enqueueParty(secondOther, "second surviving category", NOW);
            check(AdninFeatures.pollPartyCommand(NOW + 1, 100) != null, "A category may be disabled mid-message");
            setCategory(disabled, false); AdninFeatures.outputSettingsChanged();
            setCategory(disabled, true); AdninFeatures.outputSettingsChanged();
            eq(Arrays.asList("/pc first surviving category", "/pc second surviving category"), drain(NOW + 2, 256),
                    "Disable removes queued remainder permanently without removing other categories");
        }
        configure(true, true, true); AdninFeatures.clearPartyQueue();
        for (int category = 0; category < 4; category++) AdninFeatures.enqueueParty(category, "queued " + category, NOW);
        configure(false, false, false); AdninFeatures.outputSettingsChanged();
        configure(true, true, true); AdninFeatures.outputSettingsChanged();
        eq(null, AdninFeatures.pollPartyCommand(NOW + 1), "All-off drops unsent output even if switches are immediately reenabled");
        AdninFeatures.clearPartyQueue();
    }

    private static void guiHitboxes() throws Exception {
        AdninGui4 screen = new AdninGui4();
        Method clicks = method(AdninGui4.class, "handleChatOverlayPanelClicks",
                int.class, int.class, int.class, int.class, int.class, int.class, int.class);
        Method height = method(AdninGui4.class, "getPanelContentHeight", int.class);
        int x = 100, base = 50, width = 300;
        int mainY = field(AdninGui4.class, "CHAT_MAIN_Y").getInt(null);
        eq(180, field(AdninGui4.class, "CHAT_MAIN_H").getInt(null), "Chat main card accommodates all four category rows and tag scope");
        eq(204, field(AdninGui4.class, "CHAT_THRESH_Y").getInt(null), "Thresholds begin below the enlarged output card");
        eq(356, height.invoke(screen, 4), "Chat scroll height includes relocated thresholds");
        for (int category = 0; category < 4; category++) {
            configure(false, false, false); AdninGui4.chatOverlay = false;
            int y = base + mainY + new int[]{36,92,148,64}[category];
            check((Boolean) clicks.invoke(screen, x, base, width, 0, 1000, x + 15, y + 11), "Category row consumes its own click");
            for (int other = 0; other < 4; other++)
                eq(category == other, AdninFeatures.outputEnabled(other), "GUI changes exactly one output switch");
            eq(category != 2, AdninGui4.chatOverlay, "Players/Tags request local generation while AC remains independent");
            check(!(Boolean) clicks.invoke(screen, x, base, width, y + 12, y + 22, x + 15, y + 11),
                    "Clipped-out category row pixels cannot receive a click");
            check((Boolean) clicks.invoke(screen, x, base, width, 0, 1000, x + 15, y + 11), "Second click disables the same category");
            check(!AdninFeatures.anyOutputEnabled(), "Second category click leaves every switch off");
        }
        Method clamp = method(AdninGui4.class, "clampScroll", int.class, int.class, int.class);
        int viewport = 228, scroll = (Integer) clamp.invoke(screen, -1000000, 356, viewport);
        eq(-128, scroll, "Chat panel's full content remains reachable in a compact viewport");
        int top = 35, bottom = top + viewport, shiftedBase = top + 5 + scroll;
        configure(false, false, false);
        int acY = shiftedBase + mainY + 148;
        check(acY >= top && acY + 22 < bottom, "Bottom-scrolled AC output row fits inside the viewport");
        check((Boolean) clicks.invoke(screen, x, shiftedBase, width, top, bottom, x + 15, acY + 11),
                "Scrolled category hitbox uses the same translated row");
        check(AdninGui4.chatOutputAnticheat && !AdninGui4.chatOutput && !AdninGui4.chatOutputTags,
                "Scrolled click still affects only AC output");
    }

    @SuppressWarnings("unchecked")
    private static void replayBotGate() throws Exception {
        AdninReplayProfiles profiles = (AdninReplayProfiles) field(AdninReplay.class, "profiles").get(null);
        Field started = field(AdninReplayProfiles.class, "started"), replay = field(AdninReplay.class, "replay");
        Map<String, String> values = (Map<String, String>) field(AdninReplayProfiles.class, "values").get(profiles);
        Map<String, Long> expires = (Map<String, Long>) field(AdninReplayProfiles.class, "expires").get(profiles);
        Map<String, String> botProfiles = (Map<String, String>) feature("botProfiles").get(null);
        Map<String, Object> botCache = (Map<String, Object>) feature("botCache").get(null);
        Constructor<?> botEntry = Class.forName("AdninFeatures$BotCacheEntry")
                .getDeclaredConstructor(String.class, String.class, String.class, long.class);
        botEntry.setAccessible(true);
        Map<String, Long> candidates = (Map<String, Long>) feature("candidateTimes").get(null);
        Set<String> present = (Set<String>) feature("present").get(null);
        BlockingQueue<String[]> hints = (BlockingQueue<String[]>) feature("nickHints").get(null);
        BlockingQueue<String[]> requests = (BlockingQueue<String[]>) feature("requests").get(null);
        Map<String, Long> requested = (Map<String, Long>) feature("requested").get(null);
        Field url = feature("urlSnapshot");
        boolean oldStarted = started.getBoolean(profiles), oldReplay = replay.getBoolean(null);
        String oldUrl = (String) url.get(null);
        check(!oldStarted && requests.isEmpty() && hints.isEmpty(), "Replay fixture begins without API workers or jobs");
        String id = new UUID(0x4000L, 0x8000000000000001L).toString();
        String resolved = "VerifiedName|" + id;
        try {
            // Suppress lazy daemon creation before any pending lookup. No resolveOne
            // or Features worker runs; queued owned jobs are discarded in finally.
            started.setBoolean(profiles, true); replay.setBoolean(null, true);
            AdninGui4.botDenicker = true;
            Map<String, String> roster = new LinkedHashMap<String, String>();
            roster.put("NickAlias", "RecordedNick"); roster.put("FoundAlias", "FoundName");
            roster.put("FailedAlias", "FailedName"); roster.put("PendingAlias", "PendingName");
            profiles.publish(roster);
            cache(values, expires, "RecordedNick", "NICK");
            cache(values, expires, "FoundName", "foundname|" + id);
            cache(values, expires, "FailedName", "");
            String cacheProvider = (String) url.get(null);
            long cacheExpiry = System.currentTimeMillis() + 600000L;
            for (String name : new String[]{"recordednick", "foundname", "failedname", "pendingname", "departed"}) {
                botProfiles.put(name, resolved);
                botCache.put(name, botEntry.newInstance(resolved, cacheProvider, "verified", cacheExpiry));
            }
            eq(resolved, AdninFeatures.getBotProfile("NickAlias"), "Only confirmed Nick can expose its verified Bot identity");
            eq("NICK", AdninReplay.profile("NickAlias"), "Bot resolution never replaces original Nick classification");
            eq(1, hints.size(), "Verified cached Nick queues only a client-thread presentation hint");
            eq("NickAlias", hints.peek()[1], "Cached presentation revalidates the current native alias");
            check(requests.isEmpty(), "Cached identity display starts no HTTP work");
            for (String rejected : new String[]{"FoundAlias", "FailedAlias", "PendingAlias", "Departed", "bad/name", null})
                eq("", AdninFeatures.getBotProfile(rejected), "Cached Bot data cannot bypass current-Tab confirmed-Nick gate");
            eq(1, hints.size(), "Non-Nick candidates never add Bot hints");
            hints.clear(); candidates.clear();
            botProfiles.remove("recordednick");
            botCache.remove("recordednick");
            eq("", AdninFeatures.getBotProfile("NickAlias"), "Unresolved confirmed Nick retains its placeholder");
            eq(1, hints.size(), "Only confirmed Nick creates an asynchronous Bot candidate");
            eq("NickAlias", hints.peek()[1], "Candidate retains the native alias for current-roster revalidation");
            AdninFeatures.getBotProfile("NickAlias");
            eq(1, hints.size(), "Rapid duplicate native callbacks do not duplicate a Bot candidate");
            present.add("recordednick"); url.set(null, "https://fixture.invalid/?q=<>");
            AdninFeatures.lookupNick("NickAlias", NOW);
            eq(1, requests.size(), "Confirmed current Nick can queue one owned Bot request");
            String[] job = requests.peek();
            eq("RecordedNick", job[2], "Bot query uses the current recorded nickname, not a synthetic alias");
            AdninFeatures.lookupNick("FoundAlias", NOW);
            AdninFeatures.lookupNick("FailedAlias", NOW);
            eq(1, requests.size(), "Found and failed identities cannot enter Bot scheduling");
            botProfiles.put("recordednick", resolved);
            botCache.put("recordednick", botEntry.newInstance(resolved, (String) url.get(null), "verified", cacheExpiry));
            profiles.publish(Collections.singletonMap("NickAlias", "FoundName"));
            eq("", AdninFeatures.getBotProfile("NickAlias"), "Alias reassignment cannot reuse a former Nick's resolved identity");
            profiles.publish(Collections.<String, String>emptyMap());
            eq("", AdninFeatures.getBotProfile("NickAlias"), "Departure suppresses both cached Nick and Bot identity");
            check(!AdninFeatures.isRealTabProfile("UnitPlayer", new UUID(0x2000L, 1)),
                    "Replay support does not broaden ordinary UUID-v2 eligibility");
            check(AdninFeatures.isRealTabProfile("UnitPlayer", new UUID(0x1000L, 1))
                    && AdninFeatures.isRealTabProfile("UnitPlayer", new UUID(0x4000L, 1)),
                    "Ordinary v1/v4 policy remains unchanged");
            eq(0L, profiles.completions(), "Owned Replay fixture never resolves a real API request");
        } finally {
            profiles.publish(Collections.<String, String>emptyMap());
            ((Deque<String>) field(AdninReplayProfiles.class, "queue").get(profiles)).clear();
            ((Set<String>) field(AdninReplayProfiles.class, "pending").get(profiles)).clear();
            values.clear(); expires.clear();
            started.setBoolean(profiles, oldStarted); replay.setBoolean(null, oldReplay);
            botProfiles.clear(); botCache.clear(); candidates.clear(); hints.clear(); requests.clear(); present.clear(); requested.clear();
            url.set(null, oldUrl);
        }
    }

    private static void cache(Map<String, String> values, Map<String, Long> expires, String name, String value) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        values.put(key, value); expires.put(key, Long.MAX_VALUE);
    }
    private static void checkOffline() throws Exception {
        check(!feature("initialized").getBoolean(null), "Fixture never initializes game integration");
        check(feature("settingsPath").get(null) == null && feature("world").get(null) == null,
                "Fixture never acquires personal settings or a game world");
        check(((AtomicReference<?>) feature("pendingSave").get(null)).get() == null,
                "In-memory migration and GUI checks never request a settings write");
        eq(0L, feature("apiCompleted").getLong(null), "No Features API worker executes");
    }
    private static Field feature(String name) throws Exception { return field(AdninFeatures.class, name); }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(Class<?> owner, String name, Class<?>... args) throws Exception {
        Method method = owner.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
    private static void configure(boolean players, boolean tags, boolean anticheat) {
        configure(players, tags, anticheat, players);
    }
    private static void configure(boolean players, boolean tags, boolean anticheat, boolean denick) {
        AdninGui4.chatOutput = players; AdninGui4.chatOutputTags = tags; AdninGui4.chatOutputAnticheat = anticheat; AdninGui4.chatOutputDenick = denick;
    }
    private static void setCategory(int category, boolean value) {
        if (category == 0) AdninGui4.chatOutput = value;
        else if (category == 1) AdninGui4.chatOutputTags = value;
        else if (category == 2) AdninGui4.chatOutputAnticheat = value;
        else AdninGui4.chatOutputDenick = value;
    }
    private static boolean[] switches() {
        return new boolean[]{AdninGui4.chatOutput, AdninGui4.chatOutputTags, AdninGui4.chatOutputAnticheat, AdninGui4.chatOutputDenick};
    }
    private static List<Boolean> boxed() {
        return Arrays.asList(AdninGui4.chatOutput, AdninGui4.chatOutputTags, AdninGui4.chatOutputAnticheat, AdninGui4.chatOutputDenick);
    }
    private static List<String> drain(long now, int limit) {
        List<String> result = new ArrayList<String>();
        for (int i = 0; i < 128; i++) {
            String command = AdninFeatures.pollPartyCommand(now, limit);
            if (command == null) return result;
            result.add(command);
        }
        throw new AssertionError("Owned party queue exceeded its finite fixture bound");
    }
    private static String repeat(char value, int count) {
        char[] result = new char[count]; Arrays.fill(result, value); return new String(result);
    }
    private static void check(boolean value, String reason) {
        checks++; if (!value) throw new AssertionError(reason);
    }
    private static void eq(Object expected, Object actual, String reason) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(reason + ": expected " + expected + ", got " + actual);
    }
}
