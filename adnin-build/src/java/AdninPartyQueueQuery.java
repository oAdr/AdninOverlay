import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

/** Lunar-only mode discovery for the original native queue-party detector. */
public final class AdninPartyQueueQuery {
    static final String COMMAND = "/locraw";
    static final long DELAY_MS = 500L, MIN_ATTEMPT_MS = 5000L, READ_BACKOFF_MS = 1000L;
    static final int MAX_ATTEMPTS = 3;
    static final long SCOPE_PROBE_MS = 250L;
    static final int SCOPE_HYPIXEL = 1, SCOPE_WAITING = 2;
    private static final Policy POLICY = new Policy();
    private static final Sender LIVE = new Sender() {
        public boolean send(Object world, Object connection, String command) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !mc.isCallingFromMinecraftThread() || mc.theWorld != world
                    || mc.getNetHandler() != connection || mc.thePlayer == null
                    || mc.isSingleplayer() || !AdninGui4.partyQueueDetector
                    || AdninFeatures.outputContextAllowed()) return false;
            ServerData data = mc.getCurrentServerData();
            int scope = inspectSidebar(mc.theWorld.getScoreboard(), mc.thePlayer.getName(), LIVE_GLYPH_WIDTH);
            if ((scope & SCOPE_WAITING) == 0 || ((scope & SCOPE_HYPIXEL) == 0
                    && (data == null || !isHypixelAddress(data.serverIP)))) return false;
            mc.thePlayer.sendChatMessage(command);
            return true;
        }
    };
    private static final ScopeProbe SIDEBAR = new ScopeProbe() {
        public int observe(Object world, Object connection) {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.isCallingFromMinecraftThread() && mc.theWorld == world
                && mc.getNetHandler() == connection && mc.thePlayer != null && !mc.isSingleplayer()
                && !AdninFeatures.outputContextAllowed()
                ? inspectSidebar(mc.theWorld.getScoreboard(), mc.thePlayer.getName(), LIVE_GLYPH_WIDTH) : 0;
        }
    };
    private static final GlyphWidth LIVE_GLYPH_WIDTH = new GlyphWidth() {
        public int width(char value) {
            Minecraft mc = Minecraft.getMinecraft();
            return mc == null || mc.fontRendererObj == null ? -1
                : mc.fontRendererObj.getStringWidth(String.valueOf(value));
        }
    };

    private AdninPartyQueueQuery() { }

    /** Existing client-thread pump only: no worker, scheduled task or Future. */
    public static void tick() {
        long now = System.nanoTime() / 1000000L;
        if (!POLICY.canRead(now)) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) { POLICY.disconnect(); return; }
            if (!mc.isCallingFromMinecraftThread()) return;
            ServerData data = mc.getCurrentServerData();
            POLICY.tick(now, mc.theWorld, mc.getNetHandler(), AdninGui4.partyQueueDetector,
                !mc.isSingleplayer(), data == null ? null : data.serverIP,
                mc.thePlayer != null, LIVE, SIDEBAR, AdninFeatures.outputContextAllowed());
        } catch (RuntimeException failedRead) {
            POLICY.readFailed(now);
        } catch (LinkageError unavailable) {
            POLICY.readFailed(now);
        }
    }

    /** Terminal for this classloader; a later pump cannot revive pending work. */
    public static void shutdown() { POLICY.shutdown(); }

    /** Positive visible server branding for a forwarding/custom join address. */
    static boolean hasHypixelSidebar(Scoreboard board, String localName) {
        return hasHypixelSidebar(board, localName, null);
    }

    static boolean hasHypixelSidebar(Scoreboard board, String localName, GlyphWidth glyphs) {
        return (inspectSidebar(board, localName, glyphs) & SCOPE_HYPIXEL) != 0;
    }

    /** Inspect exactly the currently rendered sidebar, never a cached lobby title. */
    static int inspectSidebar(Scoreboard board, String localName, GlyphWidth glyphs) {
        ScoreObjective objective = AdninReplay.sidebar(board, localName);
        if (board == null || objective == null) return 0;
        Collection<Score> scores = board.getSortedScores(objective);
        if (scores == null || scores.size() > 512) return 0;
        int totalCount = scores.size(), filteredCount = 0;
        ArrayDeque<Score> visible = new ArrayDeque<Score>(15);
        for (Score score : scores) {
            if (score == null) continue;
            String name = score.getPlayerName();
            if (name == null || name.startsWith("#")) continue;
            ++filteredCount;
            if (visible.size() == 15) visible.removeFirst();
            visible.addLast(score);
        }
        // Vanilla 1.8.9 skips using the original collection size only when
        // more than 15 filtered rows remain. Hidden rows can therefore clip
        // additional entries; never authorize a footer the renderer omitted.
        if (filteredCount > 15) {
            int renderedCount = Math.max(0, filteredCount - (totalCount - 15));
            while (visible.size() > renderedCount) visible.removeFirst();
        }
        int result = 0;
        String title = plain(objective.getDisplayName());
        boolean bedwars = "BED WARS".equalsIgnoreCase(title)
            || "\u8d77\u5e8a\u6218\u4e89".equals(title) || "\u8d77\u5e8a\u6230\u722d".equals(title);
        boolean players = false, waiting = false, deniedPhase = false;
        for (Score score : visible) {
            String name = score.getPlayerName();
            ScorePlayerTeam team = board.getPlayersTeam(name);
            String text = ScorePlayerTeam.formatPlayerName(team, name);
            if (isHypixelFooter(text)) result |= SCOPE_HYPIXEL;
            // A supplementary code point may be an invisible unique row key.
            // Omit only that complete team entry, after the untouched prefix
            // and suffix match, and only if the current font gives each UTF-16
            // unit zero width. Custom visible glyphs must still be rejected.
            if (team != null && glyphs != null && name.length() == 2
                    && Character.isSurrogatePair(name.charAt(0), name.charAt(1))
                    && isHypixelFooter(ScorePlayerTeam.formatPlayerName(team, ""))
                    && glyphs.width(name.charAt(0)) == 0 && glyphs.width(name.charAt(1)) == 0) result |= SCOPE_HYPIXEL;
            if (bedwars) {
                // Scoreboard entry keys may be invisible; only omit a complete
                // legal zero-width surrogate-pair key, just as for the footer.
                if (team != null && glyphs != null && name.length() == 2
                        && Character.isSurrogatePair(name.charAt(0), name.charAt(1))
                        && glyphs.width(name.charAt(0)) == 0 && glyphs.width(name.charAt(1)) == 0)
                    text = ScorePlayerTeam.formatPlayerName(team, "");
                String row = plain(text);
                if (row != null) {
                    players |= playerCount(row);
                    waiting |= waitingLine(row);
                    deniedPhase |= containsIgnoreCase(row, "replay") || row.indexOf("\u56de\u653e") >= 0
                        || row.regionMatches(true, 0, "Lobby:", 0, 6)
                        || row.regionMatches(true, 0, "Tokens:", 0, 7)
                        || row.regionMatches(true, 0, "Diamond ", 0, 8)
                        || row.regionMatches(true, 0, "Emerald ", 0, 8)
                        || row.regionMatches(true, 0, "Time Left:", 0, 10)
                        || row.regionMatches(true, 0, "Players Left:", 0, 13);
                }
            }
        }
        if (bedwars && players && waiting && !deniedPhase) result |= SCOPE_WAITING;
        return result;
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        for (int i = 0; i + needle.length() <= value.length(); ++i)
            if (value.regionMatches(true, i, needle, 0, needle.length())) return true;
        return false;
    }

    private static boolean playerCount(String row) {
        String value;
        if (row.regionMatches(true, 0, "Players:", 0, 8)) value = row.substring(8).trim();
        else if (row.startsWith("\u73a9\u5bb6:") || row.startsWith("\u73a9\u5bb6\uff1a")) value = row.substring(3).trim();
        else return false;
        int slash = value.indexOf('/');
        if (slash < 1 || slash != value.lastIndexOf('/')) return false;
        int count = boundedNumber(value.substring(0, slash).trim(), 80);
        int maximum = boundedNumber(value.substring(slash + 1).trim(), 80);
        return count >= 1 && maximum >= count && maximum >= 2;
    }

    private static boolean waitingLine(String row) {
        if ("Waiting...".equalsIgnoreCase(row) || "Waiting\u2026".equalsIgnoreCase(row)
                || "\u7b49\u5f85\u4e2d...".equals(row) || "\u7b49\u5f85\u4e2d\u2026".equals(row)) return true;
        if (!row.regionMatches(true, 0, "Starting in ", 0, 12)) return false;
        String time = row.substring(12).trim();
        if (time.endsWith("s")) return boundedNumber(time.substring(0, time.length() - 1), 120) >= 0;
        int colon = time.indexOf(':');
        if (colon != 1 || time.length() != 4) return false;
        return boundedNumber(time.substring(0, colon), 2) >= 0
            && boundedNumber(time.substring(colon + 1), 59) >= 0;
    }

    private static int boundedNumber(String value, int maximum) {
        if (value.length() < 1 || value.length() > 3) return -1;
        int number = 0;
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return -1;
            number = number * 10 + c - '0';
        }
        return number <= maximum ? number : -1;
    }

    /** Match a complete footer; generic Bed Wars titles and substring URLs do not qualify. */
    static boolean isHypixelFooter(String text) {
        String plain = plain(text);
        if (plain == null) return false;
        for (int i = 0; i < plain.length(); ++i) if (plain.charAt(i) > 127) return false;
        return "hypixel.net".equalsIgnoreCase(plain) || "www.hypixel.net".equalsIgnoreCase(plain);
    }

    private static String plain(String text) {
        if (text == null || text.length() > 256) return null;
        String plain = text;
        if (text.indexOf('\u00a7') >= 0) {
            StringBuilder stripped = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); ++i) {
                char c = text.charAt(i);
                if (c == '\u00a7' && i + 1 < text.length()) {
                    char code = text.charAt(i + 1);
                    if (code >= 'A' && code <= 'Z') code += 'a' - 'A';
                    if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f'
                            || code >= 'k' && code <= 'o' || code == 'r') { ++i; continue; }
                }
                stripped.append(c);
            }
            plain = stripped.toString();
        }
        return plain.trim();
    }

    /** No DNS resolution: accept only ASCII Hypixel hostnames and a legal port. */
    static boolean isHypixelAddress(String address) {
        if (address == null) return false;
        String value = address.trim();
        if (value.length() == 0 || value.length() > 259) return false;
        int colon = value.indexOf(':');
        int end = colon < 0 ? value.length() : colon;
        if (colon >= 0) {
            int digits = value.length() - colon - 1;
            if (digits < 1 || digits > 5) return false;
            int port = 0;
            for (int i = colon + 1; i < value.length(); ++i) {
                char c = value.charAt(i);
                if (c < '0' || c > '9') return false;
                port = port * 10 + c - '0';
            }
            if (port < 1 || port > 65535) return false;
        }
        final String domain = "hypixel.net";
        if (end < domain.length() || end > 253
                || !value.regionMatches(true, end - domain.length(), domain, 0, domain.length())
                || end > domain.length() && value.charAt(end - domain.length() - 1) != '.') return false;
        int labelLength = 0;
        for (int i = 0; i < end; ++i) {
            char c = value.charAt(i);
            if (c == '.') {
                if (labelLength == 0 || value.charAt(i - 1) == '-') return false;
                labelLength = 0;
            } else {
                if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-')) return false;
                if (labelLength == 0 && c == '-' || ++labelLength > 63) return false;
            }
        }
        return labelLength > 0 && value.charAt(end - 1) != '-';
    }

    interface Sender { boolean send(Object world, Object connection, String command); }
    interface ScopeProbe { int observe(Object world, Object connection); }
    interface GlyphWidth { int width(char value); }

    /** Pure, injectable scheduling policy. All retained game identities are weak. */
    static final class Policy {
        private WeakReference<Object> world, connection;
        private boolean stopped, attempted, armed, readBackoff, addressChecked, addressAllowed;
        private boolean enabledBefore, hasAttemptTime, hostnameAllowed, sidebarAllowed, waitingAllowed, scopeProbed;
        private String checkedAddress;
        private long armedAt, failedReadAt, lastAttemptAt, scopeProbedAt;
        private int attemptCount;

        synchronized boolean canRead(long now) {
            if (stopped) return false;
            if (readBackoff) {
                if (now - failedReadAt < READ_BACKOFF_MS) return false;
                readBackoff = false;
            }
            return true;
        }

        synchronized void tick(long now, Object nextWorld, Object nextConnection, boolean enabled,
                boolean multiplayer, String address, boolean ready, Sender sender) {
            tick(now, nextWorld, nextConnection, enabled, multiplayer, address, ready, sender, null);
        }

        synchronized void tick(long now, Object nextWorld, Object nextConnection, boolean enabled,
                boolean multiplayer, String address, boolean ready, Sender sender, ScopeProbe scope) {
            tick(now, nextWorld, nextConnection, enabled, multiplayer, address, ready, sender, scope, false);
        }

        synchronized void tick(long now, Object nextWorld, Object nextConnection, boolean enabled,
                boolean multiplayer, String address, boolean ready, Sender sender, ScopeProbe scope, boolean activeContext) {
            if (stopped) return;
            boolean enabledNow = enabled && !enabledBefore;
            enabledBefore = enabled;
            if (nextWorld == null || nextConnection == null) { clearCurrent(); return; }
            if (referent(world) != nextWorld || referent(connection) != nextConnection) {
                clearCurrent();
                world = new WeakReference<Object>(nextWorld);
                connection = new WeakReference<Object>(nextConnection);
            }
            // Native disable clears its learned mode. A genuine explicit enable
            // starts a new opportunity; the global interval still prevents spam.
            if (enabledNow) { attempted = false; armed = false; attemptCount = 0; }
            if (!enabled || !multiplayer || activeContext) {
                armed = false; addressAllowed = false; sidebarAllowed = false; waitingAllowed = false; scopeProbed = false;
                return;
            }
            if (attempted) return;
            // Stable client ticks allocate nothing and do not parse the address.
            // The live adapter independently revalidates only on the single send.
            if (!addressChecked || !(checkedAddress == address
                    || checkedAddress != null && checkedAddress.equals(address))) {
                checkedAddress = address;
                hostnameAllowed = isHypixelAddress(address);
                addressChecked = true;
                armed = false; sidebarAllowed = false; waitingAllowed = false; scopeProbed = false;
            }
            if (!ready) {
                // A transient missing local player did not send a command.
                // Retire the deadline, then require fresh evidence/readiness.
                armed = false; scopeProbed = false;
                return;
            }
            if (!scopeProbed || now - scopeProbedAt >= SCOPE_PROBE_MS) {
                scopeProbed = true; scopeProbedAt = now; sidebarAllowed = false; waitingAllowed = false;
                try {
                    int observed = scope == null ? 0 : scope.observe(nextWorld, nextConnection);
                    sidebarAllowed = (observed & SCOPE_HYPIXEL) != 0;
                    waitingAllowed = (observed & SCOPE_WAITING) != 0;
                }
                catch (RuntimeException failedProbe) { }
                catch (LinkageError unavailable) { }
            }
            addressAllowed = waitingAllowed && (hostnameAllowed || sidebarAllowed);
            if (!addressAllowed) { armed = false; return; }
            if (!armed) {
                // Wait for an actual local player before starting the delay.
                if (!ready) return;
                armed = true; armedAt = now;
                return;
            }
            if (!due(now)) return;
            // Reserve the rate limit before dispatch. A just-changed sidebar or
            // temporarily unavailable sender must not permanently lose this
            // world's only opportunity. Failed attempts remain bounded and
            // each retry revalidates the live phase after a fresh delay.
            hasAttemptTime = true; lastAttemptAt = now; armed = false; ++attemptCount;
            boolean sent = false;
            try {
                // The immediate client-thread send shares this short lifecycle
                // lock, making shutdown linearize before or after this one send.
                // The adapter must never await a worker/EventLoop/Future.
                if (sender != null) sent = sender.send(nextWorld, nextConnection, COMMAND);
            } catch (RuntimeException failedSend) {
                // The bounded retry retains the same five-second rate limit.
            } catch (LinkageError unavailable) {
                // A persistent mapping failure exhausts this small retry budget.
            }
            if (stopped) return;
            attempted = sent || attemptCount >= MAX_ATTEMPTS;
            if (!attempted) {
                addressAllowed = false; sidebarAllowed = false; waitingAllowed = false; scopeProbed = false;
            }
        }

        synchronized void readFailed(long now) {
            if (stopped) return;
            // A failed read has sent nothing. Preserve completed attempts and
            // the retry budget while retiring stale eligibility and deadlines.
            armed = false; addressAllowed = false; sidebarAllowed = false; waitingAllowed = false; scopeProbed = false;
            readBackoff = true; failedReadAt = now;
        }

        synchronized void disconnect() { clearCurrent(); }

        synchronized void shutdown() {
            stopped = true;
            clearCurrent();
            readBackoff = false;
        }

        private void clearCurrent() {
            if (world != null) world.clear();
            if (connection != null) connection.clear();
            world = null; connection = null;
            armed = false; attempted = false; attemptCount = 0;
            addressChecked = false; addressAllowed = false; checkedAddress = null;
            hostnameAllowed = false; sidebarAllowed = false; waitingAllowed = false; scopeProbed = false;
        }

        private boolean due(long now) {
            return now - armedAt >= DELAY_MS && (!hasAttemptTime || now - lastAttemptAt >= MIN_ATTEMPT_MS);
        }

        private static Object referent(WeakReference<Object> value) { return value == null ? null : value.get(); }
    }
}
