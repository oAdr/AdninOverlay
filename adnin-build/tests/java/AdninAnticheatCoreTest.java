import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

/** Offline detector regression tests. No Minecraft classes or network are loaded. */
public final class AdninAnticheatCoreTest {
    private static int checks;
    private static final UUID PLAYER = UUID.fromString("12345678-1234-4234-9234-123456789abc");
    private static final Consumer<AdninAnticheatCore.Snapshot> BLOCK = s -> { s.swinging = true; s.blocking = true; };
    private static final Consumer<AdninAnticheatCore.Snapshot> SLOW = s -> { s.sprinting = true; s.usingItem = true; s.deltaX = 0.08; };

    private static final class Harness {
        final AdninAnticheatCore.Engine engine = new AdninAnticheatCore.Engine();
        final AdninAnticheatCore.Settings cfg = new AdninAnticheatCore.Settings();
        UUID id = PLAYER;
        Object identity = new Object();
        int tick;
        long time = 10000;
        Harness() { cfg.enabled = true; cfg.intervalSeconds = 0; }
        AdninAnticheatCore.Snapshot snapshot() {
            AdninAnticheatCore.Snapshot s = new AdninAnticheatCore.Snapshot();
            s.tick = tick; s.now = time; s.lastPacket = time;
            s.name = "PlayerA"; s.currentTab = true; s.realProfile = true;
            s.serverY = 3200; s.distanceToGround = 5;
            return s;
        }
        AdninAnticheatCore.Alert step(Consumer<AdninAnticheatCore.Snapshot> modify) {
            tick++; time += 50;
            AdninAnticheatCore.Snapshot s = snapshot();
            modify.accept(s);
            return engine.sample(id, identity, s, cfg);
        }
        AdninAnticheatCore.Alert block(int count) {
            AdninAnticheatCore.Alert result = null;
            for (int i = 0; i < count; i++) result = step(BLOCK);
            return result;
        }
    }

    public static void main(String[] args) {
        thresholds();
        legitScaffoldCycles();
        tickBoundaries();
        scaffold();
        noFall();
        policy();
        cooldowns();
        historyRetention();
        invalidSamples();
        replayDiscontinuities();
        check(checks >= 150, "Substantial boundary coverage ran");
        System.out.println("AdninAnticheatCoreTest: " + checks + " checks passed");
    }

    private static void thresholds() {
        Harness h = new Harness();
        for (int i = 1; i <= 9; i++) check(h.step(BLOCK) == null, "Autoblock needs ten game ticks");
        mode(h.step(BLOCK), AdninAnticheatCore.Check.AUTO_BLOCK, "Autoblock tick ten");
        check(h.step(s -> {}) == null, "Releasing block clears streak");
        for (int i = 1; i <= 9; i++) check(h.step(BLOCK) == null, "Autoblock restarts after release");
        mode(h.step(BLOCK), AdninAnticheatCore.Check.AUTO_BLOCK, "Restarted Autoblock threshold");
        h = new Harness();
        for (int i = 1; i <= 10; i++) check(h.step(SLOW) == null, "NoSlow needs eleven ticks");
        mode(h.step(SLOW), AdninAnticheatCore.Check.NO_SLOW, "NoSlow at exact tick eleven and speed .08");
        check(h.step(SLOW) == null, "NoSlow does not repeat at tick twelve");
        h = new Harness();
        for (int i = 1; i <= 15; i++) check(h.step(s -> { SLOW.accept(s); s.deltaX = 0.079; }) == null,
                "NoSlow speed below threshold does not flag");
        h = new Harness();
        check(h.step(s -> { s.pitch = 70; s.holdingBlock = true; }) == null, "Legit scaffold baseline");
        for (int i = 0; i < 3; i++) {
            final boolean last = i == 2;
            AdninAnticheatCore.Alert a = h.step(s -> {
                s.pitch = 70; s.holdingBlock = true; s.swinging = true;
                s.swingProgress = 1; s.sneaking = true;
            });
            if (last) mode(a, AdninAnticheatCore.Check.LEGIT_SCAFFOLD, "Third exact sneak/place transition flags");
            else check(a == null, "Fewer than three sneak/place transitions");
            if (!last) check(h.step(s -> { s.pitch = 70; s.holdingBlock = true; }) == null,
                    "Between placements does not create a transition");
        }
    }

    private static Harness legitHarness() {
        Harness h = new Harness();
        h.cfg.autoBlock = h.cfg.noFall = h.cfg.noSlow = h.cfg.scaffold = false;
        check(legitStep(h, 0, false, false) == null, "Legit baseline has no transition");
        return h;
    }

    private static AdninAnticheatCore.Alert legitStep(Harness h, int phase, boolean swinging, boolean sneak) {
        return h.step(s -> {
            s.pitch = 70; s.holdingBlock = true; s.swinging = swinging;
            s.swingProgress = phase; s.sneaking = sneak;
        });
    }

