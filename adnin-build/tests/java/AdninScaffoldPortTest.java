import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Owned numeric fixtures for the Mellow Scaffold port; no game, network or upstream execution. */
public final class AdninScaffoldPortTest {
    private static int checks;
    private static final UUID FIRST = UUID.fromString("12345678-1234-4234-9234-123456789abc");
    private static final UUID SECOND = UUID.fromString("22345678-1234-4234-9234-123456789abc");
    private static final Consumer<AdninAnticheatCore.Snapshot> SAME = s -> { };

    private static final class Harness {
        final AdninAnticheatCore.Engine engine;
        final AdninAnticheatCore.Settings cfg = new AdninAnticheatCore.Settings();
        UUID id = FIRST;
        Object identity = new Object();
        int tick;
        long now = 10000;
        double x, y, z;
        AdninAnticheatCore.Snapshot last;
        Harness() { this(new AdninAnticheatCore.Engine()); }
        Harness(AdninAnticheatCore.Engine engine) {
            this.engine = engine; cfg.enabled = true; cfg.intervalSeconds = 0;
            cfg.autoBlock = cfg.noFall = cfg.noSlow = cfg.legitScaffold = false;
        }
        AdninAnticheatCore.Alert at(double nextX, double nextY, double nextZ,
                Consumer<AdninAnticheatCore.Snapshot> changes) {
            AdninAnticheatCore.Snapshot s = new AdninAnticheatCore.Snapshot();
            s.name = "BridgeFixture"; s.currentTab = s.realProfile = true;
            s.tick = ++tick; s.sampleTick = tick; s.now = now += 50; s.lastPacket = now;
            s.x = nextX; s.y = nextY; s.z = nextZ;
            s.deltaX = nextX - x; s.deltaY = nextY - y; s.deltaZ = nextZ - z;
            s.pitch = 90; s.yaw = 90; s.swinging = s.holdingBlock = true;
            changes.accept(s);
            x = s.x; y = s.y; z = s.z; last = s;
            return engine.sample(id, identity, s, cfg);
        }
        AdninAnticheatCore.Alert stream(Consumer<AdninAnticheatCore.Snapshot> changes) {
            return at(x + .3, y + .1 + .001 * ((tick + 1) % 11), z, changes);
        }
        void idle(int count) {
            for (int i = 0; i < count; i++) check(at(x, y, z, s -> s.swinging = false) == null,
                "ordinary non-matching sample does not issue a Scaffold alert");
        }
    }

    public static void main(String[] args) throws Exception {
        modelBoundaries();
        weightsAndTypes();
        consecutiveWindows();
        stateIsolationAndResets();
        identityAndOutputPolicy();
        System.out.println("AdninScaffoldPortTest: " + checks
            + " checks passed; Mellow boundaries/weighted VL, five-point math, isolated state,"
            + " discontinuities, Replay/Nick admission and existing output cooldowns; entirely offline");
    }

    private static Harness candidate(double speed, double vertical, double accel, float pitch, float yaw,
            Consumer<AdninAnticheatCore.Snapshot> changes) {
        Harness h = new Harness();
        window(h, speed, vertical, accel, pitch, yaw, changes);
        return h;
    }

    private static void window(Harness h, double speed, double vertical, double accel, float pitch, float yaw,
            Consumer<AdninAnticheatCore.Snapshot> changes) {
        double dy = vertical / 20.0, older = dy - accel / 50.0;
        double[] ys = {-older, 0, 0, dy, 2 * dy};
        for (int i = 0; i < 5; i++) {
            final int sample = i;
            h.at(i == 4 ? speed / 20.0 : 0, ys[i], 0, s -> {
                s.pitch = pitch; s.yaw = yaw;
                if (sample < 4) s.swinging = false;
                changes.accept(s);
            });
        }
    }

