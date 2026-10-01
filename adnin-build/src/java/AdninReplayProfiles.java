import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Replay statistics identities and explicit absent-account markers. Never edits profiles or sends chat. */
final class AdninReplayProfiles implements Runnable {
    /** Returns a canonical UUID or exact NICK only for a confirmed account-absent result. */
    interface Resolver { String resolve(String name) throws IOException; }
    static final String NICK = "NICK";
    private static final int LIMIT = 256, CACHE_LIMIT = 512;
    // Verified profiles and authoritative account absence are stable enough to
    // reuse for a full ten minutes. Transport/parse failures are retried much
    // sooner so a temporary API problem cannot suppress a later result.
    private static final long SUCCESS_MS = 600000L, ABSENT_MS = 600000L, FAILURE_MS = 45000L;
    private final Resolver resolver;
    private final boolean automatic;
    private final ArrayDeque<String> queue = new ArrayDeque<String>();
    private final Set<String> pending = new HashSet<String>();
    private final Map<String, String> values = new LinkedHashMap<String, String>();
    private final Map<String, Long> expires = new HashMap<String, Long>();
    private Map<String, String> roster = Collections.emptyMap();
    private Set<String> paused = Collections.emptySet();
    private Map<String,String> pausedValues=Collections.emptyMap();
    private boolean started, stopped;
    private Thread worker;
    // Preserve failed as all completed attempts without a verified UUID; absent is a subset.
    private long requested, completed, failed, absent;
    private String lastError = "none";

    AdninReplayProfiles(Resolver resolver, boolean automatic) {
        this.resolver = resolver; this.automatic = automatic;
    }

    synchronized void publish(Map<String, String> next) {
        publish(next,Collections.<String>emptySet());
    }

    /** Gray-name pauses keep identity/cache state while stopping new queries. */
    synchronized void publish(Map<String, String> next,Set<String> pausedNames) {
        if (stopped) return;
        if (next.isEmpty() && roster.isEmpty() && queue.isEmpty() && pending.isEmpty() && paused.isEmpty()) return;
        Map<String, String> copy = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : next.entrySet()) {
            if (copy.size() >= LIMIT) break;
            if (validName(entry.getKey()) && validName(entry.getValue()))
                copy.put(lower(entry.getKey()), entry.getValue());
        }
        // Native callers may retain the short profile alias or already hold
        // the complete recorded name. Both must address the same current Tab
        // identity and cache entry; a collision never chooses an arbitrary row.
        Map<String, String> aliases = new HashMap<String, String>(copy);
        Set<String> ambiguous = new HashSet<String>();
        for (String name : copy.values()) {
            String key = lower(name);
            String previous = aliases.put(key, name);
            if (previous != null && !previous.equalsIgnoreCase(name)) ambiguous.add(key);
        }
        for (String key : ambiguous) aliases.remove(key);
        roster = aliases;
        Set<String> names = new HashSet<String>();
        for (String name : copy.values()) names.add(lower(name));
        Set<String> nextPaused=new HashSet<String>();
        Map<String,String> nextPausedValues=new HashMap<String,String>();
        if(pausedNames!=null)for(String name:pausedNames) {
            if(nextPaused.size()>=LIMIT)break;
            if(validName(name) && names.contains(lower(name))) {
                String key=lower(name);nextPaused.add(key);
                String value=paused.contains(key)?pausedValues.get(key):values.get(key);
                nextPausedValues.put(key,value==null?"":value);
            }
        }
        paused=nextPaused;pausedValues=nextPausedValues;
        // Cancel departed jobs that have not started. An in-flight marker must
        // survive departure/reentry until that specific request completes.
        for (java.util.Iterator<String> it = queue.iterator(); it.hasNext();) {
            String queued = it.next();
            if (!names.contains(queued) || paused.contains(queued)) { it.remove(); pending.remove(queued); }
        }
    }

    /** Current normalized Tab alias only; observing its mapping never schedules API work. */
    synchronized String accountName(String rawAlias) {
        if (!validName(rawAlias)) return "";
        String name = roster.get(lower(rawAlias));
        return name == null ? "" : name;
    }

    synchronized String lookup(String rawName, long now) {
        if (stopped) return "";
        String name = accountName(rawName);
        if (name.isEmpty()) return "";
        String key = lower(name);
        // A transient gray respawn must not erase an already known UUID/NICK,
        // even when its ordinary refresh TTL expires during that pause. Only
        // the original bounded cache is read; no query or negative marker is
        // created here. Normal TTL behavior resumes when the color returns.
        if(paused.contains(key)) {
            String cached=pausedValues.get(key);return cached==null?"":cached;
        }
        Long until = expires.get(key);
        if (until != null && now < until) return values.get(key);
        if (pending.size() < LIMIT && pending.add(key)) {
            queue.addLast(key); requested++;
            if (automatic && !started) {
                started = true;
                worker = new Thread(this, "Adnin Replay profiles");
                worker.setDaemon(true); worker.start();
            }
            notifyAll();
        }
        return "";
    }

    /** Owned tests call this with a fake resolver; production uses the daemon. */
    boolean resolveOne(long now) {
        String name;
        synchronized (this) { name = stopped ? null : queue.pollFirst(); }
        if (name == null) return false;
        String result = "";
        String error = "none";
        boolean missing = false;
        try {
            String id = resolver.resolve(name);
            if (NICK.equals(id)) {
                result = NICK; missing = true; error = "player-not-found";
            } else {
                UUID uuid = UUID.fromString(id);
                if (uuid.version() == 4 && uuid.variant() == 2
                        && uuid.toString().equalsIgnoreCase(id)) result = name + "|" + uuid.toString();
                else error = "response-invalid";
            }
        } catch (IOException failure) {
            error = AdninApi.errorCode(failure);
        } catch (Exception invalid) {
            error = "response-invalid";
        }
        synchronized (this) {
            pending.remove(name);
            if (stopped) return true;
            values.remove(name); values.put(name, result);
            boolean verified = !missing && !result.isEmpty();
            expires.put(name, now + (verified ? SUCCESS_MS : missing ? ABSENT_MS : FAILURE_MS));
            completed++; if (!verified) failed++;
            if (missing) absent++;
            lastError = error;
            while (values.size() > CACHE_LIMIT) {
                String first = values.keySet().iterator().next();
                values.remove(first); expires.remove(first);
            }
        }
        return true;
    }

    @Override public void run() {
        for (;;) {
            try {
                synchronized (this) {
                    while (!stopped && queue.isEmpty()) wait();
                    if (stopped) return;
                }
                resolveOne(now());
                Thread.sleep(750L);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
        }
    }

    synchronized void shutdown() {
        stopped = true;
        if (worker != null) worker.interrupt();
        worker = null;
        queue.clear(); pending.clear(); values.clear(); expires.clear();
        roster = Collections.emptyMap();
        paused=Collections.emptySet();
        pausedValues=Collections.emptyMap();
        notifyAll();
    }

    synchronized long requests() { return requested; }
    synchronized long completions() { return completed; }
    synchronized long failures() { return failed; }
    synchronized long absences() { return absent; }
    synchronized String lastError() { return lastError; }
    static long now() { return System.nanoTime() / 1000000L; }
    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}")
                && !name.equalsIgnoreCase("Suspect") && !name.equalsIgnoreCase("Viewer")
                && !name.equalsIgnoreCase("Spectator");
    }
    static String lower(String name) { return name == null ? "" : name.toLowerCase(Locale.ROOT); }
}
