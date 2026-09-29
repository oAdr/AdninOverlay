import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Executes only the rebuilt helper bootstrap with inert Minecraft fixtures. */
public final class AdninCompatBootstrapVerify {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Expected classes, fixtures and helper names");
        Path source = Paths.get(args[0]);
        Path fixtures = Paths.get(args[1]);
        Path isolated = Files.createTempDirectory("adnin-compat-bootstrap-");
        String[] copies = {"AdninIngameGui", "AdninGuiNewChat", "ave", "avo", "avt", "eu"};
        try {
            for (String name : copies) Files.copy((name.startsWith("Adnin") ? source : fixtures).resolve(name + ".class"), isolated.resolve(name + ".class"));
            List<URL> dependencies = new ArrayList<URL>();
            dependencies.add(isolated.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                Path jar = Paths.get(entry);
                String name = jar.getFileName().toString();
                if (name.startsWith("netty-") && name.endsWith(".jar")) dependencies.add(jar.toUri().toURL());
            }
            try (URLClassLoader loader = new URLClassLoader(dependencies.toArray(new URL[0]), null)) {
                for (int i = 2; i < args.length; i++) {
                    if (loader.getResource(args[i] + ".class") != null) throw new AssertionError("Helper unexpectedly available as file");
                    try { Class.forName(args[i], false, loader); throw new AssertionError("Helper loadable before bootstrap"); }
                    catch (ClassNotFoundException expected) { }
                }
                Class<?> owner = Class.forName("AdninGuiNewChat", true, loader);
                Class<?> chat = owner;
                boolean legacyVoid = false, vanillaInt = false;
                for (Method method : chat.getDeclaredMethods()) {
                    if (method.getName().equals("i") && method.getParameterTypes().length == 0) {
                        if (method.getReturnType() == void.class) legacyVoid = true;
                        if (method.getReturnType() == int.class) vanillaInt = true;
                    }
                }
                if (!legacyVoid || !vanillaInt) throw new AssertionError("Missing retained chat alias or vanilla override");
                for (int i = 2; i < args.length; i++) {
                    Class<?> helper = Class.forName(args[i], false, loader);
                    if (helper.getClassLoader() != loader) throw new AssertionError("Wrong helper loader");
                    if (loader.getResource(args[i] + ".class") != null) throw new AssertionError("Helper file appeared");
                }
                Method bootstrap = owner.getDeclaredMethod("adninDefineHelpers");
                bootstrap.setAccessible(true); bootstrap.invoke(null); bootstrap.invoke(null);
                Class<?> api = Class.forName("AdninApi", false, loader);
                Object result = api.getMethod("buildBotUrl", String.class, String.class).invoke(null,"http://example.test/api/users?q=<>&page=0","abc");
                if (!"http://example.test/api/users?q=abc&page=0".equals(result)) throw new AssertionError("Remapped API helper not callable");
                System.out.println("Compatibility bootstrap verified: " + (args.length - 2) + " remapped helpers, isolated owner loader, duplicate-safe, inert game fixtures");
            }
        } finally {
            for (String name : copies) Files.deleteIfExists(isolated.resolve(name + ".class"));
            Files.deleteIfExists(isolated);
        }
    }
}
