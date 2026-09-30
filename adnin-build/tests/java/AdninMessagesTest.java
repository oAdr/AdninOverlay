import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Fixed templates and opaque component data only; no game or personal settings. */
public final class AdninMessagesTest {
    private static int checks;
    private static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    private static String plain(String value) { return value.replaceAll("(?i)\\u00a7[0-9a-fk-or]", ""); }
    private static void eq(Object expected, Object actual, String reason) {
        check(expected == null ? actual == null : expected.equals(actual), reason + ": " + actual);
    }
    public static void main(String[] args) {
        String nick = "\u00a77[\u00a71Adnin\u00a77] \u00a7bWins\u00a77 is \u00a7cnicked\u00a7r";
        String stats = "\u00a77[\u00a71Adnin\u00a77] \u00a7b[MVP+] Wins\u00a77 - Wins: \u00a7a100\u00a77 WLR: 2.00 KDR: 3.25";
        String apiData = "CHEATER (4 days ago) Reason: Wins: 42 is nicked; API text 玩家 /wdr raw";
        String seraph = "\u00a7d[Seraph]\u00a7r \u00a7bUnitPlayer\u00a77 is blacklisted for \u00a7c" + apiData;
        try {
            AdninLanguage.setLanguage("en");
            for (String message : new String[]{nick, stats, seraph})
                eq(null, AdninMessages.translateGenerated(message, false, message == seraph ? 1 : 0), "English follows original native renderer");
            eq(0, AdninMessages.renderGenerated(nick, false, 0), "English render fallback does not initialize game");
            for (String locale : new String[]{"zh_CN", "zh_TW"}) {
                AdninLanguage.setLanguage(locale);
                String translated = AdninMessages.translateGenerated(nick, false, 0);
                eq("[Adnin] Wins" + AdninLanguage.text(" is ") + AdninLanguage.text("nicked"), plain(translated), "Only fixed Nick suffix changes, including a player named Wins");
                eq(formatting(nick), formatting(translated), "Nick colors survive replacement");
                translated = AdninMessages.translateGenerated(stats, false, 0);
                eq("[Adnin] [MVP+] Wins - " + AdninLanguage.text("Wins") + ": 100 WLR: 2.00 KDR: 3.25", plain(translated), "Stat labels change, names and ratios stay exact");
                eq(formatting(stats), formatting(translated), "Statistics color sequence stays exact");
                String duels = "[Adnin] UnitPlayer - BW-Duels: 5.50 WLR: 2.25";
                eq("[Adnin] UnitPlayer - " + AdninLanguage.text("BW-Duels") + ": 5.50 WLR: 2.25", AdninMessages.translateGenerated(duels, false, 0), "Mode label changes only");
                translated = AdninMessages.translateGenerated(seraph, false, 1);
                eq("[Seraph] UnitPlayer" + AdninLanguage.text(" is blacklisted for ") + apiData, plain(translated), "API reason content is untouched even when it resembles our labels");
                eq(formatting(seraph), formatting(translated), "Seraph colors preserved");
                eq(null, AdninMessages.translateGenerated(seraph, false, 0), "Producer category is authoritative");
                eq(null, AdninMessages.translateGenerated(nick, false, 1), "Tag category never reinterprets player messages");
                for (String key : new String[]{"Invalid Hypixel API key", "Invalid Seraph API key"}) {
                    String source = "[Adnin] \u00a7c" + key + "\u00a77. Check your key in settings.";
                    eq("[Adnin] " + AdninLanguage.text(key) + AdninLanguage.text(". Check your key in settings."),
                        plain(AdninMessages.translateGenerated(source, false, 0)), "Exact native API status template");
                    eq(AdninMessages.translateGenerated(source, false, 0), AdninMessages.translateGenerated(source, false, 4),
                        "Local-only producer category translates the same fixed error");
                }
                for (String prefix : new String[]{"Fetching stats for ", "Unable to fetch stats for: "})
                    eq("[Adnin] " + AdninLanguage.text(prefix) + "Wins_42...",
                        AdninMessages.translateGenerated("[Adnin] " + prefix + "Wins_42...", false, 0), "Request status preserves account token");
                jsonComponents(apiData);
                sessionRows();
                fallbackCases(nick);
                eq(null, AdninMessages.translateGenerated(nick, false, 4), "Local-only API producer rejects Nick template");
                eq(null, AdninMessages.translateGenerated("[Adnin] Fetching stats for Player", false, 4), "Local-only API producer allows only exact key errors");
            }
        } finally { AdninLanguage.setLanguage("en"); }
        System.out.println("AdninMessagesTest: " + checks + " checks passed; template/JSON/session fixtures only");
    }

