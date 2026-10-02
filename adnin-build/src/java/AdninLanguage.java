import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Presentation-only translations. Never translate account names, keys, URLs or API payloads. */
public final class AdninLanguage {
    private static volatile String language = "en";
    private static final Map<String,String[]> TEXT;
    static {
        Map<String,String[]> m = new LinkedHashMap<String,String[]>();
        add(m,"Settings","设置","設定");
        add(m,"Anticheat","作弊检测","作弊偵測");
        add(m,"Utils","实用工具","實用工具");
        add(m,"Overlay","数据面板","資料面板");
        add(m,"Chat Overlay","聊天数据","聊天資料");
        add(m,"Session","本次游戏","本次遊戲");
        add(m,"Experimental","实验功能","實驗功能");
        add(m,"Connections, appearance and language","连接、外观与语言","連線、外觀與語言");
        add(m,"Player checks and alert preferences","玩家检测与提醒偏好","玩家偵測與提醒偏好");
        add(m,"Everyday tools, made personal","按需设置常用工具","按需設定常用工具");
        add(m,"Choose and arrange player statistics","选择并排列玩家数据","選擇並排列玩家資料");
        add(m,"Local messages and party output","本地消息与组队聊天输出","本機訊息與隊伍聊天輸出");
        add(m,"Your session at a glance","查看本次游戏统计","查看本次遊戲統計");
        add(m,"Additional utilities","更多实用功能","更多實用功能");
        add(m,"API Keys","API 密钥","API 金鑰");
        add(m,"Hypixel API Key","Hypixel API 密钥","Hypixel API 金鑰");
        add(m,"Seraph API Key","Seraph API 密钥","Seraph API 金鑰");
        add(m,"Vega API Key","Vega API 密钥","Vega API 金鑰");
        add(m,"Urchin API Key","Urchin API 密钥","Urchin API 金鑰");
        add(m,"API URL","API 地址","API 網址");
        add(m,"Not configured","未设置","未設定");
        add(m,"Vega Proxy","Vega 代理","Vega 代理");
        add(m,"Use proxy statistics without a Hypixel key","通过代理查询数据，无需 Hypixel 密钥","透過代理查詢資料，無需 Hypixel 金鑰");
        add(m,"Selected source: Vega Proxy","当前来源：Vega 代理","目前來源：Vega 代理");
        add(m,"Selected source: Hypixel API","当前来源：Hypixel API","目前來源：Hypixel API");
        add(m,"New statistics disabled: Hypixel key is missing","未填写 Hypixel 密钥，无法查询新数据","未填寫 Hypixel 金鑰，無法查詢新資料");
        add(m,"Previously loaded statistics may remain cached.","已加载的数据可能仍保留在缓存中。","已載入的資料可能仍保留在快取中。");
        add(m,"Interface","界面","介面");
        add(m,"Active: %s","当前：%s","目前：%s");
        add(m,"Click a row to toggle. Use arrows to reorder.","点击行切换显示，使用箭头调整顺序。","點選資料列切換顯示，使用箭頭調整順序。");
        add(m,"Interface font unavailable. Press Esc to close.","界面字体不可用，按 Esc 关闭。","介面字型無法使用，按 Esc 關閉。");
        add(m,"%s (fit to screen)","%s（适应窗口）","%s（符合視窗）");
        add(m,"UI Scale","界面缩放","介面縮放");
        add(m,"Language","语言","語言");
        add(m,"Reset","重置","重設");
        add(m,"%s%% (fit)","%s%%（适应窗口）","%s%%（符合視窗）");
        add(m,"Enabled","启用","啟用");
        add(m,"Disabled","关闭","停用");
        add(m,"On","开启","開啟");
        add(m,"Off","关闭","關閉");
        add(m,"NONE","未绑定","未綁定");
        add(m,"Checks","检测项目","偵測項目");
        add(m,"Flag Sound","检测提示音","偵測提示音");
        add(m,"Autoblock","自动格挡","自動格擋");
        add(m,"AutoBlock","自动格挡","自動格擋");
        add(m,"NoFall","无摔落伤害","無摔落傷害");
        add(m,"NoSlow","无减速","無減速");
        add(m,"Scaffold","自动搭桥","自動搭橋");
        add(m,"Legit Scaffold","拟真搭桥","擬真搭橋");
        add(m,"Legit scaffold","拟真搭桥","擬真搭橋");
        add(m,"Alerts","提醒","提醒");
        add(m,"Ignore teammates","忽略队友","忽略隊友");
        add(m,"Only Atlas suspect","仅检测 Atlas 嫌疑人","僅偵測 Atlas 嫌疑人");
        add(m,"Auto report","自动举报","自動檢舉");
        add(m,"Flag interval: %ss","提醒间隔：%s 秒","提醒間隔：%s 秒");
        add(m,"Ignored players (comma separated)","忽略的玩家（以逗号分隔）","忽略的玩家（以逗號分隔）");
        add(m," detected for "," 疑似使用 "," 疑似使用 ");
        add(m,"Unknown","未知","未知");
        add(m,"Resource Timer","资源计时器","資源計時器");
        add(m,"Diamonds","钻石","鑽石");
        add(m,"Emeralds","绿宝石","綠寶石");
        add(m,"Display","显示","顯示");
        add(m,"Tab","Tab 列表","Tab 清單");
        add(m,"Scoreboard","计分板","計分板");
        add(m,"AutoWho","自动查询名单","自動查詢名單");
        add(m,"Party Detector","组队检测","隊伍偵測");
        add(m,"Number Denicker","数字识别昵称","數字識別暱稱");
        add(m,"Skin Denicker","皮肤识别昵称","外觀識別暱稱");
        add(m,"Bot Denicker","API 识别昵称","API 識別暱稱");
        add(m,"Auto GL","自动发送祝福","自動發送祝福");
        add(m,"Fast Buy","快速购买","快速購買");
        add(m,"Colored Hitboxes","彩色碰撞箱","彩色碰撞箱");
        add(m,"Thickness","线条粗细","線條粗細");
        add(m,"Dragon Hitbox","龙碰撞箱","龍碰撞箱");
        add(m,"Shot Distance","射击距离","射擊距離");
        add(m,"Bed DC Timer","床断线计时","床斷線計時");
        add(m,"Trade Indicator","换血提示","換血提示");
        add(m,"GL Message","祝福消息","祝福訊息");
        add(m,"Nickname Lookup","昵称查询","暱稱查詢");
        add(m,"Sounds","声音","聲音");
        add(m,"Client Side Sounds","客户端音效","用戶端音效");
        add(m,"Bed Wars Shop Layout","起床战争商店布局","起床戰爭商店配置");
        add(m,"Shop Layout Copier","商店布局复制","商店配置複製");
        add(m,"Target player","目标玩家","目標玩家");
        add(m,"Load Layout","读取布局","讀取配置");
        add(m,"Enter a valid player name.","请输入有效的玩家名。","請輸入有效的玩家名稱。");
        add(m,"Resolving player profile...","正在解析玩家档案……","正在解析玩家檔案……");
        add(m,"Loading shop layout...","正在读取商店布局……","正在讀取商店配置……");
        add(m,"Shop layout received.","已读取商店布局。","已讀取商店配置。");
        add(m,"Applying shop layout...","正在应用商店布局……","正在套用商店配置……");
        add(m,"Shop layout applied.","商店布局已应用。","商店配置已套用。");
        add(m,"Shop layout timed out.","商店布局读取超时。","商店配置讀取逾時。");
        add(m,"Shop profile unavailable.","无法读取玩家商店布局。","無法讀取玩家商店配置。");
        add(m,"Shop layout window closed.","商店布局窗口已关闭。","商店配置視窗已關閉。");
        add(m,"Shop layout could not be applied.","无法应用商店布局。","無法套用商店配置。");
        add(m,"Shop layout is empty.","玩家商店布局为空。","玩家商店配置為空。");
        add(m,"Party Detector IDs","实体编号组队检测","實體編號隊伍偵測");
        add(m,"Queue party size via spawn entity IDs","根据出生实体编号估计组队人数","根據出生實體編號估計隊伍人數");
        add(m,"Compact Bl","精简黑名单","精簡黑名單");
        add(m,"In Chat","聊天栏显示","聊天欄顯示");
        add(m,"Teammate","包含队友","包含隊友");
        add(m,"Output: Player Data","组队输出：玩家数据","隊伍輸出：玩家資料");
        add(m,"Output: Nick / Denick","组队输出：昵称 / 真实身份","隊伍輸出：暱稱 / 真實身分");
        add(m,"Output: Seraph / Urchin Tags","组队输出：Seraph / Urchin 标签","隊伍輸出：Seraph / Urchin 標籤");
        add(m,"Include Self","包括自己","包含自己");
        add(m,"Include Teammates","包括队友","包含隊友");
        add(m,"Output: Anticheat","组队输出：作弊检测","隊伍輸出：作弊偵測");
        add(m,"Thresholds","显示门槛","顯示門檻");
        add(m,"Bedwars Min Stars","起床战争最低星级","床戰最低星級");
        add(m,"Bedwars Min FKDR","起床战争最低 FKDR","床戰最低 FKDR");
        add(m,"SkyWars Min KDR","空岛战争最低 KDR","空島戰爭最低 KDR");
        add(m,"Game Stats","对局统计","對局統計");
        add(m,"Final Kills","最终击杀","最終擊殺");
        add(m,"Finals","最终击杀","最終擊殺");
        add(m,"Game","对局","對局");
        add(m,"Session Stats","本次统计","本次統計");
        add(m,"BW-Duels","床战决斗","床戰決鬥");
        add(m," is "," 是 "," 是 ");
        add(m,"nicked","昵称玩家","暱稱玩家");
        add(m," is blacklisted for "," 被标记，原因："," 被標記，原因：");
        add(m,"Invalid Hypixel API key","Hypixel API 密钥无效","Hypixel API 金鑰無效");
        add(m,"Invalid Seraph API key","Seraph API 密钥无效","Seraph API 金鑰無效");
        add(m,". Check your key in settings.","。请检查设置中的密钥。","。請檢查設定中的金鑰。");
        add(m,"Fetching stats for ","正在查询玩家数据：","正在查詢玩家資料：");
        add(m,"Unable to fetch stats for: ","无法查询玩家数据：","無法查詢玩家資料：");
        add(m,"Beds","拆床","拆床");
        add(m,"Kills","击杀","擊殺");
        add(m,"Wins","胜场","勝場");
        add(m,"Winstreak","连胜","連勝");
        add(m,"Slumber Tickets","沉睡票券","沉睡票券");
        add(m,"Session Games","本次场数","本次場數");
        add(m,"Game Time","对局时长","對局時長");
        add(m,"Avg Time","平均时长","平均時長");
        add(m,"Session Time","本次时长","本次時長");
        add(m,"Text Shadow","文字阴影","文字陰影");
        add(m,"Reset Session","重置本次统计","重設本次統計");
        add(m,"BG Opacity","背景透明度","背景透明度");
        add(m,"Opacity","透明度","透明度");
        add(m,"Scale","缩放","縮放");
        add(m,"Name","名称","名稱");
        add(m,"Stars","星级","星級");
        add(m,"Stars New","新版星级","新版星級");
        add(m,"Index","综合指数","綜合指數");
        add(m,"FinalKills","最终击杀","最終擊殺");
        add(m,"BedsBroken","拆床数","拆床數");
        add(m,"Requeue%","重排率","重排率");
        add(m,"Seens","遇见次数","遇見次數");
        add(m,"Ping","延迟","延遲");
        add(m,"PingVar","延迟波动","延遲波動");
        add(m,"Stars (SW)","空岛星级","空島星級");
        add(m,"KDR (SW)","空岛 KDR","空島 KDR");
        add(m,"WLR (SW)","空岛 WLR","空島 WLR");
        add(m,"Wins (SW)","空岛胜场","空島勝場");
        add(m,"Kills (SW)","空岛击杀","空島擊殺");
        add(m,"Wins (Duel)","决斗胜场","決鬥勝場");
        add(m,"WLR (Duel)","决斗 WLR","決鬥 WLR");
        add(m,"KDR (Duel)","决斗 KDR","決鬥 KDR");
        add(m,"WLR (BW Duels)","床战决斗 WLR","床戰決鬥 WLR");
        add(m,"Index (BW Duels)","床战决斗指数","床戰決鬥指數");
        add(m,"Bedwars","起床战争","床戰");
        add(m,"SkyWars","空岛战争","空島戰爭");
        add(m,"Duel","决斗","決鬥");
        add(m,"BW Duels","床战决斗","床戰決鬥");
        add(m,"Columns","数据列","資料欄");
        add(m,"Bedwars Columns","起床战争数据列","床戰資料欄");
        add(m,"Wool","羊毛","羊毛");
        add(m,"Stone Sword","石剑","石劍");
        add(m,"Iron Sword","铁剑","鐵劍");
        add(m,"Golden Apple","金苹果","金蘋果");
        add(m,"Fireball","火球","火球");
        add(m,"Ender Pearl","末影珍珠","終界珍珠");
        add(m,"Pickaxe","镐","鎬");
        add(m,"Axe","斧","斧");
        add(m,"Shears","剪刀","剪刀");
        add(m,"Chainmail Armor","锁链护甲","鎖鏈盔甲");
        add(m,"Iron Armor","铁护甲","鐵盔甲");
        add(m,"Sharpness","锋利","鋒利");
        add(m,"Protection","保护","保護");
        add(m,"Mining Fatigue","挖掘疲劳","挖掘疲勞");
        add(m,"Haste","急迫","挖掘加速");
        add(m,"Feather Falling","摔落保护","輕盈");
        add(m,"Diamond Sword","钻石剑","鑽石劍");
        add(m,"Knockback Stick","击退棒","擊退棒");
        add(m,"Arrows","箭矢","箭矢");
        add(m,"Diamond Armor","钻石护甲","鑽石盔甲");
        add(m,"No results","无结果","無結果");
        add(m,"Request failed. Will retry.","请求失败，将重试。","請求失敗，將重試。");
        add(m,"(account verification unavailable; will retry)","（账号验证暂不可用，将重试）","（帳號驗證暫不可用，將重試）");
        add(m,"Enter an API URL containing <> first.","请先填写包含 <> 的 API 地址。","請先填寫包含 <> 的 API 網址。");
        add(m,"The next eligible game entry will retry.","下次进入符合条件的对局时重试。","下次進入符合條件的對局時重試。");
        add(m,"No API key is configured. Add your Urchin API key in Settings.","未设置 API 密钥，请在设置中填写 Urchin API 密钥。","未設定 API 金鑰，請在設定中填寫 Urchin API 金鑰。");
        add(m,"The API key format is invalid. Check your Urchin API key in Settings.","密钥格式无效，请检查设置中的 Urchin API 密钥。","金鑰格式無效，請檢查設定中的 Urchin API 金鑰。");
        add(m,"Authentication failed (401). Check your Urchin API key in Settings.","身份验证失败（401），请检查 Urchin API 密钥。","身分驗證失敗（401），請檢查 Urchin API 金鑰。");
        add(m,"Access denied (403). Check your API key in Settings and your Urchin account permissions.","访问被拒绝（403），请检查 API 密钥和 Urchin 账号权限。","存取遭拒（403），請檢查 API 金鑰和 Urchin 帳號權限。");
        add(m,"Player lookup failed: invalid player identifier.","玩家查询失败：玩家标识无效。","玩家查詢失敗：玩家識別碼無效。");
        add(m,"Request limit reached (429). Wait for the quota to reset; a later eligible game entry will retry.","已达到请求上限（429）。请等待额度恢复，后续对局会重试。","已達請求上限（429）。請等待額度恢復，後續對局會重試。");
        add(m,"The API's upstream service failed (502).","API 上游服务出错（502）。","API 上游服務出錯（502）。");
        add(m,"The Urchin service returned a server error.","Urchin 服务返回服务器错误。","Urchin 服務傳回伺服器錯誤。");
        add(m,"The API returned a redirect that was not followed.","API 返回重定向，未跟随跳转。","API 傳回重新導向，未跟隨跳轉。");
        add(m,"The request timed out. Check your connection.","请求超时，请检查网络连接。","請求逾時，請檢查網路連線。");
        add(m,"Could not resolve the Urchin API hostname. Check your DNS and connection.","无法解析 Urchin API 域名，请检查 DNS 和网络连接。","無法解析 Urchin API 網域，請檢查 DNS 和網路連線。");
        add(m,"Secure TLS communication with the Urchin API failed.","与 Urchin API 建立 TLS 安全连接失败。","與 Urchin API 建立 TLS 安全連線失敗。");
        add(m,"Could not connect to the Urchin API. Check your connection.","无法连接 Urchin API，请检查网络连接。","無法連線至 Urchin API，請檢查網路連線。");
        add(m,"The API response was invalid or unsupported.","API 响应无效或格式不受支持。","API 回應無效或格式不受支援。");
        add(m,"The Urchin request configuration is invalid.","Urchin 请求配置无效。","Urchin 請求設定無效。");
        add(m,"Request failed.","请求失败。","請求失敗。");
        TEXT = Collections.unmodifiableMap(m);
    }
    private AdninLanguage() {}
    private static void add(Map<String,String[]> m,String en,String cn,String tw) { m.put(en,new String[]{cn,tw}); }
    public static String getLanguage() { return language; }
    public static void setLanguage(String value) { language = "zh_CN".equals(value) || "zh_TW".equals(value) ? value : "en"; }
    public static String text(String english) {
        if (english == null) return "";
        String current = language;
        if ("en".equals(current)) return english;
        String[] values = TEXT.get(english);
        return values == null ? english : values["zh_TW".equals(current) ? 1 : 0];
    }
    public static String format(String english,Object... values) { return String.format(Locale.ROOT,text(english),values); }
    /** Only fixed error templates are passed here, never service text. */
    public static String errorBody(String english) {
        String suffix = " The next eligible game entry will retry.";
        if (english.endsWith(suffix)) return text(english.substring(0,english.length()-suffix.length()))
            + " " + text(suffix.substring(1));
        return text(english);
    }
    public static void load(Properties properties) { setLanguage(properties.getProperty("ui.language","en")); }
    public static void save(Properties properties) { properties.setProperty("ui.language",language); }
    static Map<String,String[]> translations() { return TEXT; }
    private static Path sharedPath() {
        if ("false".equals(System.getProperty("adnin.language.shared"))) return null;
        String local = System.getenv("LOCALAPPDATA");
        return local == null || local.length() == 0 ? null : Paths.get(local,"Adnin","language.txt");
    }
    /** Pre-shared-profile language migration only; new saves use AdninSharedConfig. */
    public static void loadSharedPreference() {
        try {
            Path path = sharedPath();
            if (path != null && Files.isRegularFile(path) && Files.size(path) <= 16) {
                String value = new String(Files.readAllBytes(path),StandardCharsets.US_ASCII).trim();
                if ("en".equals(value) || "zh_CN".equals(value) || "zh_TW".equals(value)) setLanguage(value);
            }
        } catch (Exception ignored) { }
    }
}
