import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** Resolve every generated class/member signature using real runtime jars.
 * Class initialization is explicitly disabled; no game or feature method runs.
 */
public final class AdninCompatLinkVerify {
    private static final class ByteLoader extends URLClassLoader {
        ByteLoader(URL[] urls) { super(urls, null); }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            URL resource = findResource(name.replace('.', '/') + ".class");
            if (resource == null) throw new ClassNotFoundException(name);
            // JNI DefineClass consumes byte arrays, not URLClassLoader's JAR
            // certificate domains. Use unchanged class bytes under one test
            // domain so signed vanilla classes can coexist with generated ones.
            try (InputStream input = resource.openStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                byte[] data = bytes.toByteArray();
                return defineClass(name, data, 0, data.length);
            } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Expected classpath list and class names");
        List<URL> urls = new ArrayList<URL>();
        for (String path : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            if (!path.isEmpty()) urls.add(Paths.get(path).toUri().toURL());
        }
        int methods = 0, fields = 0;
        try (URLClassLoader loader = new ByteLoader(urls.toArray(new URL[0]))) {
            for (int i = 1; i < args.length; i++) {
                Class<?> type = Class.forName(args[i], false, loader);
                if (type.getClassLoader() != loader) throw new AssertionError("Unexpected generated class loader");
                methods += type.getDeclaredMethods().length;
                fields += type.getDeclaredFields().length;
                type.getDeclaredConstructors();
            }
        }
        System.out.println("Real-runtime JVM linkage verified without initialization: " + (args.length - 1) + " classes, " + methods + " methods, " + fields + " fields");
    }
}
