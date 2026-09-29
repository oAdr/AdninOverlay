import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/** Resolve helper's Minecraft/Adnin references through the JVM, without initialization. */
public final class AdninFeatureLinkageTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected AdninFeatures.class path");
        InputStream file = Files.newInputStream(Paths.get(args[0]));
        DataInputStream in = new DataInputStream(file);
        try {
            if (in.readInt() != 0xcafebabe) throw new AssertionError("Invalid class file");
            in.readUnsignedShort();
            if (in.readUnsignedShort() != 52) throw new AssertionError("Java 8 class required");
            int count = in.readUnsignedShort();
            int[] tags = new int[count], first = new int[count], second = new int[count];
            String[] utf = new String[count];
            for (int i = 1; i < count; i++) {
                tags[i] = in.readUnsignedByte();
                switch (tags[i]) {
                    case 1: utf[i] = in.readUTF(); break;
                    case 3: case 4: in.readInt(); break;
                    case 5: case 6: in.readLong(); i++; break;
                    case 7: case 8: case 16: case 19: case 20: first[i] = in.readUnsignedShort(); break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        first[i] = in.readUnsignedShort(); second[i] = in.readUnsignedShort(); break;
                    case 15: first[i] = in.readUnsignedByte(); second[i] = in.readUnsignedShort(); break;
                    default: throw new AssertionError("Unexpected constant pool tag " + tags[i]);
                }
            }
            ClassLoader loader = AdninFeatureLinkageTest.class.getClassLoader();
            int checked = 0, minecraft = 0;
            Set<String> unique = new HashSet<String>();
            for (int i = 1; i < count; i++) {
                if (tags[i] != 9 && tags[i] != 10 && tags[i] != 11) continue;
                String ownerName = utf[first[first[i]]];
                if (!(ownerName.startsWith("net/minecraft/") || ownerName.startsWith("com/mojang/") || ownerName.startsWith("Adnin"))) continue;
                int pair = second[i];
                String name = utf[first[pair]], descriptor = utf[second[pair]];
                String reference = ownerName + "." + name + descriptor;
                if (!unique.add(reference)) continue;
                Class<?> owner = Class.forName(ownerName.replace('/', '.'), false, loader);
                Member member;
                if (tags[i] == 9) {
                    Field field = findField(owner, name);
                    if (field == null) throw new AssertionError("Field not found: " + reference);
                    Class<?> expected = MethodType.fromMethodDescriptorString("()" + descriptor, loader).returnType();
                    if (field.getType() != expected) throw new AssertionError("Field descriptor differs: " + reference);
                    member = field;
                } else {
                    MethodType type = MethodType.fromMethodDescriptorString(descriptor, loader);
                    if ("<init>".equals(name)) {
                        Constructor<?> constructor = owner.getDeclaredConstructor(type.parameterArray());
                        member = constructor;
                    } else {
                        Method method = findMethod(owner, name, type.parameterArray(), type.returnType());
                        if (method == null) throw new AssertionError("Method not found: " + reference);
                        member = method;
                    }
                }
                if (!ownerName.startsWith("Adnin") && !Modifier.isPublic(member.getModifiers())) {
                    throw new AssertionError("External member is not public: " + reference);
                }
                checked++;
                if (!ownerName.startsWith("Adnin")) minecraft++;
            }
            System.out.println("AdninFeatureLinkageTest: " + checked + " JVM member references resolved (" + minecraft + " Minecraft/Mojang), no class initialization or game actions");
        } finally { in.close(); }
    }

    private static Field findField(Class<?> type, String name) {
        if (type == null) return null;
        try { return type.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        for (Class<?> item : type.getInterfaces()) {
            Field field = findField(item, name);
            if (field != null) return field;
        }
        return findField(type.getSuperclass(), name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameters, Class<?> result) {
        if (type == null) return null;
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            if (method.getReturnType() == result) return method;
        } catch (NoSuchMethodException ignored) { }
        Method inherited = findMethod(type.getSuperclass(), name, parameters, result);
        if (inherited != null) return inherited;
        for (Class<?> item : type.getInterfaces()) {
            Method method = findMethod(item, name, parameters, result);
            if (method != null) return method;
        }
        return null;
    }
}
