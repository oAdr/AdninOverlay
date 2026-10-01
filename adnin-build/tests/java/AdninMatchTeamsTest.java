import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldSettings;

public final class AdninMatchTeamsTest {
    private static int checks;
    private static final UUID LOCAL=UUID.fromString("12345678-1234-4234-9234-123456789abc");
    private static void check(boolean ok,String reason) {checks++;if(!ok)throw new AssertionError(reason);}
    private static Minecraft scene() {
        AdninMatchTeams.clear();AdninReplay.replay=false;
        Minecraft mc=new Minecraft();mc.theWorld=new WorldClient();mc.connection=new NetHandlerPlayClient();
        mc.thePlayer=new EntityPlayerSP(LOCAL,new GameProfile(LOCAL,"OriginalSelf"),"OriginalSelf");
        mc.thePlayer.display="\u00a7b[MVP+] \u00a7cNickSelf";
        mc.theWorld.playerEntities.add(mc.thePlayer);return mc;
    }
    private static NetworkPlayerInfo tab(Minecraft mc,UUID id,String name,String display) {
        NetworkPlayerInfo info=new NetworkPlayerInfo(new GameProfile(id,name));
        if(display!=null)info.display=new ChatComponentText(display);
        mc.connection.roster.put(id,info);return info;
    }
    private static EntityOtherPlayerMP actor(Minecraft mc,UUID id,String name,String display) {
        EntityOtherPlayerMP entity=new EntityOtherPlayerMP(id,new GameProfile(id,name),name);
        entity.display=display;mc.theWorld.playerEntities.add(entity);return entity;
    }
    private static UUID id(int value) {return new UUID(0x1234567812344234L,0x9234567800000000L+value);}
    private static void tick(Minecraft mc) {mc.thePlayer.ticksExisted++;AdninMatchTeams.tick(mc);}
    private static void colors() {
        String[][] accepted={{"\u00a7a[VIP] \u00a79VIP","VIP","9"},{"\u00a7cPrefixPlayerA \u00a7aPlayerA","PlayerA","a"},
            {"\u00a7c\u00a7lPlayerA","playera","c"},{"\u00a7fPlayerA","PlayerA","f"},{"\u00a77PlayerA","PlayerA","7"},
            {"\u00a7cPlay\u00a7lerA","PlayerA","c"},{"\u00a7k[Rank] \u00a7cPlayerA","PlayerA","c"}};
        for(String[] row:accepted)check(AdninMatchTeams.nameColor(row[0],row[1])==row[2].charAt(0),"Valid whole-token color "+row[0]);
        for(String text:new String[]{"PlayerA","\u00a7lPlayerA","\u00a7c[VIP] \u00a7rPlayerA","\u00a7cPlayerAB",
                "\u00a7cPlay\u00a79erA","\u00a7c\u00a7kPlayerA","\u00a7c[SPECTATOR] PlayerA","\u00a7c[Viewer] PlayerA",
                "\u00a7a[PlayerA] \u00a7rPlayerA"})
            check(AdninMatchTeams.nameColor(text,"PlayerA")==0,"Invalid/rank/reset/spectator color "+text);
        check("NickSelf".equals(AdninMatchTeams.displayedName("\u00a7a[MVP+] \u00a7cNickSelf 20","OriginalSelf",null,null)),"Unique visible nick token");
        check(AdninMatchTeams.displayedName("\u00a7cFirst Second","OriginalSelf",null,null).isEmpty(),"Ambiguous display fails closed");
        check("123456".equals(AdninMatchTeams.displayedName("\u00a7c123456 20","123456",null,null)),"Known numeric profile wins over numeric health suffix");
        check(AdninMatchTeams.displayedName("\u00a7c20","OriginalSelf",null,null).isEmpty(),"Unknown health value does not become a player alias");
    }

