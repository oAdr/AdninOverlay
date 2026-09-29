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
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.network.play.client.C01PacketChatMessage;

/** Client-thread integration. The daemon performs HTTP and config writes only. */
public final class AdninFeatures implements Runnable {
    public static final int OUTPUT_PLAYERS = 0, OUTPUT_TAGS = 1, OUTPUT_ANTICHEAT = 2;
    private static final int MAX_CACHE = 512, MAX_PENDING = 128;
    private static final long CACHE_MS = 300000L, SEND_MS = 1500L;
    private static final int MAX_PARTY_COMMAND_UNITS = 256;
    private static final String PARTY_LIMIT_PROBE = partyLimitProbe();
    private static final BlockingQueue<String[]> requests = new ArrayBlockingQueue<String[]>(MAX_PENDING);
    private static final BlockingQueue<String[]> results = new ArrayBlockingQueue<String[]>(MAX_PENDING);
    private static final BlockingQueue<String[]> nickHints = new ArrayBlockingQueue<String[]>(MAX_PENDING);
    private static final ConcurrentMap<String, String> botProfiles = new ConcurrentHashMap<String, String>();
    private static final ConcurrentMap<String, Long> candidateTimes = new ConcurrentHashMap<String, Long>();
    private static final Map<String, Long> requested = new LinkedHashMap<String, Long>();
    private static final Map<String, List<String>> tags = new LinkedHashMap<String, List<String>>();
    private static final AdninUrchinCache urchinCache = new AdninUrchinCache(MAX_CACHE);
    private static final AtomicLong matchStarts = new AtomicLong();
    private static final Deque<String[]> matchRequests = new ArrayDeque<String[]>();
    private static volatile long currentMatch;
    private static final Set<String> announced = new HashSet<String>();
    private static final Set<String> present = new HashSet<String>();
    private static final Map<String, String> displayNames = new LinkedHashMap<String, String>();
    private static final Map<String, String> nametagNames = new LinkedHashMap<String, String>();
    private static final Deque<String[]> outbox = new ArrayDeque<String[]>();
    private static final Map<String, Long> sent = new LinkedHashMap<String, Long>();
    private static boolean initialized, wasOutput, warnedUrl;
    private static volatile boolean stopped;
    private static volatile Thread worker;
    private static volatile int generation;
    private static volatile int botGeneration;
    private static final AtomicReference<Properties> pendingSave = new AtomicReference<Properties>();
    private static volatile Path settingsPath;
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

