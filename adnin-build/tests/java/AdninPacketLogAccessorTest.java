import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Owned packet fixtures only; no game, settings, native library or network. */
public final class AdninPacketLogAccessorTest {
    private static final UUID ID = UUID.fromString("12345678-1234-4000-8000-000000000071");
    private static final UUID OTHER_ID = UUID.fromString("12345678-1234-4000-8000-000000000072");
    private static final Method RESOLVE, OBSERVE;
    private static final Field CACHE, RESOLUTIONS;
    private static int checks;
    static {
        try {
            RESOLVE = AdninPacketLog.class.getDeclaredMethod("accessorsFor", Class.class);
            OBSERVE = AdninPacketLog.class.getDeclaredMethod("logSpawnPlayerIfNeeded", Object.class);
            CACHE = AdninPacketLog.class.getDeclaredField("spawnAccessors");
            RESOLUTIONS = AdninPacketLog.class.getDeclaredField("accessorResolutionCount");
            RESOLVE.setAccessible(true); OBSERVE.setAccessible(true); CACHE.setAccessible(true); RESOLUTIONS.setAccessible(true);
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    public static void main(String[] args) throws Exception {
        Field spawnClass = AdninPacketLog.class.getDeclaredField("s_spawnPlayerClass");
        spawnClass.setAccessible(true); spawnClass.set(null, Packet.class);
        namedAndMissingBursts(); aliasesAndFallbacks(); classChanges(); concurrentPublication(); concurrentClassChanges(); actualObservation(); grayObservation();
        int seen = AdninPacketLog.seenSpawnPlayerCount;
        long resolutions = count();
        AdninPacketLog.shutdown();
        OBSERVE.invoke(null, new NamedPacket());
        check(CACHE.get(null) == null, "Shutdown releases the retained accessor table and packet Class");
        check(AdninPacketLog.seenSpawnPlayerCount == seen && count() == resolutions,
            "Stopped observation neither parses accessors nor observes another packet");
        check(AdninPacketLog.isQuiescent(), "Accessor work leaves no outstanding observation after shutdown");
        System.out.println("AdninPacketLogAccessorTest: " + checks
            + " checks passed; bounded current-Class cache, cached misses, aliases, invocation fallback,"
            + " concurrent publication and shutdown; no game, settings, native library or network");
    }

    private static void namedAndMissingBursts() throws Exception {
        NamedPacket packet = new NamedPacket();
        long before = count();
        Object table = table(packet);
        check(count() == before + 1, "First packet Class resolves one complete accessor table");
        for (int i = 0; i < 10000; ++i) {
            if (table(packet) != table || integer(table, "entity", packet) != 71 || !ID.equals(uuid(table, packet))) {
                throw new AssertionError("Stable named packet cache failed at " + i);
            }
        }
        check(count() == before + 1, "10,000 named packets resolve once, not once per packet or missing alias");
        check(integer(table, "x", packet) == 32 && integer(table, "y", packet) == 2048
            && integer(table, "z", packet) == -64, "Named XYZ accessors retain their values");
        check(byteInteger(table, "yaw", packet) == -2 && byteInteger(table, "pitch", packet) == 5
            && integer(table, "item", packet) == 272, "Named rotations and item retain recovered accessor semantics");

        MissingPacket missing = new MissingPacket();
        before = count(); table = table(missing);
        for (int i = 0; i < 10000; ++i) {
            if (table(missing) != table || integer(table, "entity", missing) != 0
                    || byteInteger(table, "yaw", missing) != 0 || uuid(table, missing) != null) {
                throw new AssertionError("Cached missing accessor failed at " + i);
            }
        }
        check(count() == before + 1, "10,000 all-missing packets resolve absent aliases only once");
        for (String part : new String[]{"entity", "uuid", "x", "y", "z", "yaw", "pitch", "item"}) {
            Object chain = field(table, part);
            check(((Object[]) field(chain, "candidates")).length == 0, "A missing " + part + " chain is cached as empty");
        }
    }

    private static void aliasesAndFallbacks() throws Exception {
        Object[] packets = {new ObfuscatedPacket(), new SrgPacket(), new FieldPacket()};
        for (Object packet : packets) {
            Object table = table(packet);
            check(integer(table, "entity", packet) == 71 && ID.equals(uuid(table, packet)),
                "Identity aliases resolve for " + packet.getClass().getSimpleName());
            check(integer(table, "x", packet) == 32 && integer(table, "y", packet) == 2048
                && integer(table, "z", packet) == -64, "Coordinates resolve for " + packet.getClass().getSimpleName());
            int expectedYaw = packet instanceof FieldPacket ? 254 : -2;
            check(byteInteger(table, "yaw", packet) == expectedYaw && byteInteger(table, "pitch", packet) == 5,
                "Byte method/field conversion is preserved for " + packet.getClass().getSimpleName());
            check(integer(table, "item", packet) == 272, "Held item resolves for " + packet.getClass().getSimpleName());
        }
        FallbackPacket fallback = new FallbackPacket();
        Object table = table(fallback);
        check(integer(table, "entity", fallback) == 81,
            "Throwing first getter falls back to its same-name field before the next alias");
        check(ID.equals(uuid(table, fallback)), "Wrong-typed UUID getter falls back to its same-name field");
        check(integer(table, "x", fallback) == 42, "Throwing getter without a field reaches the next alias");
        check(integer(table, "y", fallback) == 7, "Wrong-typed integer getter falls back to widening numeric field access");
        check(integer(table, "z", fallback) == 123, "Object-returning getter is accepted when its actual value is Number");
        check(byteInteger(table, "yaw", fallback) == 253, "Wrong-typed byte getter falls back to same-name byte field");
        check(byteInteger(table, "pitch", fallback) == 9, "Throwing byte getter can use the following named alias");
        check(integer(table, "item", fallback) == 15, "Throwing item getter retains same-name field precedence");

        PriorityPacket priority = new PriorityPacket(); table = table(priority);
        check(integer(table, "entity", priority) == 91, "Original obfuscated method precedes named entity accessor");
        check(ID.equals(uuid(table, priority)), "Existing UUID alias precedes newly added named getPlayer");
        check(byteInteger(table, "pitch", priority) == 11 && integer(table, "item", priority) == 13,
            "Original SRG candidates retain precedence when old and corrected aliases coexist");

        DynamicPacket dynamic = new DynamicPacket(); table = table(dynamic);
        long before = count();
        check(integer(table, "entity", dynamic) == 101, "Healthy primary getter is preferred initially");
        dynamic.failed = true;
        check(integer(table, "entity", dynamic) == 102, "Invocation-time failure uses cached fallback without a new lookup");
        dynamic.failed = false;
        check(integer(table, "entity", dynamic) == 101, "A transiently failing getter can recover on later packets");
        check(count() == before, "Changing invocation outcomes never invalidates class-resolution cache");
    }

    private static void classChanges() throws Exception {
        NamedPacket parent = new NamedPacket();
        Object original = table(parent);
        long before = count();
        NamedSubclass child = new NamedSubclass();
        Object replacement = table(child);
        check(replacement != original && field(replacement, "packetClass") == NamedSubclass.class,
            "Different runtime Class receives a separate immutable accessor table");
        check(integer(replacement, "entity", child) == 171 && ID.equals(uuid(replacement, child)),
            "Subclass override and inherited public accessors both resolve correctly");
        check(count() == before + 1 && CACHE.get(null) == replacement,
            "A class transition performs one resolution and retains only the newest table");
        FieldSubclass fields = new FieldSubclass();
        Object inheritedFields = table(fields);
        check(integer(inheritedFields, "entity", fields) == 0 && uuid(inheritedFields, fields) == null,
            "Inherited fields stay excluded, matching the recovered getDeclaredField semantics");
        Object restored = table(parent);
        check(CACHE.get(null) == restored && restored != original && integer(restored, "entity", parent) == 71,
            "Returning to an evicted Class resolves afresh instead of retaining an unbounded class map");
    }

    private static void concurrentPublication() throws Exception {
        final ConcurrentPacket packet = new ConcurrentPacket();
        final CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(8);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicReference<Object> common = new AtomicReference<Object>();
        long before = count();
        for (int thread = 0; thread < 8; ++thread) {
            Thread worker = new Thread(new Runnable() {
                public void run() {
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Owned start latch timeout");
                        for (int i = 0; i < 1000; ++i) {
                            Object table = table(packet);
                            common.compareAndSet(null, table);
                            if (common.get() != table || integer(table, "entity", packet) != 71 || !ID.equals(uuid(table, packet))) {
                                throw new AssertionError("Partially published or duplicated accessor table");
                            }
                        }
                    } catch (Throwable problem) { failure.compareAndSet(null, problem); }
                    finally { done.countDown(); }
                }
            }, "packet-accessor-test-" + thread);
            worker.setDaemon(true); worker.start();
        }
        start.countDown();
        check(done.await(10, TimeUnit.SECONDS), "All concurrent same-Class readers complete within a bounded wait");
        check(failure.get() == null, "Immutable accessor publication is complete for all readers: " + failure.get());
        check(count() == before + 1, "Eight concurrent readers and 8,000 packets perform exactly one Class resolution");
    }

    private static void actualObservation() throws Exception {
        Object[] packets = {new NamedPacket(), new ObfuscatedPacket(), new SrgPacket()};
        for (Object packet : packets) {
            int before = AdninPacketLog.seenSpawnPlayerCount;
            OBSERVE.invoke(null, packet);
            check(AdninPacketLog.seenSpawnPlayerCount == before + 1,
                "Actual observation samples " + packet.getClass().getSimpleName() + " exactly once");
            check(("S0CPacketSpawnPlayer entityId=71 uuid=" + ID
                + " x=32 y=2048 z=-64 yaw=-2 pitch=5 item=272").equals(AdninPacketLog.lastSpawnLine),
                "Actual observation uses all cached aliases for " + packet.getClass().getSimpleName());
            check(((Number) field(AdninPacketLog.class, "activeObservations")).intValue() == 0,
                "Unbound JNI cleanup releases the active-observation guard");
        }
    }

    private static void concurrentClassChanges() throws Exception {
        final CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(2);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        for (final Object packet : new Object[]{new NamedPacket(), new ObfuscatedPacket()}) {
            Thread worker = new Thread(new Runnable() {
                public void run() {
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Owned start latch timeout");
                        for (int i = 0; i < 250; ++i) {
                            Object table = table(packet);
                            if (field(table, "packetClass") != packet.getClass()
                                    || integer(table, "entity", packet) != 71 || !ID.equals(uuid(table, packet))) {
                                throw new AssertionError("Another packet Class replaced a reader's immutable table");
                            }
                        }
                    } catch (Throwable problem) { failure.compareAndSet(null, problem); }
                    finally { done.countDown(); }
                }
            }, "packet-accessor-class-switch-" + packet.getClass().getSimpleName());
            worker.setDaemon(true); worker.start();
        }
        start.countDown();
        check(done.await(10, TimeUnit.SECONDS), "Concurrent runtime-Class changes complete within a bounded wait");
        check(failure.get() == null, "Local table remains valid when another thread replaces the single cache slot: " + failure.get());
        Object retained = field(CACHE.get(null), "packetClass");
        check(retained == NamedPacket.class || retained == ObfuscatedPacket.class,
            "Alternating Class readers leave exactly one current accessor table retained");
    }

