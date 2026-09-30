import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Owned Mellow Eagle timing and numerical regressions; no Minecraft, IO or packets. */
public final class AdninEaglePortTest {
    private static int checks;
    private static final UUID FIRST = UUID.fromString("12345678-1234-4234-9234-123456789abc");
    private static final UUID SECOND = UUID.fromString("22345678-1234-4234-9234-123456789abc");
    private static final Consumer<AdninAnticheatCore.Snapshot> SAME = s -> { };

    private static final class Harness {
        final AdninAnticheatCore.Engine engine;
        final AdninAnticheatCore.Settings cfg = new AdninAnticheatCore.Settings();
        UUID id = FIRST;
        Object identity = new Object();
        int tick, sampleTick, alerts, reports;
        long now = 10000;
        float pitch = 90, yaw = 180;
        double dx, dz = .1;
        boolean replay;
        AdninAnticheatCore.Snapshot last;
        AdninAnticheatCore.Alert lastAlert;
        Harness() { this(new AdninAnticheatCore.Engine()); }
        Harness(AdninAnticheatCore.Engine engine) {
            this.engine = engine;
            cfg.enabled = true; cfg.intervalSeconds = 0;
            cfg.autoBlock = cfg.noFall = cfg.noSlow = cfg.scaffold = false;
            step(false, false, SAME);
        }
        AdninAnticheatCore.Alert step(boolean sneak, boolean swing, Consumer<AdninAnticheatCore.Snapshot> change) {
            AdninAnticheatCore.Snapshot s = new AdninAnticheatCore.Snapshot();
            s.name = "EagleFixture"; s.currentTab = s.realProfile = true;
            s.tick = ++tick; s.sampleTick = ++sampleTick; s.now = now += 50; s.lastPacket = now;
            s.serverY = 3200; s.pitch = pitch; s.yaw = yaw; s.deltaX = dx; s.deltaZ = dz;
            s.holdingBlock = true; s.sneaking = sneak; s.swinging = swing;
            s.replay = s.replayProfile = replay;
            change.accept(s); last = s;
            AdninAnticheatCore.Alert a = engine.sample(id, identity, s, cfg);
            if (a != null) { alerts++; lastAlert = a; if (a.autoReport) reports++; }
            return a;
        }
        void cycle(int duration, int swingOffset) { cycle(duration, swingOffset, SAME); }
        void cycle(int duration, int swingOffset, Consumer<AdninAnticheatCore.Snapshot> changes) {
            for (int i = 0; i <= duration + 3; i++) {
                step(i < duration, i >= duration + swingOffset && i < duration + swingOffset + 2, changes);
            }
        }
        void idle(int count) { for (int i = 0; i < count; i++) step(false, false, SAME); }
    }

    public static void main(String[] args) throws Exception {
        oldModelDoesNotSurvive();
        timingBoundaries();
        numericalWeights();
        consistencyAndHistory();
        oneEventOneContribution();
        patternWindow();
        lifecycle();
        admissionAndDelivery();
        checkIsolation();
        System.out.println("AdninEaglePortTest: " + checks + " checks passed; Mellow Eagle 1/2-tick release timing,"
            + " fresh unique events, 15-tick window, exact weighted VL/variance, resets and existing admission/output policy; offline");
    }

    private static void oldModelDoesNotSurvive() {
        Harness h = new Harness();
        for (int cycle = 0; cycle < 6; cycle++) {
            h.step(true, true, s -> s.swingProgress = 1);
            h.step(false, false, SAME);
        }
        check(h.alerts == 0, "Former simultaneous sneak-start/swing triples are not Mellow release/swing evidence");
    }

