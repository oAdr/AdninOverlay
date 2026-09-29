import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

/** Presentation for allowlisted native producers only; never parses incoming player chat. */
public final class AdninMessages {
    private static final int MAX_TEXT = 16384;
    private static final Pattern NICK = Pattern.compile(
        "^\\[Adnin\\] [^\\r\\n]{1,160} is nicked$");
    private static final Pattern STATS = Pattern.compile(
        "^\\[Adnin\\] [^\\r\\n]{1,160} - (?:Wins: [0-9,.]+ WLR: [0-9,.]+ KDR: [0-9,.]+"
        + "|KDR: [0-9,.]+ WLR: [0-9,.]+|FKDR: [0-9,.]+|BW-Duels: [0-9,.]+ WLR: [0-9,.]+)$");
    private static final Pattern SERAPH = Pattern.compile(
        "^\\[Seraph\\] (?:\\[[^\\]\\r\\n]{1,24}\\] )*[A-Za-z0-9_]{1,16}( is blacklisted for )");
    private static final Pattern SESSION_LABEL = Pattern.compile(
        "^(Finals|Beds|Kills|Wins|Winstreak|Session Games|Slumber Tickets|Game Time|Avg Time|Session Time)(?=: )");
    private static final Pattern SESSION_TITLE = Pattern.compile("^(Session Stats|Session|Game)(?=  |$)");
    private AdninMessages() { }

