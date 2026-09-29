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
    public String name;
    public net.minecraft.item.ItemStack held;
    public EntityPlayer(java.util.UUID id, com.mojang.authlib.GameProfile gp, String name) {
        this.entityId=id; this.profile=gp; this.name=name;
    }
    public java.util.UUID getUniqueID() { return entityId; }
    public com.mojang.authlib.GameProfile getGameProfile() { return profile; }
    public String getName() { return name; }
    public net.minecraft.util.IChatComponent getDisplayName() { return new net.minecraft.util.ChatComponentText(name); }
    public boolean isEntityAlive() { return alive; }
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
    public final java.util.List<net.minecraft.entity.player.EntityPlayer> playerEntities = new java.util.ArrayList<net.minecraft.entity.player.EntityPlayer>();
    public boolean isBlockLoaded(net.minecraft.util.BlockPos position) { return true; }
    public net.minecraft.block.state.IBlockState getBlockState(net.minecraft.util.BlockPos position) {
        return new net.minecraft.block.state.IBlockState() { public net.minecraft.block.Block getBlock() { return new net.minecraft.block.BlockAir(); } };
    }
}''',
    'net/minecraft/client/network/NetworkPlayerInfo.java': '''package net.minecraft.client.network;
public final class NetworkPlayerInfo {
    private final com.mojang.authlib.GameProfile profile;
    public NetworkPlayerInfo(com.mojang.authlib.GameProfile value) { profile=value; }
    public com.mojang.authlib.GameProfile getGameProfile() { return profile; }
}''',
    'net/minecraft/client/network/NetHandlerPlayClient.java': '''package net.minecraft.client.network;
public final class NetHandlerPlayClient {
    public final java.util.Map<java.util.UUID,NetworkPlayerInfo> roster = new java.util.HashMap<java.util.UUID,NetworkPlayerInfo>();
    public NetworkPlayerInfo getPlayerInfo(java.util.UUID id) { return roster.get(id); }
}''',
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
    public static void anticheatGeneratedEvent(String text) { outputs++; lastOutput=text; }
    public static boolean isRealTabProfile(String name, java.util.UUID id) {
        return AdninAnticheatCore.validPlayerName(name) && id!=null && (id.version()==1 || id.version()==4);
    }
}''',
    'AdninReplay.java': '''public final class AdninReplay {
    public static boolean replay;
    public static final java.util.Map<net.minecraft.entity.player.EntityPlayer,String> actors = new java.util.IdentityHashMap<net.minecraft.entity.player.EntityPlayer,String>();
    public static boolean isReplay() { return replay; }
    public static String actorName(net.minecraft.entity.player.EntityPlayer actor) {
        String name = replay ? actors.get(actor) : null; return name==null ? "" : name;
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
        AdninAnticheat.shutdown(); AdninReplay.replay=false; AdninReplay.actors.clear();
        AdninFeatures.outputs=0; AdninFeatures.lastOutput="";
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
    public static void main(String[] args) {
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
        System.out.println("AdninAnticheatAdapterTest: "+checks+" checks passed; production bytecode, offline game/roster/chat fixtures");
    }
}''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    args = parser.parse_args()
    classes = args.classes.resolve()
    for name in ('AdninAnticheat', 'AdninAnticheatCore', 'AdninAnticheatCore$AlertHistory'):
        if not (classes / (name + '.class')).is_file():
            raise ValueError('Missing production class: ' + name)
    with tempfile.TemporaryDirectory(prefix='adnin-ac-adapter-') as directory:
        work = Path(directory)
        sources = []
        for name, content in FIXTURES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(path)
        output = work / 'classes'
        common.compile_sources(common.find_java(args.jdk, 'javac'), sources, str(classes), output, work / 'args.txt')
        result = subprocess.run([str(common.find_java(args.jdk, 'java')), '-Xverify:all', '-Dfile.encoding=UTF-8',
                                 '-cp', str(output) + os.pathsep + str(classes), 'AdninAnticheatAdapterTest'],
                                capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
        print((result.stdout + result.stderr).strip())
        if result.returncode:
            raise RuntimeError('Offline production Anticheat adapter regression failed')


if __name__ == '__main__':
    main()
