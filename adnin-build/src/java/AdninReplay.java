import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Locale;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

/** Local sidebar observation only; it never changes the native game state. */
public final class AdninReplay {
    private static volatile boolean replay;
    private static volatile String status = "not-observed";
    private static volatile long observations, failures;
    private static volatile int tabProfiles, formattedTabProfiles, validTabProfiles;
    private static volatile RosterCounts rosterCounts = new RosterCounts();
    private static volatile Map<EntityPlayer, String> actors = Collections.emptyMap();
    private static Object world;
    private static long nextRoster;
    private static final AdninReplayProfiles profiles = new AdninReplayProfiles(
        new AdninReplayProfiles.Resolver() {
            @Override public String resolve(String name) throws java.io.IOException {
                return AdninApi.fetchReplayMojangProfile(name);
            }
        }, true);

    private AdninReplay() { }

    public static boolean isReplay() { return replay; }

    public static void clear() {
        replay = false; status = "inactive"; world = null; nextRoster = 0;
        actors = Collections.emptyMap(); profiles.publish(Collections.<String, String>emptyMap());
        tabProfiles = 0; formattedTabProfiles = 0; validTabProfiles = 0;
        rosterCounts = new RosterCounts();
    }

    public static void shutdown() {
        clear();
        profiles.shutdown();
    }

    /** Identity map is rebuilt from the current world and Tab; UUID equality is irrelevant. */
    public static String actorName(EntityPlayer player) {
        String name = replay ? actors.get(player) : null;
        return name == null ? "" : name;
    }

    /** Called on native worker threads: immutable roster/cache access only. */
    public static String profile(String rawTabName) {
        return replay ? profiles.lookup(accountKey(rawTabName), AdninReplayProfiles.now()) : "";
    }

    /** Normalize a bounded raw Tab alias and read its current mapping without API work. */
    public static String recordedName(String rawTabName) {
        return replay ? profiles.accountName(accountKey(rawTabName)) : "";
    }

    /** Original account classification only; a later Bot resolution cannot replace it. */
    public static boolean isNick(String rawTabName) {
        return AdninReplayProfiles.NICK.equals(profile(accountKey(rawTabName)));
    }

    public static void diagnostics(Properties p) {
        p.setProperty("replayStatus", status);
        p.setProperty("replayObserved", Boolean.toString(replay));
        p.setProperty("replayObservations", Long.toString(observations));
        p.setProperty("replayFailures", Long.toString(failures));
        p.setProperty("replayActors", Integer.toString(actors.size()));
        p.setProperty("replayTabProfiles", Integer.toString(tabProfiles));
        p.setProperty("replayFormattedTabProfiles", Integer.toString(formattedTabProfiles));
        p.setProperty("replayValidTabProfiles", Integer.toString(validTabProfiles));
        RosterCounts counts = rosterCounts;
        p.setProperty("replayRosterWorldActors", Integer.toString(counts.worldActors));
        p.setProperty("replayRejectedSelf", Integer.toString(counts.self));
        p.setProperty("replayRejectedDead", Integer.toString(counts.dead));
        p.setProperty("replayRejectedViewer", Integer.toString(counts.viewer));
        p.setProperty("replayRejectedType", Integer.toString(counts.type));
        p.setProperty("replayRejectedMissingProfile", Integer.toString(counts.missingProfile));
        p.setProperty("replayRejectedUnmatched", Integer.toString(counts.unmatched));
        p.setProperty("replayRejectedConflictingAlias", Integer.toString(counts.conflictingAlias));
        p.setProperty("replayRejectedDuplicate", Integer.toString(counts.duplicate));
        p.setProperty("replayAmbiguousTabAliases", Integer.toString(counts.ambiguousTabAliases));
        p.setProperty("replayProfileRequests", Long.toString(profiles.requests()));
        p.setProperty("replayProfileCompleted", Long.toString(profiles.completions()));
        p.setProperty("replayProfileFailed", Long.toString(profiles.failures()));
        p.setProperty("replayProfileAbsent", Long.toString(profiles.absences()));
        p.setProperty("replayProfileLastError", profiles.lastError());
    }

    public static void tick(Minecraft mc) {
        boolean nextReplay = false;
        String nextStatus = "no-world";
        observations++;
        try {
            if (mc != null && mc.theWorld != null && mc.thePlayer != null) {
                Scoreboard board = mc.theWorld.getScoreboard();
                ScoreObjective objective = sidebar(board, mc.thePlayer.getName());
                nextReplay = detect(board, objective);
                nextStatus = nextReplay ? "active" : objective == null ? "no-sidebar" : "ordinary";
                if (nextReplay) {
                    long now = AdninReplayProfiles.now();
                    if (world != mc.theWorld || now >= nextRoster) {
                        refreshActors(mc);
                        world = mc.theWorld; nextRoster = now + 250L;
                    }
                }
            }
        } catch (Exception failure) { failures++; nextStatus = "observation-error"; }
          catch (LinkageError failure) { failures++; nextStatus = "linkage-error"; }
        if (!nextReplay || !"active".equals(nextStatus)) {
            if (world != null || !actors.isEmpty()) {
                actors = Collections.emptyMap(); profiles.publish(Collections.<String, String>emptyMap());
            }
            world = null; nextRoster = 0;
            tabProfiles = 0; formattedTabProfiles = 0; validTabProfiles = 0;
            rosterCounts = new RosterCounts();
        }
        // Publish once; native readers never see an intermediate false value.
        replay = nextReplay && "active".equals(nextStatus);
        status = nextStatus;
    }

