import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdninMetricsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        eq(50.0, AdninMetrics.ratio(10000, 200));
        eq(80.0, AdninMetrics.ratio(100, 1.25));
        eq(39.92, AdninMetrics.ratio(499, 12.5));
        eq(0.0, AdninMetrics.ratio(0, 10));
        eq(0.0, AdninMetrics.ratio(-0.0, 10));
        check(Double.isNaN(AdninMetrics.ratio(10, 0)), "zero level is missing");
        check(Double.isNaN(AdninMetrics.ratio(10, -0.0)), "negative zero level is missing");
        check(Double.isNaN(AdninMetrics.ratio(-1, 10)), "negative kills invalid");
        check(Double.isNaN(AdninMetrics.ratio(10, -1)), "negative level invalid");
        check(Double.isNaN(AdninMetrics.ratio(Double.NaN, 10)), "missing kills invalid");
        check(Double.isNaN(AdninMetrics.ratio(10, Double.NaN)), "missing level invalid");
        check(Double.isNaN(AdninMetrics.ratio(Double.POSITIVE_INFINITY, 10)), "infinite kills invalid");
        check(Double.isNaN(AdninMetrics.ratio(10, Double.POSITIVE_INFINITY)), "infinite level invalid");
        check(Double.isNaN(AdninMetrics.ratio(Double.MAX_VALUE, Double.MIN_VALUE)), "overflow ratio invalid");

        // Native Bedwars stars are formatted display cells, not raw doubles.
        // These reproduce the values reported in the screenshot and the exact
        // century100/200 formatting branches from FUN_180025fe0.
        eq("24.16", AdninMetrics.format(AdninMetrics.ratioFromCells("4517", "\u00a7f[187\u272b]")));
        eq("5.41", AdninMetrics.format(AdninMetrics.ratioFromCells("92", "\u00a77[17\u272b]")));
        eq("69.94", AdninMetrics.format(AdninMetrics.ratioFromCells("14338", "\u00a76[205\u272b]")));
        eq("0.00", AdninMetrics.format(AdninMetrics.ratioFromCells("0", "\u00a77[1\u272b]")));
        eq(0x55FF55, AdninMetrics.color(AdninMetrics.ratioFromCells("4517", "\u00a7f[187\u272b]")));
        eq(0xAA0000, AdninMetrics.color(AdninMetrics.ratioFromCells("14338", "\u00a76[205\u272b]")));
        eq(123.456, AdninMetrics.ratioFromCells("123456", "\u00a7c[\u00a761\u00a7e0\u00a7a0\u00a7b0\u00a7d\u272b\u00a75]"));
        eq(4517.0 / 187.0, AdninMetrics.ratioFromCells("\u00a7a4517\u00a7r", "\u00a7F\u00a7L[1\u00a7e8\u00a7a7\u272b]\u00a7R"));
        for (char glyph : new char[]{'\u272b', '\u272a', '\u269d', '\u2725'}) {
            eq(10.0, AdninMetrics.ratioFromCells("12340", "[1234" + glyph + "]"));
        }
        eq(1.0, AdninMetrics.ratioFromCells("2147483647", "[2147483647\u272b]"));
        eq(80.0, AdninMetrics.ratioFromCells("100", "1.25"));
        eq(39.92, AdninMetrics.ratioFromCells("499", "12.5"));
        eq(50.0, AdninMetrics.ratioFromCells("10000", "200"));
        eq(25.0, AdninMetrics.ratioFromCells("000050", "0002"));
        for (String invalid : new String[]{null, "", " ", "--", "4,517", "4 517", "4517 finals", "4.5", "-1", "+1",
                "1e3", "0x10", "[4517]", "4517/2", "\u00a7", "\u00a7z4517", "\u00a7\n4517", "\u00a7\u212a4517", "\uff14\uff15\uff11\uff17", "4517\u0000"}) {
            eq("--", AdninMetrics.format(AdninMetrics.ratioFromCells(invalid, "\u00a7f[187\u272b]")));
        }
        for (String invalid : new String[]{null, "", " ", "--", "0", "0.0", "[0\u272b]", "-1", "+1", "NaN", "Infinity",
                "1e3", "0x10", ".5", "1.", "1,000", "187 stars", "187/2", "[187]", "[187*]", "[187\u2606]",
                "[187\u2605]", "[1.25\u272b]", "[1 87\u272b]", "[187\u272b]junk", "prefix[187\u272b]", "[187\u272b][2\u272b]",
                "[[187\u272b]]", "[187\u272b", "187\u272b]", "\u00a7", "\u00a7r", "\u00a7x[187\u272b]", "[18\u00a7z7\u272b]",
                "[187\u272b]\u00a7", " [187\u272b]", "[187\u272b]\n", "[187\u272b]\u0000"}) {
            eq("--", AdninMetrics.format(AdninMetrics.ratioFromCells("4517", invalid)));
        }
        eq("--", AdninMetrics.format(AdninMetrics.ratioFromCells(repeat('9', 400), "1")));
        eq("--", AdninMetrics.format(AdninMetrics.ratioFromCells("1", repeat('9', 400))));

        double[] thresholds = {0, 15, 20, 25, 30, 40, 50, 60, 70, 80};
        int[] rgb = {0xAAAAAA, 0xFFFFFF, 0x55FF55, 0x00AA00, 0xFFFF55, 0xFFAA00, 0xFF5555, 0xAA0000, 0xFF55FF, 0xAA00AA};
        for (int i = 0; i < thresholds.length; i++) {
            eq(rgb[i], AdninMetrics.color(thresholds[i]));
            eq(rgb[i], AdninMetrics.color(Math.nextUp(thresholds[i])));
            if (i > 0) eq(rgb[i - 1], AdninMetrics.color(Math.nextDown(thresholds[i])));
        }
        eq(0xAAAAAA, AdninMetrics.color(Double.NaN));
        eq(0xAAAAAA, AdninMetrics.color(Double.POSITIVE_INFINITY));
        eq(0xAAAAAA, AdninMetrics.color(-1));
        eq(0xAA00AA, AdninMetrics.color(99999));
        eq("80.00", AdninMetrics.format(79.999));
        eq(0xFF55FF, AdninMetrics.color(79.999));
        eq("15.00", AdninMetrics.format(14.999));
        eq(0xAAAAAA, AdninMetrics.color(14.999));
        eq("39.92", AdninMetrics.format(AdninMetrics.ratio(499, 12.5)));
        eq("0.00", AdninMetrics.format(0));
        eq("0.00", AdninMetrics.format(-0.0));
        eq("1.01", AdninMetrics.format(1.005));
        eq("--", AdninMetrics.format(AdninMetrics.ratio(100, 0)));
        eq("--", AdninMetrics.format(Double.NEGATIVE_INFINITY));
        eq("--", AdninMetrics.format(-1));
        Locale previous = Locale.getDefault();
        try { Locale.setDefault(Locale.GERMANY); eq("12.50", AdninMetrics.format(12.5)); }
        finally { Locale.setDefault(previous); }

        eq("BC", AdninMetrics.tagAbbreviation("blatant_cheater"));
        eq("CC", AdninMetrics.tagAbbreviation("closet_cheater"));
        eq("S", AdninMetrics.tagAbbreviation("sniper"));
        eq("BC", AdninMetrics.tagAbbreviation("BLATANT_CHEATER"));
        eq("CC", AdninMetrics.tagAbbreviation("ClOsEt_ChEaTeR"));
        eq("S", AdninMetrics.tagAbbreviation("SNIPER"));
        eq("Confirmed", AdninMetrics.tagAbbreviation("confirmed_cheater"));
        eq("unknown_tag", AdninMetrics.tagAbbreviation("unknown_tag"));
        eq("", AdninMetrics.tagAbbreviation(""));
        eq("", AdninMetrics.tagAbbreviation(null));
        tagPresentations();

        if (args.length > 0) {
            Path gui = Paths.get(args[0]).resolve("AdninGui4.java");
            String source = new String(Files.readAllBytes(gui), StandardCharsets.UTF_8);
            check(source.contains("MAX_OVERLAY_COLUMNS = 20"), "array capacity includes FK/LV");
            check(array(source, "BW_COLUMN_IDS").contains("\"fklv\""), "Bedwars offers FK/LV");
            check(array(source, "BW_COLUMN_LABELS").contains("\"FK/LV\""), "Bedwars label");
            for (String mode : new String[]{"BW", "SW", "DUEL", "BWD"}) {
                String labels = array(source, mode + "_COLUMN_LABELS");
                check(labels.contains("\"Urchin\""), mode + " offers Urchin label");
                check(!labels.contains("\"Urchin Tag\""), mode + " omits old Urchin Tag label");
            }
            check(!array(source, "SW_COLUMN_IDS").contains("fklv"), "SkyWars unaffected");
            check(!array(source, "DUEL_COLUMN_IDS").contains("fklv"), "Duel unaffected");
            check(!array(source, "BWD_COLUMN_IDS").contains("fklv"), "Bedwars Duels unaffected");
            check(source.contains("public static boolean isFkLvColumnEnabled()"), "column visibility API");
        }
        System.out.println("AdninMetricsTest: " + checks + " checks passed; native prestige-cell parsing, FK/level rules and Urchin labels, aliases, per-tag colors, full reasons and safe resets");
    }

    private static void tagPresentations() {
        String[] types = {"sniper", "legit_sniper", "possible_sniper", "confirmed_cheater", "comfirmed_cheater",
                "blatant_cheater", "closet_cheater", "caution", "account", "info"};
        String[] labels = {"S", "LS", "PS", "Confirmed", "Confirmed", "BC", "CC", "caution", "account", "info"};
        int[] colors = {0xFF5555, 0xFF5555, 0xFF5555, 0xAA00AA, 0xAA00AA,
                0xFFAA00, 0xFFAA00, 0xFFAA00, 0xFFAA00, 0xAAAAAA};
        String[] codes = {"\u00a7c", "\u00a7c", "\u00a7c", "\u00a75", "\u00a75",
                "\u00a76", "\u00a76", "\u00a76", "\u00a76", "\u00a77"};
        String reason = "Exact reason: keep every word, /pc token, punctuation and \ud83d\ude00.";
        for (int i = 0; i < types.length; i++) {
            eq(labels[i], AdninMetrics.tagAbbreviation(types[i]));
            eq(colors[i], AdninMetrics.tagColor(types[i]));
            eq(codes[i], AdninMetrics.tagColorCode(types[i]));
            eq(codes[i] + labels[i] + "\u00a7r", AdninMetrics.coloredTagAbbreviation(types[i]));
            String full = i >= 1 && i <= 4 ? labels[i] : types[i];
            eq(codes[i] + full + "\u00a7f: " + reason + "\u00a7r", AdninMetrics.coloredTagText(types[i] + ": " + reason));
            for (String variant : new String[]{types[i].toUpperCase(Locale.ROOT), types[i].replace('_', ' '),
                    types[i].replace('_', '-'), " \t" + types[i].toUpperCase(Locale.ROOT).replace("_", "__-- \t") + "__ ",
                    types[i].replace("_", "\u00a0")}) {
                eq(colors[i], AdninMetrics.tagColor(variant));
                eq(codes[i], AdninMetrics.tagColorCode(variant));
                if (i <= 6) eq(labels[i], AdninMetrics.tagAbbreviation(variant));
            }
        }
        eq("\u00a75Confirmed\u00a7f: The full reason\u00a7r", AdninMetrics.coloredTagText(" COMFIRMED - CHEATER : The full reason "));
        eq("\u00a7cLS\u00a7f: The full reason\u00a7r", AdninMetrics.coloredTagText("LEGIT SNIPER: The full reason"));
        eq("\u00a7cPS\u00a7f: The full reason\u00a7r", AdninMetrics.coloredTagText("possible-sniper: The full reason"));
        eq("\u00a75Confirmed\u00a7r", AdninMetrics.coloredTagText("confirmed-cheater"));
        eq("\u00a76BLATANT-CHEATER\u00a7f: Original full type\u00a7r", AdninMetrics.coloredTagText("BLATANT-CHEATER: Original full type"));
        eq("\u00a76closet_cheater\u00a7f: Full type remains in chat\u00a7r", AdninMetrics.coloredTagText("closet_cheater: Full type remains in chat"));
        eq("\u00a7csniper\u00a7f: Full type remains in chat\u00a7r", AdninMetrics.coloredTagText("sniper: Full type remains in chat"));
        String longReason = repeat('r', 4096) + ": trailing evidence";
        eq("\u00a75Confirmed\u00a7f: " + longReason + "\u00a7r", AdninMetrics.coloredTagText("confirmed_cheater: " + longReason));
        eq("\u00a76caution\u00a7f: unrelated type text\u00a7r", AdninMetrics.coloredTagText("caution: unrelated type text"));
        eq("\u00a76closet_cheater\u00a7f:\u00a7r", AdninMetrics.coloredTagText("closet_cheater:"));
        eq("\u00a7cPS\u00a7r", AdninMetrics.coloredTagText("possible_sniper"));
        String multiple = AdninMetrics.coloredTagText("closet_cheater: first reason") + ", "
                + AdninMetrics.coloredTagText("comfirmed_cheater: second: complete reason");
        eq("\u00a76closet_cheater\u00a7f: first reason\u00a7r, \u00a75Confirmed\u00a7f: second: complete reason\u00a7r", multiple);
        eq(6, count(multiple, '\u00a7'));

        for (String empty : new String[]{null, "", "  \t\n ", "\u00a7k\u00a7r", "\u202e\u0000"}) {
            eq("", AdninMetrics.tagAbbreviation(empty));
            eq("", AdninMetrics.coloredTagAbbreviation(empty));
            eq("", AdninMetrics.coloredTagText(empty));
            eq(0xAAAAAA, AdninMetrics.tagColor(empty));
            eq("\u00a77", AdninMetrics.tagColorCode(empty));
        }
        eq("Mixed Unknown-Tag", AdninMetrics.tagAbbreviation(" Mixed Unknown-Tag "));
        eq(0xAAAAAA, AdninMetrics.tagColor("sniperish"));
        eq(0xAAAAAA, AdninMetrics.tagColor("not_confirmed_cheater"));
        eq("\u00a77Mixed Unknown-Tag\u00a7r", AdninMetrics.coloredTagAbbreviation(" Mixed Unknown-Tag "));
        eq("\u00a77unlisted\u00a7f: confirmed_cheater and sniper are in the reason\u00a7r",
                AdninMetrics.coloredTagText("unlisted: confirmed_cheater and sniper are in the reason"));
        eq("\u00a77mystery_tag next\u00a7r", AdninMetrics.coloredTagAbbreviation("mystery\u00a7k_tag\nnext\u202e\u0000"));
        String safe = AdninMetrics.coloredTagText("sniper: \u00a7khidden \u00a74danger\u202e\nnext");
        eq("\u00a7csniper\u00a7f: hidden danger next\u00a7r", safe);
        eq(3, count(safe, '\u00a7'));
        eq("\u00a77info\u00a7f: \ud83d\ude00 safe\u00a7r", AdninMetrics.coloredTagText("info: \ud83d\ude00 safe\ud800"));
        eq("\u00a7cLS\u00a7f: first: second: third\u00a7r", AdninMetrics.coloredTagText("legit_sniper: first: second: third"));
        eq("\u00a7cPS\u00a7f: no truncation or abbreviation of reason\u00a7r",
                AdninMetrics.coloredTagText("possible_sniper: no truncation or abbreviation of reason"));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            eq("LS", AdninMetrics.tagAbbreviation("LEGIT SNIPER"));
            eq("Confirmed", AdninMetrics.tagAbbreviation("COMFIRMED_CHEATER"));
            eq("\u00a77", AdninMetrics.tagColorCode("INFO"));
        } finally { Locale.setDefault(previous); }
    }

    private static int count(String value, char target) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == target) count++;
        return count;
    }

    private static String array(String source, String field) {
        Matcher match = Pattern.compile("\\b" + field + "\\s*=\\s*new String\\[\\]\\s*\\{([^}]*)\\}").matcher(source);
        if (!match.find()) throw new AssertionError("Missing column array " + field);
        return match.group(1);
    }
    private static String repeat(char c, int count) {
        StringBuilder value = new StringBuilder(count);
        while (count-- > 0) value.append(c);
        return value.toString();
    }
    private static void eq(double expected, double actual) { check(Math.abs(expected - actual) <= 1e-12, "ratio differs"); }
    private static void eq(int expected, int actual) { check(expected == actual, "color differs: expected " + Integer.toHexString(expected) + " actual " + Integer.toHexString(actual)); }
    private static void eq(String expected, String actual) { check(expected.equals(actual), "format differs: expected " + expected + " actual " + actual); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
