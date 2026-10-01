import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

/** Minecraft adapter for the Raven-derived checks and Mellow Scaffold port. Client thread only. */
public final class AdninAnticheat {
    public static volatile boolean enabled = false, flagSound = true, autoBlock = true,
        noFall = true, noSlow = true, scaffold = true, legitScaffold = true,
        ignoreTeammates = false, atlasOnly = false, autoReport = false;
    public static volatile int intervalSeconds = 20;
    public static volatile String ignoredPlayers = "";

    private static final AdninAnticheatCore.Engine engine = new AdninAnticheatCore.Engine();
    /* Reuse the per-sample roster set.  A new HashSet for every client tick
       created avoidable GC pressure in busy replays (up to 256 actors). */
    private static final Set<UUID> currentActors = new HashSet<UUID>(256);
    /* The core copies values and never retains a Snapshot. Borrow the cached
       buffer for one tick; a reentrant callback sees an empty cache and gets
       its own buffer rather than changing an outer sample under construction. */
    private static AdninAnticheatCore.Snapshot spareSnapshot;
    private static long snapshotGeneration;
    private static final long CLOCK_START_NANOS = System.nanoTime();
    private static volatile long lastClientBoundPacket;
    private static Object world, localPlayer, connection;
    private static int localTick = Integer.MIN_VALUE;
    private static String settingsKey;
    /* Immutable after publication. The tick path reuses normalized options;
       saving settings never needs the detector monitor or a new lock order. */
    private static volatile SettingsCache settingsCache;
    private static boolean replayContext;
    private static long tickCalls, sampledTicks, flagDecisions, reportDecisions, lastSample;
    private static int worldActors, acceptedActors;
    private static String status = "not-observed";

    private AdninAnticheat() { }

    /** Netty callback: deliberately no game object access, allocation or command send. */
    public static void packetReceived() { lastClientBoundPacket = monotonicMillis(); }

    /** Positive elapsed milliseconds; raw nanoTime may begin negative or wrap. */
    static long monotonicMillis() { return elapsedMillis(CLOCK_START_NANOS, System.nanoTime()); }
    static long elapsedMillis(long start, long current) { return 1L + (current - start) / 1000000L; }

    public static synchronized void shutdown() {
        engine.reset();
        currentActors.clear();
        world = null;
        localPlayer = null;
        connection = null;
        localTick = Integer.MIN_VALUE;
        settingsKey = null;
        settingsCache = null;
        spareSnapshot = null;
        snapshotGeneration++;
        replayContext = false;
        lastClientBoundPacket = 0;
        worldActors = acceptedActors = 0;
        lastSample = 0;
        status = "stopped";
    }

    /** Recover sampling/configuration without bypassing this world's report cooldowns. */
    public static synchronized void resetEvidence() {
        clearSamplingEvidence();
        settingsKey = null;
        status = "evidence-reset";
    }

    /** Retire transient observations while preserving the current world's cooldowns. */
    private static void clearSamplingEvidence() {
        engine.clearEvidence();
        currentActors.clear();
        localTick = Integer.MIN_VALUE;
        lastClientBoundPacket = 0;
        worldActors = acceptedActors = 0;
        lastSample = 0;
    }

    /** Anonymous local counters only; no actor names, UUIDs, commands, or game reads. */
    public static synchronized void diagnostics(Properties p) {
        if (p == null) return;
        p.setProperty("anticheatStatus", status);
        p.setProperty("anticheatEnabled", Boolean.toString(enabled));
        p.setProperty("anticheatReplay", Boolean.toString(replayContext));
        p.setProperty("anticheatTickCalls", Long.toString(tickCalls));
        p.setProperty("anticheatSampledTicks", Long.toString(sampledTicks));
        p.setProperty("anticheatWorldActors", Integer.toString(worldActors));
        p.setProperty("anticheatAcceptedActors", Integer.toString(acceptedActors));
        p.setProperty("anticheatFlagDecisions", Long.toString(flagDecisions));
        p.setProperty("anticheatReportDecisions", Long.toString(reportDecisions));
        p.setProperty("anticheatCooldownEntries", Integer.toString(engine.cooldownPlayers()));
        p.setProperty("anticheatLastSampleAgeMs", Long.toString(lastSample > 0
                ? Math.max(0L, monotonicMillis() - lastSample) : -1L));
    }

