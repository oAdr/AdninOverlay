import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Map;
import org.lwjgl.opengl.GpuFixture;

/** Faults and stress exercise actual AdninUi bytecode using an owned GL sink. */
public final class AdninUiLifecycleTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static Object field(String name) throws Exception {
        Field field=AdninUi.class.getDeclaredField(name);field.setAccessible(true);return field.get(null);
    }
    private static long directBytes() {
        for(BufferPoolMXBean pool:ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            if("direct".equals(pool.getName()))return pool.getTotalCapacity();
        throw new AssertionError("Missing direct buffer accounting");
    }
    private static void complete() {
        check(GpuFixture.violations.isEmpty(),"No swallowed GL fixture violation: "+GpuFixture.violations);
        check(GpuFixture.clientDepth==0,"Pixel-store stack restored after upload");
        for(GpuFixture.Texture texture:GpuFixture.live.values())
            check(texture.uploaded==texture.height && texture.alpha>0,"Complete nonblank raster uploaded");
    }
    private static void released() throws Exception {
        check(GpuFixture.live.isEmpty() && GpuFixture.resident==0,"Every owned GPU name freed");
        check(((Map<?,?>)field("unicode")).isEmpty() && ((Long)field("unicodeBytes"))==0L,"Unicode accounting cleared");
        for(int name:(int[])field("textures"))check(name==0,"Atlas handle cleared");
        check(!AdninUi.hasFontFailure(),"New lifecycle can retry fonts");
    }
    public static void main(String[] args) throws Exception {
        for(int style=0;style<3;style++)check(AdninUi.text("English atlas",0,0,-1,style),"ASCII atlas uploaded");
        for(String label:new String[]{"玩家 星级 终杀", "玩家 星級 終殺", "玩家\ud83d\ude03", "\u00a7a繁體中文"}) {
            float before=AdninUi.width(label,0);
            check(AdninUi.text(label,0,0,-1,0),"Chinese and supplementary text uploaded");
            check(Float.floatToIntBits(before)==Float.floatToIntBits(AdninUi.width(label,0)),"Cached advance exactly preserves font layout");
        }
        AdninUi.begin(800,600,1,0,1);AdninUi.clip(0,0,200,100);AdninUi.unclip();AdninUi.end();
        complete();
        Object upload=field("uploadPixels"),query=field("queryValues");
        check(((ByteBuffer)upload).capacity()==256*1024,"Fixed 256 KiB upload staging");
        long baseline=directBytes();
        check(baseline<=2L*1024*1024,"Fixture warmed below strict direct-memory limit");
        for(int frame=0;frame<12000;frame++) {
            AdninUi.begin(800,600,1,0,1);
            AdninUi.clip(0,0,200,100);AdninUi.clip(10,10,100,50);AdninUi.unclip();AdninUi.unclip();
            AdninUi.end();
        }
        check(field("uploadPixels")==upload && field("queryValues")==query,"Frame and nested clips reuse buffers");
        check(directBytes()==baseline,"12000 frames do not allocate direct memory");
        for(int label=0;label<4096;label++) {
            check(AdninUi.text("玩家 / 玩家資料 "+label,0,0,-1,label%3),"Distinct Unicode label "+label);
            if(label%128==0) {
                check(((Map<?,?>)field("unicode")).size()<=256,"Cache entry bound under churn");
                check(((Long)field("unicodeBytes"))<=32L*1024*1024,"Cache byte bound under churn");
            }
        }
        complete();
        check(directBytes()==baseline,"4096 cache misses do not allocate direct memory");
        StringBuilder longText=new StringBuilder();
        for(int i=0;i<160;i++)longText.append("玩家資料");
        for(int label=0;label<80;label++)check(AdninUi.text(longText.toString()+label,0,0,-1,2),"Large Unicode budget eviction");
        complete();
        check(((Map<?,?>)field("unicode")).size()<256,"Byte budget evicts before entry budget");
        check(GpuFixture.buffers.size()==1,"Every stripe uses the same direct allocation");
        AdninUi.releaseTextures();released();
        check(field("uploadPixels")==upload && field("queryValues")==query,"Menu/world cleanup retains tiny reusable scratch");
        for(int cycle=0;cycle<20;cycle++) {
            check(AdninUi.text("English",0,0,-1,cycle%3),"Reopen ASCII");
            check(AdninUi.text("设置 / 設定 "+cycle,0,0,-1,cycle%3),"Reopen Unicode");
            AdninUi.releaseTextures();released();
        }
        check(directBytes()==baseline,"Repeated close/reopen never allocates another direct buffer");
        for(String label:new String[]{"atlas", "中文"}) {
            GpuFixture.failStorage=true;
            check(!AdninUi.text(label,0,0,-1,0),"Storage failure falls back");
            GpuFixture.failStorage=false;
            check(GpuFixture.live.isEmpty(),"Storage failure does not leak generated texture");
            AdninUi.releaseTextures();released();
            for(int stripe:new int[]{0,1}) {
                GpuFixture.failStripe=stripe;
                String payload=label.equals("atlas")?label:longText.toString();
                check(!AdninUi.text(payload,0,0,-1,2),"Partial upload fails safely");
                GpuFixture.failStripe=-1;
                check(GpuFixture.live.isEmpty(),"Partial upload deletes texture");
                AdninUi.releaseTextures();released();
            }
        }
        GpuFixture.zeroName=true;
        check(!AdninUi.text("atlas",0,0,-1,0),"Zero texture name rejected");
        GpuFixture.zeroName=false;AdninUi.releaseTextures();released();
        check(AdninUi.text("中文",0,0,-1,0),"Texture before failed cleanup");
        GpuFixture.failDelete=true;
        try {AdninUi.releaseTextures();throw new AssertionError("Expected owned cleanup failure");}
        catch(IllegalStateException expected) { }
        check(GpuFixture.live.size()==1 && ((Map<?,?>)field("unicode")).size()==1,"Failed deletion retains ownership for retry");
        AdninUi.releaseTextures();released();
        AdninUi.dispose();released();
        check(field("uploadPixels")==null && field("queryValues")==null,"Unload releases scratch ownership");
        check(GpuFixture.created==GpuFixture.deleted,"Every generated name deleted across failures and lifecycles");
        check(GpuFixture.violations.isEmpty(),"Fixture has no hidden failures");
        System.out.println("AdninUiLifecycleTest: "+checks+" checks passed; 12000 frames, 4096 labels, 20 close/reopen cycles; direct bytes="+baseline+", peak simulated GPU bytes="+GpuFixture.peak+", generated/deleted="+GpuFixture.created);
    }
}