    public static synchronized void ensureInitialized() {
        if (initialized || stopped) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.mcDataDir == null) return;
            settingsPath = new File(mc.mcDataDir, "adnin-features.properties").toPath();
            if (Files.isRegularFile(settingsPath) && Files.size(settingsPath) <= 32768) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(settingsPath)) { p.load(in); }
                AdninGui4.api_urchin = bounded(p.getProperty("urchin.apiKey", ""), 512);
                AdninGui4.botDenickerUrl = bounded(p.getProperty("botDenicker.url", ""), 2048);
                AdninGui4.botDenicker = Boolean.parseBoolean(p.getProperty("botDenicker.enabled", "false"));
                loadOutputSettings(p);
                AdninGui4.loadUiSettings(p);
                AdninLanguage.load(p);
                AdninGui4.clientSideSounds = Boolean.parseBoolean(p.getProperty("sounds.clientSide", "false"));
                AdninAnticheat.loadSettings(p);
                if (AdninGui4.chatOutput || AdninGui4.chatOutputTags) AdninGui4.chatOverlay = true;
            }
        } catch (Exception ignored) { /* Existing defaults remain usable. Never log secrets. */ }
        if (settingsPath == null) return;
        AdninLanguage.loadSharedPreference();
        initialized = true;
        worker = new Thread(new AdninFeatures(), "Adnin API worker");
        worker.setDaemon(true);
        worker.start();
    }

    public static synchronized void requestSave() {
        if (stopped) return;
        ensureInitialized();
        Properties p = new Properties();
        p.setProperty("urchin.apiKey", bounded(AdninGui4.api_urchin, 512));
        p.setProperty("botDenicker.url", bounded(AdninGui4.botDenickerUrl, 2048));
        p.setProperty("botDenicker.enabled", Boolean.toString(AdninGui4.botDenicker));
        saveOutputSettings(p);
        AdninGui4.saveUiSettings(p);
        AdninLanguage.save(p);
        p.setProperty("sounds.clientSide", Boolean.toString(AdninGui4.clientSideSounds));
        AdninAnticheat.saveSettings(p);
        pendingSave.set(p);
        saveAt = System.currentTimeMillis() + 600;
    }

    @Override public void run() {
        long nextRequest = 0;
        try { while (!stopped) {
            try {
                try { saveIfDue(); writeDiagnostics(); } catch (IOException ignored) { /* Retry on a later poll. */ }
                String[] job = requests.poll(250, TimeUnit.MILLISECONDS);
                if (job == null || !currentJob(job)) continue;
                long wait = nextRequest - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                if (!currentJob(job)) continue;
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
                publishResult(job, result.toArray(new String[0]));
                apiCompleted++;
            } catch (InterruptedException shutdown) { Thread.currentThread().interrupt(); return; }
              catch (Exception failure) { /* Keep worker alive without logging request credentials. */ }
        } } finally {
            // Finish only an already requested settings save. Never wait for
            // this daemon on the render/native unload thread.
            try { saveAt = 0; saveIfDue(); } catch (Exception ignored) { }
            pendingSave.set(null);
        }
    }

    private static boolean currentJob(String[] job) {
        return !stopped && job[0].equals(Integer.toString("bot".equals(job[1]) ? botGeneration : generation))
            && (!"urchin".equals(job[1]) || job[5].equals(Long.toString(currentMatch)));
    }

    // Serialize the final epoch check and insertion with world changes/unload.
    // Network work stays outside this monitor.
    private static synchronized void publishResult(String[] job, String[] result) {
        if (currentJob(job)) results.offer(result);
    }

    /** Native Ingame transition only. No Minecraft calls, network, or file IO. */
    public static void matchStarted() { if (!stopped) matchStarts.incrementAndGet(); }

    /** Stop optional work and release world/response references before native unload. */
    public static synchronized void shutdown() {
        if (stopped) return;
        stopped = true;
        generation++; botGeneration++;
        Thread active = worker; worker = null;
        if (active != null) active.interrupt();
        requests.clear(); results.clear(); nickHints.clear(); matchRequests.clear();
        botProfiles.clear(); candidateTimes.clear(); requested.clear();
        tags.clear(); urchinCache.clear(); announced.clear();
        present.clear(); displayNames.clear(); nametagNames.clear();
        clearPartyQueue(); world = null; keySnapshot = ""; urlSnapshot = "";
    }

    private static void saveIfDue() throws IOException {
        Properties p = pendingSave.get();
        Path path = settingsPath;
        if (p == null || path == null || System.currentTimeMillis() < saveAt) return;
        Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) { p.store(out, "Adnin feature settings"); }
        try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        AdninLanguage.saveSharedPreference(p.getProperty("ui.language", "en"));
        pendingSave.compareAndSet(p, null);
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
        long now = System.currentTimeMillis();
        String key = bounded(AdninGui4.api_urchin, 512).trim();
        String url = bounded(AdninGui4.botDenickerUrl, 2048).trim();
        boolean changedWorld = world != mc.theWorld;
        boolean changedUrchin = changedWorld || !key.equals(keySnapshot);
        boolean changedBot = changedWorld || !url.equals(urlSnapshot) || botSnapshot != AdninGui4.botDenicker;
        if (changedUrchin) {
            generation++;
            if (!key.equals(keySnapshot)) urchinCache.clear();
            else urchinCache.clearMatch();
            keySnapshot = key;
            tags.clear(); matchRequests.clear(); clearAnnouncements("urchin:");
        }
        if (changedBot) {
            botGeneration++;
            urlSnapshot = url; botSnapshot = AdninGui4.botDenicker;
            nickHints.clear(); requested.clear(); clearAnnouncements("bot:");
            botProfiles.clear(); candidateTimes.clear();
            warnedUrl = false;
        }
        if (changedWorld) {
            world = mc.theWorld;
            present.clear(); displayNames.clear(); nametagNames.clear(); clearPartyQueue(); nextScan = 0;
        }
        // Old jobs retain response strings and credentials even though their
        // generation will be rejected. Retire them immediately on transitions.
        if (changedUrchin || changedBot) discardStaleJobs();
        if (!anyOutputEnabled() || !wasOutput) clearPartyQueue();
        wasOutput = anyOutputEnabled();
        if (mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) return;
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
                if (!result[5].equals(Long.toString(currentMatch))) continue;
                List<String> playerTags = new ArrayList<String>();
                for (int j = 6; j < result.length; j++) playerTags.add(cleanText(result[j]));
                if (!urchinCache.complete(currentMatch, result[4], playerTags, result[3].isEmpty(), now)) continue;
                applyUrchinContent();
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

    private static void discardStaleJobs() {
        requests.removeIf(job -> !currentJob(job));
        results.removeIf(job -> !currentJob(job));
    }

    /** Successful verified identities alone may enter the party-output queue. */
    private static void applyBotResult(String[] result, long now, Minecraft mc) {
        if (result == null || result.length < 6 || !"bot".equals(result[1])
                || !Integer.toString(botGeneration).equals(result[0])
                || !isPlayerName(result[2]) || !present.contains(lower(result[2]))) return;
        String id = lower(result[2]);
        String match = result.length > 6 && isPlayerName(result[6]) ? result[6] : "";
        boolean verified = !match.isEmpty() && "".equals(result[3]) && result.length > 7
            && result[7] != null && result[7].matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        if (!"".equals(result[3]) || !match.isEmpty() && !verified)
            requested.put("bot:" + id, now - CACHE_MS + 30000);
        String message = formattedBotMessage(result[2], nametagNames.get(id), match,
            nametagNames.get(lower(match)), verified, result[3]);
        if (verified) {
            botProfiles.put(id, match + "|" + lower(result[7]));
            trimMap(botProfiles, MAX_CACHE);
        }
        // An unavailable account lookup must not suppress a later verified result.
        String outcome = verified ? "verified" : match.isEmpty() ? "none" : "unverified";
        if (!announced.add("bot:" + id + ":" + match + ":" + outcome)) return;
        local(message, mc);
        if (verified) enqueueParty(OUTPUT_PLAYERS, message, now);
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
        // Freeze this one roster batch. Later Tab arrivals, Bot resolutions and
        // expired cache entries never create a mid-match Urchin request.
        tags.clear(); matchRequests.clear();
        clearAnnouncements("urchin:");
        Map<String, String> roster = new LinkedHashMap<String, String>();
        int count = 0;
        if (!keySnapshot.isEmpty()) for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (++count > 256) break;
            if (info == null || info.getGameProfile() == null) continue;
            String name = info.getGameProfile().getName();
            UUID uuid = info.getGameProfile().getId();
            if (!isRealTabProfile(name, uuid)) continue;
            String resolved = botProfiles.get(lower(name));
            String lookup = resolved != null ? resolved.substring(resolved.indexOf('|') + 1)
                : (uuid.version() == 4 ? uuid.toString() : name);
            roster.put(name, lookup);
        }
        Map<String, String> batch = urchinCache.beginMatch(currentMatch, roster, now);
        for (Map.Entry<String, String> player : batch.entrySet()) {
            matchRequests.addLast(new String[]{Integer.toString(generation), "urchin", player.getKey(),
                player.getValue(), keySnapshot, Long.toString(currentMatch)});
        }
        applyUrchinContent();
    }

    private static void applyUrchinContent() {
        tags.clear(); tags.putAll(urchinCache.visibleTags());
        for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
            if (entry.getValue().isEmpty() || !present.contains(entry.getKey())) continue;
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

    private static boolean isPlayerName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
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
        if (!AdninGui4.botDenicker || name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return;
        if (AdninReplay.isReplay()) {
            if (!AdninReplay.isNick(name)) return;
            name = AdninReplay.recordedName(name);
            if (name.isEmpty()) return;
        }
        if (!present.contains(lower(name))) return;
        if (urlSnapshot.isEmpty()) {
            if (!warnedUrl) { local("\u00a76" + AdninLanguage.text("Bot Denicker") + "\u00a7r: \u00a7c" + AdninLanguage.text("Enter an API URL containing <> first.") + "\u00a7r", Minecraft.getMinecraft()); warnedUrl = true; }
            return;
        }
        schedule("bot", name, name, urlSnapshot, now);
    }

    private static void schedule(String kind, String name, String player, String credential, long now) {
        String id = kind + ":" + lower(name);
        Long last = requested.get(id);
        if (last != null && now - last < CACHE_MS) return;
        if (!"bot".equals(kind)) return;
        if (requests.offer(new String[]{Integer.toString(botGeneration), kind, name, player, credential, ""})) {
            requested.put(id, now); trimMap(requested, MAX_CACHE);
        }
    }

    private static void generatedLocal(String text) {
        local(text, Minecraft.getMinecraft());
        enqueueParty(OUTPUT_TAGS, text, System.currentTimeMillis());
    }

    private static void local(String text, Minecraft mc) {
        if (text == null || text.isEmpty()) return;
        String message = text.startsWith("[Urchin]") ? "\u00a7d[Urchin]\u00a7r" + text.substring(8) : PREFIX + text;
        if (mc != null && mc.ingameGUI != null) mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(message));
    }

    /** Called after native Tab bot filtering. Never performs network or file IO. */
    public static synchronized String getBotProfile(String name) {
        if (stopped || !AdninGui4.botDenicker || name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return "";
        String recorded = name;
        if (AdninReplay.isReplay()) {
            if (!AdninReplay.isNick(name)) return "";
            recorded = AdninReplay.recordedName(name);
            if (recorded.isEmpty()) return "";
        }
        String id = lower(recorded);
        String profile = botProfiles.get(id);
        if (profile != null) return profile;
        long now = System.currentTimeMillis();
        Long last = candidateTimes.get(id);
        if (last == null || now - last >= 1000) {
            candidateTimes.put(id, now);
            trimMap(candidateTimes, MAX_CACHE);
            nickHints.offer(new String[]{Integer.toString(botGeneration), name});
        }
        return "";
    }

    /** Only allowlisted generated-message native callsites invoke this. */
    public static void nativeGeneratedEvent(String text, boolean json) {
        nativeGeneratedEvent(text, json, OUTPUT_PLAYERS);
    }

    public static int nativeRenderGeneratedEvent(String text, boolean json, int category) {
        if (stopped) return 0;
        nativeGeneratedEvent(text, json, category);
        return AdninMessages.renderGenerated(text, json, category);
    }

    public static void nativeGeneratedEvent(String text, boolean json, int category) {
        if (stopped) return;
        nativeEvents++;
        if (!outputEnabled(category)) return;
        if (text == null || text.length() > 16384) return;
        String localized = AdninMessages.translateGenerated(text, json, category);
        if (localized != null) text = localized;
        if (json) {
            try {
                IChatComponent component = IChatComponent.Serializer.jsonToComponent(text);
                if (component == null) return;
                text = component.getUnformattedText();
            } catch (RuntimeException invalid) { return; }
        }
        String clean = cleanText(text);
        enqueueParty(category, clean, System.currentTimeMillis());
    }

    public static void anticheatGeneratedEvent(String text) {
        enqueueParty(OUTPUT_ANTICHEAT, text, System.currentTimeMillis());
    }

    public static boolean outputEnabled(int category) {
        return category == OUTPUT_PLAYERS ? AdninGui4.chatOutput
            : category == OUTPUT_TAGS ? AdninGui4.chatOutputTags
            : category == OUTPUT_ANTICHEAT && AdninGui4.chatOutputAnticheat;
    }

    public static boolean anyOutputEnabled() {
        return AdninGui4.chatOutput || AdninGui4.chatOutputTags || AdninGui4.chatOutputAnticheat;
    }

    /** Closing a category immediately discards its queued fragments. */
    public static synchronized void outputSettingsChanged() {
        for (Iterator<String[]> it = outbox.iterator(); it.hasNext();) {
            String[] item = it.next();
            if (!outputEnabled(Integer.parseInt(item[2]))) it.remove();
        }
    }

    static void loadOutputSettings(Properties p) {
        String legacy = p.getProperty("chat.output", "false");
        AdninGui4.chatOutput = Boolean.parseBoolean(p.getProperty("chat.output.players", legacy));
        AdninGui4.chatOutputTags = Boolean.parseBoolean(p.getProperty("chat.output.tags", legacy));
        AdninGui4.chatOutputAnticheat = Boolean.parseBoolean(p.getProperty("chat.output.anticheat", "false"));
    }

    static void saveOutputSettings(Properties p) {
        p.setProperty("chat.output", Boolean.toString(AdninGui4.chatOutput));
        p.setProperty("chat.output.players", Boolean.toString(AdninGui4.chatOutput));
        p.setProperty("chat.output.tags", Boolean.toString(AdninGui4.chatOutputTags));
        p.setProperty("chat.output.anticheat", Boolean.toString(AdninGui4.chatOutputAnticheat));
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
        return clean.toString().trim().replaceAll("\\s+", " ");
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
        if (stopped || !outputEnabled(category)) return;
        String value = partyText(text);
        if (value.isEmpty()) return;
        String key = category + ":" + value;
        Long previous = sent.get(key);
        if (previous != null && now - previous < 30000) return;
        sent.put(key, now); trimMap(sent, 256);
        // Keep the event whole until delivery knows the current client's limit.
        if (outbox.size() < 32) outbox.addLast(new String[]{Long.toString(now), value, Integer.toString(category)});
    }

    private static synchronized boolean hasPartyOutput(long now) {
        while (!outbox.isEmpty()) {
            String[] next = outbox.peekFirst();
            if (outputEnabled(Integer.parseInt(next[2])) && now - Long.parseLong(next[0]) <= 15000) return true;
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
        String plain = cleanText(name);
        String player = null;
        for (String token : plain.split("[^A-Za-z0-9_]+")) if (present.contains(lower(token))) player = lower(token);
        if (player == null) return;
        List<String> found = tags.get(player);
        if (found == null || found.isEmpty()) return;
        FontRenderer font = mc.fontRendererObj;
        int colon = found.get(0).indexOf(':');
        String firstType = colon < 0 ? found.get(0) : found.get(0).substring(0, colon);
        int color = 0xff000000 | AdninMetrics.tagColor(firstType);
        String label = fit(font, tagTypes(found), Math.max(0, (int) width - 13));
        int contentWidth = 5 + (label.isEmpty() ? 0 : 4 + font.getStringWidth(label));
        // A code-drawn icon remains legible with fonts lacking special glyphs.
        int ix = Math.round(x + (width - contentWidth) / 2.0f), iy = Math.round(y) + 2;
        Gui.drawRect(ix + 1, iy, ix + 4, iy + 5, color);
        Gui.drawRect(ix, iy + 1, ix + 5, iy + 4, color);
        Gui.drawRect(ix + 2, iy + 1, ix + 3, iy + 2, 0xff202020);
        if (!label.isEmpty()) font.drawStringWithShadow(label, ix + 9, y, color);
        rowCount++;
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
    private static <T> void trimMap(Map<String, T> values, int max) {
        while (values.size() > max) {
            Iterator<String> it = values.keySet().iterator();
            if (!it.hasNext()) break;
            values.remove(it.next());
        }
    }
}
