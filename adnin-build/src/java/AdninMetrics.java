import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure presentation math for cached native Bedwars statistics. No networking. */
public final class AdninMetrics {
    private static final Pattern INTEGER_CELL = Pattern.compile("[0-9]+");
    private static final Pattern PLAIN_LEVEL_CELL = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    // FUN_180025fe0 uses exactly these four UTF-8 prestige glyphs. Its color
    // codes may appear between individual digits; retain their original order.
    private static final Pattern NATIVE_STARS_CELL = Pattern.compile("\\[([0-9]+)[\u272b\u272a\u269d\u2725]\\]");

    private AdninMetrics() { }

    /** Parse native display cells without removing arbitrary nonnumeric text. */
    public static double ratioFromCells(String finalKills, String stars) {
        String kills = stripMinecraftFormatting(finalKills);
        String level = stripMinecraftFormatting(stars);
        if (kills == null || level == null || !INTEGER_CELL.matcher(kills).matches()) return Double.NaN;
        Matcher nativeStars = NATIVE_STARS_CELL.matcher(level);
        if (nativeStars.matches()) level = nativeStars.group(1);
        else if (!PLAIN_LEVEL_CELL.matcher(level).matches()) return Double.NaN;
        try {
            return ratio(Double.parseDouble(kills), Double.parseDouble(level));
        } catch (NumberFormatException invalid) {
            return Double.NaN;
        }
    }

    private static String stripMinecraftFormatting(String value) {
        if (value == null || value.length() > 256) return null;
        if (value.indexOf('\u00a7') < 0) return value;
        StringBuilder plain = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\u00a7') {
                plain.append(c);
                continue;
            }
            if (++i == value.length()) return null;
            char code = value.charAt(i);
            if (code >= 'A' && code <= 'Z') code = (char) (code + ('a' - 'A'));
            if (!((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
                    || (code >= 'k' && code <= 'o') || code == 'r')) return null;
        }
        return plain.toString();
    }

    /** Final kills divided by the untruncated Bedwars level, not final deaths. */
    public static double ratio(double finalKills, double bedwarsLevel) {
        if (!finite(finalKills) || !finite(bedwarsLevel) || finalKills < 0.0 || bedwarsLevel <= 0.0) {
            return Double.NaN;
        }
        double value = finalKills / bedwarsLevel;
        if (!finite(value)) return Double.NaN;
        return value == 0.0 ? 0.0 : value;
    }

    /**
     * RGB values sampled from the supplied FK/LV rule screenshot, top to bottom:
     * AA00AA, FF55FF, AA0000, FF5555, FFAA00, FFFF55, 00AA00, 55FF55, FFFFFF, AAAAAA.
     * Select by the actual ratio before any display rounding.
     */
    public static int color(double value) {
        if (!finite(value) || value < 0.0) return 0xAAAAAA;
        if (value >= 80.0) return 0xAA00AA;
        if (value >= 70.0) return 0xFF55FF;
        if (value >= 60.0) return 0xAA0000;
        if (value >= 50.0) return 0xFF5555;
        if (value >= 40.0) return 0xFFAA00;
        if (value >= 30.0) return 0xFFFF55;
        if (value >= 25.0) return 0x00AA00;
        if (value >= 20.0) return 0x55FF55;
        if (value >= 15.0) return 0xFFFFFF;
        return 0xAAAAAA;
    }

    /** Fixed two decimal places, independent of locale. Missing/invalid is --. */
    public static String format(double value) {
        if (!finite(value) || value < 0.0) return "--";
        if (value == 0.0) return "0.00";
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Compact Tab labels; unknown types retain their safe, readable text. */
    public static String tagAbbreviation(String type) {
        switch (tagKey(type)) {
            case "blatant_cheater": return "BC";
            case "closet_cheater": return "CC";
            case "sniper": return "S";
            case "confirmed_cheater": return "Confirmed";
            case "legit_sniper": return "LS";
            case "possible_sniper": return "PS";
            default: return safeTagText(type).trim();
        }
    }

    /**
     * Color families follow the supplied official icon screenshot. These are
     * Minecraft 1.8.9's readable legacy-palette counterparts, not arbitrary RGB
     * text colors: red sniper family, purple confirmed, gold warnings, gray info.
     */
    public static int tagColor(String type) {
        switch (tagKey(type)) {
            case "sniper": case "legit_sniper": case "possible_sniper": return 0xFF5555;
            case "confirmed_cheater": return 0xAA00AA;
            case "blatant_cheater": case "closet_cheater": case "caution": case "account": return 0xFFAA00;
            default: return 0xAAAAAA;
        }
    }

    /** Fixed formatting prefix matching tagColor; never returns upstream text. */
    public static String tagColorCode(String type) {
        switch (tagColor(type)) {
            case 0xFF5555: return "\u00a7c";
            case 0xAA00AA: return "\u00a75";
            case 0xFFAA00: return "\u00a76";
            default: return "\u00a77";
        }
    }

    /** Each Tab label owns its color and reset; empty input adds no format codes. */
    public static String coloredTagAbbreviation(String type) {
        String label = tagAbbreviation(type);
        return label.isEmpty() ? "" : tagColorCode(type) + label + "\u00a7r";
    }

    /**
     * Only the local-chat type is colored; its colon and complete reason are
     * explicitly white. Three requested display labels replace full types;
     * BC/CC/S remain full in chat. Colons inside reasons remain untouched.
     */
    public static String coloredTagText(String tag) {
        String text = safeTagText(tag).trim();
        if (text.isEmpty()) return "";
        int colon = text.indexOf(':');
        String type = (colon < 0 ? text : text.substring(0, colon)).trim();
        String suffix = colon < 0 ? "" : text.substring(colon);
        String label = type;
        switch (tagKey(type)) {
            case "confirmed_cheater": label = "Confirmed"; break;
            case "legit_sniper": label = "LS"; break;
            case "possible_sniper": label = "PS"; break;
            default: break;
        }
        return tagColorCode(type) + label + (suffix.isEmpty() ? "" : "\u00a7f" + suffix) + "\u00a7r";
    }

    private static String tagKey(String type) {
        String value = safeTagText(type).trim().toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder(value.length());
        boolean separator = false;
        for (int i = 0; i < value.length();) {
            int c = value.codePointAt(i);
            i += Character.charCount(c);
            if (c == '_' || c == '-' || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                separator = key.length() > 0;
            } else {
                if (separator) key.append('_');
                key.appendCodePoint(c);
                separator = false;
            }
        }
        String normalized = key.toString();
        return "comfirmed_cheater".equals(normalized) ? "confirmed_cheater" : normalized;
    }

    /** Remove display controls without truncating or changing readable reasons. */
    private static String safeTagText(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder text = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            int c = value.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\u00a7') {
                if (i < value.length()) i += Character.charCount(value.codePointAt(i));
            } else if (Character.isWhitespace(c) && Character.isISOControl(c)) {
                text.append(' ');
            } else if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT
                    && Character.getType(c) != Character.SURROGATE) {
                text.appendCodePoint(c);
            }
        }
        return text.toString();
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
