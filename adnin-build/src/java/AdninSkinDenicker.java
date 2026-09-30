import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

/**
 * Mellow SkinUtils texture-owner approach, adapted for an injected Java 8 client.
 * Upstream 17ef9b7466754a33ee8c8ed87fa7ea717573d775, GPL-3.0; see notices.
 * A texture owner is evidence, not proof of the person using a shared skin.
 * No HTTP, filesystem access, texture loading, game mutation or worker thread.
 */
public final class AdninSkinDenicker {
    private static final int MAX_CACHE = 512, MAX_PENDING = 128, MAX_ROSTER = 256;
    private static final int MAX_TEXTURE = 8192, BATCH = 8;
    private static final long CACHE_MS = 600000L, SCAN_MS = 250L, RETRY_MS = 1000L;
    private static final LinkedHashMap<String, Evidence> evidence = new LinkedHashMap<String, Evidence>();
    private static final Map<String, String> current = new HashMap<String, String>();
    private static final Map<String, String> currentNames = new HashMap<String, String>();
    private static final Map<String, CurrentEvidence> currentEvidence = new HashMap<String, CurrentEvidence>();
    private static final LinkedHashMap<String, Long> requested = new LinkedHashMap<String, Long>();
    private static final LinkedHashSet<String> pending = new LinkedHashSet<String>();
    private static final LinkedHashSet<String> announced = new LinkedHashSet<String>();
    private static final Set<String> published = new HashSet<String>();
    private static final Set<String> attempted = new HashSet<String>();
    private static Object world, connection;
    private static boolean replayContext, stopped, enabled;
    private static long nextScan, parses, rosterAt, generation;
    private static final class CurrentEvidence {
        final UUID id;
        final String payload;
        CurrentEvidence(UUID id, String payload) { this.id = id; this.payload = payload; }
    }
    private static final class Evidence {
        final String profile;
        final long expires;
        Evidence(String p, long e) { profile = p; expires = e; }
    }
    private AdninSkinDenicker() { }

    public static synchronized void setEnabled(boolean value) {
        if (enabled != value) { enabled = value; clearContext(); }
    }
    public static synchronized boolean hasAttempted(String name) {
        return validName(name) && attempted.contains(name.toLowerCase(Locale.ROOT));
    }
    /** Native acknowledges that the validated identity and ready stats were published. */
    public static synchronized void markPublished(String name, String profile) {
        if (stopped || !enabled || !configured() || !AdninFeatures.outputContextAllowed() || !validName(name)) return;
        String key = name.toLowerCase(Locale.ROOT);
        if (profile != null && profile.equals(current.get(key))) {
            if (published.size() >= MAX_CACHE) published.clear();
            published.add(key + "|" + profile);
        }
    }

    /** Native callback: immutable identity result or a bounded client-thread hint. */
    public static synchronized String getProfile(String name) {
        return getProfile(name, System.currentTimeMillis());
    }
    static synchronized String getProfile(String name, long now) {
        if (stopped || !enabled || !configured() || !validName(name) || !AdninFeatures.outputContextAllowed()) return "";
        String key = name.toLowerCase(Locale.ROOT);
        Long last = requested.get(key);
        if ((last == null || now - last >= (current.containsKey(key) ? 5000L : RETRY_MS)) && pending.size() < MAX_PENDING) {
            pending.add(name);
            requested.put(key, now);
            trim(requested, MAX_CACHE);
        }
        String profile = now >= rosterAt && now - rosterAt <= 300L ? current.get(key) : null;
        return profile == null ? "" : profile;
    }

