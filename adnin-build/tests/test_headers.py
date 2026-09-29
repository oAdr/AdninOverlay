"""Exercise production native-header translation with owned fonts and no game IO."""
import argparse
import importlib.util
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('headers_build_common', ROOT / 'scripts/build-java.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

FIXTURES = {
    'net/minecraft/client/gui/FontRenderer.java': '''package net.minecraft.client.gui;
public final class FontRenderer {
    public int glyphWidth=7, dotWidth=1, calls;
    public boolean fail, linkage, negative; public String negativeText;
    public int getStringWidth(String text) {
        calls++;
        if(fail) throw new IllegalStateException("owned font failure");
        if(linkage) throw new NoSuchMethodError("owned missing font mapping");
        if(negative || text.equals(negativeText)) return -1;
        int result=0;
        for(int at=0;at<text.length();) {
            int cp=text.codePointAt(at); at+=Character.charCount(cp);
            result+=cp=='.'?dotWidth:glyphWidth;
        }
        return result;
    }
}''',
    'AdninHeadersTest.java': '''import net.minecraft.client.gui.FontRenderer;
public final class AdninHeadersTest {
    private static int checks;
    private static final String[][] LABELS={
        {"Name","玩家","玩家"},{"Stars","星级","星級"},{"Wins","胜场","勝場"},
        {"Finals","终杀","終殺"},{"Beds","拆床","拆床"},{"Requeue%","重排率","重排率"},
        {"Kills","击杀","擊殺"},{"Seen","遇见","遇見"},{"Session","本次","本次"},
        {"Ping","延迟","延遲"},{"PingVar","波动","波動"}};
    private static void check(boolean ok,String reason) { checks++; if(!ok)throw new AssertionError(reason); }
    private static void unchanged(String text,Object font,int width,String reason) {
        check(AdninHeaders.translate(text,font,width)==text,reason+" retains exact original reference");
    }
    public static void main(String[] args) {
        FontRenderer font=new FontRenderer();
        AdninLanguage.setLanguage("en");
        for(String[] item:LABELS) unchanged(new String(item[0]),font,90,"English "+item[0]);
        check(font.calls==0,"English does not measure");
        for(int language=1;language<=2;language++) {
            AdninLanguage.setLanguage(language==1?"zh_CN":"zh_TW");
            for(String[] item:LABELS) {
                String measured=AdninHeaders.translate(item[0],font,90);
                check(measured.equals(item[language]),"Full label "+item[0]);
                check(measured.equals(AdninHeaders.translate(item[0],font,90)),"Measure/draw identical "+item[0]);
            }
            int before=font.calls;
            for(String text:new String[]{"HP","FKDR","WLR","KDR","SKD","BBLR","IDX","WS","FK","LV",
                    "Seraph","Urchin","Level","unknown","name","NAME"," Name","Name ",
                    "UnitPlayer","Urchin reason: Name","api-key-Name","https://example.invalid/Name"})
                unchanged(new String(text),font,90,"Exact allowlist "+text);
            check(font.calls==before,"Unknown titles/data never measured");
            unchanged(null,font,90,"Null title");
            for(int width:new int[]{-1,Integer.MIN_VALUE,0,4097,Integer.MAX_VALUE})
                unchanged("Name",font,width,"Invalid native width");
            unchanged("Name",null,90,"Missing actual font");
            unchanged("Name",new Object(),90,"Wrong actual font");
            check(font.calls==before,"Invalid inputs never measured");
            check(AdninHeaders.translate("Name",font,18).equals(LABELS[0][language]),"Exact original width boundary");
            check(AdninHeaders.translate("Name",font,17).equals("玩..."),"Truncate whole glyph and reserve dots");
            check(AdninHeaders.translate("Name",font,7).equals("..."),"Dots-only boundary");
            check(AdninHeaders.translate("Name",font,6).equals(""),"Too narrow for dots");
            check(AdninHeaders.translate("Name",font,1).equals(""),"Padding exceeds native width");
            for(int width=1;width<=32;width++) {
                String label=AdninHeaders.translate("Requeue%",font,width);
                check(font.getStringWidth(label)<=Math.max(0,width-4),"Fits unchanged native column "+width);
                check(label.equals(AdninHeaders.translate("Requeue%",font,width)),"Narrow measure/draw match "+width);
                check(!label.contains("\\ufffd"),"No broken Unicode glyph");
            }
            FontRenderer second=new FontRenderer(); second.glyphWidth=15;
            check(AdninHeaders.translate("Name",second,18).equals("..."),"Uses actual passed native font");
            check(AdninHeaders.translate("Name",font,18).equals(LABELS[0][language]),"Independent fonts have no shared state");
            check(font.glyphWidth==7 && font.dotWidth==1,"No renderer state mutation");
            check(AdninHeaders.translate("Name",font,4096).equals(LABELS[0][language]),"Maximum valid width");
            font.fail=true; unchanged("Name",font,90,"Runtime failure"); font.fail=false;
            font.linkage=true; unchanged("Name",font,90,"Linkage failure"); font.linkage=false;
            font.negative=true; unchanged("Name",font,90,"Negative original width"); font.negative=false;
            font.negativeText="..."; unchanged("Name",font,17,"Negative dots width");
            font.negativeText="玩..."; unchanged("Name",font,17,"Negative candidate width");
            font.negativeText=null;
        }
        AdninLanguage.setLanguage("en");
        System.out.println("AdninHeadersTest: "+checks+" checks passed; production bytecode with offline native-font fixtures");
    }
}''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--classes', type=Path, required=True)
    args = parser.parse_args()
    classes = args.classes.resolve()
    for name in ('AdninHeaders', 'AdninLanguage'):
        if not (classes / (name + '.class')).is_file():
            raise ValueError('Missing production class: ' + name)
    runtime = str(classes) + os.pathsep + common.default_classpath(ROOT)
    with tempfile.TemporaryDirectory(prefix='adnin-header-font-') as directory:
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
                          '-cp', str(output) + os.pathsep + runtime, 'AdninHeadersTest'], 'Header font test'))


if __name__ == '__main__':
    main()
