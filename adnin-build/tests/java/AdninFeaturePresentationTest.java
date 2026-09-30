import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

/** Offline presentation/queue checks. Never calls tick, sends chat, or opens settings. */
public final class AdninFeaturePresentationTest {
    private static int checks;
    private static final String NAME = "UnitPlayer";
    private static final String UUID4 = "12345678-1234-4234-8234-123456789abc";
    private static final long NOW = 1000000L;

    public static void main(String[] args) throws Exception {
        check(!field("initialized").getBoolean(null), "Fixture starts without integration initialization");
        check(field("settingsPath").get(null) == null, "Fixture has no settings path");
        playerColors();
        teamPriority();
        urchinMessages();
        botMessages();
        skinMessages();
        urchinErrorThrottle();
        tabTagTypes();
        overlayNamesAndLabels();
        botPartyPolicy();
        check(!field("initialized").getBoolean(null), "Presentation never starts feature integration");
        check(field("settingsPath").get(null) == null, "Presentation never reads or writes personal settings");
        check(field("world").get(null) == null, "Presentation never initializes a game world");
        check(field("apiCompleted").getLong(null) == 0, "Presentation performs no API work");
        System.out.println("AdninFeaturePresentationTest: " + checks
                + " checks passed; real-name colors, team priority, per-tag presentation and verified-only Bot party queue; no game, network, settings or sends");
    }

    private static void skinMessages() {
        String message = AdninFeatures.formattedSkinMessage("UnitNick", "\u00a76[VIP] \u00a7bUnitNick", "RealPlayer");
        eq("Skin Denicker UnitNick → RealPlayer", plain(message), "Skin denick keeps both verified identities");
        wordColor(message, "Skin Denicker", '6', "Skin label is gold");
        wordColor(message, "UnitNick", 'b', "Skin nick follows its actual nametag color");
        wordColor(message, "RealPlayer", 'b', "Resolved Skin name inherits the visible nick color");
        neutralSeparators(message, '→');
        check(message.endsWith("\u00a7r"), "Skin success resets its trailing name color");
        String numeric = AdninFeatures.formattedSkinMessage("12345", "\u00a7c[VIP]\u00a7r 12345", "RealPlayer");
        wordColor(numeric, "12345", 'f', "Numeric Skin nickname obeys its explicit reset color");
        wordColor(numeric, "RealPlayer", 'f', "Reset-white nickname passes white to Skin owner");
        check(numeric.endsWith("\u00a7r"), "Numeric Skin nickname cannot leak formatting");
        eq("/pc Skin Denicker UnitNick → RealPlayer", AdninFeatures.partyCommands(message).get(0),
                "Skin output uses the same plain party formatting");
        eq("", AdninFeatures.formattedSkinMessage("Bad Name", null, "RealPlayer"), "Invalid Skin nick cannot inject text");
        eq("", AdninFeatures.formattedSkinMessage("UnitNick", null, "Bad Name"), "Invalid resolved Skin name cannot inject text");
        AdninFeatures.setGameActive(false);
        check(!AdninFeatures.skinResolved("UnitNick", "RealPlayer"), "Out-of-game Skin result cannot consume later presentation");
    }

    @SuppressWarnings("unchecked")
    private static void overlayNamesAndLabels() throws Exception {
        Method player = AdninFeatures.class.getDeclaredMethod("overlayPlayer", String.class);
        player.setAccessible(true);
        Set<String> present = (Set<String>) field("present").get(null);
        Map<String, String> labels = (Map<String, String>) field("tagLabels").get(null);
        AdninUrchinCache cache = (AdninUrchinCache) field("urchinCache").get(null);
        Method apply = AdninFeatures.class.getDeclaredMethod("applyUrchinContent"); apply.setAccessible(true);
        try {
            present.add("xiaoshu_sky2026"); present.add("12345"); present.add("unitplayer");
            eq("xiaoshu_sky2026", (String) player.invoke(null, "\u00a7b[MVP+] \u00a7aXiaoShu_SKY2026"),
                    "Overlay matches the complete colored Replay name");
            eq("12345", (String) player.invoke(null, "\u00a7e12345"), "Numeric roster names remain supported");
            check(player.invoke(null, "XUnitPlayerX") == null, "Larger name tokens cannot become another player's row");
            check(player.invoke(null, "XiaoShu_SKY202") == null, "Truncated names cannot become the full player's row");
            eq("unitplayer", (String) player.invoke(null, "[12345] UnitPlayer"), "Last complete roster token remains the displayed name");
            present.clear();
            cache.beginMatch(100L, java.util.Collections.singletonMap("UnitPlayer", NAME), NOW);
            cache.complete(100L, NAME, Arrays.asList("confirmed_cheater: reason", "legit_sniper", "legit_sniper"), true, NOW);
            apply.invoke(null);
            eq("Confirmed, LS", plain(labels.get("unitplayer")), "Urchin abbreviations are prepared when content changes");
            cache.clearMatch(); apply.invoke(null);
            check(labels.isEmpty(), "An empty visible snapshot releases prior Urchin labels");
        } finally {
            present.clear(); cache.clear(); labels.clear();
            ((Map<?, ?>) field("tags").get(null)).clear();
        }
    }

