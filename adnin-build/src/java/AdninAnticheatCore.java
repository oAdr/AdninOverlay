import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure client-side detection state. Existing checks derive from RavenBS-Plus-Plus
 * (MIT); Scaffold and Legit Scaffold derive from Roxiun/Mellow ScaffoldCheck
 * and EagleCheck, respectively, commit
 * 17ef9b7466754a33ee8c8ed87fa7ea717573d775 (GNU GPL version 3).
 * No game objects, IO, chat sends, or wall-clock reads occur here.
 */
public final class AdninAnticheatCore {
    private AdninAnticheatCore() { }

    public enum Check {
        AUTO_BLOCK("Autoblock"), NO_FALL("NoFall"), NO_SLOW("NoSlow"),
        SCAFFOLD("Scaffold"), LEGIT_SCAFFOLD("Legit scaffold");
        public final String label;
        Check(String label) { this.label = label; }
    }

    public static final class Settings {
        public boolean enabled, ignoreTeammates, atlasOnly, autoReport;
        public boolean flagSound = true, autoBlock = true, noFall = true,
                noSlow = true, scaffold = true, legitScaffold = true;
        public int intervalSeconds = 20;
        public String ignoredPlayers = "";
        private String ignoredSource, normalizedIgnored, cachedSignature;
        private long cachedFlags = Long.MIN_VALUE;

        String signature() {
            String source = ignoredPlayers == null ? "" : ignoredPlayers;
            long flags = (enabled ? 1 : 0) | (flagSound ? 2 : 0) | (autoBlock ? 4 : 0)
                | (noFall ? 8 : 0) | (noSlow ? 16 : 0) | (scaffold ? 32 : 0)
                | (legitScaffold ? 64 : 0) | (ignoreTeammates ? 128 : 0)
                | (atlasOnly ? 256 : 0) | (autoReport ? 1024 : 0)
                | ((long) clampInterval(intervalSeconds) << 11);
            if (cachedSignature == null || !source.equals(ignoredSource) || flags != cachedFlags) {
                if (normalizedIgnored == null || !source.equals(ignoredSource)) {
                    normalizedIgnored = normalizeIgnored(source);
                    ignoredSource = source;
                }
                cachedFlags = flags;
                cachedSignature = flags + ":" + normalizedIgnored;
            }
            return cachedSignature;
        }
    }

    /** One completed entity game tick; server coordinates retain their raw /32 units. */
    public static final class Snapshot {
        public String name = "";
        public int tick, swingProgress, serverX, serverY, serverZ;
        /** Adapter game tick for command deduplication; pure fixtures may use entity tick. */
        public int sampleTick = Integer.MIN_VALUE;
        public long now, lastPacket;
        public double deltaX, deltaY, deltaZ, distanceToGround;
        /** Actual observed coordinates/rotation, never server fixed-point substitutes. */
        public double x, y, z;
        public float pitch, yaw;
        public int hurtTime;
        public boolean currentTab, realProfile, replay, replayProfile, atlasSuspect, teammate, self, dead;
        public boolean swinging, blocking, sprinting, usingItem, sneaking, holdingBlock;
        public boolean flying, onGround, onLadder, inWater, inLava, overAir, overVoid;
        public boolean riding;
    }

    /** A local alert decision. The adapter is the sole owner of optional command sends. */
    public static final class Alert {
        public final Check check;
        public final boolean sound;
        public final String reportCommand;
        public final boolean autoReport;
        public final String scaffoldType;
        public final double scaffoldViolationLevel;
        Alert(Check check, boolean sound, String reportCommand, boolean autoReport,
                String scaffoldType, double scaffoldViolationLevel) {
            this.check = check;
            this.sound = sound;
            this.reportCommand = reportCommand;
            this.autoReport = autoReport;
            this.scaffoldType = scaffoldType;
            this.scaffoldViolationLevel = scaffoldViolationLevel;
        }
    }

