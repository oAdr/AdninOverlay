import java.net.URL;
import java.nio.file.Paths;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

public final class ForgeFacadeVerify {
    private static native void verify();
    private static Class<?> uninitialized(String name) throws Exception {
        return Class.forName("net.minecraft.fixture.InitializationProbe$"+name, false, Launch.classLoader);
    }
    private static boolean initialized(String name) {
        return System.getProperty("adnin.fixture."+name) != null;
    }
    public static void main(String[] args) throws Exception {
        Launch.classLoader=new LaunchClassLoader(new URL[]{Paths.get(args[1]).toUri().toURL()});
        System.load(Paths.get(args[0]).toAbsolutePath().toString());
        verify();
        verify();
        if (Class.forName("adnin.forge.RuntimeMappings", false, Launch.classLoader).getClassLoader() != Launch.classLoader)
            throw new AssertionError("Runtime mappings did not use the actual game loader");
        System.out.println("Forge JNI facade: actual child loader, SRG instance/static methods, inherited fields, constructors, primitive/array descriptors and exception recovery passed on Java "+System.getProperty("java.version"));
    }
}