    private static void playerColors() {
        nameColor(NAME, "\u00a76[MVP++] \u00a7b" + NAME + "\u00a7c [TEAM]", repeat('b', 10), "Name color excludes rank and suffix");
        nameColor(NAME, "\u00a7cU\u00a79ni\u00a7atPlayer", "c99aaaaaaa", "Individual name color changes are preserved");
        nameColor(NAME, "\u00a76[RANK]\u00a7r Unit\u00a7aPlayer", "ffffaaaaaa", "Reset before name restores white");
        nameColor(NAME, "\u00a7C\u00a7lU\u00a7mn\u00a7oit\u00a7kPlayer", repeat('c', 10), "Styles are discarded without changing color");
        nameColor(NAME, "\u00a7aunitplayer", repeat('a', 10), "Case-insensitive lookup preserves original roster spelling");
        nameColor(NAME, "\u00a7c[UnitPlayer] \u00a7bUnitPlayer", repeat('b', 10), "Last exact token is the actual name");
        for (String formatted : new String[]{null, "", NAME, "\u00a7aOtherPlayer", "\u00a7aXUnitPlayerX", "\u00a7aUnitPlayer_", "\u00a7a_UnitPlayer"}) {
            eq("\u00a7f" + NAME + "\u00a7r", AdninFeatures.coloredPlayerName(NAME, formatted), "Missing exact colored token falls back to white");
        }
        nameColor("a_9", "\u00a72a_9", "222", "Valid short underscore name");
        nameColor("AbCdEfGhIjKlMn_9", "\u00a7eAbCdEfGhIjKlMn_9", repeat('e', 16), "Sixteen-character name is valid");
        for (String invalid : new String[]{null, "", "ABCDEFGHIJKLMNOPQ", "Bad Name", "Bad-Name", "/command", "\u00a7cName", "\u73a9\u5bb6"}) {
            eq("", AdninFeatures.coloredPlayerName(invalid, "\u00a7cUnitPlayer"), "Invalid names cannot inject formatted text");
        }
    }