    private static void timingBoundaries() throws Exception {
        for (int duration : new int[]{1, 2}) for (int offset : new int[]{0, 1}) {
            Harness h = new Harness(); h.cycle(duration, offset);
            close(0, metric(h, "violations"), "One valid release is not a pair");
            close(1, metric(h, "patterns"), "One fresh release contributes exactly one pattern");
            h.cycle(duration, offset);
            close(offset == 0 ? 8.694 : 6.21, metric(h, "violations"), "Two events add one weighted Mellow increment");
            check(h.alerts == 0, "First weighted increment stays below imported VL ten");
            h.cycle(duration, offset); h.cycle(duration, offset);
            check(h.alerts == 1 && h.lastAlert.check == AdninAnticheatCore.Check.LEGIT_SCAFFOLD,
                "Fourth valid event crosses VL ten under the existing Legit Scaffold slot");
        }
        for (int duration : new int[]{0, 3, 4, 8}) {
            Harness h = new Harness(); for (int i = 0; i < 6; i++) h.cycle(duration, 0);
            close(0, metric(h, "violations"), "Only observed one/two-tick crouches qualify");
            check(h.alerts == 0, "Rejected duration does not alert");
        }
        for (int offset : new int[]{-2, -1, 2, 3}) {
            Harness h = new Harness(); for (int i = 0; i < 6; i++) h.cycle(2, offset);
            close(0, metric(h, "violations"), "Swing start must be release tick or the next tick");
        }
        for (float pitch : new float[]{0, 50, 69.999f}) {
            Harness h = new Harness(); h.pitch = pitch;
            for (int i = 0; i < 6; i++) h.cycle(1, 0);
            close(0, metric(h, "violations"), "Pitch below seventy cannot qualify");
        }
        Harness block = new Harness();
        for (int i = 0; i < 6; i++) block.cycle(1, 0, s -> s.holdingBlock = false);
        close(0, metric(block, "violations"), "A held block is required at the event");
        Harness phase = new Harness();
        for (int i = 0; i < 6; i++) phase.cycle(1, 0, s -> { s.swinging = false; s.swingProgress = 1; });
        close(0, metric(phase, "violations"), "Animation integer does not replace an observed swing-start edge");
    }

    private static Harness pair(float pitch, float yaw, double dx, double dz, int offset) {
        Harness h = new Harness(); h.pitch = pitch; h.yaw = yaw; h.dx = dx; h.dz = dz;
        h.cycle(1, offset); h.cycle(1, offset); return h;
    }

    private static void numericalWeights() throws Exception {
        close(3.22, metric(pair(70, 0, 0, .1, 0), "violations"), "Base two, exact-tick swing 1.4 and first consecutive 1.15");
        close(2.3, metric(pair(70, 0, 0, .1, 1), "violations"), "Next-tick swing has no exact-tick bonus");
        close(3.22, metric(pair(84.999f, 0, 0, .1, 0), "violations"), "Pitch below 85 has weight one");
        close(4.83, metric(pair(85, 0, 0, .1, 0), "violations"), "Pitch 85 enables exact 1.5 weight");
        close(3.22, metric(pair(70, 89.999f, 0, .1, 0), "violations"), "Angle below 90 has no backwards bonus");
        close(4.83, metric(pair(70, 90, 0, .1, 0), "violations"), "Angle 90 enables 1.5 backwards weight");
        close(4.83, metric(pair(70, 159.999f, 0, .1, 0), "violations"), "Angle below 160 keeps normal backwards weight");
        close(5.796, metric(pair(70, 160, 0, .1, 0), "violations"), "Angle 160 enables 1.8 direct-backwards weight");
        for (float yaw : new float[]{180, -180, 540, -540})
            close(5.796, metric(pair(70, yaw, 0, .1, 0), "violations"), "Wrapped angles preserve original backwards weighting");
        close(5.796, metric(pair(70, 90, .1, 0, 0), "violations"), "Movement atan2 uses negative X with Z");
        close(3.22, metric(pair(70, 180, 0, 0, 0), "violations"), "Stationary actors do not invent backwards direction");
        close(3.22, metric(pair(70, 180, -0.0, -0.0, 0), "violations"), "Signed zero also cannot invent travel direction");
        Harness cap = new Harness(); cap.pitch = 70; cap.yaw = 0;
        cap.cycle(1, 0); cap.step(true, false, SAME);
        setMetric(cap, "consecutive", 99);
        cap.step(false, true, SAME);
        close(4.9, metric(cap, "violations"), "Consecutive multiplier caps at five increments: 1.75");
        cap.step(false, false, SAME);
        close(99, metric(cap, "consecutive"), "A nonflagging tick decays consecutive evidence by one");
        Harness threshold = new Harness(); threshold.pitch = 70; threshold.yaw = 0;
        threshold.cycle(1, 0); threshold.step(true, false, SAME);
        setMetric(threshold, "violations", 10.0 - 3.22);
        AdninAnticheatCore.Alert a = threshold.step(false, true, SAME);
        check(a != null, "Exactly VL ten satisfies the imported threshold");
        Harness below = new Harness(); below.pitch = 70; below.yaw = 0;
        below.cycle(1, 0); below.step(true, false, SAME);
        setMetric(below, "violations", 10.0 - 3.22 - 0.000001);
        check(below.step(false, true, SAME) == null, "VL immediately below ten cannot alert");
    }

