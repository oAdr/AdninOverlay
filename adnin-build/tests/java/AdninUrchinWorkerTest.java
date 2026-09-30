import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;

/** Runs the real API client and Features worker behind an offline URL handler. */
public final class AdninUrchinWorkerTest {
    private static final String KEY = "fixture-key";
    private static final String PRIVATE = "DUMMY_PRIVATE_BODY";
    private static final String UUID = "12345678-1234-4abc-8def-123456789abc";
    private static final Map<String, Fixture> FIXTURES = new LinkedHashMap<String, Fixture>();
    private static int checks;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        add("Fixture401", 401, "authentication-failed");
        add("Fixture404", 404, "player-not-found");
        add("Fixture429", 429, "rate-limited");
        add("Fixture502", 502, "upstream-failed");
        FIXTURES.put("FixtureTLS", new Fixture(200, "", new SSLException("https://api.urchin.gg/?key=" + PRIVATE), "tls-failed"));
        FIXTURES.put("FixtureBadJson", new Fixture(200, "{\"error\":\"" + PRIVATE + "\"", null, "response-invalid"));
        FIXTURES.put("FixtureEmpty", new Fixture(200, "{\"uuid\":\"" + UUID + "\",\"displayname\":null,\"tags\":[]}", null, ""));
        FIXTURES.put("FixtureTags", new Fixture(200, "{\"uuid\":\"" + UUID + "\",\"tags\":["
                + "{\"tag_type\":\"sniper\",\"reason\":\"Fixture reason\",\"hide_username\":true,\"added_by_username\":\"" + PRIVATE + "\"},"
                + "{\"tag_type\":\"closet_cheater\",\"reason\":\"\",\"expires_at\":null}]}", null, "",
                "sniper: Fixture reason", "closet_cheater"));

