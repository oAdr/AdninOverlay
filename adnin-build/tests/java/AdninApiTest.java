import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

/** Offline API regression suite; loopback fixtures never require real API keys. */
public final class AdninApiTest implements Runnable {
    private static int checks;
    private static final String UUID = "12345678-1234-1234-1234-123456789abc";
    private static final String BOT = "{\"total\":3,\"page\":0,\"pages\":1,\"items\":["
            + "{\"username\":\"Adnin\",\"nickname\":\"Isa5\",\"aliases\":[\"Isa5\"],\"sightings\":1},"
            + "{\"username\":\"Other\",\"nickname\":\"fr33_spy\",\"aliases\":[\"imisa546\"]},"
            + "{\"username\":\"Third\",\"nickname\":\"itz_isa544\",\"aliases\":[\"itz_isa544\"]}]}";
    private static final String URCHIN = "{\"uuid\":\"" + UUID + "\",\"displayname\":\"Unused\",\"tags\":["
            + "{\"tag_type\":\"confirmed_cheater\",\"reason\":\"Example reason\",\"added_on\":0,\"hide_username\":true,\"added_by_username\":\"MUST_NOT_LEAK\"},"
            + "{\"tag_type\":\"sniper\",\"reason\":\"\",\"added_on\":0,\"hide_username\":false,\"expires_at\":123456789}]}";
    private final ServerSocket fixture;
    private final String response;
    private volatile String capturedRequest = "";
    private volatile IOException fixtureError;

    private AdninApiTest(ServerSocket server, String responseText) {
        fixture = server;
        response = responseText;
    }

