/*
 * Decompiled with CFR 0.152.
 */
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.UUID;

public class AdninPacketLog {
    public static volatile int seenSpawnPlayerCount = 0;
    public static volatile String installState = "idle";
    public static volatile String lastSpawnLine = "";
    private static final String HANDLER_KEY = "adnin_packet_log";
    private static final String CL_SPAWN_OBF = "fp";
    private static final String CL_SPAWN_MCP = "net.minecraft.network.play.server.S0CPacketSpawnPlayer";
    private static Class<?> s_spawnPlayerClass = null;

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

    private static void logSpawnPlayerIfNeeded(Object object) {
        if (object == null || s_spawnPlayerClass == null) {
            return;
        }
        if (!s_spawnPlayerClass.isInstance(object)) {
            return;
        }
        try {
            String string;
            ++seenSpawnPlayerCount;
            int n = AdninPacketLog.readIntNoArg(object, "b", "getEntityID", "func_148943_d");
            UUID uUID = AdninPacketLog.readUuidNoArg(object, "c", "getPlayerUUID", "func_179819_c");
            int n2 = AdninPacketLog.readIntNoArg(object, "d", "getX", "func_148942_f");
            int n3 = AdninPacketLog.readIntNoArg(object, "e", "getY", "func_148949_g");
            int n4 = AdninPacketLog.readIntNoArg(object, "f", "getZ", "func_148946_h");
            int n5 = AdninPacketLog.readByteAsInt(object, "g", "getYaw", "func_148941_i");
            int n6 = AdninPacketLog.readByteAsInt(object, "h", "getPitch", "func_148938_j");
            int n7 = AdninPacketLog.readIntNoArg(object, "i", "getCurrentItemID", "func_149009_m");
            lastSpawnLine = string = "S0CPacketSpawnPlayer entityId=" + n + " uuid=" + (uUID != null ? uUID.toString() : "") + " x=" + n2 + " y=" + n3 + " z=" + n4 + " yaw=" + n5 + " pitch=" + n6 + " item=" + n7;
            AdninPacketLog.nativeOnSpawnPlayerEntity(n);
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    public static void install(Object object2) {
        try {
            Object object3;
            GenericDeclaration genericDeclaration;
            installState = "install:start";
            AdninPacketLog.ensureSpawnPlayerClass(object2);
            if (s_spawnPlayerClass == null) {
                installState = "install:no_spawn_class";
                return;
            }
            Object object4 = AdninPacketLog.readFieldByNames(object2, "c", "netManager", "networkManager");
            if (object4 == null) {
                installState = "install:no_network_manager";
                return;
            }
            Object object5 = AdninPacketLog.readFieldByNames(object4, "k", "channel");
            if (object5 == null) {
                installState = "install:no_channel";
                return;
            }
            Method method2 = object5.getClass().getMethod("pipeline", new Class[0]);
            method2.setAccessible(true);
            Object object6 = method2.invoke(object5, new Object[0]);
            if (object6 == null) {
                installState = "install:no_pipeline";
                return;
            }
            try {
                genericDeclaration = AdninPacketLog.findMethodByName(object6.getClass(), "remove", String.class);
                if (genericDeclaration != null) {
                    ((Method)genericDeclaration).setAccessible(true);
                    ((Method)genericDeclaration).invoke(object6, HANDLER_KEY);
                }
            }
            catch (Exception exception) {
                // empty catch block
            }
            genericDeclaration = Class.forName("io.netty.channel.ChannelInboundHandler");
            ClassLoader classLoader = ((Class)genericDeclaration).getClassLoader();
            Method[] methodArray = new Method[]{null};
            Method[] methodArray2 = new Method[]{null};
            Method[] methodArray3 = new Method[8];
            Object object7 = Proxy.newProxyInstance(classLoader, new Class[]{(Class<?>)genericDeclaration}, (object, method, objectArray) -> {
                String string = method.getName();
                Object channelContext = objectArray != null && objectArray.length > 0 ? objectArray[0] : null;
                switch (string) {
                    case "channelRead": {
                        if (objectArray != null && objectArray.length >= 2) {
                            AdninPacketLog.logSpawnPlayerIfNeeded(objectArray[1]);
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
                    case "handlerAdded": 
                    case "handlerRemoved": {
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
            Method method3 = AdninPacketLog.findAddBefore(object6.getClass());
            if (method3 != null) {
                method3.setAccessible(true);
                object3 = new String[]{"packet_handler", "decoder", "inbound_handler", "splitter", "encoder"};
                for (Object object8 : (String[])object3) {
                    try {
                        method3.invoke(object6, object8, HANDLER_KEY, object7);
                        installState = "install:ok:" + (String)object8;
                        return;
                    }
                    catch (Exception exception) {
                    }
                }
            }
            if ((object3 = AdninPacketLog.findMethodByName(object6.getClass(), "addLast", String.class, Class.forName("io.netty.channel.ChannelHandler"))) == null) {
                for (Method method4 : object6.getClass().getMethods()) {
                    Class<?>[] classArray;
                    if (!"addLast".equals(method4.getName()) || (classArray = method4.getParameterTypes()).length != 2 || classArray[0] != String.class) continue;
                    object3 = method4;
                    ((Method)object3).setAccessible(true);
                    break;
                }
            }
            if (object3 != null) {
                ((Method)object3).setAccessible(true);
                ((Method)object3).invoke(object6, HANDLER_KEY, object7);
                installState = "install:ok:addLast";
                return;
            }
            installState = "install:no_addBefore";
        }
        catch (Exception exception) {
            installState = "install:exception:" + exception.getClass().getSimpleName();
        }
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