        // The test runs in its own JVM because a URL handler factory is global.
        // Every HTTP(S) request is handled locally or rejected, never delegated.
        URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {
            @Override public URLStreamHandler createURLStreamHandler(String protocol) {
                if (!"https".equals(protocol) && !"http".equals(protocol)) return null;
                return new URLStreamHandler() {
                    @Override protected URLConnection openConnection(URL url) throws IOException {
                        if (!"https".equals(url.getProtocol()) || !"api.urchin.gg".equals(url.getHost())
                                || (url.getPort() != -1 && url.getPort() != 443)
                                || !"/v3/player/tags".equals(url.getPath())
                                || url.getUserInfo() != null || url.getRef() != null) {
                            throw new IOException("Offline fixture refused an unexpected origin");
                        }
                        String query = url.getQuery();
                        Fixture fixture = query != null && query.startsWith("player=") ? FIXTURES.get(query.substring(7)) : null;
                        if (fixture == null) throw new IOException("Offline fixture refused an unexpected player");
                        if (fixture.connection != null) throw new IOException("Offline fixture refused a repeated request");
                        fixture.connection = new FakeHttpURLConnection(url, fixture);
                        return fixture.connection;
                    }
                };
            }
        });

        Class<?> features = Class.forName("AdninFeatures", true, AdninUrchinWorkerTest.class.getClassLoader());
        Field generation = field(features, "generation"), currentMatch = field(features, "currentMatch");
        Field nativeActive = field(features, "nativeGameActive");
        AtomicLong starts = (AtomicLong) field(features, "matchStarts").get(null);
        Field settingsPath = field(features, "settingsPath");
        BlockingQueue<String[]> requests = (BlockingQueue<String[]>) field(features, "requests").get(null);
        BlockingQueue<String[]> results = (BlockingQueue<String[]>) field(features, "results").get(null);
        check(settingsPath.get(null) == null, "No real settings path is initialized");
        check(requests.isEmpty() && results.isEmpty(), "The worker starts with empty queues");
        check(!field(features, "initialized").getBoolean(null), "The game integration remains uninitialized");
        int oldGeneration = generation.getInt(null);
        long oldMatch = currentMatch.getLong(null);
        long oldStarts = starts.get();
        boolean oldActive = nativeActive.getBoolean(null);
        long oldSucceeded = field(features, "urchinSucceeded").getLong(null);
        long oldFailed = field(features, "urchinFailed").getLong(null);
        long oldCompleted = field(features, "apiCompleted").getLong(null);
        final AtomicReference<Throwable> crashed = new AtomicReference<Throwable>();
        Thread worker = new Thread(new AdninFeatures(), "AdninUrchinWorkerTest-offline");
        worker.setDaemon(true);
        worker.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable failure) { crashed.set(failure); }
        });
        try {
            generation.setInt(null, 71);
            currentMatch.setLong(null, 93L);
            starts.set(93L); nativeActive.setBoolean(null, true);
            worker.start();
            for (Map.Entry<String, Fixture> entry : FIXTURES.entrySet()) {
                String player = entry.getKey();
                Fixture fixture = entry.getValue();
                String rosterName = "Visible" + player.substring(7);
                check(requests.offer(new String[]{"71", "urchin", rosterName, player, KEY, "93"}), "The six-field fixture job enters the worker");
                String[] result = results.poll(5, TimeUnit.SECONDS);
                check(result != null, "The real worker returns a result within its request pacing window");
                eq(6 + fixture.tags.length, result.length);
                eq("71", result[0]);
                eq("urchin", result[1]);
                eq(rosterName, result[2]);
                eq(fixture.code, result[3]);
                eq(player, result[4]);
                eq("93", result[5]);
                eq(Arrays.asList(fixture.tags), Arrays.asList(Arrays.copyOfRange(result, 6, result.length)));
                for (String value : result) {
                    check(!value.contains(KEY) && !value.contains(PRIVATE) && !value.contains("https://")
                            && !value.contains("X-API-Key"), "The worker protocol contains no credentials, raw URL, or private error data");
                }
                eq(fixture.code.isEmpty() ? "none" : fixture.code, field(features, "urchinLastError").get(null));
                check(settingsPath.get(null) == null, "The worker keeps settings and diagnostic file access disabled");
                FakeHttpURLConnection connection = fixture.connection;
                check(connection != null && connection.responseCalls == 1, "The worker performs exactly one real API-client attempt per fixture");
                eq("GET", connection.getRequestMethod());
                eq(KEY, connection.getRequestProperty("X-API-Key"));
                check(!connection.getInstanceFollowRedirects(), "The real API client disables redirects");
                check(connection.disconnected, "The real API client disconnects after success and failure");
                eq(fixture.status == 200 && fixture.failure == null ? 1 : 0, connection.bodyReads);
                check(connection.bodyReads == 0 || connection.bodyClosed,
                        "Every opened API response stream is closed");
            }
        } finally {
            worker.interrupt();
            worker.join(3000);
            generation.setInt(null, oldGeneration);
            currentMatch.setLong(null, oldMatch);
            starts.set(oldStarts); nativeActive.setBoolean(null, oldActive);
            requests.clear();
            results.clear();
        }
        check(!worker.isAlive(), "The daemon exits promptly on interruption");
        check(crashed.get() == null, "The worker has no uncaught failure");
        check(settingsPath.get(null) == null, "No real settings path was created");
        check(!field(features, "initialized").getBoolean(null), "The test never initialized Minecraft");
        eq(oldSucceeded + 2L, field(features, "urchinSucceeded").getLong(null));
        eq(oldFailed + 6L, field(features, "urchinFailed").getLong(null));
        eq(oldCompleted + 8L, field(features, "apiCompleted").getLong(null));
        eq("none", field(features, "urchinLastError").get(null));
        System.out.println("AdninUrchinWorkerTest: " + checks + " checks passed; real worker preserves HTTP/TLS/parse failures and recovers with empty and populated tags, without network or game initialization");
    }

    private static void add(String player, int status, String code) {
        FIXTURES.put(player, new Fixture(status, "{\"error\":\"" + PRIVATE + "\"}", null, code));
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }

    private static void eq(Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static final class Fixture {
        final int status;
        final byte[] body;
        final IOException failure;
        final String code;
        final String[] tags;
        volatile FakeHttpURLConnection connection;

        Fixture(int status, String body, IOException failure, String code, String... tags) {
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
            this.failure = failure;
            this.code = code;
            this.tags = tags;
        }
    }

    private static final class FakeHttpURLConnection extends HttpURLConnection {
        private final Fixture fixture;
        volatile int responseCalls, bodyReads;
        volatile boolean disconnected, bodyClosed;

        FakeHttpURLConnection(URL url, Fixture fixture) {
            super(url);
            this.fixture = fixture;
        }

        @Override public int getResponseCode() throws IOException {
            responseCalls++;
            if (fixture.failure != null) throw fixture.failure;
            return fixture.status;
        }

        @Override public long getContentLengthLong() { return fixture.body.length; }
        @Override public String getContentEncoding() { return null; }
        @Override public InputStream getInputStream() {
            bodyReads++;
            return new ByteArrayInputStream(fixture.body) {
                @Override public void close() throws IOException { bodyClosed = true; super.close(); }
            };
        }
        @Override public void connect() { /* No network exists in this fixture. */ }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
    }
}
