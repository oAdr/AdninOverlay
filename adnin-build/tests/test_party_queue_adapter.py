"""Exercise the actual mode-query adapter with owned offline Minecraft fixtures.

No game process, network, personal settings or native library is used. Supplying
--classes tests packaged production bytecode; --source reproduces older source.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = {
    'AdninGui4.java': 'public final class AdninGui4 { public static boolean partyQueueDetector = true; }',
    'AdninFeatures.java': '''public final class AdninFeatures {
        public static boolean active; public static boolean outputContextAllowed() { return active; }
    }''',
    'AdninPacketLog.java': '''public final class AdninPacketLog {
        static final Object TOKEN = new Object();
        static Object observerToken(Object connection) { return TOKEN; }
        public static void install(Object connection) { }
    }''',
    'AdninReplay.java': '''public final class AdninReplay {
        public static net.minecraft.scoreboard.ScoreObjective sidebar(net.minecraft.scoreboard.Scoreboard b, String n) {
            return b == null ? null : b.getObjectiveInDisplaySlot(1);
        }
    }''',
    'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
    public final class Minecraft {
        public static Minecraft current;
        public net.minecraft.client.multiplayer.WorldClient theWorld = new net.minecraft.client.multiplayer.WorldClient();
        public net.minecraft.client.entity.EntityPlayerSP thePlayer = new net.minecraft.client.entity.EntityPlayerSP();
        public net.minecraft.client.gui.FontRenderer fontRendererObj = new net.minecraft.client.gui.FontRenderer();
        public net.minecraft.client.network.NetHandlerPlayClient connection = new net.minecraft.client.network.NetHandlerPlayClient();
        public net.minecraft.client.multiplayer.ServerData server = new net.minecraft.client.multiplayer.ServerData();
        public boolean clientThread = true, singleplayer;
        public Runnable onServerRead;
        public static Minecraft getMinecraft() { return current; }
        public boolean isCallingFromMinecraftThread() { return clientThread; }
        public boolean isSingleplayer() { return singleplayer; }
        public net.minecraft.client.network.NetHandlerPlayClient getNetHandler() { return connection; }
        public net.minecraft.client.multiplayer.ServerData getCurrentServerData() {
            if (onServerRead != null) { Runnable r = onServerRead; onServerRead = null; r.run(); }
            return server;
        }
    }''',
    'net/minecraft/client/multiplayer/ServerData.java': '''package net.minecraft.client.multiplayer;
    public final class ServerData { public String serverIP = "mc.hypixel.net"; }''',
    'net/minecraft/client/multiplayer/WorldClient.java': '''package net.minecraft.client.multiplayer;
    public final class WorldClient {
        public net.minecraft.scoreboard.Scoreboard board = new net.minecraft.scoreboard.Scoreboard();
        public net.minecraft.scoreboard.Scoreboard getScoreboard() { return board; }
    }''',
    'net/minecraft/client/network/NetHandlerPlayClient.java': 'package net.minecraft.client.network; public final class NetHandlerPlayClient { }',
    'net/minecraft/client/gui/FontRenderer.java': '''package net.minecraft.client.gui;
    public final class FontRenderer {
        public int bmpWidth;
        public int getStringWidth(String s) {
            if (s.length() == 1 && s.charAt(0) == '\\u26bd') return bmpWidth;
            return s.length() == 1 && Character.isSurrogate(s.charAt(0)) ? 0 : s.length();
        }
    }''',
    'net/minecraft/client/entity/EntityPlayerSP.java': '''package net.minecraft.client.entity;
    public final class EntityPlayerSP {
        public int calls, failures; public String message; public Runnable onSend;
        public String getName() { return "OwnedFixture"; }
        public void sendChatMessage(String s) {
            if (failures > 0) { failures--; throw new IllegalStateException("Owned sender fixture"); }
            calls++; message = s;
            if (onSend != null) onSend.run();
        }
    }''',
    'net/minecraft/scoreboard/ScoreObjective.java': '''package net.minecraft.scoreboard;
    public final class ScoreObjective { public String title = "BED WARS"; public String getDisplayName() { return title; } }''',
    'net/minecraft/scoreboard/Score.java': '''package net.minecraft.scoreboard;
    public final class Score { final String name; public Score(String n) { name = n; } public String getPlayerName() { return name; } }''',
    'net/minecraft/scoreboard/Team.java': 'package net.minecraft.scoreboard; public class Team { }',
    'net/minecraft/scoreboard/ScorePlayerTeam.java': '''package net.minecraft.scoreboard;
    public final class ScorePlayerTeam extends Team {
        public String prefix = "", suffix = "";
        public static String formatPlayerName(Team t, String s) {
            return t instanceof ScorePlayerTeam ? ((ScorePlayerTeam)t).prefix + s + ((ScorePlayerTeam)t).suffix : s;
        }
    }''',
    'net/minecraft/scoreboard/Scoreboard.java': '''package net.minecraft.scoreboard;
    public final class Scoreboard {
        public ScoreObjective objective = new ScoreObjective();
        public final java.util.List<Score> rows = new java.util.ArrayList<Score>();
        public final java.util.Map<String, ScorePlayerTeam> teams = new java.util.HashMap<String, ScorePlayerTeam>();
        public ScoreObjective getObjectiveInDisplaySlot(int s) { return objective; }
        public java.util.Collection<Score> getSortedScores(ScoreObjective s) { return rows; }
        public ScorePlayerTeam getPlayersTeam(String n) { return teams.get(n); }
        public void set(String... values) { rows.clear(); teams.clear(); for (String s : values) rows.add(new Score(s)); }
    }''',
    'AdninPartyQueueAdapterTest.java': r'''import java.lang.reflect.*;
    import net.minecraft.client.Minecraft;
    import net.minecraft.scoreboard.Scoreboard;
    public final class AdninPartyQueueAdapterTest {
        static int checks;
        static void check(boolean b, String why) { checks++; if (!b) throw new AssertionError(why); }
        static Object policy() throws Exception { Field f = AdninPartyQueueQuery.class.getDeclaredField("POLICY"); f.setAccessible(true); return f.get(null); }
        static void set(String name, Object value) throws Exception { Object p = policy(); Field f = p.getClass().getDeclaredField(name); f.setAccessible(true); f.set(p, value); }
        static void due() throws Exception {
            // Advance only owned policy timestamps; no sleeps or game state writes.
            set("armedAt", System.nanoTime() / 1000000L - 501L);
            set("lastAttemptAt", System.nanoTime() / 1000000L - 5001L);
        }
        static void observe() throws Exception { set("scopeProbed", false); AdninPartyQueueQuery.tick(); }
        static Object response() throws Exception { Field f=AdninPartyQueueQuery.class.getDeclaredField("response"); f.setAccessible(true); return f.get(null); }
        static long sentAt(Object r) throws Exception { Field f=r.getClass().getDeclaredField("sentAt"); f.setAccessible(true); return f.getLong(r); }
        static int mode(Object r) throws Exception { Field f=r.getClass().getDeclaredField("mode"); f.setAccessible(true); return ((java.util.concurrent.atomic.AtomicInteger)f.get(r)).get(); }
        static void waiting(Scoreboard b) { b.set("www.hypixel.net", "Map: Owned", "Players: 12/16", "Waiting...", "Mode: Doubles"); }
        static void bmpWaiting(Scoreboard b, String phase) {
            b.set("www.hypixel.net", "Map: Owned", "Players: 12/16", "\u26bd", "Mode: Doubles");
            net.minecraft.scoreboard.ScorePlayerTeam t = new net.minecraft.scoreboard.ScorePlayerTeam();
            t.prefix = phase; b.teams.put("\u26bd", t);
        }
        static void lobby(Scoreboard b) { b.set("www.hypixel.net", "Your Level: 200", "Coins: 10000", "Tokens: 100", "Lobby: bedwarslobby18"); }
        static void sendNow(Minecraft mc) throws Exception {
            observe(); due(); observe();
            check(mc.thePlayer.calls == 1 && "/locraw".equals(mc.thePlayer.message), "Exactly one production-adapter mode query");
        }
        public static void main(String[] args) throws Exception {
            final Minecraft mc = new Minecraft(); Minecraft.current = mc;
            mc.thePlayer.onSend = new Runnable() { public void run() {
                AdninPartyQueueQuery.observeMode(AdninPacketLog.TOKEN,
                    "{\"server\":\"mini123A\",\"gametype\":\"BEDWARS\",\"mode\":\"eight_two\"}", System.nanoTime()/1000000L);
            }};
            final Scoreboard board = mc.theWorld.board;
            String mode = args[0];
            if (mode.equals("lobby") || mode.equals("relay-lobby")) {
                if (mode.startsWith("relay")) mc.server.serverIP = "relay.invalid";
                lobby(board); observe(); due(); observe();
                check(mc.thePlayer.calls == 0, "A BED WARS lobby with official footer must never send locraw");
                waiting(board); sendNow(mc);
            } else if (mode.equals("waiting") || mode.equals("relay-waiting")) {
                if (mode.startsWith("relay")) mc.server.serverIP = "relay.invalid";
                waiting(board); sendNow(mc); due(); observe();
                check(mc.thePlayer.calls == 1, "Stable context never repeats the automatic query");
            } else if (mode.equals("bmp-waiting") || mode.equals("bmp-countdown") || mode.equals("relay-bmp-waiting")) {
                if (mode.startsWith("relay")) mc.server.serverIP = "relay.invalid";
                bmpWaiting(board, mode.equals("bmp-countdown") ? "Starting in 20s" : "Waiting...");
                sendNow(mc);
                check(AdninPartyQueueQuery.pollMode() == 2, "BMP-key queue accepts the response and hands the verified mode to native");
                due(); observe();
                check(mc.thePlayer.calls == 1, "BMP-key queue never repeats a completed mode query");
                lobby(board); observe();
                check(response() == null, "Leaving BMP-key pregame retires the completed response");
            } else if (mode.equals("bmp-visible") || mode.equals("bmp-becomes-visible")) {
                bmpWaiting(board, "Waiting...");
                if (mode.equals("bmp-visible")) mc.fontRendererObj.bmpWidth = 4;
                observe(); due();
                mc.fontRendererObj.bmpWidth = 4; AdninPartyQueueQuery.tick();
                check(mc.thePlayer.calls == 0, "Visible BMP glyph at observation or immediate send cannot authorize a query");
                mc.fontRendererObj.bmpWidth = 0; observe(); due(); observe();
                check(mc.thePlayer.calls == 1, "Restoring witnessed zero-width BMP rendering permits a bounded retry");
            } else if (mode.equals("lost-before-send")) {
                waiting(board); observe(); due();
                // Keep the positive 250ms probe cache; LIVE must still inspect current rows.
                lobby(board); AdninPartyQueueQuery.tick();
                check(mc.thePlayer.calls == 0, "Immediate sender revalidation rejects a lobby even within the probe interval");
                waiting(board); observe();
                set("armedAt", System.nanoTime() / 1000000L - 501L);
                AdninPartyQueueQuery.tick();
                check(mc.thePlayer.calls == 0, "Rejected sender retains the five-second attempt rate limit");
                due(); observe();
                check(mc.thePlayer.calls == 1, "Current waiting evidence recovers after a failed live revalidation without a manual toggle");
            } else if (mode.equals("missing-player")) {
                waiting(board); observe(); due();
                net.minecraft.client.entity.EntityPlayerSP player = mc.thePlayer;
                mc.thePlayer = null; AdninPartyQueueQuery.tick();
                check(player.calls == 0, "Missing player at the deadline cannot send");
                mc.thePlayer = player; observe();
                check(player.calls == 0, "A returning player begins a fresh readiness delay");
                due(); observe();
                check(player.calls == 1, "A returning player can still query in the same world");
            } else if (mode.equals("sender-recovers")) {
                waiting(board); mc.thePlayer.failures = 1;
                observe(); due(); observe();
                check(mc.thePlayer.calls == 0, "A temporarily failing sender does not report a successful packet");
                observe();
                set("armedAt", System.nanoTime() / 1000000L - 501L);
                AdninPartyQueueQuery.tick();
                check(mc.thePlayer.calls == 0, "Sender exceptions cannot cause immediate retry spam");
                due(); observe();
                check(mc.thePlayer.calls == 1, "Production sender recovers after the bounded retry delay");
            } else if (mode.equals("active") || mode.equals("replay")) {
                waiting(board); observe(); due(); AdninFeatures.active = true; AdninPartyQueueQuery.tick();
                check(mc.thePlayer.calls == 0, "Native active game or Replay revokes a pending query immediately");
            } else if (mode.equals("replay-row")) {
                waiting(board); observe(); due(); board.rows.add(new net.minecraft.scoreboard.Score("Replay: paused"));
                AdninPartyQueueQuery.tick();
                check(mc.thePlayer.calls == 0, "Immediate sidebar Replay evidence rejects a stale positive pregame cache");
            } else if (mode.equals("disabled")) {
                waiting(board); observe(); due(); AdninGui4.partyQueueDetector = false; AdninPartyQueueQuery.tick();
                check(mc.thePlayer.calls == 0, "Disable cancels an already pending query");
                AdninGui4.partyQueueDetector = true; sendNow(mc);
            } else if (mode.equals("consumed-same-world")) {
                waiting(board); sendNow(mc);
                AdninFeatures.active = true; observe(); AdninFeatures.active = false;
                lobby(board); due(); observe(); waiting(board); due(); observe(); due(); observe();
                check(mc.thePlayer.calls == 2, "Same-world active/lobby/waiting transition restores the native cleared mode once");
                AdninGui4.partyQueueDetector = false; observe(); AdninGui4.partyQueueDetector = true; observe(); due(); observe();
                check(mc.thePlayer.calls == 3, "An actual explicit re-enable starts exactly one fresh opportunity");
            } else if (mode.equals("response-window")) {
                waiting(board); mc.thePlayer.onSend=null; sendNow(mc);
                Object pending=response(); long at=sentAt(pending);
                String valid="{\"server\":\"mini123A\",\"gametype\":\"BEDWARS\",\"mode\":\"eight_two\"}";
                check(AdninPartyQueueQuery.awaitsMode(AdninPacketLog.TOKEN,at), "Current token starts a bounded response window");
                check(!AdninPartyQueueQuery.awaitsMode(new Object(),at), "Wrong observer token cannot parse response");
                check(!AdninPartyQueueQuery.awaitsMode(AdninPacketLog.TOKEN,at-1), "Backward time cannot parse response");
                check(!AdninPartyQueueQuery.awaitsMode(AdninPacketLog.TOKEN,at+4501), "Expired window avoids parsing further chat");
                AdninPartyQueueQuery.observeMode(new Object(),valid,at);
                AdninPartyQueueQuery.observeMode(AdninPacketLog.TOKEN,valid,at-1);
                AdninPartyQueueQuery.observeMode(AdninPacketLog.TOKEN,valid,at+4501);
                AdninPartyQueueQuery.observeMode(AdninPacketLog.TOKEN,"[Player] "+valid,at);
                check(mode(pending)==0 && AdninPartyQueueQuery.pollMode()==0, "Wrong token, stale and player responses cannot publish a mode");
                AdninPartyQueueQuery.observeMode(AdninPacketLog.TOKEN,valid,at+4500);
                check(mode(pending)==2 && !AdninPartyQueueQuery.awaitsMode(AdninPacketLog.TOKEN,at+4500), "Boundary response is accepted once");
                check(AdninPartyQueueQuery.pollMode()==2, "Verified response reaches the native-facing callback");
                check(AdninPartyQueueQuery.pollMode()==0, "Repeated callback is throttled");
                observe(); check(mc.thePlayer.calls==1, "Receipt stops retries");
                mc.connection=new net.minecraft.client.network.NetHandlerPlayClient();
                check(AdninPartyQueueQuery.pollMode()==0, "Old response never enters another connection");
                observe(); check(response()==null, "Changed connection retires the response");
            } else if (mode.equals("response-scope-cycle")) {
                waiting(board); sendNow(mc); observe();
                check(response()!=null, "Verified response retained while in the same waiting room");
                lobby(board); observe();
                check(response()==null && AdninPartyQueueQuery.pollMode()==0, "Same-world lobby retires verified mode");
                waiting(board); observe(); due(); observe();
                check(mc.thePlayer.calls==2, "Same-world waiting room after lobby gets a fresh query");
                AdninPartyQueueQuery.shutdown();
                check(response()==null && !AdninPartyQueueQuery.awaitsMode(AdninPacketLog.TOKEN), "Shutdown retires the response and observer admission");
            } else if (mode.equals("missing-sidebar")) {
                waiting(board); observe(); due(); board.objective = null; AdninPartyQueueQuery.tick();
                check(mc.thePlayer.calls == 0, "A removed sidebar cannot use cached pregame authorization");
                board.objective = new net.minecraft.scoreboard.ScoreObjective(); waiting(board);
                observe(); due(); observe();
                check(mc.thePlayer.calls == 1, "A sidebar restored after the due-time rejection can query in the same world");
            } else if (mode.equals("long-delay")) {
                waiting(board); observe(); due();
                set("armedAt", System.nanoTime() / 1000000L - 60000L);
                lobby(board); AdninPartyQueueQuery.tick();
                check(mc.thePlayer.calls == 0, "A delayed client/menu pump cannot dispatch old waiting-room work in a lobby");
            } else if (mode.equals("new-world")) {
                waiting(board); observe(); due(); mc.theWorld = new net.minecraft.client.multiplayer.WorldClient(); lobby(mc.theWorld.board);
                AdninPartyQueueQuery.tick(); due(); observe();
                check(mc.thePlayer.calls == 0, "New lobby world cannot reuse former waiting evidence");
            } else if (mode.equals("new-connection")) {
                waiting(board); observe(); due(); mc.connection = new net.minecraft.client.network.NetHandlerPlayClient(); lobby(board);
                AdninPartyQueueQuery.tick(); due(); observe();
                check(mc.thePlayer.calls == 0, "New lobby connection cannot reuse former waiting evidence");
            } else if (mode.equals("foreign") || mode.equals("solo")) {
                waiting(board);
                if (mode.equals("solo")) mc.singleplayer = true;
                else { mc.server.serverIP = "unrelated.invalid"; board.rows.remove(0); }
                observe(); due(); observe(); check(mc.thePlayer.calls == 0, "Out-of-server or singleplayer evidence cannot send");
            } else if (mode.equals("shutdown")) {
                waiting(board); observe(); due(); AdninPartyQueueQuery.shutdown(); AdninPartyQueueQuery.tick();
                check(mc.thePlayer.calls == 0, "Terminal shutdown cancels the production sender");
            } else if (mode.equals("sender")) {
                // Call the actual private production sender through its tested interface.
                Field f = AdninPartyQueueQuery.class.getDeclaredField("LIVE"); f.setAccessible(true);
                AdninPartyQueueQuery.Sender sender = (AdninPartyQueueQuery.Sender) f.get(null);
                lobby(board); check(!sender.send(mc.theWorld, mc.connection, "/locraw"), "Sender independently denies lobby");
                waiting(board); AdninFeatures.active = true;
                check(!sender.send(mc.theWorld, mc.connection, "/locraw"), "Sender independently denies active/Replay");
                AdninFeatures.active = false; AdninGui4.partyQueueDetector = false;
                check(!sender.send(mc.theWorld, mc.connection, "/locraw"), "Sender independently denies disabled detector");
                AdninGui4.partyQueueDetector = true;
                check(!sender.send(new Object(), mc.connection, "/locraw"), "Sender independently denies old world");
                check(!sender.send(mc.theWorld, new Object(), "/locraw"), "Sender independently denies old connection");
                mc.clientThread = false; check(!sender.send(mc.theWorld, mc.connection, "/locraw"), "Sender rejects non-client thread");
                mc.clientThread = true; check(sender.send(mc.theWorld, mc.connection, "/locraw"), "Sender admits current waiting room once called");
                check(mc.thePlayer.calls == 1, "All rejected calls leave packet count unchanged");
            } else throw new AssertionError("Unknown owned scenario");
            System.out.println("AdninPartyQueueAdapterTest[" + mode + "]: " + checks + " checks passed");
        }
    }''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--runtime-java', type=Path)
    parser.add_argument('--classes', type=Path)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--scenario', help='Run one owned scenario for regression reproduction')
    args = parser.parse_args()
    java = args.runtime_java or args.jdk / 'bin/java.exe'
    with tempfile.TemporaryDirectory(prefix='adnin-party-adapter-') as directory:
        work = Path(directory)
        sources = []
        for name, content in FIXTURES.items():
            path = work / 'src' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf8')
            sources.append(path)
        if not args.classes:
            source = args.source or ROOT / 'src/java/AdninPartyQueueQuery.java'
            path = work / 'src/AdninPartyQueueQuery.java'
            path.write_bytes(source.read_bytes())
            sources.append(path)
        output = work / 'classes'
        output.mkdir()
        compile_args = [str(args.jdk / 'bin/javac.exe'), '--release', '8', '-encoding', 'UTF-8',
                        '-proc:none', '-d', str(output)]
        if args.classes:
            compile_args += ['-cp', str(args.classes.resolve())]
        subprocess.run(compile_args + [str(p) for p in sources], check=True, timeout=60)
        cp = str(output) + (os.pathsep + str(args.classes.resolve()) if args.classes else '')
        scenarios = [args.scenario] if args.scenario else [
            'lobby', 'relay-lobby', 'waiting', 'relay-waiting', 'lost-before-send', 'missing-player', 'sender-recovers',
            'bmp-waiting', 'bmp-countdown', 'relay-bmp-waiting', 'bmp-visible', 'bmp-becomes-visible',
            'active', 'replay', 'replay-row', 'disabled', 'consumed-same-world', 'response-window', 'response-scope-cycle', 'missing-sidebar',
            'long-delay', 'new-world', 'new-connection', 'foreign', 'solo', 'shutdown', 'sender']
        for scenario in scenarios:
            result = subprocess.run([str(java), '-Xverify:all', '-Dfile.encoding=UTF-8', '-cp', cp,
                                     'AdninPartyQueueAdapterTest', scenario], capture_output=True,
                                    text=True, encoding='utf8', errors='replace', timeout=20)
            print((result.stdout + result.stderr).strip())
            if result.returncode:
                raise RuntimeError('Production party-query adapter regression failed: ' + scenario)


if __name__ == '__main__':
    main()