    public static void main(String[] args) throws Exception {
        eq("http://example.test/api/users?q=isa5&page=0", AdninApi.buildBotUrl("http://example.test/api/users?q=<>&page=0", "isa5"));
        eq("https://example.test/api/users?page=0&q=isa5&sort=last%20seen", AdninApi.buildBotUrl("https://example.test/api/users?page=0&q=%3c%3E&sort=last%20seen", "isa5"));
        eq("http://example.test/api/users?q=isa5&page=0", AdninApi.buildBotUrl("http://example.test/api/users?q=&page=0", "isa5"));
        eq("http://example.test/api/users?q=A%26page%3D9%23x%20%E7%8C%AB&page=0", AdninApi.buildBotUrl("http://example.test/api/users?q=<>&page=0", "A&page=9#x 猫"));
        eq("http://example.test/api/users?nick=isa5&page=0", AdninApi.buildBotUrl("http://example.test/api/users?nick=<>&page=0", "isa5"));
        fail(0, "", "isa5");
        fail(0, "file:///tmp/data?q=<>", "isa5");
        fail(0, "http://user:password@example.test/?q=<>", "isa5");
        fail(0, "http://example.test/?q=<>#fragment", "isa5");
        fail(0, "http://<>.test/?q=<>", "isa5");
        fail(0, "http://example.test/<>?q=<>", "isa5");
        fail(0, "http://example.test/?q=<>&q=another", "isa5");
        fail(0, "http://example.test/?q=<>&%71=another", "isa5");
        fail(0, "http://example.test/?q=isa5", "isa5");
        fail(0, "http://example.test/?q=<>", "a\r\nHeader: value");
        fail(0, "http://example.test/?q=<>\r\nHeader: value", "isa5");
        fail(0, "http://example.test:99999/?q=<>", "isa5");

        eq("Adnin", AdninApi.parseBotResponse(BOT, "isa5"));
        eq("", AdninApi.parseBotResponse(BOT, "isa"));
        eq("Other", AdninApi.parseBotResponse(BOT, "IMISA546"));
        eq("", AdninApi.parseBotResponse("{\"items\":[]}", "isa5"));
        eq("", AdninApi.parseBotResponse("{}", "isa5"));
        eq("", AdninApi.parseBotResponse("{\"items\":[{\"username\":\"Adnin\",\"nickname\":\"isa5\"},{\"username\":\"Other\",\"aliases\":[\"ISA5\"]}]}", "isa5"));
        eq("ADNIN", AdninApi.parseBotResponse("{\"items\":[{\"username\":\"Adnin\",\"nickname\":\"isa5\"},{\"username\":\"ADNIN\",\"aliases\":[\"ISA5\"]}]}", "isa5"));
        eq("", AdninApi.parseBotResponse("{\"items\":[{\"username\":\"/pc exploit\",\"nickname\":\"isa5\"}]}", "isa5"));
        eq("", AdninApi.parseBotResponse("{\"items\":[{\"username\":\"Adnin\\ncommand\",\"nickname\":\"isa5\"}]}", "isa5"));
        eq("", AdninApi.parseBotResponse("{\"items\":[{\"nickname\":\"isa5\"}]}", "isa5"));
        eq("Adnin", AdninApi.parseBotResponse("{\"items\":[null,2,{\"username\":\"Adnin\",\"nickname\":\"Isa\\u0035\"}]}", "isa5"));
        fail(1, "{\"items\":[],\"items\":[{\"username\":\"Adnin\",\"nickname\":\"isa5\"}]}", "isa5");
        fail(1, "{\"items\":[]} trailing", "isa5");
        fail(1, "{\"items\":[}", "isa5");
        fail(1, "{\"items\":[],\"error\":\"secret invalid\"}", "isa5");
        fail(1, "{\"items\":[],\"unknown\":1e999999999}", "isa5");
        String deep = "";
        for (int i = 0; i < 30; i++) deep += "[";
        for (int i = 0; i < 30; i++) deep += "]";
        fail(1, deep, "isa5");
        fail(1, repeat("x", AdninApi.MAX_BODY_BYTES + 1), "isa5");

        eq("12345678-1234-4abc-8def-123456789abc", AdninApi.parseMojangUuid("{\"id\":\"1234567812344ABC8DEF123456789ABC\",\"name\":\"Adnin\"}", "adnin"));
        eq("12345678-1234-4abc-bdef-123456789abc", AdninApi.parseMojangUuid("{\"id\":\"12345678-1234-4abc-bdef-123456789abc\",\"name\":\"Adnin\"}", "Adnin"));
        fail(6, "{\"id\":\"1234567812344abc8def123456789abc\",\"name\":\"Other\"}", "Adnin");
        fail(6, "{\"id\":\"1234567812343abc8def123456789abc\",\"name\":\"Adnin\"}", "Adnin");
        fail(6, "{\"id\":\"1234567812344abc7def123456789abc\",\"name\":\"Adnin\"}", "Adnin");
        fail(6, "{\"id\":\"1234567812344abc8def123456789abg\",\"name\":\"Adnin\"}", "Adnin");
        fail(6, "{\"id\":\"12345678-1234-4abc-8def-123456789ab\",\"name\":\"Adnin\"}", "Adnin");
        fail(6, "{\"id\":\"00000000000000000000000000000000\",\"name\":\"Adnin\"}", "Adnin");
        fail(6, "{\"id\":123,\"name\":\"Adnin\"}", "Adnin");
        fail(6, "{\"name\":\"Adnin\"}", "Adnin");
        fail(6, "{\"id\":\"1234567812344abc8def123456789abc\"}", "Adnin");
        fail(6, "{\"id\":\"1234567812344abc8def123456789abc\",\"name\":\"Adnin\",\"errorMessage\":\"fixture\"}", "Adnin");
        fail(6, "{\"error\":\"fixture\"}", "Adnin");
        fail(6, "[]", "Adnin");
        fail(6, "{", "Adnin");
        fail(6, "{}", "bad/name");
        fail(6, "{}", null);
        fail(7, "bad/name", "");
        fail(7, "bad?name=Other", "");
        fail(7, null, "");

        eq("https://api.urchin.gg/v3/player/tags?player=Adnin", AdninApi.buildUrchinUrl("Adnin"));
        eq("https://api.urchin.gg/v3/player/tags?player=" + UUID, AdninApi.buildUrchinUrl(UUID));
        fail(2, "Adnin&key=other", "");
        List<String> tags = AdninApi.parseUrchinTags(URCHIN);
        eq(2, tags.size());
        eq("confirmed_cheater: Example reason", tags.get(0));
        eq("sniper", tags.get(1));
        check(!tags.toString().contains("MUST_NOT_LEAK"), "Urchin must omit adder identities");
        List<Map<String, String>> details = AdninApi.parseUrchinTagDetails(URCHIN);
        eq("123456789", details.get(1).get("expiresAt"));
        check(!details.toString().contains("MUST_NOT_LEAK"), "Details must omit adder identities");
        eq(0, AdninApi.parseUrchinTags("{\"uuid\":\"" + UUID + "\",\"tags\":[]}").size());
        fail(3, "{\"tags\":[]}", "");
        fail(3, "{\"uuid\":\"--------------------------------\",\"tags\":[]}", "");
        fail(3, "{\"uuid\":\"" + UUID + "\",\"tags\":[\"fake\"]}", "");
        fail(3, "{\"uuid\":\"" + UUID + "\",\"tags\":[{\"tag_type\":\"test\"}]}", "");
        fail(3, "{\"error\":\"API key secret\"}", "");
        eq("red hello /pc harmless", AdninApi.cleanText("\u00a7cred\nhello\r/pc harmless\u202e", 200));
        eq("abc", AdninApi.cleanText("abcdef", 3));
        eq("", AdninApi.cleanText("\ud83d\ude00", 1));
        eq("\ud83d\ude00", AdninApi.cleanText("\ud83d\ude00", 2));

        eq("abc", AdninApi.readBoundedBody(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), 3));
        fail(4, "abcd", "3");
        fail(5, "", "");
        http("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + BOT.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + BOT, true);
        http("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/?key=SHOULD_NOT_SEND\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", false);
        http("HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\nConnection: close\r\n\r\n", false);
        http("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", false);
        errorCategories();
        errorDisplayMessages();
        System.out.println("AdninApiTest: " + checks + " checks passed");
    }

