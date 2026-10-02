package adnin.forge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class RuntimeMappings {
    private static final ClassLoader GAME;
    private static final Object REMAPPER;
    private static final Method MAP, UNMAP, DESC, METHOD, FIELD;
    private static final Map<Class<?>,Map<String,Object>> MEMBERS=new HashMap<Class<?>,Map<String,Object>>();
    static {
        try {
            GAME = (ClassLoader)Class.forName("net.minecraft.launchwrapper.Launch")
                    .getField("classLoader").get(null);
            Class<?> type = Class.forName("net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper", false, GAME);
            REMAPPER = type.getField("INSTANCE").get(null);
            MAP = type.getMethod("map", String.class);
            UNMAP = type.getMethod("unmap", String.class);
            DESC = type.getMethod("mapDesc", String.class);
            METHOD = type.getMethod("mapMethodName", String.class, String.class, String.class);
            FIELD = type.getMethod("mapFieldName", String.class, String.class, String.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String transform(Method method, String value) throws Exception {
        return (String)method.invoke(REMAPPER, value);
    }

    public static Class<?> findClass(String raw) throws Exception {
        String mapped = raw.charAt(0) == '[' ? transform(DESC, raw) : transform(MAP, raw);
        return Class.forName(mapped.replace('/', '.'), true, GAME);
    }

    private static Class<?> findType(String raw) throws Exception {
        String mapped = raw.charAt(0) == '[' ? transform(DESC, raw) : transform(MAP, raw);
        return Class.forName(mapped.replace('/', '.'), false, GAME);
    }

    private static Class<?> descriptorType(String text) throws Exception {
        switch (text.charAt(0)) {
            case 'V': return void.class;
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'S': return short.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case 'L': return findType(text.substring(1, text.length()-1));
            case '[': return findType(text);
            default: throw new IllegalArgumentException("Invalid JNI descriptor");
        }
    }

    private static Class<?>[] parameters(String signature) throws Exception {
        if (signature.charAt(0) != '(') throw new IllegalArgumentException("Invalid JNI method descriptor");
        List<Class<?>> result = new ArrayList<Class<?>>();
        for (int index=1; signature.charAt(index) != ')';) {
            int end = index;
            while (signature.charAt(end) == '[') end++;
            if (signature.charAt(end) == 'L') end = signature.indexOf(';', end);
            if (end < index) throw new IllegalArgumentException("Invalid JNI method descriptor");
            end++;
            result.add(descriptorType(signature.substring(index, end)));
            index = end;
        }
        return result.toArray(new Class<?>[result.size()]);
    }

    public static Object findMember(Class<?> owner, String name, String signature, int kind) throws Exception {
        String key=kind+":"+name+signature;
        synchronized (MEMBERS) {
            Map<String,Object> known=MEMBERS.get(owner);
            if (known != null && known.containsKey(key)) return known.get(key);
        }
        // Cached IDs already have initialized owners. Descriptor types stay lazy.
        Class.forName(owner.getName(), true, owner.getClassLoader());
        Object result=resolveMember(owner,name,signature,kind);
        synchronized (MEMBERS) {
            Map<String,Object> known=MEMBERS.get(owner);
            if (known == null && MEMBERS.size()<256) {
                known=new HashMap<String,Object>();MEMBERS.put(owner,known);
            }
            if (known != null && known.size()<128) known.put(key,result);
        }
        return result;
    }

    private static Object resolveMember(Class<?> owner, String name, String signature, int kind) throws Exception {
        boolean isField = kind >= 2, isStatic = (kind & 1) != 0;
        Class<?> expected = isField ? descriptorType(signature)
                : descriptorType(signature.substring(signature.indexOf(')')+1));
        Class<?>[] arguments = isField ? null : parameters(signature);
        if ("<init>".equals(name)) {
            if (isField || isStatic) throw new NoSuchMethodException(name);
            Constructor<?> constructor = owner.getDeclaredConstructor(arguments);
            constructor.setAccessible(true);
            return constructor;
        }
        List<Class<?>> pending = new ArrayList<Class<?>>();
        pending.add(owner);
        for (int index=0; index<pending.size(); index++) {
            Class<?> type = pending.get(index);
            String original = transform(UNMAP, type.getName().replace('.', '/'));
            String mapped = (String)(isField ? FIELD : METHOD).invoke(REMAPPER, original, name, signature);
            try {
                if (isField) {
                    Field field = type.getDeclaredField(mapped);
                    if (field.getType() == expected && Modifier.isStatic(field.getModifiers()) == isStatic) {
                        field.setAccessible(true);
                        return field;
                    }
                } else {
                    Method method = type.getDeclaredMethod(mapped, arguments);
                    if (method.getReturnType() == expected && Modifier.isStatic(method.getModifiers()) == isStatic) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            } catch (NoSuchFieldException | NoSuchMethodException missing) { }
            Class<?> parent = type.getSuperclass();
            if (parent != null && !pending.contains(parent)) pending.add(parent);
            for (Class<?> contract : type.getInterfaces()) if (!pending.contains(contract)) pending.add(contract);
        }
        if (isField) throw new NoSuchFieldException(name);
        throw new NoSuchMethodException(name + signature);
    }
}
