"""Run production Mellow metadata denicker with owned Tab fixtures and no network."""
import argparse
import importlib.util
import os
import sys
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'scripts'))
spec = importlib.util.spec_from_file_location('skin_build_common', ROOT/'scripts/build-java.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)
FIXTURES = {
 'AdninGui4.java': 'public final class AdninGui4 { public static String api_hypixel="fixture-key"; }',
 'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
 public final class Minecraft {
  public boolean clientThread=true;
  public net.minecraft.client.multiplayer.WorldClient theWorld=new net.minecraft.client.multiplayer.WorldClient();
  public net.minecraft.client.network.NetHandlerPlayClient connection=new net.minecraft.client.network.NetHandlerPlayClient();
  public boolean isCallingFromMinecraftThread(){return clientThread;}
  public net.minecraft.client.network.NetHandlerPlayClient getNetHandler(){return connection;}
 }''',
 'net/minecraft/client/multiplayer/WorldClient.java': 'package net.minecraft.client.multiplayer; public final class WorldClient {}',
 'net/minecraft/client/network/NetHandlerPlayClient.java': '''package net.minecraft.client.network;
 public final class NetHandlerPlayClient {
  public int reads;
  public java.util.List<NetworkPlayerInfo> players=new java.util.ArrayList<NetworkPlayerInfo>();
  public java.util.Collection<NetworkPlayerInfo> getPlayerInfoMap(){reads++;return players;}
 }''',
 'net/minecraft/client/network/NetworkPlayerInfo.java': '''package net.minecraft.client.network;
 public final class NetworkPlayerInfo {
  public boolean lightGray;
  private final com.mojang.authlib.GameProfile profile;
  public NetworkPlayerInfo(com.mojang.authlib.GameProfile p){profile=p;}
  public com.mojang.authlib.GameProfile getGameProfile(){return profile;}
 }''',
 'AdninFeatures.java': '''public final class AdninFeatures {
  static boolean active=true, accept=true;
  static final java.util.Set<String> ignored=new java.util.HashSet<String>();
  public static boolean shouldIgnorePlayer(String name){return name!=null&&ignored.contains(name.toLowerCase(java.util.Locale.ROOT));}
  static final java.util.List<String> messages=new java.util.ArrayList<String>();
  public static boolean outputContextAllowed(){return active;}
  public static boolean skinResolved(String nick,String real){
   if(Thread.holdsLock(AdninSkinDenicker.class)) throw new AssertionError("Skin lock held across Features callback");
   if(!accept)return false;messages.add(nick+"|"+real);return true;
  }
 }''',
 'AdninMatchTeams.java': '''public final class AdninMatchTeams {
  public static boolean isLightGray(net.minecraft.client.network.NetworkPlayerInfo info){return info!=null&&info.lightGray;}
 }''',
 'AdninReplay.java': '''public final class AdninReplay {
  static boolean replay;
  static final java.util.Map<String,String> names=new java.util.HashMap<String,String>();
  static final java.util.Set<String> nicks=new java.util.HashSet<String>();
  public static boolean isReplay(){return replay;}
  public static boolean isNick(String name){return nicks.contains(name.toLowerCase(java.util.Locale.ROOT));}
  public static String recordedName(String name){String n=names.get(name.toLowerCase(java.util.Locale.ROOT));return n==null?"":n;}
 }'''
}

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--jdk',type=Path,required=True)
 p.add_argument('--runtime-jdk',type=Path)
 p.add_argument('--classes',type=Path,required=True)
 args=p.parse_args()
 cp=(args.classes/'runtime-classpath.txt').read_text(encoding='utf8').strip()
 with tempfile.TemporaryDirectory(prefix='adnin-skin-mellow-') as folder:
  work=Path(folder); sources=[ROOT/'src/java/AdninSkinDenicker.java',ROOT/'src/java/AdninNickSkins.java',ROOT/'tests/java/AdninSkinDenickerTest.java']
  for name,content in FIXTURES.items():
   file=work/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(content,encoding='utf8');sources.append(file)
  out=work/'classes'
  common.compile_sources(common.find_java(args.jdk,'javac'),sources,cp,out,work/'args.txt')
  print(common.run([common.find_java(args.runtime_jdk or args.jdk,'java'),'-Xverify:all','-Xmx32m','-cp',str(out)+os.pathsep+cp,'AdninSkinDenickerTest'],'Mellow Skin production helper test'))

if __name__=='__main__':main()
