import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;

/** Offline rules and an in-memory Netty pipeline; never starts Minecraft or audio. */
public final class AdninClientSoundsTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--pending-only".equals(args[0])) { registeredLifecycle(true); return; }
        placementRules(); suppressionRules(); queueBounds(); pipelineBehavior(); queueOverflowPipeline(); leaseCleanup();
        registeredLifecycle(false); channelReuse();
        AdninClientSounds.tick(null,true,true);
        AdninClientSounds.shutdown();
        check(active().get(null) == null,"Null world and shutdown leave no session");
        System.out.println("AdninClientSoundsTest: " + checks
            + " checks passed; placement rules, packet pass-through, bounded blocked-EventLoop lifecycle and channel reuse; no game, audio, settings or network");
    }
    private static void placementRules() {
        double[][] places = {{10.5,19.5,-2.5},{10.5,21.5,-2.5},{10.5,20.5,-3.5},
            {10.5,20.5,-1.5},{9.5,20.5,-2.5},{11.5,20.5,-2.5}};
        for (int face=0; face<6; face++) {
            AdninClientSounds.SoundPlan plan=plan(1,false,false,true,face,"dig.stone");
            check(plan != null,"A solid clicked block accepts every real face");
            xyz(plan.x,plan.y,plan.z,places[face],"Face selects correct adjacent block center");
            xyz(plan.clickedX,plan.clickedY,plan.clickedZ,new double[]{10.5,20.5,-2.5},"Clicked center remains a duplicate-sound candidate");
            check("dig.stone".equals(plan.sound),"Held block supplies placement sound identifier");
        }
        // Behavioral fixtures from the supplied 1.1 JAR's static block-ID tables.
        int[] functional={23,25,26,36,54,61,62,63,64,68,58,69,71,77,84,85,92,93,94,96,107,113,116,117,118,122,130,137,138,140,143,145,146,149,150,151,154,167,178,183,184,185,186,187,188,189,190,191,192,193,194,195,196};
        for(int block:functional) {
            check(plan(block,false,false,true,1,"dig.wood")==null,"Ordinary interaction with functional block is silent: "+block);
            check(plan(block,true,false,true,1,"dig.wood")!=null,"Sneaking permits placement on functional block: "+block);
        }
        for(int block:new int[]{6,8,9,10,11,31,32,78,106}) for(int face=0;face<6;face++) {
            AdninClientSounds.SoundPlan plan=plan(block,false,false,true,face,"dig.grass");
            check(plan!=null,"Replaceable target permits held-block placement");
            xyz(plan.x,plan.y,plan.z,new double[]{10.5,20.5,-2.5},"Replaceable block keeps clicked position for every face");
        }
        check(plan(1,false,true,true,1,"dig.stone")==null,"Creative mode does not add prediction sound");
        check(plan(1,false,false,false,1,"dig.stone")==null,"Non-block held items are ignored");
        for(int face:new int[]{-1,6,255}) check(plan(1,false,false,true,face,"dig.stone")==null,"Air-use or invalid face is ignored");
        check(plan(1,false,false,true,1,null)==null && plan(1,false,false,true,1,"")==null,"Missing sound is ignored");
        check(AdninClientSounds.VOLUME==1.0f && AdninClientSounds.PITCH==0.7936508f,"Volume and pitch preserve supplied mod values");
        check(AdninClientSounds.retention(-1)==500 && AdninClientSounds.retention(0)==500
            && AdninClientSounds.retention(123)==623,"Normal suppression window uses ping plus 500 ms with safe unknown-ping fallback");
        check(AdninClientSounds.retention(Long.MAX_VALUE)==60000,"Pathological ping cannot overflow expiry or retain sounds indefinitely");
    }
    private static void suppressionRules() {
        AdninClientSounds.Ledger ledger=new AdninClientSounds.Ledger();
        AdninClientSounds.Pending pending=ledger.begin(10,20,-3,1,1000);
        check(pending!=null && ledger.waitingAt(10.5,20.5,-2.5,1000),"Pending clicked center can wait for client-thread decision");
        check(ledger.waitingAt(10.5,21.5,-2.5,1000),"Pending adjacent center can wait for client-thread decision");
        check(!ledger.waitingAt(10.5,22.5,-2.5,1000),"Unrelated coordinates are not held");
        check(!ledger.suppresses("dig.stone",10.5,21.5,-2.5,1000),"An unplayed pending placement does not suppress anything");
        ledger.finish(pending.id,plan(1,false,false,true,1,"dig.stone"),1000,100);
        check(ledger.pending.isEmpty(),"Resolved prediction releases pending slot");
        check(ledger.suppresses("dig.stone",10.5,20.5,-2.5,1600),"Clicked-center duplicate is suppressed through exact expiry");
        check(ledger.suppresses("minecraft:dig.stone",10.5,21.5,-2.5,1600),"Placed-center default-namespace alias is the same sound");
        check(!ledger.suppresses("random.explode",10.5,21.5,-2.5,1500),"Unrelated sound at the same point remains audible");
        check(!ledger.suppresses("other:dig.stone",10.5,21.5,-2.5,1500),"Other namespaces remain distinct");
        check(!ledger.suppresses("dig.stone",10.5,21.5,-2.5,1601),"Server sound resumes after ping-plus-500 window");
        pending=ledger.begin(10,20,-3,1,2000);
        ledger.finish(pending.id,null,2001,100);
        check(!ledger.waitingAt(10.5,21.5,-2.5,2001) && ledger.recent.isEmpty(),"Rejected or failed playback never creates suppression");
        pending=ledger.begin(10,20,-3,1,3000);
        check(ledger.get(pending.id,5000)!=null && ledger.get(pending.id,5001)==null,"Pending client work has a bounded lifetime");
        ledger.finish(pending.id,plan(1,false,false,true,1,"dig.stone"),5001,100);
        check(ledger.recent.isEmpty(),"Expired work cannot add a late sound suppression entry");
        pending=ledger.begin(10,20,-3,1,6000);
        ledger.finish(pending.id,plan(1,false,false,true,1,"dig.stone"),6000,1000);
        pending=ledger.begin(10,20,-3,1,6100);
        ledger.finish(pending.id,plan(1,false,false,true,1,"dig.stone"),6100,0);
        check(ledger.suppresses("dig.stone",10.5,21.5,-2.5,7500),"A later shorter prediction cannot shorten an existing suppression window");
        ledger.clear();
        check(ledger.pending.isEmpty() && ledger.recent.isEmpty(),"Disable/world clear releases all prediction metadata");
    }
    private static void queueBounds() {
        AdninClientSounds.Ledger ledger=new AdninClientSounds.Ledger();
        for(int i=0;i<AdninClientSounds.MAX_PENDING;i++) check(ledger.begin(i,0,0,1,1000)!=null,"Pending fixture fills bounded capacity");
        check(ledger.begin(999,0,0,1,1000)==null && ledger.pending.size()==AdninClientSounds.MAX_PENDING,"Overflow leaves pending map bounded");
        ledger.expire(3001);
        check(ledger.begin(999,0,0,1,3001)!=null,"Expired pending slots become reusable");
        ledger.clear();
        for(int i=0;i<300;i++) {
            AdninClientSounds.Pending pending=ledger.begin(i,0,0,1,5000);
            ledger.finish(pending.id,AdninClientSounds.predict(1,false,false,true,1,i,0,0,"dig.stone"),5000,100);
        }
        check(ledger.recent.size()==AdninClientSounds.MAX_RECENT,"Recent suppression metadata is bounded independently of placement rate");
        check(!ledger.suppresses("dig.stone",0.5,1.5,0.5,5000),"Oldest excess suppression entries are evicted");
        check(ledger.suppresses("dig.stone",299.5,1.5,0.5,5000),"Newest suppression entries remain available");
    }
    private static void pipelineBehavior() throws Exception {
        EmbeddedChannel channel=new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        channel.pipeline().replace(channel.pipeline().first(),"packet_handler",new ChannelInboundHandlerAdapter());
        FakePackets packets=new FakePackets();
        AdninClientSounds.Session session=new AdninClientSounds.Session(null,null,new Object(),scheduledChannel(channel),packets);
        Field active=active();
        active.set(null,session);
        try {
            session.setModes(true,false);
            long now=System.nanoTime()/1000000L;
            session.requestInstall(now); channel.runPendingTasks();
            check(session.installed && channel.pipeline().get(AdninClientSounds.HANDLER_NAME)==session.handler,"One duplex handler installs before packet handler");
            session.requestInstall(now+2000); channel.runPendingTasks();
            int handlers=0; for(String name:channel.pipeline().names()) if(name.equals(AdninClientSounds.HANDLER_NAME)) handlers++;
            check(handlers==1,"Repeated ticks never install a second sound handler");
            FakePlacement placement=new FakePlacement(10,20,-3,1);
            check(channel.writeOutbound(placement) && channel.readOutbound()==placement,"Outgoing placement object is forwarded unchanged");
            check(session.placements.size()==1 && session.ledger.pending.size()==1,"Outgoing placement is recorded once despite duplex direction");
            FakeSound duplicate=sound("dig.stone",10.5,21.5,-2.5);
            boolean passed=channel.writeInbound(duplicate);
            check(!passed && session.deferred.size()==1,"Fast response waits for the client-thread placement decision: passed="
                +passed+", deferred="+session.deferred.size()+", live="+session.live()+", pending="+session.ledger.pending.size());
            AdninClientSounds.Placement event=session.placements.poll();
            session.ledger.finish(event.id,plan(1,false,false,true,1,"dig.stone"),now,100);
            session.drainDeferred(now); channel.runPendingTasks();
            check(session.deferred.isEmpty() && channel.readInbound()==null,"A completed local prediction suppresses its deferred server duplicate");
            check(!channel.writeInbound(sound("dig.stone",10.5,20.5,-2.5)),"Later clicked-center duplicate is also suppressed");
            FakeSound unrelated=sound("random.explode",10.5,21.5,-2.5);
            check(channel.writeInbound(unrelated) && channel.readInbound()==unrelated,"Unrelated sound at a predicted point passes unchanged");
            Object marker=new Object();
            check(channel.writeInbound(marker) && channel.readInbound()==marker,"Non-packet input passes through unchanged");
            session.ledger.clear();
            check(channel.writeOutbound(placement) && channel.readOutbound()==placement,"A subsequent placement still forwards unchanged");
            FakeSound held=sound("dig.wood",10.5,21.5,-2.5);
            check(!channel.writeInbound(held) && session.deferred.size()==1,"Potential sound waits while placement is unresolved");
            session.setModes(false,true); channel.runPendingTasks();
            check(channel.readInbound()==held,"Turning sounds off releases held sound instead of swallowing it");
            check(session.placements.isEmpty() && session.ledger.pending.isEmpty() && session.ledger.recent.isEmpty(),"Turning sounds off clears all placement metadata");
            check(channel.writeOutbound(placement) && channel.readOutbound()==placement && session.placements.isEmpty(),"Observation-only mode never queues outgoing placement work");
            FakeSound observed=sound("dig.stone",10.5,21.5,-2.5);
            check(channel.writeInbound(observed) && channel.readInbound()==observed,"Observation-only mode passes actual incoming packets unchanged");
            check(packets.received==1,"Observation-only mode notifies packet observer exactly once");
            check(channel.writeInbound(marker) && channel.readInbound()==marker && packets.received==1,"Non-packet objects never notify the packet observer");
            session.setModes(true,false);
            check(channel.writeOutbound(placement) && channel.readOutbound()==placement,"Re-enabling sounds begins observing placements again");
            held=sound("dig.stone",10.5,21.5,-2.5);
            check(!channel.writeInbound(held),"Shutdown fixture contains a deferred sound");
            AdninClientSounds.shutdown(); channel.runPendingTasks();
            check(channel.readInbound()==held,"Shutdown releases pending server sound");
            check(channel.pipeline().get(AdninClientSounds.HANDLER_NAME)==null && active.get(null)==null,"Shutdown removes only owned handler and active session");
            check(channel.pipeline().get("packet_handler")!=null,"Original packet handler survives shutdown");
            check(session.placements.isEmpty() && session.deferred.isEmpty() && session.ledger.pending.isEmpty(),"Shutdown leaves no queued game objects");
        } finally { AdninClientSounds.shutdown(); finish(channel); }
    }
    private static void leaseCleanup() throws Exception {
        EmbeddedChannel channel=new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        channel.pipeline().replace(channel.pipeline().first(),"packet_handler",new ChannelInboundHandlerAdapter());
        FakePackets packets=new FakePackets();
        AdninClientSounds.Session session=new AdninClientSounds.Session(null,null,new Object(),scheduledChannel(channel),packets);
        active().set(null,session);
        try {
            session.setModes(true,true);
            long now=System.nanoTime()/1000000L;
            session.requestInstall(now); channel.runPendingTasks();
            check(session.installed && session.leaseCheck!=null,"Installed handler schedules a bounded inactivity check");
            AdninClientSounds.Pending pending=session.ledger.begin(10,20,-3,1,now);
            session.ledger.finish(pending.id,plan(1,false,false,true,1,"dig.stone"),now,10000);
            session.heartbeat=now-AdninClientSounds.LEASE_MS-1;
            FakeSound duplicate=sound("dig.stone",10.5,21.5,-2.5);
            check(channel.writeInbound(duplicate) && channel.readInbound()==duplicate,"Expired client lease immediately stops suppressing matching sound");
            check(packets.received==0,"Expired lease cannot keep the anticheat packet timestamp alive");
            FakePlacement outgoing=new FakePlacement(10,20,-3,1);
            check(channel.writeOutbound(outgoing) && channel.readOutbound()==outgoing && session.placements.isEmpty(),"Expired lease forwards outgoing packets without queuing work");
            session.checkLease(); channel.runPendingTasks();
            check(session.stopped && !session.sounds && !session.observe && active().get(null)==null,"Watchdog disables both functions and retires stale session");
            check(channel.pipeline().get(AdninClientSounds.HANDLER_NAME)==null && session.leaseCheck==null,"Watchdog removes owned handler and cancels its timer");
            check(session.ledger.recent.isEmpty(),"Watchdog cannot leave stale sound suppression behind");
            AdninClientSounds.Session replacement=new AdninClientSounds.Session(null,null,new Object(),scheduledChannel(channel),new FakePackets());
            active().set(null,replacement); replacement.setModes(true,false);
            replacement.requestInstall(System.nanoTime()/1000000L); channel.runPendingTasks();
            check(replacement.installed && channel.pipeline().get(AdninClientSounds.HANDLER_NAME)==replacement.handler,"Fresh client ticks can install a replacement after lease expiry");
        } finally { AdninClientSounds.shutdown(); finish(channel); }
    }
    private static void queueOverflowPipeline() throws Exception {
        EmbeddedChannel channel=new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        channel.pipeline().replace(channel.pipeline().first(),"packet_handler",new ChannelInboundHandlerAdapter());
        AdninClientSounds.Session session=new AdninClientSounds.Session(null,null,new Object(),scheduledChannel(channel),new FakePackets());
        active().set(null,session);
        try {
            session.setModes(true,false);
            session.requestInstall(System.nanoTime()/1000000L); channel.runPendingTasks();
            boolean forwarded=true;
            for(int i=0;i<AdninClientSounds.MAX_PENDING+1;i++) {
                FakePlacement placement=new FakePlacement(10,20,-3,1);
                forwarded &= channel.writeOutbound(placement) && channel.readOutbound()==placement;
            }
            check(forwarded && session.placements.size()==AdninClientSounds.MAX_PENDING,
                "Placement queue overflow still forwards every outgoing packet unchanged");
            check(session.ledger.pending.size()==AdninClientSounds.MAX_PENDING,"Placement overflow cannot grow prediction metadata");
            boolean held=true;
            for(int i=0;i<AdninClientSounds.MAX_DEFERRED;i++) held &= !channel.writeInbound(sound("dig.stone",10.5,21.5,-2.5));
            check(held && session.deferred.size()==AdninClientSounds.MAX_DEFERRED,"Potential server sounds fill only the bounded deferred queue");
            FakeSound overflow=sound("dig.stone",10.5,21.5,-2.5);
            check(channel.writeInbound(overflow) && channel.readInbound()==overflow,
                "Deferred queue overflow immediately forwards the original server sound");
            session.setModes(false,false); channel.runPendingTasks();
            int released=0; while(channel.readInbound()!=null) released++;
            check(released==AdninClientSounds.MAX_DEFERRED && session.deferred.isEmpty() && session.placements.isEmpty(),
                "Disable releases the entire full deferred queue and clears placement work");
        } finally { AdninClientSounds.shutdown(); finish(channel); }
    }
    private static void finish(EmbeddedChannel channel) {
        channel.finish();
        for(Object value; (value=channel.readInbound())!=null;) ReferenceCountUtil.release(value);
        for(Object value; (value=channel.readOutbound())!=null;) ReferenceCountUtil.release(value);
    }

    /** Actual registered Netty 4.0.23 channels; never bind or connect a socket. */
    private static void registeredLifecycle(boolean pendingOnly) throws Exception {
        check(Channel.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("/netty-all-4.0.23.Final.jar"),
            "Lifecycle fixtures use the actual installed Netty 4.0.23 JAR");
        final LocalEventLoopGroup group=new LocalEventLoopGroup(1,new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread=new Thread(task,"sounds-lifecycle-fixture"); thread.setDaemon(true); return thread;
            }
        });
        List<LocalChannel> channels=new ArrayList<LocalChannel>();
        try {
            final LocalChannel channel=registered(group,channels,true);
            final FakePackets packets=new FakePackets();
            final AdninClientSounds.Session session=session(channel,packets);
            int queued=blockedLoop(channel.eventLoop(),new Runnable() {
                @Override public void run() {
                    long start=System.nanoTime()/1000000L;
                    for(int i=0;i<3000;i++) { session.heartbeat=System.nanoTime()/1000000L; session.requestInstall(start+1001L*i); }
                }
            });
            check(queued==1,"Three thousand retries queue exactly one install on a blocked registered EventLoop, actual="+queued);
            if(pendingOnly) { System.out.println("AdninClientSounds pending regression passed"); return; }
            check(session.installed && !installPending(session),"Completed install clears its pending slot");
            check(channel.pipeline().get(AdninClientSounds.HANDLER_NAME)==session.handler,"Blocked installation keeps the exact owned handler");
            session.setModes(false,true);
            final FakeSound observed=sound("dig.stone",0,0,0);
            final AtomicReference<Object> delivered=new AtomicReference<Object>();
            await(channel.eventLoop().submit(new Runnable() {
                @Override public void run() {
                    channel.pipeline().addLast("owned_collector",new ChannelInboundHandlerAdapter() {
                        @Override public void channelRead(ChannelHandlerContext context,Object value) { delivered.set(value); }
                    });
                    channel.pipeline().fireChannelRead(observed);
                }
            }),"Observe a real registered-pipeline packet");
            check(delivered.get()==observed && packets.received==1,"Observation forwards a real pipeline input exactly once");
            queued=blockedLoop(channel.eventLoop(),new Runnable() {
                @Override public void run() { for(int i=0;i<3000;i++) { AdninClientSounds.shutdown(); session.stop(); } }
            });
            check(queued==1,"Repeated shutdown and stop queue one removal while the loop is blocked");
            check(!session.installed && session.leaseCheck==null && active().get(null)==null,"Removal clears installed state and watchdog");
            check(channel.pipeline().get(AdninClientSounds.HANDLER_NAME)==null && channel.pipeline().get("packet_handler")!=null,
                "Idempotent stop removes only its own handler");

            final AdninClientSounds.Session stale=session(channel,new FakePackets());
            final AdninClientSounds.Session latest=new AdninClientSounds.Session(null,null,new Object(),channel,new FakePackets());
            queued=blockedLoop(channel.eventLoop(),new Runnable() {
                @Override public void run() {
                    stale.requestInstall(System.nanoTime()/1000000L);
                    AdninClientSounds.shutdown();
                    try { active().set(null,latest); } catch(Exception error) { throw new RuntimeException(error); }
                    latest.setModes(false,true);
                    latest.requestInstall(System.nanoTime()/1000000L);
                    stale.stop();
                }
            });
            check(queued<=3,"World replacement has one old install, one cleanup and one current install");
            check(stale.stopped && !stale.installed && !installPending(stale),"Shutdown retires a still-pending installation");
            check(latest.installed && channel.pipeline().get(AdninClientSounds.HANDLER_NAME)==latest.handler,
                "Queued old-world cleanup cannot remove the new world's handler");
            AdninClientSounds.shutdown(); drain(channel.eventLoop());

            final LocalChannel missing=registered(group,channels,false);
            AdninClientSounds.Session noAnchor=session(missing,new FakePackets());
            noAnchor.requestInstall(0); drain(missing.eventLoop());
            check(!noAnchor.installed && !installPending(noAnchor),"Missing anchor returns its pending slot");
            await(missing.eventLoop().submit(new Runnable() {
                @Override public void run() { missing.pipeline().addLast("packet_handler",new ChannelInboundHandlerAdapter()); }
            }),"Add a later packet anchor");
            noAnchor.requestInstall(1001); drain(missing.eventLoop());
            check(noAnchor.installed && !installPending(noAnchor),"A later valid anchor can retry installation");
            AdninClientSounds.shutdown(); drain(missing.eventLoop());

            final ChannelHandler foreign=new ChannelInboundHandlerAdapter();
            await(channel.eventLoop().submit(new Runnable() {
                @Override public void run() { channel.pipeline().addBefore("packet_handler",AdninClientSounds.HANDLER_NAME,foreign); }
            }),"Place a foreign same-name handler");
            AdninClientSounds.Session conflict=session(channel,new FakePackets());
            conflict.requestInstall(0); drain(channel.eventLoop());
            check(!conflict.installed && !installPending(conflict),"Foreign-handler conflict clears the pending slot");
            AdninClientSounds.shutdown(); drain(channel.eventLoop());
            check(channel.pipeline().get(AdninClientSounds.HANDLER_NAME)==foreign,"Shutdown preserves a foreign same-name handler");

            LocalChannel faults=registered(group,channels,true);
            Faults controls=new Faults();
            AdninClientSounds.Session faultSession=session(faultChannel(faults,controls),new FakePackets());
            controls.rejectExecute.set(1);
            faultSession.requestInstall(0);
            check(!installPending(faultSession) && !faultSession.installed,"Rejected execute releases the pending slot synchronously");
            controls.failExecute.set(1);
            boolean failed=false;
            try { faultSession.requestInstall(1001); } catch(IllegalStateException expected) { failed=true; }
            check(failed && !installPending(faultSession),"Unexpected submission failure also releases the pending slot");
            controls.failPipeline.set(1);
            faultSession.requestInstall(2002); drain(faults.eventLoop());
            check(!faultSession.installed && !installPending(faultSession),"An asynchronous pipeline failure releases the pending slot");
            faultSession.requestInstall(3003); drain(faults.eventLoop());
            check(faultSession.installed && !installPending(faultSession),"Installation retries after a handled asynchronous failure");
            AdninClientSounds.shutdown(); drain(faults.eventLoop());

            controls.rejectSchedule.set(1);
            AdninClientSounds.Session rejectedLease=session(faultChannel(faults,controls),new FakePackets());
            rejectedLease.requestInstall(0); drain(faults.eventLoop());
            check(rejectedLease.stopped && !rejectedLease.installed && !installPending(rejectedLease),
                "Rejected lease scheduling safely retires the newly installed observer");
            check(active().get(null)==null && faults.pipeline().get(AdninClientSounds.HANDLER_NAME)==null,
                "Rejected watchdog leaves no active session or stale handler");

            controls.failSchedule.set(1);
            AdninClientSounds.Session failedLease=session(faultChannel(faults,controls),new FakePackets());
            failedLease.requestInstall(0); drain(faults.eventLoop());
            check(failedLease.stopped && !failedLease.installed && !installPending(failedLease),
                "Unexpected watchdog failure also retires the observer without leaking a pending slot");
            check(failedLease.leaseCheck==null && active().get(null)==null && faults.pipeline().get(AdninClientSounds.HANDLER_NAME)==null,
                "Unexpected watchdog failure cannot leave an immortal observer");

            final AdninClientSounds.Session stoppedPending=session(faults,new FakePackets());
            queued=blockedLoop(faults.eventLoop(),new Runnable() {
                @Override public void run() {
                    stoppedPending.requestInstall(0);
                    AdninClientSounds.shutdown();
                    for(int i=0;i<3000;i++) stoppedPending.requestInstall(1001L*i);
                }
            });
            check(queued<=2,"Shutdown of pending work queues at most install plus cleanup");
            check(stoppedPending.stopped && !stoppedPending.installed && !installPending(stoppedPending)
                && faults.pipeline().get(AdninClientSounds.HANDLER_NAME)==null,"Stopped pending observer never installs late");
        } finally {
            AdninClientSounds.shutdown();
            for(LocalChannel channel:channels) channel.close().await(5000L);
            group.shutdownGracefully(0,1,TimeUnit.SECONDS).await(5000L);
        }
    }

    private static LocalChannel registered(LocalEventLoopGroup group,List<LocalChannel> channels,boolean anchor) throws Exception {
        LocalChannel channel=new LocalChannel(); channels.add(channel);
        if(anchor) channel.pipeline().addLast("packet_handler",new ChannelInboundHandlerAdapter());
        await(group.register(channel),"Register an owned, unconnected LocalChannel");
        check(channel.isRegistered() && channel.isOpen() && !channel.isActive(),"Fixture never binds or connects");
        return channel;
    }
    private static AdninClientSounds.Session session(Channel channel,FakePackets packets) throws Exception {
        AdninClientSounds.Session session=new AdninClientSounds.Session(null,null,new Object(),channel,packets);
        active().set(null,session); session.setModes(false,true); return session;
    }
    private static boolean installPending(AdninClientSounds.Session session) throws Exception {
        Field field=AdninClientSounds.Session.class.getDeclaredField("installPending"); field.setAccessible(true);
        synchronized(session) { return field.getBoolean(session); }
    }
    private static int blockedLoop(final EventLoop loop,final Runnable operation) throws Exception {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        final AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
        loop.execute(new Runnable() {
            @Override public void run() {
                entered.countDown();
                try { if(!release.await(5000L,TimeUnit.MILLISECONDS)) throw new AssertionError("Blocked fixture timed out"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); failure.set(error); }
            }
        });
        check(entered.await(5000L,TimeUnit.MILLISECONDS),"Registered EventLoop reaches the controlled block");
        Thread caller=new Thread(new Runnable() {
            @Override public void run() {
                try { operation.run(); } catch(Throwable error) { failure.set(error); }
                finally { finished.countDown(); }
            }
        },"sounds-client-fixture");
        caller.setDaemon(true);
        int pending; boolean returned;
        try {
            caller.start(); returned=finished.await(1500L,TimeUnit.MILLISECONDS);
            pending=((SingleThreadEventExecutor)loop).pendingTasks();
        } finally { release.countDown(); }
        check(returned,"Install/shutdown returns without waiting for the blocked EventLoop");
        check(finished.await(5000L,TimeUnit.MILLISECONDS) && failure.get()==null,"Client fixture finishes without failure: "+failure.get());
        drain(loop); return pending;
    }
    private static void drain(EventLoop loop) throws Exception {
        for(int i=0;i<3;i++) await(loop.submit(new Runnable() { @Override public void run() { } }),"Drain lifecycle work");
    }
    private static void await(Future<?> future,String why) throws Exception {
        check(future.await(5000L) && future.isSuccess(),why+": "+future.cause());
    }

    private static final class Faults {
        final AtomicInteger rejectExecute=new AtomicInteger(),failExecute=new AtomicInteger(),failPipeline=new AtomicInteger(),rejectSchedule=new AtomicInteger(),failSchedule=new AtomicInteger();
    }
    private static boolean consume(AtomicInteger value) { return value.getAndSet(0)!=0; }
    private static Channel faultChannel(final LocalChannel channel,final Faults faults) {
        final EventLoop loop=(EventLoop)Proxy.newProxyInstance(AdninClientSoundsTest.class.getClassLoader(),new Class<?>[]{EventLoop.class},new InvocationHandler() {
            @Override public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
                if(method.getName().equals("execute")) {
                    if(consume(faults.rejectExecute)) throw new RejectedExecutionException("Owned execute rejection");
                    if(consume(faults.failExecute)) throw new IllegalStateException("Owned execute failure");
                }
                if(method.getName().equals("schedule")) {
                    if(consume(faults.rejectSchedule)) throw new RejectedExecutionException("Owned schedule rejection");
                    if(consume(faults.failSchedule)) throw new IllegalStateException("Owned schedule failure");
                }
                try { return method.invoke(channel.eventLoop(),args); }
                catch(InvocationTargetException failure) { throw failure.getCause(); }
            }
        });
        return (Channel)Proxy.newProxyInstance(AdninClientSoundsTest.class.getClassLoader(),new Class<?>[]{Channel.class},new InvocationHandler() {
            @Override public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
                if(method.getName().equals("eventLoop")) return loop;
                if(method.getName().equals("pipeline") && consume(faults.failPipeline)) throw new IllegalStateException("Owned pipeline failure");
                try { return method.invoke(channel,args); }
                catch(InvocationTargetException failure) { throw failure.getCause(); }
            }
        });
    }

    private static void channelReuse() throws Exception {
        EmbeddedChannel first=new EmbeddedChannel(new ChannelInboundHandlerAdapter()),
            replacement=new EmbeddedChannel(new ChannelInboundHandlerAdapter()),other=new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        Object world=new Object();
        DiscoveryManager manager=new DiscoveryManager(first),changedManager=new DiscoveryManager(other);
        Method method=AdninClientSounds.class.getDeclaredMethod("channelFor",NetworkManager.class,Object.class); method.setAccessible(true);
        AdninClientSounds.Session session=new AdninClientSounds.Session(null,manager,world,first,new FakePackets());
        active().set(null,session);
        try {
            // If reflection ran again it would find replacement. A stable live
            // Session must keep its known channel until a lifecycle boundary.
            manager.discovered=replacement;
            for(int i=0;i<1000;i++) check(method.invoke(null,manager,world)==first,"Stable manager/world reuse their open Channel");
            check(method.invoke(null,changedManager,world)==other,"A changed manager rediscovers its private Channel field");
            check(method.invoke(null,manager,new Object())==replacement,"A changed world rediscovers the manager's Channel");
            first.close();
            check(method.invoke(null,manager,world)==replacement,"A closed cached channel triggers rediscovery");
            AdninClientSounds.shutdown(); first.runPendingTasks();
            check(method.invoke(null,manager,world)==replacement,"A retired session cannot reuse the old channel");
            manager.discovered=null;
            check(method.invoke(null,manager,world)==null,"Disconnected manager reports no channel");
            manager.discovered=other;
            check(method.invoke(null,manager,world)==other,"Reconnected manager can discover a later channel");
        } finally { AdninClientSounds.shutdown(); finish(first); finish(replacement); finish(other); }
    }
    private static final class DiscoveryManager extends NetworkManager {
        private Channel discovered;
        DiscoveryManager(Channel channel) { super(EnumPacketDirection.CLIENTBOUND); discovered=channel; }
    }
    // Netty 4.0.23's EmbeddedEventLoop intentionally has no scheduler. Keep its
    // real packet pipeline and task queue, using Netty's shared executor solely
    // for the cancellable watchdog timer. No channel is bound or connected.
    private static Channel scheduledChannel(final EmbeddedChannel channel) {
        final EventLoop loop=(EventLoop)Proxy.newProxyInstance(AdninClientSoundsTest.class.getClassLoader(),
            new Class<?>[]{EventLoop.class},new InvocationHandler() {
                @Override public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
                    try {
                        return method.invoke(method.getName().equals("schedule") ? GlobalEventExecutor.INSTANCE : channel.eventLoop(),args);
                    } catch(InvocationTargetException failure) { throw failure.getCause(); }
                }
            });
        return (Channel)Proxy.newProxyInstance(AdninClientSoundsTest.class.getClassLoader(),
            new Class<?>[]{Channel.class},new InvocationHandler() {
                @Override public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
                    if(method.getName().equals("eventLoop")) return loop;
                    try { return method.invoke(channel,args); }
                    catch(InvocationTargetException failure) { throw failure.getCause(); }
                }
            });
    }
    private static Field active() throws Exception { Field field=AdninClientSounds.class.getDeclaredField("active"); field.setAccessible(true); return field; }
    private interface FixturePacket { }
    private static final class FakePlacement implements FixturePacket {
        final int x,y,z,face;
        FakePlacement(int x,int y,int z,int face) { this.x=x; this.y=y; this.z=z; this.face=face; }
    }
    private static final class FakeSound implements FixturePacket {
        final String name;
        final double x,y,z;
        FakeSound(String name,double x,double y,double z) { this.name=name; this.x=x; this.y=y; this.z=z; }
    }
    private static final class FakePackets implements AdninClientSounds.PacketAccess {
        int received;
        @Override public boolean isPacket(Object value) { return value instanceof FixturePacket; }
        @Override public AdninClientSounds.PlacementData placement(Object value) {
            if (!(value instanceof FakePlacement)) return null;
            FakePlacement packet=(FakePlacement)value;
            return new AdninClientSounds.PlacementData(packet,packet.x,packet.y,packet.z,packet.face);
        }
        @Override public AdninClientSounds.SoundData sound(Object value) {
            if (!(value instanceof FakeSound)) return null;
            FakeSound packet=(FakeSound)value;
            return new AdninClientSounds.SoundData(packet.name,packet.x,packet.y,packet.z);
        }
        @Override public void received() { received++; }
    }
    private static FakeSound sound(String name,double x,double y,double z) { return new FakeSound(name,x,y,z); }
    private static AdninClientSounds.SoundPlan plan(int id,boolean sneak,boolean creative,boolean held,int face,String sound) {
        return AdninClientSounds.predict(id,sneak,creative,held,face,10,20,-3,sound);
    }
    private static void xyz(double x,double y,double z,double[] expected,String why) { check(x==expected[0] && y==expected[1] && z==expected[2],why); }
    private static void check(boolean condition,String why) { checks++; if(!condition) throw new AssertionError(why); }
}
