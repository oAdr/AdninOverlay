import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/** Pure policy/adapter tests; never initializes Minecraft or sends a packet. */
public final class AdninPartyQueueQueryTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        addressRules(); footerRules(); boundaries(); lateToggle(); transitions(); gates(); waitingScope(); proxyScope(); failures(); responseRecovery(); referenceLifecycle(); shutdown();
        System.out.println("AdninPartyQueueQueryTest: " + checks
            + " checks passed; 500ms boundary, explicit enable cycles, 5s throttling, mandatory waiting/domain/footer scope, 250ms probes,"
            + " bounded failed-send recovery and weak lifecycle; no game, settings, native library or network");
    }

    private static void addressRules() {
        for (String address : new String[]{"hypixel.net", "mc.hypixel.net", "alpha.hypixel.net", "a.b.hypixel.net",
                "MC.HYPIXEL.NET", "hypixel.net:25565", "mc.hypixel.net:1", "mc.hypixel.net:65535",
                "mc.hypixel.net:00080", "  mc.hypixel.net:25565  ", "a-1.hypixel.net"}) {
            check(AdninPartyQueueQuery.isHypixelAddress(address), "Accept valid Hypixel host: " + address);
        }
        for (String address : new String[]{null, "", " ", "localhost", "127.0.0.1", "example.net", "evil-hypixel.net",
                "hypixel.net.evil.example", "hypixel.net@evil.example", "evil.example@hypixel.net", "https://hypixel.net",
                "hypixel.net/path", "hypixel.net?x", "hypixel.net#x", "hypixel.net:", "hypixel.net:0",
                "hypixel.net:65536", "hypixel.net:-1", "hypixel.net:+1", "hypixel.net:1.0", "hypixel.net:25565:1",
                "hypixel.net:000080", "hypixel.net: 80", "hypixel.net:８０", "[hypixel.net]:25565", ".hypixel.net",
                "a..hypixel.net", "-a.hypixel.net", "a-.hypixel.net", "a_b.hypixel.net", "a .hypixel.net",
                "hypixel.net.", "hypixel.net\u0000.evil", "hypixel.net\n.evil", "\u043c\u0441.hypixel.net"}) {
            check(!AdninPartyQueueQuery.isHypixelAddress(address), "Reject invalid/non-Hypixel address: " + address);
        }
        check(AdninPartyQueueQuery.isHypixelAddress(repeat('a', 63) + ".hypixel.net"), "DNS label may contain 63 ASCII characters");
        check(!AdninPartyQueueQuery.isHypixelAddress(repeat('a', 64) + ".hypixel.net"), "Oversize DNS label is rejected");
    }

    private static void boundaries() {
        Fixture value = new Fixture();
        value.tick(1000);
        check(value.sender.calls == 0, "Entry only schedules the query");
        value.tick(1499);
        check(value.sender.calls == 0, "499ms cannot send");
        value.tick(1500);
        check(value.sender.calls == 1 && value.sender.sent == 1, "Exactly 500ms sends once");
        check("/locraw".equals(value.sender.command), "Only the server mode query is dispatched");
        check(value.sender.world == value.world && value.sender.connection == value.connection,
            "Dispatch carries the exact current world and connection identities");
        for (int i = 0; i < 2000; ++i) value.tick(1501 + i);
        value.tick(60000);
        check(value.sender.calls == 1, "Repeated client ticks never repeat a stable enable-cycle query");
        Fixture zero = new Fixture();
        zero.tick(0); zero.tick(499);
        check(zero.sender.calls == 0, "A zero-origin clock does not bypass the delay");
        zero.tick(500);
        check(zero.sender.calls == 1, "A zero-origin clock fires at the exact boundary");
        Fixture negative = new Fixture();
        negative.tick(-1000); negative.tick(-501);
        check(negative.sender.calls == 0, "Monotonic clock values need not be positive");
        negative.tick(-500);
        check(negative.sender.calls == 1, "Negative monotonic origin retains the 500ms delay");
        negative.enabled = false; negative.tick(-499); negative.enabled = true; negative.tick(-498);
        negative.tick(4499);
        check(negative.sender.calls == 1, "Negative first-attempt time still enforces the full five-second cooldown");
        negative.tick(4500);
        check(negative.sender.calls == 2, "Negative-origin cooldown expires at its exact boundary");
    }

    private static void footerRules() {
        for (String value : new String[]{"hypixel.net", "www.hypixel.net", "  WWW.HYPIXEL.NET  ",
                "\u00a7ewww.hypixel.net", "\u00a7EHypixel\u00a7r.net", "www.hy\u00a7apixel.net\u00a7r"}) {
            check(AdninPartyQueueQuery.isHypixelFooter(value), "Complete visible official footer is accepted");
        }
        for (String value : new String[]{null, "", "BED WARS", "HYPIXEL", "Hypixel Network", "Replay",
                "join www.hypixel.net", "hypixel.net.evil.example", "www.hypixel.net:25565", "hypixel.net/path",
                "fakehypixel.net", "www.hypixel.net@elsewhere.example", "https://hypixel.net", "www.\u00a7zhypixel.net",
                "hyp\u0131xel.net", "hyp\u0130xel.net", "\u00a7\u212Ahypixel.net", "\u00a7ewww.hypixel.ne\uD83C\uDF82\u00a7et",
                "#hypixel.net", "\u00a7ehypixel.net\u00a7", repeat('x', 257)}) {
            check(!AdninPartyQueueQuery.isHypixelFooter(value), "Generic/partial/unrelated/oversize footer is rejected");
        }
    }

    private static void lateToggle() {
        Fixture value = new Fixture();
        value.enabled = false; value.tick(0); value.tick(5000);
        check(value.sender.calls == 0, "Disabled detector never queries an entered world");
        value.enabled = true; value.tick(5000); value.tick(5499);
        check(value.sender.calls == 0, "Enabling in an existing world starts its own delay");
        value.tick(5500);
        check(value.sender.calls == 1, "Late enable queries at 500ms");
        for (int i = 0; i < 100; ++i) {
            value.enabled = false; value.tick(6000 + 20L * i);
            value.enabled = true; value.tick(6001 + 20L * i); value.tick(6002 + 20L * i);
        }
        value.tick(10499);
        check(value.sender.calls == 1, "Rapid explicit toggles cannot bypass the five-second attempt interval");
        value.tick(10500);
        check(value.sender.calls == 2, "Explicit re-enable can restore native mode at the five-second boundary");
        value.tick(60000);
        check(value.sender.calls == 2, "A stable re-enabled cycle still sends only once");
        value.enabled = false; value.tick(60001); value.enabled = true; value.tick(60002); value.tick(60501);
        check(value.sender.calls == 2, "A later explicit enable still waits a fresh 500ms even after throttle expires");
        value.tick(60502);
        check(value.sender.calls == 3, "Later explicit enable can restore the native mode once more");
        Fixture cancelled = new Fixture();
        cancelled.tick(0); cancelled.enabled = false; cancelled.tick(499); cancelled.tick(500);
        check(cancelled.sender.calls == 0, "Disabling retires an unsent pending query");
        cancelled.enabled = true; cancelled.tick(600); cancelled.tick(1099);
        check(cancelled.sender.calls == 0, "Re-enable cannot inherit a cancelled deadline");
        cancelled.tick(1100);
        check(cancelled.sender.calls == 1, "Unsent world may make its one attempt after a fresh delay");
    }

    private static void transitions() {
        Fixture value = new Fixture();
        Object first = value.world;
        value.tick(0);
        value.world = new Object(); value.tick(499); value.tick(500);
        check(value.sender.calls == 0, "World replacement cancels the prior world's due query");
        value.tick(999);
        check(value.sender.calls == 1 && value.sender.world == value.world, "Only the replacement world is queried");
        Object second = value.world;
        value.world = first; value.tick(1200); value.tick(1700); value.tick(5998);
        check(value.sender.calls == 1, "New world cannot bypass the cross-context five-second attempt interval");
        value.tick(5999);
        check(value.sender.calls == 2 && value.sender.world == first, "New world queries at the throttled deadline");
        value.world = second; value.tick(12000); value.tick(12500);
        check(value.sender.calls == 3, "Returning to a prior world is a new context with one fresh query");
        value.connection = new Object(); value.tick(18000); value.tick(18500);
        check(value.sender.calls == 4, "Connection replacement creates one new delayed query cycle");

        Fixture connection = new Fixture();
        connection.tick(0); connection.connection = new Object(); connection.tick(499); connection.tick(500);
        check(connection.sender.calls == 0, "New connection retires the old unsent schedule");
        connection.tick(999);
        check(connection.sender.calls == 1 && connection.sender.connection == connection.connection,
            "Connection replacement queries only after its own 500ms interval");

        Fixture disconnect = new Fixture();
        Object world = disconnect.world, oldConnection = disconnect.connection;
        disconnect.tick(0); disconnect.world = null; disconnect.connection = null; disconnect.tick(500);
        check(disconnect.sender.calls == 0, "Disconnected pending work cannot send");
        disconnect.world = world; disconnect.connection = oldConnection; disconnect.tick(1000); disconnect.tick(1499);
        check(disconnect.sender.calls == 0, "Reconnection retires the previous deadline");
        disconnect.tick(1500);
        check(disconnect.sender.calls == 1, "Reconnected unsent world can query once after a fresh delay");

        Fixture equalWorlds = new Fixture();
        equalWorlds.world = new EqualWorld(); equalWorlds.tick(0); equalWorlds.tick(500);
        equalWorlds.world = new EqualWorld(); equalWorlds.tick(1000); equalWorlds.tick(1500); equalWorlds.tick(5500);
        check(equalWorlds.sender.calls == 2, "World tracking compares object identity, not equals/hashCode");
    }

    private static void gates() {
        for (String host : new String[]{"example.net", "evil-hypixel.net", "hypixel.net.evil", "hypixel.net:0", null}) {
            Fixture value = new Fixture(); value.address = host; value.tick(0); value.tick(500); value.tick(5000);
            check(value.sender.calls == 0, "Non-Hypixel world cannot query: " + host);
        }
        Fixture solo = new Fixture(); solo.multiplayer = false; solo.tick(0); solo.tick(5000);
        check(solo.sender.calls == 0, "Singleplayer never dispatches even with stale Hypixel server data");
        Fixture absent = new Fixture(); absent.world = null; absent.tick(0); absent.tick(500);
        check(absent.sender.calls == 0, "No world means no query");
        absent.world = new Object(); absent.connection = null; absent.tick(1000); absent.tick(1500);
        check(absent.sender.calls == 0, "No connection means no query");
        Fixture player = new Fixture(); player.ready = false; player.tick(0); player.tick(5000);
        check(player.sender.calls == 0, "Missing local player cannot arm the query");
        player.ready = true; player.tick(5000); player.tick(5499);
        check(player.sender.calls == 0, "Local-player readiness starts a complete delay");
        player.tick(5500);
        check(player.sender.calls == 1, "Ready multiplayer player can query after 500ms");
        Fixture changedHost = new Fixture(); changedHost.tick(0); changedHost.address = "other.example"; changedHost.tick(500);
        check(changedHost.sender.calls == 0, "Address scope is rechecked at dispatch time");
        changedHost.address = "mc.hypixel.net"; changedHost.tick(501); changedHost.tick(1000);
        check(changedHost.sender.calls == 0, "Re-entering allowed address cannot inherit retired deadline");
        changedHost.tick(1001);
        check(changedHost.sender.calls == 1, "New allowed context gets its own complete delay");
    }

    private static void failures() throws Exception {
        Fixture busy = new Fixture(); busy.tick(0); busy.ready = false; busy.tick(500);
        busy.ready = true; busy.tick(501); busy.tick(1000);
        check(busy.sender.calls == 0, "Missing player cancels the deadline and recovery waits a fresh 500ms");
        busy.tick(1001);
        check(busy.sender.calls == 1, "A transiently missing player does not permanently disable the query");
        for (int failure = 0; failure < 3; ++failure) {
            Fixture value = new Fixture(); value.sender.failure = failure + 1;
            value.tick(0); value.tick(500);
            for (int i = 0; i < 2000; ++i) value.tick(501 + i);
            value.tick(5499);
            check(value.sender.calls == 1 && value.sender.sent == 0,
                "False/exception/unavailable sender never retries every frame or bypasses throttle: " + failure);
            value.sender.failure = 0;
            value.tick(5500);
            check(value.sender.calls == 2 && value.sender.sent == 1,
                "The same world recovers at five seconds without requiring a manual toggle: " + failure);
            value.tick(60000);
            check(value.sender.calls == 2, "A successful retry completes the current cycle: " + failure);

            Fixture exhausted = new Fixture(); exhausted.sender.failure = failure + 1;
            exhausted.tick(0); exhausted.tick(500); exhausted.tick(501); exhausted.tick(5500);
            exhausted.tick(5501); exhausted.tick(10500); exhausted.tick(10501); exhausted.tick(60000);
            check(exhausted.sender.calls == AdninPartyQueueQuery.MAX_ATTEMPTS && exhausted.sender.sent == 0,
                "Persistent failure stops after the finite attempt budget: " + failure);
            exhausted.sender.failure = 0;
            exhausted.enabled = false; exhausted.tick(60001); exhausted.enabled = true; exhausted.tick(60002);
            exhausted.tick(60502);
            check(exhausted.sender.calls == AdninPartyQueueQuery.MAX_ATTEMPTS + 1 && exhausted.sender.sent == 1,
                "An explicit re-enable may recover an exhausted budget after a fresh delay: " + failure);
        }
        Fixture read = new Fixture(); read.tick(0); read.policy.readFailed(200);
        check(!read.policy.canRead(200) && !read.policy.canRead(1199), "Failed state read has a full 1000ms backoff");
        check(read.policy.canRead(1200), "State reads resume at the exact backoff boundary");
        read.tick(1200); read.tick(1699);
        check(read.sender.calls == 0, "Read recovery requires fresh waiting evidence and a new delay");
        read.tick(1700);
        check(read.sender.calls == 1, "The same world recovers after a transient state-read failure");
        check((Boolean) field(read.policy, "enabledBefore"), "Read failure never invents an off/on enable transition");
        read.policy.readFailed(1800); check(read.policy.canRead(2800), "Post-success read failure also recovers after backoff");
        read.tick(2800); read.tick(10000);
        check(read.sender.calls == 1, "A read failure cannot rearm an already successful query");
        read.world = new Object(); read.tick(11000); read.tick(11500);
        check(read.sender.calls == 2, "A later world independently starts its own query cycle");

        Fixture missingSender = new Fixture();
        missingSender.policy.tick(0, missingSender.world, missingSender.connection, true, true,
            missingSender.address, true, null, missingSender.scope);
        missingSender.policy.tick(500, missingSender.world, missingSender.connection, true, true,
            missingSender.address, true, null, missingSender.scope);
        missingSender.tick(501); missingSender.tick(5499);
        check(missingSender.sender.calls == 0, "A missing sender still consumes the bounded attempt interval");
        missingSender.tick(5500);
        check(missingSender.sender.sent == 1, "A sender becoming available recovers after the same rate limit");
    }

    private static void proxyScope() {
        Fixture official = new Fixture(); official.scope.allowed = true;
        official.tick(0); official.tick(250); official.tick(500); official.tick(60000);
        check(official.sender.calls == 1 && official.scope.calls == 4,
            "Official addresses retain bounded scope checks to retire a same-world queue response");

        Fixture relay = new Fixture(); relay.address = "relay.example";
        relay.tick(0);
        for (int i = 1; i < 250; ++i) relay.tick(i);
        check(relay.scope.calls == 1 && relay.sender.calls == 0,
            "An unrecognized forwarding address probes at most once before 250ms");
        relay.scope.allowed = true; relay.tick(250); relay.tick(499);
        check(relay.scope.calls == 2 && relay.sender.calls == 0,
            "A newly visible footer is accepted at the probe boundary and starts a fresh delay");
        relay.tick(500); relay.tick(749);
        check(relay.scope.calls == 3 && relay.sender.calls == 0,
            "Positive footer cache still enforces a full 500ms before the query");
        relay.tick(750);
        check(relay.sender.calls == 1 && relay.scope.calls == 4, "Forwarding address can query with current visible Hypixel evidence");
        for (int i = 0; i < 2000; ++i) relay.tick(751 + i);
        relay.tick(60000);
        check(relay.sender.calls == 1 && relay.scope.calls == 13,
            "Consumed cycle stops repeated commands; 2000 ticks add only eight 250ms scope probes plus the late probe");

        Fixture disabled = new Fixture(); disabled.address = "relay.example"; disabled.scope.allowed = true;
        disabled.enabled = false; disabled.tick(0); disabled.tick(1000);
        disabled.enabled = true; disabled.ready = false; disabled.tick(1500); disabled.tick(2000);
        check(disabled.scope.calls == 0, "Disabled or missing-player contexts never probe scoreboard identity");
        disabled.ready = true; disabled.tick(2500); disabled.tick(3000);
        check(disabled.sender.calls == 1 && disabled.scope.calls == 2, "Ready explicitly enabled proxy context can query once");
        Fixture solo = new Fixture(); solo.address = "relay.example"; solo.multiplayer = false; solo.scope.allowed = true;
        solo.tick(0); solo.tick(5000);
        check(solo.scope.calls == 0 && solo.sender.calls == 0, "Singleplayer cannot use even a matching footer to query");

        Fixture replace = new Fixture(); replace.address = "relay.example"; replace.scope.allowed = true;
        replace.tick(0); replace.world = new Object(); replace.scope.allowed = false; replace.tick(10);
        check(replace.scope.calls == 2 && replace.sender.calls == 0, "New world immediately retires the prior positive scope cache");
        replace.connection = new Object(); replace.scope.allowed = true; replace.tick(20); replace.tick(519);
        check(replace.sender.calls == 0, "New connection starts its own scope check and complete delay");
        replace.tick(520);
        check(replace.sender.calls == 1 && replace.sender.world == replace.world && replace.sender.connection == replace.connection,
            "Only the new scope-confirmed world/connection may dispatch");

        Fixture lost = new Fixture(); lost.address = "relay.example"; lost.scope.allowed = true; lost.tick(0);
        lost.scope.allowed = false; lost.tick(250); lost.tick(500);
        check(lost.sender.calls == 0, "Losing visible footer cancels a pending proxy query");
        lost.scope.allowed = true; lost.tick(750); lost.tick(1249);
        check(lost.sender.calls == 0, "Restored footer cannot reuse the cancelled deadline");
        lost.tick(1250);
        check(lost.sender.calls == 1, "Restored visible evidence can arm a fresh query");

        Fixture failing = new Fixture(); failing.address = "relay.example"; failing.scope.failure = true;
        failing.tick(0);
        for (int i = 1; i < 250; ++i) failing.tick(i);
        check(failing.scope.calls == 1 && failing.sender.calls == 0, "Failed scope probes are throttled and cannot dispatch");
        failing.tick(250);
        check(failing.scope.calls == 2 && failing.sender.calls == 0, "Scope recovery probes remain bounded to the normal interval");
        failing.policy.shutdown(); failing.tick(5000);
        check(failing.scope.calls == 2, "Shutdown forbids later scope probes as well as sends");
    }

    private static void referenceLifecycle() throws Exception {
        Fixture value = new Fixture(); value.tick(0);
        WeakReference<?> oldWorld = (WeakReference<?>) field(value.policy, "world");
        WeakReference<?> oldConnection = (WeakReference<?>) field(value.policy, "connection");
        check(oldWorld.get() == value.world && oldConnection.get() == value.connection,
            "Policy stores current game identities exclusively through weak references");
        String originalAddress = value.address;
        for (int i = 1; i < 50; ++i) {
            value.address = new String(originalAddress);
            value.tick(i);
        }
        check(field(value.policy, "checkedAddress") == originalAddress,
            "Equal unchanged host values reuse the parsed guard without replacing its cached String");
        check(field(value.policy, "world") == oldWorld && field(value.policy, "connection") == oldConnection,
            "Stable client ticks allocate no replacement weak-reference holders");
        value.tick(500);
        value.world = new Object(); value.connection = new Object(); value.tick(1000);
        check(oldWorld.get() == null && oldConnection.get() == null,
            "Context replacement clears prior-world and prior-connection reference holders immediately");
        value.policy.disconnect();
        check(field(value.policy, "world") == null && field(value.policy, "connection") == null,
            "Disconnect releases both current-world weak-reference holders");
        check(!(Boolean) field(value.policy, "armed"), "Disconnect retires the pending schedule");
    }

    private static void waitingScope() {
        for (String address : new String[]{"mc.hypixel.net", "relay.example"}) {
            Fixture lobby = new Fixture(); lobby.address = address; lobby.scope.allowed = true; lobby.scope.waiting = false;
            lobby.tick(0); lobby.tick(500); lobby.tick(5000);
            check(lobby.sender.calls == 0, "Lobby branding alone never schedules a mode query: " + address);
            lobby.scope.waiting = true; lobby.tick(6000); lobby.tick(6499);
            check(lobby.sender.calls == 0, "Entering a genuine pregame room starts a fresh complete delay");
            lobby.tick(6500);
            check(lobby.sender.calls == 1, "A positive waiting-room transition enables one delayed query");

            Fixture leave = new Fixture(); leave.address = address; leave.scope.allowed = true;
            leave.tick(0); leave.scope.waiting = false; leave.tick(250); leave.tick(500);
            check(leave.sender.calls == 0, "Leaving pregame retires its pending query before dispatch");
            leave.scope.waiting = true; leave.tick(750); leave.tick(1249);
            check(leave.sender.calls == 0, "A return to pregame cannot inherit the retired deadline");
            leave.tick(1250);
            check(leave.sender.calls == 1, "Restored pregame gets its own 500ms delay");
        }
        Fixture missing = new Fixture();
        missing.policy.tick(0, missing.world, missing.connection, true, true, missing.address, true, missing.sender);
        missing.policy.tick(5000, missing.world, missing.connection, true, true, missing.address, true, missing.sender);
        check(missing.sender.calls == 0, "Missing phase evidence fails closed even on an official hostname");
        Fixture active = new Fixture(); active.tick(0);
        active.active = true; active.tick(100); active.tick(500);
        check(active.sender.calls == 0, "Active game/Replay revokes pending deadline immediately");
        active.active = false; active.tick(1000); active.tick(1499);
        check(active.sender.calls == 0, "Returning from active context starts a complete unconsumed delay");
        active.tick(1500); check(active.sender.calls == 1, "Unsent context may make its one permitted attempt");
        active.active = true; active.tick(2000); active.active = false; active.tick(3000); active.tick(9000);
        check(active.sender.calls == 2, "A native active-to-waiting transition restores mode discovery even when the world object is reused");
        active.scope.waiting = false; active.tick(10000); active.scope.waiting = true; active.tick(11000); active.tick(12000);
        check(active.sender.calls == 2, "Temporarily missing sidebar cannot rearm a consumed world");
    }

    private static void responseRecovery() {
        Fixture f = new Fixture(); ResponseSender sender = new ResponseSender();
        long[] times={0,500,5499,5500,5999,6000,10999,11000,11499,11500,16500,60000};
        int[] expected={0,1,1,1,1,2,2,2,2,3,3,3};
        for(int i=0;i<times.length;i++) {
            f.policy.tick(times[i],f.world,f.connection,true,true,f.address,true,sender,f.scope,false);
            check(sender.calls==expected[i],"Missing decoded mode response follows bounded retries at "+times[i]);
        }
        Fixture accepted = new Fixture(); ResponseSender answer = new ResponseSender();
        accepted.policy.tick(0,accepted.world,accepted.connection,true,true,accepted.address,true,answer,accepted.scope,false);
        accepted.policy.tick(500,accepted.world,accepted.connection,true,true,accepted.address,true,answer,accepted.scope,false);
        answer.accepted=true;
        for(long now:new long[]{501,5500,100000})
            accepted.policy.tick(now,accepted.world,accepted.connection,true,true,accepted.address,true,answer,accepted.scope,false);
        check(answer.calls==1,"A decoded mode response completes the cycle without extra queries");
        accepted.policy.tick(100001,accepted.world,accepted.connection,true,true,accepted.address,true,answer,accepted.scope,true);
        answer.accepted=false;
        accepted.policy.tick(100002,accepted.world,accepted.connection,true,true,accepted.address,true,answer,accepted.scope,false);
        accepted.policy.tick(100502,accepted.world,accepted.connection,true,true,accepted.address,true,answer,accepted.scope,false);
        check(answer.calls==2,"The next waiting phase does not inherit the previous native mode");
        String prefix="{\"server\":\"mini123A\",\"gametype\":\"BEDWARS\",\"mode\":\"";
        String[] modes={"eight_one","eight_two","four_three","four_four","two_four"};
        for(int i=0;i<modes.length;i++) check(AdninPartyQueueQuery.parseModeResponse(prefix+modes[i]+"\",\"map\":\"Lighthouse\"}")==i+1,"Exact mode mapping");
        for(String text:new String[]{"[MVP+] User: "+prefix+"eight_two\"}","{\"mode\":\"eight_two\"}",
                prefix+"evil_eight_two\"}",prefix+"eight_two\",\"mode\":\"four_four\"}",
                prefix+"eight_two\",\"extra\":[]}",prefix+"eight_two\",}",
                prefix.replace("mini123A","dynamiclobby1")+"eight_two\"}",
                prefix.replace("BEDWARS","SKYWARS")+"eight_two\"}",prefix+"eight_two\\n\"}"})
            check(AdninPartyQueueQuery.parseModeResponse(text)==0,"Reject player text, invalid structure, foreign/lobby modes and duplicate keys");
    }

    private static final class ResponseSender implements AdninPartyQueueQuery.ResponseSender {
        int calls; boolean accepted;
        public boolean send(Object world,Object connection,String command){calls++;return true;}
        public boolean hasResponse(Object world,Object connection){return accepted;}
    }

    private static void shutdown() throws Exception {
        Fixture value = new Fixture(); value.tick(0); value.policy.shutdown(); value.tick(500);
        check(value.sender.calls == 0, "Shutdown cancels a due pending query");
        value.world = new Object(); value.connection = new Object(); value.tick(1000); value.tick(1500);
        check(value.sender.calls == 0 && !value.policy.canRead(1000000), "Shutdown is terminal even after reconnect or late enable");
        check(field(value.policy, "world") == null && field(value.policy, "connection") == null
            && field(value.policy, "checkedAddress") == null, "Shutdown retains no world, connection or server-address reference");
        value.policy.readFailed(2000); value.policy.disconnect(); value.policy.shutdown();
        check(!value.policy.canRead(Long.MAX_VALUE), "Late cleanup or failure cannot revive a stopped policy");
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static String repeat(char c, int count) { StringBuilder value = new StringBuilder(); while(count-- > 0) value.append(c); return value.toString(); }
    private static void check(boolean condition, String message) { ++checks; if (!condition) throw new AssertionError(message); }

    private static final class Fixture {
        final AdninPartyQueueQuery.Policy policy = new AdninPartyQueueQuery.Policy();
        final FakeSender sender = new FakeSender();
        final FakeScope scope = new FakeScope();
        Object world = new Object(), connection = new Object();
        boolean enabled = true, multiplayer = true, ready = true, active;
        String address = "mc.hypixel.net";
        void tick(long now) { policy.tick(now, world, connection, enabled, multiplayer, address, ready, sender, scope, active); }
    }
    private static final class FakeSender implements AdninPartyQueueQuery.Sender {
        int calls, sent, failure;
        Object world, connection;
        String command;
        public boolean send(Object nextWorld, Object nextConnection, String nextCommand) {
            ++calls; world = nextWorld; connection = nextConnection; command = nextCommand;
            if (failure == 1) return false;
            if (failure == 2) throw new IllegalStateException("Owned offline fixture");
            if (failure == 3) throw new NoClassDefFoundError("Owned offline fixture");
            ++sent;
            return true;
        }
    }
    private static final class EqualWorld {
        public boolean equals(Object other) { return other instanceof EqualWorld; }
        public int hashCode() { return 1; }
    }
    private static final class FakeScope implements AdninPartyQueueQuery.ScopeProbe {
        int calls;
        boolean allowed, failure, waiting = true;
        public int observe(Object world, Object connection) {
            ++calls;
            if (failure) throw new IllegalStateException("Owned scope fixture");
            return (allowed ? AdninPartyQueueQuery.SCOPE_HYPIXEL : 0)
                | (waiting ? AdninPartyQueueQuery.SCOPE_WAITING : 0);
        }
    }
}