    private static final class PlayerState {
        final Object identity;
        int tick, autoBlockTicks, noSlowTicks;
        long now;
        double serverX, serverY, serverZ;
        boolean packetFresh;
        ScaffoldState scaffold = new ScaffoldState();
        EagleState eagle;
        PlayerState(Object identity) { this.identity = identity; }
    }

    /** Bounded Mellow Eagle history; one observed release can contribute only once. */
    private static final class EagleState {
        final int[] durations = new int[3];
        int durationCount, patterns, consecutive;
        long tick, crouchStart, crouchEnd, swingTick, lastPatternTick, consumedEnd;
        boolean crouching, swinging, hasStart, hasEnd, hasSwing, consumed;
        double violations, lastIncrement;
        String lastType = "";
    }

    /** Five consecutive observed positions plus player-local Mellow scoring state. */
    private static final class ScaffoldState {
        final double[] x = new double[5], y = new double[5], z = new double[5];
        int count, consecutive;
        long tick, lastViolationTick;
        double violations, lastIncrement;
        String lastType = "";
    }

    private static final class AlertHistory {
        final EnumMap<Check, Long> alerts = new EnumMap<Check, Long>(Check.class);
        long lastAlert, lastReport;
        int lastReportTick;
        boolean reported;
    }

    public static final class Engine {
        private final Map<UUID, PlayerState> players = new HashMap<UUID, PlayerState>();
        private final Map<UUID, AlertHistory> histories = new LinkedHashMap<UUID, AlertHistory>();
        private static final int HISTORY_LIMIT = 1024;
        private static final long HISTORY_TTL_MILLIS = 60000L;
        private Set<String> ignored = Collections.emptySet();
        private String configuration;
        private long lastSound, nextHistorySweep;
        private boolean soundPlayed;

        /** Apply configuration even when no eligible entities currently exist. */
        public void configure(Settings settings) {
            String next = settings.signature();
            if (!next.equals(configuration)) {
                clearEvidence();
                configuration = next;
                ignored = ignoredSet(settings.ignoredPlayers);
            }
        }

        public void reset() {
            players.clear();
            histories.clear();
            ignored = Collections.emptySet();
            configuration = null;
            lastSound = 0;
            soundPlayed = false;
            nextHistorySweep = 0;
        }

        /** Settings, roster churn, and entity replacements must not reset report cooldowns. */
        public void clearEvidence() { players.clear(); }
        public void retainPlayers(Set<UUID> current) { players.keySet().retainAll(current); }
        public int trackedPlayers() { return players.size(); }
        int cooldownPlayers() { return histories.size(); }

        /** Cheap guard before the game adapter performs any downward terrain scan. */
        public boolean noFallCandidate(UUID id, Object identity, Snapshot s, Settings cfg) {
            configure(cfg);
            PlayerState p = players.get(id);
            return cfg.enabled && cfg.noFall && p != null && p.identity == identity
                && validSnapshot(s) && eligible(s, cfg) && !ignored.contains(lower(s.name))
                && !(cfg.ignoreTeammates && s.teammate) && noFallCandidate(p, s);
        }

        private boolean noFallCandidate(PlayerState p, Snapshot s) {
            if (s.flying || !p.packetFresh || !packetFresh(s) || s.tick - (long) p.tick != 1L
                    || s.now < p.now || s.now - p.now > 250L || replayDiscontinuity(p, s)) return false;
            double dy = p.serverY - s.serverY / 32.0;
            return dy >= 5.0 && dy <= 40.0 && Math.abs(p.serverX - s.serverX / 32.0) <= 10.0
                && Math.abs(p.serverZ - s.serverZ / 32.0) <= 10.0;
        }

