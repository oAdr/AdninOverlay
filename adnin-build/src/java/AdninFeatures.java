import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.network.play.client.C01PacketChatMessage;

/** Client-thread integration. The daemon performs HTTP and config writes only. */
public final class AdninFeatures implements Runnable {
    public static final int OUTPUT_PLAYERS = 0, OUTPUT_TAGS = 1, OUTPUT_ANTICHEAT = 2, OUTPUT_DENICK = 3;
    private static final int MAX_CACHE = 512, MAX_PENDING = 128;
    // Identity results follow the same bounded policy as Seraph/Replay
    // profiles: verified and explicit no-result values remain readable for
    // ten minutes, while transport/verification failures retry sooner.
    private static final long SUCCESS_MS = 600000L, FAILURE_MS = 45000L;
    private static final long CACHE_MS = SUCCESS_MS, SEND_MS = 1500L;
    private static final int MAX_PARTY_COMMAND_UNITS = 256;
    private static final String PARTY_LIMIT_PROBE = partyLimitProbe();
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern TAG_SUBJECT = java.util.regex.Pattern.compile(
        "^\\[(?:Urchin|Seraph)\\]\\s+(?:\\[[^\\]\\r\\n]{1,24}\\]\\s+)*([A-Za-z0-9_]{1,16})(?=\\s|:|$)");
    private static final java.util.regex.Pattern OUTPUT_SUBJECT = java.util.regex.Pattern.compile(
        "^(?:\\[[^\\]\\r\\n]{1,24}\\]\\s+)*([A-Za-z0-9_]{1,16})(?=\\s|:|$)");
    private static final BlockingQueue<String[]> requests = new ArrayBlockingQueue<String[]>(MAX_PENDING);
    private static final BlockingQueue<String[]> results = new ArrayBlockingQueue<String[]>(MAX_PENDING);
    private static final BlockingQueue<String[]> nickHints = new ArrayBlockingQueue<String[]>(MAX_PENDING);
    // The sole worker may hold one dequeued Urchin job while rate pacing.
    // Its reference is guarded by this class monitor; it holds strings only.
    private static String[] waitingUrchin;
    /** One bounded identity record keeps value, provider, status and expiry together. */
    private static final class BotCacheEntry {
        final String profile, provider, status;
        final long expires;
        BotCacheEntry(String profile, String provider, String status, long expires) {
            this.profile = profile; this.provider = provider;
            this.status = status; this.expires = expires;
        }
    }
    private static final ConcurrentMap<String, BotCacheEntry> botCache = new ConcurrentHashMap<String, BotCacheEntry>();
    // Read-only compatibility mirror for diagnostics/tests; validity always
    // comes from botCache, so an expiry can never disappear independently.
    private static final ConcurrentMap<String, String> botProfiles = new ConcurrentHashMap<String, String>();
    private static final ConcurrentMap<String, Long> candidateTimes = new ConcurrentHashMap<String, Long>();
    private static final Map<String, Long> requested = new LinkedHashMap<String, Long>();
    private static final Map<String, List<String>> tags = new LinkedHashMap<String, List<String>>();
    private static final Map<String, String> tagLabels = new LinkedHashMap<String, String>();
    private static final AdninUrchinCache urchinCache = new AdninUrchinCache(MAX_CACHE);
    private static final AtomicLong matchStarts = new AtomicLong();
    private static final Deque<String[]> matchRequests = new ArrayDeque<String[]>();
    private static volatile long currentMatch;
    private static final Set<String> announced = new HashSet<String>();
    private static final Set<String> present = new HashSet<String>();
    // Published only by the client thread. Native readers never touch a world,
    // roster, Skin/Features monitor or mutable collection through this API.
    private static final class IgnoredPlayers {
        final Set<String> aliases;
        final Set<UUID> ids;
        final Set<Integer> entities;
        final Map<String, String> botResults;
        final Map<String, List<String>> tagResults;
        IgnoredPlayers(Set<String> names, Set<UUID> uuids, Set<Integer> entityIds) {
            this(names,uuids,entityIds,Collections.<String,String>emptyMap(),Collections.<String,List<String>>emptyMap());
        }
        IgnoredPlayers(Set<String> names, Set<UUID> uuids, Set<Integer> entityIds,
                Map<String,String> bots, Map<String,List<String>> oldTags) {
            aliases = Collections.unmodifiableSet(new HashSet<String>(names));
            ids = Collections.unmodifiableSet(new HashSet<UUID>(uuids));
            entities = Collections.unmodifiableSet(new HashSet<Integer>(entityIds));
            botResults = Collections.unmodifiableMap(new HashMap<String,String>(bots));
            tagResults = Collections.unmodifiableMap(new HashMap<String,List<String>>(oldTags));
        }
    }
    private static final IgnoredPlayers NO_IGNORED = new IgnoredPlayers(Collections.<String>emptySet(),
        Collections.<UUID>emptySet(), Collections.<Integer>emptySet());
    private static volatile IgnoredPlayers ignoredPlayers = NO_IGNORED;
    private static final Set<String> ignoredScratch = new HashSet<String>();
    private static final Set<UUID> ignoredIdsScratch = new HashSet<UUID>();
    private static final Set<Integer> ignoredEntitiesScratch = new HashSet<Integer>();
    private static final Map<UUID, NetworkPlayerInfo> ignoredRosterScratch = new HashMap<UUID, NetworkPlayerInfo>();
    // Client-thread lookup only. The existing 250 ms pass supplies identities;
    // actual output reads that player's current team without rescanning everyone.
    private static final Map<String, UUID> outputPlayerIds = new HashMap<String, UUID>();
    private static final Map<UUID, EntityPlayer> outputPlayerActors = new HashMap<UUID, EntityPlayer>();
    private static Object ignoredWorld, ignoredConnection;
    private static long ignoredAt = Long.MIN_VALUE;
    private static final Map<String, String> displayNames = new LinkedHashMap<String, String>();
    private static final Map<String, String> nametagNames = new LinkedHashMap<String, String>();
    private static final Deque<String[]> outbox = new ArrayDeque<String[]>();
    private static final Map<String, Long> sent = new LinkedHashMap<String, Long>();
    private static boolean initialized, warnedUrl;
    private static volatile boolean stopped;
    private static volatile Thread worker;
    private static volatile boolean nativeGameActive;
    private static boolean outputReplayContext;
    private static volatile int generation;
    private static volatile int botGeneration;
    private static final AtomicReference<Properties> pendingSave = new AtomicReference<Properties>();
    private static volatile Path settingsPath;
    private static Properties settingsSnapshot;
    private static volatile long saveAt;
    private static Object world;
    private static long nextScan, nextSend, nextError;
    private static volatile long ticks, apiCompleted, nativeEvents;
    private static volatile long urchinSucceeded, urchinFailed;
    private static volatile String urchinLastError = "none";
    private static long diagnosticAt;
    private static String keySnapshot = "", urlSnapshot = "";
    private static boolean botSnapshot;
    private static final String PREFIX = "\u00a7b[Adnin]\u00a7r ";
    private static int rowCount;

    /** Configured provider only: no request, key validation, cache claim, or saved copy. */
    public static String statsProviderStatus() {
        if (AdninGui4.vegaProxy) return "vega-proxy";
        String key = AdninGui4.api_hypixel;
        return key != null && key.trim().length() > 0 ? "hypixel-direct" : "missing-hypixel-key";
    }

    /** Current light-gray nametag exclusion; bounded, immutable, lock-free read. */
    public static boolean shouldIgnorePlayer(String name) {
        Set<String> ignored = ignoredPlayers.aliases;
        return !ignored.isEmpty() && isPlayerName(name) && ignored.contains(lower(name));
    }

    /** Netty/native observation guards: unknown identities continue normally. */
    public static boolean shouldIgnorePlayerId(UUID id) { return id != null && ignoredPlayers.ids.contains(id); }
    public static boolean shouldIgnorePlayerEntityId(int id) { return id > 0 && ignoredPlayers.entities.contains(id); }

