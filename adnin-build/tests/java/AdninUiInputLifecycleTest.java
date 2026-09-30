import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Actual Gui4 bytecode with owned input, save and renderer-boundary fixtures. */
public final class AdninUiInputLifecycleTest {
    private static int checks;

    private static Field field(String name) throws Exception {
        Field result = AdninGui4.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void key(AdninGui4 screen, int key) throws Exception {
        Method method = AdninGui4.class.getDeclaredMethod("keyTyped", char.class, int.class);
        method.setAccessible(true);
        method.invoke(screen, '\0', key);
    }

    private static void release(AdninGui4 screen, int button) throws Exception {
        Method method = AdninGui4.class.getDeclaredMethod("mouseReleased", int.class, int.class, int.class);
        method.setAccessible(true);
        method.invoke(screen, 0, 0, button);
    }

    private static AdninGui4 screen(int panel) {
        AdninGui4 screen = new AdninGui4();
        screen.width = 800; screen.height = 560; screen.selectedTheme = panel;
        screen.initGui();
        return screen;
    }

    private static void dragging(AdninGui4 screen) throws Exception {
        field("draggingUiScale").setBoolean(screen, true);
        field("draggingHitboxSlider").setBoolean(screen, true);
        field("hitboxSliderTrackX").setInt(screen, 350);
        field("hitboxSliderTrackW").setInt(screen, 250);
    }

    private static void noDrag(AdninGui4 screen, String context) throws Exception {
        check(!field("draggingUiScale").getBoolean(screen), context + ": UI scale drag cleared");
        check(!field("draggingHitboxSlider").getBoolean(screen), context + ": hitbox drag cleared");
        check(field("hitboxSliderTrackX").getInt(screen) == 0
            && field("hitboxSliderTrackW").getInt(screen) == 0, context + ": old track geometry cleared");
    }

    private static void drawBeforeRenderer(AdninGui4 screen, int x) throws Exception {
        // Keep the fade in its active interval without using sleep or a game.
        if (field("closingAt").getLong(screen) != 0)
            field("closingAt").setLong(screen, System.nanoTime());
        try {
            screen.drawScreen(x, 100, 0);
            throw new AssertionError("Expected owned renderer boundary");
        } catch (AdninUi.RenderBoundary expected) { }
    }

    private static void closingAndResize() throws Exception {
        Keyboard.reset(false); Mouse.down = true; AdninGui4.uiScalePercent = 100;
        AdninGui4 scale = screen(0);
        // Preload bytecode before the timing-sensitive close path.
        drawBeforeRenderer(scale, 100);
        dragging(scale); field("activeInput").setInt(scale, 1);
        key(scale, 1);
        check(field("closingAt").getLong(scale) != 0, "Escape starts the close animation");
        check(field("activeInput").getInt(scale) == 0, "Escape clears text focus");
        noDrag(scale, "Escape");
        AdninFeatures.saves = 0;
        drawBeforeRenderer(scale, 900);
        check(AdninGui4.uiScalePercent == 100 && AdninFeatures.saves == 0,
            "closing UI scale cannot move or save while left button stays down");
        dragging(scale);
        drawBeforeRenderer(scale, 900);
        check(AdninGui4.uiScalePercent == 100 && AdninFeatures.saves == 0,
            "draw path itself rejects a stale scale drag after close begins");

        long closing = field("closingAt").getLong(scale);
        long opened = field("openedAt").getLong(scale);
        for (int i = 0; i < 4; i++) {
            dragging(scale);
            scale.width = i % 2 == 0 ? 320 : 800;
            scale.height = i % 2 == 0 ? 180 : 560;
            scale.initGui();
            check(field("closingAt").getLong(scale) == closing, "F11/resize preserves pending close");
            check(field("openedAt").getLong(scale) == opened, "F11/resize does not replay opening fade");
            noDrag(scale, "F11/resize");
        }
        scale.onGuiClosed();
        check(!Keyboard.repeat, "close after repeated resize restores original repeat state");
        noDrag(scale, "closed screen");
        check(field("openedAt").getLong(scale) == 0 && field("closingAt").getLong(scale) == 0,
            "closed instance can reopen with fresh animation state");
        scale.initGui();
        check(field("openedAt").getLong(scale) != 0 && field("closingAt").getLong(scale) == 0,
            "reopening the same instance does not inherit a pending close");
        scale.onGuiClosed();

        AdninGui4 hitbox = screen(2);
        AdninGui4.coloredHitboxes = true; AdninGui4.hitboxThickness = 1;
        dragging(hitbox); key(hitbox, 1); drawBeforeRenderer(hitbox, 900);
        check(AdninGui4.hitboxThickness == 1, "closing hitbox drag cannot change thickness");
        dragging(hitbox); drawBeforeRenderer(hitbox, 900);
        check(AdninGui4.hitboxThickness == 1, "draw rejects a stale hitbox drag during fade");
        hitbox.onGuiClosed();

        AdninGui4 released = screen(2);
        dragging(released); release(released, 1);
        check(field("draggingHitboxSlider").getBoolean(released), "right release does not end left drag");
        release(released, 0); noDrag(released, "left release");
        // Poll state can lag behind an actual release event; the event still wins.
        Mouse.down = true; AdninGui4.hitboxThickness = 1;
        drawBeforeRenderer(released, 900);
        check(AdninGui4.hitboxThickness == 1, "released slider cannot restart from stale button polling");
        released.onGuiClosed();
    }

    private static void repeatOwnership() throws Exception {
        for (boolean original : new boolean[]{false, true}) {
            Keyboard.reset(original);
            AdninGui4 first = screen(0);
            check(Keyboard.repeat, "own menu enables repeated editing keys");
            check(field("keyboardRepeatCaptured").getBoolean(first), "original repeat state captured");
            for (int i = 0; i < 5; i++) first.initGui();
            check(field("keyboardRepeatBefore").getBoolean(first) == original,
                "resize never replaces prior owner's repeat state");
            dragging(first); field("activeInput").setInt(first, 1);
            first.onGuiClosed();
            check(Keyboard.repeat == original, "screen switch restores prior repeat state");
            check(field("activeInput").getInt(first) == 0, "screen switch clears input focus");
            noDrag(first, "screen switch");
            int setters = Keyboard.setters;
            first.onGuiClosed();
            check(Keyboard.setters == setters, "duplicate close never overwrites next owner's input state");
            AdninGui4 second = screen(2);
            check(Keyboard.repeat, "next owned screen acquires repeat input");
            second.onGuiClosed();
            check(Keyboard.repeat == original, "next owned screen restores same external state");
        }

        Keyboard.reset(false); Keyboard.created = false;
        AdninGui4 absent = screen(0);
        check(!field("keyboardRepeatCaptured").getBoolean(absent), "missing device is not captured");
        absent.onGuiClosed();
        check(Keyboard.setters == 0, "missing device never receives a repeat API call");
        absent.initGui(); Keyboard.created = true; absent.initGui();
        check(Keyboard.repeat, "a later available device can acquire repeat safely");
        absent.onGuiClosed(); check(!Keyboard.repeat, "late device state restored");

        for (String operation : new String[]{"created", "read", "write"}) {
            for (boolean linkage : new boolean[]{false, true}) {
                Keyboard.reset(false); Keyboard.failure = operation; Keyboard.linkageFailure = linkage;
                AdninGui4 failed = screen(0);
                Keyboard.failure = "";
                failed.onGuiClosed();
                check(!Keyboard.repeat, "failed " + operation + " cannot leak repeat enablement");
                check(!field("keyboardRepeatCaptured").getBoolean(failed), "failed init ownership released");
            }
        }
        for (boolean linkage : new boolean[]{false, true}) {
            Keyboard.reset(false); AdninGui4 failedClose = screen(0);
            Keyboard.failure = "write"; Keyboard.linkageFailure = linkage;
            failedClose.onGuiClosed();
            check(!field("keyboardRepeatCaptured").getBoolean(failedClose), "failed close drops old ownership");
            Keyboard.failure = "";
        }
        for (int fault : new int[]{1, 2}) {
            Keyboard.reset(false); AdninGui4 failedGl = screen(0);
            AdninUi.releaseFailure = fault; failedGl.onGuiClosed();
            check(!Keyboard.repeat, "renderer cleanup fault still restores keyboard state");
        }
    }

    private static void enterKeys() throws Exception {
        for (int key : new int[]{28, Keyboard.KEY_NUMPADENTER}) {
            Keyboard.reset(false); AdninGui4 s = screen(2);
            field("activeInput").setInt(s, 4); AdninGui4.gl_message = "owned fixture";
            key(s, key);
            check(field("activeInput").getInt(s) == 0, "both Enter keys finish text input");
            check(AdninGui4.gl_message.equals("owned fixture"), "Enter preserves the input text");
            check(field("closingAt").getLong(s) == 0, "Enter does not close the menu");
            s.onGuiClosed();
        }
    }

    public static void main(String[] args) throws Exception {
        closingAndResize(); repeatOwnership(); enterKeys();
        System.out.println("AdninUiInputLifecycleTest: " + checks
            + " checks passed; production Gui4 draw/key/release/init/close paths;"
            + " owned input and renderer boundary; no game, GL, config, API, network or native payload");
    }
}
