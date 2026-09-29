import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Test JNI-defined owners against the real signed vanilla class loader. */
public final class AdninSignedBootstrapVerify {
    private static native Class<?> define(ClassLoader loader, String name, byte[] data);
    private static native boolean initialized(Class<?> type);
    public static void main(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException("Expected shim, classpath, probes, helpers");
        System.load(Paths.get(args[0]).toAbsolutePath().toString());
        List<URL> urls = new ArrayList<URL>();
        for (String path : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            if (!path.isEmpty()) urls.add(Paths.get(path).toUri().toURL());
        }
        boolean legacyFailed = false;
        for (boolean legacy : new boolean[]{true,false}) {
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
                Class<?> anchor = Class.forName("ave",false,loader);
                Class<?> screen = Class.forName("axu",false,loader);
                Class<?> chat = Class.forName("avt",false,loader);
                if (anchor.getSigners() == null || anchor.getSigners().length == 0) throw new AssertionError("Expected signed vanilla runtime fixture");
                if (initialized(anchor) || initialized(screen) || initialized(chat)) throw new AssertionError("Game initialized before bootstrap");
                String probe = legacy ? "AdninSignedLegacyProbe" : "AdninSignedFixedProbe";
                Class<?> owner = define(loader,probe,Files.readAllBytes(Paths.get(args[2]).resolve(probe+".class")));
                if (owner == null) throw new AssertionError("JNI owner definition failed");
                try {
                    Class.forName(probe,true,loader);
                } catch (ExceptionInInitializerError failure) {
                    if (!legacy) throw failure;
                    Throwable cause = failure;
                    boolean signerFailure = false;
                    while (cause != null) {
                        if (cause instanceof SecurityException && String.valueOf(cause.getMessage()).contains("signer information")) signerFailure = true;
                        cause = cause.getCause();
                    }
                    if (!signerFailure) throw new AssertionError("Unexpected legacy bootstrap failure",failure);
                    legacyFailed = true;
                }
                if (!legacy) {
                    for (int i=3;i<args.length;i++) {
                        Class<?> helper = Class.forName(args[i],false,loader);
                        if (helper.getClassLoader()!=loader) throw new AssertionError("Wrong helper loader");
                        if (helper.getProtectionDomain().getCodeSource() == null ||
                            !Arrays.equals(anchor.getProtectionDomain().getCodeSource().getCertificates(),
                                           helper.getProtectionDomain().getCodeSource().getCertificates()))
                            throw new AssertionError("Helper protection domain certificate mismatch");
                    }
                    Method bootstrap = owner.getDeclaredMethod("adninDefineHelpers");
                    bootstrap.setAccessible(true); bootstrap.invoke(null); bootstrap.invoke(null);
                }
                if (initialized(anchor) || initialized(screen) || initialized(chat)) throw new AssertionError("Game initialized during bootstrap");
            }
        }
        System.out.println("Signed JNI bootstrap verified on Java "+System.getProperty("java.version")+": legacy signer failure="+legacyFailed+"; "+(args.length-3)+" fixed helpers match Minecraft protection domain certificates; game classes remain uninitialized");
    }
}