        public Alert sample(UUID id, Object identity, Snapshot s, Settings cfg) {
            configure(cfg);
            if (id == null || identity == null) return null;
            if (!cfg.enabled || !eligible(s, cfg) || ignored.contains(lower(s.name))
                    || (cfg.ignoreTeammates && s.teammate)) {
                players.remove(id);
                return null;
            }
            if (!validSnapshot(s)) {
                players.remove(id);
                return null;
            }
            pruneHistories(s.now);
            PlayerState p = players.get(id);
            boolean fresh = p == null || p.identity != identity;
            if (!fresh && s.tick == p.tick) return null; // Render/native/HUD duplicate.
            if (!fresh && (s.tick - (long) p.tick != 1L || s.now < p.now
                    || s.now - p.now > 250L || replayDiscontinuity(p, s))) fresh = true;
            if (fresh) {
                p = new PlayerState(identity);
                players.put(id, p);
            }

            double speed = Math.max(Math.abs(s.deltaX), Math.abs(s.deltaZ));
            p.autoBlockTicks = s.swinging && s.blocking ? increment(p.autoBlockTicks) : 0;
            p.noSlowTicks = s.sprinting && s.usingItem ? increment(p.noSlowTicks) : 0;
            boolean eagleDetected = cfg.legitScaffold && updateEagle(p, s);
            boolean scaffoldDetected = cfg.scaffold && updateScaffold(p, s);

            double serverX = s.serverX / 32.0, serverY = s.serverY / 32.0,
                   serverZ = s.serverZ / 32.0;
            boolean packetFresh = packetFresh(s);
            Check detected = null;
            if (!fresh) {
                if (cfg.autoBlock && p.autoBlockTicks >= 10) detected = Check.AUTO_BLOCK;
                else if (eagleDetected)
                    detected = Check.LEGIT_SCAFFOLD;
                else if (cfg.noSlow && p.noSlowTicks == 11 && speed >= 0.08) detected = Check.NO_SLOW;
                else if (scaffoldDetected)
                    detected = Check.SCAFFOLD;
                else if (cfg.noFall && noFallCandidate(p, s) && !s.overVoid
                        && finite(s.distanceToGround) && s.distanceToGround > 3.0
                        && !s.onLadder && !s.inWater && !s.inLava)
                    detected = Check.NO_FALL;
            }
            p.tick = s.tick;
            p.now = s.now;
            p.serverX = serverX;
            p.serverY = serverY;
            p.serverZ = serverZ;
            p.packetFresh = packetFresh;
            if (detected == null) return null;

            AlertHistory history = histories.get(id);
            if (history == null) {
                // At the bounded limit skip new alerts rather than discard an active cooldown.
                if (histories.size() >= HISTORY_LIMIT) return null;
                history = new AlertHistory();
                histories.put(id, history);
            }
            Long prior = history.alerts.get(detected);
            long interval = clampInterval(cfg.intervalSeconds) * 1000L;
            if (prior != null && (s.now < prior || s.now - prior < interval)) return null;
            history.alerts.put(detected, s.now);
            history.lastAlert = Math.max(history.lastAlert, s.now);
            boolean sound = cfg.flagSound && (!soundPlayed || s.now >= lastSound
                    && s.now - lastSound >= 1500L);
            if (sound) { lastSound = s.now; soundPlayed = true; }
            String command = !s.replay && !s.atlasSuspect && validPlayerName(s.name)
                    ? "/wdr " + s.name : "";
            int reportTick = s.sampleTick == Integer.MIN_VALUE ? s.tick : s.sampleTick;
            boolean report = cfg.autoReport && !command.isEmpty()
                    && (!history.reported || reportTick != history.lastReportTick
                        && s.now >= history.lastReport && s.now - history.lastReport >= interval);
            if (report) {
                history.reported = true;
                history.lastReport = s.now;
                history.lastReportTick = reportTick;
            }
            return new Alert(detected, sound, command, report,
                    detected == Check.SCAFFOLD ? p.scaffold.lastType : "",
                    detected == Check.SCAFFOLD ? p.scaffold.violations : 0.0);
        }