    private static void consistencyAndHistory() throws Exception {
        Harness equal = new Harness(); for (int i = 0; i < 4; i++) equal.cycle(1, 0);
        close(21.735, metric(equal, "violations"), "Three equal recent durations give variance zero and consistency weight 1.5");
        check("backwards-bridging".equals(stateText(equal, "lastType")), "Backwards type takes upstream precedence");
        Harness alternating = new Harness(); for (int d : new int[]{1, 2, 1, 2}) alternating.cycle(d, 0);
        close(21.4935, metric(alternating, "violations"), "Latest [2,1,2] durations produce variance 2/9 and exact combined VL");
        Harness noisy = new Harness(); noisy.cycle(3, 0); noisy.cycle(1, 0); noisy.cycle(1, 0);
        close(12.075, metric(noisy, "violations"), "Rejected long crouches still contribute to the original three-duration consistency history");
        Harness mixed = new Harness(); mixed.yaw = 0; mixed.cycle(3, 0); mixed.cycle(1, 0); mixed.cycle(1, 0);
        check("mechanical-pattern".equals(stateText(mixed, "lastType")), "Nonbackwards inconsistent histories use mechanical-pattern");
        mixed.cycle(1, 0); mixed.cycle(1, 0);
        check("consistent-pattern".equals(stateText(mixed, "lastType")), "Three latest short durations replace older irregular history");
        Object state = state(mixed); Field f = state.getClass().getDeclaredField("durations"); f.setAccessible(true);
        check(((int[]) f.get(state)).length == 3 && metric(mixed, "durationCount") == 3,
            "History has a fixed three-integer capacity matching the upstream limit(3)");
        Harness variance = new Harness(); variance.cycle(10, 0); variance.cycle(1, 0); variance.cycle(1, 0);
        close(8.694, metric(variance, "violations"), "Large variance clamps consistency to zero without negative weights");
    }

    private static void oneEventOneContribution() throws Exception {
        Harness h = new Harness(); h.step(true, false, SAME); h.step(false, true, SAME);
        close(1, metric(h, "patterns"), "Release and new swing contribute one event");
        for (int i = 0; i < 100; i++) {
            check(h.engine.sample(h.id, h.identity, h.last, h.cfg) == null, "Duplicate same-tick callback cannot repeat an event");
        }
        for (int i = 0; i < 100; i++) h.step(false, true, SAME);
        close(0, metric(h, "violations"), "Holding animation with old event timestamps never accumulates VL");
        close(0, metric(h, "patterns"), "Unused event expires after the original 15-tick window");
        Harness stale = new Harness(); stale.step(true, false, SAME);
        stale.step(false, true, s -> s.pitch = 0); stale.step(false, true, s -> s.pitch = 0);
        for (int i = 0; i < 10; i++) stale.step(false, true, SAME);
        close(0, metric(stale, "patterns"), "Looking down later cannot activate an expired release/swing pair");
        Harness first = new Harness(); first.identity = new Object();
        first.step(true, true, SAME); first.step(false, true, SAME); first.idle(3);
        close(0, metric(first, "patterns"), "Joining during a held sneak or swing cannot infer an unobserved start");
        Harness firstSneak = new Harness(); firstSneak.identity = new Object();
        firstSneak.step(true, false, SAME); firstSneak.step(false, true, SAME); firstSneak.idle(2);
        close(0, metric(firstSneak, "patterns"), "An initially crouching actor has no observed crouch duration");
        Harness firstSwing = new Harness(); firstSwing.identity = new Object();
        firstSwing.step(false, true, SAME); firstSwing.step(true, true, SAME); firstSwing.step(false, true, SAME);
        close(0, metric(firstSwing, "patterns"), "An initially swinging actor has no observed swing-start tick");
        Harness continuing = new Harness(); continuing.step(false, true, SAME);
        for (int i = 0; i < 8; i++) continuing.cycle(1, 0, s -> { s.swinging = true; s.swingProgress = iValue(s.tick); });
        close(0, metric(continuing, "violations"), "Continuous swing progress wraps do not invent Mellow rising swing edges");
    }

