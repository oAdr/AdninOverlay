import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;

/** Executes the owned scheduler solely against a queue-only game fixture. */
public final class AdninCompatPumpTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void awaitPending(Minecraft minecraft) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (minecraft.pending.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10L);
        check(minecraft.pending.size() == 1, "Expected exactly one pending callback");
    }
    private static void join(Thread thread) throws Exception {
        thread.join(3000L);
        check(!thread.isAlive(), "Thread did not finish: "+thread.getName());
    }
    private static void await(CountDownLatch latch) throws Exception {
        check(latch.await(3L, TimeUnit.SECONDS), "Fixture synchronization timed out");
    }
    private static void drain(Minecraft minecraft) {
        Runnable delivery;
        while ((delivery = minecraft.pending.poll()) != null) delivery.run();
    }
    private static void queuedStop() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        Minecraft minecraft = new Minecraft();
        AdninCompatPump.start(minecraft, new Runnable() { public void run() { calls.incrementAndGet(); } });
        awaitPending(minecraft);
        Thread scheduler = minecraft.schedulingThread;
        AdninCompatPump.stop();
        join(scheduler);
        drain(minecraft);
        check(calls.get() == 0, "Queued delivery ran after native unload barrier");
        check(!AdninCompatPump.isRunning(), "Stopped pump still reports running");
        AdninCompatPump.start(minecraft, new Runnable() { public void run() { calls.incrementAndGet(); } });
        AdninCompatPump.stop();
        check(minecraft.attempts.get() == 1, "Stopped class restarted");
    }
    private static void activeStop() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch stopping = new CountDownLatch(1);
        final AtomicBoolean returned = new AtomicBoolean();
        final AtomicBoolean completed = new AtomicBoolean();
        final Minecraft minecraft = new Minecraft();
        AdninCompatPump.start(minecraft, new Runnable() { public void run() {
            entered.countDown();
            try {
                if (!release.await(5L, TimeUnit.SECONDS)) throw new AssertionError("Callback was not released");
                if (returned.get()) throw new AssertionError("stop returned during native callback");
                completed.set(true);
            } catch (InterruptedException failure) { throw new AssertionError(failure); }
        }});
        awaitPending(minecraft);
        Thread client = new Thread(new Runnable() { public void run() { minecraft.pending.remove().run(); } }, "fixture client");
        client.start();
        await(entered);
        Thread stopper = new Thread(new Runnable() { public void run() {
            stopping.countDown(); AdninCompatPump.stop(); returned.set(true);
        }}, "fixture unload");
        stopper.start();
        try {
            await(stopping);
            long deadline = System.currentTimeMillis()+3000L;
            while (stopper.getState()!=Thread.State.BLOCKED && !returned.get() && System.currentTimeMillis()<deadline) Thread.yield();
            check(stopper.getState()==Thread.State.BLOCKED && !returned.get(), "stop did not wait for active delivery");
        } finally { release.countDown(); }
        join(client); join(stopper); join(minecraft.schedulingThread);
        check(returned.get() && completed.get(), "Unload barrier did not finish after callback");
        drain(minecraft);
        check(!AdninCompatPump.isRunning(), "Active stop failed to stop pump");
    }
    private static void stopBeforeStart() {
        Minecraft minecraft = new Minecraft();
        AdninCompatPump.stop();
        AdninCompatPump.start(minecraft, new Runnable() { public void run() { throw new AssertionError("Restarted"); } });
        check(!AdninCompatPump.isRunning(), "Stop-before-start allowed restart");
        check(minecraft.attempts.get()==0 && minecraft.schedulingThread==null, "Stop-before-start created a daemon");
    }
    private static void startStopRace() throws Exception {
        final Minecraft minecraft = new Minecraft();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[16];
        for (int i=0;i<workers.length;i++) {
            final boolean stop = i%2==0;
            workers[i] = new Thread(new Runnable() { public void run() {
                try {
                    start.await();
                    if (stop) AdninCompatPump.stop();
                    else AdninCompatPump.start(minecraft, new Runnable() { public void run() { calls.incrementAndGet(); } });
                } catch (Throwable failure) { failures.incrementAndGet(); }
            }});
            workers[i].start();
        }
        start.countDown();
        for (Thread worker:workers) join(worker);
        AdninCompatPump.stop();
        if (minecraft.schedulingThread!=null) join(minecraft.schedulingThread);
        drain(minecraft);
        check(failures.get()==0, "start/stop race threw an exception");
        check(calls.get()==0 && !AdninCompatPump.isRunning(), "start/stop race left an active delivery");
        check(minecraft.attempts.get()<=1, "start/stop race duplicated scheduler");
    }
    private static void reentrantStop() throws Exception {
        final AtomicInteger heartbeats = new AtomicInteger();
        Minecraft minecraft = new Minecraft();
        AdninCompatPump.start(minecraft, new Runnable() { public void run() {
            AdninCompatPump.stop();
            if (AdninCompatPump.isRunning()) heartbeats.incrementAndGet();
        }});
        awaitPending(minecraft);
        minecraft.pending.remove().run();
        join(minecraft.schedulingThread);
        check(heartbeats.get()==0, "Reentrant stop permitted native heartbeat");
        check(!AdninCompatPump.isRunning(), "Reentrant stop left pump running");
    }
    public static void main(String[] args) throws Exception {
        String scenario = args.length==0 ? "normal" : args[0];
        if (scenario.equals("normal")) normal();
        else if (scenario.equals("queued-stop")) queuedStop();
        else if (scenario.equals("active-stop")) activeStop();
        else if (scenario.equals("stop-before-start")) stopBeforeStart();
        else if (scenario.equals("start-stop-race")) startStopRace();
        else if (scenario.equals("reentrant-stop")) reentrantStop();
        else throw new IllegalArgumentException(scenario);
        check(AdninGameModules.stopped, "Unload barrier must stop the game module hooks");
        System.out.println("AdninCompatPumpTest "+scenario+": "+checks+" checks passed");
    }
    private static void normal() throws Exception {
        final Thread client = Thread.currentThread();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger throwsRemaining = new AtomicInteger();
        Minecraft minecraft = new Minecraft();
        Runnable callback = new Runnable() {
            @Override public void run() {
                check(Thread.currentThread() == client, "Feature callback ran off the client thread");
                calls.incrementAndGet();
                if (throwsRemaining.getAndSet(0) != 0) throw new IllegalStateException("Fixture callback failure");
            }
        };
        AdninCompatPump.start(minecraft, callback);
        AdninCompatPump.start(minecraft, callback);
        Minecraft ignored = new Minecraft();
        AdninCompatPump.start(ignored, callback);
        Thread daemon = null;
        try {
            awaitPending(minecraft);
            check(calls.get() == 0, "Daemon directly executed the feature callback");
            check(minecraft.schedulingThread != client, "Scheduling did not use daemon");
            Thread.sleep(350L);
            check(minecraft.pending.size() == 1, "Scheduler built a backlog");
            check(minecraft.attempts.get() == 1, "Scheduler resubmitted while a callback was pending");
            check(ignored.attempts.get() == 0, "Repeated start created a second scheduler");
            int matching = 0;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.isAlive() && thread.getName().equals("Adnin client scheduler")) { matching++; daemon=thread; }
            }
            check(matching == 1 && daemon != null && daemon.isDaemon(), "Expected one daemon thread");
            minecraft.pending.remove().run();
            check(calls.get() == 1, "Queued callback did not execute on client");
            awaitPending(minecraft);
            minecraft.failures.set(2);
            minecraft.pending.remove().run();
            int completedBeforeRetry = calls.get();
            awaitPending(minecraft);
            check(minecraft.failures.get() == 0, "Failed scheduling was not retried");
            check(calls.get() == completedBeforeRetry, "Scheduling failure caused off-thread callback");
            check(minecraft.pending.size() == 1, "Retry produced duplicate callbacks");
            throwsRemaining.set(1);
            try { minecraft.pending.remove().run(); throw new AssertionError("Expected fixture callback failure"); }
            catch (IllegalStateException expected) { }
            awaitPending(minecraft);
            minecraft.pending.remove().run();
            check(calls.get() == completedBeforeRetry+2, "Callback exception left scheduler permanently queued");
            // The fixture has no world/player/render methods, establishing that
            // a client at the menu can still receive readiness callbacks.
            System.out.println("AdninCompatPumpTest: "+checks+" checks passed; bounded queue, client-only callbacks, retry recovery, single daemon, menu-independent");
        } finally {
            AdninCompatPump.stop();
            if (daemon == null) daemon = minecraft.schedulingThread;
            if (daemon != null) { daemon.interrupt(); daemon.join(2000L); }
        }
    }
}