    private static void refreshActors(Minecraft mc) {
        Map<String, String> tabs = new HashMap<String, String>();
        Map<String, String> candidates = new HashMap<String, String>();
        Set<String> ambiguousRaw = new HashSet<String>();
        Set<String> ambiguous = new HashSet<String>();
        RosterCounts counts = new RosterCounts();
        int observed = 0, formatted = 0, valid = 0;
        if (mc.getNetHandler() != null) {
            int count = 0;
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (++count > 256) break;
                if (info == null || info.getGameProfile() == null) continue;
                String original = info.getGameProfile().getName();
                String raw = accountKey(original);
                observed++;
                if (original != null && !original.equals(raw)) formatted++;
                // Match the visible Tab name, including a scoreboard-team suffix
                // when the server did not send a separate display component.
                String display = info.getDisplayName() == null
                        ? ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), original)
                        : info.getDisplayName().getFormattedText();
                if (viewer(display) || !AdninReplayProfiles.validName(raw)) continue;
                valid++;
                String name = displayedAccount(raw, display);
                if (name.isEmpty()) continue;
                String previous = tabs.put(AdninReplayProfiles.lower(raw), name);
                if (previous != null && !previous.equalsIgnoreCase(name)) {
                    ambiguousRaw.add(AdninReplayProfiles.lower(raw));
                    ambiguous.add(AdninReplayProfiles.lower(raw));
                    ambiguous.add(AdninReplayProfiles.lower(previous));
                    ambiguous.add(AdninReplayProfiles.lower(name));
                }
            }
        }
        for (Map.Entry<String, String> entry : tabs.entrySet()) {
            if (!ambiguousRaw.contains(entry.getKey())) {
                addAlias(candidates, ambiguous, entry.getKey(), entry.getValue());
                addAlias(candidates, ambiguous, entry.getValue(), entry.getValue());
            }
        }
        Map<EntityPlayer, String> next = new IdentityHashMap<EntityPlayer, String>();
        Map<String, EntityPlayer> unique = new HashMap<String, EntityPlayer>();
        Set<String> duplicateActors = new HashSet<String>();
        int count = 0, matched = 0;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (++count > 256) break;
            counts.worldActors++;
            if (player == mc.thePlayer) { counts.self++; continue; }
            if (!(player instanceof EntityOtherPlayerMP)) { counts.type++; continue; }
            if (player.isDead || !player.isEntityAlive()) { counts.dead++; continue; }
            if (player.getGameProfile() == null) { counts.missingProfile++; continue; }
            String display = player.getDisplayName() == null ? null : player.getDisplayName().getFormattedText();
            if (viewer(display)) { counts.viewer++; continue; }
            String raw = accountKey(player.getGameProfile().getName());
            String recorded = null;
            boolean conflict = false;
            // Raw profile aliases and displayed accounts both identify a current Tab
            // entry. Normalize every fallback and reject contradictory identities.
            // Account API results deliberately never participate in actor admission.
            for (String alias : new String[]{displayedAccount(raw, display), raw, accountKey(player.getName())}) {
                String aliasKey = AdninReplayProfiles.lower(alias);
                if (ambiguous.contains(aliasKey)) { conflict = true; break; }
                String candidate = candidates.get(aliasKey);
                if (candidate == null) continue;
                if (recorded != null && !recorded.equalsIgnoreCase(candidate)) { conflict = true; break; }
                recorded = candidate;
            }
            if (conflict) { counts.conflictingAlias++; continue; }
            if (recorded == null) { counts.unmatched++; continue; }
            String key = AdninReplayProfiles.lower(recorded);
            if (unique.put(key, player) != null) duplicateActors.add(key);
            next.put(player, recorded);
            matched++;
        }
        Map<String, String> eligible = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : tabs.entrySet()) {
            // Tab identities remain queryable outside entity tracking distance.
            // Detection still uses the separate current-world actor map below.
            if (!ambiguous.contains(entry.getKey())
                    && !ambiguous.contains(AdninReplayProfiles.lower(entry.getValue())))
                eligible.put(entry.getKey(), entry.getValue());
        }
        for (java.util.Iterator<Map.Entry<EntityPlayer, String>> it = next.entrySet().iterator(); it.hasNext();) {
            Map.Entry<EntityPlayer, String> entry = it.next();
            if (duplicateActors.contains(AdninReplayProfiles.lower(entry.getValue()))) it.remove();
        }
        actors = Collections.unmodifiableMap(next);
        profiles.publish(eligible);
        tabProfiles = observed; formattedTabProfiles = formatted; validTabProfiles = valid;
        counts.duplicate = matched - next.size();
        counts.ambiguousTabAliases = ambiguous.size();
        rosterCounts = counts;
    }

    private static void addAlias(Map<String, String> candidates, Set<String> ambiguous, String alias, String account) {
        String key = AdninReplayProfiles.lower(alias);
        String previous = candidates.put(key, account);
        if (previous != null && !previous.equalsIgnoreCase(account)) ambiguous.add(key);
    }

    /** Published once per bounded roster refresh; contains no account identifiers. */
    private static final class RosterCounts {
        int worldActors, self, dead, viewer, type, missingProfile, unmatched, conflictingAlias, duplicate, ambiguousTabAliases;
    }

    static boolean viewer(String display) {
        if (display == null) return false;
        String plain = display.replaceAll("(?i)\\u00a7[0-9a-fk-or]", "").toLowerCase(Locale.ROOT);
        return plain.contains("[viewer]") || plain.contains("[spectator]");
    }

    static String displayedAccount(String raw, String display) {
        if (viewer(display)) return "";
        raw = accountKey(raw);
        if (display != null) {
            String[] tokens = display.replaceAll("\\[[^\\]]*\\]", " ").trim().split("\\s+");
            String selected = "";
            for (int i = 0; i < tokens.length; i++) {
                String token = accountKey(tokens[i]);
                // Formatting within an account never creates a word boundary.
                // Only an observed team marker is removed; missing letters are
                // never guessed from a profile prefix or a remote lookup.
                if (token.length() > 1 && teamLetter(token.substring(0, 1))
                        && !prefix(token, raw)) {
                    String afterTeam = token.substring(1);
                    String runs = tokens[i].replaceAll("(?i)\\u00a7[0-9a-fk-or]", " ").trim();
                    if (prefix(afterTeam, raw) || runs.matches("(?i)[RBGYAPWS]\\s+.*"))
                        token = afterTeam;
                }
                if (selected.isEmpty() && i + 1 < tokens.length && teamLetter(token)
                        && !token.equalsIgnoreCase(raw) && !accountKey(tokens[i + 1]).matches("[0-9]+")) continue;
                if (!AdninReplayProfiles.validName(token)) continue;
                // The usual trailing Tab health/score is a separate numeric
                // token. A digit inside the same visible account stays intact.
                if (!selected.isEmpty() && token.matches("[0-9]+") && !token.equalsIgnoreCase(raw)) continue;
                if (!selected.isEmpty() && !selected.equalsIgnoreCase(token)) return "";
                selected = token;
            }
            if (!selected.isEmpty()) return selected;
        }
        return AdninReplayProfiles.validName(raw) ? raw : "";
    }

    private static boolean teamLetter(String text) {
        return text.length() == 1 && "RBGYAPWS".indexOf(Character.toUpperCase(text.charAt(0))) >= 0;
    }

    private static boolean prefix(String text, String raw) {
        return AdninReplayProfiles.validName(raw) && text.length() >= raw.length()
                && text.regionMatches(true, 0, raw, 0, raw.length());
    }

    /** Native row names already remove formatting. Keep the same key without mutating profiles. */
    static String accountKey(String raw) {
        if (raw == null || raw.length() > 96) return "";
        return raw.replaceAll("(?i)\\u00a7[0-9a-fk-or]", "");
    }

    /** Match the sidebar objective selected by the 1.8.9 in-game renderer. */
    static ScoreObjective sidebar(Scoreboard board, String playerName) {
        if (board == null) return null;
        ScorePlayerTeam team = playerName == null ? null : board.getPlayersTeam(playerName);
        if (team != null && team.getChatFormat() != null) {
            int color = team.getChatFormat().getColorIndex();
            if (color >= 0 && color < 16) {
                ScoreObjective colored = board.getObjectiveInDisplaySlot(3 + color);
                if (colored != null) return colored;
            }
        }
        return board.getObjectiveInDisplaySlot(1);
    }

    static boolean detect(Scoreboard board, ScoreObjective objective) {
        if (board == null || objective == null) return false;
        if (containsReplay(objective.getDisplayName())) return true;
        Collection<Score> scores = board.getSortedScores(objective);
        if (scores == null) return false;
        ArrayDeque<String> visible = new ArrayDeque<String>(15);
        for (Score score : scores) {
            if (score == null) continue;
            String name = score.getPlayerName();
            if (name == null || name.startsWith("#")) continue;
            if (visible.size() == 15) visible.removeFirst();
            visible.addLast(ScorePlayerTeam.formatPlayerName(board.getPlayersTeam(name), name));
        }
        for (String line : visible) if (containsReplay(line)) return true;
        return false;
    }

    static boolean containsReplay(String text) {
        if (text == null) return false;
        StringBuilder plain = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()
                    && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(text.charAt(i + 1))) >= 0) {
                i++;
            } else plain.append(c);
        }
        return plain.toString().toLowerCase(Locale.ROOT).contains("replay");
    }
}