        /**
         * Mellow ScaffoldCheck's tower/horizontal thresholds and weighting, adapted
         * to Adnin's admitted actors and completed ticks. Only Scaffold owns this VL.
         * The upstream manager default is 10; Adnin retains its existing alert and
         * report interval instead of importing Mellow's other checks or alert system.
         */
        private boolean updateScaffold(PlayerState player, Snapshot s) {
            ScaffoldState state = player.scaffold;
            if (!finite(s.x) || !finite(s.y) || !finite(s.z) || !finite(s.yaw)) {
                player.scaffold = new ScaffoldState();
                return false;
            }
            long tick = s.sampleTick == Integer.MIN_VALUE ? s.tick : s.sampleTick;
            if (state.count > 0 && (tick - state.tick != 1L
                    || Math.abs(s.x - state.x[0]) >= 5.0
                    || Math.abs(s.y - state.y[0]) >= 5.0
                    || Math.abs(s.z - state.z[0]) >= 5.0)) {
                // Same conservative five-block discontinuity bound as Replay;
                // retire only Scaffold evidence so other live checks stay unchanged.
                state = player.scaffold = new ScaffoldState();
            }
            for (int i = 4; i > 0; i--) {
                state.x[i] = state.x[i - 1];
                state.y[i] = state.y[i - 1];
                state.z[i] = state.z[i - 1];
            }
            state.x[0] = s.x; state.y[0] = s.y; state.z[0] = s.z;
            state.tick = tick;
            state.count = Math.min(5, state.count + 1);
            state.lastIncrement = 0.0;
            if (s.riding || state.count < 5) return false;

            // Upstream uses a fixed 20-tick scale, not measured wall-clock speed.
            double dx = (state.x[0] - state.x[1]) * 20.0;
            double dz = (state.z[0] - state.z[1]) * 20.0;
            double speedXZSq = dx * dx + dz * dz;
            double speedXZ = Math.sqrt(speedXZSq);
            double speedY = (state.y[1] - state.y[2]) * 20.0;
            double accelY = 50.0 * (state.y[1] - state.y[2] - (state.y[3] - state.y[4]));
            float lookYaw = s.yaw % 360.0f;
            if (lookYaw >= 180.0f) lookYaw -= 360.0f;
            if (lookYaw < -180.0f) lookYaw += 360.0f;
            double moveYaw = Math.toDegrees(Math.atan2(dz / 20.0, dx / 20.0)) - 90.0;
            double angle = (moveYaw - lookYaw + 360.0) % 360.0;
            if (angle > 180.0) angle -= 360.0;
            String type = "";
            double base = 0.0;
            if (s.swinging && s.hurtTime == 0 && s.pitch > 50.0f && speedXZSq > 9.0
                    && s.holdingBlock && Math.abs(angle) > 165.0 && speedXZSq < 100.0
                    && !(Math.abs(accelY) < 0.001)) {
                double pitchFactor = unit((s.pitch - 50.0) / 40.0);
                double angleFactor = unit((Math.abs(angle) - 165.0) / 15.0);
                if (speedY >= 4.0 && speedY <= 15.0 && accelY > -25.0) {
                    type = "tower";
                    double severity = pitchFactor * 0.3 + angleFactor * 0.3
                            + unit((speedY - 4.0) / 11.0) * 0.3 + unit((accelY + 25.0) / 25.0) * 0.1;
                    base = 3.0 + severity * 3.0;
                } else if (speedY >= -1.0 && speedY <= 4.0 && Math.abs(speedY) > 0.005
                        && speedXZSq > 25.0) {
                    type = "horizontal";
                    double severity = pitchFactor * 0.3 + angleFactor * 0.3
                            + unit((speedXZ - 5.0) / 5.0) * 0.4;
                    base = 3.0 + severity * 3.0;
                }
            }
            if (type.length() == 0) {
                if (tick - state.lastViolationTick > 60L) state.consecutive = 0;
                return false;
            }
            state.consecutive = type.equals(state.lastType) && tick - state.lastViolationTick < 40L
                    ? increment(state.consecutive) : 0;
            state.lastType = type;
            state.lastViolationTick = tick;
            state.lastIncrement = base * (1.0 + Math.min(state.consecutive, 5) * 0.2);
            state.violations += state.lastIncrement;
            return state.violations >= 10.0;
        }