    /** Full animation plus release; edgeOffset is relative to the swing start. */
    private static int legitCycle(Harness h, int firstPhase, int edgeOffset) {
        int alerts = 0;
        for (int offset = -2; offset < 7; offset++) {
            AdninAnticheatCore.Alert a = legitStep(h, firstPhase + Math.max(0, offset),
                    offset >= 0 && offset < 6, offset >= edgeOffset && offset <= 3);
            if (a != null) {
                mode(a, AdninAnticheatCore.Check.LEGIT_SCAFFOLD, "Only Legit scaffold matches this cycle");
                alerts++;
            }
        }
        return alerts;
    }

    private static void legitScaffoldCycles() {
        for (int firstPhase : new int[]{-1, 0, 1, 2}) {
            for (int edgeOffset : new int[]{-1, 0, 1}) {
                Harness h = legitHarness();
                check(legitCycle(h, firstPhase, edgeOffset) == 0, "First correlated cycle is insufficient");
                check(legitCycle(h, firstPhase, edgeOffset) == 0, "Second correlated cycle is insufficient");
                check(legitCycle(h, firstPhase, edgeOffset) == 1,
                        "Three cycles flag exactly once regardless of initial phase and adjacent edge timing");
                check(legitCycle(h, firstPhase, edgeOffset) == 1,
                        "Zero cooldown still alerts only on a new matched cycle");
            }
        }
        for (int badOffset : new int[]{-2, 2, 3, 4}) {
            Harness h = legitHarness();
            for (int cycle = 0; cycle < 6; cycle++) check(legitCycle(h, 0, badOffset) == 0,
                    "Uncorrelated sneak edge cannot accumulate: " + badOffset);
        }
        Harness h = legitHarness();
        for (int i = 0; i < 48; i++) {
            int phase = i % 8;
            check(legitStep(h, phase, phase < 6, true) == null,
                    "Holding sneak through multiple placements is not a fresh edge");
        }
        h = legitHarness();
        for (int i = 0; i < 32; i++) check(legitStep(h, 1, true, i % 2 == 0) == null,
                "Repeated animation phase and sneak toggles do not invent more swing cycles");
        h = legitHarness();
        for (int i = 0; i < 32; i++) check(legitStep(h, 1, false, i % 2 == 0) == null,
                "Animation integer alone cannot represent a placement swing");
        h = legitHarness();
        for (int cycle = 0; cycle < 3; cycle++) {
            int alerts = 0;
            for (int phase = 0; phase < 6; phase++) {
                if (legitStep(h, phase, true, phase <= 2) != null) alerts++;
            }
            check(alerts == (cycle == 2 ? 1 : 0), "Swing progress wrap detects one new continuous swing cycle");
        }
        h = legitHarness();
        legitCycle(h, 0, 0); legitCycle(h, 0, 0);
        check(legitCycle(h, 0, 2) == 0, "An unmatched swing resets two earlier matches");
        check(legitCycle(h, 0, 0) == 0 && legitCycle(h, 0, 0) == 0,
                "After an unmatched swing two matches remain insufficient");
        check(legitCycle(h, 0, 0) == 1, "Three fresh matches recover after an unmatched swing");
        for (int reset = 0; reset < 4; reset++) {
            h = legitHarness(); legitCycle(h, 0, 0); legitCycle(h, 0, 0);
            if (reset == 0) check(h.step(s -> { s.pitch = 69.99f; s.holdingBlock = true; }) == null,
                    "Pitch below seventy invalidates matching evidence");
            if (reset == 1) check(h.step(s -> s.pitch = 70) == null,
                    "Releasing the held block invalidates matching evidence");
            if (reset == 2) h.tick += 2;
            if (reset == 3) h.time += 251;
            check(legitCycle(h, 0, 0) == 0 && legitCycle(h, 0, 0) == 0,
                    "Posture loss and missing or delayed ticks require fresh matching cycles");
            check(legitCycle(h, 0, 0) == 1, "Three new cycles recover after reset");
        }
        h = legitHarness();
        legitCycle(h, 0, 0); legitCycle(h, 0, 0);
        check(legitStep(h, 0, true, true) != null, "Third cycle creates its one alert");
        for (int callback = 0; callback < 100; callback++) {
            AdninAnticheatCore.Snapshot s = h.snapshot();
            s.pitch = 70; s.holdingBlock = true; s.swinging = true; s.sneaking = true;
            check(h.engine.sample(h.id, h.identity, s, h.cfg) == null,
                    "Duplicate callback cannot reuse the third swing or emit another alert");
        }
        for (int phase = 1; phase < 6; phase++) check(legitStep(h, phase, true, true) == null,
                "Remaining animation phases cannot reuse a matched sneak edge");
    }