    public static void tick(Minecraft mc) { tick(mc, System.currentTimeMillis()); }
    static void tick(Minecraft mc, long now) {
        if (mc == null || !mc.isCallingFromMinecraftThread()) return;
        List<String[]> messages = null;
        long observedGeneration;
        synchronized (AdninSkinDenicker.class) {
            if (stopped) return;
            if (!configured()) { setEnabled(false); return; }
            if (!enabled) return;
            Object nextWorld = mc.theWorld, nextConnection = mc.getNetHandler();
            boolean replay = AdninReplay.isReplay();
            if (world != nextWorld || connection != nextConnection || replayContext != replay) {
                clearContext();
                world = nextWorld; connection = nextConnection; replayContext = replay;
            }
            if (nextWorld == null || nextConnection == null || !AdninFeatures.outputContextAllowed()) {
                clearContext();
                return;
            }
            if (now < rosterAt) {
                clearContext();
                world = nextWorld; connection = nextConnection; replayContext = replay;
            }
            if (now < nextScan) return;
            nextScan = now + SCAN_MS;
            if (!pending.isEmpty() || !current.isEmpty()) {
                // One roster pass per bounded batch; never one world/entity scan per row.
                Map<String, NetworkPlayerInfo> roster = new HashMap<String, NetworkPlayerInfo>();
                Set<String> ambiguous = new HashSet<String>();
                int count = 0;
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    if (++count > MAX_ROSTER) break;
                    if (info == null || info.getGameProfile() == null) continue;
                    GameProfile p = info.getGameProfile();
                    String raw = p.getName();
                    if (!validName(raw) || p.getId() == null) continue;
                    if (!replay && p.getId().version() != 1) continue;
                    if (replay && !AdninReplay.isNick(raw)) continue;
                    String key = raw.toLowerCase(Locale.ROOT);
                    if (roster.put(key, info) != null) ambiguous.add(key);
                }
                for (Iterator<Map.Entry<String, String>> active = current.entrySet().iterator(); active.hasNext();) {
                    Map.Entry<String,String> entry = active.next();
                    if (!roster.containsKey(entry.getKey()) || ambiguous.contains(entry.getKey())) {
                        published.remove(entry.getKey()+"|"+entry.getValue()); active.remove();
                    }
                }
                currentNames.keySet().retainAll(current.keySet());
                currentEvidence.keySet().retainAll(current.keySet());
                attempted.retainAll(roster.keySet());
                // Refresh cheap identity evidence independently of the five-
                // second native hint throttle. Name reuse must not reuse a skin.
                for (Iterator<Map.Entry<String, CurrentEvidence>> existing = currentEvidence.entrySet().iterator(); existing.hasNext();) {
                    Map.Entry<String, CurrentEvidence> entry = existing.next();
                    GameProfile profile = roster.get(entry.getKey()).getGameProfile();
                    CurrentEvidence previous = entry.getValue();
                    String payload = textureEvidence(profile);
                    if (!previous.id.equals(profile.getId()) || !previous.payload.equals(payload)) {
                        published.remove(entry.getKey()+"|"+current.get(entry.getKey()));
                        current.remove(entry.getKey()); currentNames.remove(entry.getKey());
                        attempted.remove(entry.getKey()); existing.remove();
                        if (pending.size() < MAX_PENDING) pending.add(profile.getName());
                    }
                }
                rosterAt = now;
                Iterator<String> it = pending.iterator();
                for (int done = 0; done < BATCH && it.hasNext(); done++) {
                    String raw = it.next(); it.remove();
                    String key = raw.toLowerCase(Locale.ROOT);
                    NetworkPlayerInfo info = ambiguous.contains(key) ? null : roster.get(key);
                    String nick = replay ? AdninReplay.recordedName(raw) : info == null ? raw : info.getGameProfile().getName();
                    String payload = info == null ? "" : textureEvidence(info.getGameProfile());
                    String result = validName(nick) && !payload.isEmpty() ? fromTexture(payload, now) : "";
                    attempted.add(key);
                    if (attempted.size() > MAX_CACHE) attempted.clear();
                    if (!result.isEmpty() && result.substring(0, result.indexOf('|')).equalsIgnoreCase(nick)) result = "";
                    if (result.isEmpty()) { current.remove(key); currentNames.remove(key); currentEvidence.remove(key); }
                    else {
                        current.put(key, result); currentNames.put(key, nick);
                        currentEvidence.put(key, new CurrentEvidence(info.getGameProfile().getId(), payload));
                    }
                }
            }
            for (Map.Entry<String, String> entry : current.entrySet()) {
                String nick = currentNames.get(entry.getKey());
                if (!validName(nick)) continue;
                String profile = entry.getValue();
                String id = entry.getKey() + "|" + profile;
                if (published.contains(id) && !announced.contains(id)) {
                    if (messages == null) messages = new ArrayList<String[]>();
                    messages.add(new String[]{nick, profile.substring(0, profile.indexOf('|')), id});
                }
            }
            observedGeneration = generation;
        }
        // Features owns the outer client lifecycle. Never acquire its monitor
        // while holding the Skin monitor; native readers cannot reverse locks.
        if (messages == null) return;
        for (String[] message : messages) {
            if (!deliveryCurrent(observedGeneration, message[2])) continue;
            if (AdninFeatures.skinResolved(message[0], message[1])) {
                synchronized (AdninSkinDenicker.class) {
                    if (deliveryCurrent(observedGeneration, message[2])) {
                        announced.add(message[2]);
                        while (announced.size() > MAX_CACHE) { Iterator<String> it = announced.iterator(); it.next(); it.remove(); }
                    }
                }
            }
        }
    }
    private static synchronized boolean deliveryCurrent(long observed, String id) {
        return !stopped && enabled && configured() && generation == observed && published.contains(id)
            && AdninFeatures.outputContextAllowed();
    }
    private static boolean configured() {
        // The original native Skin setting is derived from Hypixel key presence,
        // not a separate GUI option. Read only presence; never copy/log a key.
        String value = AdninGui4.api_hypixel;
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > ' ') return true;
        return false;
    }

    private static String textureEvidence(GameProfile profile) {
        Collection<Property> textures = profile.getProperties().get("textures");
        if (textures == null || textures.isEmpty()) return "";
        String result = "";
        int count = 0;
        for (Property property : textures) {
            if (++count > 4 || property == null) return "";
            String payload = property.getValue();
            if (payload == null || payload.isEmpty() || payload.length() > MAX_TEXTURE) return "";
            if (!result.isEmpty() && !result.equals(payload)) return "";
            result = payload;
        }
        return result;
    }
    private static String fromTexture(String payload, long now) {
        Evidence cached = evidence.get(payload);
        if (cached == null || now >= cached.expires) {
            parses++;
            cached = new Evidence(resolveTexture(payload), now + CACHE_MS);
            evidence.put(payload, cached);
            trim(evidence, MAX_CACHE);
        }
        return cached.profile;
    }

    /** Bounded, strict metadata reader. Missing/default/ambiguous data fail closed. */
    static String resolveTexture(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TEXTURE) return "";
        try {
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            if (!boundedNesting(json)) return "";
            JsonReader reader = new JsonReader(new java.io.StringReader(json));
            reader.setLenient(false);
            String name = "", id = "", url = "";
            Set<String> seen = new HashSet<String>();
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!seen.add(field)) return "";
                if ("profileName".equals(field)) name = readString(reader);
                else if ("profileId".equals(field)) id = readString(reader);
                else if ("textures".equals(field)) url = readTextures(reader);
                else reader.skipValue();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !validName(name)) return "";
            String uuid = onlineUuid(id), hash = textureHash(url);
            if (uuid.isEmpty() || hash.isEmpty() || AdninNickSkins.contains(hash)) return "";
            return name + "|" + uuid;
        } catch (Exception unavailable) { return ""; }
    }

    private static String readTextures(JsonReader reader) throws java.io.IOException {
        String url = "";
        Set<String> seen = new HashSet<String>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!seen.add(field)) throw new java.io.IOException("Duplicate texture field");
            if (!"SKIN".equals(field)) { reader.skipValue(); continue; }
            Set<String> skinSeen = new HashSet<String>();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!skinSeen.add(key)) throw new java.io.IOException("Duplicate skin field");
                if ("url".equals(key)) url = readString(reader); else reader.skipValue();
            }
            reader.endObject();
        }
        reader.endObject();
        return url;
    }
    private static String readString(JsonReader reader) throws java.io.IOException {
        if (reader.peek() != JsonToken.STRING) throw new java.io.IOException("Expected string");
        return reader.nextString();
    }
    private static boolean boundedNesting(String json) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 8) return false; }
            else if (c == '}' || c == ']') { if (--depth < 0) return false; }
        }
        return depth == 0 && !quoted;
    }
    private static String textureHash(String url) {
        String plain = url.toLowerCase(Locale.ROOT);
        String path = plain.startsWith("https://textures.minecraft.net/texture/") ? plain.substring(39)
            : plain.startsWith("http://textures.minecraft.net/texture/") ? plain.substring(38) : "";
        if (path.length() < 32 || path.length() > 64) return "";
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return "";
        }
        return path;
    }
    private static String onlineUuid(String id) {
        if (id.length() == 32) id = id.substring(0,8)+"-"+id.substring(8,12)+"-"+id.substring(12,16)+"-"+id.substring(16,20)+"-"+id.substring(20);
        if (id.length() != 36) return "";
        try {
            UUID uuid = UUID.fromString(id);
            return uuid.version() == 4 && uuid.variant() == 2 && uuid.toString().equalsIgnoreCase(id) ? uuid.toString() : "";
        } catch (IllegalArgumentException invalid) { return ""; }
    }
    static boolean validName(String name) {
        if (name == null || name.length() < 1 || name.length() > 16) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return true;
    }
    private static void trim(Map<?, ?> map, int maximum) {
        while (map.size() > maximum) { Iterator<?> it = map.keySet().iterator(); it.next(); it.remove(); }
    }
    public static synchronized void clearContext() {
        generation++;
        current.clear(); currentNames.clear(); currentEvidence.clear(); requested.clear(); pending.clear(); announced.clear(); published.clear(); attempted.clear(); nextScan = 0; rosterAt = 0;
        world = null; connection = null;
    }
    public static synchronized void shutdown() { stopped = true; clearContext(); evidence.clear(); }
}
