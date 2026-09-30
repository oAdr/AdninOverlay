"""Exercise production message rendering with owned chat counters and no game IO."""
import argparse
import importlib.util
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('messages_build_common', ROOT / 'scripts/build-java.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

FIXTURES = {
    'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
public final class Minecraft {
    public static Minecraft instance; public static int gets;
    public boolean clientThread = true;
    public net.minecraft.client.gui.GuiIngame ingameGUI = new net.minecraft.client.gui.GuiIngame();
    public static Minecraft getMinecraft() { gets++; return instance; }
    public boolean isCallingFromMinecraftThread() { return clientThread; }
}''',
    'net/minecraft/client/gui/GuiIngame.java': '''package net.minecraft.client.gui;
public final class GuiIngame {
    public GuiNewChat chat = new GuiNewChat();
    public GuiNewChat getChatGUI() { return chat; }
}''',
    'net/minecraft/client/gui/GuiNewChat.java': '''package net.minecraft.client.gui;
public final class GuiNewChat {
    public static int deliveries; public static boolean fail, linkage;
    public static net.minecraft.util.IChatComponent last;
    public void printChatMessage(net.minecraft.util.IChatComponent component) {
        if (fail) throw new IllegalStateException("owned failure before delivery");
        if (linkage) throw new NoSuchMethodError("owned missing mapping");
        deliveries++; last=component;
    }
}''',
    'net/minecraft/util/IChatComponent.java': '''package net.minecraft.util;
public interface IChatComponent {
    public static final class Serializer {
        public static boolean fail, empty;
        public static IChatComponent jsonToComponent(String value) {
            if (fail) throw new IllegalArgumentException("owned parse failure");
            return empty ? null : new ChatComponentText(value);
        }
    }
}''',
    'net/minecraft/util/ChatComponentText.java': '''package net.minecraft.util;
public final class ChatComponentText implements IChatComponent {
    public final String text;
    public ChatComponentText(String text) { this.text=text; }
}''',
    'AdninMessagesRenderTest.java': '''import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
public final class AdninMessagesRenderTest {
    private static int checks;
    private static final String NICK="[Adnin] UnitPlayer is nicked";
    private static void check(boolean ok,String reason) { checks++; if(!ok)throw new AssertionError(reason); }
    private static void fallback(String text,boolean json,int category,String reason) {
        int before=GuiNewChat.deliveries;
        check(AdninMessages.renderGenerated(text,json,category)==0,reason+" fallback");
        check(GuiNewChat.deliveries==before,reason+" has no delivery");
    }
    public static void main(String[] args) {
        Minecraft mc=new Minecraft(); Minecraft.instance=mc;
        AdninLanguage.setLanguage("en"); fallback(NICK,false,0,"English");
        check(Minecraft.gets==0,"English does not ask for a game instance");
        for(String locale:new String[]{"zh_CN","zh_TW"}) {
            AdninLanguage.setLanguage(locale);
            int before=GuiNewChat.deliveries;
            check(AdninMessages.renderGenerated(NICK,false,0)==1,"Successful local render acknowledged");
            check(GuiNewChat.deliveries==before+1,"Exactly one local delivery");
            check(((ChatComponentText)GuiNewChat.last).text.equals("[Adnin] UnitPlayer"+AdninLanguage.text(" is ")+AdninLanguage.text("nicked")),"Translated payload reaches local sink");
            String json="{\\"text\\":\\"[Adnin] UnitPlayer is nicked\\",\\"clickEvent\\":{\\"action\\":\\"run_command\\",\\"value\\":\\"/wdr UnitPlayer\\"}}";
            check(AdninMessages.renderGenerated(json,true,0)==1,"JSON delivery acknowledged");
            check(((ChatComponentText)GuiNewChat.last).text.contains("/wdr UnitPlayer"),"Original click payload reaches JSON parser");
            fallback("ordinary server chat",false,0,"Unknown template");
            fallback(NICK,false,4,"Local-only category rejects Nick");
            check(AdninMessages.renderGenerated("[Adnin] Invalid Hypixel API key. Check your key in settings.",false,4)==1,"Local-only API error delivered");
            Minecraft.instance=null; fallback(NICK,false,0,"Missing client"); Minecraft.instance=mc;
            mc.clientThread=false; fallback(NICK,false,0,"Wrong thread"); mc.clientThread=true;
            net.minecraft.client.gui.GuiIngame hud=mc.ingameGUI;
            mc.ingameGUI=null; fallback(NICK,false,0,"Missing HUD"); mc.ingameGUI=hud;
            net.minecraft.client.gui.GuiNewChat chat=hud.chat;
            hud.chat=null; fallback(NICK,false,0,"Missing chat"); hud.chat=chat;
            GuiNewChat.fail=true; fallback(NICK,false,0,"Failed local sink"); GuiNewChat.fail=false;
            GuiNewChat.linkage=true; fallback(NICK,false,0,"Unavailable game mapping"); GuiNewChat.linkage=false;
            IChatComponent.Serializer.fail=true; fallback(json,true,0,"Parser failure"); IChatComponent.Serializer.fail=false;
            IChatComponent.Serializer.empty=true; fallback(json,true,0,"Empty component"); IChatComponent.Serializer.empty=false;
        }
        AdninLanguage.setLanguage("en");
        System.out.println("AdninMessagesRenderTest: "+checks+" checks passed; production bytecode with offline local-chat counters");
    }
}''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    args = parser.parse_args()
    classes = args.classes.resolve()
    for name in ('AdninMessages', 'AdninLanguage'):
        if not (classes / (name + '.class')).is_file():
            raise ValueError('Missing production class: ' + name)
    runtime = str(classes) + os.pathsep + common.default_classpath(ROOT)
    with tempfile.TemporaryDirectory(prefix='adnin-message-render-') as directory:
        work = Path(directory)
        sources = []
        for name, content in FIXTURES.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(path)
        output = work / 'classes'
        common.compile_sources(common.find_java(args.jdk, 'javac'), sources, runtime, output, work / 'args.txt')
        print(common.run([common.find_java(args.jdk, 'java'), '-Xverify:all', '-Dfile.encoding=UTF-8',
                          '-cp', str(output) + os.pathsep + runtime, 'AdninMessagesRenderTest'], 'Message render test'))


if __name__ == '__main__':
    main()
