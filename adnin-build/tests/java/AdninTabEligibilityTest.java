import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline predicate fixtures plus source guards for the two real Tab consumers. */
public final class AdninTabEligibilityTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected Java source directory");
        Class<?> features = Class.forName("AdninFeatures", true, AdninTabEligibilityTest.class.getClassLoader());
        check(!field(features, "initialized").getBoolean(null), "Game integration starts uninitialized");
        check(field(features, "settingsPath").get(null) == null, "No user settings path is opened");

        UUID normal = UUID.fromString("12345678-1234-4abc-8def-123456789abc");
        UUID nick = UUID.fromString("12345678-1234-1abc-8def-123456789abc");
        for (String name : new String[]{"A", "_", "0", "Player", "Mixed_CASE09", "1234567890123456"}) {
            check(AdninFeatures.isRealTabProfile(name, normal), "Valid normal-account profile: " + name);
            check(AdninFeatures.isRealTabProfile(name, nick), "Valid nick profile: " + name);
        }
        for (String name : new String[]{null, "", "12345678901234567", " Player", "Player ",
                "Player Name", "Player-Name", "Player.Name", "[VIP]Player", "\u00a7aPlayer",
                "Player\n", "Player\t", "Player\u0000", "J\u00f6rg", "\u73a9\u5bb6", "\ud83d\ude00"}) {
            check(!AdninFeatures.isRealTabProfile(name, normal), "Malformed normal-account name is rejected");
            check(!AdninFeatures.isRealTabProfile(name, nick), "Malformed nick name is rejected");
        }
        check(!AdninFeatures.isRealTabProfile("Player", null), "A missing UUID cannot enter either roster");
        check(!AdninFeatures.isRealTabProfile(null, null), "A wholly missing profile is rejected");
        for (int version = 0; version <= 15; version++) {
            UUID id = UUID.fromString("12345678-1234-" + Integer.toHexString(version) + "abc-8def-123456789abc");
            boolean expected = version == 1 || version == 4;
            check(AdninFeatures.isRealTabProfile("Player", id) == expected,
                    "Only UUID v1/v4 is eligible; tested v" + version);
        }
        check(!AdninFeatures.isRealTabProfile("Player", new UUID(0L, 0L)), "Nil UUID is rejected");
        // No server-authentication claim: a synthetic profile can look exactly
        // like a normal Tab account. The predicate has no independent proof.
        check(AdninFeatures.isRealTabProfile("NpcLikeName", normal),
                "A plausible v4 Tab profile is not falsely promised to be distinguishable as human");

        String source = new String(Files.readAllBytes(Paths.get(args[0], "AdninFeatures.java")), StandardCharsets.UTF_8);
        String scan = method(source, "scanPlayers");
        String match = method(source, "beginUrchinMatch");
        String apply = method(source, "applyUrchinContent");
        for (String body : new String[]{scan, match}) {
            check(body.contains("mc.getNetHandler().getPlayerInfoMap()"), "Roster comes from live Tab membership");
            check(!body.contains("loadedEntityList") && !body.contains("playerEntities"),
                    "World entities cannot substitute for the Tab roster");
            before(body, "info == null || info.getGameProfile() == null", "info.getGameProfile().getName()",
                    "Null entries/profiles are skipped before accessing their names");
            check(matches(body, "if\\s*\\(!isRealTabProfile\\(name,\\s*(?:uuid|info\\.getGameProfile\\(\\)\\.getId\\(\\))\\)\\)\\s*continue\\s*;"),
                    "Both roster consumers reject through the shared eligibility helper");
            check(matches(body, "if\\s*\\(\\+\\+count\\s*>\\s*256\\)\\s*break\\s*;"),
                    "Each roster enumeration remains bounded to 256 entries");
            check(!body.contains("AdninApi.fetch"), "Tab enumeration cannot perform API IO");
        }
        before(scan, "present.clear()", "present.add(", "Current membership is rebuilt on each scan");
        before(scan, "!isRealTabProfile(", "present.add(", "Only eligible Tab profiles enter present");
        before(match, "!isRealTabProfile(", "roster.put(", "Only eligible Tab profiles enter the frozen request roster");
        before(match, "roster.put(", "urchinCache.beginMatch(", "Eligibility is applied before cached or new lookup selection");
        check(!scan.contains("matchRequests.add") && !scan.contains("urchinCache.beginMatch("),
                "Periodic scans do not create new mid-match Urchin request batches");
        before(apply, "!present.contains(entry.getKey())", "generatedLocal(",
                "Cached tag announcements require the same filtered current membership");
        check(matches(apply, "if\\s*\\(entry\\.getValue\\(\\)\\.isEmpty\\(\\)\\s*\\|\\|\\s*!present\\.contains\\(entry\\.getKey\\(\\)\\)\\)\\s*continue\\s*;"),
                "Empty and absent-player cache entries cannot announce tags");
        check(matches(method(source, "tick"), "scanPlayers\\(mc,\\s*now\\);\\s*beginUrchinMatch\\(mc,\\s*now\\);"),
                "Match entry refreshes filtered membership before applying cached contents");
        check(!field(features, "initialized").getBoolean(null), "Predicate tests do not initialize Minecraft integration");
        check(field(features, "settingsPath").get(null) == null, "Predicate tests do not read private configuration");
        System.out.println("AdninTabEligibilityTest: " + checks + " checks passed; v1/v4 and name boundaries, shared live-Tab source guards and filtered cached announcements; no game initialization or network");
    }

    private static String method(String source, String name) {
        Matcher declaration = Pattern.compile("(?m)^    (?:private|public) static [^\\n]+\\b" + name + "\\([^\\n]*\\)\\s*\\{").matcher(source);
        check(declaration.find(), "Method exists: " + name);
        int start = declaration.start(), end = source.length();
        Matcher next = Pattern.compile("(?m)^    (?:private|public|protected) ").matcher(source);
        if (next.find(declaration.end())) end = next.start();
        // The checked methods have no string literals containing comment syntax.
        return source.substring(start, end).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\r\\n]*", "");
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static boolean matches(String source, String regex) { return Pattern.compile(regex).matcher(source).find(); }

    private static void before(String source, String first, String second, String description) {
        int a = source.indexOf(first), b = source.indexOf(second);
        check(a >= 0 && b > a, description);
    }

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
}