    /** Return one only after the localized local message was actually delivered. */
    public static int renderGenerated(String text, boolean json, int category) {
        try {
            String translated = translateGenerated(text, json, category);
            if (translated == null) return 0;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !mc.isCallingFromMinecraftThread() || mc.ingameGUI == null
                    || mc.ingameGUI.getChatGUI() == null) return 0;
            IChatComponent message = json ? IChatComponent.Serializer.jsonToComponent(translated)
                : new ChatComponentText(translated);
            if (message == null) return 0;
            mc.ingameGUI.getChatGUI().printChatMessage(message);
            return 1;
        } catch (RuntimeException invalid) { return 0; }
        catch (LinkageError unavailable) { return 0; }
    }

    /** Null means retain the original native renderer, including unknown templates. */
    public static String translateGenerated(String text, boolean json, int category) {
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT
                || "en".equals(AdninLanguage.getLanguage()) || (category != 0 && category != 1 && category != 3)) return null;
        try {
            if (!json) return translatedText(text, category);
            if (!boundedJson(text)) return null;
            JsonElement tree = new JsonParser().parse(text);
            List<Part> parts = new ArrayList<Part>();
            StringBuilder raw = new StringBuilder();
            if (!collect(tree, parts, raw, 0)) return null;
            Visible visible = new Visible(raw.toString());
            List<Change> changes = generatedChanges(visible.plain, category);
            if (changes.isEmpty()) return null;
            boolean changed = false;
            for (Part part : parts) {
                String result = replace(part.text, part.offset, visible, changes);
                if (!result.equals(part.text)) {
                    part.object.addProperty("text", result);
                    changed = true;
                }
            }
            return changed ? tree.toString() : null;
        } catch (RuntimeException invalid) { return null; }
    }

    /** Only native Session HUD rows call this; numeric values and ratios are retained. */
    public static String sessionRow(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT
                || "en".equals(AdninLanguage.getLanguage())) return text;
        Visible visible = new Visible(text);
        Matcher label = SESSION_LABEL.matcher(visible.plain);
        if (!label.find()) {
            label = SESSION_TITLE.matcher(visible.plain);
            if (!label.find()) return text;
        }
        List<Change> changes = new ArrayList<Change>();
        add(changes, label.start(1), label.end(1), label.group(1));
        return changes.isEmpty() ? text : replace(text, 0, visible, changes);
    }

    private static String translatedText(String raw, int category) {
        Visible visible = new Visible(raw);
        List<Change> changes = generatedChanges(visible.plain, category);
        if (changes.isEmpty()) return null;
        String result = replace(raw, 0, visible, changes);
        return result.equals(raw) ? null : result;
    }

    private static List<Change> generatedChanges(String plain, int category) {
        List<Change> changes = new ArrayList<Change>();
        if (category == 3) {
            apiChanges(plain, changes, true);
        } else if (category == 1) {
            Matcher seraph = SERAPH.matcher(plain);
            if (seraph.find()) add(changes, seraph.start(1), seraph.end(1), seraph.group(1));
            // All subsequent text is opaque API tag/reason content, even if it
            // resembles one of our labels. Hover payloads are never traversed.
        } else if (NICK.matcher(plain).matches()) {
            int start = plain.length() - " is nicked".length();
            add(changes, start, start + 4, " is ");
            add(changes, start + 4, plain.length(), "nicked");
        } else if (STATS.matcher(plain).matches()) {
            int start = plain.lastIndexOf(" - ") + 3;
            if (plain.startsWith("Wins:", start)) add(changes, start, start + 4, "Wins");
            else if (plain.startsWith("BW-Duels:", start)) add(changes, start, start + 8, "BW-Duels");
            // Established ratio abbreviations FKDR/KDR/WLR stay recognizable.
        } else apiChanges(plain, changes, false);
        return changes;
    }

    private static void apiChanges(String plain, List<Change> changes, boolean errorsOnly) {
        if (plain.startsWith("[Adnin] ")) {
            String body = plain.substring(8);
            String suffix = ". Check your key in settings.";
            for (String key : new String[]{"Invalid Hypixel API key", "Invalid Seraph API key"}) {
                if (body.equals(key + suffix)) {
                    add(changes, 8, 8 + key.length(), key);
                    add(changes, 8 + key.length(), plain.length(), suffix);
                    return;
                }
            }
            if (errorsOnly) return;
            for (String prefix : new String[]{"Fetching stats for ", "Unable to fetch stats for: "}) {
                if (body.startsWith(prefix) && body.substring(prefix.length()).matches("[A-Za-z0-9_]{1,16}(?:\\.\\.\\.)?")) {
                    add(changes, 8, 8 + prefix.length(), prefix);
                    return;
                }
            }
        }
    }

    private static void add(List<Change> changes, int start, int end, String english) {
        String translated = AdninLanguage.text(english);
        if (!translated.equals(english)) changes.add(new Change(start, end, translated));
    }

    /** Copy formatting bytes and component boundaries; replace visible literals only. */
    private static String replace(String text, int offset, Visible visible, List<Change> changes) {
        StringBuilder result = new StringBuilder();
        int change = 0;
        for (int i = 0; i < text.length(); i++) {
            int raw = offset + i;
            while (change < changes.size() && raw >= visible.end(changes.get(change).end)) change++;
            if (change < changes.size()) {
                Change current = changes.get(change);
                int start = visible.at[current.start], end = visible.end(current.end);
                if (raw == start) result.append(current.text);
                if (raw >= start && raw < end && !visible.format[raw]) continue;
            }
            result.append(text.charAt(i));
        }
        return result.toString();
    }

    private static boolean collect(JsonElement element, List<Part> parts, StringBuilder raw, int depth) {
        if (depth > 24 || parts.size() >= 256 || element == null || !element.isJsonObject()) return false;
        JsonObject object = element.getAsJsonObject();
        if (object.has("translate") || object.has("score") || object.has("selector")) return false;
        if (object.has("text")) {
            JsonElement value = object.get("text");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
            String text = value.getAsString();
            parts.add(new Part(object, text, raw.length()));
            raw.append(text);
            if (raw.length() > MAX_TEXT) return false;
        }
        if (object.has("extra")) {
            if (!object.get("extra").isJsonArray()) return false;
            JsonArray extra = object.getAsJsonArray("extra");
            for (JsonElement child : extra) if (!collect(child, parts, raw, depth + 1)) return false;
        }
        return true;
    }

    // Bound nesting before Gson parses it; malformed JSON falls back to native.
    private static boolean boundedJson(String text) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 32) return false; }
            else if (c == '}' || c == ']') { if (--depth < 0) return false; }
        }
        return depth == 0 && !quoted;
    }

    private static final class Visible {
        final String plain;
        final int[] at;
        final boolean[] format;
        final int length;
        Visible(String raw) {
            length = raw.length();
            at = new int[length]; format = new boolean[length];
            StringBuilder clean = new StringBuilder();
            for (int i = 0; i < length; i++) {
                char c = raw.charAt(i);
                if (c == '\u00a7' && i + 1 < length
                        && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(raw.charAt(i + 1)) >= 0) {
                    format[i] = format[i + 1] = true; i++;
                } else { at[clean.length()] = i; clean.append(c); }
            }
            plain = clean.toString();
        }
        int end(int index) { return index == plain.length() ? length : at[index]; }
    }
    private static final class Change {
        final int start, end; final String text;
        Change(int start, int end, String text) { this.start = start; this.end = end; this.text = text; }
    }
    private static final class Part {
        final JsonObject object; final String text; final int offset;
        Part(JsonObject object, String text, int offset) { this.object = object; this.text = text; this.offset = offset; }
    }
}
