import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldSettings;

/** Match-scoped positive team identities. No session username, API or Features callback. */
public final class AdninMatchTeams {
    private static final int MAX_PLAYERS=256, MAX_IDENTITIES=512, MAX_ALIASES=1024;
    private static final Set<UUID> ownIds=new HashSet<UUID>(), teamIds=new HashSet<UUID>();
    private static final Set<String> ownNames=new HashSet<String>(), teamNames=new HashSet<String>();
    /* Rebuilt at most once per game tick after self identity is resolved.
       Reusing the map avoids a short lived allocation on every 20 Hz tick. */
    private static final Map<UUID,EntityPlayer> entities=new HashMap<UUID,EntityPlayer>(256);
    private static Object world, connection;
    private static UUID localId;
    private static boolean nativeActive, replayContext;
    private static int observedTick=Integer.MIN_VALUE;
    private static char ownColor;

    private AdninMatchTeams() { }

    /** Native state observation only; entering/leaving a game invalidates its identities. */
    public static synchronized void setGameActive(boolean active) {
        if (nativeActive==active) return;
        nativeActive=active;resetMatch();
    }

    /** Safe from the native game-start producer; never touches Minecraft objects. */
    public static synchronized void beginMatch() { resetMatch(); }

    public static synchronized void clear() {
        nativeActive=false;replayContext=false;world=null;connection=null;localId=null;resetMatch();
    }

    private static void resetMatch() {
        ownIds.clear();teamIds.clear();ownNames.clear();teamNames.clear();ownColor=0;
        entities.clear();
        observedTick=Integer.MIN_VALUE;
    }

