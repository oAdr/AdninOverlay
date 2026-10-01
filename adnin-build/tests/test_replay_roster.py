"""Execute production Replay bytecode against offline world/Tab identity fixtures."""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('replay_build', ROOT / 'scripts/build-java.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

FIXTURES = {
    'com/mojang/authlib/GameProfile.java': '''package com.mojang.authlib;
public class GameProfile {
    private final java.util.UUID id; private final String name;
    public GameProfile(java.util.UUID id, String name) { this.id=id; this.name=name; }
    public java.util.UUID getId() { return id; } public String getName() { return name; }
}''',
    'net/minecraft/util/IChatComponent.java': '''package net.minecraft.util;
public interface IChatComponent { String getFormattedText(); }''',
    'net/minecraft/util/ChatComponentText.java': '''package net.minecraft.util;
public class ChatComponentText implements IChatComponent {
    private final String text; public ChatComponentText(String value) { text=value; }
    public String getFormattedText() { return text; }
}''',
    'net/minecraft/util/EnumChatFormatting.java': '''package net.minecraft.util;
public class EnumChatFormatting { public int getColorIndex() { return -1; } }''',
    'net/minecraft/entity/player/EntityPlayer.java': '''package net.minecraft.entity.player;
public class EntityPlayer {
    public boolean isDead;
    public boolean isSpectator() { return false; }
    public int ticksExisted;
    public com.mojang.authlib.GameProfile profile;
    public String display, name;
    public java.util.UUID uuid;
    public boolean isEntityAlive() { return !isDead; }
    public String getName() { return name == null ? profile.getName() : name; }
    public com.mojang.authlib.GameProfile getGameProfile() { return profile; }
    public net.minecraft.util.IChatComponent getDisplayName() {
        return new net.minecraft.util.ChatComponentText(display == null ? getName() : display);
    }
    public java.util.UUID getUniqueID() { return uuid; }
}''',
    'net/minecraft/client/entity/EntityOtherPlayerMP.java': '''package net.minecraft.client.entity;
public class EntityOtherPlayerMP extends net.minecraft.entity.player.EntityPlayer { }''',
    'net/minecraft/client/entity/EntityPlayerSP.java': '''package net.minecraft.client.entity;
public class EntityPlayerSP extends net.minecraft.entity.player.EntityPlayer { }''',
    'net/minecraft/client/network/NetworkPlayerInfo.java': '''package net.minecraft.client.network;
public class NetworkPlayerInfo {
    public com.mojang.authlib.GameProfile profile; public String display;
    public net.minecraft.scoreboard.ScorePlayerTeam team;
    public com.mojang.authlib.GameProfile getGameProfile() { return profile; }
    public net.minecraft.world.WorldSettings.GameType getGameType() { return net.minecraft.world.WorldSettings.GameType.SURVIVAL; }
    public net.minecraft.scoreboard.ScorePlayerTeam getPlayerTeam() { return team; }
    public net.minecraft.util.IChatComponent getDisplayName() {
        return display == null ? null : new net.minecraft.util.ChatComponentText(display);
    }
}''',
    'net/minecraft/client/network/NetHandlerPlayClient.java': '''package net.minecraft.client.network;
public class NetHandlerPlayClient {
    public final java.util.List<NetworkPlayerInfo> roster=new java.util.ArrayList<NetworkPlayerInfo>();
    public java.util.Collection<NetworkPlayerInfo> getPlayerInfoMap() { return roster; }
    public NetworkPlayerInfo getPlayerInfo(java.util.UUID id) {
        for(NetworkPlayerInfo info:roster)if(info.profile!=null && id.equals(info.profile.getId()))return info;return null;
    }
}''',
    'net/minecraft/world/WorldSettings.java': '''package net.minecraft.world;
public final class WorldSettings { public enum GameType { SURVIVAL, SPECTATOR } }''',
    'net/minecraft/scoreboard/Score.java': '''package net.minecraft.scoreboard;
public class Score { public String getPlayerName() { return ""; } }''',
    'net/minecraft/scoreboard/ScoreObjective.java': '''package net.minecraft.scoreboard;
public class ScoreObjective { public String title="REPLAY"; public String getDisplayName() { return title; } }''',
    'net/minecraft/scoreboard/Team.java': 'package net.minecraft.scoreboard; public class Team { }',
    'net/minecraft/scoreboard/ScorePlayerTeam.java': '''package net.minecraft.scoreboard;
public class ScorePlayerTeam extends Team {
    public String prefix="", suffix="";
    public net.minecraft.util.EnumChatFormatting getChatFormat() { return null; }
    public static String formatPlayerName(Team team,String name) {
        return team == null ? name : ((ScorePlayerTeam)team).prefix + name + ((ScorePlayerTeam)team).suffix;
    }
}''',
    'net/minecraft/scoreboard/Scoreboard.java': '''package net.minecraft.scoreboard;
public class Scoreboard {
    public final ScoreObjective objective=new ScoreObjective();
    public ScorePlayerTeam getPlayersTeam(String name) { return null; }
    public ScoreObjective getObjectiveInDisplaySlot(int slot) { return slot==1?objective:null; }
    public java.util.Collection<Score> getSortedScores(ScoreObjective objective) { return java.util.Collections.emptyList(); }
}''',
    'net/minecraft/client/multiplayer/WorldClient.java': '''package net.minecraft.client.multiplayer;
public class WorldClient {
    public final java.util.List<net.minecraft.entity.player.EntityPlayer> playerEntities=new java.util.ArrayList<net.minecraft.entity.player.EntityPlayer>();
    public final net.minecraft.scoreboard.Scoreboard scoreboard=new net.minecraft.scoreboard.Scoreboard();
    public int scoreboardReads;
    public boolean failScoreboard;
    public net.minecraft.scoreboard.Scoreboard getScoreboard() {
        scoreboardReads++;
        if(failScoreboard) throw new IllegalStateException("Owned offline scoreboard failure");
        return scoreboard;
    }
}''',
    'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
public class Minecraft {
    public net.minecraft.client.multiplayer.WorldClient theWorld=new net.minecraft.client.multiplayer.WorldClient();
    public net.minecraft.client.entity.EntityPlayerSP thePlayer=new net.minecraft.client.entity.EntityPlayerSP();
    public net.minecraft.client.network.NetHandlerPlayClient connection=new net.minecraft.client.network.NetHandlerPlayClient();
    public net.minecraft.client.network.NetHandlerPlayClient getNetHandler() { return connection; }
    public boolean isCallingFromMinecraftThread() { return true; }
}''',
    'AdninApi.java': '''public class AdninApi {
    public static volatile int requests;
    public static final java.util.Set<String> names=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    public static String fetchReplayMojangProfile(String name) {
        requests++; names.add(name);
        return "nickfixture".equals(name) || "xiaoshu_sky202".equals(name)
            ? "NICK" : new java.util.UUID(0x4000L,0x8000000000000001L).toString();
    }
    public static String errorCode(java.io.IOException failure) { return "request-failed"; }
}''',
    'AdninReplayRosterTest.java': '''import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.network.NetworkPlayerInfo;
public class AdninReplayRosterTest {
    private static int checks;
    private static long observationTime;
    private static EntityOtherPlayerMP actor(Minecraft mc,String raw,String display,int id) {
        EntityOtherPlayerMP p=new EntityOtherPlayerMP();
        p.profile=new GameProfile(new UUID(0x2000L,id),raw);
        p.uuid=new UUID(0x3000L,id+100); p.display=display; mc.theWorld.playerEntities.add(p); return p;
    }
    private static void tab(Minecraft mc,String raw,String display,int id) {
        NetworkPlayerInfo info=new NetworkPlayerInfo(); info.profile=new GameProfile(new UUID(0x1000L,id+200),raw);
        info.display=display; mc.connection.roster.add(info);
    }
    private static void refresh(Minecraft mc) throws Exception {
        observationTime += 300000000L;
        AdninReplay.tick(mc, observationTime);
    }
    private static void check(boolean value,String reason) { checks++; if(!value)throw new AssertionError(reason); }
    private static java.util.Properties diagnostics() {
        java.util.Properties p=new java.util.Properties(); AdninReplay.diagnostics(p); return p;
    }
    private static int count(String key) { return Integer.parseInt(diagnostics().getProperty(key)); }
    private static void grayPauseRoster() throws Exception {
        AdninReplay.clear();Minecraft mc=new Minecraft();mc.thePlayer.profile=new GameProfile(new UUID(0,0),"Viewer");
        EntityOtherPlayerMP p=actor(mc,"GrayBot","\\u00a77GrayRecorded",94);
        tab(mc,"GrayAlias","\\u00a77GrayRecorded",94);refresh(mc);
        NetworkPlayerInfo info=mc.connection.roster.get(0);
        info.team=new net.minecraft.scoreboard.ScorePlayerTeam();info.team.prefix="\\u00a7c";refresh(mc);
        check("GrayRecorded".equals(AdninReplay.actorName(p)) && "GrayRecorded".equals(AdninReplay.recordedName("GrayAlias")),
            "Gray Replay pause preserves actor and recorded-name identity mappings");
        check(AdninReplay.playerInfo("GrayRecorded")==info && AdninReplay.playerInfo("GrayAlias")==info,
            "Both admitted aliases expose the same current Tab object without per-actor roster scans");
        int before=count("replayProfileRequests");
        p.display="\\u00a7cGrayRecorded";refresh(mc);
        check(AdninReplay.profile("GrayRecorded").isEmpty() && count("replayProfileRequests")==before,
            "Gray Replay Tab pauses profile lookup despite still-red actor and scoreboard");
        p.display="\\u00a77GrayRecorded";info.display="\\u00a7cGrayRecorded";refresh(mc);
        check(AdninReplay.profile("GrayRecorded").isEmpty() && count("replayProfileRequests")==before,
            "Gray Replay actor pauses profile lookup despite still-red Tab and scoreboard");
        for(int i=0;i<50;i++)check(AdninReplay.profile(i%2==0?"GrayAlias":"GrayRecorded").isEmpty(),
            "Unknown gray Replay profile keeps its original unresolved state");
        check(count("replayProfileRequests")==before,"Gray Replay aliases create no identity query");
        p.display="\\u00a7cGrayRecorded";info.display="\\u00a7cGrayRecorded";refresh(mc);
        AdninReplay.profile("GrayAlias");String known="";long until=System.nanoTime()+3000000000L;
        while(known.isEmpty() && System.nanoTime()<until){Thread.sleep(10);known=AdninReplay.profile("GrayRecorded");}
        check(known.startsWith("grayrecorded|"),"Normal color resumes the ordinary account resolver");
        before=count("replayProfileRequests");p.display="\\u00a77GrayRecorded";info.display="\\u00a77GrayRecorded";refresh(mc);
        check(known.equals(AdninReplay.profile("GrayAlias")) && count("replayProfileRequests")==before,
            "A subsequent gray pause preserves the same account's known profile without requerying");
        AdninReplay.clear();check(AdninReplay.playerInfo("GrayAlias")==null && AdninReplay.recordedName("GrayAlias").isEmpty(),
            "A genuine context clear releases gray and ordinary alias snapshots together");
    }
    private static void observationLifecycle() {
        AdninReplay.clear();
        Minecraft mc=new Minecraft(); mc.thePlayer.profile=new GameProfile(new UUID(0,0),"Viewer");
        mc.thePlayer.ticksExisted=1000;
        EntityOtherPlayerMP oldActor=actor(mc,"ObservedActor",null,91);
        tab(mc,"ObservedActor",null,91);
        AdninReplay.tick(mc,0L);
        check(mc.theWorld.scoreboardReads==1 && AdninReplay.isReplay(),"Initial live-adapter fixture scans once");
        for(int i=0;i<2000;i++) AdninReplay.tick(mc,i*20000L);
        check(mc.theWorld.scoreboardReads==1,"Two thousand frame/pump callbacks inside 50ms perform one actual scoreboard read");
        mc.theWorld.scoreboard.objective.title="BED WARS";
        AdninReplay.tick(mc,49999999L);
        check(AdninReplay.isReplay() && mc.theWorld.scoreboardReads==1,"Sidebar cache is retained only within the 50ms window");
        AdninReplay.tick(mc,50000000L);
        check(!AdninReplay.isReplay() && mc.theWorld.scoreboardReads==2 && AdninReplay.actorName(oldActor).isEmpty(),
            "At 50ms the same paused player observes Replay exit and clears actor admission");
        mc.theWorld.scoreboard.objective.title="REPLAY";
        AdninReplay.tick(mc,100000000L);
        check(AdninReplay.isReplay() && "ObservedActor".equals(AdninReplay.actorName(oldActor)),
            "Replay can resume while entity tick count stays paused");
        mc.connection=new net.minecraft.client.network.NetHandlerPlayClient();
        AdninReplay.tick(mc,100000001L);
        check(mc.theWorld.scoreboardReads==4 && AdninReplay.actorName(oldActor).isEmpty()
            && AdninReplay.recordedName("ObservedActor").isEmpty(),
            "Connection replacement immediately discards the old Tab and actor identities");
        tab(mc,"ObservedActor",null,91);
        mc.thePlayer=new net.minecraft.client.entity.EntityPlayerSP();
        mc.thePlayer.profile=new GameProfile(new UUID(0,1),"NextViewer");
        AdninReplay.tick(mc,100000002L);
        check(mc.theWorld.scoreboardReads==5 && "ObservedActor".equals(AdninReplay.actorName(oldActor)),
            "Player replacement immediately selects its own sidebar and actor roster");
        net.minecraft.client.multiplayer.WorldClient oldWorld=mc.theWorld;
        mc.theWorld=new net.minecraft.client.multiplayer.WorldClient();
        mc.theWorld.scoreboard.objective.title="BED WARS";
        AdninReplay.tick(mc,100000003L);
        check(!AdninReplay.isReplay() && mc.theWorld.scoreboardReads==1 && oldWorld.scoreboardReads==5,
            "World replacement cannot inherit the previous Replay flag or observation deadline");
        mc.theWorld.scoreboard.objective.title="REPLAY";
        AdninReplay.tick(mc,150000003L);
        check(AdninReplay.isReplay(),"Replacement world enters Replay through its own sidebar");
        mc.connection=null;
        AdninReplay.tick(mc,150000004L);
        check(!AdninReplay.isReplay() && mc.theWorld.scoreboardReads==2
            && "no-connection".equals(diagnostics().getProperty("replayStatus")),
            "Disconnect invalidates Replay immediately without reading the scoreboard");
        mc.connection=new net.minecraft.client.network.NetHandlerPlayClient();
        EntityOtherPlayerMP beforeSeek=actor(mc,"SeekActor",null,92); tab(mc,"SeekActor",null,92);
        AdninReplay.tick(mc,150000005L);
        check("SeekActor".equals(AdninReplay.actorName(beforeSeek)),"Reconnection scans immediately and admits its current actors");
        mc.thePlayer.ticksExisted=0;
        mc.theWorld.playerEntities.clear();
        EntityOtherPlayerMP afterSeek=actor(mc,"SeekActor",null,93);
        AdninReplay.tick(mc,400000005L);
        check(AdninReplay.actorName(beforeSeek).isEmpty() && "SeekActor".equals(AdninReplay.actorName(afterSeek)),
            "A backward seek cannot stall the existing 250ms actor refresh or retain pre-seek entity identities");
        mc.theWorld.failScoreboard=true;
        int beforeFailure=mc.theWorld.scoreboardReads;
        AdninReplay.tick(mc,450000005L);
        check(!AdninReplay.isReplay() && AdninReplay.actorName(afterSeek).isEmpty(),
            "A failed observation clears stale actor admission");
        for(int i=0;i<2000;i++) AdninReplay.tick(mc,450000005L+i*20000L);
        check(mc.theWorld.scoreboardReads==beforeFailure+1,
            "An exceptional scoreboard is not retried on every frame inside the 50ms window");
        mc.theWorld.failScoreboard=false;
        AdninReplay.tick(mc,500000005L);
        check(AdninReplay.isReplay() && "SeekActor".equals(AdninReplay.actorName(afterSeek)),
            "The next 50ms observation recovers after the transient scoreboard failure");
        mc.thePlayer=null;
        AdninReplay.tick(mc,500000006L);
        check(!AdninReplay.isReplay() && AdninReplay.actorName(afterSeek).isEmpty(),
            "Local-player loss invalidates a just-observed Replay immediately");
        mc.theWorld=null;
        AdninReplay.tick(mc,500000007L);
        check(!AdninReplay.isReplay() && count("replayRosterWorldActors")==0,
            "World loss retains no Replay roster counters");
        AdninReplay.clear();
    }
    public static void main(String[] args) throws Exception {
        observationLifecycle();
        Minecraft mc=new Minecraft(); mc.thePlayer.profile=new GameProfile(new UUID(0,0),"Viewer");
        EntityOtherPlayerMP a=actor(mc,"RecordedOne","\\u00a7cRRecordedOne",1);
        tab(mc,"RecordedOne","\\u00a7cRRecordedOne",1);
        refresh(mc);
        check(AdninReplay.isReplay(),"Real production observer recognizes sidebar");
        check("RecordedOne".equals(AdninReplay.actorName(a)),"Entity UUID, profile UUID and Tab UUID may all differ");
        check(AdninReplay.actorName(mc.thePlayer).isEmpty(),"Local viewer cannot be a recorded actor");
        EntityOtherPlayerMP b=actor(mc,"EntityBot","\\u00a7a[MVP+] RecordedTwo",2);
        tab(mc,"TabBot","\\u00a7a[MVP+] RecordedTwo",2); refresh(mc);
        check("RecordedTwo".equals(AdninReplay.actorName(b)),"Independent entity/Tab bot names match by recorded display account");
        check("RecordedTwo".equals(AdninReplay.recordedName("TabBot")) && AdninApi.requests==0,"Read-only recorded account lookup uses current Tab without API work");
        check(AdninReplay.profile("TabBot").isEmpty(),"First statistics lookup is asynchronous");
        long until=System.nanoTime()+2000000000L; String profile="";
        while(profile.isEmpty() && System.nanoTime()<until) { Thread.sleep(10); profile=AdninReplay.profile("TabBot"); }
        check(profile.startsWith("recordedtwo|"),"Native callback gets verified account UUID for raw Tab alias");
        check(AdninApi.requests==1,"Production worker uses one bounded lookup");
        tab(mc,"DistantPlayer",null,5); refresh(mc);
        AdninReplay.profile("DistantPlayer");
        java.util.Properties diagnostics=new java.util.Properties(); AdninReplay.diagnostics(diagnostics);
        check("2".equals(diagnostics.getProperty("replayProfileRequests")),"Current Tab entry outside entity tracking distance still queues statistics");
        EntityOtherPlayerMP spectator=actor(mc,"RecordedOne","[Viewer] RecordedOne",3); refresh(mc);
        check(AdninReplay.actorName(spectator).isEmpty(),"Viewer decoration excludes otherwise valid name");
        check(!AdninReplay.actorName(a).isEmpty(),"Viewer cannot create a duplicate actor conflict");
        EntityOtherPlayerMP duplicate=actor(mc,"RecordedOne",null,4); refresh(mc);
        check(AdninReplay.actorName(a).isEmpty() && AdninReplay.actorName(duplicate).isEmpty(),"Ambiguous duplicate actors fail closed");
        diagnostics.clear(); AdninReplay.diagnostics(diagnostics);
        long before=Long.parseLong(diagnostics.getProperty("replayProfileRequests"));
        AdninReplay.profile("RecordedOne"); diagnostics.clear(); AdninReplay.diagnostics(diagnostics);
        check(Long.parseLong(diagnostics.getProperty("replayProfileRequests"))==before+1,"Duplicate loaded actors cannot suppress a unique current-Tab statistics identity");
        mc.theWorld.playerEntities.remove(duplicate); refresh(mc);
        check(!AdninReplay.actorName(a).isEmpty(),"Unambiguous current roster recovers");
        EntityOtherPlayerMP ambiguousA=actor(mc,"RecordedA",null,10);
        EntityOtherPlayerMP ambiguousB=actor(mc,"RecordedB",null,11);
        tab(mc,"ConflictingBot","RecordedA",10); tab(mc,"ConflictingBot","RecordedB",11); refresh(mc);
        check(AdninReplay.actorName(ambiguousA).isEmpty() && AdninReplay.actorName(ambiguousB).isEmpty(),"Conflicting raw Tab aliases cannot back either detector actor");
        check(AdninReplay.profile("ConflictingBot").isEmpty(),"Conflicting raw Tab aliases cannot query statistics");
        mc.connection.roster.clear(); refresh(mc);
        check(AdninReplay.actorName(a).isEmpty() && AdninReplay.profile("TabBot").isEmpty(),"Tab departure clears actor and native identity eligibility");
        tab(mc,"RecordedOne",null,1); refresh(mc);
        mc.theWorld.scoreboard.objective.title="BED WARS"; refresh(mc);
        check(!AdninReplay.isReplay() && AdninReplay.actorName(a).isEmpty(),"Normal games do not inherit Replay bot eligibility");
        mc.theWorld=new net.minecraft.client.multiplayer.WorldClient(); refresh(mc);
        check(AdninReplay.actorName(a).isEmpty(),"New world cannot reuse old entity references");
        mc.connection.roster.clear();
        EntityOtherPlayerMP decorated=actor(mc,"DecoratedName\\u00a7r","\\u00a7aGDecoratedName\\u00a7r",20);
        tab(mc,"DecoratedName\\u00a7r","\\u00a7aGDecoratedName\\u00a7r",20); refresh(mc);
        check("DecoratedName".equals(AdninReplay.actorName(decorated)),"Formatted Replay profile names qualify under the normalized current Tab identity");
        diagnostics.clear(); AdninReplay.diagnostics(diagnostics);
        check("1".equals(diagnostics.getProperty("replayFormattedTabProfiles")),"Fixed diagnostic counts expose formatted profiles without names");
        before=Long.parseLong(diagnostics.getProperty("replayProfileRequests"));
        AdninReplay.profile("DecoratedName"); diagnostics.clear(); AdninReplay.diagnostics(diagnostics);
        check(Long.parseLong(diagnostics.getProperty("replayProfileRequests"))==before+1,"Native normalized key resolves the original formatted Tab profile");
        tab(mc,"DecoratedName\\u00a7c","OtherAccount",21); refresh(mc);
        check(AdninReplay.actorName(decorated).isEmpty() && AdninReplay.profile("DecoratedName").isEmpty(),"Formatting-normalized alias conflicts fail closed");
        mc.connection.roster.clear();
        tab(mc,"NickAlias","NickFixture",30); refresh(mc);
        check("NickFixture".equals(AdninReplay.recordedName("NickAlias\\u00a7r")),"Current formatted Replay alias exposes its mapped nickname");
        check(!AdninReplay.isNick("NickAlias"),"Pending profile lookup is not a confirmed Nick");
        until=System.nanoTime()+3000000000L;
        while(!AdninReplay.isNick("NickAlias") && System.nanoTime()<until) Thread.sleep(10);
        check(AdninReplay.isNick("NickAlias\\u00a7r") && "NICK".equals(AdninReplay.profile("NickAlias")),"Confirmed Nick uses the exact native sentinel and normalized raw wrapper");
        check(AdninReplay.isNick("NickFixture") && "NICK".equals(AdninReplay.profile("NickAlias\\u00a7r")),
            "True Nick classification stays identical through its full recorded name and formatted raw alias");
        mc.connection.roster.clear();
        tab(mc,"NickAlias","OtherAccount",31); refresh(mc);
        check("OtherAccount".equals(AdninReplay.recordedName("NickAlias")) && !AdninReplay.isNick("NickAlias"),"Alias reassignment cannot inherit cached Nick");
        mc.connection.roster.clear(); refresh(mc);
        check(AdninReplay.recordedName("NickAlias").isEmpty() && !AdninReplay.isNick("NickAlias"),"Departed aliases lose recorded-name and Nick access");

        mc.theWorld=new net.minecraft.client.multiplayer.WorldClient(); mc.connection.roster.clear();
        EntityOtherPlayerMP suffix=actor(mc,"SuffixNick\\u00a7r","\\u00a7aSuffixNick \\u00a7e20",40);
        tab(mc,"SuffixNick\\u00a7r","\\u00a7aSuffixNick \\u00a7e20",40); refresh(mc);
        check("SuffixNick".equals(AdninReplay.actorName(suffix)),"Formatted raw account survives additional display tokens in both world and Tab");
        EntityOtherPlayerMP alias=actor(mc,"RawAlias\\u00a7r","UnmappedDisplay",41);
        tab(mc,"RawAlias","CanonicalName",41); refresh(mc);
        check("CanonicalName".equals(AdninReplay.actorName(alias)),"Normalized GameProfile fallback matches the unambiguous current Tab raw alias");
        EntityOtherPlayerMP fallback=actor(mc,"UnknownProfile","UnmappedDisplay",42);
        fallback.name="NameFallback\\u00a7r"; tab(mc,"NameFallback",null,42); refresh(mc);
        check("NameFallback".equals(AdninReplay.actorName(fallback)),"Player getName fallback is normalized against current Tab canonical aliases");
        tab(mc,"UnmappedDisplay",null,43); refresh(mc);
        check(AdninReplay.actorName(alias).isEmpty() && AdninReplay.actorName(fallback).isEmpty(),"Contradictory raw, display and player-name matches fail closed");
        check(count("replayRejectedConflictingAlias")==2,"Contradictory entity aliases have their own anonymous count");
        mc.theWorld=new net.minecraft.client.multiplayer.WorldClient(); mc.connection.roster.clear();
        EntityOtherPlayerMP collision=actor(mc,"SecondAlias",null,44);
        tab(mc,"FirstAlias","SecondAlias",44); tab(mc,"SecondAlias","ThirdAlias",45); refresh(mc);
        check(AdninReplay.actorName(collision).isEmpty() && count("replayAmbiguousTabAliases")==1,
            "Raw/canonical Tab alias collisions cannot select an arbitrary identity");
        check(AdninReplay.recordedName("FirstAlias").isEmpty() && AdninReplay.recordedName("SecondAlias").isEmpty(),
            "Ambiguous canonical identity is also withheld from Stats and Denicker aliases");

        mc.theWorld=new net.minecraft.client.multiplayer.WorldClient(); mc.connection.roster.clear();
        EntityOtherPlayerMP truncated=actor(mc,"XiaoShu_SKY202",null,46);
        tab(mc,"XiaoShu_SKY202","\\u00a7cR\\u00a7fXiaoShu_SKY202\\u00a7a6 \\u00a7e20",46); refresh(mc);
        check("XiaoShu_SKY2026".equals(AdninReplay.actorName(truncated)),
            "A truncated entity profile maps to the complete current Tab account for AC");
        check("XiaoShu_SKY2026".equals(AdninReplay.recordedName("XiaoShu_SKY202"))
            && "XiaoShu_SKY2026".equals(AdninReplay.recordedName("XiaoShu_SKY2026")),
            "Both exact caller aliases expose the full current account to Denicker");
        AdninReplay.profile("XiaoShu_SKY202"); profile=""; until=System.nanoTime()+4000000000L;
        while(profile.isEmpty() && System.nanoTime()<until) { Thread.sleep(10); profile=AdninReplay.profile("XiaoShu_SKY2026"); }
        check(profile.startsWith("xiaoshu_sky2026|") && profile.equals(AdninReplay.profile("XiaoShu_SKY202")),
            "Stats resolves the complete current account through either exact alias");
        check(profile.equals(AdninReplay.profile("XiaoShu_SKY202\\u00a7r")),
            "Statistics normalization preserves the same corrected full account for formatted caller aliases");
        check(!AdninReplay.isNick("XiaoShu_SKY202") && !AdninReplay.isNick("XiaoShu_SKY2026")
            && AdninApi.names.contains("xiaoshu_sky2026") && !AdninApi.names.contains("xiaoshu_sky202"),
            "The truncated non-account is never queried or misclassified as a Nick");
        check("XiaoShu_SKY202".equals(truncated.profile.getName()) && truncated.uuid.equals(new UUID(0x3000L,146)),
            "Selecting the full recorded identity never mutates the actor profile or entity UUID");
        mc.connection.roster.clear();
        tab(mc,"XiaoShu_SKY202",null,46);
        net.minecraft.scoreboard.ScorePlayerTeam team=new net.minecraft.scoreboard.ScorePlayerTeam();
        team.prefix="\\u00a7cR\\u00a7f"; team.suffix="\\u00a7a6 \\u00a7e20";
        mc.connection.roster.get(0).team=team; refresh(mc);
        check("XiaoShu_SKY2026".equals(AdninReplay.actorName(truncated))
            && "XiaoShu_SKY2026".equals(AdninReplay.recordedName("XiaoShu_SKY202")),
            "Tab's scoreboard team suffix supplies the observed final digit when displayName is absent");
        mc.connection.roster.get(0).display="XiaoShu_SKY2026 OtherAccount"; refresh(mc);
        check(AdninReplay.actorName(truncated).isEmpty() && AdninReplay.recordedName("XiaoShu_SKY202").isEmpty(),
            "Ambiguous complete display text does not fall back to a truncated synthetic account");
        mc.connection.roster.get(0).display="XiaoShu_SKY2026"; refresh(mc);
        EntityOtherPlayerMP unseen=actor(mc,"XiaoShu_SKY20",null,47); refresh(mc);
        check(AdninReplay.actorName(unseen).isEmpty(), "Unobserved shorter entity prefixes cannot fuzzily match the complete recorded name");

        mc.theWorld=new net.minecraft.client.multiplayer.WorldClient(); mc.connection.roster.clear();
        mc.theWorld.playerEntities.add(mc.thePlayer);
        actor(mc,"FixtureGood",null,50); tab(mc,"FixtureGood",null,50);
        EntityOtherPlayerMP dead=actor(mc,"FixtureDead",null,51); dead.isDead=true; tab(mc,"FixtureDead",null,51);
        actor(mc,"FixtureViewed","[Viewer] FixtureViewed",52); tab(mc,"FixtureViewed",null,52);
        mc.theWorld.playerEntities.add(new net.minecraft.entity.player.EntityPlayer());
        EntityOtherPlayerMP missing=actor(mc,"FixtureMissing",null,53); missing.profile=null;
        actor(mc,"FixtureUnmatched",null,54);
        actor(mc,"ConflictOne","ConflictTwo",55); tab(mc,"ConflictOne",null,55); tab(mc,"ConflictTwo",null,56);
        actor(mc,"FixtureDuplicate",null,57); actor(mc,"FixtureDuplicate",null,58); tab(mc,"FixtureDuplicate",null,57);
        refresh(mc);
        check(count("replayRosterWorldActors")==10 && count("replayActors")==1,"Roster snapshot counts all ten fixture entries and the sole valid actor");
        int rejected=0;
        for(String key:new String[]{"Self","Dead","Viewer","Type","MissingProfile","Unmatched","ConflictingAlias","Duplicate"}) {
            int value=count("replayRejected"+key); rejected+=value;
            check(value==("Duplicate".equals(key)?2:1),"Independent per-roster rejection count: "+key);
        }
        check(rejected+count("replayActors")==count("replayRosterWorldActors"),"Accepted plus exclusive rejection counters reconcile with observed world entries");
        for(Object value:diagnostics().values())check(value.toString().matches("[a-z-]+|[0-9]+"),"Roster diagnostic values reveal no names, UUIDs or display strings");
        mc.theWorld.scoreboard.objective.title="BED WARS"; refresh(mc);
        check(count("replayRosterWorldActors")==0 && count("replayRejectedDuplicate")==0,"Leaving Replay clears per-roster diagnostic counters");
        AdninReplay.clear();
        check(AdninReplay.profile("RecordedOne").isEmpty(),"Unload disables cached native profile access");
        check(count("replayRejectedSelf")==0 && count("replayAmbiguousTabAliases")==0,"Explicit clear retires roster diagnostics");
        grayPauseRoster();
        System.out.println("AdninReplayRosterTest: "+checks+" checks passed; production bytecode, fake world/Tab/API, no game or network");
    }
}'''
}


def run_anticheat_integration(jdk, classes, work, source=False, java=None):
    """Use the real Replay roster, adapter and Engine; only game/API endpoints are fake."""
    import test_anticheat_adapter as adapter
    fixtures = dict(adapter.FIXTURES)
    del fixtures['AdninReplay.java']
    del fixtures['AdninAnticheatAdapterTest.java']
    for name, content in FIXTURES.items():
        if name.startswith('net/minecraft/scoreboard/') or name.endswith('/EnumChatFormatting.java'):
            fixtures[name] = content
    path = 'net/minecraft/entity/player/EntityPlayer.java'
    fixtures[path] = fixtures[path].replace('public String name;', 'public String name, display;').replace(
        'new net.minecraft.util.ChatComponentText(name)',
        'new net.minecraft.util.ChatComponentText(display == null ? name : display)')
    path = 'net/minecraft/client/multiplayer/WorldClient.java'
    if 'getScoreboard()' not in fixtures[path]:
        fixtures[path] = fixtures[path].replace('extends net.minecraft.world.World {', '''extends net.minecraft.world.World {
    public final net.minecraft.scoreboard.Scoreboard scoreboard=new net.minecraft.scoreboard.Scoreboard();
    public net.minecraft.scoreboard.Scoreboard getScoreboard() { return scoreboard; }''')
    path = 'net/minecraft/client/network/NetworkPlayerInfo.java'
    if 'getPlayerTeam()' not in fixtures[path]:
        fixtures[path] = fixtures[path].replace('public final class NetworkPlayerInfo {', '''public final class NetworkPlayerInfo {
    public String display;
    public net.minecraft.scoreboard.ScorePlayerTeam team;
    public net.minecraft.scoreboard.ScorePlayerTeam getPlayerTeam() { return team; }
    public net.minecraft.util.IChatComponent getDisplayName() {
        return display == null ? null : new net.minecraft.util.ChatComponentText(display);
    }''')
    path = 'net/minecraft/client/network/NetHandlerPlayClient.java'
    if 'getPlayerInfoMap()' not in fixtures[path]:
        fixtures[path] = fixtures[path].replace('public final class NetHandlerPlayClient {', '''public final class NetHandlerPlayClient {
    public java.util.Collection<NetworkPlayerInfo> getPlayerInfoMap() { return roster.values(); }''')
    fixtures['AdninApi.java'] = '''public final class AdninApi {
    public static volatile int requests;
    public static volatile boolean absent;
    public static String fetchReplayMojangProfile(String name) throws java.io.IOException {
        requests++;
        if (absent) return "NICK";
        throw new java.io.IOException("Owned offline failure");
    }
    public static String errorCode(java.io.IOException failure) { return "request-failed"; }
}'''
    fixtures['AdninReplayAnticheatTest.java'] = '''import java.util.UUID;
import java.util.Properties;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
public final class AdninReplayAnticheatTest {
    private static int checks;
    private static long observationTime;
    private static final UUID LOCAL=new UUID(0x4000L,0x8000000000000001L);
    private static final UUID NICK=new UUID(0x1000L,0x8000000000000002L);
    private static final UUID ENTITY=new UUID(0x2000L,0x8000000000000003L);
    private static final UUID PROFILE=new UUID(0x3000L,0x8000000000000004L);
    private static final UUID TAB=new UUID(0x1000L,0x8000000000000005L);
    private static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
    private static Properties diagnostics() { Properties p=new Properties(); AdninReplay.diagnostics(p); AdninAnticheat.diagnostics(p); return p; }
    private static long count(String key) { return Long.parseLong(diagnostics().getProperty(key)); }
    private static Minecraft scene(boolean replay) {
        AdninReplay.clear(); AdninAnticheat.shutdown(); AdninFeatures.outputs=0;
        Minecraft mc=new Minecraft(); mc.theWorld=new WorldClient(); mc.connection=new NetHandlerPlayClient();
        mc.thePlayer=new EntityPlayerSP(LOCAL,new GameProfile(LOCAL,"LocalFixture"),"LocalFixture");
        mc.theWorld.playerEntities.add(mc.thePlayer);
        mc.theWorld.scoreboard.objective.title=replay?"REPLAY":"BED WARS";
        Properties p=new Properties(); p.setProperty("anticheat.enabled","true");
        p.setProperty("anticheat.autoReport","true"); p.setProperty("anticheat.flagSound","false");
        p.setProperty("anticheat.noFall","false"); p.setProperty("anticheat.noSlow","false");
        p.setProperty("anticheat.scaffold","false"); p.setProperty("anticheat.legitScaffold","false");
        AdninAnticheat.loadSettings(p); return mc;
    }
    private static EntityOtherPlayerMP actor(Minecraft mc,UUID entity,UUID profile,String raw,String name,String display) {
        EntityOtherPlayerMP p=new EntityOtherPlayerMP(entity,new GameProfile(profile,raw),name);
        p.display=display; p.blocking=p.isSwingInProgress=true; mc.theWorld.playerEntities.add(p); return p;
    }
    private static void tab(Minecraft mc,UUID id,String raw,String display) {
        NetworkPlayerInfo p=new NetworkPlayerInfo(new GameProfile(id,raw));
        p.display=display==null?null:new net.minecraft.util.ChatComponentText(display);
        mc.connection.roster.put(id,p);
    }
    private static void refresh(Minecraft mc) throws Exception {
        observationTime += 300000000L;
        AdninReplay.tick(mc,observationTime);
    }
    private static void ticks(Minecraft mc,int count) {
        for(int i=0;i<count;i++) {
            for(EntityPlayer p:mc.theWorld.playerEntities)p.ticksExisted++;
            AdninAnticheat.tick(mc);
        }
    }
    private static void waitForCompletion(long before) throws Exception {
        long until=System.nanoTime()+3000000000L;
        while(count("replayProfileCompleted")==before && System.nanoTime()<until)Thread.sleep(10);
        check(count("replayProfileCompleted")>before,"Owned resolver completed without a socket");
    }
    public static void main(String[] args) throws Exception {
        Minecraft mc=scene(false);
        EntityOtherPlayerMP live=actor(mc,NICK,NICK,"NormalNick","NormalNick",null);
        tab(mc,NICK,"NormalNick",null); refresh(mc);
        ticks(mc,9); check(mc.thePlayer.messages==0,"Normal Nick keeps the original ten-observation threshold");
        ticks(mc,1);
        check(NICK.version()==1 && count("anticheatAcceptedActors")==1 && mc.thePlayer.messages==1,
            "Current-Tab UUIDv1 Nick reaches production adapter and Engine");
        check(AdninApi.requests==0,"Normal Nick detection never requests account verification");
        check(AdninFeatures.outputs==1 && AdninFeatures.lastOutput.contains("NormalNick")
            && !AdninFeatures.lastOutput.contains("WDR"),"Normal Nick alert passes actual text once to category Output");

        mc=scene(true);
        EntityOtherPlayerMP replay=actor(mc,ENTITY,PROFILE,"ReplayNick\\u00a7r","ReplayNick\\u00a7r","\\u00a7aReplayNick \\u00a7e20");
        tab(mc,TAB,"ReplayNick\\u00a7r","\\u00a7aReplayNick \\u00a7e20"); refresh(mc);
        check("ReplayNick".equals(AdninReplay.actorName(replay)),"Actual Replay roster admits normalized current-Tab Nick with mismatched world/profile/Tab UUIDs");
        check(!ENTITY.equals(PROFILE) && !ENTITY.equals(TAB) && !PROFILE.equals(TAB),"Fixture UUIDs are all independently mismatched");
        check(AdninApi.requests==0,"Replay roster admission requires no Mojang lookup");
        ticks(mc,10);
        check(mc.thePlayer.messages==1 && count("anticheatAcceptedActors")==1,
            "Unresolved Replay Nick reaches real Engine through real roster and real adapter");
        check(mc.thePlayer.reports==0 && !mc.thePlayer.lastMessage.contains("WDR"),"Replay Nick is never reported automatically or through a clickable report");
        check(AdninFeatures.outputs==1 && AdninFeatures.lastOutput.equals(mc.thePlayer.lastMessage),
            "Replay alert Output receives the exact generated local detection message");

        long completed=count("replayProfileCompleted");
        check(AdninReplay.profile("ReplayNick").isEmpty(),"Statistics lookup begins unresolved");
        waitForCompletion(completed);
        check(count("replayProfileFailed")==count("replayProfileCompleted")
            && count("replayProfileAbsent")==0 && AdninReplay.profile("ReplayNick").isEmpty(),
            "All resolver attempts failed and yielded no successful account UUID");
        AdninAnticheat.shutdown(); mc.thePlayer.messages=mc.thePlayer.reports=0; AdninFeatures.outputs=0;
        refresh(mc); ticks(mc,10);
        check(mc.thePlayer.messages==1 && count("anticheatAcceptedActors")==1 && AdninFeatures.outputs==1,
            "The same Replay Nick still triggers the production Engine after account lookup failure");

        mc=scene(true); AdninApi.absent=true;
        replay=actor(mc,ENTITY,PROFILE,"AbsentAlias\\u00a7r","AbsentAlias\\u00a7r",null);
        tab(mc,TAB,"AbsentAlias","AbsentNick"); refresh(mc);
        check("AbsentNick".equals(AdninReplay.actorName(replay)),"Raw Replay alias maps to the unambiguous canonical Tab name");
        completed=count("replayProfileCompleted"); AdninReplay.profile("AbsentAlias"); waitForCompletion(completed);
        check(AdninReplay.isNick("AbsentAlias") && "NICK".equals(AdninReplay.profile("AbsentAlias")),"Only owned definitive absence marks statistics as Nick");
        ticks(mc,10);
        check(mc.thePlayer.messages==1 && count("anticheatAcceptedActors")==1 && mc.thePlayer.reports==0,
            "A confirmed-absent Nick also remains fully eligible for Replay detection without a UUID lookup result");
        check(count("replayProfileFailed")==count("replayProfileCompleted"),"No successful account result occurred anywhere in the integration fixture");
        mc.connection.roster.clear(); refresh(mc); ticks(mc,12);
        check(count("anticheatAcceptedActors")==0 && mc.thePlayer.messages==1,
            "Current Tab departure immediately prevents further sampling even when Nick cache remains");

        mc=scene(true);
        replay=actor(mc,ENTITY,PROFILE,"XiaoShu_SKY202","XiaoShu_SKY202",null);
        tab(mc,TAB,"XiaoShu_SKY202",null);
        net.minecraft.scoreboard.ScorePlayerTeam team=new net.minecraft.scoreboard.ScorePlayerTeam();
        team.prefix="\\u00a7cR\\u00a7f"; team.suffix="\\u00a7a6 \\u00a7e20";
        mc.connection.roster.get(TAB).team=team;
        long requests=AdninApi.requests;
        refresh(mc);
        check("XiaoShu_SKY2026".equals(AdninReplay.actorName(replay)),
            "The real Replay roster admits the complete Tab-team name despite a truncated actor profile");
        ticks(mc,10);
        check(mc.thePlayer.messages==1 && count("anticheatAcceptedActors")==1
            && mc.thePlayer.lastMessage.contains("XiaoShu_SKY2026"),
            "Production adapter and Engine emit the complete recorded name in the actual AC alert");
        check(AdninFeatures.outputs==1 && AdninFeatures.lastOutput.equals(mc.thePlayer.lastMessage),
            "AC Output receives the same complete recorded name exactly once");
        check(mc.thePlayer.reports==0 && !mc.thePlayer.lastMessage.contains("WDR") && AdninApi.requests==requests,
            "Full-name selection changes neither Replay report suppression nor independent account lookup policy");
        check("XiaoShu_SKY202".equals(replay.getGameProfile().getName()) && PROFILE.equals(replay.getGameProfile().getId())
            && ENTITY.equals(replay.getUniqueID()), "AC identity repair never rewrites actor GameProfile or UUIDs");
        mc=scene(true);replay=actor(mc,ENTITY,PROFILE,"GrayAlias","GrayAlias",null);
        tab(mc,TAB,"GrayAlias","\\u00a77GrayRecorded");refresh(mc);long beforeGray=count("replayProfileRequests");
        check("GrayRecorded".equals(AdninReplay.actorName(replay)) && AdninReplay.playerInfo("GrayRecorded")==mc.connection.roster.get(TAB),
            "Mismatched Replay UUIDs retain the same authoritative Tab color snapshot");
        ticks(mc,20);
        check(count("anticheatAcceptedActors")==0 && mc.thePlayer.messages==0 && AdninFeatures.outputs==0 && mc.thePlayer.reports==0,
            "Real Replay roster/adapter/Engine ignore a gray Tab name even when the bot's own name is unformatted");
        check(AdninReplay.profile("GrayAlias").isEmpty() && count("replayProfileRequests")==beforeGray,
            "The gray Replay bot also creates no new statistics identity query");
        mc.connection.roster.get(TAB).display=new net.minecraft.util.ChatComponentText("\\u00a7cGrayRecorded");refresh(mc);ticks(mc,9);
        check(mc.thePlayer.messages==0,"Gray Replay interval cannot accumulate hidden Autoblock evidence");
        ticks(mc,1);check(mc.thePlayer.messages==1 && AdninFeatures.outputs==1 && mc.thePlayer.reports==0,
            "Ten fresh ordinary-color Replay observations restore the unchanged detector");
        AdninReplay.clear(); AdninAnticheat.shutdown();
        System.out.println("AdninReplayAnticheatTest: "+checks+" checks passed; real Replay roster/adapter/Engine, fake game/failed-or-absent API, zero successful account lookups");
    }
}'''
    sources = []
    if source:
        sources += [ROOT/'src/java/AdninReplay.java',ROOT/'src/java/AdninReplayProfiles.java',ROOT/'src/java/AdninMatchTeams.java',ROOT/'src/java/AdninAnticheat.java']
    for name, content in fixtures.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8'); sources.append(path)
    output = work / 'classes'
    common.compile_sources(common.find_java(jdk, 'javac'), sources, str(classes), output, work / 'args.txt')
    result = subprocess.run([str(java or common.find_java(jdk, 'java')), '-Xverify:all', '-Dfile.encoding=UTF-8',
        '-cp', str(output) + os.pathsep + str(classes), 'AdninReplayAnticheatTest'],
        capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
    print((result.stdout + result.stderr).strip())
    if result.returncode: raise RuntimeError('Production Replay/Anticheat integration regression failed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--java',type=Path,help='Optional Java 8+ runtime; fixtures still compile with --jdk')
    parser.add_argument('--source',action='store_true',help='Compile current Replay/helpers against the owned fixtures')
    args = parser.parse_args()
    classes = args.classes.resolve()
    with tempfile.TemporaryDirectory(prefix='adnin-replay-roster-') as directory:
        work = Path(directory)
        sources = []
        if args.source:
            sources += [ROOT/'src/java/AdninReplay.java',ROOT/'src/java/AdninReplayProfiles.java',ROOT/'src/java/AdninMatchTeams.java']
        for name, content in FIXTURES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8'); sources.append(path)
        output = work / 'classes'
        common.compile_sources(common.find_java(args.jdk, 'javac'), sources, str(classes), output, work / 'args.txt')
        result = subprocess.run([str(args.java or common.find_java(args.jdk, 'java')), '-Xverify:all', '-Dfile.encoding=UTF-8',
            '-cp', str(output) + os.pathsep + str(classes), 'AdninReplayRosterTest'],
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
        print((result.stdout + result.stderr).strip())
        if result.returncode: raise RuntimeError('Production Replay roster regression failed')
        run_anticheat_integration(args.jdk, classes, work / 'anticheat',args.source,args.java)

if __name__ == '__main__': main()