    private static void modelBoundaries() throws Exception {
        for (double speed : new double[]{3.0, 2.999, 10.0, 10.001})
            zero(candidate(speed, 5, 1, 90, 90, SAME), "outer speed square bounds are strict");
        for (double speed : new double[]{3.001, 9.999})
            kind(candidate(speed, 5, 1, 90, 90, SAME), "tower", "interior tower speed qualifies");
        zero(candidate(5, 1, 1, 90, 90, SAME), "horizontal speed square must be greater than twenty-five");
        kind(candidate(5.001, 1, 1, 90, 90, SAME), "horizontal", "horizontal speed just above five qualifies");
        zero(candidate(6, 1, 1, 50, 90, SAME), "pitch fifty is excluded");
        kind(candidate(6, 1, 1, Math.nextUp(50f), 90, SAME), "horizontal", "next representable pitch above fifty qualifies");
        for (float yaw : new float[]{75f, 105f})
            zero(candidate(6, 1, 1, 90, yaw, SAME), "exact absolute angle 165 is excluded");
        for (float yaw : new float[]{75.001f, 104.999f, 90f, 450f, -270f})
            kind(candidate(6, 1, 1, 90, yaw, SAME), "horizontal", "yaw wrap and strict backward-angle boundary");
        for (double accel : new double[]{0, .0005, -.0005})
            zero(candidate(6, 1, accel, 90, 90, SAME), "almost-zero acceleration is excluded");
        for (double accel : new double[]{.0015, -.0015})
            kind(candidate(6, 1, accel, 90, 90, SAME), "horizontal", "nonzero acceleration outside tolerance qualifies");
        zero(candidate(6, 5, -25, 90, 90, SAME), "tower acceleration exactly minus twenty-five is excluded");
        zero(candidate(6, 5, -25.001, 90, 90, SAME), "tower acceleration below boundary is excluded");
        kind(candidate(6, 5, -24.999, 90, 90, SAME), "tower", "tower acceleration strictly above boundary qualifies");
        kind(candidate(6, 4, 1, 90, 90, SAME), "tower", "tower wins the overlapping vertical-speed-four branch");
        kind(candidate(6, 15, 1, 90, 90, SAME), "tower", "tower upper vertical speed fifteen is inclusive");
        zero(candidate(6, 15.001, 1, 90, 90, SAME), "vertical speed above fifteen is excluded");
        kind(candidate(6, -1, 1, 90, 90, SAME), "horizontal", "horizontal lower vertical speed minus one is inclusive");
        zero(candidate(6, -1.001, 1, 90, 90, SAME), "horizontal vertical speed below minus one is excluded");
        for (double vertical : new double[]{0, .005, -.005})
            zero(candidate(6, vertical, 1, 90, 90, SAME), "horizontal absolute vertical speed must exceed .005");
        for (double vertical : new double[]{.005001, -.005001})
            kind(candidate(6, vertical, 1, 90, 90, SAME), "horizontal", "vertical speed just outside flat-motion tolerance qualifies");
        for (int excluded = 0; excluded < 4; excluded++) {
            final int gate = excluded;
            zero(candidate(6, 1, 1, 90, 90, s -> {
                if (gate == 0) s.swinging = false;
                if (gate == 1) s.holdingBlock = false;
                if (gate == 2) s.hurtTime = 1;
                if (gate == 3) s.riding = true;
            }), "held-block, swing, damage and riding exclusions remain separate gates");
        }
        kind(candidate(6, 1, 1, 90, 90, s -> {
            s.sneaking = true; s.onGround = true; s.overAir = false;
            s.deltaX = 0; s.deltaY = 0; s.deltaZ = 0;
        }), "horizontal", "model uses sampled coordinates and does not import old sneak/air/delta gates");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            zero(candidate(6, 1, 1, 90, 90, s -> s.x = invalid), "nonfinite position clears evidence");
            zero(candidate(6, 1, 1, 90, 90, s -> s.yaw = (float) invalid), "nonfinite yaw clears only Scaffold evidence");
        }
    }

    private static void weightsAndTypes() throws Exception {
        Harness h = candidate(6, 1, 1, 90, 90, SAME);
        close(5.04, metric(h, "lastIncrement"), "horizontal weighted increment: 3 + 3*(.3+.3+.4*.2)");
        close(5.04, metric(h, "violations"), "one weighted match does not meet VL ten");
        AdninAnticheatCore.Alert a = h.stream(SAME);
        scaffold(a, "horizontal", "second same-type increment crosses the imported default threshold");
        close(11.088, a.scaffoldViolationLevel, "exact accumulated first and 1.2-weighted second match");
        for (int count = 2; count <= 7; count++) {
            a = h.stream(SAME);
            scaffold(a, "horizontal", "after threshold, every new matching tick reaches existing alert policy");
            close(5.04 * (1 + Math.min(count, 5) * .2), metric(h, "lastIncrement"),
                "consecutive multiplier rises by .2 and caps after five increments");
        }
        Harness tower = candidate(6, 15, 1, 90, 90, SAME);
        close(6, metric(tower, "lastIncrement"), "maximum tower severity gives base VL six");
        kind(tower, "tower", "tower classification is retained");
        Harness cappedPitch = candidate(6, 1, 1, 120, 90, SAME);
        close(5.04, metric(cappedPitch, "lastIncrement"), "pitch factor clamps at one above ninety degrees");
        double before = metric(h, "violations");
        h.idle(3);
        close(before, metric(h, "violations"), "non-matches do not decay accumulated Mellow VL");
        window(h, 6, 8, 1, 90, 90, SAME);
        kind(h, "tower", "classification can change within one player's score");
        close(0, metric(h, "consecutive"), "a type change restarts the multiplier at one");
    }

    private static void consecutiveWindows() throws Exception {
        for (int gap : new int[]{39, 40}) {
            Harness h = candidate(6, 1, 1, 90, 90, SAME);
            h.idle(gap - 5);
            window(h, 6, 1, 1, 90, 90, SAME);
            close(gap == 39 ? 1 : 0, metric(h, "consecutive"), "same-type window is strictly below forty ticks");
        }
        Harness h = candidate(6, 1, 1, 90, 90, SAME);
        h.stream(SAME);
        h.idle(60);
        close(1, metric(h, "consecutive"), "non-match at exactly sixty ticks retains the stored counter");
        h.idle(1);
        close(0, metric(h, "consecutive"), "non-match after sixty ticks retires the counter");
    }

    private static void stateIsolationAndResets() throws Exception {
        Harness first = candidate(6, 1, 1, 90, 90, SAME);
        Harness other = new Harness(first.engine); other.id = SECOND;
        window(other, 6, 1, 1, 90, 90, SAME);
        close(5.04, metric(first, "violations"), "second player cannot mutate first player's evidence");
        close(5.04, metric(other, "violations"), "each player starts a separate weighted score");
        scaffold(first.stream(SAME), "horizontal", "first actor can complete its own threshold");
        close(5.04, metric(other, "violations"), "first actor's alert cannot push another over threshold");
        double before = metric(other, "violations");
        for (int i = 0; i < 30; i++) check(other.engine.sample(other.id, other.identity, other.last, other.cfg) == null,
            "duplicate callbacks cannot score a repeated entity tick");
        close(before, metric(other, "violations"), "duplicate calls preserve score exactly");
        for (int reset = 0; reset < 8; reset++) {
            Harness h = candidate(6, 1, 1, 90, 90, SAME);
            final int cause = reset;
            if (cause == 0) h.tick++;
            if (cause == 1) h.now -= 100;
            if (cause == 2) h.now += 251;
            if (cause == 3) h.identity = new Object();
            if (cause == 4) h.engine.clearEvidence();
            if (cause == 5) h.engine.reset();
            check(h.stream(s -> {
                if (cause == 6) s.x += 5;
                if (cause == 7) s.sampleTick += 2;
            }) == null, "tick/clock/identity/reset/position/client-tick discontinuity establishes a new baseline");
            close(0, metric(h, "violations"), "discontinuity clears weighted Scaffold VL");
            close(1, metric(h, "count"), "post-discontinuity history starts with one actual position");
        }
        Harness disabled = candidate(6, 1, 1, 90, 90, SAME);
        disabled.cfg.scaffold = false;
        check(disabled.stream(SAME) == null, "disabled Scaffold produces no alert");
        disabled.cfg.scaffold = true;
        check(disabled.stream(SAME) == null, "reenabling Scaffold cannot reuse the previous score");
        close(0, metric(disabled, "violations"), "reenabling starts with zero weighted evidence");
    }

    private static void identityAndOutputPolicy() throws Exception {
        for (int reject = 0; reject < 6; reject++) {
            Harness h = new Harness(); final int gate = reject;
            for (int i = 0; i < 12; i++) check(h.stream(s -> {
                if (gate == 0) s.currentTab = false;
                if (gate == 1) s.realProfile = false;
                if (gate == 2) s.self = true;
                if (gate == 3) s.dead = true;
                if (gate == 4) { s.replay = true; s.replayProfile = false; }
                if (gate == 5) { s.teammate = true; h.cfg.ignoreTeammates = true; }
            }) == null, "existing admission gates precede new Scaffold scoring");
        }
        Harness replay = new Harness(); replay.cfg.autoReport = true;
        for (int i = 0; i < 5; i++) check(replay.stream(s -> {
            s.replay = s.replayProfile = true; s.currentTab = s.realProfile = false;
        }) == null, "admitted Replay actor builds genuine positions and initial score");
        AdninAnticheatCore.Alert a = replay.stream(s -> {
            s.replay = s.replayProfile = true; s.currentTab = s.realProfile = false;
        });
        scaffold(a, "horizontal", "admitted Replay actors use the same weighted detection");
        check(a.reportCommand.isEmpty() && !a.autoReport, "historical alert never becomes an automatic or clickable report");
        check(replay.stream(s -> { s.replay = s.replayProfile = true; s.z += 5; }) == null,
            "Replay seek clears old motion and score before detecting");
        close(0, metric(replay, "violations"), "seek does not retain a high accumulated score");
        Harness live = new Harness(); live.cfg.autoReport = true; live.cfg.intervalSeconds = 20;
        for (int i = 0; i < 5; i++) live.stream(SAME);
        a = live.stream(SAME);
        scaffold(a, "horizontal", "live weighted alert enters the existing delivery policy");
        check(a.autoReport && "/wdr BridgeFixture".equals(a.reportCommand), "opt-in live reporting remains unchanged");
        live.engine.clearEvidence();
        for (int i = 0; i < 7; i++) check(live.stream(SAME) == null,
            "rebuilding a weighted score cannot bypass existing current-world alert/report cooldown");
    }

    private static Object state(Harness h) throws Exception {
        Field players = AdninAnticheatCore.Engine.class.getDeclaredField("players"); players.setAccessible(true);
        Object player = ((Map<?, ?>) players.get(h.engine)).get(h.id);
        if (player == null) return null;
        Field scaffold = player.getClass().getDeclaredField("scaffold"); scaffold.setAccessible(true);
        return scaffold.get(player);
    }
    private static double metric(Harness h, String name) throws Exception {
        Object state = state(h); if (state == null) return 0;
        Field field = state.getClass().getDeclaredField(name); field.setAccessible(true);
        return ((Number) field.get(state)).doubleValue();
    }
    private static void zero(Harness h, String message) throws Exception { close(0, metric(h, "violations"), message); }
    private static void kind(Harness h, String expected, String message) throws Exception {
        Object state = state(h); check(state != null, message + " has actor state");
        Field type = state.getClass().getDeclaredField("lastType"); type.setAccessible(true);
        check(expected.equals(type.get(state)) && metric(h, "lastIncrement") > 0, message);
    }
    private static void scaffold(AdninAnticheatCore.Alert alert, String type, String message) {
        check(alert != null && alert.check == AdninAnticheatCore.Check.SCAFFOLD
            && type.equals(alert.scaffoldType) && alert.scaffoldViolationLevel >= 10, message);
    }
    private static void close(double expected, double actual, String message) {
        check(Math.abs(expected - actual) <= 1e-8, message + " (expected " + expected + ", got " + actual + ")");
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
