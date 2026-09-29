import java.util.*;

/** Client-thread match snapshots and bounded, session-wide Urchin content cache. */
public final class AdninUrchinCache {
    public static final long SUCCESS_MS = 600000L;
    public static final long FAILURE_MS = 45000L;
    static final long MAX_CONTENT_BYTES = 4L * 1024L * 1024L;
    private final int capacity;
    private long contentBytes;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<String, Entry>(16, 0.75f, true);
    private final Map<String, String> roster = new LinkedHashMap<String, String>();
    private final Map<String, List<String>> visible = new LinkedHashMap<String, List<String>>();
    private final Set<String> pending = new HashSet<String>();
    private long activeToken = Long.MIN_VALUE;

    private static final class Entry {
        final List<String> values;
        final boolean success;
        final long completed;
        final long bytes;
        Entry(List<String> values, boolean success, long completed) {
            this.values = Collections.unmodifiableList(new ArrayList<String>(values));
            this.success = success;
            this.completed = completed;
            long weight = 96L + this.values.size() * 8L;
            for (String value : this.values) if (value != null) weight += 40L + value.length() * 2L;
            this.bytes = weight;
        }
        boolean fresh(long now) {
            long elapsed = now - completed;
            return elapsed >= 0 && elapsed <= (success ? SUCCESS_MS : FAILURE_MS);
        }
    }

    public AdninUrchinCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Positive cache capacity required");
        this.capacity = capacity;
    }

    /** Only an explicit native game-start event can create a new request batch. */
    public Map<String, String> beginMatch(long token, Map<String, String> players, long now) {
        if (token == activeToken) return Collections.emptyMap();
        clearMatch();
        activeToken = token;
        Map<String, String> batch = new LinkedHashMap<String, String>();
        if (players == null) return batch;
        for (Map.Entry<String, String> player : players.entrySet()) {
            String name = player.getKey(), lookup = player.getValue();
            if (name == null || !name.matches("[A-Za-z0-9_]{1,16}") || lookup == null || lookup.isEmpty()) continue;
            String id = identity(lookup);
            roster.put(name.toLowerCase(Locale.ROOT), id);
            Entry cached = entries.get(id);
            if (cached != null && cached.fresh(now)) {
                if (cached.success) visible.put(name.toLowerCase(Locale.ROOT), cached.values);
            } else if (pending.add(id)) {
                batch.put(name, lookup);
            }
        }
        return batch;
    }

    /** Both tagged and empty successful responses are cached; errors have a cooldown. */
    public boolean complete(long token, String lookup, List<String> values, boolean success, long now) {
        String id = identity(lookup);
        if (token != activeToken || !pending.remove(id)) return false;
        Entry result = new Entry(success && values != null ? values : Collections.<String>emptyList(), success, now);
        Entry previous = entries.remove(id);
        if (previous != null) contentBytes -= previous.bytes;
        // Cache complete content, but do not let unusually verbose API reasons
        // turn the 512-entry count limit into tens of MiB of retained heap.
        if (result.bytes <= MAX_CONTENT_BYTES) {
            entries.put(id, result); contentBytes += result.bytes;
        }
        while (entries.size() > capacity || contentBytes > MAX_CONTENT_BYTES) {
            Entry removed = entries.remove(entries.keySet().iterator().next());
            contentBytes -= removed.bytes;
        }
        if (success) {
            for (Map.Entry<String, String> player : roster.entrySet()) {
                if (id.equals(player.getValue())) visible.put(player.getKey(), result.values);
            }
        }
        return true;
    }

    /** Rendering and chat read this snapshot; reading it never expires or fetches data. */
    public Map<String, List<String>> visibleTags() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, List<String>>(visible));
    }

    /** World changes discard match state while preserving cross-match response content. */
    public void clearMatch() {
        activeToken = Long.MIN_VALUE;
        roster.clear(); visible.clear(); pending.clear();
    }

    /** Changing the API key discards data obtained with the previous key. */
    public void clear() { clearMatch(); entries.clear(); contentBytes = 0; }
    public int size() { return entries.size(); }
    long retainedContentBytes() { return contentBytes; }

    private static String identity(String value) {
        String lower = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        String compact = lower.replace("-", "");
        return compact.matches("[0-9a-f]{32}") ? "uuid:" + compact : "name:" + lower;
    }
}