    private static void tickBoundaries() {
        Harness h = new Harness();
        h.block(1);
        for (int frame = 0; frame < 1000; frame++) {
            AdninAnticheatCore.Snapshot s = h.snapshot(); BLOCK.accept(s);
            check(h.engine.sample(h.id, h.identity, s, h.cfg) == null, "Repeated render frame cannot increment counters");
        }
        check(h.block(8) == null, "Only nine distinct ticks after duplicate renders");
        mode(h.block(1), AdninAnticheatCore.Check.AUTO_BLOCK, "Tenth distinct tick flags");
        h = new Harness(); h.block(9); h.tick += 2;
        check(h.block(1) == null, "Missing entity tick resets evidence");
        check(h.block(8) == null, "Gap does not extrapolate suspicious ticks");
        mode(h.block(1), AdninAnticheatCore.Check.AUTO_BLOCK, "Fresh ten tick streak after gap");
        h = new Harness(); h.block(9); h.time += 500;
        check(h.block(1) == null, "Long sample stall resets evidence even if entity tick advanced once");
        h = new Harness(); h.block(9); h.identity = new Object();
        check(h.block(1) == null, "Entity replacement with same UUID resets evidence");
        h = new Harness(); h.block(9); h.tick = 0;
        check(h.block(1) == null, "Entity tick rollback resets evidence");
        h = new Harness(); h.block(9); h.time -= 200;
        check(h.block(1) == null, "Clock rollback resets evidence");
        h = new Harness(); h.block(9); h.engine.retainPlayers(Collections.<UUID>emptySet());
        check(h.engine.trackedPlayers() == 0, "Leaving current world roster removes samples");
        check(h.block(1) == null, "Returning entity must start new evidence");
        h = new Harness(); h.block(9); h.engine.reset();
        check(h.block(1) == null, "World/disconnect reset clears accumulated streak");
        h = new Harness(); h.block(9); h.cfg.enabled = false;
        check(h.block(1) == null && h.engine.trackedPlayers() == 0, "Disabled state is empty");
        h.cfg.enabled = true;
        check(h.block(1) == null, "Reenabled detector cannot reuse prior evidence");
        h = new Harness(); h.block(9); h.cfg.autoBlock = false;
        check(h.block(1) == null, "Individual check switch applies immediately");
        h.cfg.autoBlock = true;
        check(h.block(1) == null, "Individual check reenable starts fresh");
    }

    private static void scaffold() {
        Consumer<AdninAnticheatCore.Snapshot> bridge = AdninAnticheatCoreTest::mellowBridge;
        Harness h = new Harness();
        for (int i = 1; i <= 5; i++) check(h.step(bridge) == null, "Scaffold needs five real positions then the Mellow VL threshold");
        AdninAnticheatCore.Alert detected = h.step(bridge);
        mode(detected, AdninAnticheatCore.Check.SCAFFOLD, "Two weighted horizontal matches cross VL ten");
        check("horizontal".equals(detected.scaffoldType) && detected.scaffoldViolationLevel >= 10.0,
                "Scaffold retains the actual type and accumulated weighted score");
        h = new Harness();
        for (int i = 1; i <= 40; i++) check(h.step(s -> { bridge.accept(s); s.x = s.tick * .1; }) == null,
                "Mellow Scaffold excludes low horizontal speed using sampled positions");
        h = new Harness();
        for (int i = 0; i < 40; i++) {
            h.tick++;
            check(h.step(bridge) == null, "Missing every other tick cannot be invented as Scaffold evidence");
        }
        h = new Harness();
        for (int i = 0; i < 35; i++) check(h.step(s -> { bridge.accept(s); s.pitch = 50; }) == null,
                "Mellow Scaffold requires pitch strictly above fifty");
        h = new Harness();
        for (int i = 0; i < 35; i++) check(h.step(s -> { bridge.accept(s); s.holdingBlock = false; }) == null,
                "Scaffold still requires a held block");
    }

    private static void mellowBridge(AdninAnticheatCore.Snapshot s) {
        s.deltaX = .3; s.deltaY = .1 + .001 * (2 * s.tick - 1);
        s.x = .3 * s.tick; s.y = .1 * s.tick + .001 * s.tick * s.tick;
        s.pitch = 90; s.yaw = 90; s.holdingBlock = true; s.swinging = true;
    }

    private static Harness baseline(int x, int y, int z, long packetAge) {
        Harness h = new Harness();
        check(h.step(s -> { s.serverX = x; s.serverY = y; s.serverZ = z; s.lastPacket -= packetAge; }) == null,
                "NoFall first sample cannot flag");
        return h;
    }

