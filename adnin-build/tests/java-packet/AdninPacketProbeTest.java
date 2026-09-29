import java.lang.reflect.Field;
import java.nio.file.Path;
import net.minecraft.network.play.client.C01PacketChatMessage;

/** Exercises the production Features bytecode with owned packet-policy fixtures. */
public final class AdninPacketProbeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        check(C01PacketChatMessage.class.getClassLoader() == AdninFeatures.class.getClassLoader(),
                "The packet fixture and actual Features use the same loader");
        check(C01PacketChatMessage.class.getProtectionDomain().getCodeSource().getLocation().equals(
                AdninPacketProbeTest.class.getProtectionDomain().getCodeSource().getLocation()),
                "The packet is the owned fixture, not a Lunar game class");
        check(!AdninFeatures.class.getProtectionDomain().getCodeSource().getLocation().equals(
                AdninPacketProbeTest.class.getProtectionDomain().getCodeSource().getLocation()),
                "Features comes from the actual supplied compiled output");
        checkStateUntouched();
        for (int limit : new int[]{100, 256, 80, 128, 6, 17, 255, 4096}) {
            C01PacketChatMessage.mode = "normal";
            C01PacketChatMessage.limit = limit;
            int constructors = C01PacketChatMessage.constructions;
            int reads = C01PacketChatMessage.reads;
            eq(Math.min(limit, 256), AdninFeatures.currentPartyCommandLimit(), "Accepted current constructor limit " + limit);
            eq(constructors + 1, C01PacketChatMessage.constructions, "One packet construction per probe");
            eq(reads + 1, C01PacketChatMessage.reads, "The probe reads the constructed packet");
            String probe = C01PacketChatMessage.lastInput;
            check(probe != null && probe.length() == 256 && probe.startsWith("/pc "),
                    "The complete probe is a 256-unit party command");
            boolean ascii = true;
            for (int i = 0; i < probe.length(); i++) {
                char value = probe.charAt(i);
                ascii &= value >= 0x20 && value <= 0x7e;
            }
            check(ascii, "Probe contains only printable ASCII");
            checkStateUntouched();
        }
        for (int limit : new int[]{0, 1, 4, 5}) {
            C01PacketChatMessage.mode = "normal";
            C01PacketChatMessage.limit = limit;
            eq(0, AdninFeatures.currentPartyCommandLimit(), "Too-short result cannot produce a useful party command");
        }
        for (String mode : new String[]{"null", "changed-prefix", "changed-body", "expanded",
                "constructor-exception", "getter-exception", "constructor-linkage", "getter-linkage"}) {
            C01PacketChatMessage.limit = 256;
            C01PacketChatMessage.mode = mode;
            eq(0, AdninFeatures.currentPartyCommandLimit(), "Invalid or failed packet probe is rejected: " + mode);
            checkStateUntouched();
        }
        C01PacketChatMessage.mode = "normal";
        C01PacketChatMessage.limit = 256;
        eq(256, AdninFeatures.currentPartyCommandLimit(), "A later successful probe recovers after failures");
        checkStateUntouched();
        System.out.println("AdninPacketProbeTest: " + checks
                + " checks passed; actual Features with owned packet policy, no Minecraft initialization or chat send");
    }

    private static void checkStateUntouched() throws Exception {
        Field initialized = AdninFeatures.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        Field settings = AdninFeatures.class.getDeclaredField("settingsPath");
        settings.setAccessible(true);
        Field world = AdninFeatures.class.getDeclaredField("world");
        world.setAccessible(true);
        check(!initialized.getBoolean(null), "Feature worker remains uninitialized");
        check((Path) settings.get(null) == null, "No settings file path is read or created");
        check(world.get(null) == null, "No game world is used");
    }

    private static void check(boolean result, String reason) {
        checks++;
        if (!result) throw new AssertionError(reason);
    }

    private static void eq(int expected, int actual, String reason) {
        check(expected == actual, reason + ": expected " + expected + ", got " + actual);
    }
}