    private static Object table(Object packet) throws Exception { return RESOLVE.invoke(null, packet.getClass()); }
    private static void grayObservation() throws Exception {
        Field snapshot = AdninFeatures.class.getDeclaredField("ignoredPlayers"); snapshot.setAccessible(true);
        Object original = snapshot.get(null);
        java.lang.reflect.Constructor<?> constructor = original.getClass().getDeclaredConstructor(java.util.Set.class, java.util.Set.class, java.util.Set.class);
        constructor.setAccessible(true);
        GrayProbePacket packet = new GrayProbePacket();
        try {
            snapshot.set(null, constructor.newInstance(java.util.Collections.emptySet(),java.util.Collections.singleton(ID),java.util.Collections.emptySet()));
            AdninPacketLog.lastSpawnLine = "owned-sentinel";
            OBSERVE.invoke(null,packet);
            check(packet.coordinateReads==0 && "owned-sentinel".equals(AdninPacketLog.lastSpawnLine),
                "Known gray UUID skips auxiliary decoding/native observation before coordinate access");
            snapshot.set(null, constructor.newInstance(java.util.Collections.emptySet(),java.util.Collections.emptySet(),java.util.Collections.singleton(71)));
            OBSERVE.invoke(null,packet);
            check(packet.coordinateReads==0 && "owned-sentinel".equals(AdninPacketLog.lastSpawnLine),
                "Known gray entity ID has the same immutable-reader short circuit");
            snapshot.set(null,original);OBSERVE.invoke(null,packet);
            check(packet.coordinateReads==1 && AdninPacketLog.lastSpawnLine.contains("entityId=71 "),
                "Restored or unknown UUID/entity observations continue through the original decoder");
        } finally { snapshot.set(null,original); }
    }
    private static long count() throws Exception { return RESOLUTIONS.getLong(null); }
    private static int integer(Object table, String part, Object packet) throws Exception {
        return ((Number) invoke(field(table, part), "integer", packet)).intValue();
    }
    private static int byteInteger(Object table, String part, Object packet) throws Exception {
        return ((Number) invoke(field(table, part), "byteInteger", packet)).intValue();
    }
    private static UUID uuid(Object table, Object packet) throws Exception { return (UUID) invoke(field(table, "uuid"), "uuid", packet); }
    private static Object invoke(Object target, String method, Object packet) throws Exception {
        Method reader = target.getClass().getDeclaredMethod(method, Object.class); reader.setAccessible(true); return reader.invoke(target, packet);
    }
    private static Object field(Object target, String name) throws Exception {
        Class<?> owner = target instanceof Class ? (Class<?>) target : target.getClass();
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target instanceof Class ? null : target);
    }
    private static void check(boolean condition, String why) { ++checks; if (!condition) throw new AssertionError(why); }

    private interface Packet { }
    public static class NamedPacket implements Packet {
        public int getEntityID() { return 71; }
        public UUID getPlayer() { return ID; }
        public int getX() { return 32; }
        public int getY() { return 2048; }
        public int getZ() { return -64; }
        public byte getYaw() { return -2; }
        public byte getPitch() { return 5; }
        public int getCurrentItemID() { return 272; }
    }
    public static final class GrayProbePacket extends NamedPacket {
        int coordinateReads;
        @Override public int getX() { coordinateReads++; return 32; }
    }
    public static final class ObfuscatedPacket implements Packet {
        public int b() { return 71; }
        public UUID c() { return ID; }
        public int d() { return 32; }
        public int e() { return 2048; }
        public int f() { return -64; }
        public byte g() { return -2; }
        public byte h() { return 5; }
        public int i() { return 272; }
    }
    public static final class SrgPacket implements Packet {
        public int func_148943_d() { return 71; }
        public UUID func_179819_c() { return ID; }
        public int func_148942_f() { return 32; }
        public int func_148949_g() { return 2048; }
        public int func_148946_h() { return -64; }
        public byte func_148941_i() { return -2; }
        public byte func_148945_j() { return 5; }
        public int func_148947_k() { return 272; }
    }
    public static class FieldPacket implements Packet {
        private int b = 71, d = 32, e = 2048, f = -64, i = 272;
        private UUID c = ID;
        private byte g = -2, h = 5;
    }
    public static final class FallbackPacket implements Packet {
        private int b = 81;
        private UUID c = ID;
        private byte e = 7, g = -3;
        private short i = 15;
        public int b() { throw new IllegalStateException("Owned getter fixture"); }
        public int getEntityID() { return 999; }
        public String c() { return "wrong type"; }
        public UUID getPlayerUUID() { return OTHER_ID; }
        public int d() { throw new IllegalStateException("Owned getter fixture"); }
        public int getX() { return 42; }
        public String e() { return "wrong type"; }
        public int getY() { return 999; }
        public Object f() { return Integer.valueOf(123); }
        public Object g() { return "wrong type"; }
        public byte getYaw() { return 99; }
        public byte h() { throw new IllegalStateException("Owned getter fixture"); }
        public byte getPitch() { return 9; }
        public int i() { throw new IllegalStateException("Owned getter fixture"); }
        public int getCurrentItemID() { return 999; }
    }
    public static final class PriorityPacket implements Packet {
        public int b() { return 91; }
        public int getEntityID() { return 999; }
        public UUID getPlayerUUID() { return ID; }
        public UUID getPlayer() { return OTHER_ID; }
        public byte func_148938_j() { return 11; }
        public byte func_148945_j() { return 99; }
        public int func_149009_m() { return 13; }
        public int func_148947_k() { return 999; }
    }
    public static final class DynamicPacket implements Packet {
        private int b = 102;
        boolean failed;
        public int b() { if (failed) throw new IllegalStateException("Owned transient fixture"); return 101; }
    }
    public static final class MissingPacket implements Packet { public int unrelated = 999; }
    public static final class NamedSubclass extends NamedPacket { @Override public int getEntityID() { return 171; } }
    public static final class FieldSubclass extends FieldPacket { }
    public static final class ConcurrentPacket extends NamedPacket { }
}