    private static void noFall() {
        Harness h = baseline(0, 201, 0, 0);
        mode(h.step(s -> s.serverY = 41), AdninAnticheatCore.Check.NO_FALL, "NoFall exact five block fractional drop");
        h = baseline(0, 200, 0, 0);
        check(h.step(s -> s.serverY = 41) == null, "Fractional 4.96875 drop must not round up to five");
        h = baseline(-1, 3200, 0, 0);
        check(h.step(s -> { s.serverX = 320; s.serverY = 3000; }) == null,
                "Fractional horizontal delta above ten cannot truncate into range");
        h = baseline(0, 3200, 0, 0);
        mode(h.step(s -> { s.serverX = 320; s.serverY = 1920; }), AdninAnticheatCore.Check.NO_FALL,
                "Exactly forty down and ten sideways remain inclusive");
        h = baseline(0, 3200, 0, 0);
        check(h.step(s -> s.serverY = 1919) == null, "Above forty drop is excluded");
        h = baseline(0, 3200, 0, 0);
        check(h.step(s -> s.serverY = 3400) == null, "Rising position cannot be NoFall");
        for (int exclusion = 0; exclusion < 7; exclusion++) {
            h = baseline(0, 3200, 0, 0);
            final int flag = exclusion;
            check(h.step(s -> {
                s.serverY = 3000;
                if (flag == 0) s.flying = true;
                if (flag == 1) s.onLadder = true;
                if (flag == 2) s.inWater = true;
                if (flag == 3) s.inLava = true;
                if (flag == 4) s.overVoid = true;
                if (flag == 5) s.distanceToGround = 3;
                if (flag == 6) s.lastPacket -= 151;
            }) == null, "NoFall environment/packet exemption " + flag);
        }
        h = baseline(0, 3200, 0, 150);
        mode(h.step(s -> { s.serverY = 3000; s.lastPacket -= 150; }), AdninAnticheatCore.Check.NO_FALL,
                "Exactly 150ms packet freshness allowed");
        h = baseline(0, 3200, 0, 151);
        check(h.step(s -> s.serverY = 3000) == null, "First fresh packet after stale sample cannot flag");
        mode(h.step(s -> s.serverY = 2800), AdninAnticheatCore.Check.NO_FALL,
                "Two consecutive fresh samples restore NoFall");
        h = baseline(0, 3200, 0, 0); h.tick += 3;
        check(h.step(s -> s.serverY = 3000) == null, "Missing ticks invalidate NoFall delta");
        h = baseline(0, 3200, 0, 0);
        check(h.step(s -> { s.serverY = 3000; s.lastPacket = s.now + 1; }) == null,
                "Future packet clock is not fresh");
        h = baseline(0, 3200, 0, 0);
        h.tick++; h.time += 50;
        AdninAnticheatCore.Snapshot s = h.snapshot();
        check(!h.engine.noFallCandidate(h.id, h.identity, s, h.cfg), "Stationary player performs no terrain scan");
        s.serverY = 3000;
        check(h.engine.noFallCandidate(h.id, h.identity, s, h.cfg), "Candidate drop requests terrain scan");
        s.lastPacket -= 151;
        check(!h.engine.noFallCandidate(h.id, h.identity, s, h.cfg), "Stale packet never requests terrain scan");
    }

    private static void policy() {
        Harness h = new Harness();
        for (int i = 0; i < 12; i++) check(h.step(s -> { BLOCK.accept(s); s.currentTab = false; }) == null,
                "Non-tab entity excluded");
        check(h.engine.trackedPlayers() == 0, "Non-tab entities consume no state");
        for (int i = 0; i < 12; i++) check(h.step(s -> { BLOCK.accept(s); s.realProfile = false; }) == null,
                "Rejected bot profile excluded");
        h = new Harness(); h.cfg.ignoreTeammates = true;
        for (int i = 0; i < 12; i++) check(h.step(s -> { BLOCK.accept(s); s.teammate = true; }) == null,
                "Teammate exemption precedes accumulation");
        h = new Harness(); h.cfg.ignoredPlayers = "  playera , FRIEND_2, playera, /wdr X, bad-name ";
        check("playera,friend_2".equals(AdninAnticheatCore.normalizeIgnored(h.cfg.ignoredPlayers)),
                "Ignored list validates and canonicalizes comma-separated names");
        for (int i = 0; i < 12; i++) check(h.step(BLOCK) == null, "Ignored friend name case insensitive");
        h = new Harness(); h.cfg.atlasOnly = true; h.cfg.autoReport = true;
        for (int i = 0; i < 12; i++) check(h.step(BLOCK) == null, "Atlas mode excludes ordinary players");
        Consumer<AdninAnticheatCore.Snapshot> suspect = s -> {
            BLOCK.accept(s); s.name = "Suspect\u00a7r"; s.atlasSuspect = true; s.realProfile = false;
        };
        for (int i = 0; i < 9; i++) check(h.step(suspect) == null, "Atlas suspect accumulates real game ticks");
        AdninAnticheatCore.Alert a = h.step(suspect);
        mode(a, AdninAnticheatCore.Check.AUTO_BLOCK, "Current-tab exact Atlas identity allowed");
        check(!a.autoReport && a.reportCommand.isEmpty(), "Atlas has no unsafe WDR side effect");
        check(h.step(s -> { suspect.accept(s); s.currentTab = false; }) == null, "Atlas still requires current Tab");
        check(AdninAnticheatCore.isAtlasSuspect("Suspect\u00a7r"), "Exact Atlas sentinel");
        check(!AdninAnticheatCore.isAtlasSuspect("Suspect"), "Real account named Suspect is not the replay sentinel");
        for (String bad : new String[]{"", "a b", "a\n/pc hi", "\u00a7cPlayerA", "x;y", "01234567890123456", "玩家"})
            check(!AdninAnticheatCore.validPlayerName(bad), "Commands reject malformed names");
        check(AdninAnticheatCore.validPlayerName("Valid_Name123"), "Command accepts valid account name");
        h = new Harness();
        a = h.block(10);
        check(!a.autoReport && "/wdr PlayerA".equals(a.reportCommand), "WDR is clickable but automatic send defaults off");
        h = new Harness(); h.cfg.autoReport = true;
        a = h.block(10);
        check(a.autoReport && "/wdr PlayerA".equals(a.reportCommand), "Opt-in report emits only validated decision");
        replayPolicy();
    }