    /** Client pump preflight. Share the lifecycle barrier before reading display maps. */
    public static synchronized void refreshIgnoredPlayers(Minecraft mc) {
        if (stopped || mc == null || !mc.isCallingFromMinecraftThread()) return;
        Object nextWorld = mc.theWorld, nextConnection = mc.getNetHandler();
        if (nextWorld == null || nextConnection == null || mc.thePlayer == null) {
            ignoredPlayers = NO_IGNORED; ignoredScratch.clear(); ignoredIdsScratch.clear();
            ignoredEntitiesScratch.clear(); ignoredRosterScratch.clear();
            outputPlayerIds.clear(); outputPlayerActors.clear();
            ignoredWorld = ignoredConnection = null; ignoredAt = Long.MIN_VALUE;
            return;
        }
        long now = System.nanoTime() / 1000000L;
        if (ignoredWorld == nextWorld && ignoredConnection == nextConnection
                && ignoredAt != Long.MIN_VALUE && now >= ignoredAt && now - ignoredAt < 250L) return;
        ignoredWorld = nextWorld; ignoredConnection = nextConnection; ignoredAt = now;
        ignoredScratch.clear(); ignoredIdsScratch.clear(); ignoredEntitiesScratch.clear(); ignoredRosterScratch.clear();
        outputPlayerIds.clear(); outputPlayerActors.clear();
        boolean replay = AdninReplay.isReplay();
        int count = 0;
        try {
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (++count > 256) break;
            if (info == null || info.getGameProfile() == null) continue;
            UUID profileId = info.getGameProfile().getId();
            if (profileId != null) ignoredRosterScratch.put(profileId, info);
            String raw = info.getGameProfile().getName();
            String admitted = replay ? AdninReplay.recordedName(raw) : raw;
            rememberOutputPlayer(raw, profileId); rememberOutputPlayer(admitted, profileId);
            if (!AdninMatchTeams.isLightGray(info)) continue;
            if (profileId != null) ignoredIdsScratch.add(profileId);
            addIgnoredAlias(raw);
            addIgnoredAlias(admitted);
            if (isPlayerName(admitted)) {
                BotCacheEntry bot = botCache.get(lower(admitted));
                if (bot != null && "verified".equals(bot.status) && bot.provider.equals(urlSnapshot)
                        && System.currentTimeMillis() < bot.expires) addIgnoredProfileAlias(bot.profile);
            }
        }
        count = 0;
        for (EntityPlayer actor : mc.theWorld.playerEntities) {
            if (++count > 256) break;
            if (actor == null) continue;
            UUID id = actor.getUniqueID();
            NetworkPlayerInfo info = id == null ? null : ignoredRosterScratch.get(id);
            String admitted = replay ? AdninReplay.actorName(actor) : actor.getName();
            if (replay && isPlayerName(admitted)) {
                NetworkPlayerInfo replayInfo = AdninReplay.playerInfo(admitted);
                if (replayInfo != null) info = replayInfo;
            }
            if (id != null) outputPlayerActors.put(id, actor);
            rememberOutputPlayer(actor.getName(), id); rememberOutputPlayer(admitted, id);
            if (actor.getGameProfile() != null) rememberOutputPlayer(actor.getGameProfile().getName(), id);
            if (info != null && info.getGameProfile() != null && info.getGameProfile().getId() != null) {
                UUID tabId = info.getGameProfile().getId();
                outputPlayerActors.put(tabId, actor);
                rememberOutputPlayer(info.getGameProfile().getName(), tabId);
                rememberOutputPlayer(admitted, tabId);
            }
            if (!AdninMatchTeams.isLightGray(actor, info, admitted)) continue;
            if (id != null) ignoredIdsScratch.add(id);
            int entityId = actor.getEntityId();
            if (entityId > 0) ignoredEntitiesScratch.add(entityId);
            addIgnoredAlias(actor.getName()); addIgnoredAlias(admitted);
            if (actor.getGameProfile() != null) addIgnoredAlias(actor.getGameProfile().getName());
            if (info != null && info.getGameProfile() != null) {
                addIgnoredAlias(info.getGameProfile().getName());
                if (info.getGameProfile().getId() != null) ignoredIdsScratch.add(info.getGameProfile().getId());
            }
        }
        } finally { ignoredRosterScratch.clear(); }
        AdninSkinDenicker.appendIgnoredAliases(ignoredScratch, 1024);
        if (stopped) return;
        IgnoredPlayers previous = ignoredPlayers;
        if (!previous.aliases.equals(ignoredScratch) || !previous.ids.equals(ignoredIdsScratch)
                || !previous.entities.equals(ignoredEntitiesScratch)) {
            Map<String,String> frozenBots = new HashMap<String,String>();
            Map<String,List<String>> frozenTags = new HashMap<String,List<String>>();
            for (String name : ignoredScratch) {
                if (previous.aliases.contains(name)) {
                    frozenBots.put(name, previous.botResults.get(name));
                    frozenTags.put(name, previous.tagResults.get(name));
                } else {
                    BotCacheEntry cached = botCache.get(name);
                    frozenBots.put(name, cached != null && cached.provider.equals(urlSnapshot) ? cached.profile : "");
                    frozenTags.put(name, tags.get(name));
                }
            }
            ignoredPlayers = ignoredScratch.isEmpty() && ignoredIdsScratch.isEmpty() && ignoredEntitiesScratch.isEmpty()
                ? NO_IGNORED : new IgnoredPlayers(ignoredScratch, ignoredIdsScratch, ignoredEntitiesScratch, frozenBots, frozenTags);
            nextScan = 0;
        }
    }

    private static void addIgnoredAlias(String name) {
        if (isPlayerName(name) && ignoredScratch.size() < 1024) ignoredScratch.add(lower(name));
    }

    private static void rememberOutputPlayer(String name, UUID id) {
        if (id != null && isPlayerName(name) && outputPlayerIds.size() < 1024)
            outputPlayerIds.put(lower(name), id);
    }

    /** Actual delivery boundary only. Netty and render/cache readers keep the
     * immutable ignoredPlayers snapshot and never enter Minecraft here. */
    private static synchronized boolean ignoredAtAction(String name) {
        if (!isPlayerName(name)) return false;
        if (shouldIgnorePlayer(name)) return true;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread() || mc.theWorld == null || mc.getNetHandler() == null)
            return false;
        if (ignoredWorld != mc.theWorld || ignoredConnection != mc.getNetHandler()) return false;
        UUID id = outputPlayerIds.get(lower(name));
        NetworkPlayerInfo info = id == null ? null : mc.getNetHandler().getPlayerInfo(id);
        // A new roster entry can precede the next 250 ms identity snapshot.
        // Resolve it only for an actual message, never per row/frame or packet.
        if (info == null) {
            int count = 0;
            for (NetworkPlayerInfo candidate : mc.getNetHandler().getPlayerInfoMap()) {
                if (++count > 256) break;
                if (candidate == null || candidate.getGameProfile() == null) continue;
                String raw = candidate.getGameProfile().getName();
                if (!name.equalsIgnoreCase(raw) && !(AdninReplay.isReplay()
                        && name.equalsIgnoreCase(AdninReplay.recordedName(raw)))) continue;
                info = candidate; id = candidate.getGameProfile().getId();
                rememberOutputPlayer(name, id); break;
            }
        }
        EntityPlayer actor = id == null ? null : outputPlayerActors.get(id);
        if (!AdninMatchTeams.isLightGray(actor, info, name)) return false;
        pauseObservedPlayer(name, id, actor, info);
        return true;
    }

    /** A proven gray action closes the small interval before the periodic
     * pass. Freeze existing values before accepting an in-flight completion;
     * the regular 250 ms pass still owns color recovery and lifecycle expiry. */
    private static synchronized void pauseObservedPlayer(String name, UUID id, EntityPlayer actor, NetworkPlayerInfo info) {
        if (!isPlayerName(name) || shouldIgnorePlayer(name)) return;
        IgnoredPlayers before = ignoredPlayers;
        Set<String> aliases = new HashSet<String>(before.aliases);
        Set<UUID> ids = new HashSet<UUID>(before.ids);
        Set<Integer> entities = new HashSet<Integer>(before.entities);
        addPausedAlias(aliases, name);
        if (id != null) ids.add(id);
        if (info != null && info.getGameProfile() != null) {
            addPausedAlias(aliases, info.getGameProfile().getName());
            if (info.getGameProfile().getId() != null) ids.add(info.getGameProfile().getId());
        }
        if (actor != null) {
            addPausedAlias(aliases, actor.getName());
            if (actor.getGameProfile() != null) addPausedAlias(aliases, actor.getGameProfile().getName());
            if (actor.getUniqueID() != null) ids.add(actor.getUniqueID());
            if (actor.getEntityId() > 0) entities.add(actor.getEntityId());
        }
        BotCacheEntry bot = botCache.get(lower(name));
        if (bot != null && bot.provider.equals(urlSnapshot) && "verified".equals(bot.status)) {
            int separator = bot.profile.indexOf('|');
            if (separator > 0) addPausedAlias(aliases, bot.profile.substring(0, separator));
        }
        Map<String,String> bots = new HashMap<String,String>(before.botResults);
        Map<String,List<String>> oldTags = new HashMap<String,List<String>>(before.tagResults);
        for (String alias : aliases) if (!before.aliases.contains(alias)) {
            BotCacheEntry cached = botCache.get(alias);
            bots.put(alias, cached != null && cached.provider.equals(urlSnapshot) ? cached.profile : "");
            oldTags.put(alias, tags.get(alias));
        }
        ignoredPlayers = new IgnoredPlayers(aliases, ids, entities, bots, oldTags);
        nextScan = 0;
    }

    private static void addPausedAlias(Set<String> names, String name) {
        if (names.size() < 1024 && isPlayerName(name)) names.add(lower(name));
    }

    private static void addIgnoredProfileAlias(String profile) {
        if (profile == null) return;
        int separator = profile.indexOf('|');
        if (separator > 0) addIgnoredAlias(profile.substring(0, separator));
    }

    public static synchronized void ensureInitialized() {
        if (initialized || stopped) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.mcDataDir == null) return;
            settingsPath = AdninSharedConfig.path();
            if (settingsPath == null) return;
            // The native initializer has already applied the old toggles.
            boolean migrate = AdninSharedConfig.load(settingsPath,
                new File(mc.mcDataDir, "adnin-features.properties").toPath());
            settingsSnapshot = AdninSharedConfig.capture();
            if (migrate) { pendingSave.set(settingsSnapshot); saveAt = System.currentTimeMillis(); }
        } catch (Exception ignored) { /* Existing defaults remain usable. Never log secrets. */ }
        if (settingsPath == null) return;
        initialized = true;
        worker = new Thread(new AdninFeatures(), "Adnin API worker");
        worker.setDaemon(true);
        worker.start();
    }

    public static synchronized void requestSave() {
        if (stopped) return;
        ensureInitialized();
        Properties p = AdninSharedConfig.capture();
        if (p.equals(settingsSnapshot)) return;
        settingsSnapshot = p;
        pendingSave.set(p);
        saveAt = System.currentTimeMillis() + 600;
    }

    @Override public void run() {
        long nextRequest = 0;
        try { while (!stopped) {
            String[] job = null;
            try {
                try { saveIfDue(); writeDiagnostics(); } catch (IOException ignored) { /* Retry on a later poll. */ }
                job = pollRequest();
                if (job == null) { Thread.sleep(250); continue; }
                if (!currentJob(job)) { retireFailedPublication(job); continue; }
                long wait = nextRequest - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                if (!canStartRequest(job)) { retireFailedPublication(job); continue; }
                nextRequest = System.currentTimeMillis() + 700;
                List<String> data = new ArrayList<String>();
                String error = "";
                try {
                    if ("bot".equals(job[1])) {
                        String realName = AdninApi.fetchBot(job[4], job[2]);
                        data.add(realName);
                        if (!realName.isEmpty()) {
                            try { data.add(AdninApi.fetchMojangUuid(realName)); }
                            catch (IOException unavailable) { error = "profile-unavailable"; }
                        }
                    } else data.addAll(AdninApi.fetchUrchin(job[3], job[4]));
                } catch (IOException failure) {
                    error = "urchin".equals(job[1]) ? AdninApi.errorCode(failure) : "request-failed";
                }
                if ("urchin".equals(job[1])) {
                    if (error.isEmpty()) urchinSucceeded++; else urchinFailed++;
                    urchinLastError = error.isEmpty() ? "none" : error;
                }
                List<String> result = new ArrayList<String>();
                result.add(job[0]); result.add(job[1]); result.add(job[2]); result.add(error);
                result.add(job[3]); result.add(job[5]);
                result.addAll(data);
                // Keep the provider bound to the response. A result that
                // arrives after the setting changes must never populate the
                // next provider's identity cache. Appending it preserves the
                // existing result indexes consumed by offline fixtures.
                if ("bot".equals(job[1])) result.add("\u0001" + job[4]);
                publishResult(job, result.toArray(new String[0]));
                apiCompleted++;
            } catch (InterruptedException shutdown) {
                retireFailedPublication(job);
                Thread.currentThread().interrupt(); return;
            }
              catch (Exception failure) { retireFailedPublication(job); }
        } } finally {
            // Finish only an already requested settings save. Never wait for
            // this daemon on the render/native unload thread.
            try { saveAt = 0; saveIfDue(); } catch (Exception ignored) { }
            pendingSave.set(null);
        }
    }

    private static boolean currentJob(String[] job) {
        // Urchin content belongs to the identity, not the match which happened
        // to start the HTTP call. Same-key in-flight results remain reusable.
        return !stopped && job[0].equals(Integer.toString("bot".equals(job[1]) ? botGeneration : generation));
    }

    /** Remove and register a paced job atomically with match/key retirement.
     * The poll never blocks while holding the client integration monitor. */
    private static synchronized String[] pollRequest() {
        String[] job = requests.poll();
        if (job != null && "urchin".equals(job[1])) waitingUrchin = job;
        return job;
    }

    /** Claim only unstarted Urchin work in the match which admitted its roster.
     * Once HTTP has begun, currentJob still permits its same-key result to
     * populate the cross-match cache after leaving that match. */
    private static synchronized boolean canStartRequest(String[] job) {
        if (!"urchin".equals(job[1])) return currentJob(job) && !shouldIgnorePlayer(job[2]);
        boolean ownsWaiting = waitingUrchin == job;
        if (ownsWaiting) waitingUrchin = null;
        return ownsWaiting && currentJob(job) && !shouldIgnorePlayer(job[2]) && nativeGameActive
            && currentMatch == matchStarts.get() && job[5].equals(Long.toString(currentMatch));
    }

    // Serialize the final epoch check and insertion with world changes/unload.
    // Network work stays outside this monitor.
    private static synchronized void publishResult(String[] job, String[] result) {
        if (currentJob(job) && !results.offer(result)) retireFailedPublication(job);
    }

    private static synchronized void retireFailedPublication(String[] job) {
        if (waitingUrchin == job) waitingUrchin = null;
        if (job == null || !currentJob(job)) return;
        if ("urchin".equals(job[1])) urchinCache.cancel(Long.parseLong(job[5]), job[3]);
        else if ("bot".equals(job[1])) {
            if (shouldIgnorePlayer(job[2])) {
                // A paused gray observation is not a provider failure. Existing
                // identity results remain untouched and restoration may retry.
                requested.remove("bot:" + lower(job[2]));
                return;
            }
            // A full result queue or unexpected worker exception is a failed
            // attempt, not a ten-minute successful identity cache entry.
            requested.put("bot:" + lower(job[2]), System.currentTimeMillis() - CACHE_MS + FAILURE_MS);
            trimMap(requested, MAX_CACHE);
        }
    }

    /** Native Ingame transition only. No Minecraft calls, network, or file IO. */
    public static synchronized void matchStarted() {
        if (stopped) return;
        // The native callback is the authoritative Ingame transition. Mark it
        // active before rebuilding the serial match token so the first tick
        // cannot briefly suppress a valid output event.
        setGameActive(true);
        matchStarts.incrementAndGet();
        clearPartyQueue();
        clearAnnouncements("bot:");
        candidateTimes.clear(); nickHints.clear();
        AdninSkinDenicker.clearContext();
        AdninMatchTeams.beginMatch();
    }

    /** Native state snapshot, published on every state poll including leaving a game. */
    public static synchronized void setGameActive(boolean active) {
        if (stopped) return;
        if (nativeGameActive != active) {
            clearPartyQueue();
            if (!active) retireQueuedUrchin();
        }
        nativeGameActive = active;
        AdninMatchTeams.setGameActive(active);
    }

    static boolean outputContextAllowed() {
        return !stopped && (nativeGameActive || AdninReplay.isReplay());
    }

    /** Stop optional work and release world/response references before native unload. */
    public static synchronized void shutdown() {
        if (stopped) return;
        stopped = true;
        AdninSkinDenicker.shutdown();
        nativeGameActive = outputReplayContext = false;
        generation++; botGeneration++;
        Thread active = worker; worker = null;
        if (active != null) active.interrupt();
        requests.clear(); results.clear(); nickHints.clear(); matchRequests.clear(); waitingUrchin = null;
        botCache.clear(); botProfiles.clear();
        candidateTimes.clear(); requested.clear();
        tags.clear(); tagLabels.clear(); urchinCache.clear(); announced.clear();
        present.clear(); displayNames.clear(); nametagNames.clear();
        ignoredPlayers = NO_IGNORED;
        ignoredScratch.clear(); ignoredIdsScratch.clear(); ignoredEntitiesScratch.clear(); ignoredRosterScratch.clear();
        outputPlayerIds.clear(); outputPlayerActors.clear();
        ignoredWorld = ignoredConnection = null; ignoredAt = Long.MIN_VALUE;
        clearPartyQueue(); world = null; keySnapshot = ""; urlSnapshot = "";
        settingsSnapshot = null;
        if (active == null) pendingSave.set(null);
    }

    private static void saveIfDue() throws IOException {
        Properties p = pendingSave.get();
        Path path = settingsPath;
        if (p == null || path == null || System.currentTimeMillis() < saveAt) return;
        if (AdninSharedConfig.write(path, p)) pendingSave.compareAndSet(p, null);
    }

    private static void writeDiagnostics() throws IOException {
        if (settingsPath == null || System.currentTimeMillis() < diagnosticAt) return;
        diagnosticAt = System.currentTimeMillis() + 5000;
        Properties p = new Properties();
        p.setProperty("initialized", "true");
        p.setProperty("ticks", Long.toString(ticks));
        p.setProperty("apiCompleted", Long.toString(apiCompleted));
        p.setProperty("nativeEvents", Long.toString(nativeEvents));
        p.setProperty("tagRowsDrawn", Integer.toString(rowCount));
        p.setProperty("matchStarts", Long.toString(matchStarts.get()));
        p.setProperty("urchinSucceeded", Long.toString(urchinSucceeded));
        p.setProperty("urchinFailed", Long.toString(urchinFailed));
        p.setProperty("urchinLastError", urchinLastError);
        AdninReplay.diagnostics(p);
        AdninAnticheat.diagnostics(p);
        // No URLs, keys, chat contents, player names, or API response data.
        try (OutputStream out = Files.newOutputStream(settingsPath.resolveSibling("adnin-runtime-status.properties"))) {
            p.store(out, "Adnin integration status");
        }
    }

    /** Invoked by the existing native pump on Minecraft's client thread. */
    public static synchronized void tick() {
        if (stopped) return;
        ensureInitialized();
        ticks++;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread()) return;
        AdninGameModules.tick(mc);
        if (stopped) return;
        refreshIgnoredPlayers(mc);
        preparePartyQueue(AdninReplay.isReplay());
        long now = System.currentTimeMillis();
        String key = bounded(AdninGui4.api_urchin, 512).trim();
        String url = bounded(AdninGui4.botDenickerUrl, 2048).trim();
        boolean changedWorld = world != mc.theWorld;
        boolean changedUrchin = !key.equals(keySnapshot);
        // Nick/Denick identity and negative cooldowns are session caches like
        // Seraph: a world transition changes the visible roster, not the URL
        // provider or the cached identity result.
        boolean changedBot = !url.equals(urlSnapshot) || botSnapshot != AdninGui4.botDenicker;
        if (changedUrchin) {
            generation++;
            urchinCache.clearPending();
            keySnapshot = key;
            tags.clear(); tagLabels.clear(); matchRequests.clear(); clearAnnouncements("urchin:");
        }
        if (changedBot) {
            botGeneration++;
            urlSnapshot = url; botSnapshot = AdninGui4.botDenicker;
            nickHints.clear(); requested.clear(); clearAnnouncements("bot:");
            botCache.clear(); botProfiles.clear();
            candidateTimes.clear();
            warnedUrl = false;
        }
        if ((changedUrchin || changedBot) && ignoredPlayers != NO_IGNORED) {
            IgnoredPlayers prior = ignoredPlayers;
            ignoredPlayers = new IgnoredPlayers(prior.aliases, prior.ids, prior.entities,
                changedBot ? Collections.<String,String>emptyMap() : prior.botResults,
                changedUrchin ? Collections.<String,List<String>>emptyMap() : prior.tagResults);
        }
        if (changedWorld) {
            retireQueuedUrchin();
            urchinCache.clearMatch(); tags.clear(); tagLabels.clear(); clearAnnouncements("urchin:");
            world = mc.theWorld;
            present.clear(); displayNames.clear(); nametagNames.clear(); clearPartyQueue(); nextScan = 0;
            clearAnnouncements("bot:");
            candidateTimes.clear(); nickHints.clear();
            AdninSkinDenicker.clearContext();
        }
        // Old jobs retain response strings and credentials even though their
        // generation will be rejected. Retire them immediately on transitions.
        if (changedUrchin || changedBot) discardStaleJobs();
        if (mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            setGameActive(false); clearPartyQueue(); return;
        }
        if (mc.currentScreen instanceof AdninGui4) return;
        if (now >= nextScan) {
            nextScan = now + 1000;
            scanPlayers(mc, now);
        }
        long started = matchStarts.get();
        if (started != currentMatch) {
            currentMatch = started;
            discardStaleJobs();
            scanPlayers(mc, now);
            beginUrchinMatch(mc, now);
        }
        AdninSkinDenicker.tick(mc);
        while (!matchRequests.isEmpty() && requests.offer(matchRequests.peekFirst())) matchRequests.removeFirst();
        for (int i = 0; i < 16; i++) {
            String[] hint = nickHints.poll();
            if (hint == null) break;
            if (hint[0].equals(Integer.toString(botGeneration))) lookupNick(hint[1], now);
        }
        for (int i = 0; i < 16; i++) {
            String[] result = results.poll();
            if (result == null) break;
            if ("bot".equals(result[1])) {
                applyBotResult(result, now, mc);
            } else {
                if (!result[0].equals(Integer.toString(generation))) continue;
                ignoredAtAction(result[2]); // Freeze visible tags before a respawn-time completion changes them.
                List<String> playerTags = new ArrayList<String>();
                for (int j = 6; j < result.length; j++) playerTags.add(cleanText(result[j]));
                if (!urchinCache.complete(Long.parseLong(result[5]), result[4], playerTags,
                        result[3].isEmpty(), AdninReplayProfiles.now())) continue;
                applyUrchinContent();
                if (result[5].equals(Long.toString(currentMatch)) && !ignoredAtAction(result[2]))
                    local(urchinErrorForChat(result[3], now), mc);
            }
        }
        if (anyOutputEnabled() && now >= nextSend && !mc.isSingleplayer() && hasPartyOutput(now)) {
            // Probe on the client thread, immediately before splitting. Lunar's
            // packet constructor applies its current server/module chat policy.
            nextSend = now + SEND_MS;
            int limit = currentPartyCommandLimit();
            if (limit > 0) {
                String command = pollPartyCommand(now, limit);
                if (command != null) mc.thePlayer.sendChatMessage(command);
            }
        }
    }

    private static synchronized void discardStaleJobs() {
        requests.removeIf(job -> !currentJob(job));
        results.removeIf(job -> !currentJob(job));
        if (waitingUrchin != null && !currentJob(waitingUrchin)) {
            urchinCache.cancel(Long.parseLong(waitingUrchin[5]), waitingUrchin[3]);
            waitingUrchin = null;
        }
    }

    private static synchronized void retireQueuedUrchin() {
        if (waitingUrchin != null) {
            urchinCache.cancel(Long.parseLong(waitingUrchin[5]), waitingUrchin[3]);
            waitingUrchin = null;
        }
        for (Iterator<String[]> it = requests.iterator(); it.hasNext();) {
            String[] job = it.next();
            if ("urchin".equals(job[1])) {
                it.remove();
                urchinCache.cancel(Long.parseLong(job[5]), job[3]);
            }
        }
        for (String[] job : matchRequests) urchinCache.cancel(Long.parseLong(job[5]), job[3]);
        matchRequests.clear();
    }

    /** Successful verified identities alone may enter the party-output queue. */
    private static void applyBotResult(String[] result, long now, Minecraft mc) {
        if (result == null || result.length < 6 || !"bot".equals(result[1])
                || !Integer.toString(botGeneration).equals(result[0])
                || !isPlayerName(result[2])) return;
        String id = lower(result[2]);
        // Freeze any pre-respawn identity before this completion updates the
        // reusable cache. The response itself is still retained for recovery.
        boolean paused = ignoredAtAction(result[2]);
        int end = result.length;
        String provider = urlSnapshot;
        if (end > 6 && result[end - 1] != null && result[end - 1].startsWith("\u0001")) {
            provider = bounded(result[--end].substring(1), 2048).trim();
        }
        // Older owned fixtures omit the provider trailer; live worker results
        // always carry it and are rejected when the setting changed.
        if (!provider.isEmpty() && !provider.equals(urlSnapshot)) return;
        String match = end > 6 && isPlayerName(result[6]) ? result[6] : "";
        boolean verified = !match.isEmpty() && "".equals(result[3]) && end > 7
            && isOnlineUuid(result[7]);
        String status = verified ? "verified" : match.isEmpty() && result[3].isEmpty() ? "empty" : "failure";
        long ttl = verified || "empty".equals(status) ? SUCCESS_MS : FAILURE_MS;
        String profile = verified ? match + "|" + lower(result[7]) : "";
        botCache.put(id, new BotCacheEntry(profile, provider, status, now + ttl));
        if (verified) botProfiles.put(id, profile); else botProfiles.remove(id);
        trimBotCache();
        // The scheduler stamp and the atomic entry have the same TTL.
        requested.put("bot:" + id, now - CACHE_MS + ttl);
        trimMap(requested, MAX_CACHE);
        // A worker result remains cached when the player briefly leaves the
        // roster. It becomes visible only after that identity is observed
        // again; a cached success can announce once in each later match.
        if (!present.contains(id) || paused) return;
        String message = formattedBotMessage(result[2], nametagNames.get(id), match,
            nametagNames.get(lower(match)), verified, result[3]);
        String outcome = verified ? "verified" : match.isEmpty() ? "none" : "unverified";
        if (!announced.add("bot:" + id + ":" + match + ":" + outcome)) return;
        local(message, mc);
        if (verified) enqueueParty(OUTPUT_DENICK, message, now, result[2]);
    }

    private static void scanPlayers(Minecraft mc, long now) {
        present.clear(); displayNames.clear(); nametagNames.clear();
        Collection<NetworkPlayerInfo> players = mc.getNetHandler().getPlayerInfoMap();
        int count = 0;
        for (NetworkPlayerInfo info : players) {
            if (++count > 256) break;
            if (info == null || info.getGameProfile() == null) continue;
            String name = info.getGameProfile().getName();
            if (AdninReplay.isReplay()) {
                name = AdninReplay.recordedName(name);
                if (name.isEmpty()) continue;
            } else if (!isRealTabProfile(name, info.getGameProfile().getId())) continue;
            present.add(lower(name));
            displayNames.put(lower(name), name);
            nametagNames.put(lower(name), playerNametag(info, name));
            // Bot candidates come only from the original native Denicker's
            // filtered Tab-player path, never from UUID version alone.
        }
        applyUrchinContent();
        if (announced.size() > 2048) announced.clear();
    }

    /** Eligible current Tab profiles: normal accounts or server nick identities.
     * This excludes the native UUID-v2 bot category, offline/non-player UUID
     * versions, and malformed profiles. It cannot authenticate a server-forged
     * v1/v4 profile as human; the original native filter has that same boundary.
     */
    public static boolean isRealTabProfile(String name, UUID uuid) {
        return isPlayerName(name) && uuid != null && (uuid.version() == 1 || uuid.version() == 4);
    }

    private static void beginUrchinMatch(Minecraft mc, long now) {
        // A start serial may have waited behind /config or a delayed client
        // tick. Consume it without capturing a lobby/Replay roster once that
        // native game has ended; only the next real start can create a batch.
        if (stopped || !nativeGameActive || currentMatch != matchStarts.get()) return;
        // Freeze this one roster batch. Later Tab arrivals, Bot resolutions and
        // expired cache entries never create a mid-match Urchin request.
        tags.clear(); tagLabels.clear(); retireQueuedUrchin();
        clearAnnouncements("urchin:");
        Map<String, String> roster = new LinkedHashMap<String, String>();
        int count = 0;
        if (!keySnapshot.isEmpty()) for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (++count > 256) break;
            if (info == null || info.getGameProfile() == null) continue;
            String name = info.getGameProfile().getName();
            UUID uuid = info.getGameProfile().getId();
            if (!isRealTabProfile(name, uuid)) continue;
            String botId = lower(name);
            BotCacheEntry bot = botCache.get(botId);
            String resolved = bot != null && now < bot.expires && "verified".equals(bot.status)
                && bot.provider.equals(urlSnapshot) ? bot.profile : null;
            if (bot != null && now >= bot.expires) { botCache.remove(botId, bot); botProfiles.remove(botId); }
            String lookup = resolved != null ? resolved.substring(resolved.indexOf('|') + 1)
                : (uuid.version() == 4 ? uuid.toString() : name);
            roster.put(name, lookup);
        }
        Map<String, String> batch = urchinCache.beginMatch(currentMatch, roster, AdninReplayProfiles.now());
        for (Map.Entry<String, String> player : batch.entrySet()) {
            if (ignoredAtAction(player.getKey())) {
                // Keep any previously successful visible content while declining
                // this start's new request. Color alone never clears a result.
                urchinCache.cancel(currentMatch, player.getValue());
                continue;
            }
            matchRequests.addLast(new String[]{Integer.toString(generation), "urchin", player.getKey(),
                player.getValue(), keySnapshot, Long.toString(currentMatch)});
        }
        applyUrchinContent();
    }

    private static void applyUrchinContent() {
        tags.clear(); tags.putAll(urchinCache.visibleTags());
        // Accepted in-flight data still enters the cross-match cache, but gray
        // respawn display state stays exactly as it was before the pause.
        IgnoredPlayers ignored = ignoredPlayers;
        for (String name : ignored.aliases) {
            List<String> prior = ignored.tagResults.get(name);
            if (prior == null) tags.remove(name); else tags.put(name, prior);
        }
        tagLabels.clear();
        for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
            if (!entry.getValue().isEmpty()) tagLabels.put(entry.getKey(), tagTypes(entry.getValue()));
            if (entry.getValue().isEmpty() || !present.contains(entry.getKey())) continue;
            if (ignoredAtAction(entry.getKey())) continue;
            String name = displayNames.containsKey(entry.getKey()) ? displayNames.get(entry.getKey()) : entry.getKey();
            String message = formattedUrchinMessage(name, nametagNames.get(entry.getKey()), entry.getValue());
            if (announced.add("urchin:" + entry.getKey())) generatedLocal(message);
        }
    }

    private static String playerNametag(NetworkPlayerInfo info, String name) {
        // Scoreboard teams format the actual in-world nametag. A custom Tab
        // display name is only a fallback when that team supplies no name color.
        ScorePlayerTeam team = info.getPlayerTeam();
        if (team != null) {
            String colored = coloredNameSpan(name, ScorePlayerTeam.formatPlayerName(team, name));
            if (colored != null) return colored;
        }
        IChatComponent display = info.getDisplayName();
        return coloredPlayerName(name, display == null ? null : display.getFormattedText());
    }

    /** Copy only the real roster name's colors, never its rank or other text. */
    public static String coloredPlayerName(String name, String formattedName) {
        if (!isPlayerName(name)) return "";
        String colored = coloredNameSpan(name, formattedName);
        return colored == null ? "\u00a7f" + name + "\u00a7r" : colored;
    }

    private static String coloredNameSpan(String name, String formattedName) {
        if (!isPlayerName(name) || formattedName == null || formattedName.length() > 4096) return null;
        StringBuilder plain = new StringBuilder();
        char[] colors = new char[formattedName.length()];
        char color = 0;
        for (int i = 0; i < formattedName.length(); i++) {
            char c = formattedName.charAt(i);
            if (c == '\u00a7') {
                if (++i >= formattedName.length()) break;
                char code = Character.toLowerCase(formattedName.charAt(i));
                if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') color = code;
                else if (code == 'r') color = 'f';
                continue;
            }
            colors[plain.length()] = color;
            plain.append(c);
        }
        String visible = plain.toString();
        // The last complete token avoids mistaking a rank prefix for the name.
        for (int start = visible.length() - name.length(); start >= 0; start--) {
            int end = start + name.length();
            if (!visible.regionMatches(true, start, name, 0, name.length())
                    || start > 0 && playerNameCharacter(visible.charAt(start - 1))
                    || end < visible.length() && playerNameCharacter(visible.charAt(end))) continue;
            boolean explicit = false;
            StringBuilder result = new StringBuilder();
            char previous = 0;
            for (int i = 0; i < name.length(); i++) {
                char active = colors[start + i];
                if (active != 0) explicit = true;
                else active = 'f';
                if (active != previous) result.append('\u00a7').append(active);
                result.append(name.charAt(i));
                previous = active;
            }
            return explicit ? result.append("\u00a7r").toString() : null;
        }
        return null;
    }

    private static boolean playerNameCharacter(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }

    /** Allocation-free validation on the hot Tab/denicker paths. */
    private static boolean isPlayerName(String name) {
        if (name == null || name.length() == 0 || name.length() > 16) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return true;
    }

    private static boolean isOnlineUuid(String value) {
        if (value == null || value.length() != 36) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
                continue;
            }
            if (i == 14 && c != '4') return false;
            if (i == 19 && !(c == '8' || c == '9' || c == 'a' || c == 'b'
                    || c == 'A' || c == 'B')) return false;
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f'
                    || c >= 'A' && c <= 'F')) return false;
        }
        return true;
    }

    /** Cached tag contents remain untouched; colors are added only for display. */
    public static String formattedUrchinMessage(String name, String formattedName, List<String> values) {
        String player = coloredPlayerName(name, formattedName);
        if (player.isEmpty()) return "";
        StringBuilder message = new StringBuilder("[Urchin] ").append(player).append("\u00a77: \u00a7r");
        boolean first = true;
        if (values != null) for (String value : values) {
            String tag = AdninMetrics.coloredTagText(value);
            if (tag.isEmpty()) continue;
            if (!first) message.append("\u00a77 | \u00a7r");
            message.append(tag);
            first = false;
        }
        return message.toString();
    }

    /** Gold feature label, roster-derived name colors, and red failure text. */
    public static String formattedBotMessage(String nick, String formattedNick, String realName,
            String formattedReal, boolean verified, String error) {
        String player = coloredPlayerName(nick, formattedNick);
        if (player.isEmpty()) return "";
        StringBuilder message = new StringBuilder("\u00a76" + AdninLanguage.text("Bot Denicker") + "\u00a7r ")
            .append(player).append("\u00a77 → \u00a7r");
        if (!isPlayerName(realName)) {
            message.append("\u00a7c").append(error == null || error.isEmpty()
                ? AdninLanguage.text("No results") : AdninLanguage.text("Request failed. Will retry.")).append("\u00a7r");
        } else {
            // A resolved alias belongs to the nicked player. If it has no own
            // visible nametag, inherit that player's color, never an API style.
            String ownColor = coloredNameSpan(realName, formattedReal);
            message.append(ownColor == null
                ? player.substring(0, 2) + realName + "\u00a7r" : ownColor);
            if (!verified) message.append(" \u00a7c").append(AdninLanguage.text("(account verification unavailable; will retry)")).append("\u00a7r");
        }
        return message.toString();
    }

    /** Suppressed/missing-player notices do not consume another error's throttle. */
    private static String urchinErrorForChat(String error, long now) {
        if (error == null || error.isEmpty()) return "";
        String message = AdninApi.urchinErrorMessage(error);
        if (message.isEmpty() || now < nextError) return "";
        nextError = now + 60000;
        return message;
    }

    private static void clearAnnouncements(String prefix) {
        for (Iterator<String> it = announced.iterator(); it.hasNext();) {
            if (it.next().startsWith(prefix)) it.remove();
        }
    }

    /** Only queued native Tab-player candidates call this client-thread method. */
    public static void lookupNick(String name, long now) {
        if (!AdninGui4.botDenicker || !isPlayerName(name) || ignoredAtAction(name)) return;
        if (AdninReplay.isReplay()) {
            if (!AdninReplay.isNick(name)) return;
            name = AdninReplay.recordedName(name);
            if (name.isEmpty()) return;
        }
        if (!present.contains(lower(name)) || ignoredAtAction(name)) return;
        if (urlSnapshot.isEmpty()) {
            if (!warnedUrl) { local("\u00a76" + AdninLanguage.text("Bot Denicker") + "\u00a7r: \u00a7c" + AdninLanguage.text("Enter an API URL containing <> first.") + "\u00a7r", Minecraft.getMinecraft()); warnedUrl = true; }
            return;
        }
        String cachedMessage = cachedBotMessage(name, now);
        if (cachedMessage != null) {
            if (!cachedMessage.isEmpty()) {
                local(cachedMessage, Minecraft.getMinecraft());
                enqueueParty(OUTPUT_DENICK, cachedMessage, now, name);
            }
            return;
        }
        schedule("bot", name, name, urlSnapshot, now);
    }

    /** Null is a cache miss; an empty message is a hit needing no presentation. */
    private static String cachedBotMessage(String name, long now) {
        if (ignoredAtAction(name)) return "";
        String id = lower(name);
        BotCacheEntry cached = botCache.get(id);
        if (cached == null || now >= cached.expires || !cached.provider.equals(urlSnapshot)) return null;
        if (!"verified".equals(cached.status) || !present.contains(id) || !outputContextAllowed()) return "";
        String real = cached.profile.substring(0, cached.profile.indexOf('|'));
        if (announced.add("bot:" + id + ":" + real + ":verified")) {
            return formattedBotMessage(name, nametagNames.get(id), real,
                nametagNames.get(lower(real)), true, "");
        }
        return "";
    }

    private static void schedule(String kind, String name, String player, String credential, long now) {
        if (shouldIgnorePlayer(name)) return;
        String id = kind + ":" + lower(name);
        String key = lower(name);
        BotCacheEntry cached = botCache.get(key);
        if (cached != null && now < cached.expires && cached.provider.equals(credential)) return;
        Long last = requested.get(id);
        if (last != null && now - last < CACHE_MS) return;
        if (!"bot".equals(kind)) return;
        if (requests.offer(new String[]{Integer.toString(botGeneration), kind, name, player, credential, ""})) {
            requested.put(id, now); trimMap(requested, MAX_CACHE);
        }
    }

    private static void generatedLocal(String text) {
        if (ignoredAtAction(tagSubject(text))) return;
        local(text, Minecraft.getMinecraft());
        enqueueParty(OUTPUT_TAGS, text, System.currentTimeMillis());
    }

    /** Client-thread Skin texture-owner evidence; a stale roster must not consume presentation. */
    public static synchronized boolean skinResolved(String nick, String realName) {
        if (!outputContextAllowed() || !isPlayerName(nick) || !isPlayerName(realName)
                || ignoredAtAction(nick) || nick.equalsIgnoreCase(realName) || !present.contains(lower(nick))) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.ingameGUI == null
                || !mc.isCallingFromMinecraftThread()) return false;
        String message = formattedSkinMessage(nick, nametagNames.get(lower(nick)), realName);
        local(message, mc);
        enqueueParty(OUTPUT_DENICK, message, System.currentTimeMillis(), nick);
        return true;
    }

    static String formattedSkinMessage(String nick, String formattedNick, String realName) {
        if (!isPlayerName(realName)) return "";
        String player = coloredPlayerName(nick, formattedNick);
        if (player.isEmpty()) return "";
        return "\u00a76" + AdninLanguage.text("Skin Denicker") + "\u00a7r " + player
            + "\u00a77 → " + player.substring(0, 2) + realName + "\u00a7r";
    }

    private static void local(String text, Minecraft mc) {
        if (text == null || text.isEmpty()) return;
        String message = text.startsWith("[Urchin]") ? "\u00a7d[Urchin]\u00a7r" + text.substring(8) : PREFIX + text;
        if (mc != null && mc.ingameGUI != null) mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(message));
    }

    /** Called after native Tab bot filtering. Never performs network or file IO. */
    public static synchronized String getBotProfile(String name) {
        if (stopped || !AdninGui4.botDenicker || !isPlayerName(name)) return "";
        String recorded = name;
        if (AdninReplay.isReplay()) {
            if (!AdninReplay.isNick(name)) return "";
            recorded = AdninReplay.recordedName(name);
            if (recorded.isEmpty()) return "";
        }
        String id = lower(recorded);
        long now = System.currentTimeMillis();
        BotCacheEntry cached = botCache.get(id);
        // Pause lookup/presentation during a gray respawn, but preserve the
        // same provider's already resolved identity for native cached rendering.
        if (shouldIgnorePlayer(name) || shouldIgnorePlayer(recorded)) {
            String prior = ignoredPlayers.botResults.get(id);
            return prior == null ? "" : prior;
        }
        if (cached != null && now < cached.expires && cached.provider.equals(urlSnapshot)) {
            if ("verified".equals(cached.status) && outputContextAllowed()) {
                String real = cached.profile.substring(0, cached.profile.indexOf('|'));
                if (!announced.contains("bot:" + id + ":" + real + ":verified")) queueNickHint(name, id, now);
            }
            // Negative cache reads never create work. Positive cache hints
            // only ask the client thread to announce; they perform no HTTP.
            return cached.profile;
        }
        if (cached != null && now >= cached.expires) {
            botCache.remove(id, cached); botProfiles.remove(id);
        }
        queueNickHint(name, id, now);
        return "";
    }

    private static void queueNickHint(String name, String id, long now) {
        Long last = candidateTimes.get(id);
        if ((last == null || now - last >= 1000)
                && nickHints.offer(new String[]{Integer.toString(botGeneration), name})) {
            candidateTimes.put(id, now);
            trimMap(candidateTimes, MAX_CACHE);
        }
    }

    /** Only allowlisted generated-message native callsites invoke this. */
    public static void nativeGeneratedEvent(String text, boolean json) {
        nativeGeneratedEvent(text, json, OUTPUT_PLAYERS);
    }

    public static int nativeRenderGeneratedEvent(String text, boolean json, int category) {
        if (stopped) return 0;
        // Return handled to prevent the original native renderer from showing
        // a currently excluded player's event as its fallback.
        if (ignoredGeneratedEvent(text, json, category)) return 1;
        nativeGeneratedEvent(text, json, category);
        return AdninMessages.renderGenerated(text, json, category);
    }

    public static void nativeGeneratedEvent(String text, boolean json, int category) {
        if (stopped) return;
        nativeEvents++;
        if (!outputContextAllowed() || !outputEnabled(category)) return;
        if (text == null || text.length() > 16384) return;
        if (ignoredGeneratedEvent(text, json, category)) return;
        // Keep the original subject before translation changes a known prefix.
        // Delayed delivery must still recognize a newly gray player in every language.
        String subject = generatedSubject(text, json, category);
        if (ignoredAtAction(subject)) return;
        String localized = AdninMessages.translateGenerated(text, json, category);
        if (localized != null) text = localized;
        if (json) {
            try {
                IChatComponent component = IChatComponent.Serializer.jsonToComponent(text);
                if (component == null) return;
                text = component.getUnformattedText();
            } catch (RuntimeException invalid) { return; }
        }
        enqueueParty(category, text, System.currentTimeMillis(), subject);
    }

    public static void anticheatGeneratedEvent(String text) {
        enqueueParty(OUTPUT_ANTICHEAT, text, System.currentTimeMillis());
    }

    public static boolean outputEnabled(int category) {
        return category == OUTPUT_PLAYERS ? AdninGui4.chatOutput
            : category == OUTPUT_TAGS ? AdninGui4.chatOutputTags
            : category == OUTPUT_ANTICHEAT ? AdninGui4.chatOutputAnticheat
            : category == OUTPUT_DENICK && AdninGui4.chatOutputDenick;
    }

    public static boolean anyOutputEnabled() {
        return AdninGui4.chatOutput || AdninGui4.chatOutputDenick || AdninGui4.chatOutputTags || AdninGui4.chatOutputAnticheat;
    }

    /** Client-thread housekeeping; enabling Output must preserve newly admitted events. */
    private static void preparePartyQueue(boolean replayNow) {
        if (replayNow != outputReplayContext) {
            clearPartyQueue();
            AdninSkinDenicker.clearContext();
            nickHints.clear();
            if (replayNow) {
                // Replay may start inside the same world after lobby lookups.
                // Keep identity results, but permit one presentation in this Replay.
                clearAnnouncements("bot:");
                candidateTimes.clear();
            }
        }
        outputReplayContext = replayNow;
        if (!outputContextAllowed() || !anyOutputEnabled()) clearPartyQueue();
    }

    /** Closing a category immediately discards its queued fragments. */
    public static synchronized void outputSettingsChanged() {
        for (Iterator<String[]> it = outbox.iterator(); it.hasNext();) {
            String[] item = it.next();
            if (!outputItemAllowed(item)) it.remove();
        }
    }

    static void loadOutputSettings(Properties p) {
        String legacy = p.getProperty("chat.output", "false");
        AdninGui4.chatOutput = Boolean.parseBoolean(p.getProperty("chat.output.players", legacy));
        AdninGui4.chatOutputDenick = Boolean.parseBoolean(p.getProperty("chat.output.denick",
            p.getProperty("chat.output.players", legacy)));
        AdninGui4.chatOutputTags = Boolean.parseBoolean(p.getProperty("chat.output.tags", legacy));
        AdninGui4.chatOutputAnticheat = Boolean.parseBoolean(p.getProperty("chat.output.anticheat", "false"));
        AdninGui4.chatOutputTagsSelf = Boolean.parseBoolean(p.getProperty("chat.output.tags.self", "true"));
        AdninGui4.chatOutputTagsTeammates = Boolean.parseBoolean(p.getProperty("chat.output.tags.teammates", "true"));
    }

    static void saveOutputSettings(Properties p) {
        p.setProperty("chat.output", Boolean.toString(AdninGui4.chatOutput));
        p.setProperty("chat.output.players", Boolean.toString(AdninGui4.chatOutput));
        p.setProperty("chat.output.denick", Boolean.toString(AdninGui4.chatOutputDenick));
        p.setProperty("chat.output.tags", Boolean.toString(AdninGui4.chatOutputTags));
        p.setProperty("chat.output.anticheat", Boolean.toString(AdninGui4.chatOutputAnticheat));
        p.setProperty("chat.output.tags.self", Boolean.toString(AdninGui4.chatOutputTagsSelf));
        p.setProperty("chat.output.tags.teammates", Boolean.toString(AdninGui4.chatOutputTagsTeammates));
    }

    /** Remove formatting/control characters before enforcing the /pc boundary. */
    public static String cleanText(String text) {
        if (text == null) return "";
        StringBuilder clean = new StringBuilder();
        boolean skipFormat = false;
        for (int i = 0; i < text.length() && clean.length() < 1500; i++) {
            char c = text.charAt(i);
            if (skipFormat) { skipFormat = false; continue; }
            if (c == '\u00a7') { skipFormat = true; continue; }
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) { clean.append(' '); continue; }
            clean.append(c);
        }
        return WHITESPACE.matcher(clean.toString().trim()).replaceAll(" ");
    }

    /** Only party output drops our own leading brand; local chat remains intact. */
    public static String partyText(String text) {
        String clean = cleanText(text);
        if (clean.startsWith("[Adnin]")) clean = clean.substring(7).trim();
        // cleanText bounds input; never retain half a pair at its truncation edge.
        if (!clean.isEmpty() && Character.isHighSurrogate(clean.charAt(clean.length() - 1)))
            clean = clean.substring(0, clean.length() - 1);
        return clean;
    }

    private static String partyLimitProbe() {
        char[] body = new char[MAX_PARTY_COMMAND_UNITS - 4];
        Arrays.fill(body, 'x');
        return "/pc " + new String(body);
    }

    /** Construct only: no packet is queued or sent by this capacity probe. */
    static int currentPartyCommandLimit() {
        try {
            String accepted = new C01PacketChatMessage(PARTY_LIMIT_PROBE).getMessage();
            if (accepted == null || accepted.length() < 6 || accepted.length() > MAX_PARTY_COMMAND_UNITS
                    || !PARTY_LIMIT_PROBE.startsWith(accepted)) return 0;
            return accepted.length();
        } catch (RuntimeException unavailable) { return 0; }
          catch (LinkageError unavailable) { return 0; }
    }

    private static int partyChunkEnd(String text, int start, int commandLimit) {
        int end = Math.min(start + Math.min(commandLimit, MAX_PARTY_COMMAND_UNITS) - 4, text.length());
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return end;
    }

    public static List<String> partyCommands(String text) { return partyCommands(text, MAX_PARTY_COMMAND_UNITS); }

    public static List<String> partyCommands(String text, int commandLimit) {
        String clean = partyText(text);
        List<String> commands = new ArrayList<String>();
        if (commandLimit < 6) return commands;
        for (int p = 0; p < clean.length();) {
            int end = partyChunkEnd(clean, p, commandLimit);
            commands.add("/pc " + clean.substring(p, end)); p = end;
        }
        return commands;
    }

    public static synchronized void enqueueParty(String text, long now) {
        enqueueParty(OUTPUT_PLAYERS, text, now);
    }

    public static synchronized void enqueueParty(int category, String text, long now) {
        enqueueParty(category, text, now, null);
    }

    private static synchronized void enqueueParty(int category, String text, long now, String knownSubject) {
        if (!outputContextAllowed() || !outputEnabled(category)) return;
        String value = partyText(text);
        if (value.isEmpty()) return;
        String subject = knownSubject == null ? outputSubject(category, value) : knownSubject;
        if (ignoredAtAction(subject)) return;
        if (category == OUTPUT_TAGS && !tagOutputAllowed(subject)) return;
        String key = category + ":" + value;
        Long previous = sent.get(key);
        if (previous != null && now - previous < 30000) return;
        // Keep the event whole until delivery knows the current client's limit.
        // Rejected overflow is not a sent event and must not reserve its dedup key.
        if (outbox.size() >= 32) return;
        outbox.addLast(new String[]{Long.toString(now), value, Integer.toString(category), subject});
        sent.put(key, now); trimMap(sent, 256);
    }

    /** Extract only the subject before the opaque API reason; never match names in reasons. */
    static String tagSubject(String text) {
        return tagSubjectClean(partyText(text));
    }

    private static String tagSubjectClean(String text) {
        java.util.regex.Matcher match = TAG_SUBJECT.matcher(text);
        return match.find() ? match.group(1) : "";
    }

    private static String outputSubject(int category, String text) {
        if (category == OUTPUT_TAGS) return tagSubjectClean(text);
        if (category != OUTPUT_PLAYERS && category != OUTPUT_DENICK && category != OUTPUT_ANTICHEAT) return "";
        for (String label : new String[]{"Bot Denicker ", "Skin Denicker ", "Number Denicker "})
            if (text.startsWith(label)) { text = text.substring(label.length()); break; }
        for (String prefix : new String[]{"Fetching stats for ", "Unable to fetch stats for: "})
            if (text.startsWith(prefix)) {
                text = text.substring(prefix.length());
                if (text.endsWith("...")) text = text.substring(0, text.length() - 3);
                break;
            }
        java.util.regex.Matcher match = OUTPUT_SUBJECT.matcher(text);
        return match.find() ? match.group(1) : "";
    }

    private static boolean ignoredGeneratedEvent(String text, boolean json, int category) {
        if (text == null || text.length() > 16384) return false;
        String subject = generatedSubject(text, json, category);
        if (!isPlayerName(subject)) return false;
        if (ignoredAtAction(subject)) return true;
        // Native statistics already carry the row's actual nametag formatting.
        // It can turn gray before both the periodic snapshot and the Java team
        // update. Inspect only the first subject token, not gray ranks/reasons.
        if (json) {
            try {
                IChatComponent component = IChatComponent.Serializer.jsonToComponent(text);
                if (component == null) return false;
                text = component.getFormattedText();
            } catch (RuntimeException invalid) { return false; }
        }
        // Progress/failure templates may apply one gray color to their entire
        // sentence. Their text color is not player-team evidence; the current
        // roster check above still pauses them for a genuinely gray player.
        if (category == OUTPUT_PLAYERS) {
            String body = partyText(text);
            if (body.startsWith("Fetching stats for ") || body.startsWith("Unable to fetch stats for: ")) return false;
        }
        if (subjectNameColor(text, subject) != '7') return false;
        pauseObservedPlayer(subject, null, null, null);
        return true;
    }

    private static char subjectNameColor(String text, String name) {
        char active=0, tokenColor=0;
        boolean bracket=false, tokenBracket=false, matches=true, uniform=true, obfuscated=false;
        int length=0;
        for (int i=0;i<=text.length();i++) {
            char c=i<text.length()?text.charAt(i):'\0';
            if (c=='\u00a7' && i+1<text.length()) {
                char code=Character.toLowerCase(text.charAt(++i));
                if (code>='0'&&code<='9'||code>='a'&&code<='f') {active=code;obfuscated=false;}
                else if (code=='r') {active=0;obfuscated=false;}
                else if (code=='k') obfuscated=true;
                continue;
            }
            if (playerNameCharacter(c)) {
                char color=obfuscated?0:active;
                if (length==0) {tokenColor=color;tokenBracket=bracket;matches=true;uniform=true;}
                if (color!=tokenColor) uniform=false;
                if (length>=name.length() || Character.toLowerCase(c)!=Character.toLowerCase(name.charAt(length))) matches=false;
                length++;
            } else {
                if (length==name.length() && matches && !tokenBracket) return uniform?tokenColor:0;
                length=0;
                if (c=='[') bracket=true; else if (c==']') bracket=false;
            }
        }
        return 0;
    }

    private static String generatedSubject(String text, boolean json, int category) {
        if (json) {
            try {
                IChatComponent component = IChatComponent.Serializer.jsonToComponent(text);
                if (component == null) return "";
                text = component.getUnformattedText();
            } catch (RuntimeException invalid) { return ""; }
        }
        return outputSubject(category, partyText(text));
    }

    static boolean tagOutputAllowed(String name) {
        if (shouldIgnorePlayer(name)) return false;
        if (AdninGui4.chatOutputTagsSelf && AdninGui4.chatOutputTagsTeammates) return true;
        if (!isPlayerName(name)) return false;
        if (AdninMatchTeams.isSelf(name)) return AdninGui4.chatOutputTagsSelf;
        return !AdninMatchTeams.isTeammate(name) || AdninGui4.chatOutputTagsTeammates;
    }

    private static boolean outputItemAllowed(String[] item) {
        int category = Integer.parseInt(item[2]);
        return outputContextAllowed() && outputEnabled(category)
            && !ignoredAtAction(item.length > 3 ? item[3] : outputSubject(category, item[1]))
            && (category != OUTPUT_TAGS || tagOutputAllowed(item.length > 3 ? item[3] : tagSubject(item[1])));
    }

    private static synchronized boolean hasPartyOutput(long now) {
        if (!outputContextAllowed()) { clearPartyQueue(); return false; }
        while (!outbox.isEmpty()) {
            String[] next = outbox.peekFirst();
            if (outputItemAllowed(next) && now - Long.parseLong(next[0]) <= 15000) return true;
            outbox.removeFirst();
        }
        return false;
    }

    public static String pollPartyCommand(long now) { return pollPartyCommand(now, MAX_PARTY_COMMAND_UNITS); }

    public static synchronized String pollPartyCommand(long now, int commandLimit) {
        if (!hasPartyOutput(now) || commandLimit < 6) return null;
        String[] item = outbox.peekFirst();
        int end = partyChunkEnd(item[1], 0, commandLimit);
        String command = "/pc " + item[1].substring(0, end);
        if (end == item[1].length()) outbox.removeFirst();
        else item[1] = item[1].substring(end);
        return command;
    }

    public static synchronized void clearPartyQueue() { outbox.clear(); sent.clear(); }

    // Legacy callback ABI remains available. Native columns now own their
    // headers, width and ordering, so no trailing extension is reserved/drawn.
    public static int getUrchinColumnWidth(int available) { return 0; }
    public static void drawUrchinHeader(float x, float y, float right) { }
    public static void drawUrchinRow(String name, float x, float y, float right) { }
    public static void drawOverlayRow(String name, float x, float y, float right, String finalKills, String level) { }

    /** Draw only data, using the same ordered native columns as the headers. */
    public static void drawOrderedOverlayRow(String name, float y, float fkX, float fkWidth,
            float urchinX, float urchinWidth, String finalKills, String level) {
        if (!AdninGui4.tabOverlay || Float.isNaN(y) || Float.isInfinite(y)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) return;
        FontRenderer font = mc.fontRendererObj;
        if (validCell(fkX, fkWidth)) {
            double ratio = AdninMetrics.ratioFromCells(finalKills, level);
            String label = fit(font, AdninMetrics.format(ratio), Math.max(0, (int) fkWidth - 4));
            font.drawStringWithShadow(label, fkX + (fkWidth - font.getStringWidth(label)) / 2.0f,
                y, 0xff000000 | AdninMetrics.color(ratio));
        }
        if (validCell(urchinX, urchinWidth)) drawUrchinCell(name, y, urchinX, urchinWidth);
    }

    private static boolean validCell(float x, float width) {
        return !Float.isNaN(x) && !Float.isInfinite(x) && !Float.isNaN(width)
            && !Float.isInfinite(width) && width >= 8 && width <= 65536;
    }

    private static void drawUrchinCell(String name, float y, float x, float width) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) return;
        String player = overlayPlayer(name);
        if (player == null) return;
        List<String> found = tags.get(player);
        if (found == null || found.isEmpty()) return;
        FontRenderer font = mc.fontRendererObj;
        int colon = found.get(0).indexOf(':');
        String firstType = colon < 0 ? found.get(0) : found.get(0).substring(0, colon);
        int color = 0xff000000 | AdninMetrics.tagColor(firstType);
        String cachedLabel = tagLabels.get(player);
        String label = fit(font, cachedLabel == null ? "" : cachedLabel, Math.max(0, (int) width - 13));
        int contentWidth = 5 + (label.isEmpty() ? 0 : 4 + font.getStringWidth(label));
        // A code-drawn icon remains legible with fonts lacking special glyphs.
        int ix = Math.round(x + (width - contentWidth) / 2.0f), iy = Math.round(y) + 2;
        Gui.drawRect(ix + 1, iy, ix + 4, iy + 5, color);
        Gui.drawRect(ix, iy + 1, ix + 5, iy + 4, color);
        Gui.drawRect(ix + 2, iy + 1, ix + 3, iy + 2, 0xff202020);
        if (!label.isEmpty()) font.drawStringWithShadow(label, ix + 9, y, color);
        rowCount++;
    }

    /** Match complete visible roster tokens without compiling a regex per rendered row. */
    private static String overlayPlayer(String name) {
        if (isPlayerName(name)) {
            String key = lower(name);
            return present.contains(key) ? key : null;
        }
        String plain = cleanText(name);
        String player = null;
        for (int start = 0; start < plain.length();) {
            if (!playerNameCharacter(plain.charAt(start))) { start++; continue; }
            int end = start + 1;
            while (end < plain.length() && playerNameCharacter(plain.charAt(end))) end++;
            if (end - start <= 16) {
                String token = lower(plain.substring(start, end));
                if (present.contains(token)) player = token;
            }
            start = end;
        }
        return player;
    }

    private static String fit(FontRenderer font, String text, int pixels) {
        if (font.getStringWidth(text) <= pixels) return text;
        if (font.getStringWidth("...") > pixels) return "";
        StringBuilder prefix = new StringBuilder();
        boolean visible = false;
        for (int i = 0; i < text.length();) {
            if (text.charAt(i) == '\u00a7') {
                if (i + 1 >= text.length()) break;
                prefix.append(text, i, i + 2); i += 2;
                continue;
            }
            int end = i + Character.charCount(text.codePointAt(i));
            String next = text.substring(i, end);
            if (font.getStringWidth(prefix.toString() + next + "...") > pixels) break;
            prefix.append(next); visible = true; i = end;
        }
        return visible ? prefix.toString() + "\u00a77...\u00a7r" : "";
    }

    private static String tagTypes(List<String> values) {
        List<String> types = new ArrayList<String>();
        for (String value : values) {
            if (value == null) continue;
            int colon = value.indexOf(':');
            String type = colon < 0 ? value : value.substring(0, colon);
            type = AdninMetrics.coloredTagAbbreviation(type);
            if (!type.isEmpty() && !types.contains(type)) types.add(type);
        }
        return join(types, "\u00a77, \u00a7r");
    }

    private static String bounded(String value, int length) { return value == null ? "" : value.substring(0, Math.min(value.length(), length)); }
    private static String lower(String value) { return value.toLowerCase(Locale.ROOT); }
    private static String join(List<String> values, String separator) { StringBuilder b = new StringBuilder(); for (String v : values) { if (b.length() > 0) b.append(separator); b.append(v); } return b.toString(); }
    private static void trimBotCache() {
        while (botCache.size() > MAX_CACHE) {
            Iterator<String> it = botCache.keySet().iterator();
            if (!it.hasNext()) break;
            String key = it.next();
            botCache.remove(key);
            botProfiles.remove(key);
        }
    }
    private static <T> void trimMap(Map<String, T> values, int max) {
        while (values.size() > max) {
            Iterator<String> it = values.keySet().iterator();
            if (!it.hasNext()) break;
            values.remove(it.next());
        }
    }
}