    private static int iValue(int tick) { return tick % 6; }

    private static void patternWindow() throws Exception {
        for (int gap : new int[]{14, 15, 16}) {
            Harness h = new Harness(); h.cycle(1, 0);
            int elapsed = h.sampleTick - (int) metric(h, "lastPatternTick");
            h.idle(gap - elapsed - 2);
            h.step(true, false, SAME); h.step(false, true, SAME);
            close(gap <= 15 ? 8.694 : 0, metric(h, "violations"), "Pattern gap boundary is inclusive at 15 ticks");
            close(gap <= 15 ? 0 : 1, metric(h, "patterns"), "Expired pattern starts a new pair");
        }
        Harness h = new Harness(); h.cycle(1, 0); h.cycle(1, 0);
        double scored = metric(h, "violations"); h.idle(100);
        close(scored, metric(h, "violations"), "Mellow manager VL has no hidden time decay");
    }

    private static void lifecycle() throws Exception {
        for (int reset = 0; reset < 10; reset++) {
            Harness h = new Harness(); h.cycle(1, 0); h.cycle(1, 0);
            check(metric(h, "violations") > 0, "Fixture has earlier Eagle VL before reset");
            if (reset == 0) h.tick += 2;
            if (reset == 1) h.now += 251;
            if (reset == 2) h.identity = new Object();
            if (reset == 3) h.tick = 0;
            if (reset == 4) h.now -= 500;
            if (reset == 5) h.engine.clearEvidence();
            if (reset == 6) h.engine.retainPlayers(Collections.<UUID>emptySet());
            if (reset == 7) { h.cfg.legitScaffold = false; h.step(false, false, SAME); h.cfg.legitScaffold = true; }
            if (reset == 8) { h.cfg.enabled = false; h.step(false, false, SAME); h.cfg.enabled = true; }
            if (reset == 9) h.sampleTick += 2;
            h.step(false, false, SAME);
            close(0, metric(h, "violations"), "Discontinuity or lifecycle change retires Eagle VL: " + reset);
            close(0, metric(h, "patterns"), "Discontinuity or lifecycle change retires pending release: " + reset);
        }
        for (float invalid : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            Harness h = new Harness(); h.cycle(1, 0); h.cycle(1, 0);
            h.step(false, false, s -> s.yaw = invalid);
            check(state(h) == null, "Nonfinite yaw clears only Eagle evidence");
            h.step(false, false, SAME); close(0, metric(h, "violations"), "A finite yaw resumes from a fresh baseline");
        }
        Harness replay = new Harness(); replay.replay = true; replay.cycle(1, 0); replay.cycle(1, 0);
        replay.step(false, false, s -> s.deltaX = 5);
        close(0, metric(replay, "violations"), "Replay seek cannot retain old weighted Eagle evidence");
        Harness one = new Harness(); Harness two = new Harness(one.engine); two.id = SECOND; two.step(false, false, SAME);
        one.cycle(1, 0); one.cycle(1, 0);
        two.cycle(1, 0);
        close(0, metric(two, "violations"), "A different actor cannot borrow another actor's events or VL");
    }

