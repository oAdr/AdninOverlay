import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;

/** Independent placement-sound implementation; no Forge or third-party mod bytecode.
 * Game state and audio are accessed only by the existing client-thread tick.
 * Netty forwards every outgoing packet unchanged and queues bounded observations.
 */
public final class AdninClientSounds {
    static final String HANDLER_NAME = "adnin_client_sounds";
    static final int MAX_PENDING = 128, MAX_DEFERRED = 256, MAX_RECENT = 256;
    static final long PENDING_MS = 2000L;
    static final long LEASE_MS = 2000L;
    static final float VOLUME = 1.0f, PITCH = 0.7936508f;
    private static final int[] FUNCTIONAL = {23,25,26,36,54,61,62,63,64,68,58,69,71,77,84,85,92,93,94,96,107,113,116,117,118,122,130,137,138,140,143,145,146,149,150,151,154,167,178,183,184,185,186,187,188,189,190,191,192,193,194,195,196};
    private static final int[] REPLACED = {6,8,9,10,11,31,32,78,106};
    private static volatile Session active;

    private AdninClientSounds() { }

    /** Call on the client thread, including when the menu is open or a world ends. */
    public static synchronized void tick(Minecraft mc, boolean soundsEnabled, boolean observePackets) {
        if ((!soundsEnabled && !observePackets) || mc == null || mc.theWorld == null || mc.thePlayer == null
                || mc.playerController == null || mc.getNetHandler() == null || mc.isSingleplayer()) {
            shutdown();
            return;
        }
        try {
            NetworkManager manager = mc.getNetHandler().getNetworkManager();
            Channel channel = channelFor(manager, mc.theWorld);
            if (channel == null || !channel.isOpen() || manager.isLocalChannel()) { shutdown(); return; }
            if (active == null || active.stopped || active.channel != channel || active.world != mc.theWorld) {
                shutdown();
                active = new Session(mc, manager, mc.theWorld, channel);
            }
            Session session = active;
            long now = now();
            session.heartbeat = now;
            session.setModes(soundsEnabled, observePackets);
            session.requestInstall(now);
            session.drainPlacements(now);
            session.drainDeferred(now);
            session.ledger.expire(now);
        } catch (RuntimeException ignored) { shutdown(); }
          catch (LinkageError ignored) { shutdown(); }
    }

    /** Also call before stopping the client pump; never waits for a Netty thread. */
    public static synchronized void shutdown() {
        Session previous = active;
        active = null;
        if (previous != null) previous.stop();
    }

    private static synchronized void retire(Session session) {
        if (active == session) active = null;
        session.stop();
    }

    private static synchronized boolean retireIfExpired(Session session) {
        if (!session.owned()) return true;
        // Recheck while holding the same lock as tick: a just-renewed lease
        // must not be retired by a watchdog decision made before that tick.
        if (now()-session.heartbeat <= LEASE_MS) return false;
        active=null;
        session.stop();
        return true;
    }

    private static long now() { return System.nanoTime() / 1000000L; }

    // A stable connection already supplied this field. Keep short sound-drain
    // ticks cheap, but rediscover it after a manager/world change or closure.
    private static Channel channelFor(NetworkManager manager, Object world) {
        if (manager == null) return null;
        Session session = active;
        if (session != null && !session.stopped && session.manager == manager
                && session.world == world && session.channel.isOpen()) return session.channel;
        return channelOf(manager);
    }