    private static void http(String response, boolean success) throws Exception {
        http(response, success, null);
    }

    private static void http(String response, boolean success, String expectedCode) throws Exception {
        ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        server.setSoTimeout(10000);
        AdninApiTest fixture = new AdninApiTest(server, response);
        Thread thread = new Thread(fixture, "AdninApiTest-fixture");
        thread.setDaemon(true);
        thread.start();
        String target = "http://127.0.0.1:" + server.getLocalPort() + "/api/users?q=<>&page=0";
        try {
            String result = AdninApi.fetchBot(target, "isa5");
            if (!success) throw new AssertionError("HTTP error should fail closed");
            eq("Adnin", result);
        } catch (IOException failure) {
            if (success) throw failure;
            check(!failure.getMessage().contains(target) && !failure.getMessage().contains("SHOULD_NOT_SEND"), "Errors must omit URL and credentials");
            check(!failure.getMessage().contains("DUMMY_PRIVATE_BODY") && failure.getCause() == null, "Transport wrapper must discard external body and exception cause");
            if (expectedCode != null) {
                eq(expectedCode, AdninApi.errorCode(failure));
                if ("player-not-found".equals(expectedCode)) eq("", AdninApi.urchinErrorMessage(AdninApi.errorCode(failure)));
            }
        } finally { thread.join(5000); server.close(); }
        if (fixture.fixtureError != null) throw fixture.fixtureError;
        check(!fixture.capturedRequest.toLowerCase(java.util.Locale.ROOT).contains("x-api-key"), "Bot request must not contain Urchin API key");
        check(fixture.capturedRequest.startsWith("GET /api/users?q=isa5&page=0 HTTP/1.1"), "HTTP request preserves query");
    }

