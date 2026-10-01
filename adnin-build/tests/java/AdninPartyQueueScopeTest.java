import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

/** Real owned scoreboard fixtures, without a Minecraft instance or network. */
public final class AdninPartyQueueScopeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Scoreboard board = new Scoreboard();
        ScoreObjective ordinary = board.addScoreObjective("ordinary", IScoreObjectiveCriteria.DUMMY);
        board.setObjectiveInDisplaySlot(1, ordinary);
        ordinary.setDisplayName("BED WARS");
        check(!allows(board, "Viewer"), "A generic Bed Wars title cannot authorize a query");
        ordinary.setDisplayName("www.hypixel.net");
        check(!allows(board, "Viewer"), "Objective title alone is not a visible official footer");
        ordinary.setDisplayName("BED WARS");
        board.getValueFromObjective("www.hypixel.net", ordinary).setScorePoints(0);
        check(allows(board, "Viewer"), "Visible complete official footer authorizes a proxy connection");
        for (int i = 1; i <= 15; ++i) board.getValueFromObjective("Fixture row " + i, ordinary).setScorePoints(i);
        check(!allows(board, "Viewer"), "Official text outside the visible last 15 rows cannot qualify");
        board.getValueFromObjective("#hypixel.net", ordinary).setScorePoints(99);
        check(!allows(board, "Viewer"), "Hidden hash row cannot qualify as an official visible footer");
        board.getValueFromObjective("www.hypixel.net", ordinary).setScorePoints(16);
        check(allows(board, "Viewer"), "Footer qualifies after moving into the visible rows");
        board.removeObjectiveFromEntity("www.hypixel.net", ordinary);
        check(!allows(board, "Viewer"), "Removing the visible footer revokes scope immediately");

        ScoreObjective teamRows = board.addScoreObjective("teamRows", IScoreObjectiveCriteria.DUMMY);
        board.setObjectiveInDisplaySlot(1, teamRows);
        ScorePlayerTeam line = board.createTeam("footer");
        line.setNamePrefix("\u00a7ewww.hy"); line.setNameSuffix(".net\u00a7r");
        assignOfflineTeam(board, "pixel", line);
        board.getValueFromObjective("pixel", teamRows).setScorePoints(1);
        check(allows(board, "Viewer"), "Team prefix, entry and suffix compose the complete colored official footer");
        line.setNameSuffix(".net.evil\u00a7r");
        check(!allows(board, "Viewer"), "A malicious domain suffix is not accepted as Hypixel branding");
        line.setNameSuffix(".net\u00a7r");

        ScoreObjective colored = board.addScoreObjective("colored", IScoreObjectiveCriteria.DUMMY);
        colored.setDisplayName("BED WARS");
        ScorePlayerTeam viewer = board.createTeam("viewer");
        viewer.setChatFormat(EnumChatFormatting.RED);
        assignOfflineTeam(board, "NickFixture", viewer);
        board.setObjectiveInDisplaySlot(3 + EnumChatFormatting.RED.getColorIndex(), colored);
        check(!allows(board, "NickFixture"), "Hidden ordinary sidebar footer cannot override the player's visible team sidebar");
        check(allows(board, "OtherFixture"), "A player without a colored sidebar uses the visible ordinary slot");
        board.getValueFromObjective("\u00a7EHypixel.net", colored).setScorePoints(1);
        check(allows(board, "NickFixture"), "Selected colored sidebar works with a server-visible Nick name");
        board.setObjectiveInDisplaySlot(3 + EnumChatFormatting.RED.getColorIndex(), null);
        check(allows(board, "NickFixture"), "Absent colored sidebar falls back to the ordinary visible sidebar");
        board.setObjectiveInDisplaySlot(0, teamRows);
        board.setObjectiveInDisplaySlot(1, null);
        check(!allows(board, "Viewer"), "Footer in Tab-only objective cannot authorize the sidebar gate");
        check(!allows(null, "Viewer"), "Missing scoreboard fails closed");

        Scoreboard clipped = new Scoreboard();
        ScoreObjective clippedRows = clipped.addScoreObjective("clipped", IScoreObjectiveCriteria.DUMMY);
        clipped.setObjectiveInDisplaySlot(1, clippedRows);
        clipped.getValueFromObjective("Bottom row", clippedRows).setScorePoints(0);
        clipped.getValueFromObjective("www.hypixel.net", clippedRows).setScorePoints(1);
        for (int i = 2; i < 16; ++i) clipped.getValueFromObjective("Visible row " + i, clippedRows).setScorePoints(i);
        check(allows(clipped, "Viewer"), "Footer inside the last 15 filtered rows initially qualifies");
        clipped.getValueFromObjective("#hidden", clippedRows).setScorePoints(99);
        check(!allows(clipped, "Viewer"), "Original row count clips an extra footer when more than 15 filtered rows exist");
        clipped.removeObjectiveFromEntity("Bottom row", clippedRows);
        check(allows(clipped, "Viewer"), "At exactly 15 filtered rows hidden entries do not trigger renderer clipping");
        clipped.getValueFromObjective("Bottom row", clippedRows).setScorePoints(0);
        for (int i = 0; i < 15; ++i) clipped.getValueFromObjective("#hidden" + i, clippedRows).setScorePoints(100 + i);
        check(!allows(clipped, "Viewer"), "Enough hidden entries can clip every candidate under the actual renderer rule");

        supplementaryRowKeys();
        bmpRowKeys();
        pregameScope();

        Scoreboard oversized = new Scoreboard();
        ScoreObjective many = oversized.addScoreObjective("many", IScoreObjectiveCriteria.DUMMY);
        oversized.setObjectiveInDisplaySlot(1, many);
        for (int i = 0; i < 512; ++i) oversized.getValueFromObjective("Row" + i, many).setScorePoints(i);
        oversized.getValueFromObjective("www.hypixel.net", many).setScorePoints(999);
        check(!allows(oversized, "Viewer"), "Oversize untrusted scoreboard is rejected before row traversal and formatting");
        System.out.println("AdninPartyQueueScopeTest: " + checks
            + " checks passed; real sidebar selection, complete footer, team formatting, last-15 visibility and bounded input;"
            + " no game instance, private settings, native library or network");
    }

    private static boolean allows(Scoreboard board, String name) { return AdninPartyQueueQuery.hasHypixelSidebar(board, name); }
    private static void check(boolean value, String why) { ++checks; if (!value) throw new AssertionError(why); }

    private static void pregameScope() throws Exception {
        for (String phase : new String[]{"Waiting...", "\u00a7eWaiting...", "Waiting\u2026", "Starting in 20s", "Starting in 0:10", "Starting in 0s"}) {
            Scoreboard board = waitingBoard("\u00a7eBED WARS", "\u00a7fPlayers: \u00a7a12/16", phase);
            check(waiting(board), "A bounded BedWars roster and current wait/countdown authorize pregame: " + phase);
        }
        check(waiting(waitingBoard("\u8d77\u5e8a\u6218\u4e89", "\u73a9\u5bb6\uff1a 8/16", "\u7b49\u5f85\u4e2d...")), "Simplified Chinese waiting evidence is supported");
        check(waiting(waitingBoard("\u8d77\u5e8a\u6230\u722d", "\u73a9\u5bb6: 8/16", "\u7b49\u5f85\u4e2d\u2026")), "Traditional Chinese waiting evidence is supported");
        for (String title : new String[]{"SKYWARS", "REPLAY", "BED WARS REPLAY", "HYPIXEL", "www.hypixel.net", "BED WARS extra"})
            check(!waiting(waitingBoard(title, "Players: 12/16", "Waiting...")), "Other games and Replay titles are rejected");
        for (String row : new String[]{"Your Level: 100", "Players: 1200", "Players: 0/16", "Players: 17/16", "Players: 1/0", "Players: 1/999", "Players: 1/16 extra", "Players: 1//16", "Players: -1/16", "Players: 1/16/2"})
            check(!waiting(waitingBoard("BED WARS", row, "Waiting...")), "Lobby/statistic/malformed counts cannot authorize a query");
        for (String phase : new String[]{"Diamond II in 5:00", "Waiting", "Not Waiting...", "Starting in tomorrow", "Starting in 99:99", "Starting in 4:10", "Starting in 10s extra", "Starting in -1s", "Starting in 999s"})
            check(!waiting(waitingBoard("BED WARS", "Players: 12/16", phase)), "Non-pregame or malformed countdowns fail closed");
        Scoreboard replay = waitingBoard("BED WARS", "Players: 12/16", "Waiting...");
        ScoreObjective objective = replay.getObjectiveInDisplaySlot(1);
        replay.getValueFromObjective("Replay: paused", objective).setScorePoints(4);
        check(!waiting(replay), "Replay row rejects otherwise positive stale pregame evidence");
        replay.removeObjectiveFromEntity("Replay: paused", objective);
        replay.getValueFromObjective("\u56de\u653e: paused", objective).setScorePoints(4);
        check(!waiting(replay), "Localized Replay row also rejects stale pregame evidence");
        for (String row : new String[]{"Lobby: bedwarslobby18", "Tokens: 100", "Diamond II in 5:00", "Emerald II in 5:00", "Time Left: 5:00", "Players Left: 4"}) {
            Scoreboard mixed = waitingBoard("BED WARS", "Players: 12/16", "Waiting...");
            mixed.getValueFromObjective(row, mixed.getObjectiveInDisplaySlot(1)).setScorePoints(4);
            check(!waiting(mixed), "Known lobby or active rows override transient stale pregame evidence");
        }
        Scoreboard clipped = waitingBoard("BED WARS", "Players: 12/16", "Waiting...");
        ScoreObjective clippedRows = clipped.getObjectiveInDisplaySlot(1);
        for (int i = 0; i < 15; ++i) clipped.getValueFromObjective("Visible " + i, clippedRows).setScorePoints(10 + i);
        check(!waiting(clipped), "Offscreen pregame evidence does not authorize a query");

        Scoreboard keyed = waitingBoard("BED WARS", "Players: 12/16", "Waiting...");
        ScoreObjective keyedRows = keyed.getObjectiveInDisplaySlot(1);
        keyed.removeObjectiveFromEntity("Players: 12/16", keyedRows);
        ScorePlayerTeam team = keyed.createTeam("count");
        team.setNamePrefix("\u00a7fPlayers: \u00a7a12/"); team.setNameSuffix("\u00a7a16");
        String key = "\uD83C\uDF82";
        assignOfflineTeam(keyed, key, team); keyed.getValueFromObjective(key, keyedRows).setScorePoints(2);
        check((AdninPartyQueueQuery.inspectSidebar(keyed, "Viewer", new FakeGlyphs()) & AdninPartyQueueQuery.SCOPE_WAITING) != 0,
            "Waiting evidence composes a zero-width separate team row key");
        check(!waiting(keyed), "Missing font evidence does not strip an unusual row key");
        FakeGlyphs visible = new FakeGlyphs(); visible.high = 2;
        check((AdninPartyQueueQuery.inspectSidebar(keyed, "Viewer", visible) & AdninPartyQueueQuery.SCOPE_WAITING) == 0,
            "Visible supplementary glyphs cannot disappear from phase evidence");
        Scoreboard noFooter = waitingBoard("BED WARS", "Players: 12/16", "Waiting...");
        noFooter.removeObjectiveFromEntity("www.hypixel.net", noFooter.getObjectiveInDisplaySlot(1));
        int facts = AdninPartyQueueQuery.inspectSidebar(noFooter, "Viewer", null);
        check((facts & AdninPartyQueueQuery.SCOPE_WAITING) != 0 && (facts & AdninPartyQueueQuery.SCOPE_HYPIXEL) == 0,
            "Queue phase and server branding remain independent facts");
    }

    private static Scoreboard waitingBoard(String title, String players, String phase) {
        Scoreboard board = new Scoreboard();
        ScoreObjective objective = board.addScoreObjective("pregame", IScoreObjectiveCriteria.DUMMY);
        objective.setDisplayName(title); board.setObjectiveInDisplaySlot(1, objective);
        board.getValueFromObjective("www.hypixel.net", objective).setScorePoints(0);
        board.getValueFromObjective(players, objective).setScorePoints(1);
        board.getValueFromObjective(phase, objective).setScorePoints(2);
        return board;
    }

    private static boolean waiting(Scoreboard board) {
        return (AdninPartyQueueQuery.inspectSidebar(board, "Viewer", null) & AdninPartyQueueQuery.SCOPE_WAITING) != 0;
    }

    private static void supplementaryRowKeys() throws Exception {
        String rowKey = "\uD83C\uDF82";
        check(allowsTeamLine("\u00a7ewww.hypixel.ne", rowKey, "\u00a7et"),
            "Observed Lunar footer with one invisible supplementary row key qualifies");
        check(allowsTeamLine("\u00a7ehypixel", "\uD83D\uDE00", ".net\u00a7r"),
            "One complete supplementary key can separate a complete official footer");
        for (String key : new String[]{"x", "!", " ", "\u200B", "x" + rowKey, rowKey + "x", rowKey + rowKey,
                "\uD83C", "\uDF82", "\uDF82\uD83C", "\uD83C\uD83C", "\u00a70" + rowKey + "\u00a7r"}) {
            check(!allowsTeamLine("www.hypixel.ne", key, "t"),
                "Visible, malformed or compound row keys are not silently removed");
        }
        check(!allowsTeamLine("www.hypixel.net.evil", rowKey, ".example"),
            "Removing a supplementary key cannot authorize an unrelated domain");
        check(!allowsTeamLine("join www.hypixel.ne", rowKey, "t"),
            "Supplementary keys do not loosen the complete footer boundary");
        check(!allowsTeamLine("www.hypixel.ne" + rowKey, rowKey, "t"),
            "Supplementary characters inside a team prefix must remain intact");
        check(!allowsTeamLine("www.hypixel.ne", rowKey, "t" + rowKey),
            "Supplementary characters inside a team suffix must remain intact");
        check(!allowsTeamLine("www.hypixel.ne", rowKey, "t", null),
            "Absent current-font evidence fails closed for a supplementary row key");
        FakeGlyphs zero = new FakeGlyphs();
        check(allowsTeamLine("www.hypixel.ne", rowKey, "t", zero) && zero.calls == 2,
            "Only a complete footer candidate checks both individual UTF-16 widths");
        FakeGlyphs visibleHigh = new FakeGlyphs(); visibleHigh.high = 4;
        check(!allowsTeamLine("www.hypixel.ne", rowKey, "t", visibleHigh) && visibleHigh.calls == 1,
            "A custom visible high-surrogate glyph is rejected immediately");
        FakeGlyphs visibleLow = new FakeGlyphs(); visibleLow.low = 4;
        check(!allowsTeamLine("www.hypixel.ne", rowKey, "t", visibleLow) && visibleLow.calls == 2,
            "A custom visible low-surrogate glyph is rejected");
        FakeGlyphs cancel = new FakeGlyphs(); cancel.high = 4; cancel.low = -4;
        check(!allowsTeamLine("www.hypixel.ne", rowKey, "t", cancel),
            "Opposite glyph widths cannot cancel into false invisibility");
        FakeGlyphs missing = new FakeGlyphs(); missing.high = -1; missing.low = -1;
        check(!allowsTeamLine("www.hypixel.ne", rowKey, "t", missing),
            "Missing font widths do not qualify as invisible");
        FakeGlyphs unused = new FakeGlyphs();
        check(allowsTeamLine("www.hy", "pixel", ".net", unused) && unused.calls == 0,
            "An ordinary footer never queries the font");
        check(!allowsTeamLine("www.hypixel.net.evil", rowKey, ".example", unused) && unused.calls == 0,
            "Unrelated domains never query the font");
        check(!allowsTeamLine("www.hypixel.ne", "X", "t", unused) && unused.calls == 0,
            "Visible ASCII keys never query the font or undergo removal");
        Scoreboard unteamed = new Scoreboard();
        ScoreObjective objective = unteamed.addScoreObjective("unjoined", IScoreObjectiveCriteria.DUMMY);
        unteamed.setObjectiveInDisplaySlot(1, objective);
        unteamed.getValueFromObjective("www.hypixel.ne" + rowKey + "t", objective).setScorePoints(1);
        check(!allows(unteamed, "Viewer"), "An unteamed raw footer never undergoes global Unicode removal");
    }

    private static void bmpRowKeys() throws Exception {
        String rowKey = "\u26bd";
        check(allowsTeamLine("\u00a7ewww.hypixel.ne", rowKey, "\u00a7et"),
            "A witnessed zero-width Lunar BMP-symbol row key also composes a complete footer");
        check(!allowsTeamLine("www.hypixel.ne", rowKey, "t", null),
            "A BMP symbol requires current-font proof before omission");
        AdninPartyQueueQuery.GlyphWidth visible = new AdninPartyQueueQuery.GlyphWidth() {
            public int width(char value) { return 4; }
        };
        check(!allowsTeamLine("www.hypixel.ne", rowKey, "t", visible),
            "A visible resource-pack BMP symbol remains in the footer");
        for (String phase : new String[]{"Waiting...", "Starting in 20s", "Starting in 0:10"}) {
            Scoreboard board = waitingBoard("BED WARS", "Players: 12/16", phase);
            ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
            board.removeObjectiveFromEntity(phase, objective);
            ScorePlayerTeam team = board.createTeam("phase");
            team.setNamePrefix(phase); team.setNameSuffix("");
            assignOfflineTeam(board, rowKey, team);
            board.getValueFromObjective(rowKey, objective).setScorePoints(2);
            check((AdninPartyQueueQuery.inspectSidebar(board, "Viewer", new FakeGlyphs()) & AdninPartyQueueQuery.SCOPE_WAITING) != 0,
                "Live Lunar BMP-key wait/countdown triggers the automatic-query scope: " + phase);
            check(!waiting(board), "An unproven BMP phase key cannot authorize a query");
            check((AdninPartyQueueQuery.inspectSidebar(board, "Viewer", visible) & AdninPartyQueueQuery.SCOPE_WAITING) == 0,
                "A visible BMP phase key cannot authorize a query");
            team.setNameSuffix(" extra");
            check((AdninPartyQueueQuery.inspectSidebar(board, "Viewer", new FakeGlyphs()) & AdninPartyQueueQuery.SCOPE_WAITING) == 0,
                "Omitting one key never strips visible text from the phase suffix");
        }
        for (String key : new String[]{rowKey + rowKey, rowKey + "x", "\u200b", "\u00a7", "\uD83C", "\uDF82"})
            check(!allowsTeamLine("www.hypixel.ne", key, "t"),
                "Compound, format, control or unpaired-surrogate BMP keys remain intact");
    }

    private static boolean allowsTeamLine(String prefix, String entry, String suffix) throws Exception {
        return allowsTeamLine(prefix, entry, suffix, new FakeGlyphs());
    }

    private static boolean allowsTeamLine(String prefix, String entry, String suffix,
            AdninPartyQueueQuery.GlyphWidth glyphs) throws Exception {
        Scoreboard board = new Scoreboard();
        ScoreObjective objective = board.addScoreObjective("row", IScoreObjectiveCriteria.DUMMY);
        board.setObjectiveInDisplaySlot(1, objective);
        ScorePlayerTeam team = board.createTeam("row");
        team.setNamePrefix(prefix); team.setNameSuffix(suffix);
        assignOfflineTeam(board, entry, team);
        board.getValueFromObjective(entry, objective).setScorePoints(1);
        return AdninPartyQueueQuery.hasHypixelSidebar(board, "Viewer", glyphs);
    }

    private static final class FakeGlyphs implements AdninPartyQueueQuery.GlyphWidth {
        int calls, high, low;
        public int width(char value) {
            ++calls;
            return Character.isHighSurrogate(value) ? high : low;
        }
    }

    @SuppressWarnings("unchecked")
    private static void assignOfflineTeam(Scoreboard board, String name, ScorePlayerTeam team) throws Exception {
        // Lunar's helper may touch live nametag invalidation. Populate only the
        // owned fixture's real membership map, as the existing Replay tests do.
        Field members = Scoreboard.class.getDeclaredField("teamMemberships"); members.setAccessible(true);
        ((Map<String, ScorePlayerTeam>) members.get(board)).put(name, team);
        team.getMembershipCollection().add(name);
    }
}
