import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

/** Real offline scoreboard fixtures; no Minecraft instance, JNI or network. */
public final class AdninReplayTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        check(!AdninReplay.isReplay(), "starts outside Replay");
        for (String text : new String[]{"REPLAY", "Replay", "replay", "Watching a Replay",
                "\u00a76R\u00a7ce\u00a7fp\u00a7ll\u00a7ra\u00a7ay", "\u00a7ARePlAy"})
            check(AdninReplay.containsReplay(text), "Replay is case/format insensitive");
        for (String text : new String[]{null, "", "BED WARS", "Re-play", "Re\u00a7zpLay", "repl\u00a7"})
            check(!AdninReplay.containsReplay(text), "unrelated sidebar does not match");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            check(AdninReplay.containsReplay("REPLAY"), "locale-independent matching");
        } finally { Locale.setDefault(original); }

        Scoreboard board = new Scoreboard();
        ScoreObjective normal = board.addScoreObjective("normal", IScoreObjectiveCriteria.DUMMY);
        normal.setDisplayName("BED WARS");
        board.setObjectiveInDisplaySlot(1, normal);
        check(AdninReplay.sidebar(board, "Viewer") == normal, "ordinary sidebar is slot 1");
        check(!AdninReplay.detect(board, normal), "normal empty board is not Replay");
        normal.setDisplayName("\u00a7aReplay");
        check(AdninReplay.detect(board, normal), "sidebar title qualifies");
        normal.setDisplayName("BED WARS");
        board.getValueFromObjective("Replay hidden", normal).setScorePoints(0);
        for (int i = 1; i <= 15; i++) board.getValueFromObjective("Row " + i, normal).setScorePoints(i);
        check(!AdninReplay.detect(board, normal), "sixteenth row outside displayed tail cannot qualify");
        board.getValueFromObjective("#Replay", normal).setScorePoints(99);
        check(!AdninReplay.detect(board, normal), "hidden # entry cannot qualify or displace visible rows");
        board.getValueFromObjective("Replay visible", normal).setScorePoints(16);
        check(AdninReplay.detect(board, normal), "last 15 visible rows qualify");
        board.removeObjectiveFromEntity("Replay visible", normal);
        check(!AdninReplay.detect(board, normal), "removing Replay row clears next observation");

        ScoreObjective teamRows = board.addScoreObjective("teams", IScoreObjectiveCriteria.DUMMY);
        teamRows.setDisplayName("PLAYBACK");
        ScorePlayerTeam lineTeam = board.createTeam("line");
        lineTeam.setNamePrefix("\u00a76Re");
        lineTeam.setNameSuffix("\u00a7clay");
        assignOfflineTeam(board, "p", lineTeam);
        board.getValueFromObjective("p", teamRows).setScorePoints(1);
        check(AdninReplay.detect(board, teamRows), "team prefix, score name and suffix form visible Replay text");
        lineTeam.setNameSuffix("air");
        check(!AdninReplay.detect(board, teamRows), "team text changes are observed");

        ScoreObjective colored = board.addScoreObjective("colored", IScoreObjectiveCriteria.DUMMY);
        colored.setDisplayName("Replay");
        ScorePlayerTeam viewer = board.createTeam("viewer");
        viewer.setChatFormat(EnumChatFormatting.RED);
        assignOfflineTeam(board, "Viewer", viewer);
        board.setObjectiveInDisplaySlot(3 + EnumChatFormatting.RED.getColorIndex(), colored);
        check(AdninReplay.sidebar(board, "Viewer") == colored, "team color sidebar takes display priority");
        check(AdninReplay.detect(board, AdninReplay.sidebar(board, "Viewer")), "selected team title qualifies");
        check(!AdninReplay.detect(board, AdninReplay.sidebar(board, "Other")), "other team sidebar is invisible");
        board.setObjectiveInDisplaySlot(3 + EnumChatFormatting.RED.getColorIndex(), null);
        check(AdninReplay.sidebar(board, "Viewer") == normal, "missing color slot falls back to slot 1");
        board.setObjectiveInDisplaySlot(0, colored);
        check(!AdninReplay.detect(board, AdninReplay.sidebar(board, "Viewer")), "Tab objective cannot qualify");
        board.setObjectiveInDisplaySlot(1, null);
        check(AdninReplay.sidebar(board, "Viewer") == null, "no visible sidebar is absent");
        check(!AdninReplay.detect(board, null), "absent sidebar clears detection");
        check(!AdninReplay.detect(null, normal), "absent scoreboard clears detection");
        check(AdninReplay.sidebar(null, "Viewer") == null, "missing board selection is safe");
        check("UnitPlayer".equals(AdninReplay.displayedAccount("UnitPlayer", "\u00a7cRUnitPlayer")),
                "Team letter cannot become part of the queried account");
        check("UnitPlayer".equals(AdninReplay.displayedAccount("UnitPlayer", "\u00a7cR\u00a7fUnit\u00a7aPlayer")),
                "Formatting within a recorded account preserves its complete name");
        check("RecordedName".equals(AdninReplay.displayedAccount("ReplayBot", "\u00a7c[MVP+] RecordedName")),
                "Synthetic profile can use explicitly displayed account");
        check("UnitPlayer".equals(AdninReplay.displayedAccount("UnitPlayer\u00a7r", "\u00a7aUnitPlayer \u00a7e20")),
                "A display suffix cannot replace an explicitly present normalized profile account");
        check("UnitPlayer".equals(AdninReplay.displayedAccount("UnitPlayer", "\u00a7cR\u00a7fUnitPlayer \u00a7e20")),
                "Team-decorated profile account is retained before a display suffix");
        check(AdninReplay.displayedAccount("UnitPlayer", "\u00a77[Viewer] UnitPlayer").isEmpty(),
                "Viewer is excluded independently of a valid profile name");
        check(AdninReplay.displayedAccount("Suspect", null).isEmpty(), "Anonymous Atlas identity is never resolved as an account");
        check("UnitPlayer".equals(AdninReplay.accountKey("UnitPlayer\u00a7r")), "Replay reset suffix matches native normalized row key");
        check("UnitPlayer".equals(AdninReplay.displayedAccount("\u00a7aUnit\u00a7lPlayer\u00a7r", null)),
                "Valid replay formatting is removed before account validation");
        check(!AdninReplayProfiles.validName(AdninReplay.accountKey("UnitPlayer\u00a7z")), "Invalid formatting is not silently admitted");
        check("XiaoShu_SKY2026".equals(AdninReplay.displayedAccount("XiaoShu_SKY202", "XiaoShu_SKY2026")),
                "Complete observed Tab account takes priority over its truncated profile alias");
        check("XiaoShu_SKY2026".equals(AdninReplay.displayedAccount("XiaoShu_SKY202", "\u00a7fXiaoShu_SKY202\u00a7a6 \u00a7e20")),
                "A formatting boundary before the last digit cannot truncate the account or select its health");
        check("XiaoShu_SKY2026".equals(AdninReplay.displayedAccount("XiaoShu_SKY202", "\u00a7cR\u00a7fXiaoShu_SKY202\u00a7a6 \u00a7e20")),
                "Known team decoration is removed while every observed account character is preserved");
        check("XiaoShu_SKY2026".equals(AdninReplay.displayedAccount("ReplayBot", "\u00a7c[MVP+] XiaoShu_SKY202\u00a7a6 \u00a7e20")),
                "Synthetic aliases also use the complete unambiguous displayed account");
        check("RecordedName".equals(AdninReplay.displayedAccount("ReplayBot", "\u00a7cR\u00a7fRecordedName")),
                "Explicitly formatted team markers remain supported for independent profile aliases");
        check("XiaoShu_SKY2026".equals(AdninReplay.displayedAccount("XiaoShu_SKY202", "\u00a7b[MVP+] \u00a7cR \u00a7fXiaoShu_SKY202\u00a7a6 20")),
                "Rank decoration before a separate team letter cannot hide the complete account");
        check("RandomPlayer".equals(AdninReplay.displayedAccount("RandomPlaye", "\u00a7cR\u00a7fandomPlayer")),
                "A real account's first letter is not removed just because it is a team letter");
        check("XiaoShu_SKY202".equals(AdninReplay.displayedAccount("XiaoShu_SKY202", null)),
                "No unseen suffix is invented when the current Tab has no fuller account");
        check(AdninReplay.displayedAccount("XiaoShu_SKY202", "XiaoShu_SKY2026 OtherAccount").isEmpty(),
                "Multiple distinct displayed accounts are ambiguous and never guessed");
        check("NickFixture".equals(AdninReplay.displayedAccount("NickFixture", "\u00a7aG\u00a7fNickFixture \u00a7e20")),
                "A real nickname stays a nickname when the Tab contains no different complete account");
        check("R".equals(AdninReplay.displayedAccount("R", "R 20")),
                "An exact single-letter account is not discarded as team decoration");
        check("1234".equals(AdninReplay.displayedAccount("1234", "1234 20")),
                "Numeric accounts remain valid before a numeric score suffix");

        Field state = AdninReplay.class.getDeclaredField("replay");
        state.setAccessible(true);
        state.setBoolean(null, true);
        AdninReplay.tick(null);
        check(!AdninReplay.isReplay(), "client/world loss clears cached Replay state");
        state.setBoolean(null, true);
        AdninReplay.clear();
        check(!AdninReplay.isReplay(), "module stop explicitly clears Replay state");
        java.util.Properties diagnostic = new java.util.Properties();
        AdninReplay.diagnostics(diagnostic);
        check(diagnostic.size() == 23, "Replay diagnostics are a fixed small scalar set");
        for (Object value : diagnostic.values()) check(value.toString().matches("[a-z-]+|[0-9]+"),
                "Replay diagnostics never contain account data, URLs or raw errors");
        System.out.println("AdninReplayTest: " + checks + " checks passed; real offline scoreboard title, selected team slot, last-15 visibility, formatting, hidden rows and state clearing; no game or network");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    @SuppressWarnings("unchecked")
    private static void assignOfflineTeam(Scoreboard board, String name, ScorePlayerTeam team) throws Exception {
        // Lunar's addPlayerToTeam invokes live nametag invalidation. Populate
        // its real scoreboard map directly so this fixture never starts Minecraft.
        Field field = Scoreboard.class.getDeclaredField("teamMemberships");
        field.setAccessible(true);
        ((Map<String, ScorePlayerTeam>) field.get(board)).put(name, team);
        team.getMembershipCollection().add(name);
    }
}
