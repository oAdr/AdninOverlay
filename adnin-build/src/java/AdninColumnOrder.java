import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Pure canonical overlay column ordering shared by saved and live settings. */
public final class AdninColumnOrder {
    private static final String BEDWARS = "stars,stars_new,fkdr,wlr,kdr,swordKD,bblr,index,wins,finalKills,bedsBroken,requeuePct,winstreak,seens,session,ping,pingvar,seraph,urchin,fklv";
    private static final String SKYWARS = "sw_stars,sw_kdr,sw_wlr,sw_wins,sw_kills,seens,session,ping,pingvar,seraph,urchin";
    private static final String DUEL = "duel_wins,duel_wlr,duel_kdr,seens,session,ping,pingvar,seraph,urchin";
    private static final String BEDWARS_DUELS = "bwd_wlr,bwd_index,seens,session,ping,pingvar,seraph,urchin";

    private AdninColumnOrder() { }

    /** Unknown modes follow the existing menu's Bedwars fallback. */
    public static String mode(String value) {
        if ("skywars".equals(value) || "duel".equals(value) || "bedwarsduels".equals(value)) return value;
        return "bedwars";
    }

    /** Enabled identifiers in config order; the existing name/hp prefix stays fixed. */
    public static String[] enabledIds(String mode, String config) {
        mode = mode(mode);
        String allowed = "skywars".equals(mode) ? SKYWARS : "duel".equals(mode) ? DUEL
                : "bedwarsduels".equals(mode) ? BEDWARS_DUELS : BEDWARS;
        Set<String> valid = new HashSet<String>(Arrays.asList(allowed.split(",")));
        LinkedHashSet<String> ordered = new LinkedHashSet<String>();
        ordered.add("name"); ordered.add("hp");
        if (config != null) for (String token : config.split(",")) {
            String id = canonicalId(mode, token.trim());
            if (valid.contains(id)) ordered.add(id);
        }
        return ordered.toArray(new String[ordered.size()]);
    }

    /** Canonical CSV, preserving the relative order of every enabled supported column. */
    public static String normalize(String mode, String config) {
        StringBuilder result = new StringBuilder();
        for (String id : enabledIds(mode, config)) {
            if (result.length() > 0) result.append(',');
            result.append(id);
        }
        return result.toString();
    }

    private static String canonicalId(String mode, String id) {
        if ("magicRatio".equals(id)) return "requeuePct";
        if ("skywars".equals(mode)) {
            if ("stars".equals(id)) return "sw_stars";
            if ("kdr".equals(id)) return "sw_kdr";
            if ("wlr".equals(id)) return "sw_wlr";
            if ("wins".equals(id)) return "sw_wins";
            if ("kills".equals(id)) return "sw_kills";
        } else if ("duel".equals(mode)) {
            if ("wins".equals(id)) return "duel_wins";
            if ("wlr".equals(id)) return "duel_wlr";
            if ("kdr".equals(id)) return "duel_kdr";
        } else if ("bedwarsduels".equals(mode)) {
            if ("wlr".equals(id)) return "bwd_wlr";
            if ("index".equals(id)) return "bwd_index";
        }
        return id;
    }
}
