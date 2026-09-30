/*
 * Decompiled with CFR 0.152.
 */
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.UUID;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

public class AdninPacketLog {
    public static volatile int seenSpawnPlayerCount = 0;
    public static volatile String installState = "idle";
    public static volatile String lastSpawnLine = "";
    private static final String HANDLER_KEY = "adnin_packet_log";
    private static final String CL_SPAWN_OBF = "fp";
    private static final String CL_SPAWN_MCP = "net.minecraft.network.play.server.S0CPacketSpawnPlayer";
    // Initialized before install's monitor release/EventLoop submission publishes
    // the handler; retain the recovered field modifiers for the native ABI audit.
    private static Class<?> s_spawnPlayerClass = null;
    private static final Object INSTALL_LOCK = new Object();
    private static volatile boolean stopped;
    private static int activeObservations;
    private static volatile InstallRequest current;
    // At most one queued/running EventLoop action and one installed observer.
    // A rapid connection change replaces only current, never grows a task queue.
    private static InstallRequest pending, installed;
    private static final Object ACCESSOR_LOCK = new Object();
    private static final Object[] NO_ARGUMENTS = new Object[0];
    private static volatile SpawnAccessors spawnAccessors;
    // Counts class-resolution work only, never packet traffic or player data.
    private static long accessorResolutionCount;

    private static void ensureSpawnPlayerClass(Object object) {
        if (s_spawnPlayerClass != null) {
            return;
        }
        try {
            s_spawnPlayerClass = Class.forName(CL_SPAWN_OBF);
            installState = "reflect:spawn_obf";
            return;
        }
        catch (Exception exception) {
            try {
                s_spawnPlayerClass = Class.forName(CL_SPAWN_MCP);
                installState = "reflect:spawn_mcp";
                return;
            }
            catch (Exception exception2) {
                try {
                    for (Method method : object.getClass().getDeclaredMethods()) {
                        String string;
                        Class<?>[] classArray = method.getParameterTypes();
                        if (classArray.length != 1 || !(string = classArray[0].getName()).contains("SpawnPlayer") && !CL_SPAWN_OBF.equals(string)) continue;
                        s_spawnPlayerClass = classArray[0];
                        installState = "reflect:spawn_nethandler:" + string;
                        return;
                    }
                }
                catch (Exception exception3) {
                    installState = "reflect:spawn_failed:" + exception3.getClass().getSimpleName();
                }
                return;
            }
        }
    }

    private static Object readFieldByNames(Object object, String ... stringArray) throws Exception {
        for (String string : stringArray) {
            try {
                Field field = object.getClass().getDeclaredField(string);
                field.setAccessible(true);
                return field.get(object);
            }
            catch (NoSuchFieldException noSuchFieldException) {
            }
        }
        throw new NoSuchFieldException(Arrays.toString(stringArray));
    }

    private static int readIntNoArg(Object object, String ... stringArray) {
        for (String string : stringArray) {
            try {
                Method method = object.getClass().getMethod(string, new Class[0]);
                method.setAccessible(true);
                return ((Number)method.invoke(object, new Object[0])).intValue();
            }
            catch (Exception exception) {
                try {
                    Field field = object.getClass().getDeclaredField(string);
                    field.setAccessible(true);
                    return field.getInt(object);
                }
                catch (Exception exception2) {
                }
            }
        }
        return 0;
    }

    private static int readByteAsInt(Object object, String ... stringArray) {
        for (String string : stringArray) {
            AccessibleObject accessibleObject;
            try {
                accessibleObject = object.getClass().getMethod(string, new Class[0]);
                ((Method)accessibleObject).setAccessible(true);
                Object object2 = ((Method)accessibleObject).invoke(object, new Object[0]);
                if (object2 instanceof Number) {
                    return ((Number)object2).intValue();
                }
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                accessibleObject = object.getClass().getDeclaredField(string);
                ((Field)accessibleObject).setAccessible(true);
                return ((Field)accessibleObject).getByte(object) & 0xFF;
            }
            catch (Exception exception) {
            }
        }
        return 0;
    }

