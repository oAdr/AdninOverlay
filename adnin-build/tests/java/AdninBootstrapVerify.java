import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Verifies embedded helpers with no helper files on an isolated class path. */
public final class AdninBootstrapVerify {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Expected classes directory and helper names");
        Path source = Paths.get(args[0]).toAbsolutePath().normalize();
        Path isolated = Files.createTempDirectory("adnin-bootstrap-test-");
        Path ownerFile = isolated.resolve("AdninClientPump.class");
        try {
            Files.copy(source.resolve("AdninClientPump.class"), ownerFile);
            List<URL> dependencies = new ArrayList<URL>();
            dependencies.add(isolated.toUri().toURL());
            // Only Netty jars are exposed: the new duplex helper has a Netty
            // superclass. No production helper directory or game is exposed.
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                Path jar = Paths.get(entry);
                String name = jar.getFileName().toString();
                if (name.startsWith("netty-") && name.endsWith(".jar")) dependencies.add(jar.toUri().toURL());
            }
            URLClassLoader loader = new URLClassLoader(dependencies.toArray(new URL[0]), null);
            try {
                for (int i = 1; i < args.length; i++) {
                    if (loader.getResource(args[i] + ".class") != null) throw new AssertionError("Helper unexpectedly available as a file");
                    try {
                        Class.forName(args[i], false, loader);
                        throw new AssertionError("Helper unexpectedly loadable before bootstrap");
                    } catch (ClassNotFoundException expected) { }
                }
                Class<?> owner = Class.forName("AdninClientPump", true, loader);
                for (int i = 1; i < args.length; i++) {
                    Class<?> helper = Class.forName(args[i], false, loader);
                    if (helper.getClassLoader() != loader) throw new AssertionError("Helper belongs to a different loader");
                    if (loader.getResource(args[i] + ".class") != null) throw new AssertionError("Helper file appeared during bootstrap");
                }
                // Defining is duplicate safe; invoking twice must not link a new copy.
                Method bootstrap = owner.getDeclaredMethod("adninDefineHelpers");
                bootstrap.setAccessible(true);
                bootstrap.invoke(null);
                bootstrap.invoke(null);
                Class<?> api = Class.forName("AdninApi", false, loader);
                Method buildUrl = api.getMethod("buildBotUrl", String.class, String.class);
                Object result = buildUrl.invoke(null, "http://example.test/api/users?q=<>&page=0", "abc");
                if (!"http://example.test/api/users?q=abc&page=0".equals(result)) throw new AssertionError("Defined API helper is not callable");
                System.out.println("Bootstrap verified on Java " + System.getProperty("java.version") + ": " + (args.length - 1) + " helpers defined in owner loader, no helper files, duplicate calls passed");
            } finally { loader.close(); }
        } finally {
            Files.deleteIfExists(ownerFile);
            Files.deleteIfExists(isolated);
        }
    }
}