    // Vanilla's Channel field is private; Lunar widens it. Type-based discovery
    // uses the same field without depending on either runtime's obfuscated name.
    private static Channel channelOf(NetworkManager manager) {
        if (manager == null) return null;
        try {
            for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !Channel.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (value instanceof Channel) return (Channel) value;
                }
            }
        } catch (ReflectiveOperationException ignored) { }
          catch (SecurityException ignored) { }
        return null;
    }

    static boolean contains(int[] values, int value) {
        for (int candidate : values) if (candidate == value) return true;
        return false;
    }

    /** Pure rules recovered from the supplied mod's observable placement behavior. */
    static SoundPlan predict(int blockId, boolean sneaking, boolean creative, boolean itemBlock,
            int face, int x, int y, int z, String sound) {
        if (creative || !itemBlock || face < 0 || face > 5 || sound == null || sound.isEmpty()
                || contains(FUNCTIONAL, blockId) && !sneaking) return null;
        double px = x + 0.5, py = y + 0.5, pz = z + 0.5;
        if (!contains(REPLACED, blockId)) {
            if (face == 0) py--; else if (face == 1) py++;
            else if (face == 2) pz--; else if (face == 3) pz++;
            else if (face == 4) px--; else px++;
        }
        return new SoundPlan(sound, x + 0.5, y + 0.5, z + 0.5, px, py, pz);
    }

    static long retention(long ping) { return 500L + Math.max(0L, Math.min(59500L, ping)); }
    static String soundName(String name) {
        return name != null && name.startsWith("minecraft:") ? name.substring(10) : name;
    }

    static final class SoundPlan {
        final String sound;
        final double clickedX, clickedY, clickedZ, x, y, z;
        SoundPlan(String sound, double clickedX, double clickedY, double clickedZ, double x, double y, double z) {
            this.sound = sound; this.clickedX = clickedX; this.clickedY = clickedY; this.clickedZ = clickedZ;
            this.x = x; this.y = y; this.z = z;
        }
    }

    static final class Pending {
        final long id, expires;
        final int x, y, z, face;
        Pending(long id, int x, int y, int z, int face, long expires) {
            this.id = id; this.x = x; this.y = y; this.z = z; this.face = face; this.expires = expires;
        }
        boolean at(double sx, double sy, double sz) {
            if (sx == x + 0.5 && sy == y + 0.5 && sz == z + 0.5) return true;
            return sx == x + 0.5 + (face == 4 ? -1 : face == 5 ? 1 : 0)
                && sy == y + 0.5 + (face == 0 ? -1 : face == 1 ? 1 : 0)
                && sz == z + 0.5 + (face == 2 ? -1 : face == 3 ? 1 : 0);
        }
    }

    static final class SoundKey {
        final String name;
        final double x, y, z;
        SoundKey(String name, double x, double y, double z) { this.name = soundName(name); this.x=x; this.y=y; this.z=z; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof SoundKey)) return false;
            SoundKey key = (SoundKey) other;
            return name != null && name.equals(key.name) && x == key.x && y == key.y && z == key.z;
        }
        @Override public int hashCode() {
            long hash = Double.doubleToLongBits(x == 0 ? 0 : x);
            hash = hash * 31 + Double.doubleToLongBits(y == 0 ? 0 : y);
            hash = hash * 31 + Double.doubleToLongBits(z == 0 ? 0 : z);
            return (name == null ? 0 : name.hashCode()) * 31 + (int)(hash ^ (hash >>> 32));
        }
    }

    /** Packet decoding is separate from the queue so neither Netty nor tests need game state. */
    interface PacketAccess {
        boolean isPacket(Object value);
        PlacementData placement(Object value);
        SoundData sound(Object value);
        void received();
    }
    static final class PlacementData {
        final Object packet;
        final int x,y,z,face;
        PlacementData(Object packet,int x,int y,int z,int face) { this.packet=packet; this.x=x; this.y=y; this.z=z; this.face=face; }
    }
    static final class SoundData {
        final String name;
        final double x,y,z;
        SoundData(String name,double x,double y,double z) { this.name=name; this.x=x; this.y=y; this.z=z; }
    }
    static final class MinecraftPackets implements PacketAccess {
        @Override public boolean isPacket(Object value) { return value instanceof Packet; }
        @Override public PlacementData placement(Object value) {
            if (!(value instanceof C08PacketPlayerBlockPlacement)) return null;
            C08PacketPlayerBlockPlacement packet=(C08PacketPlayerBlockPlacement)value;
            BlockPos pos=packet.getPosition();
            return pos==null ? null : new PlacementData(packet,pos.getX(),pos.getY(),pos.getZ(),packet.getPlacedBlockDirection());
        }
        @Override public SoundData sound(Object value) {
            if (!(value instanceof S29PacketSoundEffect)) return null;
            S29PacketSoundEffect packet=(S29PacketSoundEffect)value;
            return new SoundData(packet.getSoundName(),packet.getX(),packet.getY(),packet.getZ());
        }
        @Override public void received() { AdninAnticheat.packetReceived(); }
    }

    /** Bounded synchronized metadata shared with Netty; contains no world objects. */
    static final class Ledger {
        final Map<Long, Pending> pending = new LinkedHashMap<Long, Pending>();
        final Map<SoundKey, Long> recent = new LinkedHashMap<SoundKey, Long>();
        private long serial;
        synchronized Pending begin(int x, int y, int z, int face, long now) {
            expire(now);
            if (face < 0 || face > 5 || pending.size() >= MAX_PENDING) return null;
            Pending event = new Pending(++serial, x, y, z, face, now + PENDING_MS);
            pending.put(event.id, event);
            return event;
        }
        synchronized Pending get(long id, long now) { expire(now); return pending.get(id); }
        synchronized boolean waitingAt(double x, double y, double z, long now) {
            expire(now);
            for (Pending event : pending.values()) if (event.at(x,y,z)) return true;
            return false;
        }
        synchronized void finish(long id, SoundPlan played, long now, long ping) {
            expire(now);
            if (pending.remove(id) == null || played == null) return;
            long expires = now + retention(ping);
            remember(new SoundKey(played.sound, played.clickedX, played.clickedY, played.clickedZ), expires);
            remember(new SoundKey(played.sound, played.x, played.y, played.z), expires);
        }
        private void remember(SoundKey key, long expires) {
            Long previous = recent.remove(key);
            recent.put(key, previous == null ? expires : Math.max(expires, previous));
            while (recent.size() > MAX_RECENT) recent.remove(recent.keySet().iterator().next());
        }
        synchronized boolean suppresses(String name, double x, double y, double z, long now) {
            expire(now);
            return recent.containsKey(new SoundKey(name,x,y,z));
        }
        synchronized void expire(long now) {
            for (Iterator<Pending> it = pending.values().iterator(); it.hasNext();) if (now > it.next().expires) it.remove();
            for (Iterator<Long> it = recent.values().iterator(); it.hasNext();) if (now > it.next()) it.remove();
        }
        synchronized void clear() { pending.clear(); recent.clear(); }
    }

    static final class Placement {
        final long id;
        final Object packet;
        Placement(long id, Object packet) { this.id=id; this.packet=packet; }
    }
    static final class Deferred {
        final ChannelHandlerContext context;
        final Object packet;
        final SoundData sound;
        final long expires;
        Deferred(ChannelHandlerContext context, Object packet, SoundData sound, long expires) {
            this.context=context; this.packet=packet; this.sound=sound; this.expires=expires;
        }
        void forward() {
            try {
                context.executor().execute(new Runnable() {
                    @Override public void run() { if (context.channel().isOpen()) context.fireChannelRead(packet); }
                });
            } catch (RejectedExecutionException ignored) { /* A closed connection has no recipient. */ }
        }
    }

    static final class Session {
        final Minecraft mc;
        final NetworkManager manager;
        final Object world;
        final Channel channel;
        final PacketAccess packets;
        final Ledger ledger = new Ledger();
        final ArrayBlockingQueue<Placement> placements = new ArrayBlockingQueue<Placement>(MAX_PENDING);
        final ArrayBlockingQueue<Deferred> deferred = new ArrayBlockingQueue<Deferred>(MAX_DEFERRED);
        final Bridge handler = new Bridge(this);
        volatile boolean sounds, observe, stopped, installed;
        volatile long heartbeat;
        volatile ScheduledFuture<?> leaseCheck;
        long nextInstall=Long.MIN_VALUE;
        private boolean installPending;
        Session(Minecraft mc, NetworkManager manager, Object world, Channel channel) {
            this(mc,manager,world,channel,new MinecraftPackets());
        }
        Session(Minecraft mc, NetworkManager manager, Object world, Channel channel, PacketAccess packets) {
            this.mc=mc; this.manager=manager; this.world=world; this.channel=channel; this.heartbeat=now();
            this.packets=packets;
        }
        boolean owned() { return !stopped && active == this; }
        boolean live() { return owned() && now()-heartbeat <= LEASE_MS; }
        void setModes(boolean soundsEnabled, boolean observePackets) {
            observe = observePackets;
            boolean previous = sounds;
            sounds = soundsEnabled;
            if (previous && !soundsEnabled) clearSounds();
        }
        void requestInstall(long now) {
            synchronized (this) {
                if (installed || installPending || now < nextInstall || !live()) return;
                nextInstall = now + 1000;
                installPending = true;
            }
            boolean submitted = false;
            try {
                channel.eventLoop().execute(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!live() || !channel.isOpen()) return;
                            ChannelPipeline pipeline = channel.pipeline();
                            ChannelHandler existing = pipeline.get(HANDLER_NAME);
                            if (existing != null && existing != handler) return;
                            if (existing == null) {
                                String anchor = null;
                                for (Map.Entry<String, ChannelHandler> entry : pipeline)
                                    if (entry.getValue() == manager) { anchor = entry.getKey(); break; }
                                if (anchor == null && pipeline.get("packet_handler") != null) anchor = "packet_handler";
                                if (anchor == null) return;
                                pipeline.addBefore(anchor,HANDLER_NAME,handler);
                            }
                            installed=true;
                            scheduleLeaseCheck();
                        } catch (RuntimeException unavailable) {
                            // A failed optional install may retry after the throttle.
                        } catch (LinkageError unavailable) {
                            // Mapping failure cannot break the channel's task queue.
                        } finally {
                            finishInstall();
                        }
                    }
                });
                submitted = true;
            } catch (RejectedExecutionException ignored) { }
              finally { if (!submitted) finishInstall(); }
        }
        private synchronized void finishInstall() { installPending = false; }
        void scheduleLeaseCheck() {
            boolean failed=false;
            synchronized (this) {
                if (!owned()) return;
                ScheduledFuture<?> previous=leaseCheck;
                if (previous!=null) previous.cancel(false);
                try {
                    leaseCheck=channel.eventLoop().schedule(new Runnable() {
                        @Override public void run() { checkLease(); }
                    },1000L,TimeUnit.MILLISECONDS);
                } catch (RuntimeException unavailable) { failed=true; }
                  catch (LinkageError unavailable) { failed=true; }
            }
            // Do not acquire the class lifecycle lock while retaining Session's
            // lock: client tick/stop use the opposite order.
            if (failed) retire(this);
        }
        void checkLease() {
            if (!retireIfExpired(this)) scheduleLeaseCheck();
        }
        void capture(PlacementData packet, long now) {
            if (!live() || !sounds || packet == null) return;
            Pending entry = ledger.begin(packet.x,packet.y,packet.z,packet.face,now);
            if (entry != null && !placements.offer(new Placement(entry.id,packet.packet))) ledger.finish(entry.id,null,now,0);
        }
        void drainPlacements(long now) {
            for (int i=0; i<64; i++) {
                Placement event = placements.poll();
                if (event == null) break;
                Pending entry = ledger.get(event.id,now);
                SoundPlan played = null;
                long ping = 0;
                if (entry != null && live() && sounds && mc != null && mc.theWorld == world && mc.thePlayer != null) {
                    try {
                        C08PacketPlayerBlockPlacement packet=(C08PacketPlayerBlockPlacement)event.packet;
                        ItemStack stack = packet.getStack();
                        if (stack != null && stack.getItem() instanceof ItemBlock) {
                            Block block = ((ItemBlock)stack.getItem()).getBlock();
                            int clicked = Block.getIdFromBlock(mc.theWorld.getBlockState(packet.getPosition()).getBlock());
                            SoundPlan plan = predict(clicked,mc.thePlayer.isSneaking(),mc.playerController.isInCreativeMode(),
                                true,entry.face,entry.x,entry.y,entry.z,block.stepSound.getPlaceSound());
                            if (plan != null) {
                                mc.getSoundHandler().playSound(new PositionedSoundRecord(new ResourceLocation(plan.sound),
                                    VOLUME,PITCH,(float)plan.x,(float)plan.y,(float)plan.z));
                                played = plan;
                                ServerData data = mc.getCurrentServerData();
                                if (data != null) ping = data.pingToServer;
                            }
                        }
                    } catch (RuntimeException ignored) { }
                      catch (LinkageError ignored) { }
                }
                ledger.finish(event.id,played,now,ping);
            }
        }
        void drainDeferred(long now) {
            int count = Math.min(128,deferred.size());
            for (int i=0; i<count; i++) {
                Deferred event = deferred.poll();
                if (event == null) break;
                SoundData packet = event.sound;
                if (live() && sounds && ledger.suppresses(packet.name,packet.x,packet.y,packet.z,now)) continue;
                if (live() && sounds && now <= event.expires && ledger.waitingAt(packet.x,packet.y,packet.z,now)
                        && deferred.offer(event)) continue;
                event.forward();
            }
        }
        void clearSounds() {
            placements.clear(); ledger.clear();
            for (Deferred event; (event=deferred.poll()) != null;) event.forward();
        }
        void stop() {
            ScheduledFuture<?> timer;
            synchronized (this) {
                if (stopped) return;
                stopped=true; sounds=false; observe=false;
                timer=leaseCheck;
                leaseCheck=null;
            }
            clearSounds();
            if (timer!=null) timer.cancel(false);
            try {
                channel.eventLoop().execute(new Runnable() {
                    @Override public void run() {
                        try {
                            if (channel.pipeline().get(HANDLER_NAME) == handler) channel.pipeline().remove(HANDLER_NAME);
                        } finally { installed=false; }
                    }
                });
            } catch (RejectedExecutionException ignored) { installed=false; }
        }
    }

    static final class Bridge extends ChannelDuplexHandler {
        final Session session;
        Bridge(Session session) { this.session=session; }
        @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
            super.write(context,packet,promise);
            if (session.live() && session.sounds) {
                try { session.capture(session.packets.placement(packet),now()); }
                catch (RuntimeException ignored) { }
                catch (LinkageError ignored) { }
            }
        }
        @Override public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
            if (session.live() && session.observe && session.packets.isPacket(packet)) session.packets.received();
            SoundData sound=session.live() && session.sounds ? session.packets.sound(packet) : null;
            if (sound != null) {
                long now=now();
                if (session.ledger.suppresses(sound.name,sound.x,sound.y,sound.z,now)) return;
                if (session.ledger.waitingAt(sound.x,sound.y,sound.z,now)
                        && session.deferred.offer(new Deferred(context,packet,sound,now+PENDING_MS))) return;
            }
            super.channelRead(context,packet);
        }
        @Override public void handlerRemoved(ChannelHandlerContext context) throws Exception {
            session.installed=false;
            retire(session);
            super.handlerRemoved(context);
        }
        @Override public void channelInactive(ChannelHandlerContext context) throws Exception {
            retire(session);
            super.channelInactive(context);
        }
    }
}