    private static void replayPolicy() {
        Consumer<AdninAnticheatCore.Snapshot> replayActor = s -> {
            BLOCK.accept(s); s.replay = true; s.replayProfile = true;
            s.currentTab = false; s.realProfile = false;
        };
        Harness h = new Harness(); h.cfg.autoReport = true;
        for (int i = 0; i < 9; i++) check(h.step(replayActor) == null,
                "Replay actor must still satisfy the unchanged detection threshold");
        AdninAnticheatCore.Alert a = h.step(replayActor);
        mode(a, AdninAnticheatCore.Check.AUTO_BLOCK,
                "Identity-roster validated Replay actor need not have a live UUID/profile match");
        check(a.reportCommand.isEmpty() && !a.autoReport, "Replay actor has no WDR button or automatic report");
        h = new Harness(); h.cfg.autoReport = true;
        for (int i = 0; i < 9; i++) h.step(s -> { BLOCK.accept(s); s.replay = s.replayProfile = true; });
        a = h.step(s -> { BLOCK.accept(s); s.replay = s.replayProfile = true; });
        check(a != null && a.reportCommand.isEmpty() && !a.autoReport,
                "Even a regular Tab profile receives no WDR action while viewing Replay");
        h = new Harness();
        for (int i = 0; i < 12; i++) check(h.step(s -> { BLOCK.accept(s); s.replay = true; }) == null,
                "A regular Tab UUID alone cannot bypass the current Replay actor roster");
        for (int exclusion = 0; exclusion < 5; exclusion++) {
            h = new Harness(); final int flag = exclusion;
            for (int i = 0; i < 12; i++) check(h.step(s -> {
                replayActor.accept(s);
                if (flag == 0) s.replay = false;
                if (flag == 1) s.replayProfile = false;
                if (flag == 2) s.self = true;
                if (flag == 3) s.dead = true;
                if (flag == 4) s.name = "Bad Name";
            }) == null, "Replay actor admission remains strict for exemption " + flag);
            check(h.engine.trackedPlayers() == 0, "Rejected replay actor consumes no detector state");
        }
        h = new Harness(); h.cfg.atlasOnly = true;
        for (int i = 0; i < 12; i++) check(h.step(s -> {
            replayActor.accept(s); s.name = "Suspect\u00a7r"; s.atlasSuspect = true;
        }) == null, "Replay fallback cannot bypass Atlas's exact current-Tab policy");
        for (java.lang.reflect.Field field : AdninAnticheatCore.Settings.class.getDeclaredFields())
            check(!field.getName().equals("addEnemies"), "Enemy toggle absent from core settings");
        for (java.lang.reflect.Field field : AdninAnticheatCore.Alert.class.getDeclaredFields())
            check(!field.getName().equals("addedEnemy"), "Enemy addition absent from alert result");
        for (java.lang.reflect.Field field : AdninAnticheatCore.Engine.class.getDeclaredFields())
            check(!field.getName().equals("enemies"), "Enemy collection absent from engine state");
        for (java.lang.reflect.Method method : AdninAnticheatCore.Engine.class.getDeclaredMethods())
            check(!method.getName().equals("isEnemy") && !method.getName().equals("enemies"),
                    "Enemy APIs absent from engine");
    }

