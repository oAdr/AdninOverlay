import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLException;

/**
 * Bounded, read-only API client. Invoke fetch methods on a background worker.
 * No game types, caches, logging, executors, nested classes, or persistent secrets.
 *
 * Urchin's published contract was checked against its own OpenAPI specification:
 * https://api.urchin.gg/openapi.json and https://api.urchin.gg/llms.txt
 * GET /v3/player/tags?player=NAME_OR_UUID, with X-API-Key authentication.
 * The response contains uuid, optional displayname, and tags whose fields include
 * tag_type, reason, added_on, hide_username, and optional expires_at.
 */
public final class AdninApi {
    public static final int MAX_BODY_BYTES = 131072;
    public static final int CONNECT_TIMEOUT_MS = 2500;
    public static final int READ_TIMEOUT_MS = 3500;
    public static final int REQUEST_DEADLINE_MS = 8000;
    private static final int MAX_JSON_DEPTH = 24;
    private static final int MAX_JSON_NODES = 12000;
    private static final String URCHIN_ORIGIN = "https://api.urchin.gg";
    private static final String MOJANG_PROFILE_ORIGIN = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String BOT_PLACEHOLDER = "(?i)%3c%3e";

    private final String json;
    private int position;
    private int nodes;

    private AdninApi(String input) { json = input; }

    /**
     * A user-supplied URL is required. Literal <> and encoded %3C%3E in the query
     * are replaced with the UTF-8 encoded nickname. An empty q= also works.
     * Other query parameters are preserved byte for byte. Credentials, fragments,
     * duplicate q parameters, and placeholders outside the query are rejected.
     */
    public static String buildBotUrl(String template, String nick) throws IOException {
        if (template == null || template.trim().length() == 0) {
            throw new IOException("Bot API URL is not configured");
        }
        if (template.length() > 4096 || hasControl(template)) {
            throw new IOException("Invalid Bot API URL");
        }
        validateQueryName(nick);
        String normalized = template.trim().replace("<>", "%3C%3E");
        URI uri = checkedHttpUri(normalized);
        String query = uri.getRawQuery();
        if (query == null) throw new IOException("Bot URL needs <> or an empty q= query value");
        String prefix = uri.getScheme() + "://" + uri.getRawAuthority()
                + (uri.getRawPath() == null ? "" : uri.getRawPath());
        if (prefix.toLowerCase(java.util.Locale.ROOT).contains("%3c%3e")) {
            throw new IOException("Bot placeholder must be in the query");
        }
        String encoded = encode(nick);
        String[] parts = query.split("&", -1);
        int qCount = 0;
        boolean replaced = false;
        StringBuilder result = new StringBuilder(prefix).append('?');
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int equals = part.indexOf('=');
            String name = equals < 0 ? part : part.substring(0, equals);
            String decodedName;
            try { decodedName = java.net.URLDecoder.decode(name, "UTF-8"); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid Bot query parameter"); }
            if ("q".equals(decodedName)) {
                if (++qCount > 1) throw new IOException("Bot URL has duplicate q parameters");
                if (equals >= 0 && equals == part.length() - 1) {
                    part = name + "=" + encoded;
                    replaced = true;
                }
            }
            if (part.toLowerCase(java.util.Locale.ROOT).contains("%3c%3e")) {
                if (equals < 0 || part.substring(0, equals).toLowerCase(java.util.Locale.ROOT).contains("%3c%3e")) {
                    throw new IOException("Bot placeholder must be a query value");
                }
                part = part.replaceAll(BOT_PLACEHOLDER, java.util.regex.Matcher.quoteReplacement(encoded));
                replaced = true;
            }
            if (i != 0) result.append('&');
            result.append(part);
        }
        if (!replaced) throw new IOException("Bot URL needs <> or an empty q= query value");
        String value = result.toString();
        checkedHttpUri(value);
        return value;
    }