    private static void errorCategories() throws Exception {
        String privateDetail = "https://example.test/?key=DUMMY_PRIVATE_BODY X-API-Key: DUMMY_PRIVATE_BODY";
        eq("request-failed", AdninApi.errorCode(null));
        eq("request-failed", AdninApi.errorCode(new IOException((String) null)));
        eq("request-failed", AdninApi.errorCode(new IOException(privateDetail)));
        eq("request-failed", AdninApi.errorCode(new IOException("API error " + privateDetail)));
        eq("request-failed", AdninApi.errorCode(new IOException("API HTTP 401 " + privateDetail)));
        eq("request-timeout", AdninApi.errorCode(new SocketTimeoutException(privateDetail)));
        eq("dns-failed", AdninApi.errorCode(new UnknownHostException(privateDetail)));
        eq("tls-failed", AdninApi.errorCode(new SSLException(privateDetail)));
        eq("tls-failed", AdninApi.errorCode(new SSLHandshakeException(privateDetail)));
        eq("connection-failed", AdninApi.errorCode(new ConnectException(privateDetail)));
        eq("connection-failed", AdninApi.errorCode(new NoRouteToHostException(privateDetail)));
        eq("request-failed", AdninApi.errorCode(new IOException("outer private failure", new IOException(privateDetail))));
        eq("request-timeout", AdninApi.errorCode(new IOException("API request timed out")));
        eq("response-invalid", AdninApi.errorCode(new IOException("Invalid UTF-8 API response")));
        eq("response-invalid", AdninApi.errorCode(new IOException("Unsupported API content encoding")));
        eq("response-invalid", AdninApi.errorCode(new IOException("API response is too large")));
        eq("configuration-invalid", AdninApi.errorCode(new IOException("Urchin key origin check failed")));
        for (String code : new String[]{"key-missing", "key-invalid", "invalid-player", "authentication-failed", "access-denied",
                "player-not-found", "rate-limited", "upstream-failed", "service-unavailable", "redirect-refused", "request-timeout",
                "dns-failed", "tls-failed", "connection-failed", "response-invalid", "configuration-invalid", "request-failed"}) {
            eq(code, AdninApi.errorCode(new IOException("API error " + code)));
            eq("request-failed", AdninApi.errorCode(new IOException("API error " + code + " DUMMY_PRIVATE_BODY")));
        }
        int[] statuses = {400, 401, 403, 404, 429, 502, 503, 500, 599, 301, 302, 307, 204, 418};
        String[] codes = {"invalid-player", "authentication-failed", "access-denied", "player-not-found", "rate-limited", "upstream-failed",
                "service-unavailable", "service-unavailable", "service-unavailable", "redirect-refused", "redirect-refused", "redirect-refused", "request-failed", "request-failed"};
        for (int i = 0; i < statuses.length; i++) {
            eq(codes[i], AdninApi.errorCode(new IOException("API HTTP " + statuses[i])));
            String body = "{\"error\":\"DUMMY_PRIVATE_BODY\"}";
            http("HTTP/1.1 " + statuses[i] + " Fixture\r\nContent-Type: application/json\r\nContent-Length: " + body.length()
                    + "\r\nConnection: close\r\n\r\n" + body, false, codes[i]);
        }
        http("HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\nConnection: close\r\n\r\n", false, "response-invalid");
        http("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", false, "response-invalid");

        expectUrchinCode(0, "Adnin", "", "key-missing");
        expectUrchinCode(0, "Adnin", null, "key-missing");
        expectUrchinCode(0, "Adnin", " dummy-key", "key-invalid");
        expectUrchinCode(0, "Adnin", "dummy\nkey", "key-invalid");
        expectUrchinCode(0, "Adnin", repeat("x", 513), "key-invalid");
        expectUrchinCode(0, "bad/name", "dummy-key", "invalid-player");
        expectUrchinCode(1, "Adnin", "", "key-missing");
        expectUrchinCode(1, "Adnin", "dummy\rkey", "key-invalid");
        expectUrchinCode(1, "bad/name", "dummy-key", "invalid-player");
        for (String body : new String[]{"[]", "{}", "{", "{\"error\":\"DUMMY_PRIVATE_BODY\"}",
                "{\"uuid\":\"" + UUID + "\",\"tags\":null}",
                "{\"uuid\":\"" + UUID + "\",\"tags\":[{\"tag_type\":\"sniper\",\"reason\":null}]}",
                "{\"uuid\":\"" + UUID + "\",\"tags\":[{\"tag_type\":1,\"reason\":\"DUMMY_PRIVATE_BODY\"}]}",
                "{\"uuid\":\"invalid\",\"tags\":[]}"}) {
            expectUrchinCode(2, body, "", "response-invalid");
            expectUrchinCode(3, body, "", "response-invalid");
        }
        eq(0, AdninApi.parseUrchinTags("{\"uuid\":\"" + UUID + "\",\"tags\":[],\"displayname\":null}").size());
        eq("sniper: valid reason", AdninApi.parseUrchinTags("{\"uuid\":\"" + UUID.replace("-", "")
                + "\",\"tags\":[{\"tag_type\":\"sniper\",\"reason\":\"valid reason\",\"expires_at\":null}]}" ).get(0));
    }