        private void pruneHistories(long now) {
            if (now < nextHistorySweep) return;
            nextHistorySweep = now <= Long.MAX_VALUE - 1000L ? now + 1000L : Long.MAX_VALUE;
            for (Iterator<AlertHistory> it = histories.values().iterator(); it.hasNext();) {
                AlertHistory history = it.next();
                if (now >= history.lastAlert && now - history.lastAlert >= HISTORY_TTL_MILLIS) it.remove();
            }
        }

        private boolean replayDiscontinuity(PlayerState p, Snapshot s) {
            // Without a recorded timeline, a Replay seek and a 5+ block NoFall
            // transition are indistinguishable. Retire evidence rather than flag it.
            return s.replay && (Math.abs(s.deltaX) >= 5.0 || Math.abs(s.deltaY) >= 5.0
                    || Math.abs(s.deltaZ) >= 5.0 || Math.abs(p.serverX - s.serverX / 32.0) >= 5.0
                    || Math.abs(p.serverY - s.serverY / 32.0) >= 5.0
                    || Math.abs(p.serverZ - s.serverZ / 32.0) >= 5.0);
        }

        /**
         * Mellow Eagle's release/swing timing, three-duration variance and weighted
         * VL. START-event edges become edges of consecutive completed snapshots.
         * A release is consumed once, within one tick: upstream otherwise repeats
         * an old timestamp pair every frame. Its instant-sequence else-if is
         * implied by the preceding mechanical condition on a monotonic timeline,
         * so it cannot add an independent legitimate branch here either.
         */
        private boolean updateEagle(PlayerState player, Snapshot s) {
            if (!finite(s.yaw)) { player.eagle = null; return false; }
            long tick = s.sampleTick == Integer.MIN_VALUE ? s.tick : s.sampleTick;
            EagleState state = player.eagle;
            if (state == null || tick - state.tick != 1L) {
                state = player.eagle = new EagleState();
                state.tick = tick;
                state.crouching = s.sneaking;
                state.swinging = s.swinging;
                return false; // Joining mid-animation is not an observed edge.
            }
            state.tick = tick;
            state.lastIncrement = 0.0;
            if (s.swinging && !state.swinging) {
                state.swingTick = tick;
                state.hasSwing = true;
            }
            if (s.sneaking && !state.crouching) {
                state.crouchStart = tick;
                state.hasStart = true;
            } else if (!s.sneaking && state.crouching && state.hasStart) {
                state.crouchEnd = tick;
                state.hasEnd = true;
                long duration = tick - state.crouchStart;
                for (int i = 2; i > 0; i--) state.durations[i] = state.durations[i - 1];
                state.durations[0] = (int) Math.min(Integer.MAX_VALUE, duration);
                state.durationCount = Math.min(3, state.durationCount + 1);
            }
            state.crouching = s.sneaking;
            state.swinging = s.swinging;

            long duration = state.crouchEnd - state.crouchStart;
            boolean recentRelease = state.hasEnd && tick >= state.crouchEnd
                    && tick - state.crouchEnd <= 1L
                    && (!state.consumed || state.consumedEnd != state.crouchEnd);
            boolean quickCrouch = state.hasStart && duration >= 1L && duration <= 2L;
            boolean pairedSwing = state.hasSwing && state.swingTick >= state.crouchEnd
                    && state.swingTick - state.crouchEnd <= 1L;
            boolean flagged = false;
            if (s.pitch >= 70.0f && s.holdingBlock && recentRelease && quickCrouch && pairedSwing) {
                state.consumed = true;
                state.consumedEnd = state.crouchEnd;
                if (tick - state.lastPatternTick > 15L) state.patterns = 0;
                state.patterns = increment(state.patterns);
                state.lastPatternTick = tick;
                if (state.patterns >= 2) {
                    double consistency = 0.0;
                    boolean consistent = false;
                    if (state.durationCount >= 3) {
                        double mean = (state.durations[0] + (double) state.durations[1] + state.durations[2]) / 3.0;
                        double a = state.durations[0] - mean, b = state.durations[1] - mean, c = state.durations[2] - mean;
                        consistency = unit(1.0 - ((a * a + b * b + c * c) / 3.0) / 4.0);
                        consistent = state.durations[0] <= 2 && state.durations[1] <= 2 && state.durations[2] <= 2;
                    }
                    double moveYaw = Math.toDegrees(Math.atan2(-s.deltaX, s.deltaZ)) - s.yaw;
                    moveYaw %= 360.0;
                    if (moveYaw >= 180.0) moveYaw -= 360.0;
                    if (moveYaw < -180.0) moveYaw += 360.0;
                    // atan2(0, 0) cannot establish a backwards travel direction.
                    boolean moving = s.deltaX != 0.0 || s.deltaZ != 0.0;
                    boolean backwards = moving && Math.abs(moveYaw) >= 90.0;
                    boolean directlyBackwards = moving && Math.abs(moveYaw) >= 160.0;
                    state.consecutive = increment(state.consecutive);
                    double pitchWeight = s.pitch >= 85.0f ? 1.5 : 1.0;
                    double swingWeight = state.swingTick == state.crouchEnd ? 1.4 : 1.0;
                    double moveWeight = directlyBackwards ? 1.8 : backwards ? 1.5 : 1.0;
                    state.lastIncrement = 2.0 * pitchWeight * swingWeight * moveWeight
                            * (1.0 + consistency * 0.5) * (1.0 + Math.min(state.consecutive, 5) * 0.15);
                    state.violations += state.lastIncrement;
                    state.lastType = backwards ? "backwards-bridging"
                            : consistent ? "consistent-pattern" : "mechanical-pattern";
                    state.patterns = 0;
                    flagged = true;
                }
            }
            if (!flagged) {
                if (tick - state.lastPatternTick > 15L) state.patterns = 0;
                if (state.consecutive > 0) state.consecutive--;
            }
            return flagged && state.violations >= 10.0;
        }
    }

