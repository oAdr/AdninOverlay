import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.font.FontRenderContext;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/** Small, game-independent UI renderer. Fonts are rasterized once from the OS;
 * no external font, resource pack, Forge hook or network resource is required. */
public final class AdninUi {
    public static final int TEXT = 0xFFF2F3F7, MUTED = 0xFF969CA9;
    public static final int CARD = 0xFF25282F, BORDER = 0xFF343840;
    public static final int ACCENT = 0xFF529AFF, GREEN = 0xFF32D583;
    private static final int ATLAS = 1024, CELL_W = 80, CELL_H = 96, COLUMNS = 12;
    private static final String GLYPHS = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\u2022\u2026";
    private static final float[] SIZES = {10.0f, 12.0f, 19.0f};
    private static final int[] textures = new int[3];
    private static final float[][] advances = new float[3][GLYPHS.length()];
    private static boolean fontFailed;
    private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null,true,true);
    private static final String[] FONT_FAMILIES = {"Segoe UI", "Microsoft YaHei UI", "Microsoft JhengHei UI", "Noto Sans CJK SC", "SansSerif", "Dialog"};
    private static final Font[][] fonts = new Font[SIZES.length][FONT_FAMILIES.length];
    private static final java.util.regex.Pattern FORMAT_CODES = java.util.regex.Pattern.compile("(?i)\\u00a7[0-9a-fk-or]");
    private static final java.util.LinkedHashMap<String, UnicodeText> unicode =
        new java.util.LinkedHashMap<String, UnicodeText>(32,0.75f,true);
    private static long unicodeBytes;
    // One bounded native upload buffer, reused for every atlas/Unicode texture.
    // Do not allocate a direct buffer per frame or cache miss: reclamation of
    // those buffers depends on GC even after the OpenGL upload has completed.
    private static final int UPLOAD_BYTES = 256 * 1024;
    private static ByteBuffer uploadPixels;
    private static IntBuffer queryValues;

    private static final int UNICODE_CACHE_ENTRIES = 256;
    private static final long UNICODE_CACHE_BYTES = 32L * 1024 * 1024;
    private static float opacity = 1, scale = 1, offsetY;
    private static int logicalWidth, logicalHeight, viewportX, viewportY, viewportW, viewportH;
    private static int matrixMode, shaderProgram;
    private static final float[] EDGE_COS = new float[52], EDGE_SIN = new float[52];
    static {
        for (int step = 0; step < 52; step++) {
            double angle = -Math.PI + (step / 13) * Math.PI / 2 + (step % 13) * Math.PI / 24;
            EDGE_COS[step] = (float)Math.cos(angle);
            EDGE_SIN[step] = (float)Math.sin(angle);
        }
    }

    private AdninUi() { }
    public static boolean hasFontFailure() { return fontFailed; }

    private static final class UnicodeText {
        int texture, width, height;
        float advance;
        UnicodeText(int texture,int width,int height,float advance) {
            this.texture=texture;this.width=width;this.height=height;this.advance=advance;
        }
    }

    private static boolean nonAscii(String text) {
        for (int i=0;i<text.length();i++) {
            if (text.charAt(i)=='\u00a7' && i+1<text.length()) { i++;continue; }
            if (GLYPHS.indexOf(text.charAt(i))<0) return true;
        }
        return false;
    }

    private static Font systemFont(int style,String text) {
        for (int i=0;i<FONT_FAMILIES.length;i++) {
            Font candidate=fonts[style][i];
            if (candidate==null) fonts[style][i]=candidate=new Font(FONT_FAMILIES[i],style==0?Font.PLAIN:Font.BOLD,30).deriveFont(SIZES[style]*3);
            if (i==FONT_FAMILIES.length-1 || ((i==0 || !"Dialog".equals(candidate.getFamily())) && candidate.canDisplayUpTo(text)<0))
                return candidate;
        }
        throw new AssertionError("Missing font fallback");
    }

    /** Bounded LRU for translated labels and pasted text. A full localized panel
     * fits without rerasterizing every label each frame; large pasted strings
     * additionally obey a texture-byte budget. */
    private static boolean unicodeText(String text,float x,float y,int argb,int style) {
        String plain=FORMAT_CODES.matcher(text).replaceAll("");
        String key=style+":"+plain;
        UnicodeText item=unicode.get(key);
        if (item==null) {
            BufferedImage bitmap=null;
            try {
                Font font=systemFont(style,plain);
                double measuredWidth=font.getStringBounds(plain,FONT_CONTEXT).getWidth();
                int width=Math.min(4096,Math.max(4,(int)Math.ceil(measuredWidth)+6));
                int height=Math.max(4,(int)Math.ceil(font.getLineMetrics(plain,FONT_CONTEXT).getHeight())+6);
                bitmap=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
                Graphics2D g=bitmap.createGraphics();
                try {
                    g.setFont(font);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                    g.setColor(java.awt.Color.WHITE);g.drawString(plain,3,g.getFontMetrics().getAscent()+3);
                } finally { g.dispose(); }
                long bytes=(long)width*height*4;
                // Evict before upload, so the temporary new texture cannot exceed
                // the resident GPU budget. Raster dimensions and label layout stay.
                while (!unicode.isEmpty() && (unicode.size()>=UNICODE_CACHE_ENTRIES
                        || unicodeBytes+bytes>UNICODE_CACHE_BYTES)) {
                    String oldest=unicode.keySet().iterator().next();
                    UnicodeText removed=unicode.get(oldest);
                    GL11.glDeleteTextures(removed.texture);
                    unicode.remove(oldest);
                    unicodeBytes -= (long) removed.width * removed.height * 4;
                }
                if (bytes>UNICODE_CACHE_BYTES) return false;
                int texture=uploadTexture(bitmap);
                boolean retained=false;
                try {
                    item=new UnicodeText(texture,width,height,(float)measuredWidth/3);unicode.put(key,item);
                    unicodeBytes += bytes;retained=true;
                } finally { if (!retained) GL11.glDeleteTextures(texture); }
            } catch (Throwable unavailable) {
                fontFailed=true;return false;
            } finally { if (bitmap!=null) bitmap.flush(); }
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glBindTexture(GL11.GL_TEXTURE_2D,item.texture);color(argb,1);
        float left=x-1,top=y-1,right=left+item.width/3f,bottom=top+item.height/3f;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0,0);GL11.glVertex2f(left,top);GL11.glTexCoord2f(0,1);GL11.glVertex2f(left,bottom);
        GL11.glTexCoord2f(1,1);GL11.glVertex2f(right,bottom);GL11.glTexCoord2f(1,0);GL11.glVertex2f(right,top);
        GL11.glEnd();return true;
    }

    public static void begin(int width, int height, float uiScale, float y, float alpha) {
        IntBuffer viewport = queryBuffer();
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        viewportX = viewport.get(0); viewportY = viewport.get(1);
        viewportW = viewport.get(2); viewportH = viewport.get(3);
        logicalWidth = Math.max(1, width); logicalHeight = Math.max(1, height);
        scale = uiScale; offsetY = y; opacity = Math.max(0, Math.min(1, alpha));
        matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        shaderProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL20.glUseProgram(0);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, 1);
        GL11.glTranslatef(0, offsetY, 0);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        // Minecraft restores GL_FLAT after several GUI passes. Our coverage
        // fringe needs interpolated alpha; flat shading turns alternating strip
        // triangles into opaque teeth. Own every raster state we depend on.
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glDisable(GL11.GL_POLYGON_SMOOTH);
        GL11.glDisable(GL11.GL_POLYGON_STIPPLE);
        GL11.glDisable(GL11.GL_LINE_STIPPLE);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glDisable(GL11.GL_DITHER);
        GL11.glDisable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);
        GL11.glDisable(GL13.GL_SAMPLE_COVERAGE);
        GL11.glColorMask(true, true, true, true);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        int units = Math.min(16,GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));
        for (int unit=0;unit<units;unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);
            GL11.glDisable(GL11.GL_TEXTURE_1D);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(org.lwjgl.opengl.GL12.GL_TEXTURE_3D);
            GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_R);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }

    public static void end() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL20.glUseProgram(shaderProgram);
        GL11.glMatrixMode(matrixMode);
        opacity = 1;
    }

    public static float opacity(float value) {
        float old = opacity; opacity = value; return old;
    }

    /** Intersect with the parent's clip, and restore it exactly on pop. */
    public static void clip(float x, float y, float width, float height) {
        float sx = viewportW * scale / logicalWidth, sy = viewportH * scale / logicalHeight;
        int left = viewportX + (int) Math.ceil(x * sx);
        int right = viewportX + (int) Math.floor((x + width) * sx);
        int bottom = viewportY + viewportH - (int) Math.floor((y + offsetY + height) * sy);
        int top = viewportY + viewportH - (int) Math.ceil((y + offsetY) * sy);
        if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
            IntBuffer old = queryBuffer();
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, old);
            left = Math.max(left, old.get(0)); bottom = Math.max(bottom, old.get(1));
            right = Math.min(right, old.get(0) + old.get(2)); top = Math.min(top, old.get(1) + old.get(3));
        }
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
    }

    public static void unclip() { GL11.glPopAttrib(); }

    private static void color(int argb, float coverage) {
        GL11.glColor4f(((argb >>> 16) & 255) / 255f, ((argb >>> 8) & 255) / 255f,
            (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f * opacity * coverage);
    }

    public static void rect(int left, int top, int right, int bottom, int color) {
        round(left, top, right - left, bottom - top, 0, color);
    }

    public static void round(float x, float y, float w, float h, float radius, int argb) {
        if (w <= 0 || h <= 0) return;
        float r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        color(argb, 1);
        if (r < 0.1f) {
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y); GL11.glVertex2f(x, y + h);
            GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x + w, y);
            GL11.glEnd(); return;
        }
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(x + w / 2, y + h / 2);
        for (int i = 0; i <= 52; i++) edge(x, y, w, h, r, i, 0);
        GL11.glEnd();
        // One physical pixel of coverage at every UI/window/retina scale.
        float fringe = 1f / Math.max(0.1f, Math.min(viewportW * scale / logicalWidth,
            viewportH * scale / logicalHeight));
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= 52; i++) {
            color(argb, 1); edge(x, y, w, h, r, i, 0);
            color(argb, 0); edge(x, y, w, h, r, i, fringe);
        }
        GL11.glEnd();
    }

    private static void edge(float x, float y, float w, float h, float r, int step, float fringe) {
        step %= 52;
        int corner = step / 13;
        float cx = (corner == 0 || corner == 3) ? x + r : x + w - r;
        float cy = corner < 2 ? y + r : y + h - r;
        GL11.glVertex2f(cx + (r + fringe) * EDGE_COS[step], cy + (r + fringe) * EDGE_SIN[step]);
    }

    public static void line(float x1, float y1, float x2, float y2, int argb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.3f);
        color(argb, 1);
        GL11.glBegin(GL11.GL_LINES); GL11.glVertex2f(x1,y1); GL11.glVertex2f(x2,y2); GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    public static void icon(int kind, float x, float y, int color) {
        if (kind == 0) {
            for (int i=0;i<3;i++) { line(x+3,y+4+i*3,x+11,y+4+i*3,color); round(x+(i==1?8:4),y+2.5f+i*3,2.5f,3,1,color); }
        } else if (kind == 1) {
            line(x+3,y+3,x+7,y+2,color); line(x+7,y+2,x+11,y+3,color);
            line(x+3,y+3,x+3,y+7,color); line(x+11,y+3,x+11,y+7,color);
            line(x+3,y+7,x+7,y+12,color); line(x+7,y+12,x+11,y+7,color);
        } else if (kind == 2) {
            line(x+8,y+2,x+4,y+8,color); line(x+4,y+8,x+9,y+6,color); line(x+9,y+6,x+6,y+12,color);
        } else if (kind == 3) {
            for (int row=0;row<2;row++) for (int col=0;col<2;col++) round(x+3+col*5,y+3+row*5,3,3,0.7f,color);
        } else if (kind == 4) {
            line(x+3,y+3,x+11,y+3,color); line(x+3,y+3,x+3,y+9,color); line(x+11,y+3,x+11,y+9,color);
            line(x+11,y+9,x+7,y+9,color); line(x+7,y+9,x+4,y+12,color); line(x+4,y+12,x+4,y+9,color);
            line(x+5,y+6,x+9,y+6,color);
        } else if (kind == 5) {
            round(x+3,y+8,2,3,0.7f,color); round(x+6,y+5,2,6,0.7f,color); round(x+9,y+3,2,8,0.7f,color);
        } else {
            line(x+5,y+2,x+9,y+2,color); line(x+6,y+2,x+6,y+6,color); line(x+8,y+2,x+8,y+6,color);
            line(x+6,y+6,x+3,y+11,color); line(x+8,y+6,x+11,y+11,color); line(x+3,y+11,x+11,y+11,color);
        }
    }

    private static IntBuffer queryBuffer() {
        if (queryValues==null) queryValues=BufferUtils.createIntBuffer(16);
        queryValues.clear();return queryValues;
    }

    /** Synchronous GL upload consumes each reusable stripe before it is reused. */
    private static int uploadTexture(BufferedImage bitmap) {
        int width=bitmap.getWidth(),height=bitmap.getHeight();
        if (width<=0 || width>UPLOAD_BYTES/4) throw new IllegalArgumentException("Texture width");
        if (uploadPixels==null) uploadPixels=BufferUtils.createByteBuffer(UPLOAD_BYTES);
        int texture=GL11.glGenTextures();
        if (texture==0) throw new IllegalStateException("No texture name");
        boolean complete=false;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
            GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
            try {
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,1);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS,0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS,0);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,width,height,0,
                    GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
                int stripeRows=Math.max(1,UPLOAD_BYTES/(width*4));
                for (int row=0;row<height;row+=stripeRows) {
                    int rows=Math.min(stripeRows,height-row);
                    uploadPixels.clear();
                    for (int dy=0;dy<rows;dy++) for (int col=0;col<width;col++)
                        uploadPixels.put((byte)255).put((byte)255).put((byte)255)
                            .put((byte)(bitmap.getRGB(col,row+dy)>>>24));
                    uploadPixels.flip();
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,row,width,rows,
                        GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,uploadPixels);
                }
            } finally { GL11.glPopClientAttrib(); }
            complete=true;return texture;
        } finally { if (!complete) GL11.glDeleteTextures(texture); }
    }

    /** Client/render thread only, with its current GL context; used on close/world change. */
    public static void releaseTextures() {
        java.util.Iterator<UnicodeText> items=unicode.values().iterator();
        while (items.hasNext()) {
            UnicodeText item=items.next();
            GL11.glDeleteTextures(item.texture);
            unicodeBytes -= (long)item.width*item.height*4;
            items.remove();
        }
        for (int i=0;i<textures.length;i++) if (textures[i]!=0) {
            GL11.glDeleteTextures(textures[i]);textures[i]=0;
        }
        fontFailed=false;
    }

    /** Client/render thread only. Release names before dropping the Java scratch buffers. */
    public static void dispose() {
        releaseTextures();uploadPixels=null;queryValues=null;
        for (Font[] style : fonts) java.util.Arrays.fill(style,null);
    }

    private static boolean font(int style) {
        if (fontFailed) return false;
        if (textures[style] != 0) return true;
        BufferedImage atlas=null;
        try {
            atlas = new BufferedImage(ATLAS, ATLAS, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = atlas.createGraphics();
            try {
                g.setFont(new Font("Segoe UI", style == 0 ? Font.PLAIN : Font.BOLD, 30).deriveFont(SIZES[style] * 3));
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                g.setColor(java.awt.Color.WHITE);
                FontMetrics metrics = g.getFontMetrics();
                for (int i = 0; i < GLYPHS.length(); i++) {
                    String glyph = GLYPHS.substring(i, i + 1);
                    advances[style][i] = (float) metrics.getStringBounds(glyph, g).getWidth() / 3;
                    g.setClip(i % COLUMNS * CELL_W, i / COLUMNS * CELL_H, CELL_W, CELL_H);
                    g.drawString(glyph, i % COLUMNS * CELL_W + 3, i / COLUMNS * CELL_H + metrics.getAscent() + 3);
                }
            } finally { g.dispose(); }
            textures[style] = uploadTexture(atlas);
            return true;
        } catch (Throwable unavailable) { fontFailed = true; return false; }
        finally { if (atlas!=null) atlas.flush(); }
    }

    public static float width(String text, int style) {
        if (text == null) return 0;
        if (nonAscii(text)) {
            String plain=FORMAT_CODES.matcher(text).replaceAll("");
            UnicodeText cached=unicode.get(style+":"+plain);
            if (cached!=null) return cached.advance;
            try { return (float)systemFont(style,plain).getStringBounds(plain,FONT_CONTEXT).getWidth()/3; }
            catch (Throwable unavailable) { fontFailed=true;return text.length()*6; }
        }
        if (!font(style)) return text.length() * 6;
        float result = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\u00a7' && i + 1 < text.length()) { i++; continue; }
            int glyph = GLYPHS.indexOf(text.charAt(i));
            result += advances[style][glyph < 0 ? GLYPHS.indexOf('?') : glyph];
        }
        return result;
    }

    public static String fit(String text, float available, int style) {
        if (text == null || available <= 0) return "";
        if (width(text, style) <= available) return text;
        String suffix = "\u2026";
        if (width(suffix, style) > available) return "";
        int end = text.length();
        // Atlas glyphs have nonnegative advances and no shaping. Their prefix
        // widths are monotonic, so binary search returns exactly the old suffix.
        // Formatting codes / shaped Unicode retain the exact reference scan.
        if (text.indexOf('\u00a7') < 0 && !nonAscii(text)) {
            int low = 0, high = end;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (width(text.substring(0, middle) + suffix, style) > available) high = middle - 1;
                else low = middle;
            }
            end = low;
        } else while (end > 0 && width(text.substring(0, end) + suffix, style) > available) end--;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + suffix;
    }

    /** Exact trailing viewport used by editable input fields; never splits a surrogate pair. */
    public static String fitTail(String text, float available, int style) {
        if (text == null || text.isEmpty()) return "";
        if (width(text, style) <= available) return text;
        if (available < 0) return "";
        if (text.indexOf('\u00a7') < 0 && !nonAscii(text)) {
            int low = 0, high = text.length();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (width(text.substring(middle), style) > available) low = middle + 1;
                else high = middle;
            }
            return text.substring(low);
        }
        while (!text.isEmpty() && width(text, style) > available)
            text = text.substring(text.offsetByCodePoints(0, 1));
        return text;
    }

    public static boolean text(String text, float x, float y, int argb, int style) {
        if (text == null || text.length() == 0) return true;
        if (nonAscii(text)) return unicodeText(text,x,y,argb,style);
        if (!font(style)) return false;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[style]);
        color(argb, 1);
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\u00a7' && i + 1 < text.length()) { i++; continue; }
            int glyph = GLYPHS.indexOf(text.charAt(i));
            if (glyph < 0) glyph = GLYPHS.indexOf('?');
            // Half-pixel insets prevent linear filtering from sampling another cell.
            float u = (glyph % COLUMNS * CELL_W + 0.5f) / ATLAS, v = (glyph / COLUMNS * CELL_H + 0.5f) / ATLAS;
            float u2 = u + (CELL_W - 1f) / ATLAS, v2 = v + (CELL_H - 1f) / ATLAS;
            float left = x - 5f/6, top = y - 5f/6, right = left + (CELL_W - 1f) / 3, bottom = top + (CELL_H - 1f) / 3;
            GL11.glTexCoord2f(u,v); GL11.glVertex2f(left,top);
            GL11.glTexCoord2f(u,v2); GL11.glVertex2f(left,bottom);
            GL11.glTexCoord2f(u2,v2); GL11.glVertex2f(right,bottom);
            GL11.glTexCoord2f(u2,v); GL11.glVertex2f(right,top);
            x += advances[style][glyph];
        }
        GL11.glEnd();
        return true;
    }
}