    private static void errorDisplayMessages() {
        String[] codes = {"key-missing", "key-invalid", "invalid-player", "authentication-failed", "access-denied",
                "player-not-found", "rate-limited", "upstream-failed", "service-unavailable", "redirect-refused", "request-timeout",
                "dns-failed", "tls-failed", "connection-failed", "response-invalid", "configuration-invalid", "request-failed"};
        String[] descriptions = {"No API key is configured", "API key format is invalid", "Player lookup failed",
                "Authentication failed (401)", "Access denied (403)", "Player lookup failed: player not found (404)",
                "Request limit reached (429)", "upstream service failed (502)", "server error", "redirect that was not followed",
                "request timed out", "Could not resolve the Urchin API hostname", "Secure TLS communication",
                "Could not connect to the Urchin API", "response was invalid or unsupported", "request configuration is invalid", "Request failed"};
        for (int i = 0; i < codes.length; i++) {
            String message = AdninApi.urchinErrorMessage(codes[i]);
            if ("player-not-found".equals(codes[i])) {
                eq("", message);
                continue;
            }
            String prefix = "[Urchin] \u00a7c";
            check(message.startsWith(prefix), "The original Urchin prefix is followed by red error content");
            check(message.endsWith("\u00a7r"), "Red error content resets its formatting");
            String body = message.substring(prefix.length(), message.length() - 2);
            check(message.contains(descriptions[i]), "Display message explains its fixed category");
            check(body.matches("[\\x20-\\x7E]+"), "Error body is fixed printable English without embedded formatting or controls");
            check(message.contains("eligible game entry will retry"), "Display message describes the next eligible game retry without immediate retries");
            if (codes[i].equals("key-missing") || codes[i].equals("key-invalid") || codes[i].equals("authentication-failed")) {
                check(message.contains("Settings"), "Key and authentication failures direct the user to Settings");
            }
        }
        eq("player-not-found", AdninApi.errorCode(new IOException("API HTTP 404")));
        eq("player-not-found", AdninApi.errorCode(new IOException("API error player-not-found")));
        eq("", AdninApi.urchinErrorMessage(AdninApi.errorCode(new IOException("API HTTP 404"))));
        check(!AdninApi.urchinErrorMessage("invalid-player").isEmpty(), "Only player-not-found is silenced; invalid-player remains visible");
        check(!AdninApi.urchinErrorMessage("invalid-player").contains("400"), "Local validation errors do not claim an HTTP response");
        String generic = "[Urchin] \u00a7cRequest failed. The next eligible game entry will retry.\u00a7r";
        eq(generic, AdninApi.urchinErrorMessage("request-failed"));
        String privateDetail = "https://example.test/?key=DUMMY_PRIVATE_BODY X-API-Key: DUMMY_PRIVATE_BODY";
        for (String unknown : new String[]{null, "", "DUMMY_PRIVATE_BODY", privateDetail, "key-invalid " + privateDetail,
                "authentication-failed\n" + privateDetail, "API HTTP 401", "REQUEST-TIMEOUT", "\u4e2d\u6587",
                "PLAYER-NOT-FOUND", "player-not-found ", "player-not-found\n" + privateDetail,
                "\u00a7a" + privateDetail + "\u00a7r"}) {
            eq(generic, AdninApi.urchinErrorMessage(unknown));
        }
        for (IOException failure : new IOException[]{new IOException(privateDetail), new SocketTimeoutException(privateDetail),
                new UnknownHostException(privateDetail), new SSLHandshakeException(privateDetail), new ConnectException(privateDetail),
                new IOException("API error authentication-failed " + privateDetail), new IOException("API HTTP 401 " + privateDetail),
                new IOException("outer private failure", new IOException(privateDetail))}) {
            String message = AdninApi.urchinErrorMessage(AdninApi.errorCode(failure));
            check(message.startsWith("[Urchin] \u00a7c") && message.endsWith("\u00a7r"),
                    "Sanitized failures retain fixed red-body formatting");
            check(!message.contains("DUMMY_PRIVATE_BODY") && !message.contains("https://") && !message.contains("X-API-Key"),
                    "Sensitive exception details never reach display text");
        }
    }