    /** Client thread only. Positive matches remain fixed for this game; unknowns can resolve later. */
    public static synchronized void tick(Minecraft mc) {
        if (mc==null || !mc.isCallingFromMinecraftThread()) return;
        boolean replay=AdninReplay.isReplay();
        UUID id=mc.thePlayer==null?null:mc.thePlayer.getUniqueID();
        Object nextConnection=mc.getNetHandler();
        if (world!=mc.theWorld || connection!=nextConnection || !equal(localId,id) || replayContext!=replay) {
            resetMatch();world=mc.theWorld;connection=nextConnection;localId=id;replayContext=replay;
        }
        if ((!nativeActive && !replay) || world==null || id==null || nextConnection==null) {
            resetMatch();return;
        }
        if (observedTick==mc.thePlayer.ticksExisted) return;
        // Respawning replaces the local entity and restarts its ticks inside
        // the same match. Match/world/connection events above own invalidation;
        // a lower entity tick must not erase already recognized teammates.
        observedTick=mc.thePlayer.ticksExisted;
        int count=0;
        Collection<NetworkPlayerInfo> roster=mc.getNetHandler().getPlayerInfoMap();
        NetworkPlayerInfo localInfo=mc.getNetHandler().getPlayerInfo(id);
        String ownDisplay=formatted(mc.thePlayer.getDisplayName());
        String localName=mc.thePlayer.getName();
        String profileName=mc.thePlayer.getGameProfile()==null?"":mc.thePlayer.getGameProfile().getName();
        String tabName=profileName(localInfo);
        String visible=displayedName(ownDisplay,tabName,localName,profileName);
        if(localInfo==null && !valid(visible))visible=numericNickName(ownDisplay,roster);
        // A nick may have a server UUID unlike the local entity UUID. Only one
        // exact visible-name Tab match may link it; never guess from a rank/prefix.
        if(localInfo==null && valid(visible)) {
            NetworkPlayerInfo match=null;boolean duplicate=false;count=0;
            for(NetworkPlayerInfo info:roster) {
                if(++count>MAX_PLAYERS)break;
                if(visible.equalsIgnoreCase(profileName(info))) {
                    if(match!=null)duplicate=true;
                    match=info;
                }
            }
            if(!duplicate)localInfo=match;
            tabName=profileName(localInfo);
        }
        String ownTabText=localInfo==null?null:formatted(localInfo.getDisplayName());
        String ownTabName=displayedName(ownTabText,tabName,localName,profileName);
        boolean viewing=mc.thePlayer.isSpectator() || spectator(ownDisplay)
            || spectator(ownTabText)
            || localInfo!=null && localInfo.getGameType()==WorldSettings.GameType.SPECTATOR;
        // A temporary respawn spectator state cannot create new team evidence,
        // but confirmed same-match identities remain valid for callers.
        if(viewing) { entities.clear(); return; }
        addId(ownIds,id);addName(ownNames,localName);addName(ownNames,profileName);
        addName(ownNames,tabName);addName(ownNames,visible);addName(ownNames,ownTabName);
        if(localInfo!=null && localInfo.getGameProfile()!=null)addId(ownIds,localInfo.getGameProfile().getId());
        if(ownColor==0) {
            ownColor=localColor(localInfo,ownDisplay,visible,localName,profileName,ownTabText,ownTabName);
        }
        if(ownColor==0) return;
        entities.clear();count=0;
        for(EntityPlayer player:mc.theWorld.playerEntities) {
            if(++count>MAX_PLAYERS)break;
            if(player!=null && player.getUniqueID()!=null)entities.put(player.getUniqueID(),player);
        }
        count=0;
        for(NetworkPlayerInfo info:roster) {
            if(++count>MAX_PLAYERS)break;
            if(info==null || info.getGameProfile()==null)continue;
            UUID playerId=info.getGameProfile().getId();
            String name=profileName(info);
            if(playerId==null || !valid(name) || ownIds.contains(playerId) || ownNames.contains(key(name)))continue;
            EntityPlayer player=entities.get(playerId);
            if(info.getGameType()==WorldSettings.GameType.SPECTATOR || player!=null && player.isSpectator())continue;
            String display=player==null?tabDisplay(info):formatted(player.getDisplayName());
            if(spectator(display))continue;
            String shown=displayedName(display,name,player==null?null:player.getName(),null);
            if(teamIds.contains(playerId) || isTeammate(name) || isTeammate(shown)) {
                // A recognized player's later nick/display change adds an alias
                // without revisiting their team decision during this match.
                addId(teamIds,playerId);addName(teamNames,name);addName(teamNames,shown);
                if(player!=null)addName(teamNames,player.getName());
                continue;
            }
            if(isLightGray(player,info,shown))continue;
            char observed=nameColor(display,shown);
            /* Replay/entity wrappers sometimes expose only an unformatted
               name while the Tab entry still carries the authoritative team
               color. Fall back to that Tab evidence when the entity text has
               no usable color; an explicit reset remains fail-closed. */
            if(observed==0 && player!=null && !explicitColor(display)) {
                String tabText=tabDisplay(info);
                String tabShown=displayedName(tabText,name,player.getName(),null);
                char tabColor=nameColor(tabText,tabShown);
                if(tabColor!=0) { display=tabText; shown=tabShown; observed=tabColor; }
            }
            /* Light gray (§7) is the transient spectator/respawn nametag
               color used by the server. It is not a real Bed Wars team
               color, so it must never establish a teammate identity. A
               positive teammate decision is still match-cached above and is
               intentionally retained while that player briefly turns gray. */
            if(!usableTeamColor(observed) || observed!=ownColor)continue;
            addId(teamIds,playerId);addName(teamNames,name);addName(teamNames,shown);
            if(player!=null)addName(teamNames,player.getName());
        }
    }

    public static synchronized boolean isSelf(String name) { return valid(name) && ownNames.contains(key(name)); }
    public static synchronized boolean isTeammate(String name) { return valid(name) && teamNames.contains(key(name)); }
    public static synchronized boolean isSelf(EntityPlayer player) {
        return player!=null && (ownIds.contains(player.getUniqueID()) || isSelf(player.getName()));
    }
    public static synchronized boolean isTeammate(EntityPlayer player) {
        return player!=null && (teamIds.contains(player.getUniqueID()) || isTeammate(player.getName()));
    }

    /**
     * Current Tab nametag policy shared by player-data and identity features.
     * An explicitly formatted scoreboard name wins over a custom Tab label.
     * Only a proven light-gray name token is excluded; rank text, unknown
     * formatting, dark gray and white are never treated as light-gray names.
     * This helper has no monitor, cache, world scan or IO side effects.
     */
    public static boolean isLightGray(NetworkPlayerInfo info) {
        String name=plain(profileName(info));
        if(!valid(name))return false;
        return currentNameColor(tabDisplay(info),name,null,null)=='7';
    }

