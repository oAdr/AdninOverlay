import java.util.Properties;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;

/** Real sampler/Engine with owned game objects; no target process or network. */
public final class AdninLightGrayAnticheatTest {
    private static int checks;
    private static void check(boolean value,String reason) { checks++;if(!value)throw new AssertionError(reason); }
    private static int tracked() throws Exception {
        return ((AdninAnticheatCore.Engine)AdninAnticheatAdapterTest.runtimeField("engine")).trackedPlayers();
    }
    private static Properties options(String mode,boolean ignore) {
        Properties p=AdninAnticheatAdapterTest.options(20);
        for(String key:new String[]{"autoBlock","noFall","noSlow","scaffold","legitScaffold"})
            p.setProperty("anticheat."+key,Boolean.toString(key.equals(mode)));
        p.setProperty("anticheat.ignoreTeammates",Boolean.toString(ignore));
        p.setProperty("anticheat.flagSound","true");return p;
    }
    private static void drive(Minecraft mc,EntityOtherPlayerMP target,String mode) {
        if("scaffold".equals(mode))AdninAnticheatAdapterTest.scaffoldTicks(mc,target,6);
        else if("legitScaffold".equals(mode)) {
            AdninAnticheatAdapterTest.eagleStep(mc,false,false,target);
            AdninAnticheatAdapterTest.eagleCycles(mc,4,target);
        } else if("noSlow".equals(mode)) {
            for(int i=0;i<11;i++) {target.posX+=.1;AdninAnticheatAdapterTest.ticks(mc,1);}
        } else if("noFall".equals(mode)) {
            target.serverPosY=640;AdninAnticheat.packetReceived();AdninAnticheatAdapterTest.ticks(mc,1);
            target.serverPosY=320;AdninAnticheat.packetReceived();AdninAnticheatAdapterTest.ticks(mc,1);
        } else AdninAnticheatAdapterTest.ticks(mc,10);
    }
    private static void allChecks() throws Exception {
        for(String mode:new String[]{"autoBlock","noFall","noSlow","scaffold","legitScaffold"})
        for(boolean replay:new boolean[]{false,true})for(boolean ignore:new boolean[]{false,true})for(int graySource=0;graySource<3;graySource++) {
            Minecraft mc=AdninAnticheatAdapterTest.scene();AdninAnticheat.loadSettings(options(mode,ignore));
            EntityOtherPlayerMP target;
            java.util.UUID id=replay?AdninAnticheatAdapterTest.SYNTHETIC:AdninAnticheatAdapterTest.LIVE;
            String name=replay?"RecordedActor":"LiveActor";
            if("scaffold".equals(mode))target=AdninAnticheatAdapterTest.scaffoldActor(mc,id,replay?"NPC42":name);
            else if("legitScaffold".equals(mode))target=AdninAnticheatAdapterTest.eagleActor(mc,id,replay?"NPC42":name);
            else target=AdninAnticheatAdapterTest.actor(mc,id,id,replay?"SkinAlias":name,replay?"NPC42":name);
            if("noSlow".equals(mode)) {target.sprinting=true;target.using=true;}
            if("noFall".equals(mode)) {mc.theWorld.solidFloor=true;target.posY=10;target.serverPosY=640;AdninAnticheat.packetReceived();}
            NetworkPlayerInfo info=new NetworkPlayerInfo(new GameProfile(AdninAnticheatAdapterTest.LIVE,name));
            info.team=new ScorePlayerTeam();info.team.prefix="\u00a7c";
            info.display=new ChatComponentText("\u00a7c"+name);
            mc.connection.roster.put(AdninAnticheatAdapterTest.LIVE,info);
            AdninReplay.replay=replay;
            if(replay) {AdninReplay.actors.put(target,name);AdninReplay.infos.put(name.toLowerCase(java.util.Locale.ROOT),info);}
            target.display="\u00a7c"+name;AdninAnticheatAdapterTest.ticks(mc,1);
            check(tracked()==1,"Ordinary actor starts actual evidence: "+mode+" replay="+replay);
            setGray(graySource,target,info,name);int reads=mc.theWorld.terrainReads;drive(mc,target,mode);
            check(AdninAnticheatAdapterTest.counter("anticheatAcceptedActors")==0 && tracked()==0,
                "Gray actor is excluded and old evidence retired: "+mode+" replay="+replay+" ignore="+ignore+" source="+graySource);
            check(mc.thePlayer.messages==0 && mc.thePlayer.reports==0 && mc.thePlayer.sounds==0 && AdninFeatures.outputs==0,
                "Gray evidence cannot reach chat/report/sound/Output: "+mode+" replay="+replay);
            check(mc.theWorld.terrainReads==reads,"Gray actor never enters NoFall terrain scanning: "+mode);
            restore(target,info,name);drive(mc,target,mode);
            int expected=replay && "noFall".equals(mode)?0:1;
            check(AdninAnticheatAdapterTest.counter("anticheatAcceptedActors")==1 && tracked()==1,
                "Restoring a real name color resumes unchanged sampling: "+mode+" replay="+replay);
            check(mc.thePlayer.messages==expected && AdninFeatures.outputs==expected && mc.thePlayer.sounds==expected
                && mc.thePlayer.reports==(replay?0:expected),
                "Fresh evidence restores the existing check and delivery policy: "+mode+" replay="+replay);
            long cooldown=AdninAnticheatAdapterTest.counter("anticheatCooldownEntries");
            setGray(graySource,target,info,name);drive(mc,target,mode);
            check(tracked()==0 && AdninAnticheatAdapterTest.counter("anticheatCooldownEntries")==cooldown,
                "Gray transition removes samples without resetting alert cooldown: "+mode);
            restore(target,info,name);drive(mc,target,mode);
            check(mc.thePlayer.messages==expected && AdninFeatures.outputs==expected,
                "Normal color restoration cannot bypass an existing cooldown: "+mode);
        }
    }
    private static void setGray(int source,EntityOtherPlayerMP player,NetworkPlayerInfo info,String name) {
        if(source==0)player.display="\u00a77"+name;
        else if(source==1)info.display=new ChatComponentText("\u00a77"+name);
        else info.team.prefix="\u00a77";
    }
    private static void restore(EntityOtherPlayerMP player,NetworkPlayerInfo info,String name) {
        player.display="\u00a7c"+name;info.display=new ChatComponentText("\u00a7c"+name);info.team.prefix="\u00a7c";
    }
    private static void thresholdAndTabAuthority() throws Exception {
        Minecraft mc=AdninAnticheatAdapterTest.scene();AdninAnticheat.loadSettings(options("autoBlock",false));
        EntityOtherPlayerMP target=AdninAnticheatAdapterTest.actor(mc,AdninAnticheatAdapterTest.LIVE,
            AdninAnticheatAdapterTest.LIVE,"LiveActor","LiveActor");
        NetworkPlayerInfo info=new NetworkPlayerInfo(target.profile);mc.connection.roster.put(AdninAnticheatAdapterTest.LIVE,info);
        target.display="\u00a7cLiveActor";AdninAnticheatAdapterTest.ticks(mc,9);
        target.display="\u00a77LiveActor";AdninAnticheatAdapterTest.ticks(mc,1);
        target.display="\u00a7cLiveActor";AdninAnticheatAdapterTest.ticks(mc,9);
        check(mc.thePlayer.messages==0,"Nine pre-gray and nine post-gray samples cannot share an Autoblock streak");
        AdninAnticheatAdapterTest.ticks(mc,1);check(mc.thePlayer.messages==1,"Tenth newly valid sample keeps the unchanged threshold");
        for(boolean replay:new boolean[]{false,true}) {
            mc=AdninAnticheatAdapterTest.scene();AdninAnticheat.loadSettings(options("autoBlock",false));
            target=AdninAnticheatAdapterTest.actor(mc,replay?AdninAnticheatAdapterTest.SYNTHETIC:AdninAnticheatAdapterTest.LIVE,
                AdninAnticheatAdapterTest.SKIN,"SkinAlias",replay?"NPC42":"LiveActor");
            info=new NetworkPlayerInfo(new GameProfile(AdninAnticheatAdapterTest.LIVE,"LiveActor"));
            info.team=new ScorePlayerTeam();info.team.prefix="\u00a77";
            mc.connection.roster.put(AdninAnticheatAdapterTest.LIVE,info);
            if(replay) {AdninReplay.replay=true;AdninReplay.actors.put(target,"LiveActor");AdninReplay.infos.put("liveactor",info);}
            target.display="\u00a7cLiveActor";AdninAnticheatAdapterTest.ticks(mc,20);
            check(tracked()==0 && mc.thePlayer.messages==0,"Gray scoreboard nametag wins over stale red actor text: replay="+replay);
            info.team.prefix="\u00a78";AdninAnticheatAdapterTest.ticks(mc,10);
            check(mc.thePlayer.messages==1,"Dark gray actual team remains eligible after gray scoreboard recovery: replay="+replay);
        }
    }
    public static void main(String[] args) throws Exception {
        allChecks();thresholdAndTabAuthority();AdninAnticheat.shutdown();
        System.out.println("AdninLightGrayAnticheatTest: "+checks+" checks passed; all five real checks, live/Replay sampling, cooldown and four output routes");
    }
}
