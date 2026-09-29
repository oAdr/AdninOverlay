import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Offline rules and an in-memory Netty pipeline; never starts Minecraft or audio. */
public final class AdninClientSoundsTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        placementRules(); suppressionRules(); queueBounds(); pipelineBehavior(); queueOverflowPipeline(); leaseCleanup();
        AdninClientSounds.tick(null,true,true);
        AdninClientSounds.shutdown();
        check(active().get(null) == null,"Null world and shutdown leave no session");
        System.out.println("AdninClientSoundsTest: " + checks
            + " checks passed; placement rules, duplicate suppression and in-memory packet pass-through; no game, audio, settings or network");
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
