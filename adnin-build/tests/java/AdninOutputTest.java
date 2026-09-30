import java.util.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Real generated-message callbacks and pure party queue checks; no game world. */
public final class AdninOutputTest {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        check("[Adnin] hi there /msg secret".equals(AdninFeatures.cleanText("\u00a7b[Adnin]\u00a7r hi\nthere\r/msg secret")), "format and line cleanup");
        partyNormalization();
        lengthBoundaries();
        AdninFeatures.setGameActive(true);
        AdninGui4.chatOutput = true;
        queuedOutput();
        actualGeneratedCallbacks();
        System.out.println("AdninOutputTest: "+checks+" checks passed; no network or game messages sent");
    }

    private static void partyNormalization() {
        for (String empty : new String[]{null, "", " \n\t ", "[Adnin]", "\u00a7b[Adnin]\u00a7r  "}) {
            eq("", AdninFeatures.partyText(empty), "Empty or branding-only party body is ignored");
            check(AdninFeatures.partyCommands(empty).isEmpty(), "Empty party content creates no command");
        }
        eq("UnitNick -> RealPlayer", AdninFeatures.partyText(" \u00a77[\u00a71Adn\u00a7fin\u00a7r\u00a77]  UnitNick -> RealPlayer "),
                "Only party normalization removes the color-fragmented leading brand");
        eq("[Seraph] UnitPlayer: CHEATER", AdninFeatures.partyText("[Adnin] [Seraph] UnitPlayer: CHEATER"),
                "Removing outer Adnin branding preserves Seraph label");
        eq("[Urchin] UnitPlayer: BC", AdninFeatures.partyText("[Adnin] [Urchin] UnitPlayer: BC"),
                "Removing outer Adnin branding preserves Urchin label");
        eq("[Urchin] UnitPlayer: reason mentions [Adnin]", AdninFeatures.partyText("[Urchin] UnitPlayer: reason mentions [Adnin]"),
                "Adnin inside actual content is preserved");
        eq("[AdninExtra] UnitPlayer", AdninFeatures.partyText("[AdninExtra] UnitPlayer"),
                "An unrelated bracketed label is not treated as Adnin branding");
        for (String input : new String[]{"/msg player hello", "[Adnin] /msg player hello"}) {
            eq("/pc /msg player hello", AdninFeatures.partyCommands(input).get(0),
                    "An untrusted slash in the body cannot escape the party command");
        }
    }

    private static void lengthBoundaries() {
        // The supplied screenshot has five naturally wrapped rows of
        // 51 + 51 + 51 + 51 + 48 digits, all under one party-message header.
        String screenshot = digits(252);
        List<String> direct = AdninFeatures.partyCommands(screenshot);
        check(direct.size() == 1, "Screenshot's 252-digit body stays in one default party command");
        eq("/pc " + screenshot, direct.get(0), "Screenshot command is exactly 256 UTF-16 units with its prefix");
        for (int[] boundary : new int[][]{{96,1,1}, {97,1,2}, {100,1,2}, {101,1,2},
                {251,1,3}, {252,1,3}, {253,2,3}}) {
            String body = digits(boundary[0]);
            commands(body, body, 256, boundary[1], "256-limit ASCII boundary " + boundary[0]);
            commands(body, body, 100, boundary[2], "100-limit ASCII boundary " + boundary[0]);
        }
        commands("[Adnin] " + screenshot, screenshot, 256, 1,
                "Removed branding does not consume the available party body length");
        commands(digits(1500), digits(1500), 256, 6, "Maximum body retains its entire tail at 256 limit");
        commands(digits(1500), digits(1500), 100, 16, "Low-cap transport does not drop messages after eight fragments");
        commands("[Adnin] " + digits(1500), digits(1492), 256, 6,
                "Existing 1500-unit cleaning budget includes the removed eight-unit brand prefix");
        for (int limit : new int[]{-1,0,4,5}) {
            check(AdninFeatures.partyCommands("pending",limit).isEmpty(),
                    "A capacity too small for a safe party body produces no command");
        }
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 400; i++) emoji.append("\ud83d\ude00");
        commands(emoji.toString(), emoji.toString(), 256, 4, "Emoji content is preserved at 256 limit");
        commands(emoji.toString(), emoji.toString(), 100, 9, "Emoji content retains the ninth low-cap fragment");
        for (int limit : new int[]{100,256}) {
            String crossing = digits(limit - 5) + "\ud83d\ude00tail";
            commands(crossing, crossing, limit, 2, "Surrogate pair crossing a command boundary at " + limit);
        }
    }

    private static void queuedOutput() {
        AdninFeatures.clearPartyQueue();
        AdninFeatures.enqueueParty("\u00a7b[Adnin]\u00a7r stats 5",1000);
        AdninFeatures.enqueueParty("\u00a7cstats 5",1100);
        AdninFeatures.enqueueParty("[Adnin]   stats 5",1150);
        check("/pc stats 5".equals(AdninFeatures.pollPartyCommand(1200)), "generated event delivered");
        check(AdninFeatures.pollPartyCommand(1200)==null,"dedup uses final body after color, whitespace and brand removal");
        AdninFeatures.clearPartyQueue();
        AdninFeatures.enqueueParty("[Adnin] [Seraph] same",1000);
        AdninFeatures.enqueueParty("[Seraph] same",1100);
        AdninFeatures.enqueueParty("[Urchin] same",1200);
        eq("/pc [Seraph] same", AdninFeatures.pollPartyCommand(1300), "Seraph aliases deduplicate with outer brand removed");
        eq("/pc [Urchin] same", AdninFeatures.pollPartyCommand(1300), "Different retained provider prefixes remain distinct");
        check(AdninFeatures.pollPartyCommand(1300) == null, "Provider-label test contains exactly two distinct results");
        AdninFeatures.clearPartyQueue();
        AdninFeatures.enqueueParty("[Adnin] boundary",1000);
        eq("/pc boundary", AdninFeatures.pollPartyCommand(1000), "First canonical body is sent");
        AdninFeatures.enqueueParty("boundary",30999);
        check(AdninFeatures.pollPartyCommand(30999) == null, "Canonical duplicate is suppressed through 29,999 ms");
        AdninFeatures.enqueueParty("\u00a7c[Adnin] boundary",31000);
        eq("/pc boundary", AdninFeatures.pollPartyCommand(31000), "Canonical body may recur at exactly 30,000 ms");
        AdninFeatures.clearPartyQueue();
        AdninFeatures.enqueueParty("at expiry",2000);
        eq("/pc at expiry", AdninFeatures.pollPartyCommand(17000), "Queued message remains valid at exactly 15,000 ms");
        AdninFeatures.enqueueParty("old world",2000);
        check(AdninFeatures.pollPartyCommand(17001)==null,"stale messages expire after 15,000 ms");
        AdninFeatures.enqueueParty("disabled",20000); AdninFeatures.clearPartyQueue();
        check(AdninFeatures.pollPartyCommand(20001)==null,"disable/world change clears output");
        AdninFeatures.enqueueParty("disabled",20001);
        eq("/pc disabled", AdninFeatures.pollPartyCommand(20002), "Clearing the queue also resets canonical dedup state");
        AdninFeatures.clearPartyQueue();
        AdninFeatures.enqueueParty("[Adnin] " + digits(252),1000);
        checkQueued(digits(252), drain(1100,100), 100, 3,
                "Transport cap selected after enqueue controls all fragments");
        AdninFeatures.clearPartyQueue();
        AdninFeatures.enqueueParty("pending capacity",1000);
        for (int limit : new int[]{-1,0,4,5}) {
            check(AdninFeatures.pollPartyCommand(1100,limit) == null,
                    "Unavailable or unsafe capacity does not emit a command");
        }
        eq("/pc pending capacity", AdninFeatures.pollPartyCommand(1101,256),
                "Rejected capacities preserve the entire fresh queued event");
        check(AdninFeatures.pollPartyCommand(1101,256) == null,
                "Retried fresh event is delivered only once");
        changingLimit(256,100,5);
        changingLimit(100,256,4);
        AdninFeatures.clearPartyQueue();
        AdninFeatures.enqueueParty(digits(604),1000);
        check(AdninFeatures.pollPartyCommand(1001,100) != null, "Long queue item begins delivering");
        AdninFeatures.clearPartyQueue();
        check(AdninFeatures.pollPartyCommand(1002,256) == null, "Disable/world clear removes the unsent remainder too");
        AdninFeatures.enqueueParty("\u00a7c[Adnin]\u00a7r ",1000);
        check(AdninFeatures.pollPartyCommand(1001) == null, "Branding-only content does not enter queue");
        for (int i=0;i<60;i++) AdninFeatures.enqueueParty("event "+i,30000);
        int count=0; while(AdninFeatures.pollPartyCommand(30001)!=null) count++;
        check(count==32,"queue is bounded");
        AdninFeatures.enqueueParty("event 32",30002);
        eq("/pc event 32", AdninFeatures.pollPartyCommand(30003),
                "A queue-overflow rejection does not pretend the dropped event was sent");
        AdninFeatures.clearPartyQueue();
    }

    private static void changingLimit(int firstLimit, int laterLimit, int totalFragments) {
        AdninFeatures.clearPartyQueue();
        String body = digits(604);
        AdninFeatures.enqueueParty("[Adnin] " + body,1000);
        String first = AdninFeatures.pollPartyCommand(1001,firstLimit);
        check(first != null, "Queued long body produces first fragment");
        eq("/pc " + body.substring(0, firstLimit - 4), first, "First poll uses its current transport limit");
        AdninFeatures.enqueueParty(body,1002);
        List<String> rest = drain(1003,laterLimit);
        check(rest.size() + 1 == totalFragments, "Remainder adapts to changed cap without duplicated queue item");
        String reconstructed = rejoin(Arrays.asList(first),firstLimit) + rejoin(rest,laterLimit);
        eq(body, reconstructed, "Changing cap mid-message preserves every character in order");
    }

    private static void commands(String input, String body, int limit, int count, String why) {
        checkQueued(body, AdninFeatures.partyCommands(input,limit), limit, count, why);
    }

    private static void checkQueued(String body, List<String> pieces, int limit, int count, String why) {
        check(pieces.size() == count, why + ": only necessary fragments");
        eq(body, rejoin(pieces,limit), why + ": exact full reconstruction");
    }

    private static List<String> drain(long now, int limit) {
        List<String> result = new ArrayList<String>();
        for (int guard = 0; guard < 1024; guard++) {
            String command = AdninFeatures.pollPartyCommand(now,limit);
            if (command == null) return result;
            result.add(command);
        }
        throw new AssertionError("Queue did not terminate within fixture bound");
    }

    private static String rejoin(List<String> commands, int limit) {
        StringBuilder body = new StringBuilder();
        for (String command : commands) {
            check(command.startsWith("/pc ") && command.length() > 4 && command.length() <= limit,
                    "Every fragment stays inside its current party-command boundary");
            String part = command.substring(4);
            check(!Character.isLowSurrogate(part.charAt(0)) && !Character.isHighSurrogate(part.charAt(part.length()-1)),
                    "No fragment cuts a UTF-16 surrogate pair");
            check(part.indexOf('\u00a7') < 0 && part.indexOf('\n') < 0 && part.indexOf('\r') < 0,
                    "Transmitted content is plain and single-line");
            body.append(part);
        }
        return body.toString();
    }

    private static String digits(int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append((char) ('0' + (i + 1) % 10));
        return result.toString();
    }

    private static void eq(String expected, String actual, String why) {
        check(expected.equals(actual), why);
    }

    private static void actualGeneratedCallbacks() throws Exception {
        // Exercise the exact public Java entrypoint called by the native bridge.
        // GUI class initialization only sets its configuration defaults. We never
        // instantiate a screen, initialize Minecraft, tick, or drain to a player.
        Class<?> gui = Class.forName("AdninGui4", true, AdninOutputTest.class.getClassLoader());
        Field output = gui.getDeclaredField("chatOutput");
        Method generated = gui.getDeclaredMethod("nativeGeneratedEvent", String.class, boolean.class);
        Field initialized = AdninFeatures.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        Field world = AdninFeatures.class.getDeclaredField("world");
        world.setAccessible(true);
        boolean previous = output.getBoolean(null);
        check(!initialized.getBoolean(null) && world.get(null) == null,
                "Callback test begins without feature worker or game world");
        String json = "{\"text\":\"\",\"extra\":["
                + "{\"text\":\"\u00a7c[Seraph] \"},"
                + "{\"text\":\"Unit\",\"extra\":[{\"text\":\"Player\"}],"
                + "\"hoverEvent\":{\"action\":\"show_text\",\"value\":{\"text\":\"DO_NOT_SEND_HOVER\"}},"
                + "\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/msg hidden DO_NOT_SEND_CLICK\"}},"
                + "{\"text\":\": CHEATER\"}]}";
        // Exact plain-text shape assembled by the original Skin Denicker's
        // success branch; its color-fragmented brand is not a JSON component.
        String skin = "\u00a77[\u00a71Adn\u00a7fin\u00a7r\u00a77] \u00a7cUnitNick\u00a7r\u00a77 -> \u00a7fRealPlayer";
        try {
            output.setBoolean(null, false);
            AdninFeatures.clearPartyQueue();
            generated.invoke(null, "[Seraph] Disabled plain", false);
            generated.invoke(null, json, true);
            generated.invoke(null, skin, false);
            generated.invoke(null, "[Adnin] " + digits(252), false);
            check(AdninFeatures.pollPartyCommand(System.currentTimeMillis()) == null,
                    "Output disabled: actual plain and JSON callbacks never enqueue");

            output.setBoolean(null, true);
            generated.invoke(null, "\u00a7c[Seraph]\u00a7r UnitPlayer: CHEATER", false);
            check("/pc [Seraph] UnitPlayer: CHEATER".equals(
                    AdninFeatures.pollPartyCommand(System.currentTimeMillis())),
                    "Actual plain callback preserves Seraph prefix and enforces party command");
            check(AdninFeatures.pollPartyCommand(System.currentTimeMillis()) == null,
                    "Plain callback enqueues exactly one message");

            AdninFeatures.clearPartyQueue();
            generated.invoke(null, skin, false);
            generated.invoke(null, "[Adnin] UnitNick -> RealPlayer", false);
            generated.invoke(null, "UnitNick -> RealPlayer", false);
            generated.invoke(null, "{\"text\":\"[Adnin] \",\"extra\":[{\"text\":\"UnitNick -> RealPlayer\"}]}", true);
            check("/pc UnitNick -> RealPlayer".equals(
                    AdninFeatures.pollPartyCommand(System.currentTimeMillis())),
                    "Original Skin Denicker party output removes only branding and retains both names");
            check(AdninFeatures.pollPartyCommand(System.currentTimeMillis()) == null,
                    "Colored, plain, JSON and unbranded Skin aliases share one canonical dedup key");

            AdninFeatures.clearPartyQueue();
            generated.invoke(null, "[Adnin] " + digits(252), false);
            eq("/pc " + digits(252), AdninFeatures.pollPartyCommand(System.currentTimeMillis()),
                    "Exact generated-message callback preserves screenshot-length body in one default command");
            check(AdninFeatures.pollPartyCommand(System.currentTimeMillis()) == null,
                    "Screenshot-length native callback does not create unnecessary tail fragments");

            AdninFeatures.clearPartyQueue();
            generated.invoke(null, json, true);
            String flattened = AdninFeatures.pollPartyCommand(System.currentTimeMillis());
            check("/pc [Seraph] UnitPlayer: CHEATER".equals(flattened),
                    "Actual Minecraft serializer flattens nested JSON extra and keeps Seraph prefix");
            check(flattened != null && !flattened.contains("DO_NOT_SEND_HOVER")
                    && !flattened.contains("DO_NOT_SEND_CLICK") && !flattened.contains("/msg"),
                    "Hover and click metadata are never copied into party content");
            check(AdninFeatures.pollPartyCommand(System.currentTimeMillis()) == null,
                    "JSON callback sends only visible text, without extra metadata messages");

            AdninFeatures.clearPartyQueue();
            for (String invalid : new String[]{"{", "{\"text\":", "{\"extra\":[]}", "null"}) {
                generated.invoke(null, invalid, true);
                check(AdninFeatures.pollPartyCommand(System.currentTimeMillis()) == null,
                        "Invalid or null JSON component is rejected: " + invalid);
            }
            generated.invoke(null, null, false);
            check(AdninFeatures.pollPartyCommand(System.currentTimeMillis()) == null,
                    "Null native message is rejected");
            check(!initialized.getBoolean(null) && world.get(null) == null,
                    "Real callbacks do not start the worker or initialize a game world");
        } finally {
            output.setBoolean(null, previous);
            AdninFeatures.clearPartyQueue();
        }
    }
}
