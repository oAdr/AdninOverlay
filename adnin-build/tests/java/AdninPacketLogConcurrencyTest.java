import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Actual Netty 4.0.23 registered LocalChannels, without connecting or binding.
 * Only PacketLog and Netty are needed on the runtime classpath. No Minecraft,
 * native library, personal configuration, socket, or authenticated API is used.
 */
public final class AdninPacketLogConcurrencyTest {
    private static final String KEY = "adnin_packet_log";
    private static final long TIMEOUT_MS = 5000L;
    private static final List<Fixture> fixtures = new ArrayList<Fixture>();
    private static final List<EventLoop> loops = new ArrayList<EventLoop>();
    private static final AtomicInteger threadIds = new AtomicInteger();
    private static final ThreadFactory daemonFactory = new ThreadFactory() {
        public Thread newThread(Runnable action) {
            Thread thread = new Thread(action, "packetlog-test-eventloop-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    };
    private static final LocalEventLoopGroup group = new LocalEventLoopGroup(1, daemonFactory);
    private static final LocalEventLoopGroup otherGroup = new LocalEventLoopGroup(1, daemonFactory);
    private static Fixture foreignFixture;
    private static ChannelHandler foreignHandler;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Field spawnClass = AdninPacketLog.class.getDeclaredField("s_spawnPlayerClass");
        spawnClass.setAccessible(true);
        // Only explicit owned fixtures match the spawn class. The JNI symbol is
        // intentionally unresolved: its LinkageError must not interrupt traffic.
        spawnClass.set(null, FakeSpawnPacket.class);
        try {
            Fixture first = fixture();
            AdninPacketLog.install(first.network);
            final ChannelHandler firstHandler = awaitHandler(first, true);
            check(firstHandler != null, "Initial registered-channel installation completes");
            check(first.channel.pipeline().names().indexOf(KEY)
                < first.channel.pipeline().names().indexOf("packet_handler"),
                "Observer installs before the original packet handler");
            passThrough(first);
            duplicateInstallation(first, firstHandler);
            spawnObservation(first);
            modeResponseObservation(first);
            grayPacketForwarding(first);
            decodedFallback();
            Fixture latest = pendingReplacement(first);
            pendingClose(latest);
            Fixture restored = fixture();
            AdninPacketLog.install(restored.network);
            check(awaitHandler(restored, true) != null, "Closed pending channel does not prevent a later installation");
            crossLoopRoundTrip(restored);
            externalHandlerConflict(restored);
            stopPending(restored);
            check(foreignFixture.channel.pipeline().get(KEY) == foreignHandler,
                "Shutdown does not remove a different owner's same-name handler");
            check(AdninPacketLog.seenSpawnPlayerCount == 4,
                "Three normal and one gray spawn packet were observed; ordinary and stopped packets never sample");
            for (Fixture value : fixtures) {
                check(value.collector.added == 1, "Original packet handler was never recreated");
                if (value.channel.isOpen()) {
                    check(value.collector.removed == 0, "Open channel retains its original packet handler");
                }
            }
            System.out.println("AdninPacketLogConcurrencyTest: " + checks
                + " checks passed; actual registered Netty LocalChannels, blocked event loop, duplicate and stale work,"
                + " shutdown, spawn decoding and packet pass-through; no game, native library, settings or network");
        } finally {
            // Every artificial monitor block has already left its finally scope.
            // Bounded cleanup and daemon workers also allow a broken implementation
            // to fail without keeping the test JVM alive indefinitely.
            try { AdninPacketLog.shutdown(); } catch (Throwable ignored) { }
            for (Fixture value : fixtures) {
                try { value.channel.close().await(TIMEOUT_MS); } catch (Throwable ignored) { }
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(TIMEOUT_MS);
            otherGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(TIMEOUT_MS);
        }
    }

    private static void duplicateInstallation(final Fixture fixture, final ChannelHandler original) throws Exception {
        int pending = withBlockedTaskQueue(fixture.loop, new Runnable() {
            public void run() {
                for (int i = 0; i < 3000; ++i) AdninPacketLog.install(fixture.network);
            }
        });
        check(pending == 0, "Installed same-channel polling queues no pipeline work while event loop is blocked");
        check(awaitHandler(fixture, true) == original, "Repeated installation preserves the exact owned handler instance");
        check(countOwned(fixture) == 1, "Repeated installation leaves exactly one observer");
        passThrough(fixture);
    }

    private static Fixture pendingReplacement(final Fixture first) throws Exception {
        final Fixture second = fixture();
        final Fixture third = fixture();
        final Fixture latest = fixture();
        int pending = withBlockedTaskQueue(first.loop, new Runnable() {
            public void run() {
                for (int i = 0; i < 1000; ++i) AdninPacketLog.install(second.network);
                for (int i = 0; i < 1000; ++i) AdninPacketLog.install(third.network);
                for (int i = 0; i < 1000; ++i) AdninPacketLog.install(latest.network);
            }
        });
        check(pending <= 1, "Rapid replacement keeps at most one pending lifecycle task");
        check(awaitHandler(latest, true) != null, "Latest channel installs after the blocked loop resumes");
        check(awaitHandler(first, false) == null, "Prior channel's owned handler is retired");
        check(awaitHandler(second, false) == null && awaitHandler(third, false) == null,
            "Superseded pending channels cannot install stale handlers");
        check(countOwned(latest) == 1, "Latest channel has exactly one observer");
        passThrough(first);
        passThrough(latest);
        return latest;
    }

    private static void pendingClose(Fixture active) throws Exception {
        final Fixture closed = fixture();
        int pending = withBlockedTaskQueue(active.loop, new Runnable() {
            public void run() {
                // The close operation is ordered before any install work for
                // this channel and is allowed to execute only after release.
                closed.channel.close();
                for (int i = 0; i < 1000; ++i) AdninPacketLog.install(closed.network);
            }
        });
        check(pending <= 2, "Pending closure adds only its close task and one lifecycle task");
        await(closed.channel.closeFuture(), "Pending test channel closes");
        check(awaitHandler(closed, false) == null, "A channel closed before queued installation gets no observer");
        int pendingAfterClose = withBlockedTaskQueue(active.loop, new Runnable() {
            public void run() {
                for (int i = 0; i < 1000; ++i) AdninPacketLog.install(closed.network);
            }
        });
        check(pendingAfterClose <= 1, "Already-closed channel polling cannot flood the event loop");
        check(awaitHandler(closed, false) == null, "Already-closed channel never receives an observer");
    }

    private static void stopPending(final Fixture active) throws Exception {
        final Fixture waiting = fixture();
        final Fixture afterStop = fixture();
        final FakeSpawnPacket stalePacket = new FakeSpawnPacket();
        final BlockingSpawnPacket inFlightPacket = new BlockingSpawnPacket();
        final int observedBeforeStop = AdninPacketLog.seenSpawnPlayerCount;
        final int readsBeforeStop = active.collector.reads;
        final CountDownLatch callerFinished = new CountDownLatch(1);
        final AtomicReference<Throwable> callerFailure = new AtomicReference<Throwable>();
        final AtomicReference<Boolean> unloadGate = new AtomicReference<Boolean>();
        check(!AdninPacketLog.isQuiescent(), "Unload gate stays closed while the observer remains enabled");
        final Future<?> inFlight = active.loop.submit(new Runnable() {
            public void run() { active.channel.pipeline().fireChannelRead(inFlightPacket); }
        });
        Thread controller = new Thread(new Runnable() {
            public void run() {
                try {
                    // This packet executes before handler removal but after
                    // shutdown changes ownership. The attached retired handler
                    // must still pass it without admitting another observation.
                    active.loop.execute(new Runnable() {
                        public void run() { active.channel.pipeline().fireChannelRead(stalePacket); }
                    });
                    AdninPacketLog.install(waiting.network);
                    AdninPacketLog.shutdown();
                    for (int i = 0; i < 1000; ++i) {
                        AdninPacketLog.install(afterStop.network);
                        AdninPacketLog.shutdown();
                    }
                    unloadGate.set(AdninPacketLog.isQuiescent());
                } catch (Throwable problem) {
                    callerFailure.set(problem);
                } finally {
                    callerFinished.countDown();
                }
            }
        }, "packetlog-test-shutdown-" + threadIds.incrementAndGet());
        controller.setDaemon(true);
        int pending;
        boolean returned;
        boolean gateDuringObservation;
        try {
            check(inFlightPacket.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "Actual registered-channel spawn decoding reaches the blocked fixture getter");
            check(AdninPacketLog.seenSpawnPlayerCount == observedBeforeStop + 1,
                "In-flight spawn entered the observation region before shutdown");
            controller.start();
            returned = callerFinished.await(2000L, TimeUnit.MILLISECONDS);
            gateDuringObservation = Boolean.TRUE.equals(unloadGate.get());
            pending = ((SingleThreadEventExecutor) active.loop).pendingTasks();
        } finally {
            // Even a broken blocking shutdown is released without deadlocking
            // the test JVM; only daemon workers and bounded waits are used.
            inFlightPacket.release.countDown();
            callerFinished.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        check(returned, "Shutdown returns while an actual spawn observation remains blocked");
        check(callerFailure.get() == null, "Shutdown controller completes without exception: " + callerFailure.get());
        check(!gateDuringObservation && Boolean.FALSE.equals(unloadGate.get()),
            "Unload gate stays closed after shutdown until the admitted observation leaves");
        await(inFlight, "In-flight spawn resumes and completes its finally cleanup");
        check(!inFlightPacket.timedOut, "Owned spawn getter is explicitly released without timeout");
        drain(active.loop);
        check(AdninPacketLog.isQuiescent(), "Unload gate opens only after the stopped observer has no in-flight work");
        check(pending <= 2, "Shutdown queues at most one lifecycle task in addition to the owned pending packet");
        check(active.collector.packet == stalePacket && active.collector.reads == readsBeforeStop + 2,
            "In-flight and queued packets both traverse the retired handler unchanged exactly once");
        check(AdninPacketLog.seenSpawnPlayerCount == observedBeforeStop + 1,
            "Shutdown immediately prevents spawn observation before asynchronous handler removal");
        check(AdninPacketLog.lastSpawnLine.contains("entityId=42 "),
            "Completed admitted spawn remains the last observed packet; later stopped spawn cannot overwrite it");
        check(awaitHandler(active, false) == null, "Shutdown retires the previously installed owned handler");
        check(awaitHandler(waiting, false) == null, "Pre-shutdown pending install cannot become active later");
        check(awaitHandler(afterStop, false) == null, "Post-shutdown install cannot revive the observer");
        check(field("current") == null && field("pending") == null && field("installed") == null,
            "Shutdown releases current, pending and installed channel references after loop drain");
        check(field("spawnAccessors") == null,
            "Shutdown clears cached packet accessors and an in-flight observation cannot republish them");
        passThrough(active);
        passThrough(waiting);
        passThrough(afterStop);
    }

    private static void crossLoopRoundTrip(final Fixture active) throws Exception {
        final Fixture remote = fixture(otherGroup);
        check(active.loop != remote.loop, "Cross-channel fixture uses two distinct real event-loop threads");
        final ChannelHandler original = awaitHandler(active, true);
        int pending = withBlockedTaskQueue(active.loop, new Runnable() {
            public void run() {
                AdninPacketLog.install(remote.network);
                AdninPacketLog.install(active.network);
            }
        });
        check(pending <= 1, "Cross-loop A-to-B-to-A cancellation retains at most one lifecycle task");
        check(awaitHandler(active, true) == original,
            "A-to-B-to-A before removal retains A's exact installed observer");
        check(awaitHandler(remote, false) == null, "Cancelled cross-loop B intent never installs later");

        AdninPacketLog.install(remote.network);
        drain(active.loop);
        check(awaitHandler(remote, true) != null && awaitHandler(active, false) == null,
            "Completed cross-loop A-to-B transition removes A and installs B");
        pending = withBlockedTaskQueue(remote.loop, new Runnable() {
            public void run() {
                AdninPacketLog.install(active.network);
                AdninPacketLog.install(remote.network);
                for (int i = 0; i < 1000; ++i) AdninPacketLog.install(active.network);
            }
        });
        check(pending <= 1, "Cross-loop B-to-A-to-B-to-A replacement keeps lifecycle queue bounded");
        check(awaitHandler(active, true) != null && awaitHandler(remote, false) == null,
            "Cross-loop rapid return activates only the latest A channel");
        check(countOwned(active) == 1, "Cross-loop return leaves exactly one observer on A");
        passThrough(active);
        passThrough(remote);
    }

    private static void externalHandlerConflict(final Fixture active) throws Exception {
        foreignFixture = fixture(otherGroup);
        foreignHandler = new ChannelInboundHandlerAdapter();
        await(foreignFixture.loop.submit(new Runnable() {
            public void run() {
                foreignFixture.channel.pipeline().addBefore("packet_handler", KEY, foreignHandler);
            }
        }), "Prepare an independently owned same-name handler");
        AdninPacketLog.install(foreignFixture.network);
        drain(active.loop);
        check(foreignFixture.channel.pipeline().get(KEY) == foreignHandler,
            "Install conflict preserves the exact independent handler");
        check("install:handler_conflict".equals(AdninPacketLog.installState),
            "Conflicting handler is reported rather than silently replaced");
        check(field("installed") == null && field("current") == null && field("pending") == null,
            "Conflict retires lifecycle ownership without retaining stale channel work");
        check(awaitHandler(active, false) == null, "Conflict does not leave a stale observer on the prior channel");
        passThrough(foreignFixture);
        AdninPacketLog.install(active.network);
        drain(active.loop);
        check(awaitHandler(active, true) != null, "Observer can recover onto a valid channel after conflict");
        check(foreignFixture.channel.pipeline().get(KEY) == foreignHandler,
            "Subsequent valid installation still leaves the independent handler untouched");
    }

    /**
     * Reproduces the wait precondition from Minecraft's task ArrayDeque:
     * the event loop is blocked acquiring that monitor when install runs.
     * The driver retains the monitor and invokes install on a daemon caller,
     * rather than letting the caller own it. This preserves the assertion that
     * install must not wait on the event loop, but lets the driver unconditionally
     * release the monitor after a timeout even against the unfixed synchronous
     * Netty 4.0.23 pipeline.remove implementation.
     */
    private static int withBlockedTaskQueue(final EventLoop loop, final Runnable operation) throws Exception {
        final Object minecraftTaskQueue = new ArrayDeque<Object>();
        final CountDownLatch loopAtMonitor = new CountDownLatch(1);
        final CountDownLatch loopReleased = new CountDownLatch(1);
        final CountDownLatch callerFinished = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final Thread caller = new Thread(new Runnable() {
            public void run() {
                try { operation.run(); }
                catch (Throwable problem) { failure.set(problem); }
                finally { callerFinished.countDown(); }
            }
        }, "packetlog-test-client-" + threadIds.incrementAndGet());
        caller.setDaemon(true);
        boolean returned;
        int pending;
        try {
            synchronized (minecraftTaskQueue) {
                loop.execute(new Runnable() {
                    public void run() {
                        loopAtMonitor.countDown();
                        synchronized (minecraftTaskQueue) { loopReleased.countDown(); }
                    }
                });
                check(loopAtMonitor.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "Real Netty event loop reaches the held task-queue monitor");
                caller.start();
                returned = callerFinished.await(2000L, TimeUnit.MILLISECONDS);
                pending = ((SingleThreadEventExecutor) loop).pendingTasks();
            }
        } finally {
            // Exiting synchronized always releases the lock, including timeouts.
            // Never wait for a caller while retaining the simulated MC lock.
            callerFinished.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        check(loopReleased.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "Event loop resumes after the task-queue monitor is released");
        check(returned, "install/shutdown must return while the real Netty event loop is blocked on Minecraft's task queue");
        check(failure.get() == null, "Client operation completes without exception: " + failure.get());
        check(callerFinished.getCount() == 0, "Daemon caller leaves no blocked test work");
        drain(loop);
        return pending;
    }

    private static void passThrough(final Fixture fixture) throws Exception {
        final Object packet = new Object();
        final Object userEvent = new Object();
        final Exception error = new Exception("owned test exception");
        final int reads = fixture.collector.reads;
        final int completes = fixture.collector.completes;
        await(fixture.loop.submit(new Runnable() {
            public void run() {
                fixture.channel.pipeline().fireChannelRead(packet);
                fixture.channel.pipeline().fireChannelReadComplete();
                fixture.channel.pipeline().fireUserEventTriggered(userEvent);
                fixture.channel.pipeline().fireExceptionCaught(error);
            }
        }), "Ordinary inbound events traverse the pipeline");
        check(fixture.collector.packet == packet && fixture.collector.reads == reads + 1,
            "Ordinary packet reaches the original handler unchanged exactly once");
        check(fixture.collector.completes == completes + 1, "Read-complete event passes exactly once");
        check(fixture.collector.userEvent == userEvent, "User event passes unchanged");
        check(fixture.collector.error == error, "Exception passes unchanged to the original handler");
    }

    private static void spawnObservation(final Fixture fixture) throws Exception {
        final FakeSpawnPacket packet = new FakeSpawnPacket();
        final int reads = fixture.collector.reads;
        final int observed = AdninPacketLog.seenSpawnPlayerCount;
        final Throwable priorError = fixture.collector.error;
        await(fixture.loop.submit(new Runnable() {
            public void run() { fixture.channel.pipeline().fireChannelRead(packet); }
        }), "Owned spawn fixture traverses the active observer");
        check(AdninPacketLog.seenSpawnPlayerCount == observed + 1, "Active spawn fixture increments observation exactly once");
        check(("S0CPacketSpawnPlayer entityId=41 uuid=12345678-1234-4000-8000-000000000041"
            + " x=320 y=2048 z=-640 yaw=90 pitch=45 item=272").equals(AdninPacketLog.lastSpawnLine),
            "Spawn entity, UUID, coordinates, rotations and item are decoded from the actual fixture");
        check(fixture.collector.packet == packet && fixture.collector.reads == reads + 1,
            "Unbound native spawn callback does not swallow the original packet");
        check(fixture.collector.error == priorError, "Unbound native symbol does not escape into the game's exception pipeline");
    }

    private static void decodedFallback() throws Exception {
        final Fixture fixture = new Fixture();
        final Object encoded = new Object(); final FakeSpawnPacket decoded = new FakeSpawnPacket();
        fixture.channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
                context.fireChannelRead(packet == encoded ? decoded : packet);
            }
        });
        fixture.channel.pipeline().addLast("custom_consumer", fixture.collector);
        await(group.register(fixture.channel), "Owned decoder-only channel registers");
        fixture.loop=fixture.channel.eventLoop(); fixtures.add(fixture);
        AdninPacketLog.install(fixture.network); check(awaitHandler(fixture,true)!=null,"Decoder-only fallback installs");
        check(fixture.channel.pipeline().names().indexOf(KEY)>fixture.channel.pipeline().names().indexOf("decoder"),
            "Fallback must observe decoded packets after decoder, never raw transport buffers before it");
        int before=AdninPacketLog.seenSpawnPlayerCount, reads=fixture.collector.reads;
        await(fixture.loop.submit(new Runnable(){public void run(){fixture.channel.pipeline().fireChannelRead(encoded);}}),
            "Encoded fixture passes through the real registered pipeline");
        check(AdninPacketLog.seenSpawnPlayerCount==before+1,"Spawn observation receives the decoder's output");
        check(fixture.collector.packet==decoded && fixture.collector.reads==reads+1,
            "Game consumer receives the exact decoded packet once");
    }

    private static void modeResponseObservation(final Fixture fixture) throws Exception {
        Class<?> window=Class.forName("AdninPartyQueueQuery$ResponseWindow");
        java.lang.reflect.Constructor<?> create=window.getDeclaredConstructor(Object.class,Object.class,Object.class,long.class);
        create.setAccessible(true);
        Field response=AdninPartyQueueQuery.class.getDeclaredField("response"); response.setAccessible(true);
        Field result=window.getDeclaredField("mode"); result.setAccessible(true);
        Object world=new Object(), token=AdninPacketLog.observerToken(fixture.network);
        check(token!=null,"Only an installed observer supplies a mode response token");
        String valid="{\"server\":\"mini123A\",\"gametype\":\"BEDWARS\",\"mode\":\"eight_two\"}";
        try {
            for (int scenario=0;scenario<8;scenario++) {
                net.minecraft.util.IChatComponent component=new net.minecraft.util.ChatComponentText(valid);
                byte type=0;
                if(scenario==1)type=1;
                if(scenario==2)type=2;
                if(scenario==3)component.getChatStyle().setColor(net.minecraft.util.EnumChatFormatting.GRAY);
                if(scenario==4)component.appendText("extra");
                if(scenario==5)component=new net.minecraft.util.ChatComponentText("[Player] "+valid);
                long now=System.nanoTime()/1000000L;
                Object pending=create.newInstance(world,fixture.network,scenario==6?new Object():token,scenario==7?now-5000L:now);
                response.set(null,pending);
                final Object packet=new net.minecraft.network.play.server.S02PacketChat(component,type);
                int reads=fixture.collector.reads;
                Throwable priorError=fixture.collector.error;
                await(fixture.loop.submit(new Runnable(){public void run(){fixture.channel.pipeline().fireChannelRead(packet);}}),
                    "Decoded server chat traverses the actual observer");
                check(((AtomicInteger)result.get(pending)).get()==(scenario<=1?2:0),
                    "Only current plain server chat response is learned, scenario="+scenario);
                check(fixture.collector.packet==packet && fixture.collector.reads==reads+1,
                    "Every accepted or rejected response reaches the original consumer once, scenario="+scenario);
                check(fixture.collector.error==priorError,"Optional mode reader does not interrupt packet delivery");
            }
        } finally { response.set(null,null); }
    }

    private static Fixture fixture() throws Exception { return fixture(group); }

    private static Fixture fixture(LocalEventLoopGroup owner) throws Exception {
        Fixture result = new Fixture();
        result.channel.pipeline().addLast("packet_handler", result.collector);
        await(owner.register(result.channel), "Local channel registers without bind or connect");
        result.loop = result.channel.eventLoop();
        if (!loops.contains(result.loop)) loops.add(result.loop);
        fixtures.add(result);
        check(result.channel.isRegistered() && result.channel.isOpen() && !result.channel.isActive(),
            "Fixture is a registered, open, unconnected local channel");
        return result;
    }

    private static ChannelHandler awaitHandler(final Fixture fixture, final boolean present) throws Exception {
        ChannelHandler result = null;
        // Channel switching may queue removal followed by installation; each
        // barrier advances that actual event-loop work without arbitrary sleeps.
        for (int i = 0; i < 12; ++i) {
            Future<ChannelHandler> future = fixture.loop.submit(new Callable<ChannelHandler>() {
                public ChannelHandler call() { return fixture.channel.pipeline().get(KEY); }
            });
            await(future, "Inspect owned observer on its event loop");
            result = future.getNow();
            if ((result != null) == present) return result;
        }
        check(false, "Expected owned observer presence=" + present + ", state=" + AdninPacketLog.installState);
        return result;
    }

    private static int countOwned(final Fixture fixture) throws Exception {
        Future<Integer> result = fixture.loop.submit(new Callable<Integer>() {
            public Integer call() {
                int count = 0;
                for (String name : fixture.channel.pipeline().names()) if (KEY.equals(name)) ++count;
                return count;
            }
        });
        await(result, "Count owned handlers");
        return result.getNow();
    }

    private static void drain(EventLoop loop) throws Exception {
        for (int i = 0; i < 6; ++i) {
            await(loop.submit(new Runnable() { public void run() { } }), "Drain event-loop lifecycle work");
            for (EventLoop other : loops) if (other != loop) {
                await(other.submit(new Runnable() { public void run() { } }), "Drain cross-loop lifecycle work");
            }
        }
    }

    private static Object field(String name) throws Exception {
        Field field = AdninPacketLog.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void grayPacketForwarding(final Fixture fixture) throws Exception {
        Field snapshot = AdninFeatures.class.getDeclaredField("ignoredPlayers"); snapshot.setAccessible(true);
        Object original = snapshot.get(null);
        java.lang.reflect.Constructor<?> constructor = original.getClass().getDeclaredConstructor(java.util.Set.class,java.util.Set.class,java.util.Set.class);
        constructor.setAccessible(true);
        final FakeSpawnPacket gray = new FakeSpawnPacket();
        int before = fixture.collector.reads;
        String last = AdninPacketLog.lastSpawnLine;
        try {
            snapshot.set(null,constructor.newInstance(java.util.Collections.emptySet(),java.util.Collections.singleton(gray.getPlayerUUID()),java.util.Collections.emptySet()));
            await(fixture.loop.submit(new Runnable(){ public void run(){fixture.channel.pipeline().fireChannelRead(gray);} }),"Forward gray packet through registered observer");
            check(fixture.collector.reads==before+1 && fixture.collector.packet==gray,
                "Ignoring gray Adnin observations still forwards the exact original game packet once");
            check(last.equals(AdninPacketLog.lastSpawnLine),"Gray packet never reaches the auxiliary spawn decoder/native callback");
        } finally { snapshot.set(null,original); }
    }

    private static void await(Future<?> future, String why) throws Exception {
        check(future.await(TIMEOUT_MS), why + " (bounded wait)");
        check(future.isSuccess(), why + " (success): " + future.cause());
    }

    private static void check(boolean condition, String why) {
        ++checks;
        if (!condition) throw new AssertionError(why);
    }

    public static final class FakeNetworkHandler {
        public final FakeNetworkManager networkManager;
        FakeNetworkHandler(LocalChannel channel) { networkManager = new FakeNetworkManager(channel); }
    }
    public static final class FakeNetworkManager {
        public final LocalChannel channel;
        FakeNetworkManager(LocalChannel value) { channel = value; }
    }
    public static class FakeSpawnPacket {
        public int getEntityID() { return 41; }
        public UUID getPlayerUUID() { return UUID.fromString("12345678-1234-4000-8000-000000000041"); }
        public int getX() { return 320; }
        public int getY() { return 2048; }
        public int getZ() { return -640; }
        public int getYaw() { return 90; }
        public int getPitch() { return 45; }
        public int getCurrentItemID() { return 272; }
    }
    public static final class BlockingSpawnPacket extends FakeSpawnPacket {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean timedOut;
        @Override public int getEntityID() {
            entered.countDown();
            try {
                if (!release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) timedOut = true;
            } catch (InterruptedException interrupted) {
                timedOut = true;
                Thread.currentThread().interrupt();
            }
            return 42;
        }
    }
    private static final class Fixture {
        final LocalChannel channel = new LocalChannel();
        final Collector collector = new Collector();
        final FakeNetworkHandler network = new FakeNetworkHandler(channel);
        EventLoop loop;
    }
    private static final class Collector extends ChannelInboundHandlerAdapter {
        volatile int added, removed, reads, completes;
        volatile Object packet, userEvent;
        volatile Throwable error;
        public void handlerAdded(ChannelHandlerContext context) { ++added; }
        public void handlerRemoved(ChannelHandlerContext context) { ++removed; }
        public void channelRead(ChannelHandlerContext context, Object value) { packet = value; ++reads; }
        public void channelReadComplete(ChannelHandlerContext context) { ++completes; }
        public void userEventTriggered(ChannelHandlerContext context, Object value) { userEvent = value; }
        public void exceptionCaught(ChannelHandlerContext context, Throwable value) { error = value; }
    }
}
