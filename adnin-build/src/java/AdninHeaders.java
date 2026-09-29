import net.minecraft.client.gui.FontRenderer;

/** Short labels only for the native Overlay header callsites, never player data. */
public final class AdninHeaders {
    private static final String[][] LABELS = {
        {"Name", "玩家", "玩家"}, {"Stars", "星级", "星級"},
        {"Wins", "胜场", "勝場"}, {"Finals", "终杀", "終殺"},
        {"Beds", "拆床", "拆床"}, {"Requeue%", "重排率", "重排率"},
        {"Kills", "击杀", "擊殺"}, {"Seen", "遇见", "遇見"},
        {"Session", "本次", "本次"}, {"Ping", "延迟", "延遲"},
        {"PingVar", "波动", "波動"}
    };
    private AdninHeaders() { }

    /** Called for both measurement and drawing with the same native font and width. */
    public static String translate(String text, Object renderer, int width) {
        String locale = AdninLanguage.getLanguage();
        if (text == null || "en".equals(locale) || !(renderer instanceof FontRenderer)
                || width <= 0 || width > 4096) return text;
        String label = null;
        for (String[] item : LABELS) if (item[0].equals(text)) {
            label = item["zh_TW".equals(locale) ? 2 : 1];
            break;
        }
        if (label == null) return text;
        try {
            FontRenderer font = (FontRenderer) renderer;
            int available = Math.max(0, width - 4);
            int measured = font.getStringWidth(label);
            if (measured < 0) return text;
            if (measured <= available) return label;
            int dots = font.getStringWidth("...");
            if (dots < 0) return text;
            if (dots > available) return "";
            for (int end = label.length(); end > 0;) {
                end = label.offsetByCodePoints(end, -1);
                String candidate = label.substring(0, end) + "...";
                measured = font.getStringWidth(candidate);
                if (measured < 0) return text;
                if (measured <= available) return candidate;
            }
            return "...";
        } catch (RuntimeException failed) { return text; }
        catch (LinkageError unavailable) { return text; }
    }
}