    private static void jsonComponents(String apiData) {
        JsonObject source = new JsonParser().parse("{\"text\":\"\",\"color\":\"gray\",\"extra\":["
            + "{\"text\":\"[Adnin] \",\"bold\":true},"
            + "{\"text\":\"Wins\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/wdr Wins\"},"
            + "\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"Wins is nicked API reason\"}},"
            + "{\"text\":\" is ni\",\"extra\":[{\"text\":\"cked\",\"color\":\"red\"}]}]}").getAsJsonObject();
        String result = AdninMessages.translateGenerated(source.toString(), true, 0);
        check(result != null, "Split labels in nested JSON components translate");
        JsonObject actual = new JsonParser().parse(result).getAsJsonObject();
        JsonObject originalPlayer = source.getAsJsonArray("extra").get(1).getAsJsonObject();
        JsonObject actualPlayer = actual.getAsJsonArray("extra").get(1).getAsJsonObject();
        eq(originalPlayer, actualPlayer, "Exact player, click target, hover text and component structure preserved");
        eq(source.get("color"), actual.get("color"), "Root style preserved");
        eq(source.getAsJsonArray("extra").get(0), actual.getAsJsonArray("extra").get(0), "Brand component unchanged");
        eq("[Adnin] Wins" + AdninLanguage.text(" is ") + AdninLanguage.text("nicked"), visible(actual), "Split nested text remains in visual order");
        eq("red", actual.getAsJsonArray("extra").get(2).getAsJsonObject().getAsJsonArray("extra").get(0).getAsJsonObject().get("color").getAsString(), "Trailing component style remains attached");

        JsonObject opaque = new JsonObject(); opaque.addProperty("text", "[Seraph] UnitPlayer is blacklisted for " + apiData);
        JsonObject hover = new JsonObject(); hover.addProperty("action", "show_text"); hover.addProperty("value", "[Adnin] Wins is nicked");
        opaque.add("hoverEvent", hover);
        JsonObject changed = new JsonParser().parse(AdninMessages.translateGenerated(opaque.toString(), true, 1)).getAsJsonObject();
        eq(hover, changed.get("hoverEvent"), "Hover strings that mimic owned messages remain opaque");
        check(changed.get("text").getAsString().endsWith(apiData), "Tag reason is byte-for-byte identical after JSON roundtrip");
        eq(null, AdninMessages.translateGenerated("{\"text\":\"[Seraph] UnitPlayer [Wins]\",\"hoverEvent\":{\"action\":\"show_text\",\"value\":\"nicked\"}}", true, 1), "Compact tag has no owned natural-language template to translate");
    }

    private static void sessionRows() {
        for (String label : new String[]{"Finals", "Beds", "Kills", "Wins", "Winstreak", "Session Games", "Slumber Tickets", "Game Time", "Avg Time", "Session Time"}) {
            String row = "\u00a77" + label + ": \u00a7a12\u00a77 / \u00a7c3\u00a77 FKDR: 4.00";
            String translated = AdninMessages.sessionRow(row);
            eq(AdninLanguage.text(label) + ": 12 / 3 FKDR: 4.00", plain(translated), "Session row keeps its original values");
            eq(formatting(row), formatting(translated), "Session row formatting preserved");
        }
        for (String label : new String[]{"Session", "Game", "Session Stats"})
            eq(AdninLanguage.text(label) + "  01:25", plain(AdninMessages.sessionRow("\u00a7l" + label + "  01:25")), "Session header template");
        eq("WinstreakPlayer: 12", AdninMessages.sessionRow("WinstreakPlayer: 12"), "Unknown session labels unchanged");
        eq("FKDR: 10", AdninMessages.sessionRow("FKDR: 10"), "Ratio abbreviation preserved");
        eq(null, AdninMessages.sessionRow(null), "Null rows preserved");
    }

    private static void fallbackCases(String nick) {
        for (String input : new String[]{null, "", "Player: [Adnin] Wins is nicked", "[Adnin] Player says is nicked today",
                "[Adnin] Wins - Wins: API_VALUE WLR: 2.0 KDR: 1.0", "[Urchin] Wins is nicked",
                "[Adnin] Invalid Hypixel API key. Account: opaque-key", "[Adnin] Fetching stats for /msg secret"})
            eq(null, AdninMessages.translateGenerated(input, false, 0), "Unrecognized message falls back untouched");
        for (int category : new int[]{-1, 2, Integer.MAX_VALUE})
            eq(null, AdninMessages.translateGenerated(nick, false, category), "Unknown category unchanged");
        for (String invalid : new String[]{"{", "[]", "{\"text\":null}", "{\"text\":4}", "{\"text\":\"[Adnin] Wins is nicked\",\"extra\":[null]}",
                "{\"text\":\"[Adnin] Wins is nicked\",\"translate\":\"chat.type.text\"}"})
            eq(null, AdninMessages.translateGenerated(invalid, true, 0), "Unsupported or malformed component falls back");
        String nested = "{\"text\":\"[Adnin] Wins is nicked\"}";
        for (int i = 0; i < 40; i++) nested = "{\"text\":\"\",\"extra\":[" + nested + "]}";
        eq(null, AdninMessages.translateGenerated(nested, true, 0), "Overdeep JSON is rejected before recursive parsing");
        eq(null, AdninMessages.translateGenerated(new String(new char[16385]), false, 0), "Oversized native message rejected");
    }

    private static String visible(JsonElement element) {
        JsonObject object = element.getAsJsonObject(); StringBuilder result = new StringBuilder();
        if (object.has("text")) result.append(object.get("text").getAsString());
        if (object.has("extra")) for (JsonElement child : object.getAsJsonArray("extra")) result.append(visible(child));
        return result.toString();
    }
    private static String formatting(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length() - 1; i++) if (text.charAt(i) == '\u00a7') out.append(text.charAt(i)).append(text.charAt(++i));
        return out.toString();
    }
}
