import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Pixel-based regression for the actual inherited-state comb artifact. */
public final class AdninUiRasterStateTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    private static BufferedImage draw(int w, int h) {
        AdninUi.begin(320, 200, 1, 0, 1);
        try {
            AdninUi.rect(0, 0, 320, 200, 0xFF1C1F26);
            AdninUi.round(14, 12, 290, 174, 16, 0xFF343840);
            AdninUi.round(15, 13, 288, 172, 15, 0xFF25282F);
            for (int row = 0; row < 3; row++) {
                int y = 25 + row * 35;
                AdninUi.round(24, y, 270, 27, 8, 0xFF2C3039);
                AdninUi.round(247, y + 6, 35, 15, 7.5f, 0xFF4A4E59);
                AdninUi.round(249, y + 8, 11, 11, 5.5f, 0xFFFFFFFF);
            }
            for (int col = 0; col < 2; col++) {
                int x = 219 + col * 37;
                AdninUi.round(x, 143, 29, 29, 7, 0xFF8ABBFF);
                AdninUi.round(x + 1, 144, 27, 27, 6, 0xFF323947);
                AdninUi.line(x + 9, 157, x + 19, 157, 0xFF8ABBFF);
                if (col == 1) AdninUi.line(x + 14, 152, x + 14, 162, 0xFF8ABBFF);
            }
            AdninUi.text("Rounded controls", 30, 35, AdninUi.TEXT, 0);
        } finally { AdninUi.end(); }
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = ((h - y - 1) * w + x) * 4;
            result.setRGB(x, y, 0xFF000000 | ((pixels.get(i) & 255) << 16)
                | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
        }
        return result;
    }

    public static int run(Path output, int w, int h) throws Exception {
        GL11.glViewport(0, 0, w, h);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity(); GL11.glOrtho(0, 320, 200, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
        BufferedImage clean = draw(w, h);
        ImageIO.write(clean, "png", output.resolve("raster-clean.png").toFile());
        int vertex = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        int fragment = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(vertex, "void main(){gl_Position=ftransform();}"); GL20.glCompileShader(vertex);
        GL20.glShaderSource(fragment, "void main(){gl_FragColor=vec4(1.,0.,0.,1.);}"); GL20.glCompileShader(fragment);
        check(GL20.glGetShaderi(vertex, GL20.GL_COMPILE_STATUS) != 0
            && GL20.glGetShaderi(fragment, GL20.GL_COMPILE_STATUS) != 0, "fixture shaders compile");
        int program = GL20.glCreateProgram(); GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, fragment);
        GL20.glLinkProgram(program);
        check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0, "fixture shader program links");
        try {
        for (int state = 0; state < 7; state++) {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glPushMatrix();
            try {
                if (state == 0 || state == 5) GL11.glShadeModel(GL11.GL_FLAT);
                if (state == 1 || state == 5) {
                    GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
                    GL11.glEnable(GL11.GL_POLYGON_STIPPLE);
                    GL11.glEnable(GL11.GL_POLYGON_SMOOTH);
                    GL11.glEnable(GL11.GL_LINE_STIPPLE);
                    GL11.glLineStipple(2, (short)0x0F0F);
                }
                if (state == 2 || state == 5) {
                    GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
                    GL11.glEnable(GL11.GL_COLOR_LOGIC_OP);
                    GL11.glLogicOp(GL11.GL_XOR);
                    GL11.glColorMask(false, true, false, true);
                }
                if (state == 3 || state == 5) {
                    GL11.glTranslatef(0.31f, 0.24f, 0);
                    GL11.glEnable(GL11.GL_TEXTURE_GEN_S);
                    GL11.glEnable(GL11.GL_TEXTURE_GEN_T);
                }
                if (state == 4 || state == 5) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE1);
                    GL11.glEnable(GL13.GL_TEXTURE_CUBE_MAP);
                    GL11.glEnable(GL11.GL_ALPHA_TEST); GL11.glAlphaFunc(GL11.GL_GREATER, 0.9f);
                    GL11.glEnable(GL13.GL_SAMPLE_COVERAGE); GL13.glSampleCoverage(0.1f, true);
                }
                if (state == 6) GL20.glUseProgram(program);
                int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
                int shade = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
                int blend = GL11.glGetInteger(GL14.GL_BLEND_EQUATION);
                int shader = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
                IntBuffer polygon = BufferUtils.createIntBuffer(16); GL11.glGetInteger(GL11.GL_POLYGON_MODE, polygon);
                boolean stipple = GL11.glIsEnabled(GL11.GL_POLYGON_STIPPLE);
                boolean logic = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP);
                BufferedImage inherited = draw(w, h);
                ImageIO.write(inherited, "png", output.resolve("raster-inherited-" + state + ".png").toFile());
                int differences = 0;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
                    if (clean.getRGB(x, y) != inherited.getRGB(x, y)) differences++;
                check(differences == 0, "inherited state " + state + " changes " + differences + " pixels");
                check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == active, "active texture restored");
                check(GL11.glGetInteger(GL11.GL_SHADE_MODEL) == shade, "shade model restored");
                check(GL11.glGetInteger(GL14.GL_BLEND_EQUATION) == blend, "blend equation restored");
                check(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == shader, "shader program restored");
                IntBuffer after = BufferUtils.createIntBuffer(16); GL11.glGetInteger(GL11.GL_POLYGON_MODE, after);
                check(polygon.get(0) == after.get(0) && polygon.get(1) == after.get(1), "polygon mode restored");
                check(GL11.glIsEnabled(GL11.GL_POLYGON_STIPPLE) == stipple, "stipple restored");
                check(GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP) == logic, "logic op restored");
                check(GL11.glGetInteger(GL11.GL_MATRIX_MODE) == GL11.GL_TEXTURE, "matrix selector restored");
                check(GL11.glGetError() == GL11.GL_NO_ERROR, "no OpenGL error");
            } finally {
                GL20.glUseProgram(0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glPopMatrix();
                GL11.glPopAttrib();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
            }
        }
        } finally { GL20.glDeleteProgram(program); GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment); }
        return checks;
    }

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]); Files.createDirectories(output);
        Pbuffer buffer = new Pbuffer(1280, 800, new PixelFormat(), null, null);
        try { buffer.makeCurrent(); System.out.println("Raster state regression: " + run(output, 1280, 800) + " checks passed."); }
        finally { buffer.destroy(); }
    }
}
