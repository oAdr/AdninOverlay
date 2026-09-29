import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLException;

/** Actual Replay API/cache calls through an owned HTTP handler; never opens a socket. */
public final class AdninReplayApiTest {
    private static final String ID = new UUID(0x4000L, 0x8000000000000001L).toString();
    private static final String PRIVATE = "DUMMY_PRIVATE_BODY";
    private static final String PREFIX = "/users/profiles/minecraft/";
    private static final Map<String, Fixture> FIXTURES = new LinkedHashMap<String, Fixture>();
    private static final List<FakeConnection> CONNECTIONS = new ArrayList<FakeConnection>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        found("FixtureFound");
        found("Nick");
        status("Fixture404", 404, "player-not-found", "NICK");
        status("Fixture204", 204, "request-failed", "");
        status("Fixture400", 400, "invalid-player", "");
        status("Fixture401", 401, "authentication-failed", "");
        status("Fixture403", 403, "access-denied", "");
        status("Fixture429", 429, "rate-limited", "");
        status("Fixture500", 500, "service-unavailable", "");
        status("Fixture502", 502, "upstream-failed", "");
        status("Fixture503", 503, "service-unavailable", "");
        status("Fixture302", 302, "redirect-refused", "");
        failed("FixtureTimeout", new SocketTimeoutException(PRIVATE), "request-timeout");
        failed("FixtureDNS", new UnknownHostException(PRIVATE), "dns-failed");
        failed("FixtureTLS", new SSLException(PRIVATE), "tls-failed");
        failed("FixtureConnect", new ConnectException(PRIVATE), "connection-failed");
        failed("FixtureUnknown", new IOException(PRIVATE), "request-failed");
        invalid("FixtureBadJson", "{\"error\":\"" + PRIVATE + "\"");
        invalid("FixtureMismatch", profile("OtherName", ID));
        invalid("FixtureInvalid", profile("FixtureInvalid", new UUID(0, 0).toString()));
        invalid("FixtureSentinel", profile("FixtureSentinel", "NICK"));
        invalid("FixtureEmpty", "");

