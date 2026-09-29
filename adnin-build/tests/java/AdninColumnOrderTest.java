import java.util.Arrays;

/** Pure order/compatibility fixtures; no game classes or native runtime. */
public final class AdninColumnOrderTest {
    private static int checks;

    public static void main(String[] args) {
        eq("name,hp", AdninColumnOrder.normalize("bedwars", null));
        eq("name,hp", AdninColumnOrder.normalize("bedwars", ""));
        eq("name,hp", AdninColumnOrder.normalize("bedwars", "name,hp,tags,unknown"));
        eq("name,hp,urchin,fkdr,fklv,stars,seraph", AdninColumnOrder.normalize("bedwars", "urchin,fkdr,fklv,stars,seraph"));
        eq("name,hp,fklv,stars,urchin,fkdr", AdninColumnOrder.normalize("bedwars", "fklv,stars,urchin,fkdr"));
        eq("name,hp,fkdr,urchin,fklv", AdninColumnOrder.normalize("bedwars", "fkdr,urchin,fklv"));
        eq("name,hp,urchin,fklv,fkdr", AdninColumnOrder.normalize("bedwars", " urchin , fklv , fkdr ,urchin,fklv"));
        eq("name,hp,urchin,fklv", AdninColumnOrder.normalize("bedwars", "hp,urchin,name,fklv,hp,name"));
        eq("name,hp,requeuePct,fklv,urchin", AdninColumnOrder.normalize("bedwars", "magicRatio,requeuePct,fklv,urchin"));
        eq("name,hp,swordKD,finalKills,bedsBroken,requeuePct", AdninColumnOrder.normalize("bedwars", "swordKD,finalKills,bedsBroken,requeuePct"));
        eq("name,hp,urchin,sw_stars,sw_kdr,sw_wlr,sw_wins,sw_kills,seraph", AdninColumnOrder.normalize("skywars", "urchin,stars,kdr,wlr,wins,kills,seraph,fklv,fkdr"));
        eq("name,hp,sw_stars,urchin,sw_kdr", AdninColumnOrder.normalize("skywars", "sw_stars,stars,urchin,kdr,sw_kdr"));
        eq("name,hp,urchin,duel_wins,duel_wlr,duel_kdr", AdninColumnOrder.normalize("duel", "urchin,wins,wlr,kdr,fklv,stars"));
        eq("name,hp,bwd_wlr,urchin,bwd_index", AdninColumnOrder.normalize("bedwarsduels", "wlr,urchin,index,bwd_wlr,fklv,stars"));
        eq("name,hp,fklv,urchin", AdninColumnOrder.normalize(null, "fklv,urchin"));
        eq("name,hp,fklv,urchin", AdninColumnOrder.normalize("unknown", "fklv,urchin"));
        eq("name,hp,fklv,urchin", AdninColumnOrder.normalize("invalid-mode", "fklv,urchin"));
        eq("bedwars", AdninColumnOrder.mode(null));
        eq("bedwars", AdninColumnOrder.mode("unknown"));
        eq("skywars", AdninColumnOrder.mode("skywars"));
        eq("duel", AdninColumnOrder.mode("duel"));
        eq("bedwarsduels", AdninColumnOrder.mode("bedwarsduels"));
        check(Arrays.equals(new String[]{"name", "hp", "urchin", "fklv", "fkdr"},
            AdninColumnOrder.enabledIds("bedwars", "urchin,fklv,fkdr")), "array preserves enabled sequence");
        String[] first = AdninColumnOrder.enabledIds("bedwars", "urchin,fklv");
        first[2] = "mutated";
        eq("name,hp,urchin,fklv", AdninColumnOrder.normalize("bedwars", "urchin,fklv"));
        for (String mode : new String[]{"bedwars", "skywars", "duel", "bedwarsduels"}) {
            String canonical = AdninColumnOrder.normalize(mode, "urchin,stars,fklv,kdr,wlr,wins,index,seraph");
            eq(canonical, AdninColumnOrder.normalize(mode, canonical));
        }
        System.out.println("AdninColumnOrderTest: " + checks + " checks passed; exact mixed-column order, mode filters, legacy aliases, duplicate IDs and independent parse results");
    }

    private static void eq(String expected, String actual) { check(expected.equals(actual), "expected " + expected + " actual " + actual); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