    private static void cooldowns() {
        Harness h = new Harness(); h.cfg.intervalSeconds = 20; h.cfg.autoReport = true;
        AdninAnticheatCore.Alert first = h.block(10);
        check(first != null && first.sound && first.autoReport, "Initial alert at configured threshold");
        for (int i = 1; i < 400; i++) check(h.block(1) == null, "Per-player per-check 20 second cooldown");
        AdninAnticheatCore.Alert due = h.block(1);
        check(due != null && due.autoReport, "Exactly 20 seconds permits one report");
        AdninAnticheatCore.Snapshot duplicate = h.snapshot(); BLOCK.accept(duplicate);
        check(h.engine.sample(h.id, h.identity, duplicate, h.cfg) == null, "Repeated hook cannot resend due report");
        h = new Harness(); h.cfg.intervalSeconds = 20; h.block(10); h.tick += 2;
        check(h.block(10) == null, "Missed game ticks preserve alert cooldown after evidence rebuild");
        h.time += 1000;
        check(h.block(10) == null, "Sampling stall preserves alert cooldown");
        h = new Harness(); h.cfg.intervalSeconds = 20; h.block(10); h.time -= 1000;
        check(h.block(10) == null, "Clock rollback cannot bypass a recorded alert cooldown");
        h = new Harness(); h.cfg.intervalSeconds = 20; h.block(10);
        mode(h.step(s -> s.serverY = 3000), AdninAnticheatCore.Check.NO_FALL,
                "Different check has independent cooldown for the same player");
        h.id = UUID.fromString("22345678-1234-4234-9234-123456789abc");
        h.identity = new Object();
        mode(h.block(10), AdninAnticheatCore.Check.AUTO_BLOCK, "Another player has independent alert cooldown");
        h = new Harness();
        first = h.block(10);
        check(first.sound, "First sound is immediate");
        for (int i = 1; i < 30; i++) {
            AdninAnticheatCore.Alert a = h.block(1);
            check(a != null && !a.sound, "Sound cannot repeat before 1500ms");
        }
        check(h.block(1).sound, "Sound repeats at exact 1500ms");
        h = new Harness(); h.cfg.flagSound = false;
        check(!h.block(10).sound, "Sound toggle respected");
        h = new Harness(); h.block(10);
        h.id = UUID.fromString("22345678-1234-4234-9234-123456789abc");
        h.identity = new Object();
        check(!h.block(10).sound, "Sound rate limit shared across players");
        check(AdninAnticheatCore.clampInterval(-1) == 0, "Negative interval clamps to zero");
        check(AdninAnticheatCore.clampInterval(61) == 60, "High interval clamps to sixty");
    }

    private static void historyRetention() {
        for (int change = 0; change < 9; change++) {
            Harness h = new Harness(); h.cfg.intervalSeconds = 20; h.cfg.autoReport = true;
            check(h.block(10).autoReport, "Initial report establishes UUID history");
            if (change == 0) h.cfg.flagSound = false;
            if (change == 1) h.identity = new Object();
            if (change == 2) h.engine.retainPlayers(Collections.<UUID>emptySet());
            if (change == 3) h.step(s -> s.deltaX = Double.NaN);
            if (change == 4) { h.cfg.enabled = false; h.block(1); h.cfg.enabled = true; }
            if (change == 5) h.engine.clearEvidence();
            if (change == 6) { h.cfg.ignoredPlayers = "PlayerA"; h.block(1); h.cfg.ignoredPlayers = ""; }
            if (change == 7) { h.cfg.atlasOnly = true; h.block(1); h.cfg.atlasOnly = false; }
            if (change == 8) { h.cfg.autoBlock = false; h.block(1); h.cfg.autoBlock = true; }
            check(h.block(10) == null, "Evidence reset cannot bypass alert/report cooldown: " + change);
            check(h.engine.cooldownPlayers() == 1, "UUID history survives a temporary eligibility or evidence reset");
            h.time += 20000;
            AdninAnticheatCore.Alert due = h.block(10);
            check(due != null && due.autoReport, "Fresh evidence may report again after its real interval");
        }
        Harness h = new Harness(); h.cfg.intervalSeconds = 20; h.cfg.autoReport = true;
        check(h.block(10).autoReport, "First check reports once");
        AdninAnticheatCore.Alert otherCheck = h.step(s -> s.serverY = 3000);
        mode(otherCheck, AdninAnticheatCore.Check.NO_FALL, "Different checks keep independent local alert cooldowns");
        check(!otherCheck.autoReport && "/wdr PlayerA".equals(otherCheck.reportCommand),
                "Different check keeps manual WDR action but does not duplicate automatic WDR within the interval");

        h = new Harness(); h.cfg.autoReport = true;
        check(h.block(10).autoReport, "Zero interval permits the first report");
        otherCheck = h.step(s -> { s.serverY = 3000; s.sampleTick = 10; });
        mode(otherCheck, AdninAnticheatCore.Check.NO_FALL, "Zero interval may still show both local check alerts");
        check(!otherCheck.autoReport, "Zero interval still permits at most one automatic report per UUID/game tick");
        otherCheck = h.step(s -> { s.serverY = 2800; s.sampleTick = 11; });
        check(otherCheck != null && otherCheck.autoReport, "Zero interval may report on the next game tick");

        h = new Harness(); h.cfg.intervalSeconds = 20; h.cfg.autoReport = true;
        h.block(10); h.engine.reset();
        check(h.engine.cooldownPlayers() == 0, "A real world/disconnect reset clears prior-world histories");
        check(h.block(10).autoReport, "A new world can establish its own evidence and report interval");

        AdninAnticheatCore.Engine engine = new AdninAnticheatCore.Engine();
        AdninAnticheatCore.Settings cfg = new AdninAnticheatCore.Settings();
        cfg.enabled = cfg.autoReport = true; cfg.intervalSeconds = 60;
        long now = 50000;
        UUID first = new UUID(0x1234567812344234L, 0x9234123456780000L);
        for (int player = 0; player < 1030; player++) {
            UUID id = new UUID(first.getMostSignificantBits(), first.getLeastSignificantBits() + player);
            AdninAnticheatCore.Alert a = flagAt(engine, cfg, id, now);
            check(player < 1024 ? a != null && a.autoReport : a == null,
                    "Bounded history rejects new decisions without evicting unexpired cooldowns");
            engine.retainPlayers(Collections.<UUID>emptySet());
        }
        check(engine.cooldownPlayers() == 1024 && engine.trackedPlayers() == 0,
                "Roster churn cannot cause an unbounded UUID cooldown map");
        check(flagAt(engine, cfg, first, now) == null, "A full history retains the earliest active cooldown");
        check(flagAt(engine, cfg, first, now + 60000) != null,
                "Expired histories become reusable at the maximum supported interval");
        check(engine.cooldownPlayers() == 1, "TTL sweep removes absent players' expired history");
    }