    private static void admissionAndDelivery() throws Exception {
        for (int gate = 0; gate < 6; gate++) {
            Harness h = new Harness(); final int which = gate;
            h.cfg.ignoreTeammates = true;
            for (int i = 0; i < 8; i++) h.cycle(1, 0, s -> {
                if (which == 0) s.teammate = true;
                if (which == 1) s.self = true;
                if (which == 2) s.dead = true;
                if (which == 3) s.currentTab = false;
                if (which == 4) s.realProfile = false;
                if (which == 5) { s.replay = true; s.replayProfile = false; }
            });
            check(h.alerts == 0 && h.engine.trackedPlayers() == 0, "Existing actor filters precede Eagle: " + gate);
        }
        Harness replay = new Harness(); replay.replay = true; replay.cfg.autoReport = true;
        replay.step(false, false, SAME);
        for (int i = 0; i < 4; i++) replay.cycle(1, 0, s -> { s.currentTab = false; s.realProfile = false; });
        check(replay.alerts == 1 && replay.reports == 0 && replay.lastAlert.reportCommand.isEmpty(),
            "Roster-admitted Replay bots detect under recorded identity without historical WDR");
        Harness nick = new Harness(); nick.id = UUID.fromString("12345678-1234-1234-9234-123456789abc");
        nick.step(false, false, SAME); for (int i = 0; i < 4; i++) nick.cycle(1, 0, s -> s.name = "NickFixture");
        check(nick.alerts == 1 && nick.lastAlert.reportCommand.equals("/wdr NickFixture"), "Current admitted Nick follows existing identity/report policy");
        Harness cooldown = new Harness(); cooldown.cfg.intervalSeconds = 20; cooldown.cfg.autoReport = true;
        cooldown.step(false, false, SAME);
        for (int i = 0; i < 20; i++) cooldown.cycle(1, 0);
        check(cooldown.alerts == 1 && cooldown.reports == 1, "Weighted increments cannot bypass existing alert/report cooldown");
        cooldown.identity = new Object(); cooldown.step(false, false, SAME);
        for (int i = 0; i < 4; i++) cooldown.cycle(1, 0);
        check(cooldown.alerts == 1 && cooldown.reports == 1, "Entity/evidence resets cannot bypass report cooldown");
        cooldown.idle(400); cooldown.cycle(1, 0); cooldown.cycle(1, 0);
        check(cooldown.alerts == 2 && cooldown.reports == 2, "Fresh event pair after the configured cooldown can alert again");
    }

    private static void checkIsolation() throws Exception {
        Harness h = new Harness(); h.cfg.autoBlock = true;
        for (int i = 0; i < 10; i++) h.step(false, true, s -> { s.blocking = true; s.yaw = Float.NaN; });
        check(h.alerts == 1 && h.lastAlert.check == AdninAnticheatCore.Check.AUTO_BLOCK,
            "Invalid Eagle rotation cannot reset independent Autoblock evidence");
        Harness disabled = new Harness(); disabled.cfg.legitScaffold = false;
        disabled.step(false, false, SAME);
        check(state(disabled) == null, "Disabled Eagle allocates no per-actor duration state");
        disabled.cfg.scaffold = true;
        for (int i = 0; i < 6; i++) disabled.step(false, true, s -> {
            s.x = s.tick * .3; s.y = s.tick * .1 + .001 * s.tick * s.tick; s.yaw = 90;
        });
        check(disabled.lastAlert != null && disabled.lastAlert.check == AdninAnticheatCore.Check.SCAFFOLD,
            "Existing Mellow Scaffold remains independent while Eagle is disabled");
    }

    private static Object state(Harness h) throws Exception {
        Field players = AdninAnticheatCore.Engine.class.getDeclaredField("players"); players.setAccessible(true);
        Object player = ((Map<?, ?>) players.get(h.engine)).get(h.id);
        if (player == null) return null;
        Field eagle = player.getClass().getDeclaredField("eagle"); eagle.setAccessible(true); return eagle.get(player);
    }
    private static double metric(Harness h, String name) throws Exception {
        Object state = state(h); if (state == null) return 0;
        Field f = state.getClass().getDeclaredField(name); f.setAccessible(true); return ((Number) f.get(state)).doubleValue();
    }
    private static String stateText(Harness h, String name) throws Exception {
        Object state = state(h); Field f = state.getClass().getDeclaredField(name); f.setAccessible(true); return (String) f.get(state);
    }
    private static void setMetric(Harness h, String name, Number value) throws Exception {
        Object state = state(h); Field f = state.getClass().getDeclaredField(name); f.setAccessible(true); f.set(state, value);
    }
    private static void close(double expected, double actual, String why) {
        check(Math.abs(expected - actual) < 0.00000001, why + ": expected " + expected + ", got " + actual);
    }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
