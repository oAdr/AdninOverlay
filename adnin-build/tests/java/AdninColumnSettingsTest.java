import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Exercises compiled Gui4 settings and arrows without opening a Minecraft window. */
public final class AdninColumnSettingsTest {
    private static int checks;
    private static Class<?> gui;

    public static void main(String[] args) throws Exception {
        gui = Class.forName("AdninGui4");
        Object screen = gui.getDeclaredConstructor().newInstance();
        Method move = gui.getDeclaredMethod("moveOverlayColumn", int.class, int.class); move.setAccessible(true);
        Field enabled = field("overlayColumnEnabled"), order = field("overlayColumnOrder");
        set("overlayGamemodeEdit", "bedwars");
        call("loadAllOverlayColumnsFromToggles", new Class<?>[]{String.class, String.class, String.class, String.class, String.class},
            "name,hp,stars,fkdr,fklv,urchin", "name,hp,sw_stars,urchin,sw_kdr", "name,hp,duel_wlr,urchin", "name,hp,bwd_index,urchin", "");
        active("bedwars");
        eq("name,hp,stars,fkdr,fklv,urchin", config(), "loaded Bedwars order");
        int fkLv = index("BW_COLUMN_IDS", "fklv"), urchin = index("BW_COLUMN_IDS", "urchin");
        move.invoke(screen, fkLv, -1);
        eq("name,hp,stars,fklv,fkdr,urchin", config(), "up arrow changes effective live order before save");
        move.invoke(screen, urchin, -1);
        eq("name,hp,stars,fklv,urchin,fkdr", config(), "Urchin moves between native columns");
        move.invoke(screen, fkLv, 1);
        eq("name,hp,stars,urchin,fklv,fkdr", config(), "down arrow reflects exact mixed order");
        check((Boolean) call("isUrchinColumnEnabled"), "Urchin visibility shares effective getter");
        check((Boolean) call("isFkLvColumnEnabled"), "FK/LV visibility shares effective getter");
        ((boolean[]) enabled.get(null))[urchin] = false;
        eq("name,hp,stars,fklv,fkdr", config(), "live disable omits column without save");
        check(!(Boolean) call("isUrchinColumnEnabled"), "live visibility disabled");
        ((boolean[]) enabled.get(null))[urchin] = true;
        eq("name,hp,stars,urchin,fklv,fkdr", config(), "re-enable restores configured position");

        edit("skywars");
        eq("name,hp,stars,urchin,fklv,fkdr", config(), "editing another mode retains active Bedwars snapshot");
        active("skywars");
        eq("name,hp,sw_stars,urchin,sw_kdr", config(), "active SkyWars uses current SkyWars state");
        check(!(Boolean) call("isFkLvColumnEnabled"), "FK/LV unavailable outside Bedwars");
        move.invoke(screen, index("SW_COLUMN_IDS", "urchin"), -1);
        eq("name,hp,urchin,sw_stars,sw_kdr", config(), "SkyWars Urchin can move first");
        String swSaved = (String) call("getOverlayColumnsSkywars");
        eq(config(), swSaved, "save getter captures exact live SkyWars order");
        active("bedwars");
        eq("name,hp,stars,urchin,fklv,fkdr", config(), "active mode does not leak edited SkyWars order");
        edit("duel"); active("duel");
        eq("name,hp,duel_wlr,urchin", config(), "Duel stored order loaded");
        move.invoke(screen, index("DUEL_COLUMN_IDS", "urchin"), -1);
        eq("name,hp,urchin,duel_wlr", config(), "Duel arrow order");
        edit("bedwarsduels"); active("bedwarsduels");
        eq("name,hp,bwd_index,urchin", config(), "BW Duels stored order loaded");
        move.invoke(screen, index("BWD_COLUMN_IDS", "urchin"), -1);
        eq("name,hp,urchin,bwd_index", config(), "BW Duels arrow order");

        edit("bedwars"); active("unknown");
        eq("name,hp,stars,urchin,fklv,fkdr", config(), "unknown active mode uses live Bedwars state");
        String bwSaved = (String) call("getOverlayColumnsBedwars");
        String duelSaved = (String) call("getOverlayColumnsDuel");
        String bwdSaved = (String) call("getOverlayColumnsBedwarsduels");
        ((boolean[]) enabled.get(null))[fkLv] = false;
        call("loadAllOverlayColumnsFromToggles", new Class<?>[]{String.class, String.class, String.class, String.class, String.class},
            bwSaved, swSaved, duelSaved, bwdSaved, "");
        eq(bwSaved, config(), "save/load round trip restores mixed order and toggles");
        check((Boolean) call("isFkLvColumnEnabled"), "saved enabled flag restored");

        call("setOverlayColumnsFromConfig", new Class<?>[]{String.class}, "name,hp,fkdr,fkdr,urchin,fklv,urchin");
        eq("name,hp,fkdr,urchin,fklv", config(), "duplicate config IDs preserve first order");
        move.invoke(screen, urchin, -1);
        eq("name,hp,urchin,fkdr,fklv", config(), "duplicate loading leaves no rank holes for arrows");
        int[] ordered = (int[]) order.get(null);
        int[] seen = Arrays.copyOf(ordered, ((String[]) field("BW_COLUMN_IDS").get(null)).length);
        Arrays.sort(seen);
        for (int index = 0; index < seen.length; index++) check(seen[index] == index, "active ranks remain a permutation");
        for (String[] mode : new String[][]{{"bedwars", "BW_COLUMN_IDS"}, {"skywars", "SW_COLUMN_IDS"}, {"duel", "DUEL_COLUMN_IDS"}, {"bedwarsduels", "BWD_COLUMN_IDS"}}) {
            String[] ids = (String[]) field(mode[1]).get(null);
            String joined = join(ids);
            eq("name,hp," + joined, AdninColumnOrder.normalize(mode[0], joined), "pure helper matches actual menu catalog for " + mode[0]);
        }
        System.out.println("AdninColumnSettingsTest: " + checks + " checks passed; actual Gui4 live arrows, active/edit modes, visibility, persistence round trip and duplicate config recovery");
    }

    private static String config() throws Exception { return (String) call("getOverlayColumnsConfig"); }
    private static void active(String mode) throws Exception { call("setOverlayGamemodeActive", new Class<?>[]{String.class}, mode); }
    private static void edit(String mode) throws Exception { call("setOverlayGamemodeEdit", new Class<?>[]{String.class}, mode); }
    private static Object call(String name) throws Exception { return call(name, new Class<?>[0]); }
    private static Object call(String name, Class<?>[] types, Object... arguments) throws Exception { return gui.getMethod(name, types).invoke(null, arguments); }
    private static Field field(String name) throws Exception { Field value = gui.getDeclaredField(name); value.setAccessible(true); return value; }
    private static void set(String name, Object value) throws Exception { field(name).set(null, value); }
    private static int index(String field, String id) throws Exception { return Arrays.asList((String[]) field(field).get(null)).indexOf(id); }
    private static String join(String[] values) { StringBuilder result = new StringBuilder(); for (String value : values) { if (result.length() > 0) result.append(','); result.append(value); } return result.toString(); }
    private static void eq(Object expected, Object actual, String message) { check(expected.equals(actual), message + ": expected " + expected + " actual " + actual); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
