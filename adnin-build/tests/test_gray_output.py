"""Actual Features/Message callbacks with an owned client; no game, network or IO."""
import argparse
import importlib.util
import os
from pathlib import Path
import tempfile

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('gray_output_scope',ROOT/'tests/test_urchin_scope.py')
scope=importlib.util.module_from_spec(spec);spec.loader.exec_module(scope)
FIXTURES={k:v for k,v in scope.FIXTURES.items() if k not in (
    'AdninUrchinScopeTest.java','net/minecraft/util/IChatComponent.java',
    'net/minecraft/util/ChatComponentText.java','net/minecraft/util/ChatStyle.java',
    'net/minecraft/event/ClickEvent.java')}
FIXTURES.update({
 'net/minecraft/client/gui/GuiIngame.java':'''package net.minecraft.client.gui;
 public final class GuiIngame {public final GuiNewChat chat=new GuiNewChat();public GuiNewChat getChatGUI(){return chat;}}''',
 'net/minecraft/client/gui/GuiNewChat.java':'''package net.minecraft.client.gui;
 public final class GuiNewChat {public int messages;public void printChatMessage(net.minecraft.util.IChatComponent c){messages++;}}''',
 'AdninGrayOutputTest.java':r'''
import java.lang.reflect.*;
import java.util.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.*;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.*;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
public final class AdninGrayOutputTest {
 static int checks;
 static final String NAME="herewecandodge",GRAY="\u00a7b[Adnin] \u00a77[34\u272b] \u00a77herewecandodge \u00a77- FKDR: \u00a7c128.8";
 static Field field(String name)throws Exception{Field f=AdninFeatures.class.getDeclaredField(name);f.setAccessible(true);return f;}
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static Minecraft scene()throws Exception{
  Minecraft mc=new Minecraft();Minecraft.current=mc;mc.theWorld=new WorldClient();mc.connection=new NetHandlerPlayClient();
  UUID id=UUID.fromString("12345678-1234-4234-8234-123456789abc");
  mc.thePlayer=new EntityPlayerSP(id,new GameProfile(id,"LocalFixture"),"LocalFixture");mc.ingameGUI=new net.minecraft.client.gui.GuiIngame();
  field("initialized").setBoolean(null,true);field("ignoredAt").setLong(null,Long.MIN_VALUE);
  AdninGui4.chatOutput=AdninGui4.chatOutputDenick=AdninGui4.chatOutputTags=AdninGui4.chatOutputAnticheat=true;
  AdninFeatures.setGameActive(true);AdninFeatures.clearPartyQueue();AdninReplay.replay=false;
  AdninLanguage.setLanguage("en");AdninFeatures.refreshIgnoredPlayers(mc);return mc;
 }
 static NetworkPlayerInfo player(Minecraft mc,String color)throws Exception{
  UUID id=UUID.fromString("22345678-1234-4234-8234-222222222222");
  NetworkPlayerInfo info=new NetworkPlayerInfo(new GameProfile(id,NAME));info.team=new ScorePlayerTeam();info.team.prefix=color;
  info.display=new ChatComponentText(color+NAME);mc.connection.roster.put(id,info);
  field("ignoredAt").setLong(null,Long.MIN_VALUE);AdninFeatures.refreshIgnoredPlayers(mc);return info;
 }
 @SuppressWarnings("unchecked") static void staleSnapshot()throws Exception{
  Minecraft mc=scene();NetworkPlayerInfo info=player(mc,"\u00a7c");
  check(!AdninFeatures.shouldIgnorePlayer(NAME),"Fixture starts with a current red-name snapshot");
  long before=field("ignoredAt").getLong(null);Object snapshot=field("ignoredPlayers").get(null);
  info.team.prefix="\u00a77";info.display=new ChatComponentText("\u00a77"+NAME);
  check(AdninFeatures.nativeRenderGeneratedEvent(GRAY,false,0)==1,
   "Exact reported cached FKDR message must be consumed before native fallback during the 250ms respawn window");
  check(mc.ingameGUI.chat.messages==0,"Gray reported message never enters local chat");
  check(AdninFeatures.pollPartyCommand(System.currentTimeMillis())==null,"Gray reported message never enters /pc");
  AdninFeatures.nativeGeneratedEvent(GRAY,false,0);
  check(AdninFeatures.pollPartyCommand(System.currentTimeMillis())==null,"Direct native callback applies the same color boundary");
  // A stale-colored or unformatted already-queued message must also read the
  // current nametag at actual delivery, before any prefix/color is discarded.
  info.team.prefix="\u00a7c";info.display=new ChatComponentText("\u00a7c"+NAME);field("ignoredAt").setLong(null,Long.MIN_VALUE);AdninFeatures.refreshIgnoredPlayers(mc);
  AdninFeatures.nativeGeneratedEvent("[Adnin] [34\u272b] "+NAME+" - FKDR: 128.8",false,0);
  check(((Deque<?>)field("outbox").get(null)).size()==1,"Ordinary cached stats are admitted before respawn");
  info.team.prefix="\u00a77";
  check(AdninFeatures.pollPartyCommand(System.currentTimeMillis())==null,"A same-tick respawn suppresses queued data immediately before delivery");
  info.team.prefix="\u00a7c";field("ignoredAt").setLong(null,Long.MIN_VALUE);AdninFeatures.refreshIgnoredPlayers(mc);
  AdninFeatures.nativeGeneratedEvent("[Adnin] [34\u272b] "+NAME+" - FKDR: 129",false,0);
  check(AdninFeatures.pollPartyCommand(System.currentTimeMillis())!=null,"Color recovery resumes ordinary cached statistics output");
 }
 static void formattedOnly()throws Exception{
  scene();
  String[] texts={GRAY,"\u00a76Bot Denicker \u00a77"+NAME+"\u00a7r -> Owner",
    "[Seraph] \u00a77"+NAME+"\u00a7r is blacklisted for CHEATER",
    "[Urchin] \u00a77"+NAME+"\u00a7r: sniper",
    "\u00a7b[Adnin] \u00a77"+NAME+"\u00a7r detected for Scaffold"};
  int[] categories={0,3,1,1,2};
  for(int i=0;i<texts.length;i++){
   check(AdninFeatures.nativeRenderGeneratedEvent(texts[i],false,categories[i])==1,"Self-colored gray subject rejected with empty snapshot category "+categories[i]);
   AdninFeatures.nativeGeneratedEvent(texts[i],false,categories[i]);
   check(AdninFeatures.pollPartyCommand(System.currentTimeMillis())==null,"Direct callback cannot bypass text color category "+categories[i]);
  }
  String json="{\"text\":\"[Adnin] [34\u272b] \",\"color\":\"white\",\"extra\":[{\"text\":\""+NAME+"\",\"color\":\"gray\"},{\"text\":\" - FKDR: 128.8\",\"color\":\"red\"}]}";
  check(AdninFeatures.nativeRenderGeneratedEvent(json,true,0)==1,"JSON component style gray is rejected without flattening away its color");
  AdninFeatures.nativeGeneratedEvent(json,true,0);check(AdninFeatures.pollPartyCommand(System.currentTimeMillis())==null,"JSON direct callback retains the color gate");
  for(String text:new String[]{"\u00a77[34\u272b] \u00a7c"+NAME+"\u00a7r - FKDR: 128.8",
      "[Urchin] \u00a7c"+NAME+"\u00a7r: reason mentions \u00a77"+NAME,
      "[Seraph] \u00a7c"+NAME+"\u00a7r: \u00a77OtherPlayer",
      "\u00a78"+NAME+"\u00a7r - FKDR: 1", "\u00a7f"+NAME+"\u00a7r - FKDR: 1",
      "\u00a77[Adnin] Fetching stats for "+NAME+"...", "\u00a77[Adnin] Unable to fetch stats for: "+NAME}){
   scene();
   int category=text.startsWith("[Urchin]")||text.startsWith("[Seraph]")?1:0;
   AdninFeatures.clearPartyQueue();AdninFeatures.nativeGeneratedEvent(text,false,category);
   check(AdninFeatures.pollPartyCommand(System.currentTimeMillis())!=null,"Gray rank/reason/dark-gray/white must not suppress a non-gray subject: "+text);
  }
 }
 static void conflictingColorSources()throws Exception{
  for(int source=0;source<3;source++){
   Minecraft mc=scene();NetworkPlayerInfo info=player(mc,"\u00a7c");UUID id=info.getGameProfile().getId();
   EntityOtherPlayerMP actor=new EntityOtherPlayerMP(id,new GameProfile(id,NAME),NAME);actor.display="\u00a7c"+NAME;
   mc.theWorld.playerEntities.add(actor);field("ignoredAt").setLong(null,Long.MIN_VALUE);AdninFeatures.refreshIgnoredPlayers(mc);
   Object before=field("ignoredPlayers").get(null);long observed=field("ignoredAt").getLong(null);
   if(source==0)actor.display="\u00a77"+NAME;
   else if(source==1)info.display=new ChatComponentText("\u00a77"+NAME);
   else info.team.prefix="\u00a77";
   AdninFeatures.refreshIgnoredPlayers(mc);
   check(field("ignoredPlayers").get(null)==before&&field("ignoredAt").getLong(null)==observed,
    "Gray conflict never converts 250ms bulk snapshots to a per-action roster scan: "+source);
   check(AdninFeatures.nativeRenderGeneratedEvent("[Adnin] [34\u272b] \u00a7c"+NAME+" - FKDR: 128.8",false,0)==1,
    "Actual gray source overrides stale red cached message color at delivery: "+source);
   check(mc.ingameGUI.chat.messages==0&&AdninFeatures.pollPartyCommand(System.currentTimeMillis())==null,
    "Conflicting gray evidence cannot reach local or Party output: "+source);
   check(AdninFeatures.shouldIgnorePlayer(NAME),"Action boundary publishes proven gray for lock-free consumers: "+source);
   actor.display="\u00a7c"+NAME;info.display=new ChatComponentText("\u00a7c"+NAME);info.team.prefix="\u00a7c";
   field("ignoredAt").setLong(null,Long.MIN_VALUE);AdninFeatures.refreshIgnoredPlayers(mc);
   check(!AdninFeatures.shouldIgnorePlayer(NAME),"A later bulk snapshot resumes only once all actual gray evidence recovers: "+source);
  }
 }
 @SuppressWarnings("unchecked") static void inflightCachePreserved()throws Exception{
  Minecraft mc=scene();NetworkPlayerInfo info=player(mc,"\u00a7c");
  AdninGui4.botDenicker=true;field("urlSnapshot").set(null,"fixture-provider");field("botGeneration").setInt(null,22);
  ((Set<String>)field("present").get(null)).add(NAME);
  Method apply=AdninFeatures.class.getDeclaredMethod("applyBotResult",String[].class,long.class,Minecraft.class);apply.setAccessible(true);
  long now=System.currentTimeMillis();String uuid="12345678-1234-4234-8234-123456789abc";
  apply.invoke(null,new String[]{"22","bot",NAME,"",NAME,"","KnownOwner",uuid},now,mc);
  check(AdninFeatures.getBotProfile(NAME).startsWith("KnownOwner|"),"Known same-name identity is available before respawn");
  int localBefore=mc.ingameGUI.chat.messages;AdninFeatures.clearPartyQueue();
  info.team.prefix="\u00a77";
  apply.invoke(null,new String[]{"22","bot",NAME,"",NAME,"","LateOwner",uuid},now+1,mc);
  check(AdninFeatures.shouldIgnorePlayer(NAME),"Same-tick action publishes gray pause to immutable readers immediately");
  check(AdninFeatures.getBotProfile(NAME).startsWith("KnownOwner|"),"In-flight completion preserves the pre-gray known identity until recovery");
  check(mc.ingameGUI.chat.messages==localBefore&&AdninFeatures.pollPartyCommand(now+2)==null,"Late gray completion neither announces locally nor sends Party output");
  info.team.prefix="\u00a7c";field("ignoredAt").setLong(null,Long.MIN_VALUE);AdninFeatures.refreshIgnoredPlayers(mc);
  check(AdninFeatures.getBotProfile(NAME).startsWith("LateOwner|"),"Completion remains cached and becomes available after real color recovery");
  AdninGui4.botDenicker=false;
 }
 public static void main(String[] args)throws Exception{
  staleSnapshot();formattedOnly();conflictingColorSources();inflightCachePreserved();AdninFeatures.clearPartyQueue();
  check(field("settingsPath").get(null)==null&&field("worker").get(null)==null,"No user config or HTTP worker was opened");
  System.out.println("AdninGrayOutputTest: "+checks+" checks passed; actual Features native/local/party callbacks and same-tick respawn, no game/network");
 }
}'''
})

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk',type=Path,required=True)
    parser.add_argument('--runtime-jdk',type=Path)
    parser.add_argument('--classes',type=Path,required=True)
    parser.add_argument('--source',action='store_true')
    args=parser.parse_args();classes=args.classes.resolve()
    cp=os.pathsep.join([str(classes),(classes/'runtime-classpath.txt').read_text(encoding='utf8').strip()])
    with tempfile.TemporaryDirectory(prefix='adnin-gray-output-') as directory:
        work=Path(directory);sources=[]
        for name,content in FIXTURES.items():
            path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf8');sources.append(path)
        if args.source:
            production=work/'production'
            scope.adapter.common.compile_sources(scope.adapter.common.find_java(args.jdk,'javac'),
                [ROOT/'src/java'/(name+'.java') for name in ('AdninFeatures','AdninMatchTeams','AdninMessages')],
                cp,production,work/'production-args.txt')
            cp=str(production)+os.pathsep+cp
        output=work/'classes'
        scope.adapter.common.compile_sources(scope.adapter.common.find_java(args.jdk,'javac'),sources,cp,output,work/'args.txt')
        print(scope.adapter.common.run([scope.adapter.common.find_java(args.runtime_jdk or args.jdk,'java'),'-Dadnin.language.shared=false','-Xverify:all',
            '-cp',str(output)+os.pathsep+cp,'AdninGrayOutputTest'],'Gray native output boundary regression'))

if __name__=='__main__':main()
