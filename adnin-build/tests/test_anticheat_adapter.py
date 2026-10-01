"""Run actual Anticheat adapter/core bytecode with owned offline game fixtures.

No Minecraft instance, socket, JNI payload, audio device, or real chat sender is
created. Replay roster decisions and chat/report delivery are local counters.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('ac_adapter_build_common', ROOT / 'scripts/build-java.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

FIXTURES = {
    'com/mojang/authlib/GameProfile.java': '''package com.mojang.authlib;
public final class GameProfile {
    private final java.util.UUID id; private final String name;
    public GameProfile(java.util.UUID id, String name) { this.id=id; this.name=name; }
    public java.util.UUID getId() { return id; } public String getName() { return name; }
}''',
    'net/minecraft/entity/EntityLivingBase.java': '''package net.minecraft.entity;
public class EntityLivingBase { public int hurtTime; public boolean isOnSameTeam(EntityLivingBase other) { return false; } }''',
    'net/minecraft/entity/player/PlayerCapabilities.java': '''package net.minecraft.entity.player;
public final class PlayerCapabilities { public boolean isFlying; }''',
    'net/minecraft/entity/player/EntityPlayer.java': '''package net.minecraft.entity.player;
public class EntityPlayer extends net.minecraft.entity.EntityLivingBase {
    public int ticksExisted, serverPosX, serverPosY, serverPosZ, swingProgressInt;
    public double posX, posY, posZ, lastTickPosX, lastTickPosY, lastTickPosZ;
    public float rotationPitch, rotationYaw;
    public boolean isDead, onGround, isSwingInProgress, alive=true, blocking, sprinting, using, sneaking, riding;
    public final PlayerCapabilities capabilities = new PlayerCapabilities();
    public java.util.UUID entityId;
    public com.mojang.authlib.GameProfile profile;
    public String name, display; public boolean spectator;
    public net.minecraft.item.ItemStack held;
    public EntityPlayer(java.util.UUID id, com.mojang.authlib.GameProfile gp, String name) {
        this.entityId=id; this.profile=gp; this.name=name;
    }
    public java.util.UUID getUniqueID() { return entityId; }
    public com.mojang.authlib.GameProfile getGameProfile() { return profile; }
    public String getName() { return name; }
    public net.minecraft.util.IChatComponent getDisplayName() { return new net.minecraft.util.ChatComponentText(display==null?name:display); }
    public boolean isEntityAlive() { return alive; }
    public boolean isSpectator() { return spectator; }
    public boolean isBlocking() { return blocking; } public boolean isSprinting() { return sprinting; }
    public boolean isUsingItem() { return using; } public boolean isSneaking() { return sneaking; }
    public boolean isInWater() { return false; } public boolean isInLava() { return false; }
    public boolean isOnLadder() { return false; }
    public boolean isRiding() { return riding; }
    public net.minecraft.item.ItemStack getHeldItem() { return held; }
    public net.minecraft.util.BlockPos getPosition() { return new net.minecraft.util.BlockPos(posX,posY,posZ); }
}''',
    'net/minecraft/client/entity/EntityOtherPlayerMP.java': '''package net.minecraft.client.entity;
public final class EntityOtherPlayerMP extends net.minecraft.entity.player.EntityPlayer {
    public EntityOtherPlayerMP(java.util.UUID id, com.mojang.authlib.GameProfile gp, String name) { super(id,gp,name); }
}''',
    'net/minecraft/client/entity/EntityPlayerSP.java': '''package net.minecraft.client.entity;
public final class EntityPlayerSP extends net.minecraft.entity.player.EntityPlayer {
    public int messages, reports, sounds; public String lastMessage="", lastReport="";
    public EntityPlayerSP(java.util.UUID id, com.mojang.authlib.GameProfile gp, String name) { super(id,gp,name); }
    public void addChatMessage(net.minecraft.util.IChatComponent c) { messages++; lastMessage=c.getFormattedText(); }
    public void sendChatMessage(String command) { reports++; lastReport=command; }
    public void playSound(String sound,float volume,float pitch) { sounds++; }
}''',
    'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
public final class Minecraft {
    public boolean clientThread=true, singleplayer;
    public net.minecraft.client.multiplayer.WorldClient theWorld;
    public net.minecraft.client.entity.EntityPlayerSP thePlayer;
    public net.minecraft.client.network.NetHandlerPlayClient connection;
    public boolean isCallingFromMinecraftThread() { return clientThread; }
    public boolean isSingleplayer() { return singleplayer; }
    public net.minecraft.client.network.NetHandlerPlayClient getNetHandler() { return connection; }
}''',
    'net/minecraft/world/World.java': 'package net.minecraft.world; public class World { }',
    'net/minecraft/client/multiplayer/WorldClient.java': '''package net.minecraft.client.multiplayer;
public final class WorldClient extends net.minecraft.world.World {
    public boolean rejectRosterReads;
    public boolean solidFloor; public int terrainReads;
    public final java.util.List<net.minecraft.entity.player.EntityPlayer> playerEntities = new java.util.ArrayList<net.minecraft.entity.player.EntityPlayer>() {
        public int size() { if(rejectRosterReads)throw new AssertionError("Inactive Anticheat read the entity roster"); return super.size(); }
        public java.util.Iterator<net.minecraft.entity.player.EntityPlayer> iterator() {
            if(rejectRosterReads)throw new AssertionError("Inactive Anticheat iterated the entity roster"); return super.iterator();
        }
    };
    public boolean isBlockLoaded(net.minecraft.util.BlockPos position) { return true; }
    public net.minecraft.block.state.IBlockState getBlockState(net.minecraft.util.BlockPos position) {
        terrainReads++;
        final boolean solid=solidFloor && position.getY()<=0;
        return new net.minecraft.block.state.IBlockState() { public net.minecraft.block.Block getBlock() {
            return solid?new net.minecraft.block.Block() {
                public boolean isReplaceable(net.minecraft.world.World world,net.minecraft.util.BlockPos position) { return false; }
            }:new net.minecraft.block.BlockAir();
        } };
    }
}''',
    'net/minecraft/client/network/NetworkPlayerInfo.java': '''package net.minecraft.client.network;
public final class NetworkPlayerInfo {
    public net.minecraft.util.IChatComponent display; public net.minecraft.scoreboard.ScorePlayerTeam team;
    public net.minecraft.world.WorldSettings.GameType gameType=net.minecraft.world.WorldSettings.GameType.SURVIVAL;
    private final com.mojang.authlib.GameProfile profile;
    public net.minecraft.util.IChatComponent getDisplayName() { return display; }
    public net.minecraft.scoreboard.ScorePlayerTeam getPlayerTeam() { return team; }
    public net.minecraft.world.WorldSettings.GameType getGameType() { return gameType; }
    public NetworkPlayerInfo(com.mojang.authlib.GameProfile value) { profile=value; }
    public com.mojang.authlib.GameProfile getGameProfile() { return profile; }
}''',
    'net/minecraft/client/network/NetHandlerPlayClient.java': '''package net.minecraft.client.network;
public final class NetHandlerPlayClient {
    public final java.util.Map<java.util.UUID,NetworkPlayerInfo> roster = new java.util.HashMap<java.util.UUID,NetworkPlayerInfo>();
    public NetworkPlayerInfo getPlayerInfo(java.util.UUID id) { return roster.get(id); }
    public java.util.Collection<NetworkPlayerInfo> getPlayerInfoMap() { return roster.values(); }
}''',
    'net/minecraft/scoreboard/Team.java': 'package net.minecraft.scoreboard; public class Team { public String prefix="", suffix=""; }',
    'net/minecraft/scoreboard/ScorePlayerTeam.java': '''package net.minecraft.scoreboard;
public class ScorePlayerTeam extends Team {
    public static String formatPlayerName(Team team,String name) { return team==null?name:team.prefix+name+team.suffix; }
}''',
    'net/minecraft/world/WorldSettings.java': '''package net.minecraft.world;
public final class WorldSettings { public enum GameType { SURVIVAL, SPECTATOR } }''',
    'net/minecraft/block/material/Material.java': '''package net.minecraft.block.material;
public final class Material { public static final Material water=new Material(), lava=new Material(), air=new Material(); }''',
    'net/minecraft/block/Block.java': '''package net.minecraft.block;
public class Block {
    public net.minecraft.block.material.Material getMaterial() { return net.minecraft.block.material.Material.air; }
    public boolean isReplaceable(net.minecraft.world.World world,net.minecraft.util.BlockPos position) { return true; }
}''',
    'net/minecraft/block/BlockAir.java': 'package net.minecraft.block; public final class BlockAir extends Block { }',
    'net/minecraft/block/BlockLadder.java': 'package net.minecraft.block; public final class BlockLadder extends Block { }',
    'net/minecraft/block/state/IBlockState.java': 'package net.minecraft.block.state; public interface IBlockState { net.minecraft.block.Block getBlock(); }',
    'net/minecraft/item/Item.java': 'package net.minecraft.item; public class Item { }',
    'net/minecraft/item/ItemBlock.java': 'package net.minecraft.item; public final class ItemBlock extends Item { }',
    'net/minecraft/item/ItemStack.java': '''package net.minecraft.item;
public final class ItemStack { public Item getItem() { return new ItemBlock(); } }''',
    'net/minecraft/util/BlockPos.java': '''package net.minecraft.util;
public final class BlockPos {
    private final double x,y,z;
    public BlockPos(double x,double y,double z) { this.x=x; this.y=y; this.z=z; }
    public int getY() { return (int)Math.floor(y); }
    public BlockPos down() { return down(1); } public BlockPos down(int amount) { return new BlockPos(x,y-amount,z); }
}''',
    'net/minecraft/util/IChatComponent.java': '''package net.minecraft.util;
public interface IChatComponent { String getFormattedText(); }''',
    'net/minecraft/util/ChatComponentText.java': '''package net.minecraft.util;
public final class ChatComponentText implements IChatComponent {
    private String text;
    public ChatComponentText(String text) { this.text=text; }
    public String getFormattedText() { return text; }
    public IChatComponent appendSibling(IChatComponent other) { text+=other.getFormattedText(); return this; }
    public IChatComponent setChatStyle(ChatStyle style) { return this; }
}''',
    'net/minecraft/util/ChatStyle.java': '''package net.minecraft.util;
public final class ChatStyle { public ChatStyle setChatClickEvent(net.minecraft.event.ClickEvent event) { return this; } }''',
    'net/minecraft/event/ClickEvent.java': '''package net.minecraft.event;
public final class ClickEvent { public enum Action { RUN_COMMAND } public ClickEvent(Action action,String command) { } }''',
    'AdninFeatures.java': '''public final class AdninFeatures {
    public static int outputs; public static String lastOutput="";
    public static boolean nativeGameActive=true, stopped;
    static boolean outputContextAllowed() { return !stopped && (nativeGameActive || AdninReplay.isReplay()); }
    public static void anticheatGeneratedEvent(String text) { outputs++; lastOutput=text; }
    public static boolean isRealTabProfile(String name, java.util.UUID id) {
        return AdninAnticheatCore.validPlayerName(name) && id!=null && (id.version()==1 || id.version()==4);
    }
}''',
    'AdninReplay.java': '''public final class AdninReplay {
    public static boolean replay;
    public static final java.util.Map<net.minecraft.entity.player.EntityPlayer,String> actors = new java.util.IdentityHashMap<net.minecraft.entity.player.EntityPlayer,String>();
    public static final java.util.Map<String,net.minecraft.client.network.NetworkPlayerInfo> infos = new java.util.HashMap<String,net.minecraft.client.network.NetworkPlayerInfo>();
    public static boolean isReplay() { return replay; }
    public static String actorName(net.minecraft.entity.player.EntityPlayer actor) {
        String name = replay ? actors.get(actor) : null; return name==null ? "" : name;
    }
    public static net.minecraft.client.network.NetworkPlayerInfo playerInfo(String name) {
        return replay && name!=null ? infos.get(name.toLowerCase(java.util.Locale.ROOT)) : null;
    }
}''',
    'AdninAnticheatAdapterTest.java': '''import java.util.Properties;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
public final class AdninAnticheatAdapterTest {
    static int checks;
    static final UUID LOCAL=UUID.fromString("12345678-1234-4234-9234-123456789aaa");
    static final UUID SYNTHETIC=UUID.fromString("12345678-1234-2234-9234-123456789bbb");
    static final UUID SKIN=UUID.fromString("12345678-1234-4234-9234-123456789ccc");
    static final UUID LIVE=UUID.fromString("12345678-1234-4234-9234-123456789ddd");
    static final UUID NICK=UUID.fromString("12345678-1234-1234-9234-123456789eee");
    static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
    static Properties options(int interval) {
        Properties p=new Properties(); p.setProperty("anticheat.enabled","true");
        p.setProperty("anticheat.autoReport","true"); p.setProperty("anticheat.flagSound","false");
        p.setProperty("anticheat.noFall","false"); p.setProperty("anticheat.noSlow","false");
        p.setProperty("anticheat.scaffold","false"); p.setProperty("anticheat.legitScaffold","false");
        p.setProperty("anticheat.intervalSeconds",Integer.toString(interval)); return p;
    }
    static Minecraft scene() {
        AdninAnticheat.shutdown(); AdninReplay.replay=false; AdninReplay.actors.clear(); AdninReplay.infos.clear();
        AdninMatchTeams.clear(); AdninMatchTeams.setGameActive(true);
        AdninFeatures.outputs=0; AdninFeatures.lastOutput="";
        AdninFeatures.nativeGameActive=true; AdninFeatures.stopped=false;
        Minecraft mc=new Minecraft(); mc.theWorld=new WorldClient(); mc.connection=new NetHandlerPlayClient();
        mc.thePlayer=new EntityPlayerSP(LOCAL,new GameProfile(LOCAL,"LocalFixture"),"LocalFixture");
        mc.theWorld.playerEntities.add(mc.thePlayer); return mc;
    }
    static EntityOtherPlayerMP actor(Minecraft mc,UUID entityId,UUID profileId,String profileName,String name) {
        EntityOtherPlayerMP actor=new EntityOtherPlayerMP(entityId,new GameProfile(profileId,profileName),name);
        actor.blocking=actor.isSwingInProgress=true; mc.theWorld.playerEntities.add(actor); return actor;
    }
    static void ticks(Minecraft mc,int count) {
        for(int n=0;n<count;n++) {
            for(EntityPlayer p:mc.theWorld.playerEntities) p.ticksExisted++;
            AdninAnticheat.tick(mc);
            for(EntityPlayer p:mc.theWorld.playerEntities) { p.lastTickPosX=p.posX; p.lastTickPosY=p.posY; p.lastTickPosZ=p.posZ; }
        }
    }
    static Properties diagnostics() { Properties p=new Properties(); AdninAnticheat.diagnostics(p); return p; }
    static long counter(String name) { return Long.parseLong(diagnostics().getProperty(name)); }
    static Object runtimeField(String name) throws Exception {
        java.lang.reflect.Field field=AdninAnticheat.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    static void idleTick(Minecraft mc) {
        mc.theWorld.rejectRosterReads=true;
        try { AdninAnticheat.tick(mc); } finally { mc.theWorld.rejectRosterReads=false; }
    }
    static void emptyEvidence(String context) throws Exception {
        check(((AdninAnticheatCore.Engine)runtimeField("engine")).trackedPlayers()==0,
            context+" clears detector evidence");
        check(((java.util.Set<?>)runtimeField("currentActors")).isEmpty(),context+" releases the sampled roster");
        check(((Integer)runtimeField("localTick"))==Integer.MIN_VALUE && ((Long)runtimeField("lastClientBoundPacket"))==0L,
            context+" clears local tick and packet freshness");
        check(counter("anticheatWorldActors")==0 && counter("anticheatAcceptedActors")==0
                && counter("anticheatLastSampleAgeMs")==-1,context+" clears current-sample diagnostics");
    }
    static void scopeChecks() throws Exception {
        Minecraft mc=scene(); Properties p=options(20); p.setProperty("anticheat.flagSound","true");
        AdninAnticheat.loadSettings(p);
        EntityOtherPlayerMP target=actor(mc,LIVE,LIVE,"LiveActor","LiveActor");
        mc.connection.roster.put(LIVE,new NetworkPlayerInfo(target.profile));
        AdninFeatures.nativeGameActive=false;
        long samples=counter("anticheatSampledTicks"), flags=counter("anticheatFlagDecisions"), reports=counter("anticheatReportDecisions");
        ticks(mc,30);
        check(mc.thePlayer.messages==0 && mc.thePlayer.reports==0 && mc.thePlayer.sounds==0 && AdninFeatures.outputs==0,
            "Lobby or Prequeue never emits an Anticheat alert, report, sound or party-output event");
        check(counter("anticheatSampledTicks")==samples && counter("anticheatFlagDecisions")==flags
                && counter("anticheatReportDecisions")==reports,"Inactive context adds zero samples or decisions");
        check("outside-game-or-replay".equals(diagnostics().getProperty("anticheatStatus")),"Inactive scope is reported explicitly");
        check(runtimeField("settingsKey")==null,"Inactive scope returns before constructing the settings signature");
        AdninAnticheat.packetReceived(); idleTick(mc); emptyEvidence("Inactive context");
        check(AdninAnticheat.enabled && AdninAnticheat.autoReport && AdninAnticheat.flagSound,
            "Scope gating preserves the user's Anticheat options");
        AdninFeatures.nativeGameActive=true; ticks(mc,9);
        check(mc.thePlayer.messages==0,"Entering Ingame starts a fresh complete observation window");
        ticks(mc,1);
        check(mc.thePlayer.messages==1 && mc.thePlayer.reports==1 && mc.thePlayer.sounds==1 && AdninFeatures.outputs==1,
            "Ingame detection keeps all existing enabled delivery routes");
        check(counter("anticheatCooldownEntries")==1,"Live alert creates its normal world-scoped cooldown");
        samples=counter("anticheatSampledTicks"); flags=counter("anticheatFlagDecisions"); reports=counter("anticheatReportDecisions");
        AdninFeatures.nativeGameActive=false; AdninAnticheat.packetReceived(); idleTick(mc); emptyEvidence("Leaving Ingame");
        for(int i=0;i<30;i++) { AdninAnticheat.packetReceived(); idleTick(mc); }
        check(counter("anticheatSampledTicks")==samples && counter("anticheatFlagDecisions")==flags
                && counter("anticheatReportDecisions")==reports,"Repeated waiting-room callbacks add no cumulative detection work");
        check(counter("anticheatCooldownEntries")==1,"Leaving Ingame keeps same-world report history");
        AdninFeatures.nativeGameActive=true; ticks(mc,10);
        check(mc.thePlayer.messages==1 && mc.thePlayer.reports==1 && mc.thePlayer.sounds==1 && AdninFeatures.outputs==1,
            "Same-world scope exit and reentry cannot bypass the report, alert or sound cooldown");

        mc=scene(); AdninAnticheat.loadSettings(options(0));
        target=actor(mc,LIVE,LIVE,"LiveActor","LiveActor"); mc.connection.roster.put(LIVE,new NetworkPlayerInfo(target.profile));
        ticks(mc,9); AdninFeatures.nativeGameActive=false; idleTick(mc);
        AdninFeatures.nativeGameActive=true; ticks(mc,1);
        check(mc.thePlayer.messages==0,"Pre-exit streak cannot combine with the first reentry sample");
        ticks(mc,8); check(mc.thePlayer.messages==0,"Reentry needs its own ten genuine observations");
        ticks(mc,1); check(mc.thePlayer.messages==1,"A new complete Ingame streak still detects");
        AdninFeatures.nativeGameActive=false; idleTick(mc);
        mc.connection=new NetHandlerPlayClient(); idleTick(mc);
        check(counter("anticheatCooldownEntries")==1,"Inactive same-world connection change retains report history");
        mc.theWorld=new WorldClient(); idleTick(mc);
        check(counter("anticheatCooldownEntries")==0,"A world replacement during inactive scope clears old-world history");
        mc.connection=null; AdninAnticheat.tick(mc);
        check(runtimeField("world")==null && runtimeField("connection")==null,"Disconnect cleanup precedes inactive-scope return");
        emptyEvidence("Disconnect");

        mc=scene(); AdninAnticheat.loadSettings(p); AdninFeatures.nativeGameActive=false; mc.singleplayer=true;
        target=actor(mc,SYNTHETIC,SKIN,"SkinAlias","NPC42");
        AdninReplay.replay=true; AdninReplay.actors.put(target,"RecordedActor"); ticks(mc,10);
        check(mc.thePlayer.messages==1 && AdninFeatures.outputs==1 && mc.thePlayer.sounds==1 && mc.thePlayer.reports==0,
            "Replay remains enabled even without native Ingame and with a singleplayer replay world");
        AdninReplay.replay=false; idleTick(mc); emptyEvidence("Leaving Replay");
        check(!Boolean.parseBoolean(diagnostics().getProperty("anticheatReplay")),"Replay diagnostics follow the current context");
        samples=counter("anticheatSampledTicks"); AdninReplay.replay=true; AdninFeatures.stopped=true;
        idleTick(mc); emptyEvidence("Stopped Features");
        check(counter("anticheatSampledTicks")==samples && mc.thePlayer.messages==1 && mc.thePlayer.reports==0,
            "Stopped Features cannot resume Replay sampling or delivery");
        AdninAnticheat.shutdown();
        check(runtimeField("world")==null && runtimeField("localPlayer")==null && runtimeField("connection")==null
                && counter("anticheatCooldownEntries")==0,"Unload releases identities and complete history");
        emptyEvidence("Unload");
    }
    static Properties scaffoldOptions(int interval) {
        Properties p=options(interval); p.setProperty("anticheat.autoBlock","false");
        p.setProperty("anticheat.scaffold","true"); return p;
    }
    static EntityOtherPlayerMP scaffoldActor(Minecraft mc,UUID id,String name) {
        EntityOtherPlayerMP result=actor(mc,id,id,name,name);
        result.held=new net.minecraft.item.ItemStack(); result.rotationPitch=90; result.rotationYaw=90;
        result.blocking=false; return result;
    }
    static void scaffoldTicks(Minecraft mc,EntityPlayer actor,int count) {
        for(int i=0;i<count;i++) {
            actor.posX+=.3; actor.posY+=.1+.001*((actor.ticksExisted+1)%11);
            ticks(mc,1);
        }
    }
    static void scaffoldChecks() {
        Minecraft mc=scene(); AdninAnticheat.loadSettings(scaffoldOptions(20));
        EntityOtherPlayerMP target=scaffoldActor(mc,LIVE,"LiveActor");
        mc.connection.roster.put(LIVE,new NetworkPlayerInfo(target.profile));
        scaffoldTicks(mc,target,5);
        check(mc.thePlayer.messages==0,"Actual Scaffold sampler collects five positions before its weighted threshold");
        scaffoldTicks(mc,target,1);
        check(mc.thePlayer.messages==1 && mc.thePlayer.lastMessage.contains("Scaffold"),
            "Actual absolute position/yaw sampling reaches the Mellow horizontal check despite unchanged server coordinates");
        check(AdninFeatures.outputs==1 && AdninFeatures.lastOutput.contains("LiveActor")
            && AdninFeatures.lastOutput.contains("Scaffold"),"New Scaffold uses the same local-to-Output delivery path");
        check(mc.thePlayer.reports==1,"Owned opt-in live Scaffold reporting uses the existing policy");
        scaffoldTicks(mc,target,6);
        check(mc.thePlayer.messages==1 && AdninFeatures.outputs==1 && mc.thePlayer.reports==1,
            "Repeated weighted matches cannot bypass the existing twenty-second cooldown");

        for(int exclusion=0;exclusion<3;exclusion++) {
            mc=scene(); AdninAnticheat.loadSettings(scaffoldOptions(0));
            target=scaffoldActor(mc,LIVE,"LiveActor");
            mc.connection.roster.put(LIVE,new NetworkPlayerInfo(target.profile));
            if(exclusion==0)target.riding=true;
            if(exclusion==1)target.hurtTime=1;
            if(exclusion==2)target.rotationYaw=0;
            scaffoldTicks(mc,target,8);
            check(mc.thePlayer.messages==0,"Production sampler preserves riding/damage/yaw exemption "+exclusion);
            target.riding=false; target.hurtTime=0; target.rotationYaw=90;
            scaffoldTicks(mc,target,2);
            check(mc.thePlayer.messages==1,"Removing the sampled exemption permits two real weighted matches");
        }
        mc=scene(); AdninAnticheat.loadSettings(scaffoldOptions(0));
        target=scaffoldActor(mc,SYNTHETIC,"NPC42");
        AdninReplay.replay=true; AdninReplay.actors.put(target,"RecordedActor");
        scaffoldTicks(mc,target,6);
        check(mc.thePlayer.messages==1 && AdninFeatures.lastOutput.contains("RecordedActor"),
            "Roster-admitted Replay actor reaches new Scaffold without replacing its recorded identity");
        check(mc.thePlayer.reports==0 && !mc.thePlayer.lastMessage.contains("WDR"),
            "Historical Scaffold alert has neither automatic nor clickable WDR");
        AdninReplay.actors.remove(target); scaffoldTicks(mc,target,8);
        check(mc.thePlayer.messages==1 && counter("anticheatAcceptedActors")==0,
            "Removing the current Replay admission immediately stops Scaffold too");

        mc=scene(); AdninAnticheat.loadSettings(scaffoldOptions(0));
        target=scaffoldActor(mc,NICK,"NickFixture");
        mc.connection.roster.put(NICK,new NetworkPlayerInfo(target.profile));
        scaffoldTicks(mc,target,6);
        check(mc.thePlayer.messages==1 && AdninFeatures.lastOutput.contains("NickFixture"),
            "Existing current-Tab UUIDv1 Nick admission remains accepted by Scaffold");
        mc=scene(); AdninAnticheat.loadSettings(scaffoldOptions(0));
        target=scaffoldActor(mc,SYNTHETIC,"NPC42"); scaffoldTicks(mc,target,10);
        check(mc.thePlayer.messages==0 && counter("anticheatAcceptedActors")==0,
            "Unadmitted synthetic actors remain excluded from ordinary live Scaffold");
    }
    static NetworkPlayerInfo coloredInfo(GameProfile profile,String prefix) {
        NetworkPlayerInfo info=new NetworkPlayerInfo(profile);
        info.team=new net.minecraft.scoreboard.ScorePlayerTeam(); info.team.prefix=prefix;
        return info;
    }
    static void ownNickTeamChecks() {
        for(String ownDisplay:new String[] {"\\u00a7rLocalFixture", "\\u00a7a[MVP] LocalFixture", "\\u00a7fServerNick"}) {
            Minecraft mc=scene(); Properties p=options(20);
            p.setProperty("anticheat.ignoreTeammates","true"); p.setProperty("anticheat.flagSound","true");
            AdninAnticheat.loadSettings(p); mc.thePlayer.display=ownDisplay;
            mc.connection.roster.put(LOCAL,coloredInfo(new GameProfile(LOCAL,"ServerNick"),"\\u00a7c"));
            EntityOtherPlayerMP mate=actor(mc,LIVE,LIVE,"RedMate","RedMate");
            mate.display="\\u00a7cRedMate";
            mc.connection.roster.put(LIVE,coloredInfo(mate.profile,"\\u00a7c"));
            ticks(mc,20);
            check(AdninMatchTeams.isTeammate(mate),"Own Nick uses server team identity despite original/reset/rank display");
            check(mc.thePlayer.messages==0 && mc.thePlayer.reports==0 && mc.thePlayer.sounds==0 && AdninFeatures.outputs==0,
                "Ignore Teammates suppresses all delivery paths for actual teammate evidence while self is nicked");
            mate.display="\\u00a77RedMate"; mc.thePlayer.ticksExisted=0; ticks(mc,20);
            check(AdninMatchTeams.isTeammate(mate) && mc.thePlayer.messages==0 && mc.thePlayer.reports==0
                    && mc.thePlayer.sounds==0 && AdninFeatures.outputs==0,
                "Same-match respawn tick reset and temporary gray nametag cannot re-enable teammate detections");
            mc.thePlayer.spectator=true; ticks(mc,20); mc.thePlayer.spectator=false; ticks(mc,20);
            check(AdninMatchTeams.isTeammate(mate) && mc.thePlayer.messages==0 && mc.thePlayer.reports==0
                    && mc.thePlayer.sounds==0 && AdninFeatures.outputs==0,
                "Temporary local spectator respawn preserves teammate exemption across every alert delivery path");
            EntityOtherPlayerMP enemy=actor(mc,SKIN,SKIN,"BlueEnemy","BlueEnemy");
            enemy.display="\\u00a79BlueEnemy";
            mc.connection.roster.put(SKIN,coloredInfo(enemy.profile,"\\u00a79")); ticks(mc,10);
            check(mc.thePlayer.messages==1 && mc.thePlayer.lastMessage.contains("BlueEnemy")
                    && mc.thePlayer.reports==1 && mc.thePlayer.sounds==1 && AdninFeatures.outputs==1,
                "Own Nick does not globally suppress enemy checks or their selected Output path");
            AdninAnticheat.ignoreTeammates=false; ticks(mc,10);
            check(mc.thePlayer.messages==1 && AdninFeatures.outputs==1,
                "Disabling Ignore Teammates cannot bypass the independent gray respawn pause");
            mate.display="\\u00a7cRedMate"; ticks(mc,10);
            check(mc.thePlayer.messages==2 && mc.thePlayer.lastMessage.contains("RedMate") && AdninFeatures.outputs==2,
                "Disabling Ignore Teammates restores ordinary evidence collection once actual color recovers");
        }
        Minecraft mc=scene(); Properties p=options(20);
        p.setProperty("anticheat.ignoreTeammates","true"); AdninAnticheat.loadSettings(p);
        mc.thePlayer.display="\\u00a7rLocalFixture";
        mc.connection.roster.put(LOCAL,coloredInfo(new GameProfile(LOCAL,"ServerNick"),"\\u00a7c"));
        mc.connection.roster.put(LIVE,coloredInfo(new GameProfile(LIVE,"RecordedMate"),"\\u00a7c"));
        EntityOtherPlayerMP replayMate=actor(mc,SYNTHETIC,SKIN,"SkinAlias","NPC42");
        AdninReplay.replay=true; AdninReplay.actors.put(replayMate,"RecordedMate"); ticks(mc,20);
        check(AdninMatchTeams.isTeammate("RecordedMate") && !AdninMatchTeams.isTeammate(replayMate),
            "Replay fixture keeps the admitted Tab identity distinct from all entity UUID/name aliases");
        check(mc.thePlayer.messages==0 && mc.thePlayer.reports==0 && AdninFeatures.outputs==0,
            "Validated Replay name shares the teammate exemption without trusting the bot's skin profile");
        mc.connection.roster.put(NICK,coloredInfo(new GameProfile(NICK,"RecordedEnemy"),"\\u00a79"));
        replayMate.name="RecordedMate";
        AdninReplay.actors.put(replayMate,"RecordedEnemy"); ticks(mc,10);
        check(mc.thePlayer.messages==1 && mc.thePlayer.lastMessage.contains("RecordedEnemy")
                && mc.thePlayer.reports==0 && AdninFeatures.outputs==1,
            "A Replay opponent cannot inherit teammate exemption from a conflicting raw/skin alias");
    }
    static Properties eagleOptions() {
        Properties p=options(20); p.setProperty("anticheat.autoBlock","false");
        p.setProperty("anticheat.legitScaffold","true"); p.setProperty("anticheat.flagSound","true");
        return p;
    }
    static void eaglePose(EntityPlayer actor) {
        actor.held=new net.minecraft.item.ItemStack(); actor.rotationPitch=70; actor.rotationYaw=180;
        actor.blocking=false; actor.isSwingInProgress=false; actor.sneaking=false;
    }
    static EntityOtherPlayerMP eagleActor(Minecraft mc,UUID id,String name) {
        EntityOtherPlayerMP result=actor(mc,id,id,name,name); eaglePose(result); return result;
    }
    static void eagleStep(Minecraft mc,boolean crouch,boolean swing,EntityPlayer... actors) {
        for(EntityPlayer actor:actors) {
            actor.posZ+=.1; actor.sneaking=crouch; actor.isSwingInProgress=swing;
        }
        ticks(mc,1);
    }
    static void eagleCycles(Minecraft mc,int count,EntityPlayer... actors) {
        for(int i=0;i<count;i++) {
            eagleStep(mc,true,false,actors);
            eagleStep(mc,false,true,actors);
            eagleStep(mc,false,false,actors);
        }
    }
    static void eagleChecks() throws Exception {
        Minecraft mc=scene(); AdninAnticheat.loadSettings(eagleOptions());
        EntityOtherPlayerMP target=eagleActor(mc,LIVE,"LiveActor");
        mc.connection.roster.put(LIVE,new NetworkPlayerInfo(target.profile));
        eagleStep(mc,false,false,target); eagleCycles(mc,2,target);
        check(mc.thePlayer.messages==0 && AdninFeatures.outputs==0,
            "Mellow Eagle first two crouch-release/swing pairs remain below the weighted threshold");
        eagleCycles(mc,2,target);
        check(!AdninAnticheat.scaffold && AdninAnticheat.legitScaffold && mc.thePlayer.messages==1
                && mc.thePlayer.lastMessage.contains("Legit scaffold"),
            "Only Legit Scaffold enabled still samples the real backwards yaw and detects four qualified pairs");
        check(AdninFeatures.outputs==1 && AdninFeatures.lastOutput.contains("LiveActor")
                && AdninFeatures.lastOutput.contains("Legit scaffold") && !AdninFeatures.lastOutput.contains("WDR"),
            "New Eagle offers the actual local detection content to Output once without report decorations");
        check(mc.thePlayer.reports==1 && "/wdr LiveActor".equals(mc.thePlayer.lastReport) && mc.thePlayer.sounds==1,
            "Live Eagle uses the existing opt-in report and sound delivery");
        eagleCycles(mc,4,target);
        check(mc.thePlayer.messages==1 && AdninFeatures.outputs==1 && mc.thePlayer.reports==1,
            "Repeated Eagle evidence cannot bypass the selected alert or report cooldown");

        for(int exclusion=0;exclusion<6;exclusion++) {
            mc=scene(); AdninAnticheat.loadSettings(eagleOptions());
            target=eagleActor(mc,LIVE,"LiveActor");
            mc.connection.roster.put(LIVE,new NetworkPlayerInfo(target.profile));
            if(exclusion==0)target.rotationYaw=0;
            if(exclusion==1)target.rotationPitch=69;
            if(exclusion==2)target.held=null;
            if(exclusion==3)target.rotationYaw=Float.NaN;
            eagleStep(mc,false,false,target);
            if(exclusion<4)eagleCycles(mc,4,target);
            else for(int i=0;i<4;i++) {
                eagleStep(mc,true,exclusion==5,target);
                if(exclusion==4) {
                    eagleStep(mc,true,false,target); eagleStep(mc,true,false,target);
                }
                eagleStep(mc,false,exclusion==4,target);
                eagleStep(mc,false,false,target);
            }
            check(mc.thePlayer.messages==0 && AdninFeatures.outputs==0 && mc.thePlayer.reports==0
                    && mc.thePlayer.sounds==0,"Actual Eagle negative yaw/pitch/block/timing sequence stays silent: "+exclusion);
            if(exclusion==0) {
                target.rotationYaw=180; eagleCycles(mc,2,target);
                check(mc.thePlayer.messages==1,
                    "Changing only the observed yaw supplies the missing backwards weighting to the same live actor");
            }
        }

        for(String ownDisplay:new String[] {"\\u00a7rLocalFixture", "\\u00a7a[MVP] LocalFixture", "\\u00a7fServerNick"}) {
            mc=scene(); Properties p=eagleOptions(); p.setProperty("anticheat.ignoreTeammates","true");
            AdninAnticheat.loadSettings(p); mc.thePlayer.display=ownDisplay; eaglePose(mc.thePlayer);
            mc.connection.roster.put(LOCAL,coloredInfo(new GameProfile(LOCAL,"ServerNick"),"\\u00a7c"));
            EntityOtherPlayerMP mate=eagleActor(mc,NICK,"NickMate"); mate.display="\\u00a7cNickMate";
            mc.connection.roster.put(NICK,coloredInfo(mate.profile,"\\u00a7c"));
            EntityOtherPlayerMP enemy=eagleActor(mc,SKIN,"BlueEnemy"); enemy.display="\\u00a79BlueEnemy";
            mc.connection.roster.put(SKIN,coloredInfo(enemy.profile,"\\u00a79"));
            eagleStep(mc,false,false,mc.thePlayer,mate,enemy); eagleCycles(mc,4,mc.thePlayer,mate,enemy);
            check(AdninMatchTeams.isTeammate(mate) && counter("anticheatAcceptedActors")==2,
                "Nicked self is never sampled and its server team color identifies the UUIDv1 Nick teammate");
            check(mc.thePlayer.messages==1 && mc.thePlayer.lastMessage.contains("BlueEnemy")
                    && mc.thePlayer.reports==1 && mc.thePlayer.sounds==1 && AdninFeatures.outputs==1,
                "Identical Eagle evidence from self and Nick teammate stays silent while the opponent still detects");
            AdninAnticheat.ignoreTeammates=false;
            eagleStep(mc,false,false,mc.thePlayer,mate,enemy); eagleCycles(mc,4,mc.thePlayer,mate,enemy);
            check(mc.thePlayer.messages==2 && mc.thePlayer.lastMessage.contains("NickMate") && AdninFeatures.outputs==2
                    && "/wdr NickMate".equals(mc.thePlayer.lastReport),
                "Disabling teammate exclusion restores Nick Eagle detection without bypassing the enemy cooldown");
        }

        mc=scene(); AdninAnticheat.loadSettings(eagleOptions()); AdninFeatures.nativeGameActive=false;
        mc.singleplayer=true; target=actor(mc,SYNTHETIC,SKIN,"SkinAlias","NPC42"); eaglePose(target);
        mc.connection.roster.put(NICK,new NetworkPlayerInfo(new GameProfile(NICK,"RecordedNick")));
        AdninReplay.replay=true; AdninReplay.actors.put(target,"RecordedNick");
        eagleStep(mc,false,false,target); eagleCycles(mc,4,target);
        check(mc.thePlayer.messages==1 && counter("anticheatAcceptedActors")==1
                && mc.thePlayer.lastMessage.contains("RecordedNick") && !mc.thePlayer.lastMessage.contains("SkinAlias"),
            "Real Eagle adapter samples a validated Replay bot under its recorded Nick despite different UUID and skin profile");
        check(mc.thePlayer.reports==0 && !mc.thePlayer.lastMessage.contains("WDR") && AdninFeatures.outputs==1,
            "Replay Eagle emits local and selected Output detection text without automatic or clickable WDR");
        AdninReplay.actors.remove(target); eagleCycles(mc,4,target);
        check(mc.thePlayer.messages==1 && AdninFeatures.outputs==1 && counter("anticheatAcceptedActors")==0,
            "Losing current Replay admission immediately retires Eagle observations");

        mc=scene(); Properties p=eagleOptions(); p.setProperty("anticheat.ignoreTeammates","true");
        AdninAnticheat.loadSettings(p); mc.thePlayer.display="\\u00a7rLocalFixture";
        mc.connection.roster.put(LOCAL,coloredInfo(new GameProfile(LOCAL,"ServerNick"),"\\u00a7c"));
        mc.connection.roster.put(NICK,coloredInfo(new GameProfile(NICK,"RecordedMate"),"\\u00a7c"));
        target=actor(mc,SYNTHETIC,SKIN,"SkinAlias","NPC42"); eaglePose(target);
        AdninReplay.replay=true; AdninReplay.actors.put(target,"RecordedMate");
        eagleStep(mc,false,false,target); eagleCycles(mc,4,target);
        check(AdninMatchTeams.isTeammate("RecordedMate") && mc.thePlayer.messages==0 && AdninFeatures.outputs==0,
            "Eagle ignores a validated Replay teammate when self uses a server Nick");
        mc.connection.roster.put(LIVE,coloredInfo(new GameProfile(LIVE,"RecordedEnemy"),"\\u00a79"));
        target.name="RecordedMate"; AdninReplay.actors.put(target,"RecordedEnemy");
        eagleStep(mc,false,false,target); eagleCycles(mc,4,target);
        check(mc.thePlayer.messages==1 && mc.thePlayer.lastMessage.contains("RecordedEnemy")
                && mc.thePlayer.reports==0 && AdninFeatures.outputs==1,
            "Replay Eagle trusts the current recorded opponent rather than a conflicting teammate entity alias");

        mc=scene(); AdninAnticheat.loadSettings(eagleOptions());
        target=eagleActor(mc,LIVE,"LiveActor");
        mc.connection.roster.put(LIVE,new NetworkPlayerInfo(target.profile)); AdninFeatures.nativeGameActive=false;
        long samples=counter("anticheatSampledTicks");
        eagleStep(mc,false,false,target); eagleCycles(mc,4,target); idleTick(mc);
        check(counter("anticheatSampledTicks")==samples && mc.thePlayer.messages==0 && AdninFeatures.outputs==0
                && mc.thePlayer.reports==0 && mc.thePlayer.sounds==0,
            "Inactive Eagle never samples a roster or emits chat, Output, report or sound");
        AdninFeatures.nativeGameActive=true; eagleStep(mc,false,false,target); eagleCycles(mc,2,target);
        AdninFeatures.nativeGameActive=false; idleTick(mc); emptyEvidence("Leaving partial Eagle sequence");
        AdninFeatures.nativeGameActive=true; eagleStep(mc,false,false,target); eagleCycles(mc,2,target);
        check(mc.thePlayer.messages==0 && AdninFeatures.outputs==0,
            "Eagle cannot combine two pre-exit pairs with two post-entry pairs");
        eagleCycles(mc,2,target);
        check(mc.thePlayer.messages==1 && AdninFeatures.outputs==1,
            "Four newly admitted pairs after reentry can detect normally");
    }
    static final class SnapshotCallbackActor extends EntityPlayer {
        Minecraft nested; Object nestedBuffer; boolean stop, fail;
        SnapshotCallbackActor(UUID id,String name) { super(id,new GameProfile(id,name),name); }
        @Override public boolean isBlocking() {
            try {
                check(runtimeField("spareSnapshot")==null,"An active sample exclusively borrows the cache slot");
                if(nested!=null) {
                    Minecraft next=nested; nested=null; AdninAnticheat.tick(next);
                    nestedBuffer=runtimeField("spareSnapshot");
                }
                if(stop) { stop=false; AdninAnticheat.shutdown(); }
                if(fail) { fail=false; throw new IllegalStateException("owned sample failure"); }
                return false;
            } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
              catch(Exception e) { if(e instanceof RuntimeException)throw (RuntimeException)e; throw new AssertionError(e); }
        }
    }
    static void snapshotReuseChecks() throws Exception {
        Minecraft mc=scene(); AdninAnticheat.loadSettings(options(20));
        EntityOtherPlayerMP first=actor(mc,LIVE,LIVE,"LiveActor","LiveActor");
        EntityOtherPlayerMP second=actor(mc,NICK,NICK,"NickFixture","NickFixture");
        first.blocking=second.blocking=false;
        mc.connection.roster.put(LIVE,new NetworkPlayerInfo(first.profile));
        mc.connection.roster.put(NICK,new NetworkPlayerInfo(second.profile));
        ticks(mc,1); Object buffer=runtimeField("spareSnapshot");
        check(buffer!=null,"First accepted actor creates one reusable sample buffer");
        for(int i=0;i<20;i++) {
            ticks(mc,1);
            check(runtimeField("spareSnapshot")==buffer,"Actor rotation and following ticks reuse the same borrowed buffer");
        }
        check(((AdninAnticheatCore.Snapshot)buffer).name.isEmpty(),"Idle sample buffer retains no player name");
        for(Class<?> type:AdninAnticheatCore.class.getDeclaredClasses()) for(java.lang.reflect.Field field:type.getDeclaredFields())
            check(field.getType()!=AdninAnticheatCore.Snapshot.class,"Core state does not retain a typed Snapshot reference: "+field.getName());
        java.lang.reflect.Field players=AdninAnticheatCore.Engine.class.getDeclaredField("players");players.setAccessible(true);
        for(Object state:((java.util.Map<?,?>)players.get(runtimeField("engine"))).values()) {
            for(java.lang.reflect.Field field:state.getClass().getDeclaredFields()) {
                field.setAccessible(true); check(field.get(state)!=buffer,"Core copies observations rather than retaining the borrowed sample");
            }
        }
        java.lang.reflect.Method fill=AdninAnticheat.class.getDeclaredMethod("snapshot",Minecraft.class,EntityPlayer.class,
            String.class,long.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class,
            AdninAnticheatCore.Settings.class,AdninAnticheatCore.Snapshot.class); fill.setAccessible(true);
        AdninAnticheatCore.Snapshot reused=new AdninAnticheatCore.Snapshot();
        for(int mode=0;mode<4;mode++) for(EntityOtherPlayerMP actor:new EntityOtherPlayerMP[]{first,second}) {
            AdninAnticheatCore.Settings cfg=new AdninAnticheatCore.Settings(); cfg.enabled=true;
            cfg.noFall=false; cfg.scaffold=(mode&1)!=0; cfg.legitScaffold=(mode&2)!=0;
            first.posX=12; first.posY=80; first.posZ=-3; first.rotationYaw=137;
            first.rotationPitch=89; first.hurtTime=3; first.riding=true; first.held=new net.minecraft.item.ItemStack();
            first.serverPosX=384; first.sneaking=true; first.using=true; first.capabilities.isFlying=true;
            for(java.lang.reflect.Field field:AdninAnticheatCore.Snapshot.class.getFields()) {
                Class<?> type=field.getType();
                if(type==boolean.class)field.setBoolean(reused,true);
                else if(type==int.class)field.setInt(reused,987);
                else if(type==long.class)field.setLong(reused,987L);
                else if(type==double.class)field.setDouble(reused,Double.NaN);
                else if(type==float.class)field.setFloat(reused,Float.NaN);
                else if(type==String.class)field.set(reused,"LeakedActor");
            }
            AdninAnticheatCore.Snapshot fresh=new AdninAnticheatCore.Snapshot();
            fill.invoke(null,mc,actor,actor.name,100L,true,true,false,false,false,cfg,reused);
            fill.invoke(null,mc,actor,actor.name,100L,true,true,false,false,false,cfg,fresh);
            for(java.lang.reflect.Field field:AdninAnticheatCore.Snapshot.class.getFields())
                check(field.get(reused).equals(field.get(fresh)),"Reused sample matches clean allocation after actor/config change: "+field.getName());
        }
        AdninAnticheat.shutdown();
        check(runtimeField("spareSnapshot")==null,"Shutdown releases the reusable sample");

        for(int action=0;action<3;action++) {
            mc=scene(); AdninAnticheat.loadSettings(options(20));
            SnapshotCallbackActor probe=new SnapshotCallbackActor(LIVE,"ProbeActor");
            probe.serverPosX=32; probe.serverPosZ=64; probe.ticksExisted=1;
            mc.thePlayer.ticksExisted=1; mc.theWorld.playerEntities.add(probe);
            mc.connection.roster.put(LIVE,new NetworkPlayerInfo(probe.profile));
            if(action==0) {
                Minecraft inner=new Minecraft(); inner.theWorld=new WorldClient(); inner.connection=new NetHandlerPlayClient();
                inner.thePlayer=new EntityPlayerSP(LOCAL,new GameProfile(LOCAL,"InnerLocal"),"InnerLocal");
                inner.thePlayer.ticksExisted=42; inner.theWorld.playerEntities.add(inner.thePlayer);
                EntityOtherPlayerMP other=actor(inner,NICK,NICK,"InnerActor","InnerActor");
                other.serverPosX=777; other.serverPosZ=888; other.ticksExisted=42; other.blocking=false;
                inner.connection.roster.put(NICK,new NetworkPlayerInfo(other.profile)); probe.nested=inner;
            } else if(action==1)probe.stop=true;
            else probe.fail=true;
            try { AdninAnticheat.tick(mc); check(action!=2,"Expected owned sampling failure was thrown"); }
            catch(IllegalStateException expected) { check(action==2,"Only the owned failure escapes sampling"); }
            AdninAnticheatCore.Snapshot returned=(AdninAnticheatCore.Snapshot)runtimeField("spareSnapshot");
            if(action==0)check(returned!=null && returned!=probe.nestedBuffer && returned.serverX==32
                    && returned.serverZ==64 && returned.sampleTick==1,
                "Same-thread reentry obtains its own buffer and cannot overwrite the outer partial sample");
            else if(action==1)check(returned==null,"Shutdown during a callback prevents stale buffer republication");
            else check(returned!=null && returned.name.isEmpty(),"Exceptional sample releases its buffer without retaining identity text");
        }
        AdninAnticheat.shutdown();
    }
    public static void main(String[] args) throws Exception {
        scopeChecks();
        ownNickTeamChecks();
        Minecraft mc=scene(); Properties p=options(20); AdninAnticheat.loadSettings(p);
        EntityOtherPlayerMP replayActor=actor(mc,SYNTHETIC,SKIN,"SkinAlias","NPC42");
        mc.connection.roster.put(LIVE,new NetworkPlayerInfo(new GameProfile(LIVE,"RecordedActor")));
        AdninReplay.replay=true; AdninReplay.actors.put(replayActor,"RecordedActor");
        ticks(mc,9);
        check(mc.thePlayer.messages==0,"Replay actor still needs the unchanged ten observations");
        ticks(mc,1);
        check(mc.thePlayer.messages==1,"Actual adapter detects roster-validated Replay bot despite all UUID/name mismatches");
        check(mc.thePlayer.reports==0 && !mc.thePlayer.lastMessage.contains("[WDR]"),"Historical actor has neither automatic nor clickable WDR");
        check(AdninFeatures.outputs==1 && AdninFeatures.lastOutput.equals(mc.thePlayer.lastMessage),
            "Replay alert offers its actual local detection text to Output exactly once");
        check(counter("anticheatAcceptedActors")==1,"Anonymous diagnostics reflect the admitted current Replay actor");
        check(!diagnostics().toString().contains("RecordedActor") && !diagnostics().toString().contains(SYNTHETIC.toString()),
            "Diagnostics contain neither historical names nor UUIDs");

        mc=scene(); AdninAnticheat.loadSettings(p);
        actor(mc,SYNTHETIC,SKIN,"SkinAlias","NPC42");
        mc.connection.roster.put(LIVE,new NetworkPlayerInfo(new GameProfile(LIVE,"RecordedActor")));
        ticks(mc,12);
        check(mc.thePlayer.messages==0 && counter("anticheatAcceptedActors")==0,
            "The same synthetic bot is still rejected in ordinary multiplayer");

        mc=scene(); AdninAnticheat.loadSettings(p);
        EntityOtherPlayerMP unknown=actor(mc,LIVE,LIVE,"Viewer","Viewer");
        mc.connection.roster.put(LIVE,new NetworkPlayerInfo(unknown.profile)); AdninReplay.replay=true;
        ticks(mc,12);
        check(mc.thePlayer.messages==0 && counter("anticheatAcceptedActors")==0,
            "Even a normal UUID is rejected when absent from the validated Replay actor snapshot");

        mc=scene(); AdninAnticheat.loadSettings(p);
        EntityOtherPlayerMP nick=actor(mc,NICK,NICK,"NickFixture","NickFixture");
        mc.connection.roster.put(NICK,new NetworkPlayerInfo(nick.profile)); ticks(mc,10);
        check(NICK.version()==1 && mc.thePlayer.messages==1 && counter("anticheatAcceptedActors")==1,
            "A current-Tab UUID version 1 Nick reaches the actual adapter and detector without account lookup");
        check(AdninFeatures.outputs==1 && AdninFeatures.lastOutput.contains("NickFixture")
            && AdninFeatures.lastOutput.contains("Autoblock") && !AdninFeatures.lastOutput.contains("WDR")
            && !AdninFeatures.lastOutput.contains("/wdr"),
            "Nick alert Output contains actual detection text before the local WDR component");
        ticks(mc,20);
        check(AdninFeatures.outputs==1 && mc.thePlayer.messages==1,
            "Nick alert Output shares the unchanged local alert cooldown");

        mc=scene(); AdninAnticheat.loadSettings(p);
        EntityOtherPlayerMP live=actor(mc,LIVE,LIVE,"LiveActor","LiveActor");
        mc.connection.roster.put(LIVE,new NetworkPlayerInfo(live.profile)); ticks(mc,10);
        check(mc.thePlayer.messages==1 && mc.thePlayer.reports==1 && "/wdr LiveActor".equals(mc.thePlayer.lastReport),
            "Owned live fixture records the expected opt-in command without a real sender");
        p.setProperty("anticheat.flagSound","true"); AdninAnticheat.loadSettings(p); ticks(mc,10);
        check(mc.thePlayer.messages==1 && mc.thePlayer.reports==1,"Changing sound through the actual adapter cannot bypass cooldown");
        AdninAnticheat.enabled=false; ticks(mc,1); AdninAnticheat.enabled=true; ticks(mc,10);
        check(mc.thePlayer.reports==1,"Actual runtime main-switch off/on retains report cooldown");
        mc.theWorld.playerEntities.remove(live); live=actor(mc,LIVE,LIVE,"LiveActor","LiveActor"); ticks(mc,10);
        check(mc.thePlayer.reports==1,"Same UUID entity replacement retains current-world cooldown");
        mc.theWorld.playerEntities.remove(live); mc.connection.roster.remove(LIVE); ticks(mc,1);
        mc.theWorld.playerEntities.add(live); mc.connection.roster.put(LIVE,new NetworkPlayerInfo(live.profile)); ticks(mc,10);
        check(mc.thePlayer.reports==1,"A roster gap cannot cause another automatic report");
        AdninAnticheat.resetEvidence(); ticks(mc,10);
        check(mc.thePlayer.reports==1,"Error recovery clears evidence without losing report history");
        long before=counter("anticheatSampledTicks"); mc.clientThread=false; AdninAnticheat.tick(mc); mc.clientThread=true;
        check(counter("anticheatSampledTicks")==before,"Wrong-thread callback cannot sample or modify game evidence");
        mc.theWorld=new WorldClient(); mc.theWorld.playerEntities.add(mc.thePlayer);
        live=actor(mc,LIVE,LIVE,"LiveActor","LiveActor"); ticks(mc,10);
        check(mc.thePlayer.reports==2,"A genuine world replacement starts a new world-scoped report history");
        mc.connection=new NetHandlerPlayClient(); mc.connection.roster.put(LIVE,new NetworkPlayerInfo(live.profile)); ticks(mc,10);
        check(mc.thePlayer.reports==2,"Replacing a connection in the same world clears samples but keeps report history");
        mc.connection=null; AdninAnticheat.tick(mc);
        check(counter("anticheatCooldownEntries")==0,"Actual disconnect clears old-world cooldown and packet context");

        mc=scene(); AdninAnticheat.loadSettings(options(0));
        replayActor=actor(mc,SYNTHETIC,SKIN,"SkinAlias","NPC42");
        AdninReplay.replay=true; AdninReplay.actors.put(replayActor,"RecordedActor"); ticks(mc,9);
        replayActor.posX=5; replayActor.serverPosX=160; ticks(mc,1);
        check(mc.thePlayer.messages==0,"Observed Replay seek cannot complete a pre-seek streak");
        ticks(mc,8); check(mc.thePlayer.messages==0,"Post-seek observations rebuild from a new baseline");
        ticks(mc,1); check(mc.thePlayer.messages==1 && mc.thePlayer.reports==0,"Post-seek genuine evidence can flag locally, never report");
        AdninReplay.actors.remove(replayActor); ticks(mc,12);
        check(mc.thePlayer.messages==1 && counter("anticheatAcceptedActors")==0,
            "Removing an actor from the current Replay snapshot immediately stops detection");

        mc=scene(); AdninAnticheat.loadSettings(options(0));
        live=actor(mc,LIVE,LIVE,"LiveActor","LiveActor"); mc.connection.roster.put(LIVE,new NetworkPlayerInfo(live.profile));
        ticks(mc,9); for(int i=0;i<50;i++)AdninAnticheat.tick(mc);
        check(mc.thePlayer.messages==0,"Repeated callbacks for a paused local tick do not fabricate observations");
        ticks(mc,1); check(mc.thePlayer.messages==1,"The next genuine completed tick reaches the unchanged threshold");
        check(mc.thePlayer.sounds==0,"Disabled sound never enters even the owned audio counter");
        AdninAnticheat.tick(null);
        check("no-client".equals(diagnostics().getProperty("anticheatStatus")),"Null client retires runtime context safely");
        scaffoldChecks();
        eagleChecks();
        snapshotReuseChecks();
        System.out.println("AdninAnticheatAdapterTest: "+checks+" checks passed; production bytecode, offline game/roster/chat fixtures");
    }
}''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--java', type=Path, help='Optional Java 8+ runtime; fixture compilation still uses --jdk')
    parser.add_argument('--source', action='store_true', help='Compile current helper and sampler source against owned game fixtures')
    args = parser.parse_args()
    classes = args.classes.resolve()
    for name in ('AdninAnticheat', 'AdninAnticheatCore', 'AdninAnticheatCore$AlertHistory'):
        if not (classes / (name + '.class')).is_file():
            raise ValueError('Missing production class: ' + name)
    with tempfile.TemporaryDirectory(prefix='adnin-ac-adapter-') as directory:
        work = Path(directory)
        sources = [ROOT / 'tests/java/AdninLightGrayAnticheatTest.java']
        if args.source:
            sources += [ROOT / 'src/java/AdninMatchTeams.java', ROOT / 'src/java/AdninAnticheat.java']
        for name, content in FIXTURES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(path)
        output = work / 'classes'
        common.compile_sources(common.find_java(args.jdk, 'javac'), sources, str(classes), output, work / 'args.txt')
        java = args.java.resolve() if args.java else common.find_java(args.jdk, 'java')
        for test in ('AdninAnticheatAdapterTest', 'AdninLightGrayAnticheatTest'):
            result = subprocess.run([str(java), '-Xverify:all', '-Dfile.encoding=UTF-8',
                                     '-cp', str(output) + os.pathsep + str(classes), test],
                                    capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
            print((result.stdout + result.stderr).strip())
            if result.returncode:
                raise RuntimeError('Offline production Anticheat adapter regression failed: ' + test)


if __name__ == '__main__':
    main()