    private static AdninAnticheatCore.Alert flagAt(AdninAnticheatCore.Engine engine,
            AdninAnticheatCore.Settings cfg, UUID id, long now) {
        Object identity = new Object();
        AdninAnticheatCore.Alert result = null;
        for (int tick = 1; tick <= 10; tick++) {
            AdninAnticheatCore.Snapshot s = new AdninAnticheatCore.Snapshot();
            s.name = "BoundedFixture"; s.currentTab = s.realProfile = true;
            s.tick = tick; s.sampleTick = tick; s.now = s.lastPacket = now;
            BLOCK.accept(s);
            result = engine.sample(id, identity, s, cfg);
        }
        return result;
    }

    private static void invalidSamples() {
        for (int field = 0; field < 6; field++) {
            Harness h = new Harness(); final int bad = field;
            for (int tick = 0; tick < 12; tick++) check(h.step(s -> {
                BLOCK.accept(s);
                if (bad == 0) s.deltaX = Double.NaN;
                if (bad == 1) s.deltaY = Double.POSITIVE_INFINITY;
                if (bad == 2) s.deltaZ = Double.NEGATIVE_INFINITY;
                if (bad == 3) s.pitch = Float.POSITIVE_INFINITY;
                if (bad == 4) s.now = 0;
                if (bad == 5) s.now = Long.MIN_VALUE;
            }) == null, "Malformed input cannot accumulate detector evidence: " + field);
            check(h.engine.trackedPlayers() == 0, "Malformed snapshots leave no active evidence");
        }
        for (double badDistance : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            Harness h = baseline(0, 3200, 0, 0);
            check(h.step(s -> { s.serverY = 3000; s.distanceToGround = badDistance; }) == null,
                    "Non-finite terrain distance cannot flag NoFall");
        }
        for (int bad = 0; bad < 5; bad++) {
            Harness h = baseline(0, 3200, 0, 0);
            h.tick++; h.time += 50;
            AdninAnticheatCore.Snapshot s = h.snapshot(); s.serverY = 3000;
            if (bad == 0) s.lastPacket = 0;
            if (bad == 1) s.lastPacket = -1;
            if (bad == 2) s.lastPacket = s.now + 1;
            if (bad == 3) s.lastPacket = s.now - 151;
            if (bad == 4) s.pitch = Float.NaN;
            check(!h.engine.noFallCandidate(h.id, h.identity, s, h.cfg),
                    "Stale/future/missing packet or invalid pose cannot request a terrain scan");
            check(h.engine.sample(h.id, h.identity, s, h.cfg) == null,
                    "Candidate and final decision agree on invalid freshness/pose");
        }
    }