    public static boolean eligible(Snapshot s, Settings cfg) {
        if (s.self || s.dead || s.tick < 0) return false;
        if (cfg.atlasOnly) return s.currentTab && s.atlasSuspect;
        return !s.atlasSuspect && validPlayerName(s.name)
                && (s.replay ? s.replayProfile : s.currentTab && s.realProfile);
    }

    public static boolean validPlayerName(String value) {
        if (value == null || value.isEmpty() || value.length() > 16) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return true;
    }

    public static boolean isAtlasSuspect(String value) { return "Suspect\u00a7r".equals(value); }
    public static int clampInterval(int value) { return Math.max(0, Math.min(60, value)); }
    public static String normalizeIgnored(String value) {
        StringBuilder out = new StringBuilder();
        for (String name : ignoredSet(value)) {
            if (out.length() > 0) out.append(',');
            out.append(name);
        }
        return out.toString();
    }

    private static Set<String> ignoredSet(String value) {
        Set<String> out = new LinkedHashSet<String>();
        if (value == null) return out;
        String bounded = value.substring(0, Math.min(4096, value.length()));
        for (String token : bounded.split(",")) {
            String name = token.trim();
            if (validPlayerName(name)) out.add(lower(name));
            if (out.size() >= 128) break;
        }
        return out;
    }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static boolean packetFresh(Snapshot s) {
        return s.lastPacket > 0 && s.now >= s.lastPacket && s.now - s.lastPacket <= 150L;
    }
    private static boolean validSnapshot(Snapshot s) {
        return s.now > 0 && finite(s.deltaX) && finite(s.deltaY) && finite(s.deltaZ) && finite(s.pitch);
    }
    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static double unit(double value) { return Math.max(0.0, Math.min(1.0, value)); }
    private static int increment(int value) { return value < Integer.MAX_VALUE ? value + 1 : value; }
}