    /** Called from the stable game-tick hook; duplicate entity ticks remain harmless. */
    public static synchronized void tick(Minecraft mc) {
        tickCalls++;
        if (mc == null) { shutdown(); status = "no-client"; return; }
        if (!mc.isCallingFromMinecraftThread()) { status = "wrong-thread"; return; }
        if (mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            shutdown();
            status = "no-world-or-connection";
            return;
        }
        boolean replay = AdninReplay.isReplay();
        if (world != mc.theWorld || localPlayer != mc.thePlayer || connection != mc.getNetHandler()
                || replayContext != replay) {
            if (world != mc.theWorld) engine.reset();
            clearSamplingEvidence();
            world = mc.theWorld;
            localPlayer = mc.thePlayer;
            connection = mc.getNetHandler();
            replayContext = replay;
        }
        // This is the native Ingame/Replay predicate, independent of Output
        // options and without the Features monitor. Retire evidence before
        // settings allocation, team/actor traversal, or any delivery path.
        if (!AdninFeatures.outputContextAllowed()) {
            clearSamplingEvidence();
            settingsKey = null;
            status = "outside-game-or-replay";
            return;
        }
        AdninMatchTeams.tick(mc);
        AdninAnticheatCore.Settings cfg = settings();
        String nextSettings = cfg.signature();
        if (!nextSettings.equals(settingsKey)) {
            engine.clearEvidence();
            localTick = Integer.MIN_VALUE;
            settingsKey = nextSettings;
        }
        engine.configure(cfg);
        if (!cfg.enabled || mc.isSingleplayer() && !replay) {
            clearSamplingEvidence();
            status = cfg.enabled ? "singleplayer" : "disabled";
            return;
        }
        int tick = mc.thePlayer.ticksExisted;
        if (tick == localTick) { status = "duplicate-local-tick"; return; }
        if (tick < localTick) engine.clearEvidence();
        localTick = tick;
        long now = monotonicMillis();
        sampledTicks++;
        lastSample = now;
        worldActors = mc.theWorld.playerEntities.size();
        acceptedActors = 0;
        status = replay ? "replay-sampling" : "live-sampling";
        currentActors.clear();
        Set<UUID> current = currentActors;
        int count = 0;
        long leaseGeneration = snapshotGeneration;
        AdninAnticheatCore.Snapshot reusable = spareSnapshot;
        spareSnapshot = null;
        try {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (++count > 256) break;
                if (player == null || player == mc.thePlayer || player.isDead || !player.isEntityAlive()) continue;
                UUID uuid = player.getUniqueID();
                if (uuid == null) continue;
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(uuid);
                boolean currentTab = info != null && info.getGameProfile() != null
                        && uuid.equals(info.getGameProfile().getId());
                String replayName = replay ? AdninReplay.actorName(player) : "";
                boolean replayProfile = replay && AdninAnticheatCore.validPlayerName(replayName);
                if (!currentTab && !replayProfile) continue;
                String name = currentTab ? info.getGameProfile().getName() : player.getName();
                boolean real = currentTab && AdninFeatures.isRealTabProfile(name, uuid);
                boolean suspect = AdninAnticheatCore.isAtlasSuspect(player.getName())
                        || AdninAnticheatCore.isAtlasSuspect(name);
                if (cfg.atlasOnly ? !currentTab || !suspect
                        : suspect || (replay ? !replayProfile : !real)) continue;
                if (replayProfile) name = replayName;
                // Respawn/spectator light-gray nametags never produce evidence
                // for any check, independently of Ignore Teammates. Leaving
                // this actor out of current also retires its partial evidence
                // below, while match teammate identities and cooldowns remain.
                if (AdninMatchTeams.isLightGray(player,
                        replayProfile ? AdninReplay.playerInfo(name) : info, name)) continue;
                acceptedActors++;
                current.add(uuid);
                if (reusable == null) reusable = new AdninAnticheatCore.Snapshot();
                AdninAnticheatCore.Snapshot snapshot = snapshot(mc, player, name, now,
                        currentTab, real, suspect, replay, replayProfile, cfg, reusable);
                AdninAnticheatCore.Alert alert = engine.sample(uuid, player, snapshot, cfg);
                if (alert != null) {
                    flagDecisions++;
                    if (alert.autoReport) reportDecisions++;
                    alert(mc, player, name, alert);
                }
            }
            engine.retainPlayers(current);
        } finally {
            if (reusable != null) {
                reusable.name = "";
                // A stop occurring during a client callback must not republish
                // the pre-stop buffer after shutdown released the cache.
                if (leaseGeneration == snapshotGeneration) spareSnapshot = reusable;
            }
        }
    }

    private static AdninAnticheatCore.Snapshot snapshot(Minecraft mc, EntityPlayer p, String name,
            long now, boolean currentTab, boolean real, boolean suspect,
            boolean replay, boolean replayProfile, AdninAnticheatCore.Settings cfg,
            AdninAnticheatCore.Snapshot s) {
        // Reset every conditional/default field before another actor uses this
        // buffer, including fields whose current adapter path never sets true.
        s.self = s.dead = s.overAir = s.onLadder = s.riding = false;
        s.x = s.y = s.z = 0; s.yaw = 0; s.hurtTime = 0;
        s.name = name == null ? "" : name;
        s.currentTab = currentTab; s.realProfile = real; s.atlasSuspect = suspect;
        s.replay = replay; s.replayProfile = replayProfile;
        s.teammate = cfg.ignoreTeammates && (replayProfile
                ? AdninMatchTeams.isTeammate(name) : AdninMatchTeams.isTeammate(p));
        s.tick = p.ticksExisted; s.now = now; s.lastPacket = lastClientBoundPacket;
        s.sampleTick = localTick;
        s.deltaX = p.posX - p.lastTickPosX;
        s.deltaY = p.posY - p.lastTickPosY;
        s.deltaZ = p.posZ - p.lastTickPosZ;
        s.serverX = p.serverPosX; s.serverY = p.serverPosY; s.serverZ = p.serverPosZ;
        s.pitch = p.rotationPitch; s.swinging = p.isSwingInProgress;
        s.swingProgress = p.swingProgressInt;
        s.blocking = p.isBlocking(); s.sprinting = p.isSprinting();
        s.usingItem = p.isUsingItem(); s.sneaking = p.isSneaking();
        s.holdingBlock = p.getHeldItem() != null && p.getHeldItem().getItem() instanceof ItemBlock;
        s.flying = p.capabilities.isFlying; s.onGround = p.onGround;
        s.inWater = p.isInWater(); s.inLava = p.isInLava();
        s.overVoid = true;
        s.distanceToGround = -1;
        if (cfg.scaffold || cfg.legitScaffold) s.yaw = p.rotationYaw;
        if (cfg.scaffold) {
            s.x = p.posX; s.y = p.posY; s.z = p.posZ;
            s.riding = p.isRiding(); s.hurtTime = p.hurtTime;
        }
        if (engine.noFallCandidate(p.getUniqueID(), p, s, cfg)) {
            s.overVoid = overVoid(mc, s.serverX / 32.0, s.serverY / 32.0, s.serverZ / 32.0);
            s.distanceToGround = distanceToGround(mc, p);
            BlockPos ladder = new BlockPos(p.posX, p.posY - 0.20000000298023224D, p.posZ);
            s.onLadder = p.isOnLadder() || !p.onGround && mc.theWorld.isBlockLoaded(ladder)
                    && mc.theWorld.getBlockState(ladder).getBlock() instanceof BlockLadder;
        }
        return s;
    }

    private static boolean overVoid(Minecraft mc, double x, double y, double z) {
        if (!finite(x) || !finite(y) || !finite(z) || y < 0 || y > 512) return true;
        for (int height = (int) Math.floor(y); height >= 0; height--) {
            BlockPos pos = new BlockPos(x, height, z);
            if (!mc.theWorld.isBlockLoaded(pos)) return true;
            if (!(mc.theWorld.getBlockState(pos).getBlock() instanceof BlockAir)) return false;
        }
        return true;
    }

    private static double distanceToGround(Minecraft mc, EntityPlayer player) {
        if (player.onGround) return 0;
        double y = player.posY;
        if (!finite(y) || y < 0 || y > 512) return -1;
        if (y % 1.0 == 0.0) y--;
        for (int height = (int) Math.floor(y); height >= 0; height--) {
            BlockPos pos = new BlockPos(player.posX, height, player.posZ);
            if (!mc.theWorld.isBlockLoaded(pos)) return -1;
            Block block = mc.theWorld.getBlockState(pos).getBlock();
            Material material = block.getMaterial();
            if (!block.isReplaceable(mc.theWorld, pos)
                    && material != Material.water && material != Material.lava) return y - height - 1;
        }
        return -1;
    }

    static char nameColor(IChatComponent component, String name) {
        return AdninMatchTeams.nameColor(component==null?null:component.getFormattedText(),name);
    }

    private static void alert(Minecraft mc, EntityPlayer player, String name, AdninAnticheatCore.Alert result) {
        String display = player.getDisplayName() == null ? name : player.getDisplayName().getFormattedText();
        // Replay entities may expose a short profile alias. Preserve their
        // decoration only when it contains the complete admitted Tab account.
        if (AdninReplay.isReplay() && display != null && name != null) {
            String plain = display.replaceAll("(?i)\\u00a7[0-9a-fk-or]", "");
            if (!java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(name)
                    + "(?![A-Za-z0-9_])", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(plain).find())
                display = name;
        }
        if (display == null) display = AdninLanguage.text("Unknown");
        display = display.replace('\r', ' ').replace('\n', ' ');
        if (display.length() > 256) display = display.substring(0, 256);
        ChatComponentText message = new ChatComponentText("\u00a77[\u00a7bAdnin\u00a77]\u00a7r "
            + display + "\u00a77" + AdninLanguage.text(" detected for ") + "\u00a7d"
            + AdninLanguage.text(result.check.label));
        AdninFeatures.anticheatGeneratedEvent(message.getFormattedText());
        if (!result.reportCommand.isEmpty()) {
            ChatStyle style = new ChatStyle().setChatClickEvent(
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, result.reportCommand));
            message.appendSibling(new ChatComponentText(" \u00a77[\u00a7cWDR\u00a77]").setChatStyle(style));
        }
        mc.thePlayer.addChatMessage(message);
        if (result.sound) mc.thePlayer.playSound("note.pling", 1.0f, 1.0f);
        if (result.autoReport) mc.thePlayer.sendChatMessage(result.reportCommand);
    }

    public static synchronized void loadSettings(Properties p) {
        if (p == null) p = new Properties();
        enabled = bool(p, "enabled", false); flagSound = bool(p, "flagSound", true);
        autoBlock = bool(p, "autoBlock", true); noFall = bool(p, "noFall", true);
        noSlow = bool(p, "noSlow", true); scaffold = bool(p, "scaffold", true);
        legitScaffold = bool(p, "legitScaffold", true);
        ignoreTeammates = bool(p, "ignoreTeammates", false);
        atlasOnly = bool(p, "atlasOnly", false);
        autoReport = bool(p, "autoReport", false);
        try { intervalSeconds = AdninAnticheatCore.clampInterval(Integer.parseInt(
            p.getProperty("anticheat.intervalSeconds", "20").trim())); }
        catch (NumberFormatException invalid) { intervalSeconds = 20; }
        ignoredPlayers = AdninAnticheatCore.normalizeIgnored(p.getProperty("anticheat.ignoredPlayers", ""));
        // Applying options retires evidence, never the current world's alert/report cooldowns.
        resetEvidence();
    }

    public static void saveSettings(Properties p) {
        if (p == null) return;
        AdninAnticheatCore.Settings s = settings();
        p.setProperty("anticheat.enabled", Boolean.toString(s.enabled));
        p.setProperty("anticheat.flagSound", Boolean.toString(s.flagSound));
        p.setProperty("anticheat.autoBlock", Boolean.toString(s.autoBlock));
        p.setProperty("anticheat.noFall", Boolean.toString(s.noFall));
        p.setProperty("anticheat.noSlow", Boolean.toString(s.noSlow));
        p.setProperty("anticheat.scaffold", Boolean.toString(s.scaffold));
        p.setProperty("anticheat.legitScaffold", Boolean.toString(s.legitScaffold));
        p.setProperty("anticheat.ignoreTeammates", Boolean.toString(s.ignoreTeammates));
        p.setProperty("anticheat.atlasOnly", Boolean.toString(s.atlasOnly));
        // Retire the old option even when saving into a previously loaded Properties object.
        p.remove("anticheat.addEnemies");
        p.setProperty("anticheat.autoReport", Boolean.toString(s.autoReport));
        p.setProperty("anticheat.intervalSeconds", Integer.toString(s.intervalSeconds));
        p.setProperty("anticheat.ignoredPlayers", s.ignoredPlayers);
    }

    private static AdninAnticheatCore.Settings settings() {
        boolean active = enabled, sound = flagSound, block = autoBlock;
        boolean fall = noFall, slow = noSlow, bridge = scaffold, legit = legitScaffold;
        boolean team = ignoreTeammates, atlas = atlasOnly, report = autoReport;
        int interval = AdninAnticheatCore.clampInterval(intervalSeconds);
        String source = ignoredPlayers;
        if (source == null) source = "";
        SettingsCache cached = settingsCache;
        if (cached != null) {
            AdninAnticheatCore.Settings value = cached.value;
            if (value.enabled == active && value.flagSound == sound && value.autoBlock == block
                    && value.noFall == fall && value.noSlow == slow && value.scaffold == bridge
                    && value.legitScaffold == legit && value.ignoreTeammates == team
                    && value.atlasOnly == atlas && value.autoReport == report
                    && value.intervalSeconds == interval && cached.source.equals(source)) return value;
        }
        AdninAnticheatCore.Settings s = new AdninAnticheatCore.Settings();
        s.enabled = active; s.flagSound = sound; s.autoBlock = block;
        s.noFall = fall; s.noSlow = slow; s.scaffold = bridge;
        s.legitScaffold = legit; s.ignoreTeammates = team;
        s.atlasOnly = atlas; s.autoReport = report;
        s.intervalSeconds = interval;
        s.ignoredPlayers = AdninAnticheatCore.normalizeIgnored(source);
        s.signature();
        settingsCache = new SettingsCache(source, s);
        return s;
    }

    private static final class SettingsCache {
        final String source;
        final AdninAnticheatCore.Settings value;
        SettingsCache(String source, AdninAnticheatCore.Settings value) {
            this.source = source; this.value = value;
        }
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty("anticheat." + key);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value.trim())) return true;
        if ("false".equalsIgnoreCase(value.trim())) return false;
        return fallback;
    }
    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static boolean nameCharacter(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }
}
