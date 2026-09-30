import java.lang.reflect.*;
import java.util.*;
import org.lwjgl.opengl.UiGeometryFixture;

/** Exact output/geometry checks for bounded caches and the previous clipping algorithm. */
public final class AdninUiPerformanceEquivalenceTest {
    private static int checks;
    private static final Random RANDOM = new Random(782341);
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(String name, Class<?>... args) throws Exception {
        Method method = AdninGui4.class.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void equal(String expected, String actual, String message) {
        check(Objects.equals(expected, actual), message + ": expected=" + expected + " actual=" + actual);
    }

    private static String referenceColumns() throws Exception {
        String[] ids = (String[]) method("columnIdsForMode", String.class).invoke(null, AdninGui4.overlayGamemodeEdit);
        int[] indexes = new int[ids.length];
        for (int i = 0; i < indexes.length; i++) indexes[i] = i;
        for (int i = 0; i < indexes.length - 1; i++) for (int j = i + 1; j < indexes.length; j++) {
            if (AdninGui4.overlayColumnOrder[indexes[i]] <= AdninGui4.overlayColumnOrder[indexes[j]]) continue;
            int value = indexes[i]; indexes[i] = indexes[j]; indexes[j] = value;
        }
        StringBuilder result = new StringBuilder("name,hp");
        for (int index : indexes) if (AdninGui4.overlayColumnEnabled[index]) result.append(',').append(ids[index]);
        return result.toString();
    }

    private static void columns() throws Exception {
        String[] modes = {"bedwars", "skywars", "duel", "bedwarsduels", "unknown", null};
        Method build = method("buildColumnsConfigFromState");
        Method stored = method("getStoredColumnsForMode", String.class);
        for (int round = 0; round < 600; round++) {
            AdninGui4.overlayGamemodeEdit = modes[RANDOM.nextInt(modes.length)];
            for (int i = 0; i < 20; i++) {
                AdninGui4.overlayColumnEnabled[i] = RANDOM.nextBoolean();
                AdninGui4.overlayColumnOrder[i] = RANDOM.nextInt(15) - 5;
            }
            String expected = referenceColumns();
            String first = (String) build.invoke(null);
            equal(expected, first, "direct JNI array mutation preserves original tie/order rules");
            check(first == build.invoke(null), "unchanged state reuses its immutable CSV");
            AdninGui4.snapshotOverlayColumnsEditMode();
            for (String active : modes) {
                AdninGui4.overlayGamemodeActive = active;
                String normalized = AdninColumnOrder.mode(active);
                String source = normalized.equals(AdninColumnOrder.mode(AdninGui4.overlayGamemodeEdit))
                    ? expected : (String) stored.invoke(null, normalized);
                String wanted = AdninColumnOrder.normalize(normalized, source);
                String actual = AdninGui4.getOverlayColumnsConfig();
                equal(wanted, actual, "active/edit modes and fallbacks preserve column order");
                check(actual == AdninGui4.getOverlayColumnsConfig(), "unchanged normalized CSV reuses result");
                List<String> ids = Arrays.asList(wanted.split(","));
                check(AdninGui4.isUrchinColumnEnabled() == ids.contains("urchin"), "Urchin flag matches canonical tokens");
                check(AdninGui4.isFkLvColumnEnabled() == (normalized.equals("bedwars") && ids.contains("fklv")),
                    "FK/LV flag remains Bedwars-only");
            }
            AdninGui4.overlayColumnEnabled = AdninGui4.overlayColumnEnabled.clone();
            AdninGui4.overlayColumnOrder = AdninGui4.overlayColumnOrder.clone();
            equal(expected, (String)build.invoke(null), "array replacement with identical values is safe");
        }
        AdninGui4.overlayGamemodeEdit = "bedwars";
        AdninGui4.overlayGamemodeActive = "skywars";
        AdninGui4.loadAllOverlayColumnsFromToggles("urchin,fklv", "urchin,stars", "wins", "index", "");
        equal("name,hp,urchin,sw_stars", AdninGui4.getOverlayColumnsConfig(), "loaded off-mode config invalidates immediately");
        AdninGui4.loadAllOverlayColumnsFromToggles("fklv", "kills", "kdr", "wlr", "");
        equal("name,hp,sw_kills", AdninGui4.getOverlayColumnsConfig(), "replacement source invalidates normalization");
        AdninGui4.setOverlayGamemodeEdit("skywars");
        Arrays.fill(AdninGui4.overlayColumnEnabled, false);
        equal("name,hp", AdninGui4.getOverlayColumnsConfig(), "clearing all direct toggles applies immediately");
        check(!AdninGui4.isUrchinColumnEnabled(), "clearing toggles invalidates derived flags");
        check(((Object[])field(AdninGui4.class, "normalizedColumnSnapshots").get(null)).length == 4,
            "normalization cache has exactly four fixed mode slots");
    }

    // Literal prior algorithm: the optimized implementation is compared with it,
    // including formatting codes and fonts with shaping rather than additive widths.
    private static String referenceFit(String text, float available, int style) {
        if (text == null || available <= 0) return "";
        if (AdninUi.width(text, style) <= available) return text;
        String suffix = "\u2026";
        if (AdninUi.width(suffix, style) > available) return "";
        int end = text.length();
        while (end > 0 && AdninUi.width(text.substring(0, end) + suffix, style) > available) end--;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + suffix;
    }
    private static String referenceTail(String text, float available, int style) {
        if (text == null) text = "";
        while (!text.isEmpty() && AdninUi.width(text, style) > available)
            text = text.substring(text.offsetByCodePoints(0, 1));
        return text;
    }
    private static String masked(String value) {
        char[] result = new char[value.length()]; Arrays.fill(result, '\u2022'); return new String(result);
    }
    private static void clipping() throws Exception {
        String[] special = {null, "", "a", "hello world", "\u00a7aabc\u00a7rxyz", "abc\u00a7",
            "\u73a9\u5bb6\ud83d\ude03\u540d\u79f0", "e\u0301e\u0301", "\u0633\u0644\u0627\u0645",
            "https://owned.invalid/?q=<>&page=0", "\u2022\u2022\u2022\u2026"};
        for (int style = 0; style < 3; style++) for (String value : special) {
            float[] widths = {-1, 0, 1, 7, 10.001f, 24, 67.25f, 310, Float.NaN, Float.POSITIVE_INFINITY};
            for (float width : widths) {
                equal(referenceFit(value, width, style), AdninUi.fit(value, width, style), "prefix exact reference");
                equal(referenceTail(value, width, style), AdninUi.fitTail(value, width, style), "tail exact reference");
            }
        }
        for (int i = 0; i < 500; i++) {
            int length = 1 + RANDOM.nextInt(130), style = RANDOM.nextInt(3);
            StringBuilder value = new StringBuilder();
            for (int j = 0; j < length; j++) value.append((char)(' ' + RANDOM.nextInt(95)));
            String text = value.toString();
            float width = RANDOM.nextFloat() * AdninUi.width(text, style);
            equal(referenceFit(text, width, style), AdninUi.fit(text, width, style), "random additive prefix exact");
            equal(referenceTail(text, width, style), AdninUi.fitTail(text, width, style), "random additive tail exact");
            width = AdninUi.width(text.substring(0, length / 2) + "\u2026", style);
            equal(referenceFit(text, width, style), AdninUi.fit(text, width, style), "exact floating-point prefix boundary");
        }
        char[] longUrl = new char[2048]; Arrays.fill(longUrl, 'W');
        String text = new String(longUrl);
        equal(referenceFit(text, 310, 0), AdninUi.fit(text, 310, 0), "maximum URL prefix exact");
        equal(referenceTail(text, 310, 0), AdninUi.fitTail(text, 310, 0), "maximum URL tail exact");

        AdninGui4 screen = new AdninGui4();
        Method clip = method("visibleInput", String.class, int.class, boolean.class, boolean.class);
        for (String value : special) for (boolean reveal : new boolean[]{false,true}) for (boolean focused : new boolean[]{false,true}) {
            String source = value == null ? "" : value;
            String displayed = reveal ? source : masked(source);
            String expected = focused ? referenceTail(displayed, 80, 0) : referenceFit(displayed, 86, 0);
            String actual = (String)clip.invoke(screen, value, 100, reveal, focused);
            equal(expected, actual, "per-screen cache preserves focus/reveal clipping");
            check(actual == clip.invoke(screen, value, 100, reveal, focused), "unchanged input reuses visible string");
        }
        for (int i = 0; i < 60; i++) clip.invoke(screen, "owned value " + i, 100, true, false);
        Object[] slots = (Object[])field(AdninGui4.class, "inputClips").get(screen);
        check(slots.length == 10, "input cache has exactly ten fixed slots");
        check(!Modifier.isStatic(field(AdninGui4.class, "inputClips").getModifiers()), "input source retention is per instance only");
        field(AdninUi.class, "fontFailed").setBoolean(null, true);
        equal(referenceFit("owned value 59", 86, 0), (String)clip.invoke(screen, "owned value 59", 100, true, false),
            "font failure invalidates previously measured input");
        field(AdninUi.class, "fontFailed").setBoolean(null, false);
        screen.onGuiClosed();
        for (Object cached : slots) check(cached == null, "close releases every retained input source");
    }

    private static void vertex(float x, float y, int[] at) {
        check(UiGeometryFixture.values[at[0]++] == Float.floatToIntBits(x), "X vertex bits unchanged");
        check(UiGeometryFixture.values[at[0]++] == Float.floatToIntBits(y), "Y vertex bits unchanged");
    }
    private static void edge(float x, float y, float w, float h, float r, int step, float fringe, int[] at) {
        step %= 52; int corner = step / 13;
        double angle = -Math.PI + corner * Math.PI / 2 + (step % 13) * Math.PI / 24;
        float cx = (corner == 0 || corner == 3) ? x + r : x + w - r;
        float cy = corner < 2 ? y + r : y + h - r;
        vertex(cx + (r + fringe) * (float)Math.cos(angle), cy + (r + fringe) * (float)Math.sin(angle), at);
    }
    private static void geometry() {
        for (float scale : new float[]{0.37f, 0.7f, 1, 1.4f}) {
            AdninUi.begin(800, 560, scale, 2.5f, 0.8f);
            for (int i = 0; i < 100; i++) {
                float x = RANDOM.nextFloat() * 1000 - 500, y = RANDOM.nextFloat() * 500;
                float w = RANDOM.nextFloat() * 500 + 1, h = RANDOM.nextFloat() * 100 + 1;
                float radius = RANDOM.nextFloat() * 30 + 0.1f, r = Math.min(radius, Math.min(w, h) / 2);
                UiGeometryFixture.count = 0;
                AdninUi.round(x, y, w, h, radius, 0xFFDDAA88);
                int[] at = {0};
                vertex(x + w / 2, y + h / 2, at);
                for (int step = 0; step <= 52; step++) edge(x,y,w,h,r,step,0,at);
                float fringe = 1 / Math.max(0.1f, Math.min(800 * scale / 800, 560 * scale / 560));
                for (int step = 0; step <= 52; step++) {
                    edge(x,y,w,h,r,step,0,at); edge(x,y,w,h,r,step,fringe,at);
                }
                check(at[0] == UiGeometryFixture.count, "same vertex count and ordering including closure point");
            }
            AdninUi.end();
        }
    }

    public static void main(String[] args) throws Exception {
        columns(); clipping(); geometry(); AdninUi.dispose();
        System.out.println("AdninUiPerformanceEquivalenceTest: " + checks
            + " checks passed; direct-array/config cache invalidation, exact clipping, bounded per-screen retention,"
            + " bit-identical rounded vertices and order; no game, network, settings or native GL");
    }
}
