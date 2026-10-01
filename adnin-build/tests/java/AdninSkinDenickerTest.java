import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

public final class AdninSkinDenickerTest {
    private static int checks;
    private static long now=10000;
    private static final String ID="12345678-1234-4567-89ab-123456789abc";
    private static final String NICK_ID="12345678-1234-1567-89ab-123456789abc";
    private static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    private static String encode(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    private static String json(String name,String id,String url){return "{\"profileName\":\""+name+"\",\"profileId\":\""+id+"\",\"textures\":{\"SKIN\":{\"url\":\""+url+"\"}}}";}
    private static String texture(String name){return encode(json(name,ID,"http://textures.minecraft.net/texture/"+HASH));}
    private static Object field(String name)throws Exception{Field f=AdninSkinDenicker.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static int size(String name)throws Exception{Object v=field(name);return v instanceof Map?((Map<?,?>)v).size():((Set<?>)v).size();}
    private static NetworkPlayerInfo player(String name,String uuid,String payload){
        GameProfile p=new GameProfile(UUID.fromString(uuid),name);
        if(payload!=null)p.getProperties().put("textures",new Property("textures",payload));
        return new NetworkPlayerInfo(p);
    }
    private static Minecraft context(){
        AdninGui4.api_hypixel="fixture-key";AdninSkinDenicker.clearContext();AdninSkinDenicker.setEnabled(true);AdninFeatures.active=true;AdninFeatures.accept=true;
        AdninFeatures.messages.clear();AdninReplay.replay=false;AdninReplay.names.clear();AdninReplay.nicks.clear();
        AdninFeatures.ignored.clear();
        Minecraft m=new Minecraft();now+=1000;AdninSkinDenicker.tick(m,now);return m;
    }
    private static void tick(Minecraft m){now+=300;AdninSkinDenicker.tick(m,now);}
    private static String request(Minecraft m,String name){AdninSkinDenicker.getProfile(name,now);tick(m);return AdninSkinDenicker.getProfile(name,now);}
    public static void main(String[] args)throws Exception{
        check(AdninSkinDenicker.resolveTexture(texture("RealPlayer")).equals("RealPlayer|"+ID),"Mellow texture owner");
        check(AdninSkinDenicker.resolveTexture(encode(json("RealPlayer",ID.replace("-",""),"https://textures.minecraft.net/texture/"+HASH))).equals("RealPlayer|"+ID),"undashed id/https");
        for(String value:new String[]{null,"","not base64","!!!!",encode("null"),encode("[]"),encode("{}"),
            encode(json("Bad Name",ID,"http://textures.minecraft.net/texture/"+HASH)),
            encode(json("Real",NICK_ID,"http://textures.minecraft.net/texture/"+HASH)),
            encode(json("Real",ID,"http://other.example/texture/"+HASH)),
            encode(json("Real",ID,"http://textures.minecraft.net/texture/"+HASH+"?x=1")),
            encode(json("Real",ID,"http://textures.minecraft.net/texture/gggg")),
            encode(json("Real",ID,"http://textures.minecraft.net/texture/"+HASH)+"{}"),
            encode("{\"profileName\":\"A\",\"profileName\":\"B\"}"),
            encode("{\"extra\":[[[[[[[[[0]]]]]]]]]}"),
            encode(json("Real",ID,"http://textures.minecraft.net/texture/"+HASH).replace("\"Real\"","123")),
            new String(new char[8193]).replace('\0','A')}) check(AdninSkinDenicker.resolveTexture(value).isEmpty(),"reject malformed/untrusted metadata");
        Field hashes=AdninNickSkins.class.getDeclaredField("HASHES");hashes.setAccessible(true);
        Set<?> denied=(Set<?>)hashes.get(null);check(denied.size()==230,"all pinned Mellow shared hashes present");
        for(Object hash:denied)check(AdninSkinDenicker.resolveTexture(encode(json("SharedOwner",ID,"http://textures.minecraft.net/texture/"+hash))).isEmpty(),"shared Nick skin excluded");

        Minecraft m=context();m.connection.players.add(player("NickCase",NICK_ID,texture("RealPlayer")));
        String p=request(m,"NickCase");check(p.equals("RealPlayer|"+ID),"current Tab identity resolves");
        check(AdninFeatures.messages.isEmpty(),"candidate alone cannot announce before native publish");
        AdninSkinDenicker.markPublished("NickCase","Wrong|"+ID);tick(m);check(AdninFeatures.messages.isEmpty(),"stale/rejected publish ignored");
        AdninSkinDenicker.markPublished("NickCase",p);AdninFeatures.accept=false;tick(m);check(AdninFeatures.messages.isEmpty(),"roster not ready announcement waits");
        AdninFeatures.accept=true;tick(m);check(AdninFeatures.messages.size()==1&&AdninFeatures.messages.get(0).equals("NickCase|RealPlayer"),"publish uses original nickname case");
        tick(m);AdninSkinDenicker.markPublished("NickCase",p);tick(m);check(AdninFeatures.messages.size()==1,"one announcement per match");
        long parsed=((Long)field("parses")).longValue();int reads=m.connection.reads;
        for(int i=0;i<2000;i++)check(AdninSkinDenicker.getProfile("NickCase",now).equals(p),"hot callback cache");
        check(m.connection.reads==reads&&((Long)field("parses")).longValue()==parsed,"no game scans/parses on native path");
        NetworkPlayerInfo gray=m.connection.players.get(0);gray.lightGray=true;AdninFeatures.ignored.add("nickcase");
        int known=size("current"),attempted=size("attempted");tick(m);
        check(AdninSkinDenicker.getProfile("NickCase",now).equals(p)&&size("current")==known&&size("attempted")==attempted,
            "Gray respawn preserves the same-name cached identity and prior attempt state");
        check(size("pending")==0&&((Long)field("parses")).longValue()==parsed,"Gray cached reads never schedule or reparse texture evidence");
        java.util.Set<String> aliases=new java.util.HashSet<String>();aliases.add("nickcase");
        AdninSkinDenicker.appendIgnoredAliases(aliases,1024);
        check(aliases.contains("realplayer"),"Gray source alias enriches immutable delivery filtering with its cached Skin owner");
        tick(m);check(AdninFeatures.messages.size()==1,"Gray transitions do not reannounce a previously presented result");
        gray.lightGray=false;AdninFeatures.ignored.clear();tick(m);
        check(AdninSkinDenicker.getProfile("NickCase",now).equals(p)&&AdninFeatures.messages.size()==1,
            "Color recovery retains cached identity and the already-announced marker");
        NetworkPlayerInfo unknownGray=player("GrayUnknown",NICK_ID,texture("UnknownOwner"));unknownGray.lightGray=true;
        m.connection.players.add(unknownGray);AdninFeatures.ignored.add("grayunknown");
        check(AdninSkinDenicker.getProfile("GrayUnknown",now).isEmpty()&&size("pending")==0,
            "A new gray nickname creates no Skin request");
        tick(m);check(!AdninSkinDenicker.hasAttempted("GrayUnknown")&&((Long)field("parses")).longValue()==parsed,
            "Gray never becomes a no-result classification or parses a new texture");
        unknownGray.lightGray=false;AdninFeatures.ignored.clear();
        tick(m);
        check(request(m,"GrayUnknown").startsWith("UnknownOwner|"),"Restored color allows the first actual Skin lookup");
        m.connection.players.remove(unknownGray);tick(m);parsed=((Long)field("parses")).longValue();
        AdninSkinDenicker.clearContext();tick(m);p=request(m,"NickCase");check(((Long)field("parses")).longValue()==parsed,"same texture across matches reuses evidence");
        AdninSkinDenicker.markPublished("NickCase",p);tick(m);check(AdninFeatures.messages.size()==2,"new match may announce cached native publish");
        m.connection.players.clear();m.connection.players.add(player("NickCase",NICK_ID,texture("OtherOwner")));
        tick(m);p=AdninSkinDenicker.getProfile("NickCase",now);check(p.equals("OtherOwner|"+ID),"same nickname changed textures invalidate within roster tick, not five-second hint throttle");
        check(((Long)field("parses")).longValue()==parsed+1,"cache key is texture evidence, not nickname");
        m.connection.players.clear();m.connection.players.add(player("NickCase",ID,texture("OtherOwner")));
        tick(m);check(AdninSkinDenicker.getProfile("NickCase",now).isEmpty(),"online/non-Nick UUID cannot reuse Nick identity");
        m=context();m.connection.players.add(player("Fresh",NICK_ID,texture("FreshOwner")));p=request(m,"Fresh");
        now+=301;check(AdninSkinDenicker.getProfile("Fresh",now).isEmpty(),"stalled client tick expires snapshot without reusing cached identity");
        tick(m);check(AdninSkinDenicker.getProfile("Fresh",now).equals(p),"client refresh restores evidence");
        check(AdninSkinDenicker.getProfile("Fresh",now-1000).isEmpty(),"clock rollback cannot extend identity snapshot");
        AdninSkinDenicker.markPublished("Fresh",p);AdninGui4.api_hypixel="  ";tick(m);
        check(AdninFeatures.messages.isEmpty()&&size("current")==0,"clear key suppresses pending success without another native callback");

        m=context();m.connection.players.add(player("Unlisted",NICK_ID,texture("RealPlayer")));
        check(request(m,"Missing").isEmpty(),"native hint still requires exact current roster");
        m.connection.players.add(player("Unlisted",NICK_ID,texture("RealPlayer")));
        check(request(m,"Unlisted").isEmpty(),"duplicate ambiguous roster name rejected");
        m=context();m.connection.players.add(player("RealPlayer",NICK_ID,texture("RealPlayer")));
        check(request(m,"RealPlayer").isEmpty(),"same name is no denick");
        m=context();m.connection.players.add(player("Empty",NICK_ID,null));
        check(request(m,"Empty").isEmpty()&&AdninSkinDenicker.hasAttempted("Empty"),"missing properties allow Bot fallback after local attempt");
        m.connection.players.clear();m.connection.players.add(player("Empty",NICK_ID,texture("LateOwner")));
        now+=1100;check(request(m,"Empty").startsWith("LateOwner|"),"late texture arrival retries locally");
        m=context();NetworkPlayerInfo conflicting=player("Conflict",NICK_ID,texture("A"));
        conflicting.getGameProfile().getProperties().put("textures",new Property("textures",texture("B")));m.connection.players.add(conflicting);
        check(request(m,"Conflict").isEmpty(),"conflicting texture owners rejected");
        m=context();AdninReplay.replay=true;AdninReplay.nicks.add("alias");AdninReplay.names.put("alias","RecordedNick");
        tick(m);m.connection.players.add(player("Alias",ID,texture("ReplayOwner")));
        p=request(m,"Alias");check(p.startsWith("ReplayOwner|"),"Replay admitted Nick ignores actor UUID class");
        AdninSkinDenicker.markPublished("Alias",p);tick(m);check(AdninFeatures.messages.get(0).equals("RecordedNick|ReplayOwner"),"Replay announcement uses full recorded name");
        AdninReplay.nicks.clear();now+=6000;check(request(m,"Alias").isEmpty(),"Replay cached NICK admission required");
        m=context();for(int i=0;i<20;i++){String n="Nick"+i;m.connection.players.add(player(n,NICK_ID,texture("Owner"+i)));AdninSkinDenicker.getProfile(n,now);}
        tick(m);check(size("current")==8&&size("pending")==12,"eight candidates maximum per batch");
        check(m.connection.reads==1,"one roster pass for entire batch");
        for(int i=0;i<1000;i++)AdninSkinDenicker.getProfile("N"+i,now);
        check(size("pending")<=128&&size("requested")<=512,"bounded native hint pressure");
        m.clientThread=false;int pending=size("pending");tick(m);check(size("pending")==pending,"off-thread tick never touches game roster");m.clientThread=true;
        AdninFeatures.active=false;tick(m);check(size("current")==0&&size("pending")==0,"inactive context drops candidate/results");
        AdninSkinDenicker.setEnabled(false);check(AdninSkinDenicker.getProfile("Nick",now).isEmpty(),"disabled native setting respected");
        AdninSkinDenicker.shutdown();check(size("evidence")==0&&size("current")==0,"shutdown releases all caches/game contexts");
        System.out.println("Mellow Skin Denicker: "+checks+" checks passed (offline fixtures)");
    }
}
