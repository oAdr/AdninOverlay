"""Exercise actual Features tick/request boundaries with an owned, offline client."""
import argparse
import importlib.util
import os
from pathlib import Path
import tempfile

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('urchin_scope_adapter',ROOT/'tests/test_anticheat_adapter.py')
adapter=importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)

FIXTURES={k:v for k,v in adapter.FIXTURES.items()
          if k not in ('AdninAnticheatAdapterTest.java','AdninFeatures.java')}
FIXTURES.update({
 'net/minecraft/client/gui/GuiScreen.java':'package net.minecraft.client.gui; public class GuiScreen {}',
 'AdninGui4.java':'''public class AdninGui4 extends net.minecraft.client.gui.GuiScreen {
  public static String api_urchin="fixture-key", api_hypixel="", botDenickerUrl="";
  public static boolean botDenicker, chatOutput, chatOutputDenick, chatOutputTags, chatOutputAnticheat;
  public static boolean chatOutputTagsSelf=true, chatOutputTagsTeammates=true;
 }''',
 'AdninGameModules.java':'public final class AdninGameModules { public static void tick(net.minecraft.client.Minecraft mc){} }',
 'AdninReplay.java':'''public final class AdninReplay {
  static boolean replay;
  public static boolean isReplay(){return replay;}
  public static String recordedName(String name){return name;}
  public static boolean isNick(String name){return false;}
 }''',
 'AdninSkinDenicker.java':'public final class AdninSkinDenicker { public static void tick(net.minecraft.client.Minecraft mc){} public static void clearContext(){} public static void shutdown(){} }',
 'net/minecraft/client/Minecraft.java':'''package net.minecraft.client;
 public final class Minecraft {
  public static Minecraft current;
  public net.minecraft.client.multiplayer.WorldClient theWorld;
  public net.minecraft.client.entity.EntityPlayerSP thePlayer;
  public net.minecraft.client.network.NetHandlerPlayClient connection;
  public net.minecraft.client.gui.GuiScreen currentScreen;
  public net.minecraft.client.gui.GuiIngame ingameGUI;
  public static Minecraft getMinecraft(){return current;}
  public boolean isCallingFromMinecraftThread(){return true;}
  public boolean isSingleplayer(){return false;}
  public net.minecraft.client.network.NetHandlerPlayClient getNetHandler(){return connection;}
 }''',
 'AdninUrchinScopeTest.java':r'''
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
public final class AdninUrchinScopeTest {
 static int checks;
 static Field field(String name)throws Exception{Field f=AdninFeatures.class.getDeclaredField(name);f.setAccessible(true);return f;}
 static Method method(String name,Class<?>... types)throws Exception{Method m=AdninFeatures.class.getDeclaredMethod(name,types);m.setAccessible(true);return m;}
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 @SuppressWarnings("unchecked") static BlockingQueue<String[]> requests()throws Exception{return (BlockingQueue<String[]>)field("requests").get(null);}
 @SuppressWarnings("unchecked") static BlockingQueue<String[]> results()throws Exception{return (BlockingQueue<String[]>)field("results").get(null);}
 @SuppressWarnings("unchecked") static Deque<String[]> staged()throws Exception{return (Deque<String[]>)field("matchRequests").get(null);}
 static AdninUrchinCache cache()throws Exception{return (AdninUrchinCache)field("urchinCache").get(null);}
 static AtomicLong starts()throws Exception{return (AtomicLong)field("matchStarts").get(null);}
 static Minecraft scene()throws Exception{
  requests().clear();results().clear();staged().clear();cache().clear();AdninReplay.replay=false;
  Minecraft mc=new Minecraft();Minecraft.current=mc;
  mc.theWorld=new WorldClient();mc.connection=new NetHandlerPlayClient();
  UUID local=UUID.fromString("12345678-1234-4234-8234-123456789abc");
  mc.thePlayer=new EntityPlayerSP(local,new GameProfile(local,"LocalFixture"),"LocalFixture");
  field("initialized").setBoolean(null,true);field("keySnapshot").set(null,"fixture-key");field("world").set(null,mc.theWorld);
  field("generation").setInt(null,17);field("botGeneration").setInt(null,22);
  field("currentMatch").setLong(null,100);starts().set(100);
  field("nativeGameActive").setBoolean(null,false);field("nextScan").setLong(null,0);
  field("waitingUrchin").set(null,null);
  return mc;
 }
 static void player(Minecraft mc,String name){
  UUID id=UUID.fromString("22345678-1234-4234-8234-222222222222");
  mc.connection.roster.clear();mc.connection.roster.put(id,new NetworkPlayerInfo(new GameProfile(id,name)));
 }
 static String[] job(String name,long token){return new String[]{"17","urchin",name,name,"fixture-key",Long.toString(token)};}
 static boolean claim(String[] job)throws Exception{return (Boolean)method("canStartRequest",String[].class).invoke(null,(Object)job);}
 static void delayedConfig()throws Exception{
  Minecraft mc=scene();mc.currentScreen=new AdninGui4();AdninFeatures.matchStarted();AdninFeatures.tick();
  check(field("currentMatch").getLong(null)==100,"Open config defers the start serial without creating a batch");
  AdninFeatures.setGameActive(false);mc.currentScreen=null;player(mc,"LobbyPlayer");AdninFeatures.tick();
  check(!AdninFeatures.outputContextAllowed(),"Fixture is outside game and Replay");
  check(requests().isEmpty()&&staged().isEmpty(),"Closing config after leaving cannot query the lobby roster");
  check(field("currentMatch").getLong(null)==101,"Retired start serial is consumed and cannot be replayed later");
  AdninFeatures.tick();check(requests().isEmpty(),"Repeated lobby ticks create no new Urchin work");
  AdninFeatures.setGameActive(true);AdninFeatures.tick();
  check(requests().isEmpty(),"An active flag alone cannot replay a retired start or create a mid-match batch");
  player(mc,"MatchPlayer");AdninFeatures.matchStarted();AdninFeatures.tick();
  check(requests().size()==1&&"MatchPlayer".equals(requests().peek()[2]),"The next genuine start captures its own current roster exactly once");
  AdninFeatures.tick();check(requests().size()==1,"The same start serial does not enqueue a duplicate batch");
  AdninFeatures.setGameActive(false);
  check(requests().isEmpty()&&staged().isEmpty(),"Leaving immediately retires queued Urchin work without waiting for another client tick");
  mc=scene();mc.currentScreen=new AdninGui4();AdninFeatures.matchStarted();AdninFeatures.tick();
  player(mc,"StillPlaying");mc.currentScreen=null;AdninFeatures.tick();
  check(requests().size()==1&&"StillPlaying".equals(requests().peek()[2]),"Closing config during the same active match still admits the real start batch");
  mc=scene();mc.currentScreen=new AdninGui4();AdninFeatures.matchStarted();AdninFeatures.tick();
  AdninFeatures.setGameActive(false);AdninReplay.replay=true;mc.currentScreen=null;player(mc,"ReplayPlayer");AdninFeatures.tick();
  check(AdninFeatures.outputContextAllowed()&&requests().isEmpty(),"Replay Output eligibility cannot resurrect an earlier live-game Urchin start");
 }
 static void queuedAndInflight()throws Exception{
  scene();AdninFeatures.setGameActive(true);starts().set(200);field("currentMatch").setLong(null,200);
  Map<String,String> players=new LinkedHashMap<String,String>();
  for(String name:new String[]{"Inflight","Queued","Waiting","Staged"})players.put(name,name);
  check(cache().beginMatch(200,players,1000).size()==4,"Owned cache reserves four distinct current-match identities");
  String[] inflight=job("Inflight",200),waiting=job("Waiting",200);
  requests().add(inflight);check(method("pollRequest").invoke(null)==inflight,"The worker dequeues the in-flight candidate through the same claim boundary");
  check(claim(inflight),"Current native match can begin its own reserved request");
  requests().add(waiting);
  check(method("pollRequest").invoke(null)==waiting&&field("waitingUrchin").get(null)==waiting,"Dequeuing registers the waiting reference in the same monitor operation");
  requests().add(job("Queued",200));staged().add(job("Staged",200));
  AdninFeatures.setGameActive(false);
  check(requests().isEmpty()&&staged().isEmpty()&&field("waitingUrchin").get(null)==null,"Leave retires queue, staging and paced reference, not the active HTTP reservation");
  check(!cache().complete(200,"Queued",Collections.singletonList("sniper"),true,1001)
      &&!cache().complete(200,"Staged",Collections.singletonList("sniper"),true,1001),"Retired queued reservations cannot publish stale content");
  check(!claim(waiting),"A dequeued request waiting for rate pacing cannot start in the lobby");
  AdninFeatures.setGameActive(true);check(!claim(waiting),"Returning active without a new start cannot resurrect the retired paced owner");AdninFeatures.setGameActive(false);
  method("retireFailedPublication",String[].class).invoke(null,(Object)waiting);
  check(!cache().complete(200,"Waiting",Collections.singletonList("sniper"),true,1001),"Skipped paced request releases only its own reservation");
  check(cache().beginMatch(201,Collections.singletonMap("Inflight","Inflight"),1001).isEmpty(),"A new match shares an existing in-flight identity reservation instead of making a duplicate request");
  check((Boolean)method("currentJob",String[].class).invoke(null,(Object)inflight),"Already-started same-key response remains eligible across a leave");
  String[] result={"17","urchin","Inflight","","Inflight","200","sniper"};
  method("publishResult",String[].class,String[].class).invoke(null,inflight,result);
  check(results().size()==1&&cache().complete(200,"Inflight",Collections.singletonList("sniper"),true,1001),"An actual in-flight identity can still complete and populate the session cache");
  check(cache().visibleTags().get("inflight").equals(Collections.singletonList("sniper")),"The old in-flight owner's completion populates the currently active match's matching identity");
  check(cache().beginMatch(202,Collections.singletonMap("Inflight","Inflight"),1002).isEmpty(),"The next match reuses that successful in-flight content without querying again");
  check(claim(new String[]{"22","bot","Nick","Nick","fixture-key",""}),"Urchin-only admission change preserves the existing Bot query policy");
  AdninFeatures.setGameActive(true);field("currentMatch").setLong(null,201);starts().set(201);
  check(!claim(inflight),"An unstarted previous-match request cannot start merely because another match became active");
  String[] current=job("Waiting",201);requests().add(current);method("pollRequest").invoke(null);
  starts().set(202);check(!claim(current),"A newer native start retires prior pacing work before the next client tick");
  field("currentMatch").setLong(null,202);current=job("Current",202);requests().add(current);method("pollRequest").invoke(null);
  check(claim(current),"Matching current game-start serial restores request admission");
  current=job("Current",202);requests().add(current);method("pollRequest").invoke(null);field("generation").setInt(null,18);
  check(!claim(current),"A replaced credential epoch still rejects old work");
 }
 static void pacedOwnerReplacement()throws Exception{
  Minecraft mc=scene();AdninFeatures.setGameActive(true);
  Map<String,String> old=new LinkedHashMap<String,String>();old.put("Waiting","Waiting");old.put("Inflight","Inflight");
  cache().beginMatch(100,old,1000);
  String[] waiting=job("Waiting",100);requests().add(waiting);method("pollRequest").invoke(null);
  UUID first=UUID.fromString("22345678-1234-1234-8234-222222222222"),second=UUID.fromString("32345678-1234-1234-8234-333333333333");
  mc.connection.roster.put(first,new NetworkPlayerInfo(new GameProfile(first,"Waiting")));
  mc.connection.roster.put(second,new NetworkPlayerInfo(new GameProfile(second,"Inflight")));
  AdninFeatures.matchStarted();AdninFeatures.tick();
  check(field("waitingUrchin").get(null)==null,"New match retires the paced old owner before freezing the new roster");
  check(requests().size()==1&&"Waiting".equals(requests().peek()[2])&&"101".equals(requests().peek()[5]),"New match queues the waiting identity again while sharing the genuine in-flight identity");
  check(!claim(waiting),"Old worker waking after roster freeze cannot start its retired job");
  method("retireFailedPublication",String[].class).invoke(null,(Object)waiting);
  check(cache().complete(101,"Waiting",Collections.singletonList("sniper"),true,1002),"Old worker retirement cannot cancel the replacement match's pending owner");
  check(cache().complete(100,"Inflight",Collections.singletonList("sniper"),true,1002),"Genuinely in-flight prior owner still completes into the new match");
 }
 static void pacedLifecycle()throws Exception{
  Minecraft mc=scene();AdninFeatures.setGameActive(true);cache().beginMatch(100,Collections.singletonMap("Waiting","Waiting"),1000);
  String[] waiting=job("Waiting",100);requests().add(waiting);method("pollRequest").invoke(null);
  field("generation").setInt(null,18);method("discardStaleJobs").invoke(null);
  check(field("waitingUrchin").get(null)==null&&!cache().complete(100,"Waiting",Collections.singletonList("sniper"),true,1001),"Credential retirement releases both the paced reference and its reservation");
  mc=scene();AdninFeatures.setGameActive(true);cache().beginMatch(100,Collections.singletonMap("Waiting","Waiting"),1000);
  waiting=job("Waiting",100);requests().add(waiting);method("pollRequest").invoke(null);
  mc.theWorld=new WorldClient();AdninFeatures.tick();
  check(field("waitingUrchin").get(null)==null&&!cache().complete(100,"Waiting",Collections.singletonList("sniper"),true,1001),"World replacement releases the paced reference before another game-start batch");
  check(AdninFeatures.outputContextAllowed()&&!claim(waiting),"World-retired work cannot start while the native active flag and serial still lag unchanged");
  scene();AdninFeatures.setGameActive(true);cache().beginMatch(100,Collections.singletonMap("Waiting","Waiting"),1000);
  waiting=job("Waiting",100);requests().add(waiting);method("pollRequest").invoke(null);AdninFeatures.shutdown();
  check(field("waitingUrchin").get(null)==null&&!claim(waiting),"Shutdown clears the string-only paced reference and prevents restart");
 }
 public static void main(String[] args)throws Exception{
  delayedConfig();queuedAndInflight();pacedOwnerReplacement();pacedLifecycle();
  check(field("worker").get(null)==null&&field("settingsPath").get(null)==null,"Tests never initialized a game, API worker, settings path, or network");
  System.out.println("AdninUrchinScopeTest: "+checks+" checks passed; real tick, deferred config, queue retirement, paced-start and in-flight cache boundaries; no IO or sends");
 }
}
'''
})


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk',type=Path,required=True)
    parser.add_argument('--runtime-jdk',type=Path)
    parser.add_argument('--classes',type=Path,required=True)
    args=parser.parse_args()
    classes=args.classes.resolve()
    cp=os.pathsep.join([str(classes),(classes/'runtime-classpath.txt').read_text(encoding='utf8').strip()])
    with tempfile.TemporaryDirectory(prefix='adnin-urchin-scope-') as folder:
        work=Path(folder);sources=[]
        for name,content in FIXTURES.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf8');sources.append(path)
        output=work/'classes'
        adapter.common.compile_sources(adapter.common.find_java(args.jdk,'javac'),sources,cp,output,work/'args.txt')
        print(adapter.common.run([adapter.common.find_java(args.runtime_jdk or args.jdk,'java'),'-Xverify:all',
              '-cp',str(output)+os.pathsep+cp,'AdninUrchinScopeTest'],'Urchin game-start scope regression'))


if __name__=='__main__':main()