    private static void lightGrayPolicy() {
        Minecraft mc=scene();
        check(!AdninMatchTeams.isLightGray((NetworkPlayerInfo)null)
            && !AdninMatchTeams.isLightGray(null,null,null),"Missing identities never invent a light gray color");
        NetworkPlayerInfo info=tab(mc,id(870),"ColorPlayer",null);
        String[][] cases={{"\u00a77ColorPlayer","true"},{"\u00a77\u00a7lColorPlayer","true"},
            {"\u00a77[VIP] \u00a7cColorPlayer","false"},{"\u00a7c[VIP] \u00a77ColorPlayer","true"},
            {"\u00a78ColorPlayer","false"},{"\u00a7fColorPlayer","false"},{"ColorPlayer","false"},
            {"\u00a77[ColorPlayer] \u00a7rColorPlayer","false"},{"\u00a77Color\u00a7cPlayer","false"},
            {"\u00a77\u00a7kColorPlayer","false"},{"\u00a7rColorPlayer","false"}};
        for(String[] row:cases) {
            info.display=new ChatComponentText(row[0]);
            check(AdninMatchTeams.isLightGray(info)==Boolean.parseBoolean(row[1]),"Tab light gray policy uses actual whole name: "+row[0]);
        }
        info.team=new ScorePlayerTeam();info.team.prefix="\u00a7c";info.display=new ChatComponentText("\u00a77ColorPlayer");
        check(AdninMatchTeams.isLightGray(info),"Gray current Tab name pauses despite a still-red scoreboard during respawn");
        info.team.prefix="\u00a77";info.display=new ChatComponentText("\u00a7cColorPlayer");
        check(AdninMatchTeams.isLightGray(info),"Actual gray scoreboard nametag outranks red custom Tab display");
        info.team.prefix="\u00a77[VIP] \u00a7r";info.display=new ChatComponentText("\u00a77ColorPlayer");
        check(AdninMatchTeams.isLightGray(info),"A scoreboard reset cannot cancel separate explicit gray Tab name evidence");
        info.display=new ChatComponentText("\u00a77[VIP] \u00a7rColorPlayer");
        check(!AdninMatchTeams.isLightGray(info),"Gray ranks and explicitly reset player names never manufacture gray evidence");
        info.display=new ChatComponentText("\u00a77ColorPlayer");
        info.team.prefix="";check(AdninMatchTeams.isLightGray(info),"Unformatted scoreboard permits current Tab fallback");
        info=tab(mc,id(871),"ShortAlias","\u00a77RecordedPlayer");
        check(AdninMatchTeams.isLightGray(info),"Full Replay display name may differ from the raw Tab profile");
        info=tab(mc,id(876),"RecordedPlayer\u00a7r","\u00a77RecordedPlayer");
        check(AdninMatchTeams.isLightGray(info),"Formatted raw Replay aliases still expose the current gray name");
        info=tab(mc,id(872),"XiaoShu_SKY202",null);info.team=new ScorePlayerTeam();info.team.prefix="\u00a77";info.team.suffix="6";
        check(AdninMatchTeams.isLightGray(info),"Scoreboard suffix is part of the full visible Replay name");
        info.team.prefix="\u00a7cR\u00a77";
        check(AdninMatchTeams.isLightGray(info),"Separate red team marker cannot hide the gray full Replay name");
        info=tab(mc,id(875),"RedPlayer","\u00a7cR\u00a77edPlayer");
        check(!AdninMatchTeams.isLightGray(info),"A real account's first letter is never removed as an invented marker");
        info=tab(mc,id(873),"123456","\u00a77123456 20");
        check(AdninMatchTeams.isLightGray(info),"Known numeric Nick is not mistaken for the health suffix");
        EntityOtherPlayerMP actor=actor(mc,id(874),"NPC42","\u00a77RecordedPlayer");
        check(AdninMatchTeams.isLightGray(actor,null,"RecordedPlayer"),"Replay actor color uses admitted full name instead of bot profile");
        actor.display="\u00a77[VIP] \u00a7cRecordedPlayer";
        check(!AdninMatchTeams.isLightGray(actor,null,"RecordedPlayer"),"Gray actor rank cannot exclude a colored recorded player");
        actor.display="NPC42";info=tab(mc,id(874),"RecordedPlayer","\u00a77RecordedPlayer");
        check(AdninMatchTeams.isLightGray(actor,info,"RecordedPlayer"),"Unformatted actor may fall back to its current Tab color");
        actor.display="\u00a7rNPC42";
        check(AdninMatchTeams.isLightGray(actor,info,"RecordedPlayer"),"Actor reset does not hide a separately explicit gray Tab player name");
        actor.display="\u00a77RecordedPlayer";info.team=new ScorePlayerTeam();info.team.prefix="\u00a7c";
        info.display=new ChatComponentText("\u00a7cRecordedPlayer");
        check(AdninMatchTeams.isLightGray(actor,info,"RecordedPlayer"),"Gray actor name pauses despite red scoreboard and red Tab components");
        info.team.prefix="\u00a77";actor.display="\u00a7cRecordedPlayer";
        check(AdninMatchTeams.isLightGray(actor,info,"RecordedPlayer"),"Gray scoreboard nametag excludes an actor with stale red text");
        info.team.prefix="\u00a7c";
        for(String text:new String[]{"RecordedPlayer","\u00a7rRecordedPlayer","\u00a7lRecordedPlayer",
                "\u00a77[VIP] \u00a7cRecordedPlayer","\u00a78RecordedPlayer","\u00a7fRecordedPlayer"}) {
            actor.display=text;info.display=new ChatComponentText(text);
            check(!AdninMatchTeams.isLightGray(actor,info,"RecordedPlayer"),"Unknown/default/rank/dark-gray/white evidence does not create a pause: "+text);
        }
        AdninMatchTeams.clear();
    }
    private static void lightGrayAndRespawn() {
        Minecraft mc=scene();
        mc.thePlayer.display="\u00a77NickSelf";
        NetworkPlayerInfo local=tab(mc,LOCAL,"NickSelf","\u00a77NickSelf");
        local.team=new ScorePlayerTeam();local.team.prefix="\u00a77";
        EntityOtherPlayerMP gray=actor(mc,id(880),"GrayPlayer","\u00a77GrayPlayer");
        tab(mc,id(880),"GrayPlayer","\u00a77GrayPlayer");
        EntityOtherPlayerMP red=actor(mc,id(881),"RedPlayer","\u00a7cRedPlayer");
        tab(mc,id(881),"RedPlayer","\u00a7cRedPlayer");
        AdninMatchTeams.setGameActive(true);tick(mc);
        check(!AdninMatchTeams.isTeammate(gray) && !AdninMatchTeams.isTeammate(red),
            "Local light gray cannot freeze a team color or identify other gray players");
        local.team.prefix="\u00a7c";local.display=new ChatComponentText("\u00a7cNickSelf");tick(mc);
        check(!AdninMatchTeams.isTeammate(red),"Gray actual self Nick delays team establishment despite still-red scoreboard and Tab");
        local.display=new ChatComponentText("\u00a77NickSelf");
        mc.thePlayer.display="\u00a7cNickSelf";tick(mc);
        check(!AdninMatchTeams.isTeammate(red),"Gray self Tab Nick delays team establishment despite red scoreboard and entity text");
        mc.thePlayer.display="\u00a7cNickSelf";
        local.display=new ChatComponentText("\u00a7cNickSelf");local.team.prefix="\u00a7c";tick(mc);
        check(!AdninMatchTeams.isTeammate(gray) && AdninMatchTeams.isTeammate(red),
            "Later valid local color identifies red team without admitting gray players");
        gray.display="\u00a7cGrayPlayer";mc.connection.roster.get(id(880)).display=new ChatComponentText("\u00a7cGrayPlayer");tick(mc);
        check(AdninMatchTeams.isTeammate(gray),"Formerly gray player is eligible after its actual team color arrives");
        // Cache membership by visible name, independent of a respawn's entity
        // instance, short spectator interval or its reset ticksExisted value.
        red.display="\u00a77RedPlayer";mc.thePlayer.ticksExisted=0;tick(mc);
        check(AdninMatchTeams.isTeammate(red),"Local tick rewind and gray teammate retain same-match identity");
        mc.thePlayer.spectator=true;local.gameType=WorldSettings.GameType.SPECTATOR;tick(mc);
        check(AdninMatchTeams.isTeammate("RedPlayer"),"Temporary local spectator respawn does not clear known teammates");
        mc.thePlayer.spectator=false;local.gameType=WorldSettings.GameType.SURVIVAL;
        EntityPlayerSP respawn=new EntityPlayerSP(LOCAL,new GameProfile(LOCAL,"OriginalSelf"),"OriginalSelf");
        respawn.display="\u00a77NickSelf";mc.thePlayer=respawn;tick(mc);
        check(AdninMatchTeams.isTeammate("RedPlayer"),"New local entity with the same identity keeps the match cache");
        mc.connection.roster.remove(id(881));mc.theWorld.playerEntities.remove(red);tick(mc);
        check(AdninMatchTeams.isTeammate("RedPlayer"),"Temporarily absent Tab name remains a cached teammate");
        EntityOtherPlayerMP replacement=actor(mc,id(882),"RedPlayer","\u00a77RedPlayer");
        tab(mc,id(882),"RedPlayer","\u00a77RedPlayer");tick(mc);
        check(AdninMatchTeams.isTeammate(replacement),"Same-match cached player name survives a changed entity UUID");
        AdninMatchTeams.beginMatch();tick(mc);
        check(!AdninMatchTeams.isTeammate("RedPlayer"),"Next match cannot reuse a teammate learned before the reset");
        check(AdninMatchTeams.nameColor("\u00a77GrayPlayer","GrayPlayer")=='7'
            && !AdninMatchTeams.usableTeamColor('7'),"Light gray remains a text color but never a team color");
        check(AdninMatchTeams.usableTeamColor('8') && AdninMatchTeams.usableTeamColor('f'),
            "Dark gray and white remain usable team colors");
        AdninMatchTeams.clear();
    }
    private static void numericNames() {
        Minecraft mc=scene();mc.thePlayer.display="\u00a7c123456 20";
        tab(mc,LOCAL,"123456","\u00a7c123456");
        EntityOtherPlayerMP friend=actor(mc,id(801),"654321","\u00a7c654321 20");
        tab(mc,id(801),"654321","\u00a7c654321");
        tab(mc,id(802),"987654","\u00a79987654");
        AdninMatchTeams.setGameActive(true);tick(mc);
        check(AdninMatchTeams.isSelf("123456") && !AdninMatchTeams.isTeammate("123456"),"Numeric local Nick remains self");
        check(AdninMatchTeams.isTeammate(friend),"Known numeric teammate uses actual nametag color");
        check(!AdninMatchTeams.isTeammate("987654"),"Numeric enemy remains excluded");
        mc=scene();mc.thePlayer.display="\u00a7c123456 20";
        tab(mc,id(803),"123456","\u00a7c123456");
        tab(mc,id(804),"Friend","\u00a7cFriend");
        AdninMatchTeams.setGameActive(true);tick(mc);
        check(AdninMatchTeams.isSelf("123456") && !AdninMatchTeams.isTeammate("123456")
            && AdninMatchTeams.isTeammate("Friend"),"Numeric self Nick links a different server UUID by unique full Tab token");
        AdninMatchTeams.beginMatch();tab(mc,id(805),"20","\u00a7c20");tick(mc);
        check(!AdninMatchTeams.isSelf("123456") && !AdninMatchTeams.isTeammate("Friend"),"Two Tab names in numeric display are ambiguous and fail closed");
        AdninMatchTeams.beginMatch();mc.thePlayer.display="UnknownName \u00a7c20";tick(mc);
        check(!AdninMatchTeams.isSelf("20") && !AdninMatchTeams.isTeammate("Friend"),"A health suffix matching Tab cannot replace an earlier unknown player name");
        AdninMatchTeams.clear();
    }
    private static void nickedSelfServerIdentity() {
        for(String localDisplay:new String[]{"\u00a7rOriginalSelf","\u00a7b[MVP+] \u00a7bOriginalSelf","\u00a7fNickSelf"}) {
            Minecraft mc=scene();mc.thePlayer.display=localDisplay;
            NetworkPlayerInfo self=tab(mc,LOCAL,"NickSelf",null);
            self.team=new ScorePlayerTeam();self.team.prefix="\u00a7c";
            EntityOtherPlayerMP friend=actor(mc,id(850),"RedFriend","\u00a7cRedFriend");
            tab(mc,id(850),"RedFriend",friend.display);
            tab(mc,id(851),"RankColorEnemy","\u00a7bRankColorEnemy");
            AdninMatchTeams.setGameActive(true);tick(mc);
            check(AdninMatchTeams.isSelf("NickSelf"),"Server Nick remains self with stale local account formatting");
            check(AdninMatchTeams.isTeammate(friend),"Current Nick scoreboard color overrides local reset/rank/name formatting: "+localDisplay);
            check(!AdninMatchTeams.isTeammate("RankColorEnemy"),"Original rank color cannot make an enemy a teammate");
        }
        Minecraft mc=scene();mc.thePlayer.display="\u00a7bOriginalSelf";
        NetworkPlayerInfo originalProfile=tab(mc,LOCAL,"OriginalSelf","\u00a7cNickSelf");
        originalProfile.team=new ScorePlayerTeam();originalProfile.team.prefix="\u00a7b";
        tab(mc,id(852),"RedFriend","\u00a7cRedFriend");
        AdninMatchTeams.setGameActive(true);tick(mc);
        check(AdninMatchTeams.isSelf("NickSelf") && AdninMatchTeams.isTeammate("RedFriend"),"UUID-linked server display Nick wins when local profile remains original");
        mc.connection.getPlayerInfo(LOCAL).display=new ChatComponentText("\u00a7cLaterNick");tick(mc);
        check(AdninMatchTeams.isSelf("LaterNick") && AdninMatchTeams.isSelf("NickSelf"),"Later server-only self Nick adds an alias without losing earlier match identity");

        mc=scene();mc.thePlayer.display="\u00a7b[MVP+] \u00a7bOriginalSelf";
        tab(mc,id(853),"BlueEnemy","\u00a7bBlueEnemy");tab(mc,id(854),"RedFriend","\u00a7cRedFriend");
        AdninMatchTeams.setGameActive(true);tick(mc);
        check(!AdninMatchTeams.isTeammate("BlueEnemy") && !AdninMatchTeams.isTeammate("RedFriend"),"Unlinked original rank color is provisional and cannot fix a team");
        NetworkPlayerInfo self=tab(mc,LOCAL,"NickSelf",null);tick(mc);
        check(!AdninMatchTeams.isTeammate("BlueEnemy"),"Linked Nick with no color cannot fall back to obsolete original rank");
        self.team=new ScorePlayerTeam();self.team.prefix="\u00a7c";tick(mc);
        check(AdninMatchTeams.isTeammate("RedFriend") && !AdninMatchTeams.isTeammate("BlueEnemy"),"Late server Nick evidence resolves without retaining a wrong provisional team");
        self.team.prefix="\u00a79";tick(mc);
        check(AdninMatchTeams.isTeammate("RedFriend"),"Established positive teammate remains fixed within the match");
        AdninMatchTeams.beginMatch();tick(mc);
        check(!AdninMatchTeams.isTeammate("RedFriend"),"New match discards prior Nick team color");

        mc=scene();mc.thePlayer.display="\u00a7b[MVP+] \u00a7bOriginalSelf";
        NetworkPlayerInfo partial=tab(mc,LOCAL,"OriginalSelf",null);
        tab(mc,id(856),"BlueEnemy","\u00a7bBlueEnemy");tab(mc,id(857),"RedFriend","\u00a7cRedFriend");
        AdninMatchTeams.setGameActive(true);tick(mc);
        check(!AdninMatchTeams.isTeammate("BlueEnemy"),"Partial original-profile Tab entry cannot freeze the local rank as team color");
        partial.team=new ScorePlayerTeam();partial.team.prefix="\u00a7c";tick(mc);
        check(AdninMatchTeams.isTeammate("RedFriend") && !AdninMatchTeams.isTeammate("BlueEnemy"),"Late scoreboard color resolves even when the server retains the original profile");

        mc=scene();mc.thePlayer.display="\u00a7bOriginalSelf";
        tab(mc,LOCAL,"OriginalSelf",null);tab(mc,id(858),"BlueEnemy","\u00a7bBlueEnemy");tab(mc,id(859),"RedFriend","\u00a7cRedFriend");
        AdninMatchTeams.setGameActive(true);tick(mc);
        NetworkPlayerInfo replacement=tab(mc,LOCAL,"NickSelf",null);replacement.team=new ScorePlayerTeam();replacement.team.prefix="\u00a7c";tick(mc);
        check(AdninMatchTeams.isTeammate("RedFriend") && !AdninMatchTeams.isTeammate("BlueEnemy"),"Partial Tab profile can later become the real server Nick without a frozen rank decision");

        mc=scene();mc.thePlayer.display="\u00a7rNickSelf";
        tab(mc,LOCAL,"NickSelf","\u00a7cNickSelf");tab(mc,id(855),"RedFriend","\u00a7cRedFriend");
        AdninMatchTeams.setGameActive(true);tick(mc);
        check(!AdninMatchTeams.isTeammate("RedFriend"),"Same-identity explicit reset remains fail-closed without scoreboard evidence");
        NetworkPlayerInfo viewed=mc.connection.getPlayerInfo(LOCAL);viewed.team=new ScorePlayerTeam();viewed.team.prefix="\u00a7c";
        viewed.display=new ChatComponentText("\u00a7c[Viewer] NickSelf");tick(mc);
        check(!AdninMatchTeams.isTeammate("RedFriend") && AdninMatchTeams.isSelf("NickSelf"),"Server-only viewer label cannot establish a local team or erase the existing self identity");
        AdninMatchTeams.clear();
    }
    private static void lifecycle() {
        Minecraft mc=scene();tab(mc,LOCAL,"NickSelf","\u00a7cNickSelf");
        EntityOtherPlayerMP friend=actor(mc,id(1),"NickFriend","\u00a79[VIP] \u00a7cNickFriend");
        tab(mc,id(1),"NickFriend","\u00a79NickFriend");
        tab(mc,id(2),"Enemy","\u00a79Enemy");
        tick(mc);check(!AdninMatchTeams.isSelf("NickSelf") && !AdninMatchTeams.isTeammate("NickFriend"),"Lobby observations cannot populate team/self filters");
        AdninMatchTeams.setGameActive(true);tick(mc);
        check(AdninMatchTeams.isSelf("NickSelf") && AdninMatchTeams.isSelf("OriginalSelf") && AdninMatchTeams.isSelf(mc.thePlayer),"Local entity UUID and actual nick aliases identify self");
        check(AdninMatchTeams.isTeammate("nickfriend") && AdninMatchTeams.isTeammate(friend),"Actual entity nametag color overrides contrary Tab color");
        check(!AdninMatchTeams.isTeammate("Enemy") && !AdninMatchTeams.isTeammate("NickSelf"),"Enemy and self are not teammates");
        friend.display="\u00a79NickFriend";tick(mc);
        for(int i=0;i<100;i++) {AdninMatchTeams.setGameActive(true);tick(mc);}
        check(AdninMatchTeams.isTeammate(friend),"Identified player remains teammate without color redecision or repeated native true reset");
        friend.name="RenamedFriend";friend.display="\u00a79RenamedFriend";tab(mc,id(1),"RenamedFriend","\u00a79RenamedFriend");tick(mc);
        check(AdninMatchTeams.isTeammate("RenamedFriend") && AdninMatchTeams.isTeammate("NickFriend"),"Same UUID later nick keeps current and previous match aliases");
        AdninMatchTeams.beginMatch();check(!AdninMatchTeams.isTeammate("NickFriend"),"Native match event invalidates immediately even same world");tick(mc);
        check(!AdninMatchTeams.isTeammate(friend) && !AdninMatchTeams.isTeammate("NickFriend"),"New match cannot retain previous team");
        friend.display="\u00a7cRenamedFriend";tick(mc);check(AdninMatchTeams.isTeammate(friend),"Unknown player can become identified later");
        mc.connection=new NetHandlerPlayClient();tick(mc);check(!AdninMatchTeams.isTeammate(friend),"Connection transition clears membership");
        tab(mc,id(1),"RenamedFriend","\u00a79RenamedFriend");tick(mc);check(AdninMatchTeams.isTeammate(friend),"Still uses actual entity evidence after transition");
        mc.theWorld=new WorldClient();tick(mc);check(!AdninMatchTeams.isTeammate(friend),"World transition drops old entity evidence and membership");
        AdninMatchTeams.setGameActive(false);check(!AdninMatchTeams.isSelf("NickSelf"),"Inactive transition clears output filters immediately");
        AdninMatchTeams.clear();
    }
    private static void fallbackAndSpectators() {
        Minecraft mc=scene();NetworkPlayerInfo self=tab(mc,id(10),"NickSelf","\u00a7cNickSelf");
        NetworkPlayerInfo distant=tab(mc,id(11),"Distant",null);distant.team=new ScorePlayerTeam();distant.team.prefix="\u00a7c";
        tab(mc,id(12),"RankOnly","\u00a7c[VIP] \u00a7rRankOnly");
        NetworkPlayerInfo spectator=tab(mc,id(13),"SpectatorX","\u00a7cSpectatorX");spectator.gameType=WorldSettings.GameType.SPECTATOR;
        EntityOtherPlayerMP entity=actor(mc,id(14),"InvisibleViewer","\u00a7cInvisibleViewer");entity.spectator=true;tab(mc,id(14),entity.name,entity.display);
        AdninMatchTeams.setGameActive(true);tick(mc);
        check(AdninMatchTeams.isSelf("NickSelf"),"Different server nick UUID links unique local visible account");
        check(!AdninMatchTeams.isTeammate("NickSelf"),"Server nick UUID does not classify local user as teammate");
        check(AdninMatchTeams.isTeammate("Distant"),"Distant Tab scoreboard color can establish membership");
        check(!AdninMatchTeams.isTeammate("RankOnly") && !AdninMatchTeams.isTeammate("SpectatorX") && !AdninMatchTeams.isTeammate(entity),"Rank/reset and both spectator states rejected");
        AdninMatchTeams.beginMatch();mc.thePlayer.spectator=true;tick(mc);
        check(!AdninMatchTeams.isTeammate("Distant"),"Local spectator cannot invent a team");
        mc.thePlayer.spectator=false;mc.thePlayer.display="\u00a7c[Viewer] NickSelf";tick(mc);
        check(!AdninMatchTeams.isTeammate("Distant"),"Replay viewer marker cannot invent a team");
        mc.thePlayer.display="\u00a7rNickSelf";tick(mc);check(!AdninMatchTeams.isTeammate("Distant"),"Reset local name has no team color");
        mc.thePlayer.display="\u00a7cNickSelf";tick(mc);check(AdninMatchTeams.isTeammate("Distant"),"Late valid local color recovers");
        AdninMatchTeams.setGameActive(false);AdninReplay.replay=true;tick(mc);
        check(AdninMatchTeams.isTeammate("Distant"),"Replay world observation can establish a real local color without native live state");
        AdninReplay.replay=false;tick(mc);check(!AdninMatchTeams.isTeammate("Distant") && !AdninMatchTeams.isSelf("NickSelf"),"Leaving replay clears all filter identities");
        AdninMatchTeams.setGameActive(true);mc.clientThread=false;tick(mc);
        check(!AdninMatchTeams.isSelf("NickSelf"),"Worker thread cannot observe game objects");
        mc.clientThread=true;tick(mc);check(AdninMatchTeams.isSelf("NickSelf"),"Client observation resumes");
        AdninMatchTeams.clear();check(!AdninMatchTeams.isSelf(mc.thePlayer),"Unload drops UUID aliases");
        mc=scene();mc.thePlayer.display="OriginalSelf";
        NetworkPlayerInfo linked=tab(mc,LOCAL,"NickSelf","\u00a7cNickSelf");linked.team=new ScorePlayerTeam();
        tab(mc,id(22),"Friend","\u00a7cFriend");
        AdninMatchTeams.setGameActive(true);tick(mc);
        check(AdninMatchTeams.isSelf("NickSelf") && AdninMatchTeams.isTeammate("Friend"),"Uncolored original local entity falls back to UUID-linked actual nick Tab color");
        AdninMatchTeams.beginMatch();mc.thePlayer.display="\u00a7rOriginalSelf";tick(mc);
        check(AdninMatchTeams.isTeammate("Friend"),"Reset original identity cannot suppress a linked current server Nick color");
        AdninMatchTeams.clear();
    }
    private static void replayAliasesAndBounds() throws Exception {
        Minecraft mc=scene();AdninReplay.replay=true;
        mc.thePlayer.display="\u00a7cNickSelf";tab(mc,LOCAL,"NickSelf",mc.thePlayer.display);
        tab(mc,id(20),"ShortAlias","\u00a7cRecordedFriend");tick(mc);
        check(AdninMatchTeams.isTeammate("ShortAlias") && AdninMatchTeams.isTeammate("RecordedFriend"),"Replay current raw and full displayed account both resolve");
        mc.thePlayer.profile=new GameProfile(id(99),"OtherLocal");mc.thePlayer.entityId=id(99);
        mc.thePlayer.display="\u00a79NewSelf";tick(mc);
        check(!AdninMatchTeams.isTeammate("RecordedFriend") && !AdninMatchTeams.isSelf("NickSelf"),"Local UUID transition clears old self and team aliases");
        AdninMatchTeams.beginMatch();mc.thePlayer.display="\u00a7cNewSelf";
        for(int batch=0;batch<12;batch++) {
            mc.connection.roster.clear();
            for(int i=0;i<256;i++) {
                String name="Player"+(batch*256+i);
                tab(mc,id(batch*256+i+1000),name,"\u00a7c"+name);
            }
            tick(mc);
        }
        for(String name:new String[]{"ownIds","teamIds","ownNames","teamNames"}) {
            java.lang.reflect.Field field=AdninMatchTeams.class.getDeclaredField(name);field.setAccessible(true);
            check(((java.util.Set<?>)field.get(null)).size()<=(name.endsWith("Ids")?512:1024),"Bounded per-match identities "+name);
        }
        AdninMatchTeams.clear();
        for(String name:new String[]{"ownIds","teamIds","ownNames","teamNames"}) {
            java.lang.reflect.Field field=AdninMatchTeams.class.getDeclaredField(name);field.setAccessible(true);
            check(((java.util.Set<?>)field.get(null)).isEmpty(),"Full lifecycle clear "+name);
        }
    }
    private static void lexicalFastPaths() throws Exception {
        String plain=new String("Fixture123");
        for(String helper:new String[]{"plain","withoutBracketSections"}) {
            java.lang.reflect.Method method=AdninMatchTeams.class.getDeclaredMethod(helper,String.class);method.setAccessible(true);
            check(method.invoke(null,plain)==plain,"Ordinary names reuse their existing string in "+helper);
        }
        check(AdninMatchTeams.displayedName("\u00a79Fixture123","Fixture123",null,null).equals("Fixture123")
            && AdninMatchTeams.nameColor("\u00a79Fixture123","Fixture123")=='9',"Bracket-free colored names retain identity and color");
        check(AdninMatchTeams.displayedName("\u00a7c[Vi\u00a7eEwEr] Fixture123","Fixture123",null,null).isEmpty()
            && AdninMatchTeams.nameColor("\u00a7c[Vi\u00a7eEwEr] Fixture123","Fixture123")==0,
            "Viewer markers with internal formatting still reject both identity and color");
        check(AdninMatchTeams.displayedName("\u00a7a[VIP] \u00a7cFixture123","Fixture123",null,null).equals("Fixture123")
            && AdninMatchTeams.nameColor("\u00a7a[VIP] \u00a7cFixture123","Fixture123")=='c',
            "Rank brackets keep the existing formatted token path");
    }
    public static void main(String[] args) throws Exception {colors();lightGrayPolicy();lightGrayAndRespawn();numericNames();nickedSelfServerIdentity();lifecycle();fallbackAndSpectators();replayAliasesAndBounds();lexicalFastPaths();System.out.println("AdninMatchTeamsTest: "+checks+" checks passed; actual production helper, offline nick/team/match/replay fixtures");}
}
