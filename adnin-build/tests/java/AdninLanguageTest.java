import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

public final class AdninLanguageTest {
    private static int checks;
    private static void check(boolean ok,String reason) { checks++; if(!ok) throw new AssertionError(reason); }
    public static void main(String[] args) {
        check("en".equals(AdninLanguage.getLanguage()),"English is the clean-install default");
        String untouched="Player_Name42 https://user.example/?q=<> api-key-opaque";
        Pattern slots=Pattern.compile("%(?:\\d+\\$)?[sd]");
        for(Map.Entry<String,String[]> e:AdninLanguage.translations().entrySet()) {
            AdninLanguage.setLanguage("en");
            check(e.getKey().equals(AdninLanguage.text(e.getKey())),"English exact text");
            int expected=slots.matcher(e.getKey()).replaceAll("X").length()-e.getKey().length();
            int index=0;
            for(String locale:new String[]{"zh_CN","zh_TW"}) {
                AdninLanguage.setLanguage(locale);
                String translated=AdninLanguage.text(e.getKey());
                check(translated.equals(e.getValue()[index++])&&!translated.isEmpty(),"Translation exists");
                check(slots.matcher(translated).replaceAll("X").length()-translated.length()==expected,"Format placeholders preserved");
                check(untouched.equals(AdninLanguage.text(untouched)),"Unknown values untouched");
            }
        }
        AdninLanguage.setLanguage("zh_CN");
        check(AdninLanguage.format("Flag interval: %ss",5).equals("提醒间隔：5 秒"),"Simplified dynamic template");
        Properties p=new Properties(); AdninLanguage.save(p); AdninLanguage.setLanguage("en"); AdninLanguage.load(p);
        check("zh_CN".equals(AdninLanguage.getLanguage()),"Preference roundtrip");
        AdninLanguage.setLanguage("zh_TW");
        check(AdninLanguage.text("Settings").equals("設定"),"Traditional vocabulary");
        check(AdninLanguage.errorBody("Request failed. The next eligible game entry will retry.").equals("請求失敗。 下次進入符合條件的對局時重試。"),"Error suffix translated");
        for(String value:new String[]{null,"", "ja", "../../secret", "zh"}) {
            AdninLanguage.setLanguage(value); check("en".equals(AdninLanguage.getLanguage()),"Invalid preference fallback");
        }
        AdninLanguage.load(new Properties());
        check("en".equals(AdninLanguage.getLanguage()),"No implicit system locale");
        System.out.println("AdninLanguageTest: "+checks+" checks passed; no settings or network accessed");
    }
}