    private static void replayDiscontinuities() {
        for (int field = 0; field < 6; field++) {
            Harness h = new Harness(); final int axis = field;
            Consumer<AdninAnticheatCore.Snapshot> before = s -> {
                BLOCK.accept(s); s.replay = s.replayProfile = true;
            };
            Consumer<AdninAnticheatCore.Snapshot> after = s -> {
                before.accept(s);
                if (axis == 3) s.serverX = 160;
                if (axis == 4) s.serverY = 3040;
                if (axis == 5) s.serverZ = -160;
            };
            for (int i = 0; i < 9; i++) check(h.step(before) == null, "Replay builds nine genuine observations");
            check(h.step(s -> {
                after.accept(s);
                if (axis == 0) s.deltaX = 5;
                if (axis == 1) s.deltaY = -5;
                if (axis == 2) s.deltaZ = 5;
            }) == null, "Five-block Replay discontinuity cannot complete an old streak: " + axis);
            for (int i = 0; i < 8; i++) check(h.step(after) == null,
                    "No extrapolation of evidence across the Replay seek");
            AdninAnticheatCore.Alert a = h.step(after);
            mode(a, AdninAnticheatCore.Check.AUTO_BLOCK, "A new ten-observation streak can recover after a seek");
            check(!a.autoReport && a.reportCommand.isEmpty(), "Recovered Replay detection remains unreportable");
        }
        for (int drop : new int[]{160, 320, 1280}) {
            Harness h = new Harness();
            h.step(s -> { s.replay = s.replayProfile = true; });
            h.tick++; h.time += 50;
            AdninAnticheatCore.Snapshot s = h.snapshot();
            s.replay = s.replayProfile = true; s.serverY = 3200 - drop;
            check(!h.engine.noFallCandidate(h.id, h.identity, s, h.cfg),
                    "Replay 5-40 block seek/NoFall ambiguity does not request a terrain scan");
            check(h.engine.sample(h.id, h.identity, s, h.cfg) == null,
                    "Replay 5-40 block sudden drops are conservatively not labeled NoFall");
        }
        Harness h = new Harness();
        Consumer<AdninAnticheatCore.Snapshot> bridge = s -> {
            mellowBridge(s); s.replay = s.replayProfile = true;
        };
        for (int i = 0; i < 5; i++) check(h.step(bridge) == null, "Replay Scaffold builds five positions and its first weighted match");
        check(h.step(s -> { bridge.accept(s); s.deltaZ = 5; }) == null,
                "A Replay seek cannot reuse the accumulated Scaffold observations");
        for (int i = 0; i < 4; i++) check(h.step(bridge) == null, "Scaffold rebuilds positions and weighted VL after a seek");
        mode(h.step(bridge), AdninAnticheatCore.Check.SCAFFOLD,
                "Continuous Replay observations use the same Mellow threshold as live actors");

        h = legitHarness();
        check(replayLegitCycle(h) == 0 && replayLegitCycle(h) == 0,
                "Replay Legit scaffold starts with two correlated cycles");
        check(h.step(s -> {
            s.replay = s.replayProfile = true; s.pitch = 70; s.holdingBlock = true; s.deltaX = 5;
        }) == null, "A Replay seek discards earlier Legit scaffold matches");
        check(replayLegitCycle(h) == 0 && replayLegitCycle(h) == 0,
                "Two post-seek Legit cycles cannot reuse the earlier count");
        check(replayLegitCycle(h) == 1, "Three new Replay Legit cycles retain the unchanged threshold");

        h = new Harness(); h.cfg.intervalSeconds = 20;
        Consumer<AdninAnticheatCore.Snapshot> block = s -> {
            BLOCK.accept(s); s.replay = s.replayProfile = true;
        };
        for (int i = 0; i < 10; i++) h.step(block);
        check(h.step(s -> { block.accept(s); s.deltaY = -5; }) == null, "Seek resets evidence after an earlier flag");
        for (int i = 0; i < 10; i++) check(h.step(block) == null, "Replay seek never bypasses an existing alert cooldown");

        h = new Harness();
        for (int i = 0; i < 9; i++) h.step(block);
        for (int callback = 0; callback < 20; callback++) {
            AdninAnticheatCore.Snapshot s = h.snapshot(); block.accept(s);
            check(h.engine.sample(h.id, h.identity, s, h.cfg) == null,
                    "Paused/repeated Replay entity ticks do not create extra evidence");
        }
        h.time += 1000;
        check(h.step(block) == null, "Resuming after a real sampling pause establishes a new baseline");
        for (int i = 0; i < 8; i++) check(h.step(block) == null, "A pause never fabricates missed observations");
        mode(h.step(block), AdninAnticheatCore.Check.AUTO_BLOCK, "Ten actual post-pause observations recover");
    }

    private static int replayLegitCycle(Harness h) {
        int alerts = 0;
        for (int phase = -2; phase < 7; phase++) {
            final int at = phase;
            AdninAnticheatCore.Alert a = h.step(s -> {
                s.replay = s.replayProfile = true; s.pitch = 70; s.holdingBlock = true;
                s.swinging = at >= 0 && at < 6; s.swingProgress = Math.max(0, at);
                s.sneaking = at >= 0 && at <= 3;
            });
            if (a != null) {
                mode(a, AdninAnticheatCore.Check.LEGIT_SCAFFOLD, "Replay cycle emits only its correlated check");
                check(!a.autoReport && a.reportCommand.isEmpty(), "Replay Legit scaffold never produces WDR");
                alerts++;
            }
        }
        return alerts;
    }

    private static void mode(AdninAnticheatCore.Alert result, AdninAnticheatCore.Check expected, String message) {
        check(result != null && result.check == expected, message);
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