    private static UUID readUuidNoArg(Object object, String ... stringArray) {
        for (String string : stringArray) {
            Object object2;
            AccessibleObject accessibleObject;
            try {
                accessibleObject = object.getClass().getMethod(string, new Class[0]);
                ((Method)accessibleObject).setAccessible(true);
                object2 = ((Method)accessibleObject).invoke(object, new Object[0]);
                if (object2 instanceof UUID) {
                    return (UUID)object2;
                }
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                accessibleObject = object.getClass().getDeclaredField(string);
                ((Field)accessibleObject).setAccessible(true);
                object2 = ((Field)accessibleObject).get(object);
                if (!(object2 instanceof UUID)) continue;
                return (UUID)object2;
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        return null;
    }

    /** One immutable table; replacing the packet class cannot grow a class map. */
    private static SpawnAccessors accessorsFor(Class<?> packetClass) {
        SpawnAccessors found = spawnAccessors;
        if (found != null && found.packetClass == packetClass) return found;
        synchronized (ACCESSOR_LOCK) {
            found = spawnAccessors;
            if (found != null && found.packetClass == packetClass) return found;
            found = new SpawnAccessors(packetClass);
            accessorResolutionCount++;
            // Reflection resolution and invocation never hold INSTALL_LOCK.
            // Publish briefly under it so shutdown cannot be followed by a late
            // retained-class publication from an already admitted observation.
            synchronized (INSTALL_LOCK) {
                if (!stopped) spawnAccessors = found;
            }
            return found;
        }
    }

    private static final class SpawnAccessors {
        final Class<?> packetClass;
        final AccessorChain entity, uuid, x, y, z, yaw, pitch, item;
        SpawnAccessors(Class<?> packetClass) {
            this.packetClass = packetClass;
            entity = new AccessorChain(packetClass, "b", "getEntityID", "func_148943_d");
            // Add compatible names after the recovered candidates so an object
            // exposing more than one alias keeps the original precedence.
            uuid = new AccessorChain(packetClass, "c", "getPlayerUUID", "func_179819_c", "getPlayer");
            x = new AccessorChain(packetClass, "d", "getX", "func_148942_f");
            y = new AccessorChain(packetClass, "e", "getY", "func_148949_g");
            z = new AccessorChain(packetClass, "f", "getZ", "func_148946_h");
            yaw = new AccessorChain(packetClass, "g", "getYaw", "func_148941_i");
            pitch = new AccessorChain(packetClass, "h", "getPitch", "func_148938_j", "func_148945_j");
            item = new AccessorChain(packetClass, "i", "getCurrentItemID", "func_149009_m", "func_148947_k");
        }
    }

    /** Cache available and absent aliases, retaining invocation-time fallbacks. */
    private static final class AccessorChain {
        final AccessibleObject[] candidates;
        AccessorChain(Class<?> packetClass, String... aliases) {
            AccessibleObject[] resolved = new AccessibleObject[aliases.length * 2];
            int size = 0;
            for (String alias : aliases) {
                try {
                    Method method = packetClass.getMethod(alias, new Class[0]);
                    method.setAccessible(true);
                    resolved[size++] = method;
                } catch (Exception unavailable) { }
                try {
                    Field field = packetClass.getDeclaredField(alias);
                    field.setAccessible(true);
                    resolved[size++] = field;
                } catch (Exception unavailable) { }
            }
            candidates = Arrays.copyOf(resolved, size);
        }

        int integer(Object packet) {
            for (AccessibleObject candidate : candidates) {
                try {
                    if (candidate instanceof Method) {
                        return ((Number) ((Method) candidate).invoke(packet, NO_ARGUMENTS)).intValue();
                    }
                    return ((Field) candidate).getInt(packet);
                } catch (Exception unavailable) { }
            }
            return 0;
        }

        int byteInteger(Object packet) {
            for (AccessibleObject candidate : candidates) {
                try {
                    if (candidate instanceof Method) {
                        Object value = ((Method) candidate).invoke(packet, NO_ARGUMENTS);
                        if (value instanceof Number) return ((Number) value).intValue();
                    } else {
                        return ((Field) candidate).getByte(packet) & 0xFF;
                    }
                } catch (Exception unavailable) { }
            }
            return 0;
        }

        UUID uuid(Object packet) {
            for (AccessibleObject candidate : candidates) {
                try {
                    Object value = candidate instanceof Method
                        ? ((Method) candidate).invoke(packet, NO_ARGUMENTS) : ((Field) candidate).get(packet);
                    if (value instanceof UUID) return (UUID) value;
                } catch (Exception unavailable) { }
            }
            return null;
        }
    }

    private static void logSpawnPlayerIfNeeded(Object object) {
        if (stopped || object == null || s_spawnPlayerClass == null) {
            return;
        }
        if (!s_spawnPlayerClass.isInstance(object)) {
            return;
        }
        synchronized (INSTALL_LOCK) {
            if (stopped) return;
            activeObservations++;
        }
        try {
            String string;
            ++seenSpawnPlayerCount;
            SpawnAccessors accessors = accessorsFor(object.getClass());
            int n = accessors.entity.integer(object);
            UUID uUID = accessors.uuid.uuid(object);
            int n2 = accessors.x.integer(object);
            int n3 = accessors.y.integer(object);
            int n4 = accessors.z.integer(object);
            int n5 = accessors.yaw.byteInteger(object);
            int n6 = accessors.pitch.byteInteger(object);
            int n7 = accessors.item.integer(object);
            lastSpawnLine = string = "S0CPacketSpawnPlayer entityId=" + n + " uuid=" + (uUID != null ? uUID.toString() : "") + " x=" + n2 + " y=" + n3 + " z=" + n4 + " yaw=" + n5 + " pitch=" + n6 + " item=" + n7;
            if (!stopped) AdninPacketLog.nativeOnSpawnPlayerEntity(n);
        }
        catch (Exception exception) {
            // empty catch block
        }
        catch (LinkageError unavailable) {
            // A retired observer must never interrupt the game's packet flow.
        }
        finally {
            synchronized (INSTALL_LOCK) { activeObservations--; }
        }
    }

    public static void install(Object netHandler) {
        if (stopped || netHandler == null) return;
        try {
            AdninPacketLog.ensureSpawnPlayerClass(netHandler);
            if (s_spawnPlayerClass == null) {
                installState = "install:no_spawn_class";
                return;
            }
            Object manager = AdninPacketLog.readFieldByNames(netHandler, "c", "netManager", "networkManager");
            if (manager == null) {
                installState = "install:no_network_manager";
                return;
            }
            Object value = AdninPacketLog.readFieldByNames(manager, "k", "channel");
            if (!(value instanceof Channel)) {
                installState = "install:no_channel";
                return;
            }
            Channel channel = (Channel) value;
            synchronized (INSTALL_LOCK) {
                if (stopped) return;
                if (!channel.isOpen()) {
                    if (current != null && current.channel == channel) {
                        current.closed = true; current = null; dispatchLocked();
                    }
                    installState = "install:closed";
                    return;
                }
                if (current != null && current.channel == channel && !current.closed) return;
                current = installed != null && installed.channel == channel && !installed.closed
                    ? installed : new InstallRequest(channel);
                installState = "install:queued";
                dispatchLocked();
            }
        } catch (Exception failure) {
            installState = "install:exception:" + failure.getClass().getSimpleName();
        } catch (LinkageError failure) {
            installState = "install:unavailable";
        }
    }

    /** Disable new native observations immediately; never wait for an EventLoop. */
    public static void shutdown() {
        synchronized (INSTALL_LOCK) {
            stopped = true; current = null;
            spawnAccessors = null;
            installState = "install:stopped";
            dispatchLocked();
        }
    }

    /** Stable unload acknowledgement: stopped forbids new observations. */
    public static boolean isQuiescent() {
        synchronized (INSTALL_LOCK) { return stopped && activeObservations == 0; }
    }

    /** Called only with INSTALL_LOCK. execute() queues work, never awaits a Future. */
    private static void dispatchLocked() {
        if (pending != null) return;
        // A rejected removal can be followed by one latest installation. There
        // are only two live slots, so even rejection cannot create a retry loop.
        for (int attempt = 0; attempt < 2; attempt++) {
            InstallRequest next;
            int action;
            if (installed != null && installed != current) { next = installed; action = 2; }
            else if (!stopped && current != null && installed != current) { next = current; action = 1; }
            else return;
            pending = next; next.action = action;
            try {
                next.channel.eventLoop().execute(next);
                return;
            } catch (RuntimeException unavailable) {
                pending = null; next.closed = true;
                if (current == next) current = null;
                if (installed == next) installed = null;
                next.handler = null;
                if (!stopped) installState = "install:executor_unavailable";
            }
        }
    }

    private static void closed(InstallRequest owner) {
        synchronized (INSTALL_LOCK) {
            owner.closed = true;
            if (current == owner) current = null;
            dispatchLocked();
        }
    }

    private static void removed(InstallRequest owner) {
        synchronized (INSTALL_LOCK) {
            if (installed == owner) installed = null;
            if (current == owner) current = null;
            owner.handler = null;
            if (!stopped) installState = "install:removed";
            dispatchLocked();
        }
    }

    private static final class InstallRequest implements Runnable {
        final Channel channel;
        volatile boolean closed;
        ChannelHandler handler;
        int action;
        InstallRequest(Channel channel) { this.channel = channel; }
        boolean observes() { return !stopped && !closed && current == this; }

        @Override public void run() {
            synchronized (INSTALL_LOCK) {
                if (pending != this) return;
            }
            try {
                // Netty 4.0 remove() submits a PromiseTask and waits when used
                // off this executor. Every pipeline read/write stays here.
                synchronized (INSTALL_LOCK) {
                    if (action == 2) {
                        if (installed != this || current == this && !stopped && !closed) return;
                        ChannelPipeline pipeline = channel.pipeline();
                        if (pipeline.get(HANDLER_KEY) == handler && handler != null) pipeline.remove(HANDLER_KEY);
                        if (installed == this) installed = null;
                        handler = null;
                    } else {
                        if (!observes() || !channel.isOpen()) {
                            if (current == this) current = null;
                            return;
                        }
                        ChannelPipeline pipeline = channel.pipeline();
                        ChannelHandler existing = pipeline.get(HANDLER_KEY);
                        if (existing != null) {
                            if (existing == handler) { installed = this; installState = "install:ok:existing"; }
                            else { current = null; installState = "install:handler_conflict"; }
                            return;
                        }
                        if (handler == null) handler = createHandler(this);
                        for (String anchor : new String[]{"packet_handler", "decoder", "inbound_handler", "splitter", "encoder"}) {
                            if (pipeline.get(anchor) == null) continue;
                            pipeline.addBefore(anchor, HANDLER_KEY, handler);
                            installed = this; installState = "install:ok:" + anchor;
                            return;
                        }
                        pipeline.addLast(HANDLER_KEY, handler);
                        installed = this; installState = "install:ok:addLast";
                    }
                }
            } catch (Exception failure) {
                failed("install:exception:" + failure.getClass().getSimpleName());
            } catch (LinkageError failure) {
                failed("install:unavailable");
            } finally {
                synchronized (INSTALL_LOCK) {
                    if (pending == this) pending = null;
                    dispatchLocked();
                }
            }
        }

        private void failed(String state) {
            synchronized (INSTALL_LOCK) {
                closed = true;
                if (current == this) current = null;
                if (action == 2) {
                    // An unavailable executor/pipeline must not resubmit a
                    // failing removal forever. The disabled proxy only forwards.
                    if (installed == this) installed = null;
                    handler = null;
                    if (!stopped) installState = state;
                    return;
                }
                // A partially added handler must remain owned until its
                // scheduled removal; it never observes packets once closed.
                try {
                    if (handler != null && channel.pipeline().get(HANDLER_KEY) == handler) installed = this;
                    else { if (installed == this) installed = null; handler = null; }
                } catch (RuntimeException unavailable) {
                    if (installed == this) installed = null;
                    handler = null;
                }
                if (!stopped) installState = state;
            }
        }
    }

    private static ChannelHandler createHandler(final InstallRequest owner) throws Exception {
            Class<?> inbound = Class.forName("io.netty.channel.ChannelInboundHandler");
            ClassLoader classLoader = inbound.getClassLoader();
            Method[] methodArray = new Method[]{null};
            Method[] methodArray2 = new Method[]{null};
            Method[] methodArray3 = new Method[8];
            Object object7 = Proxy.newProxyInstance(classLoader, new Class[]{inbound}, (object, method, objectArray) -> {
                String string = method.getName();
                Object channelContext = objectArray != null && objectArray.length > 0 ? objectArray[0] : null;
                switch (string) {
                    case "channelRead": {
                        if (objectArray != null && objectArray.length >= 2) {
                            if (owner.observes()) AdninPacketLog.logSpawnPlayerIfNeeded(objectArray[1]);
                            if (channelContext != null) {
                                if (methodArray[0] == null) {
                                    methodArray[0] = AdninPacketLog.findMethodByName(channelContext.getClass(), "fireChannelRead", Object.class);
                                }
                                if (methodArray[0] != null) {
                                    methodArray[0].invoke(channelContext, objectArray[1]);
                                }
                            }
                        }
                        return null;
                    }
                    case "exceptionCaught": {
                        if (channelContext != null && objectArray != null && objectArray.length >= 2) {
                            if (methodArray2[0] == null) {
                                methodArray2[0] = AdninPacketLog.findMethodByName(channelContext.getClass(), "fireExceptionCaught", Throwable.class);
                            }
                            if (methodArray2[0] != null) {
                                methodArray2[0].invoke(channelContext, objectArray[1]);
                            }
                        }
                        return null;
                    }
                    case "channelRegistered": {
                        if (channelContext != null) {
                            AdninPacketLog.invokeFireNoArg(channelContext, "fireChannelRegistered", methodArray3, 0);
                        }
                        return null;
                    }
                    case "channelUnregistered": {
                        if (channelContext != null) {
                            AdninPacketLog.invokeFireNoArg(channelContext, "fireChannelUnregistered", methodArray3, 1);
                        }
                        return null;
                    }
                    case "channelActive": {
                        if (channelContext != null) {
                            AdninPacketLog.invokeFireNoArg(channelContext, "fireChannelActive", methodArray3, 2);
                        }
                        return null;
                    }
                    case "channelInactive": {
                        AdninPacketLog.closed(owner);
                        if (channelContext != null) {
                            AdninPacketLog.invokeFireNoArg(channelContext, "fireChannelInactive", methodArray3, 3);
                        }
                        return null;
                    }
                    case "channelReadComplete": {
                        if (channelContext != null) {
                            AdninPacketLog.invokeFireNoArg(channelContext, "fireChannelReadComplete", methodArray3, 4);
                        }
                        return null;
                    }
                    case "userEventTriggered": {
                        if (channelContext != null && objectArray != null && objectArray.length >= 2) {
                            if (methodArray3[5] == null) {
                                methodArray3[5] = AdninPacketLog.findMethodByName(channelContext.getClass(), "fireUserEventTriggered", Object.class);
                            }
                            if (methodArray3[5] != null) {
                                methodArray3[5].invoke(channelContext, objectArray[1]);
                            }
                        }
                        return null;
                    }
                    case "channelWritabilityChanged": {
                        if (channelContext != null) {
                            AdninPacketLog.invokeFireNoArg(channelContext, "fireChannelWritabilityChanged", methodArray3, 6);
                        }
                        return null;
                    }
                    case "handlerAdded": {
                        return null;
                    }
                    case "handlerRemoved": {
                        AdninPacketLog.removed(owner);
                        return null;
                    }
                    case "equals": {
                        return object == objectArray[0];
                    }
                    case "hashCode": {
                        return System.identityHashCode(object);
                    }
                    case "toString": {
                        return "AdninPacketLog$Handler";
                    }
                }
                if (method.getReturnType() == Boolean.TYPE) {
                    return false;
                }
                if (method.getReturnType() == Integer.TYPE) {
                    return 0;
                }
                return null;
            });
            return (ChannelHandler) object7;
    }

    private static Method findMethodByName(Class<?> clazz, String string, Class<?> ... classArray) {
        try {
            Method method = clazz.getMethod(string, classArray);
            method.setAccessible(true);
            return method;
        }
        catch (NoSuchMethodException noSuchMethodException) {
            for (Class<?> clazz2 = clazz; clazz2 != null && clazz2 != Object.class; clazz2 = clazz2.getSuperclass()) {
                for (Method method : clazz2.getDeclaredMethods()) {
                    if (!method.getName().equals(string) || !Arrays.equals(method.getParameterTypes(), classArray)) continue;
                    method.setAccessible(true);
                    return method;
                }
            }
            return null;
        }
    }

    private static Method findAddBefore(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            Class<?>[] classArray;
            if (!"addBefore".equals(method.getName()) || (classArray = method.getParameterTypes()).length != 3 || classArray[0] != String.class || classArray[1] != String.class) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private static void invokeFireNoArg(Object object, String string, Method[] methodArray, int n) {
        try {
            if (methodArray[n] == null) {
                methodArray[n] = AdninPacketLog.findMethodByName(object.getClass(), string, new Class[0]);
            }
            if (methodArray[n] != null) {
                methodArray[n].invoke(object, new Object[0]);
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private static native void nativeOnSpawnPlayerEntity(int var0);
}
