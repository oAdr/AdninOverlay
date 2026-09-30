import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.lang.reflect.*;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Renders the real Gui4 paths in a hidden OpenGL pbuffer, with no game, config,
 * process attachment, network requests or public messages. */
public class AdninUiRenderTest {
    static int checks;
    static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    static Field field(String name) throws Exception { Field f=AdninGui4.class.getDeclaredField(name); f.setAccessible(true); return f; }
    static void prepare(AdninGui4 screen,int panel,int scroll) throws Exception {
        screen.selectedTheme=panel;
        field("drawnTheme").setInt(screen,panel);
        field("openedAt").setLong(screen,System.nanoTime()-1000000000L);
        field("pageChangedAt").setLong(screen,System.nanoTime()-1000000000L);
        field("sidebarSelection").setDouble(screen,panel*34);
        field("visualScroll").setDouble(screen,scroll);
        Method m=AdninGui4.class.getDeclaredMethod("setPanelScroll",int.class,int.class); m.setAccessible(true); m.invoke(screen,panel,scroll);
    }
    static BufferedImage capture(int w,int h) {
        ByteBuffer pixels=BufferUtils.createByteBuffer(w*h*4);
        GL11.glReadPixels(0,0,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int i=((h-y-1)*w+x)*4;
            image.setRGB(x,y,0xFF000000|((pixels.get(i)&255)<<16)|((pixels.get(i+1)&255)<<8)|(pixels.get(i+2)&255));
        }
        return image;
    }
    public static void main(String[] args) throws Exception {
        Path out=Paths.get(args[0]); Files.createDirectories(out);
        int width=1600,height=1120;
        Pbuffer buffer=new Pbuffer(width,height,new PixelFormat(),null,null);
        try {
            buffer.makeCurrent();
            checks += AdninUiRasterStateTest.run(out,width,height);
            GL11.glViewport(0,0,width,height);
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity(); GL11.glOrtho(0,800,560,0,-1,1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
            AdninGui4 screen=new AdninGui4(); screen.width=800;screen.height=560;screen.layoutForViewport();
            AdninAnticheat.enabled=true; AdninAnticheat.scaffold=true; AdninAnticheat.noFall=true;
            AdninGui4.chatOutput=true; AdninGui4.chatOutputDenick=true; AdninGui4.chatOutputTags=true; AdninGui4.chatOverlay=true;
            for(String language : new String[]{"en","zh_CN","zh_TW"}) {
            AdninLanguage.setLanguage(language);
            for(int panel=0;panel<7;panel++) for(int lower=0;lower<2;lower++) {
                int scroll=lower==0?0:-10000;
                Method set=AdninGui4.class.getDeclaredMethod("setPanelScroll",int.class,int.class);set.setAccessible(true);set.invoke(screen,panel,scroll);
                Method clamp=AdninGui4.class.getDeclaredMethod("clampScroll",int.class,int.class,int.class);clamp.setAccessible(true);
                Method content=AdninGui4.class.getDeclaredMethod("getPanelContentHeight",int.class);content.setAccessible(true);
                scroll=(Integer)clamp.invoke(screen,scroll,(Integer)content.invoke(screen,panel),screen.winH-56-16-32-5);
                prepare(screen,panel,scroll);
                GL11.glClearColor(0.18f,0.21f,0.26f,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                GL11.glColor4f(0.2f,0.3f,0.4f,0.5f); GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,8);
                screen.drawScreen(-100,-100,0);
                check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE1,"active texture restored");
                check(GL11.glIsEnabled(GL11.GL_TEXTURE_2D),"secondary texture state restored");
                check(GL11.glGetInteger(GL11.GL_MATRIX_MODE)==GL11.GL_PROJECTION,"matrix mode restored");
                check(GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)==8,"pixel storage restored");
                check(GL11.glIsEnabled(GL11.GL_ALPHA_TEST),"alpha state restored");
                check(!GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),"scissor state restored");
                check(GL11.glGetError()==GL11.GL_NO_ERROR,"GL state valid for panel "+panel);
                ImageIO.write(capture(width,height),"png",out.resolve(("en".equals(language)?"":language+"-")+"panel-"+panel+(lower==0?"":"-bottom")+".png").toFile());
            }
            AdninGui4.chatOutputTags=false;
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            prepare(screen,4,0); screen.drawScreen(-100,-100,0);
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"disabled tag subcontrols render correctly in "+language);
            ImageIO.write(capture(width,height),"png",out.resolve(language+"-chat-tags-disabled.png").toFile());
            AdninGui4.chatOutputTags=true;
            }
            AdninLanguage.setLanguage("en");
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glMatrixMode(GL11.GL_MODELVIEW);
            AdninUi.begin(800,560,1,0,1);
            try {
                AdninUi.clip(20,20,100,100);
                AdninUi.clip(10,10,50,50);
                java.nio.IntBuffer box=BufferUtils.createIntBuffer(16);GL11.glGetInteger(GL11.GL_SCISSOR_BOX,box);
                check(box.get(0)==40 && box.get(2)==80 && box.get(3)==80,"nested clip intersects parent");
                AdninUi.unclip();GL11.glGetInteger(GL11.GL_SCISSOR_BOX,box);
                check(box.get(0)==40 && box.get(2)==200 && box.get(3)==200,"nested clip restores parent");
                AdninUi.unclip();
                check(AdninUi.width("\u73a9\u5bb6\u540d",0)>0,"Unicode retains measured width");
                check(AdninUi.text("\u73a9\u5bb6\u540d",20,20,0xFFFFFFFF,0),"Unicode raster fallback renders");
                for(int i=0;i<300;i++)AdninUi.text("\u73a9\u5bb6"+i,20,40,0xFFFFFFFF,0);
                Field cache=AdninUi.class.getDeclaredField("unicode");cache.setAccessible(true);
                check(((java.util.Map<?,?>)cache.get(null)).size()==256,"localized Unicode textures are LRU bounded");
                Field bytes=AdninUi.class.getDeclaredField("unicodeBytes");bytes.setAccessible(true);
                check(bytes.getLong(null)<=32L*1024*1024,"Unicode textures obey the 32 MiB budget");
                check(!AdninUi.hasFontFailure(),"all fonts are available");
            } finally {AdninUi.end();}
            screen.width=320;screen.height=180;screen.layoutForViewport();
            GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();GL11.glOrtho(0,320,180,0,-1,1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            prepare(screen,4,0);screen.drawScreen(-100,-100,0);
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"fractional viewport renders without GL error");
            ImageIO.write(capture(width,height),"png",out.resolve("compact-viewport.png").toFile());
            check(!field("api_hypixel").get(null).toString().isEmpty()==false,"dummy preview has no credentials");
            for(String language : new String[]{"en","zh_CN","zh_TW"}) for(int percent:new int[]{70,140}) {
                AdninLanguage.setLanguage(language);AdninGui4.uiScalePercent=percent;
                screen.width=800;screen.height=560;screen.layoutForViewport();
                GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();GL11.glOrtho(0,800,560,0,-1,1);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();
                Method heightMethod=AdninGui4.class.getDeclaredMethod("getPanelContentHeight",int.class);heightMethod.setAccessible(true);
                int floor=Math.min(0,screen.winH-56-16-32-5-(Integer)heightMethod.invoke(screen,0));
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                prepare(screen,0,floor);screen.drawScreen(-100,-100,0);
                check(GL11.glGetError()==GL11.GL_NO_ERROR,"localized scaled settings renders without GL error");
                ImageIO.write(capture(width,height),"png",out.resolve(language+"-scale-"+percent+".png").toFile());
            }
            AdninGui4.uiScalePercent=100;AdninLanguage.setLanguage("en");
            java.util.List<Integer> textureNames=new java.util.ArrayList<Integer>();
            Field atlasNames=AdninUi.class.getDeclaredField("textures");atlasNames.setAccessible(true);
            for(int texture:(int[])atlasNames.get(null))if(texture!=0)textureNames.add(texture);
            Field unicodeNames=AdninUi.class.getDeclaredField("unicode");unicodeNames.setAccessible(true);
            for(Object item:((java.util.Map<?,?>)unicodeNames.get(null)).values()) {
                Field texture=item.getClass().getDeclaredField("texture");texture.setAccessible(true);
                textureNames.add(texture.getInt(item));
            }
            check(!textureNames.isEmpty(),"actual GL lifecycle has populated textures");
            screen.onGuiClosed();
            for(int texture:textureNames)check(!GL11.glIsTexture(texture),"menu close deletes actual GL texture "+texture);
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"real close cleanup has no GL error");
            System.out.println("Real GUI pbuffer render: "+checks+" checks, all seven panels at top/bottom in three languages, 70/140 percent, no game/config/network.");
        } finally {try {AdninUi.dispose();} finally {buffer.destroy();}}
    }
}