    /** All fetch fixtures fail validation before any external connection. */
    private static void expectUrchinCode(int operation, String first, String second, String code) throws Exception {
        try {
            if (operation == 0) AdninApi.fetchUrchin(first, second);
            else if (operation == 1) AdninApi.fetchUrchinDetails(first, second);
            else if (operation == 2) AdninApi.parseUrchinTags(first);
            else if (operation == 3) AdninApi.parseUrchinTagDetails(first);
            else throw new AssertionError("Unknown error-code fixture");
            throw new AssertionError("Expected Urchin error " + code);
        } catch (IOException failure) {
            eq(code, AdninApi.errorCode(failure));
            check(!failure.getMessage().contains("DUMMY_PRIVATE_BODY") && failure.getCause() == null, "Urchin errors contain fixed diagnostics only");
        }
    }

    @Override public void run() {
        try {
            Socket client = fixture.accept();
            try {
                client.setSoTimeout(5000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                StringBuilder request = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && line.length() > 0) request.append(line).append('\n');
                capturedRequest = request.toString();
                OutputStream output = client.getOutputStream();
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally { client.close(); }
        } catch (IOException failure) { fixtureError = failure; }
    }

    private static void fail(int operation, String first, String second) throws Exception {
        try {
            if (operation == 0) AdninApi.buildBotUrl(first, second);
            else if (operation == 1) AdninApi.parseBotResponse(first, second);
            else if (operation == 2) AdninApi.buildUrchinUrl(first);
            else if (operation == 3) AdninApi.parseUrchinTags(first);
            else if (operation == 4) AdninApi.readBoundedBody(new ByteArrayInputStream(first.getBytes(StandardCharsets.UTF_8)), Integer.parseInt(second));
            else if (operation == 5) AdninApi.readBoundedBody(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}), 2);
            else if (operation == 6) AdninApi.parseMojangUuid(first, second);
            else if (operation == 7) AdninApi.fetchMojangUuid(first);
            else throw new AssertionError("Unknown test operation");
            throw new AssertionError("Expected failure for operation " + operation);
        } catch (IOException expected) { checks++; }
    }
    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    private static void eq(Object expected, Object actual) { check(expected.equals(actual), "Expected " + expected + " but got " + actual); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