    /** No-result or conflicting exact matches return an empty string. */
    public static String parseBotResponse(String body, String nick) throws IOException {
        validateQueryName(nick);
        Object root = parseJson(body);
        if (!(root instanceof Map)) throw new IOException("Invalid Bot response object");
        Map<?, ?> response = (Map<?, ?>) root;
        if (response.containsKey("error")) throw new IOException("Bot API reported an error");
        Object items = response.get("items");
        if (!(items instanceof List)) return "";
        String matched = "";
        for (Object item : (List<?>) items) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> record = (Map<?, ?>) item;
            boolean exact = exactString(record.get("nickname"), nick);
            Object aliases = record.get("aliases");
            if (aliases instanceof List) {
                for (Object alias : (List<?>) aliases) if (exactString(alias, nick)) exact = true;
            }
            if (!exact) continue;
            Object username = record.get("username");
            // Treat a matching malformed record as inconclusive, not a valid identity.
            if (!(username instanceof String) || !isPlayerName((String) username)) return "";
            String value = (String) username;
            if (matched.length() > 0 && !matched.equalsIgnoreCase(value)) return "";
            matched = value;
        }
        return matched;
    }

    /** The Bot endpoint never receives an Urchin key or another authentication header. */
    public static String fetchBot(String userUrl, String nick) throws IOException {
        return parseBotResponse(fetchJson(buildBotUrl(userUrl, nick), null), nick);
    }

    /** Resolve a Bot-returned real name on the worker, never in a native callback. */
    public static String fetchMojangUuid(String realName) throws IOException {
        if (realName == null || !isPlayerName(realName)) throw new IOException("Invalid Mojang player name");
        return parseMojangUuid(fetchJson(MOJANG_PROFILE_ORIGIN + encode(realName), null), realName);
    }

    /** Replay-only absence marker; ordinary Bot and Urchin lookup contracts are unchanged. */
    public static String fetchReplayMojangProfile(String name) throws IOException {
        try {
            return fetchMojangUuid(name);
        } catch (IOException failure) {
            // Only the fixed official account endpoint's HTTP 404 means absent.
            // Pending requests, malformed profiles and transport failures are not Nick evidence.
            if ("player-not-found".equals(errorCode(failure))) return "NICK";
            throw failure;
        }
    }

    /** Require an exact account name and online UUIDv4; return lowercase dashed form. */
    public static String parseMojangUuid(String body, String expectedName) throws IOException {
        if (expectedName == null || !isPlayerName(expectedName)) throw new IOException("Invalid Mojang player name");
        Object root = parseJson(body);
        if (!(root instanceof Map)) throw new IOException("Invalid Mojang profile object");
        Map<?, ?> response = (Map<?, ?>) root;
        if (response.containsKey("error") || response.containsKey("errorMessage")) {
            throw new IOException("Mojang API reported an error");
        }
        if (!exactString(response.get("name"), expectedName)) throw new IOException("Mojang profile name mismatch");
        Object value = response.get("id");
        if (!(value instanceof String) || !isUuid((String) value)) throw new IOException("Invalid Mojang profile UUID");
        String id = ((String) value).replace("-", "").toLowerCase(java.util.Locale.ROOT);
        if (!id.matches("[0-9a-f]{12}4[0-9a-f]{3}[89ab][0-9a-f]{15}")) {
            throw new IOException("Mojang profile UUID is not online UUIDv4");
        }
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16)
                + "-" + id.substring(16, 20) + "-" + id.substring(20);
    }

    public static String buildUrchinUrl(String player) throws IOException {
        if (player == null || !(isPlayerName(player)
                || isUuid(player))) {
            throw new IOException("Invalid Urchin player identifier");
        }
        return URCHIN_ORIGIN + "/v3/player/tags?player=" + encode(player);
    }

    /** Text form, useful for an overlay cell or a local chat message. */
    public static List<String> parseUrchinTags(String body) throws IOException {
        List<Map<String, String>> details = parseUrchinTagDetails(body);
        Set<String> result = new LinkedHashSet<String>();
        for (Map<String, String> tag : details) {
            String type = tag.get("type");
            String reason = tag.get("reason");
            result.add(type + (reason.length() == 0 ? "" : ": " + reason));
        }
        return Collections.unmodifiableList(new ArrayList<String>(result));
    }

    /**
     * Stable primitive data for caching and selecting a local icon by tag type.
     * No remote textures, adder identities, chat commands, or displayname markup.
     * Each result map contains type and reason, and may contain expiresAt.
     */
    public static List<Map<String, String>> parseUrchinTagDetails(String body) throws IOException {
        try {
            return parseUrchinTagDetailsChecked(body);
        } catch (IOException invalidResponse) {
            // The service's error text and response contents never become UI text.
            throw apiFailure("response-invalid");
        }
    }

    private static List<Map<String, String>> parseUrchinTagDetailsChecked(String body) throws IOException {
        Object root = parseJson(body);
        if (!(root instanceof Map)) throw new IOException("Invalid Urchin response object");
        Map<?, ?> response = (Map<?, ?>) root;
        if (response.containsKey("error")) throw new IOException("Urchin API reported an error");
        Object uuid = response.get("uuid");
        Object tags = response.get("tags");
        if (!(uuid instanceof String) || !isUuid((String) uuid) || !(tags instanceof List)) {
            throw new IOException("Invalid Urchin tags response");
        }
        if (((List<?>) tags).size() > 64) throw new IOException("Too many Urchin tags");
        List<Map<String, String>> result = new ArrayList<Map<String, String>>();
        for (Object value : (List<?>) tags) {
            if (!(value instanceof Map)) throw new IOException("Invalid Urchin tag");
            Map<?, ?> source = (Map<?, ?>) value;
            Object type = source.get("tag_type");
            Object reason = source.get("reason");
            if (!(type instanceof String) || !(reason instanceof String)) throw new IOException("Invalid Urchin tag fields");
            String cleanType = cleanText((String) type, 64);
            if (cleanType.length() == 0) throw new IOException("Empty Urchin tag type");
            Map<String, String> tag = new LinkedHashMap<String, String>();
            tag.put("type", cleanType);
            tag.put("reason", cleanText((String) reason, 512));
            Object expiry = source.get("expires_at");
            if (expiry instanceof BigDecimal) tag.put("expiresAt", ((BigDecimal) expiry).toPlainString());
            result.add(Collections.unmodifiableMap(tag));
        }
        return Collections.unmodifiableList(result);
    }

    public static List<String> fetchUrchin(String player, String apiKey) throws IOException {
        return parseUrchinTags(fetchJson(buildUrchinUrl(player), checkedApiKey(apiKey)));
    }

    public static List<Map<String, String>> fetchUrchinDetails(String player, String apiKey) throws IOException {
        return parseUrchinTagDetails(fetchJson(buildUrchinUrl(player), checkedApiKey(apiKey)));
    }

    private static String checkedApiKey(String key) throws IOException {
        if (key == null || key.length() == 0) throw new IOException("Urchin API key is not configured");
        if (key.length() > 512 || hasControl(key) || !key.equals(key.trim())) throw new IOException("Invalid Urchin API key");
        return key;
    }

    private static String fetchJson(String address, String urchinKey) throws IOException {
        URI uri = checkedHttpUri(address);
        if (urchinKey != null && !("https".equalsIgnoreCase(uri.getScheme())
                && "api.urchin.gg".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && "/v3/player/tags".equals(uri.getPath()))) {
            throw new IOException("Urchin key origin check failed");
        }
        long deadline = System.nanoTime() + REQUEST_DEADLINE_MS * 1000000L;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Adnin/1.0");
            if (urchinKey != null) connection.setRequestProperty("X-API-Key", urchinKey);
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("API HTTP " + status);
            long length = connection.getContentLengthLong();
            if (length > MAX_BODY_BYTES) throw new IOException("API response is too large");
            String encoding = connection.getContentEncoding();
            if (encoding != null && encoding.length() > 0 && !"identity".equalsIgnoreCase(encoding)) {
                throw new IOException("Unsupported API content encoding");
            }
            InputStream stream = connection.getInputStream();
            try {
                return readBoundedBody(stream, MAX_BODY_BYTES, deadline, connection);
            } finally { stream.close(); }
        } catch (IOException failure) {
            // Classify the original subtype before discarding transport messages,
            // which may contain a URL, proxy information, or other private data.
            throw apiFailure(errorCode(failure));
        } finally { if (connection != null) connection.disconnect(); }
    }

    /** Fixed public categories only. Never returns an exception message or cause. */
    public static String errorCode(IOException failure) {
        if (failure == null) return "request-failed";
        if (failure instanceof SocketTimeoutException) return "request-timeout";
        if (failure instanceof UnknownHostException) return "dns-failed";
        if (failure instanceof SSLException) return "tls-failed";
        if (failure instanceof ConnectException || failure instanceof NoRouteToHostException) return "connection-failed";
        String message = failure.getMessage();
        if (message == null) return "request-failed";
        if (message.startsWith("API error ")) {
            String code = message.substring(10);
            if (knownErrorCode(code)) return code;
            return "request-failed";
        }
        if (message.matches("API HTTP [0-9]{3}")) {
            int status = Integer.parseInt(message.substring(9));
            if (status == 400) return "invalid-player";
            if (status == 401) return "authentication-failed";
            if (status == 403) return "access-denied";
            if (status == 404) return "player-not-found";
            if (status == 429) return "rate-limited";
            if (status == 502) return "upstream-failed";
            if (status >= 500 && status <= 599) return "service-unavailable";
            if (status >= 300 && status <= 399) return "redirect-refused";
            return "request-failed";
        }
        if (message.equals("Urchin API key is not configured")) return "key-missing";
        if (message.equals("Invalid Urchin API key")) return "key-invalid";
        if (message.equals("Invalid Urchin player identifier") || message.equals("Invalid Mojang player name")
                || message.equals("Invalid nickname")) return "invalid-player";
        if (message.equals("API request timed out")) return "request-timeout";
        if (message.equals("API response is too large") || message.equals("Invalid UTF-8 API response")
                || message.equals("Unsupported API content encoding") || message.equals("Invalid JSON response")
                || message.equals("JSON response is too complex") || message.equals("Duplicate JSON key")
                || message.equals("Invalid JSON number") || message.equals("JSON number is too large")
                || message.equals("Invalid JSON string") || message.equals("Invalid Bot response object")
                || message.equals("Bot API reported an error") || message.equals("Invalid Mojang profile object")
                || message.equals("Mojang API reported an error") || message.equals("Mojang profile name mismatch")
                || message.equals("Invalid Mojang profile UUID") || message.equals("Mojang profile UUID is not online UUIDv4")) {
            return "response-invalid";
        }
        if (message.equals("Invalid API URL") || message.equals("Urchin key origin check failed")
                || message.equals("Bot API URL is not configured") || message.equals("Invalid Bot API URL")
                || message.equals("Bot URL needs <> or an empty q= query value")
                || message.equals("Bot placeholder must be in the query") || message.equals("Invalid Bot query parameter")
                || message.equals("Bot URL has duplicate q parameters") || message.equals("Bot placeholder must be a query value")
                || message.equals("Invalid response limit")) return "configuration-invalid";
        return "request-failed";
    }

    /**
     * Presentation only: player-not-found stays silent without changing its
     * failure category or cooldown. Other fixed English bodies are red; the
     * caller retains control of the [Urchin] prefix color.
     */
    public static String urchinErrorMessage(String code) {
        if ("player-not-found".equals(code)) return "";
        return "[Urchin] \u00a7c" + AdninLanguage.errorBody(urchinErrorBody(code)) + "\u00a7r";
    }

    /** Never echoes an untrusted code, exception message, URL or response body. */
    private static String urchinErrorBody(String code) {
        String retry = " The next eligible game entry will retry.";
        if (code == null) return "Request failed." + retry;
        switch (code) {
            case "key-missing":
                return "No API key is configured. Add your Urchin API key in Settings." + retry;
            case "key-invalid":
                return "The API key format is invalid. Check your Urchin API key in Settings." + retry;
            case "authentication-failed":
                return "Authentication failed (401). Check your Urchin API key in Settings." + retry;
            case "access-denied":
                return "Access denied (403). Check your API key in Settings and your Urchin account permissions." + retry;
            case "invalid-player":
                return "Player lookup failed: invalid player identifier." + retry;
            case "rate-limited":
                return "Request limit reached (429). Wait for the quota to reset; a later eligible game entry will retry.";
            case "upstream-failed":
                return "The API's upstream service failed (502)." + retry;
            case "service-unavailable":
                return "The Urchin service returned a server error." + retry;
            case "redirect-refused":
                return "The API returned a redirect that was not followed." + retry;
            case "request-timeout":
                return "The request timed out. Check your connection." + retry;
            case "dns-failed":
                return "Could not resolve the Urchin API hostname. Check your DNS and connection." + retry;
            case "tls-failed":
                return "Secure TLS communication with the Urchin API failed." + retry;
            case "connection-failed":
                return "Could not connect to the Urchin API. Check your connection." + retry;
            case "response-invalid":
                return "The API response was invalid or unsupported." + retry;
            case "configuration-invalid":
                return "The Urchin request configuration is invalid." + retry;
            default:
                return "Request failed." + retry;
        }
    }

    private static IOException apiFailure(String code) {
        return new IOException("API error " + (knownErrorCode(code) ? code : "request-failed"));
    }

    private static boolean knownErrorCode(String code) {
        switch (code) {
            case "key-missing": case "key-invalid": case "invalid-player":
            case "authentication-failed": case "access-denied": case "player-not-found":
            case "rate-limited": case "upstream-failed": case "service-unavailable":
            case "redirect-refused": case "request-timeout": case "dns-failed":
            case "tls-failed": case "connection-failed": case "response-invalid":
            case "configuration-invalid": case "request-failed": return true;
            default: return false;
        }
    }

    /** Package-visible for a bounded stream regression test. */
    static String readBoundedBody(InputStream stream, int maxBytes) throws IOException {
        if (maxBytes < 0 || maxBytes > MAX_BODY_BYTES) throw new IOException("Invalid response limit");
        return readBoundedBody(stream, maxBytes, Long.MAX_VALUE, null);
    }

    private static String readBoundedBody(InputStream stream, int maxBytes, long deadline, HttpURLConnection connection) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(4096, maxBytes));
        byte[] buffer = new byte[4096];
        int total = 0;
        while (true) {
            if (connection != null) {
                long remaining = (deadline - System.nanoTime()) / 1000000L;
                if (remaining <= 0) throw new IOException("API request timed out");
                connection.setReadTimeout((int) Math.max(1L, Math.min(READ_TIMEOUT_MS, remaining)));
            }
            int count = stream.read(buffer, 0, Math.min(buffer.length, maxBytes - total + 1));
            if (count < 0) break;
            if (count == 0) continue;
            total += count;
            if (total > maxBytes) throw new IOException("API response is too large");
            bytes.write(buffer, 0, count);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException invalid) { throw new IOException("Invalid UTF-8 API response"); }
    }

    private static URI checkedHttpUri(String address) throws IOException {
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().length() == 0
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() < -1 || uri.getPort() > 65535) throw new IOException("Invalid API URL");
            return uri;
        } catch (URISyntaxException invalid) { throw new IOException("Invalid API URL"); }
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static void validateQueryName(String nick) throws IOException {
        if (nick == null || nick.length() == 0 || nick.length() > 64 || hasControl(nick)) throw new IOException("Invalid nickname");
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static boolean isPlayerName(String value) { return value.matches("[A-Za-z0-9_]{1,16}"); }
    private static boolean isUuid(String value) {
        return value.matches("[0-9A-Fa-f]{32}")
                || value.matches("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    }
    private static boolean exactString(Object value, String expected) { return value instanceof String && expected.equalsIgnoreCase((String) value); }

    public static String cleanText(String value, int limit) {
        if (value == null || limit <= 0) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < limit; i++) {
            char c = value.charAt(i);
            if (c == '\u00a7') { if (i + 1 < value.length()) i++; continue; }
            int type = Character.getType(c);
            if (Character.isISOControl(c) || type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                if (result.length() > 0 && result.charAt(result.length() - 1) != ' ') result.append(' ');
                continue;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)) && result.length() + 2 <= limit) {
                    result.append(c).append(value.charAt(++i));
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            result.append(c);
        }
        return result.toString().trim();
    }

    private static Object parseJson(String value) throws IOException {
        if (value == null || value.length() > MAX_BODY_BYTES) throw new IOException("API response is too large");
        AdninApi parser = new AdninApi(value);
        Object parsed = parser.readValue(0);
        parser.whitespace();
        if (parser.position != value.length()) throw new IOException("Invalid JSON response");
        return parsed;
    }

    private Object readValue(int depth) throws IOException {
        if (depth > MAX_JSON_DEPTH || ++nodes > MAX_JSON_NODES) throw new IOException("JSON response is too complex");
        whitespace();
        if (position >= json.length()) throw new IOException("Invalid JSON response");
        char c = json.charAt(position);
        if (c == '"') return readString();
        if (c == '{') {
            position++;
            Map<String, Object> object = new LinkedHashMap<String, Object>();
            whitespace();
            if (take('}')) return object;
            while (true) {
                whitespace();
                if (position >= json.length() || json.charAt(position) != '"') throw new IOException("Invalid JSON response");
                String key = readString();
                if (object.containsKey(key)) throw new IOException("Duplicate JSON key");
                whitespace();
                if (!take(':')) throw new IOException("Invalid JSON response");
                object.put(key, readValue(depth + 1));
                whitespace();
                if (take('}')) return object;
                if (!take(',')) throw new IOException("Invalid JSON response");
            }
        }
        if (c == '[') {
            position++;
            List<Object> array = new ArrayList<Object>();
            whitespace();
            if (take(']')) return array;
            while (true) {
                array.add(readValue(depth + 1));
                whitespace();
                if (take(']')) return array;
                if (!take(',')) throw new IOException("Invalid JSON response");
            }
        }
        if (json.startsWith("true", position)) { position += 4; return Boolean.TRUE; }
        if (json.startsWith("false", position)) { position += 5; return Boolean.FALSE; }
        if (json.startsWith("null", position)) { position += 4; return null; }
        int start = position;
        if (take('-') && position >= json.length()) throw new IOException("Invalid JSON number");
        if (take('0')) {
            if (position < json.length() && Character.isDigit(json.charAt(position))) throw new IOException("Invalid JSON number");
        } else {
            if (position >= json.length() || json.charAt(position) < '1' || json.charAt(position) > '9') throw new IOException("Invalid JSON response");
            while (position < json.length() && isDigit(json.charAt(position))) position++;
        }
        if (take('.')) {
            int decimal = position;
            while (position < json.length() && isDigit(json.charAt(position))) position++;
            if (decimal == position) throw new IOException("Invalid JSON number");
        }
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            int exponent = position;
            while (position < json.length() && isDigit(json.charAt(position))) position++;
            if (exponent == position) throw new IOException("Invalid JSON number");
        }
        if (position - start > 128) throw new IOException("JSON number is too large");
        try {
            BigDecimal number = new BigDecimal(json.substring(start, position));
            if (Math.abs((long) number.scale()) > 1000) throw new IOException("JSON number is too large");
            return number;
        } catch (NumberFormatException invalid) { throw new IOException("Invalid JSON number"); }
    }

    private String readString() throws IOException {
        position++;
        StringBuilder result = new StringBuilder();
        while (position < json.length()) {
            char c = json.charAt(position++);
            if (c == '"') return result.toString();
            if (c < 0x20) throw new IOException("Invalid JSON string");
            if (c == '\\') {
                if (position >= json.length()) throw new IOException("Invalid JSON string");
                c = json.charAt(position++);
                if (c == '"' || c == '\\' || c == '/') result.append(c);
                else if (c == 'b') result.append('\b');
                else if (c == 'f') result.append('\f');
                else if (c == 'n') result.append('\n');
                else if (c == 'r') result.append('\r');
                else if (c == 't') result.append('\t');
                else if (c == 'u') {
                    if (position + 4 > json.length()) throw new IOException("Invalid JSON string");
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int digit = Character.digit(json.charAt(position++), 16);
                        if (digit < 0) throw new IOException("Invalid JSON string");
                        code = code * 16 + digit;
                    }
                    result.append((char) code);
                } else throw new IOException("Invalid JSON string");
            } else result.append(c);
        }
        throw new IOException("Invalid JSON string");
    }

    private boolean take(char expected) {
        if (position < json.length() && json.charAt(position) == expected) { position++; return true; }
        return false;
    }
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private void whitespace() {
        while (position < json.length()) {
            char c = json.charAt(position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            position++;
        }
    }
}