        // This test has its own JVM. All HTTP(S) connections are intercepted and
        // unexpected addresses fail closed instead of falling back to networking.
        URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {
            public URLStreamHandler createURLStreamHandler(String protocol) {
                if (!"https".equals(protocol) && !"http".equals(protocol)) return null;
                return new URLStreamHandler() {
                    protected URLConnection openConnection(URL url) throws IOException {
                        if (!"https".equals(url.getProtocol()) || !"api.mojang.com".equals(url.getHost())
                                || (url.getPort() != -1 && url.getPort() != 443)
                                || !url.getPath().startsWith(PREFIX) || url.getQuery() != null
                                || url.getRef() != null || url.getUserInfo() != null) {
                            throw new IOException("Offline fixture refused unexpected origin");
                        }
                        Fixture fixture = FIXTURES.get(url.getPath().substring(PREFIX.length()).toLowerCase(java.util.Locale.ROOT));
                        if (fixture == null) throw new IOException("Offline fixture refused unknown account");
                        FakeConnection connection = new FakeConnection(url, fixture);
                        CONNECTIONS.add(connection);
                        return connection;
                    }
                };
            }
        });

        for (Map.Entry<String, Fixture> entry : FIXTURES.entrySet()) {
            String name = entry.getKey();
            Fixture fixture = entry.getValue();
            int before = CONNECTIONS.size();
            try {
                String actual = AdninApi.fetchReplayMojangProfile(name);
                check(!fixture.expected.isEmpty(), "Only a profile or authoritative absence can return normally");
                eq(fixture.expected, actual);
            } catch (IOException failure) {
                check(fixture.expected.isEmpty(), "Confirmed absence and successful profiles do not throw");
                eq(fixture.error, AdninApi.errorCode(failure));
                check(failure.getMessage() == null || !failure.getMessage().contains(PRIVATE),
                        "Replay transport errors discard raw private details");
            }
            eq(before + 1, CONNECTIONS.size());
            final AdninReplayProfiles cache = new AdninReplayProfiles(new AdninReplayProfiles.Resolver() {
                public String resolve(String account) throws IOException {
                    return AdninApi.fetchReplayMojangProfile(account);
                }
            }, false);
            cache.publish(Collections.singletonMap("TabAlias", name));
            before = CONNECTIONS.size();
            eq("", cache.lookup("TabAlias", 0));
            eq(before, CONNECTIONS.size());
            check(cache.resolveOne(0), "Production cache resolves the owned HTTP fixture off the lookup path");
            String expected = "NICK".equals(fixture.expected) ? "NICK"
                    : fixture.expected.isEmpty() ? "" : name.toLowerCase(java.util.Locale.ROOT) + "|" + ID;
            eq(expected, cache.lookup("TabAlias", 1));
            boolean verified = ID.equals(fixture.expected), absent = "NICK".equals(fixture.expected);
            eq(1L, cache.completions());
            eq(verified ? 0L : 1L, cache.failures());
            eq(absent ? 1L : 0L, cache.absences());
            eq(verified ? "none" : fixture.error, cache.lastError());
            check(!cache.lastError().contains(PRIVATE) && !cache.lastError().contains("://"),
                    "Cache diagnostics contain only fixed categories");
            cache.publish(Collections.<String, String>emptyMap());
            eq("", cache.lookup("TabAlias", 2));
        }

        // The ordinary Bot account API keeps throwing on 404, and shared HTTP
        // classifications remain unchanged for Urchin and other callers.
        try {
            AdninApi.fetchMojangUuid("Fixture404");
            throw new AssertionError("Ordinary account API must not return the Replay sentinel");
        } catch (IOException failure) { eq("player-not-found", AdninApi.errorCode(failure)); }
        eq("request-failed", AdninApi.errorCode(new IOException("API HTTP 204")));
        int before = CONNECTIONS.size();
        try {
            AdninApi.fetchReplayMojangProfile("bad/name");
            throw new AssertionError("Invalid names must be rejected before HTTP");
        } catch (IOException failure) { eq("invalid-player", AdninApi.errorCode(failure)); }
        eq(before, CONNECTIONS.size());

        for (FakeConnection connection : CONNECTIONS) {
            eq("GET", connection.getRequestMethod());
            eq(null, connection.getRequestProperty("X-API-Key"));
            eq(null, connection.getRequestProperty("Authorization"));
            eq(AdninApi.CONNECT_TIMEOUT_MS, connection.getConnectTimeout());
            check(connection.getReadTimeout() > 0
                    && connection.getReadTimeout() <= AdninApi.READ_TIMEOUT_MS, "Read timeout remains bounded");
            check(!connection.getInstanceFollowRedirects(), "Replay cannot follow a redirect to another service");
            check(connection.disconnected, "Every Replay request disconnects");
            eq(1, connection.responseCalls);
            eq(connection.fixture.status == 200 && connection.fixture.failure == null ? 1 : 0, connection.bodyReads);
            check(connection.bodyReads == 0 || connection.bodyClosed, "Every opened response body is closed");
        }
        System.out.println("AdninReplayApiTest: " + checks
                + " checks passed; actual API/cache behind fake HTTP, no network, game or settings");
    }

    private static String profile(String name, String id) {
        return "{\"name\":\"" + name + "\",\"id\":\"" + id.replace("-", "") + "\"}";
    }
    private static void found(String name) {
        FIXTURES.put(name.toLowerCase(java.util.Locale.ROOT), new Fixture(200, profile(name, ID), null, "", ID));
    }
    private static void status(String name, int status, String error, String result) {
        FIXTURES.put(name.toLowerCase(java.util.Locale.ROOT), new Fixture(status, "{\"error\":\"" + PRIVATE + "\"}", null, error, result));
    }
    private static void failed(String name, IOException failure, String error) {
        FIXTURES.put(name.toLowerCase(java.util.Locale.ROOT), new Fixture(200, "", failure, error, ""));
    }
    private static void invalid(String name, String body) {
        FIXTURES.put(name.toLowerCase(java.util.Locale.ROOT), new Fixture(200, body, null, "response-invalid", ""));
    }
    private static void check(boolean value, String reason) {
        checks++; if (!value) throw new AssertionError(reason);
    }
    private static void eq(Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static final class Fixture {
        final int status;
        final byte[] body;
        final IOException failure;
        final String error, expected;
        Fixture(int status, String body, IOException failure, String error, String expected) {
            this.status = status; this.body = body.getBytes(StandardCharsets.UTF_8);
            this.failure = failure; this.error = error; this.expected = expected;
        }
    }
    private static final class FakeConnection extends HttpURLConnection {
        final Fixture fixture;
        int responseCalls, bodyReads;
        boolean disconnected, bodyClosed;
        FakeConnection(URL url, Fixture fixture) { super(url); this.fixture = fixture; }
        public int getResponseCode() throws IOException {
            responseCalls++;
            if (fixture.failure != null) throw fixture.failure;
            return fixture.status;
        }
        public long getContentLengthLong() { return fixture.body.length; }
        public String getContentEncoding() { return null; }
        public InputStream getInputStream() {
            bodyReads++;
            return new ByteArrayInputStream(fixture.body) {
                public void close() throws IOException { bodyClosed = true; super.close(); }
            };
        }
        public void connect() { }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; }
    }
}