    /** Current actor policy, including Replay names unlike the bot profile. */
    public static boolean isLightGray(EntityPlayer player,NetworkPlayerInfo info,String admittedName) {
        String tabName=plain(profileName(info));
        if(info!=null && info.getPlayerTeam()!=null && valid(tabName)) {
            String text=ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(),profileName(info));
            // Explicit resets also carry authority: a stale custom display
            // label must not replace unknown scoreboard nametag formatting.
            if(explicitColor(text))return currentNameColor(text,tabName,admittedName,null)=='7';
        }
        if(player!=null) {
            String text=formatted(player.getDisplayName());
            String profile=player.getGameProfile()==null?null:player.getGameProfile().getName();
            char color=currentNameColor(text,admittedName,player.getName(),profile);
            if(color!=0 || explicitColor(text))return color=='7';
        }
        return isLightGray(info);
    }

    private static char currentNameColor(String text,String first,String second,String third) {
        if(text==null || text.length()>4096 || text.indexOf('\u00a7')<0)return 0;
        // The ordinary exact-account path is allocation-free. Replay suffixes
        // and server Nick display aliases use the established token parser.
        char color=nameColor(text,first);
        if(color!=0)return color;
        color=nameColor(text,displayedName(text,first,second,third));
        if(color!=0)return color;
        // Replay may concatenate a separately formatted team marker directly
        // before a full/suffixed account. Remove only a witnessed marker whose
        // remainder starts with one of our known identities; never guess that
        // the first letter of an unknown or mixed-color player name is a team.
        String unmarked=withoutKnownTeamMarker(text,first,second,third);
        return unmarked==null?0:nameColor(unmarked,displayedName(unmarked,first,second,third));
    }

    private static String withoutKnownTeamMarker(String text,String first,String second,String third) {
        if(text==null || text.length()>4096)return null;
        String clean=withoutBracketSections(plain(text)).trim();
        int end=0;while(end<clean.length() && nameCharacter(clean.charAt(end)))end++;
        if(end<2 || end>17)return null;
        String token=clean.substring(0,end);
        if("RBGYAPWS".indexOf(Character.toUpperCase(token.charAt(0)))<0
            || knownPrefix(token,first) || knownPrefix(token,second) || knownPrefix(token,third))return null;
        String remainder=token.substring(1);
        if(!knownPrefix(remainder,first) && !knownPrefix(remainder,second) && !knownPrefix(remainder,third))return null;
        boolean bracket=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='\u00a7' && i+1<text.length()) {i++;continue;}
            if(c=='[') {bracket=true;continue;}
            if(c==']') {bracket=false;continue;}
            if(bracket || !nameCharacter(c))continue;
            if(Character.toLowerCase(c)==Character.toLowerCase(token.charAt(0))
                && i+1<text.length() && text.charAt(i+1)=='\u00a7')return text.substring(0,i)+text.substring(i+1);
            return null;
        }
        return null;
    }
    private static boolean knownPrefix(String token,String known) {
        return valid(known) && token.length()>=known.length() && token.regionMatches(true,0,known,0,known.length());
    }

    private static String profileName(NetworkPlayerInfo info) {
        return info==null || info.getGameProfile()==null?"":info.getGameProfile().getName();
    }
    /** The server's linked current Nick/team outranks stale local account text. */
    private static char localColor(NetworkPlayerInfo info,String display,String visible,String localName,String profileName,String tabText,String shown) {
        String tabName=profileName(info);
        // Some clients preserve the account profile in Tab too and change only
        // the server display component. Its exact Nick token is then the live
        // identity; the profile's old scoreboard entry may still be a rank.
        if(info!=null && valid(shown) && !shown.equalsIgnoreCase(tabName)) {
            char color=nameColor(tabText,shown);
            if(color!=0)return usableTeamColor(color)?color:0;
        }
        if(info!=null && info.getPlayerTeam()!=null && valid(tabName)) {
            char color=nameColor(ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(),tabName),tabName);
            if(color!=0)return usableTeamColor(color)?color:0;
        }
        boolean serverAlias=valid(shown) && !shown.equalsIgnoreCase(visible)
            || valid(tabName) && !tabName.equalsIgnoreCase(visible);
        if(info!=null && serverAlias) {
            char color=nameColor(tabText,shown);
            if(color!=0)return usableTeamColor(color)?color:0;
        }
        /* Under server Nick the local entity can retain its account name and
           rank/reset formatting. Do not freeze that as the match's team while
           the matching Tab/scoreboard update is still arriving. */
        boolean originalIdentity=visible.equalsIgnoreCase(localName) || visible.equalsIgnoreCase(profileName);
        if(originalIdentity)return 0;
        char color=nameColor(display,visible);
        if(color==0 && !explicitColor(display) && info!=null) {
            String fallback=tabDisplay(info);
            String name=displayedName(fallback,tabName,localName,profileName);
            color=nameColor(fallback,name);addName(ownNames,name);
        }
        return usableTeamColor(color)?color:0;
    }
    private static String tabDisplay(NetworkPlayerInfo info) {
        if(info==null)return null;
        if(info.getPlayerTeam()!=null) {
            String teamText=ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(),profileName(info));
            if(explicitColor(teamText))return teamText;
        }
        return formatted(info.getDisplayName());
    }
    private static boolean explicitColor(String text) {
        if(text==null)return false;
        for(int i=0;i+1<text.length();i++)if(text.charAt(i)=='\u00a7') {
            char code=Character.toLowerCase(text.charAt(++i));
            if(code>='0'&&code<='9'||code>='a'&&code<='f'||code=='r'||code=='k')return true;
        }
        return false;
    }
    private static String formatted(IChatComponent component) { return component==null?null:component.getFormattedText(); }
    private static boolean equal(Object a,Object b) { return a==b || a!=null && a.equals(b); }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    /** Allocation-free Minecraft name validation used by the per-tick roster scan. */
    private static boolean valid(String name) {
        if(name==null || name.length()==0 || name.length()>16)return false;
        for(int i=0;i<name.length();i++) {
            char c=name.charAt(i);
            if(!(c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'))return false;
        }
        return true;
    }
    private static void addName(Set<String> names,String name) { if(valid(name) && names.size()<MAX_ALIASES)names.add(key(name)); }
    private static void addId(Set<UUID> ids,UUID id) { if(id!=null && ids.size()<MAX_IDENTITIES)ids.add(id); }
    /** Strip Minecraft formatting without compiling a regex on every roster tick. */
    private static String plain(String text) {
        if(text==null || text.length()==0)return "";
        if(text.indexOf('\u00a7')<0)return text;
        StringBuilder out=new StringBuilder(text.length());
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='\u00a7' && i+1<text.length() && isFormatCode(text.charAt(i+1))) { i++; continue; }
            out.append(c);
        }
        return out.toString();
    }
    private static boolean isFormatCode(char code) {
        code=Character.toLowerCase(code);
        return code>='0'&&code<='9' || code>='a'&&code<='f' || code>='k'&&code<='o' || code=='r';
    }
    /** Remove bracketed rank/spectator prefixes while preserving plain names. */
    private static String withoutBracketSections(String text) {
        if(text==null || text.length()==0)return "";
        if(text.indexOf('[')<0)return text;
        StringBuilder out=new StringBuilder(text.length());
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='[') {
                int end=text.indexOf(']',i+1);
                if(end>=0) { out.append(' '); i=end; continue; }
            }
            out.append(c);
        }
        return out.toString();
    }
    private static boolean spectator(String text) {
        if(text==null || text.indexOf('[')<0)return false;
        String clean=plain(text).toLowerCase(Locale.ROOT);
        return clean.contains("[viewer]") || clean.contains("[spectator]");
    }
    private static boolean nameCharacter(char c) { return c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'; }

    /** Exact colored account tokens win; a unique visible token supports a local nick. */
    static String displayedName(String formatted,String first,String second,String third) {
        if(formatted==null || formatted.length()>4096 || spectator(formatted))return "";
        String text=withoutBracketSections(plain(formatted)).trim();
        String candidate="";
        for(String token:text.split("\\s+")) {
            if(!valid(token))continue;
            if(token.equalsIgnoreCase(first) || token.equalsIgnoreCase(second) || token.equalsIgnoreCase(third))return token;
            // A confirmed numeric profile is a name. Unmatched numbers may be
            // health/level suffixes, so exclude them only after exact matching.
            if(digitsOnly(token))continue;
            if(token.length()==1 && "RBGYAPWS".contains(token.toUpperCase(Locale.ROOT)))continue;
            if(!candidate.isEmpty())return "";
            candidate=token;
        }
        return candidate;
    }

    private static boolean digitsOnly(String text) {
        if(text==null || text.length()==0)return false;
        for(int i=0;i<text.length();i++)if(text.charAt(i)<'0'||text.charAt(i)>'9')return false;
        return true;
    }

    /** A numeric self nick needs a unique current Tab match, never just a health suffix. */
    private static String numericNickName(String formatted,Collection<NetworkPlayerInfo> roster) {
        if(formatted==null || formatted.length()>4096 || spectator(formatted))return "";
        String[] tokens=withoutBracketSections(plain(formatted)).trim().split("\\s+");
        String candidate="";
        for(String token:tokens) {
            if(!valid(token))continue;
            if(token.length()==1 && "RBGYAPWS".contains(token.toUpperCase(Locale.ROOT)))continue;
            // An earlier real-looking name makes later digits a suffix.
            if(!digitsOnly(token))return "";
            candidate=token;break;
        }
        if(candidate.isEmpty() || nameColor(formatted,candidate)==0)return "";
        int count=0,matches=0;boolean candidateFound=false;
        for(NetworkPlayerInfo info:roster) {
            if(++count>MAX_PLAYERS)break;
            String name=profileName(info);
            if(!valid(name))continue;
            for(String token:tokens)if(name.equalsIgnoreCase(token)) {
                if(++matches>1)return "";
                candidateFound=candidate.equalsIgnoreCase(name);break;
            }
        }
        return matches==1 && candidateFound?candidate:"";
    }

    /** The account's whole token must have one explicit color; formatting is not color. */
    static char nameColor(String formatted,String name) {
        if(formatted==null || formatted.length()>4096 || !valid(name) || spectator(formatted))return 0;
        /* Scan formatted tokens directly. The old implementation allocated two
           builders and their backing strings for every roster/Anticheat name. */
        char active=0,tokenColor=0,result=0;
        boolean obfuscated=false,bracket=false,tokenBracket=false,matches=true,uniform=true;
        int length=0;
        for(int i=0;i<=formatted.length();i++) {
            char c=i<formatted.length()?formatted.charAt(i):'\0';
            if(c=='\u00a7' && i+1<formatted.length()) {
                char code=Character.toLowerCase(formatted.charAt(++i));
                if(code>='0'&&code<='9'||code>='a'&&code<='f') {active=code;obfuscated=false;}
                else if(code=='r') {active=0;obfuscated=false;}
                else if(code=='k')obfuscated=true;
                continue;
            }
            if(nameCharacter(c)) {
                char color=obfuscated?0:active;
                if(length==0) {tokenColor=color;tokenBracket=bracket;matches=true;uniform=true;}
                if(color!=tokenColor)uniform=false;
                if(length>=name.length() || Character.toLowerCase(c)!=Character.toLowerCase(name.charAt(length)))matches=false;
                length++;
            } else {
                if(length==name.length() && matches && !tokenBracket) {
                    if(!uniform || tokenColor==0 || result!=0 && result!=tokenColor)return 0;
                    result=tokenColor;
                }
                length=0;
                if(c=='[')bracket=true;
                else if(c==']')bracket=false;
            }
        }
        return result;
    }

    /**
     * Returns whether a nametag color can identify a Bed Wars team. §7 light
     * gray is deliberately excluded: the server uses it transiently for
     * respawning/spectating players and it does not represent a team.
     */
    static boolean usableTeamColor(char color) { return color!=0 && color!='7'; }
}