    private static void teamPriority() throws Exception {
        Method method = AdninFeatures.class.getDeclaredMethod("playerNametag", NetworkPlayerInfo.class, String.class);
        method.setAccessible(true);
        Scoreboard scoreboard = new Scoreboard();
        ScorePlayerTeam team = new ScorePlayerTeam(scoreboard, "fixture");
        IChatComponent tab = new ChatComponentText(NAME).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.BLUE));
        team.setNamePrefix("\u00a7c[RED] ");
        team.setNameSuffix("\u00a7e [TAIL]");
        String actual = (String) method.invoke(null, new FixturePlayer(team, tab), NAME);
        renderedName(NAME, actual, repeat('c', 10), "Explicit world-team color takes priority over alternate Tab color");
        team.setNamePrefix("\u00a7r");
        renderedName(NAME, (String) method.invoke(null, new FixturePlayer(team, tab), NAME), repeat('f', 10), "Explicit team reset takes priority");
        team.setNamePrefix("[TEAM] ");
        team.setNameSuffix("");
        renderedName(NAME, (String) method.invoke(null, new FixturePlayer(team, tab), NAME), repeat('9', 10), "Unspecified team color falls back to Tab style");
        renderedName(NAME, (String) method.invoke(null, new FixturePlayer(null, tab), NAME), repeat('9', 10), "Missing team uses formatted component");
        renderedName(NAME, (String) method.invoke(null, new FixturePlayer(null, null), NAME), repeat('f', 10), "Missing team and Tab use neutral roster name");
        IChatComponent ranked = new ChatComponentText("[VIP] ").setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GOLD));
        ranked.appendSibling(new ChatComponentText(NAME).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.AQUA)));
        renderedName(NAME, (String) method.invoke(null, new FixturePlayer(null, ranked), NAME), repeat('b', 10), "Nested component applies name color rather than rank color");
    }

    private static void urchinMessages() {
        List<String> values = new ArrayList<String>(Arrays.asList("confirmed_cheater: First reason", "legit_sniper: Second reason", "possible_sniper: Third reason", "sniper: Fourth reason", "blatant_cheater: Fifth reason", "closet_cheater: Sixth reason", "information: Final reason"));
        List<String> original = new ArrayList<String>(values);
        String message = AdninFeatures.formattedUrchinMessage(NAME, "\u00a76[RANK] \u00a7b" + NAME, values);
        check(message.startsWith("[Urchin] "), "Prefix stays plain for local() to color once");
        eq("[Urchin] UnitPlayer: Confirmed: First reason | LS: Second reason | PS: Third reason | sniper: Fourth reason | blatant_cheater: Fifth reason | closet_cheater: Sixth reason | information: Final reason",
                plain(message), "Full reasons survive type-label presentation");
        wordColor(message, NAME, 'b', "Roster name retains its actual nametag color");
        tagLabelAndReason(message, "Confirmed", "First reason", '5');
        tagLabelAndReason(message, "LS", "Second reason", 'c');
        tagLabelAndReason(message, "PS", "Third reason", 'c');
        tagLabelAndReason(message, "sniper", "Fourth reason", 'c');
        tagLabelAndReason(message, "blatant_cheater", "Fifth reason", '6');
        tagLabelAndReason(message, "closet_cheater", "Sixth reason", '6');
        tagLabelAndReason(message, "information", "Final reason", '7');
        for (String value : values) check(message.contains(AdninMetrics.coloredTagText(value)), "Every complete colored tag segment is included");
        neutralSeparators(message, '|');
        check(message.endsWith("\u00a7r"), "Final tag resets color");
        check(values.equals(original), "Presentation never rewrites cached tag data");
        List<String> defaultCommands = AdninFeatures.partyCommands(message);
        check(defaultCommands.size() == 1, "The complete Urchin announcement fits one 256-unit party command");
        eq("/pc " + plain(message), defaultCommands.get(0), "Urchin provider prefix and every reason survive default output");
        for (int limit : new int[]{100,256}) {
            StringBuilder sent = new StringBuilder();
            for (String command : AdninFeatures.partyCommands(message,limit)) {
                check(command.startsWith("/pc ") && command.length() <= limit && command.indexOf('\u00a7') < 0,
                        "Party commands stay plain and bounded by the selected transport");
                sent.append(command.substring(4));
            }
            eq(plain(message), sent.toString(), "Party splitting retains every visible Urchin reason at either cap");
        }
        eq("", AdninFeatures.formattedUrchinMessage("Bad Name", "\u00a7cBad Name", values), "Invalid name cannot produce an Urchin announcement");
    }

    private static void botMessages() {
        String nick = "UnitNick", real = "RealPlayer";
        String message = AdninFeatures.formattedBotMessage(nick, "\u00a76[RANK] \u00a7a" + nick,
                real, "\u00a7c[OTHER] \u00a7b" + real, true, "");
        eq("Bot Denicker UnitNick → RealPlayer", plain(message), "Verified Bot message keeps its existing visible content");
        check(message.startsWith("\u00a76Bot Denicker\u00a7r "), "Gold Bot title ends with an explicit reset");
        wordColor(message, "Bot Denicker", '6', "Bot title is gold");
        wordColor(message, nick, 'a', "Nick uses its own name color rather than the rank color");
        wordColor(message, real, 'b', "Resolved name uses its independently supplied nametag color");
        neutralSeparators(message, '→');
        check(message.endsWith("\u00a7r"), "Successful Bot message cannot leak name color to following text");
        String gradientNick = "\u00a76[RANK] \u00a7cU\u00a7anitNick";
        String inherited = AdninFeatures.formattedBotMessage(nick, gradientNick, real, null, true, "");
        wordColor(inherited, real, 'c', "Hidden real identity inherits the first actual nick-name color, never the rank color");
        String unrelated = AdninFeatures.formattedBotMessage(nick, gradientNick, real, "\u00a7eOtherPlayer", true, "");
        wordColor(unrelated, real, 'c', "Unrelated formatted text cannot supply the resolved player's color");
        String noNames = AdninFeatures.formattedBotMessage(nick, null, real, null, true, "");
        wordColor(noNames, nick, 'f', "Missing nick formatting falls back to white");
        wordColor(noNames, real, 'f', "Missing real formatting inherits the white fallback");
        String absent = AdninFeatures.formattedBotMessage(nick, "\u00a7a" + nick, "", null, false, "");
        eq("Bot Denicker UnitNick → No results", plain(absent), "Empty lookup has a fixed local result");
        wordColor(absent, "Bot Denicker", '6', "No-result title remains gold");
        wordColor(absent, nick, 'a', "No-result message still preserves the nick color");
        wordColor(absent, "No results", 'c', "No results is red");
        String failed = AdninFeatures.formattedBotMessage(nick, "\u00a7a" + nick, "", null, false, "fixture-error-payload");
        eq("Bot Denicker UnitNick → Request failed. Will retry.", plain(failed), "Lookup errors use a fixed safe message");
        wordColor(failed, "Request failed. Will retry.", 'c', "Complete request-failure content is red");
        check(!failed.contains("fixture-error-payload"), "External error contents are never echoed");
        String unverified = AdninFeatures.formattedBotMessage(nick, "\u00a7a" + nick, real, "\u00a7b" + real, false, "profile-unavailable");
        wordColor(unverified, nick, 'a', "Unverified result preserves original nick color");
        wordColor(unverified, real, 'b', "Unverified result preserves independently supplied real-name color");
        wordColor(unverified, "(account verification unavailable; will retry)", 'c', "Complete verification-unavailable note is red");
        for (String errorMessage : new String[]{absent, failed, unverified}) {
            check(errorMessage.endsWith("\u00a7r"), "Every error message ends with reset");
            neutralSeparators(errorMessage, '→');
        }
        eq("", AdninFeatures.formattedBotMessage("Bad Name", null, real, null, true, ""), "Invalid nick cannot produce a Bot announcement");
        eq("/pc Bot Denicker UnitNick → RealPlayer", AdninFeatures.partyCommands(message).get(0), "Bot colors never enter transmitted party text");
    }

    private static void urchinErrorThrottle() throws Exception {
        Method method = AdninFeatures.class.getDeclaredMethod("urchinErrorForChat", String.class, long.class);
        method.setAccessible(true);
        Field throttle = field("nextError");
        long previous = throttle.getLong(null);
        try {
            throttle.setLong(null, 0);
            for (String code : new String[]{null, "", "player-not-found"}) {
                eq("", (String) method.invoke(null, code, NOW), "Missing and 404 errors are silent");
                check(throttle.getLong(null) == 0, "Silent error cannot consume visible-error throttle");
            }
            String auth = (String) method.invoke(null, "authentication-failed", NOW);
            check(!auth.isEmpty() && plain(auth).contains("401"), "Authentication error remains visible immediately after 404");
            wordColor(auth, plain(auth).substring("[Urchin] ".length()), 'c', "Entire authentication error body and retry advice are red");
            check(auth.endsWith("\u00a7r"), "Urchin error body ends with reset");
            check(throttle.getLong(null) == NOW + 60000, "Only a visible error starts the sixty-second throttle");
            eq("", (String) method.invoke(null, "authentication-failed", NOW + 59999), "Repeated visible errors are throttled");
            eq("", (String) method.invoke(null, "player-not-found", NOW + 100000), "A later 404 remains silent");
            check(throttle.getLong(null) == NOW + 60000, "A later 404 does not extend throttle");
            check(!((String) method.invoke(null, "authentication-failed", NOW + 60000)).isEmpty(), "Visible error is allowed at the exact sixty-second boundary");
            Method local = AdninFeatures.class.getDeclaredMethod("local", String.class, Minecraft.class);
            local.setAccessible(true);
            local.invoke(null, (Object) null, null);
            local.invoke(null, "", null);
            check(true, "Empty and null local messages are safely ignored");
        } finally {
            throttle.setLong(null, previous);
        }
    }

    private static void tabTagTypes() throws Exception {
        Method method = AdninFeatures.class.getDeclaredMethod("tagTypes", List.class);
        method.setAccessible(true);
        List<String> tags = Arrays.asList("confirmed_cheater: First", "confirmed_cheater: Second", "legit_sniper: Third", "possible_sniper: Fourth", "legit_sniper: Fifth");
        String actual = (String) method.invoke(null, tags);
        eq("Confirmed, LS, PS", plain(actual), "Tab tags deduplicate types independently of their reasons");
        wordColor(actual, "Confirmed", '5', "Confirmed label keeps its own color");
        wordColor(actual, "LS", 'c', "LS label keeps its own color");
        wordColor(actual, "PS", 'c', "PS label keeps its own color");
        for (String type : new String[]{"confirmed_cheater", "legit_sniper", "possible_sniper"}) {
            String expected = AdninMetrics.coloredTagAbbreviation(type);
            check(actual.contains(expected) && actual.indexOf(expected) == actual.lastIndexOf(expected), "Every abbreviation has one color and reset");
        }
        neutralSeparators(actual, ',');
        check(!actual.contains("First") && !actual.contains("Second"), "Tab excludes full tag reasons");
    }

    @SuppressWarnings("unchecked")
    private static void botPartyPolicy() throws Exception {
        Method apply = AdninFeatures.class.getDeclaredMethod("applyBotResult", String[].class, long.class, Minecraft.class);
        apply.setAccessible(true);
        Field epoch = field("botGeneration");
        Set<String> present = (Set<String>) field("present").get(null);
        Set<String> announced = (Set<String>) field("announced").get(null);
        Map<String, String> profiles = (Map<String, String>) field("botProfiles").get(null);
        Map<String, Long> requested = (Map<String, Long>) field("requested").get(null);
        Set<String> oldPresent = new HashSet<String>(present), oldAnnounced = new HashSet<String>(announced);
        Map<String, String> oldProfiles = new HashMap<String, String>(profiles);
        Map<String, Long> oldRequested = new HashMap<String, Long>(requested);
        int oldEpoch = epoch.getInt(null);
        boolean oldOutput = AdninGui4.chatOutputDenick;
        AdninFeatures.setGameActive(true);
        try {
            epoch.setInt(null, 31);
            AdninGui4.chatOutputDenick = true;
            for (String[] failure : new String[][]{
                    result("", "", ""), result("request-failed", "", ""), result("profile-unavailable", "RealPlayer", ""),
                    result("request-failed", "RealPlayer", UUID4), result("", "Invalid Name", UUID4),
                    result("", "RealPlayer", null), result("", "RealPlayer", "not-a-uuid"),
                    result("", "RealPlayer", UUID4.replace("-", "")),
                    result("", "RealPlayer", "12345678-1234-3234-8234-123456789abc"),
                    result("", "RealPlayer", "12345678-1234-4234-7234-123456789abc")}) {
                reset(present, announced, profiles, requested);
                invoke(apply, failure, NOW);
                check(AdninFeatures.pollPartyCommand(NOW) == null, "Unverified, empty and failed Bot outcomes stay out of party chat");
                check(profiles.isEmpty(), "Unverified outcomes cannot populate native identity cache");
                check(!announced.isEmpty(), "Local-only result still passes the announcement branch");
            }
            reset(present, announced, profiles, requested);
            invoke(apply, result("profile-unavailable", "RealPlayer", ""), NOW);
            check(requested.containsKey("bot:unitnick"), "Unavailable profile receives a retry stamp");
            invoke(apply, result("", "RealPlayer", UUID4.toUpperCase(java.util.Locale.ROOT)), NOW + 1);
            eq("/pc Bot Denicker UnitNick → RealPlayer", AdninFeatures.pollPartyCommand(NOW + 1), "Later verified success is not suppressed by earlier unverified result");
            eq("RealPlayer|" + UUID4, profiles.get("unitnick"), "Verified UUID is normalized for native handoff");
            invoke(apply, result("", "RealPlayer", UUID4), NOW + 31000);
            check(AdninFeatures.pollPartyCommand(NOW + 31000) == null, "Repeated success is deduplicated beyond queue dedup interval");
            reset(present, announced, profiles, requested);
            invoke(apply, result("", "", ""), NOW);
            invoke(apply, result("", "RealPlayer", UUID4), NOW + 1);
            check(AdninFeatures.pollPartyCommand(NOW + 1) != null, "No-results followed by success can emit once");
            check(AdninFeatures.pollPartyCommand(NOW + 1) == null, "Success enqueues only one message");
            reset(present, announced, profiles, requested);
            AdninGui4.chatOutputDenick = false;
            invoke(apply, result("", "RealPlayer", UUID4), NOW);
            check(AdninFeatures.pollPartyCommand(NOW) == null && profiles.containsKey("unitnick"), "Output disabled preserves verified identity locally without party output");
            AdninGui4.chatOutputDenick = true;
            reset(present, announced, profiles, requested);
            String[] stale = result("", "RealPlayer", UUID4); stale[0] = "30";
            invoke(apply, stale, NOW);
            check(announced.isEmpty() && profiles.isEmpty() && AdninFeatures.pollPartyCommand(NOW) == null, "Stale settings epoch is ignored");
            present.clear();
            invoke(apply, result("", "RealPlayer", UUID4), NOW);
            check(announced.isEmpty() && profiles.containsKey("unitnick")
                    && AdninFeatures.pollPartyCommand(NOW) == null,
                    "Departed player result is cached without entering party output");
            for (String[] malformed : new String[][]{null, new String[0], new String[]{"31", "bot"}}) invoke(apply, malformed, NOW);
            check(AdninFeatures.pollPartyCommand(NOW) == null, "Malformed callback arrays are ignored");
        } finally {
            present.clear(); present.addAll(oldPresent); announced.clear(); announced.addAll(oldAnnounced);
            profiles.clear(); profiles.putAll(oldProfiles); requested.clear(); requested.putAll(oldRequested);
            epoch.setInt(null, oldEpoch); AdninGui4.chatOutputDenick = oldOutput; AdninFeatures.clearPartyQueue();
        }
    }

    private static final class FixturePlayer extends NetworkPlayerInfo {
        private final ScorePlayerTeam team;
        private final IChatComponent display;
        FixturePlayer(ScorePlayerTeam team, IChatComponent display) {
            super(new GameProfile(UUID.fromString(UUID4), NAME));
            this.team = team; this.display = display;
        }
        @Override public ScorePlayerTeam getPlayerTeam() { return team; }
        @Override public IChatComponent getDisplayName() { return display; }
    }

    private static void reset(Set<String> present, Set<String> announced, Map<String, String> profiles, Map<String, Long> requested) {
        present.clear(); present.add("unitnick"); announced.clear(); profiles.clear(); requested.clear(); AdninFeatures.clearPartyQueue();
    }
    private static String[] result(String error, String realName, String uuid) { return new String[]{"31", "bot", "UnitNick", error, "UnitNick", "", realName, uuid}; }
    private static void invoke(Method method, String[] result, long now) throws Exception { method.invoke(null, (Object) result, now, null); }
    private static Field field(String name) throws Exception { Field value = AdninFeatures.class.getDeclaredField(name); value.setAccessible(true); return value; }
    private static void nameColor(String name, String formatted, String colors, String why) { renderedName(name, AdninFeatures.coloredPlayerName(name, formatted), colors, why); }
    private static void renderedName(String name, String text, String colors, String why) {
        eq(name, plain(text), why + ": only the roster name is visible");
        eq(colors, colorSequence(text), why + ": character colors match");
        check(text.endsWith("\u00a7r") && !text.matches("(?s).*[\\u00a7][k-oK-O].*"), why + ": reset and no unrelated styles");
    }
    private static void wordColor(String text, String word, char color, String why) {
        int start = plain(text).indexOf(word);
        check(start >= 0, why + ": visible text exists");
        eq(repeat(color, word.length()), colorSequence(text).substring(start, start + word.length()), why);
    }
    private static void tagLabelAndReason(String text, String label, String reason, char color) {
        wordColor(text, label, color, "Tag label has its assigned color");
        wordColor(text, ": " + reason, 'f', "Tag colon and entire reason are white without preceding-label color bleed");
    }
    private static void neutralSeparators(String text, char separator) {
        String visible = plain(text), colors = colorSequence(text);
        for (int i = 0; i < visible.length(); i++) if (visible.charAt(i) == separator)
            check(colors.charAt(i) == '7' || colors.charAt(i) == 'f', "Separators stay neutral instead of inheriting tag/name colors");
    }
    private static String colorSequence(String text) {
        StringBuilder result = new StringBuilder(); char color = 'f';
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(++i));
                if ("0123456789abcdef".indexOf(code) >= 0) color = code;
                else if (code == 'r') color = 'f';
            } else result.append(color);
        }
        return result.toString();
    }
    private static String plain(String text) { return text.replaceAll("(?i)\\u00a7[0-9a-fk-or]", ""); }
    private static String repeat(char value, int count) { char[] text = new char[count]; Arrays.fill(text, value); return new String(text); }
    private static void eq(String expected, String actual, String why) { check(expected.equals(actual), why); }
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
