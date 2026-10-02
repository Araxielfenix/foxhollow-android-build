package dev.encounter.foxhollow;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tunable layout settings for the on-screen controls.
 *
 * Every screen has a different shape and every thumb a different reach, so the layout is not
 * hardcoded: the debug panel writes here and the view re-lays out. Values persist so the choice
 * survives a restart.
 *
 * Defaults are chosen for the "fills the screen" look: controls anchored to the outer edges with
 * only a thin margin, sized from the shorter screen axis.
 */
public class TouchControlSettings {
    private static final String PREFS = "foxhollow_touch_controls";

    private static final String KEY_SCALE = "scale";
    private static final String KEY_ALPHA = "alpha";
    private static final String KEY_MARGIN = "margin";
    private static final String KEY_DEADZONE = "deadzone";
    private static final String KEY_STRETCH = "stretch";
    private static final String KEY_STICK_RING = "stick_ring";
    private static final String KEY_MINI = "mini";
    private static final String KEY_RENDER_SCALE = "render_scale";
    private static final String KEY_SHOW_FPS = "show_fps";
    private static final String KEY_VSYNC = "vsync";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_DISPLAY_MODE = "display_mode";

    /** Original GameCube framing: 4:3 with bars down the sides. */
    public static final int DISPLAY_4_3 = 0;

    /** Widened to 16:9, still letterboxed on a taller screen. */
    public static final int DISPLAY_16_9 = 1;

    /** Stretched to the shape of the display, no bars at all. */
    public static final int DISPLAY_FULL = 2;

    public static final String[] DISPLAY_MODE_NAMES = { "4:3", "16:9", "FULL SCREEN" };

    public static final float SCALE_MIN = 0.6f;
    public static final float SCALE_MAX = 1.6f;
    public static final float MARGIN_MIN = 0f;
    public static final float MARGIN_MAX = 0.14f;
    public static final float DEADZONE_MIN = 0.05f;
    public static final float DEADZONE_MAX = 0.40f;
    public static final float RENDER_SCALE_MIN = 0.40f;
    public static final float RENDER_SCALE_MAX = 1.00f;

    /** Multiplies every control size. */
    public float scale = 1.0f;

    /** Base opacity of an unpressed control, 0..255. */
    public int alpha = 150;

    /** Gap left between the outermost control and the screen edge, as a fraction of it. */
    public float margin = 0.025f;

    /** Stick travel below this counts as centred. */
    public float deadzone = 0.16f;

    /** Anchor the clusters to the screen edges instead of keeping them inset. */
    public boolean stretch = true;

    /** Draw the ring that shows where the stick currently originates. */
    public boolean stickRing = true;

    /** Shrink the whole cluster; useful when the game needs the screen. */
    public boolean mini = false;

    /** Fraction of the display the game renders into; below 1 trades sharpness for frames. */
    public float renderScale = 1.0f;

    /** Synchronize frame rate with display refresh (VSync). */
    public boolean vSync = true;

    /** Master audio volume, 0.0 to 1.0. */
    public float volume = 1.0f;

    /** Show the frames-per-second counter over the game. */
    public boolean showFps = false;

    /** One of DISPLAY_4_3, DISPLAY_16_9 or DISPLAY_FULL. */
    public int displayMode = DISPLAY_FULL;

    private final SharedPreferences prefs;

    public TouchControlSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    /** Reads persisted values, clamping anything out of range. */
    public void load() {
        scale = clamp(prefs.getFloat(KEY_SCALE, scale), SCALE_MIN, SCALE_MAX);
        alpha = clamp(prefs.getInt(KEY_ALPHA, alpha), 40, 255);
        margin = clamp(prefs.getFloat(KEY_MARGIN, margin), MARGIN_MIN, MARGIN_MAX);
        deadzone = clamp(prefs.getFloat(KEY_DEADZONE, deadzone), DEADZONE_MIN, DEADZONE_MAX);
        stretch = prefs.getBoolean(KEY_STRETCH, stretch);
        stickRing = prefs.getBoolean(KEY_STICK_RING, stickRing);
        mini = prefs.getBoolean(KEY_MINI, mini);
        renderScale = clamp(prefs.getFloat(KEY_RENDER_SCALE, renderScale), RENDER_SCALE_MIN, RENDER_SCALE_MAX);
        showFps = prefs.getBoolean(KEY_SHOW_FPS, showFps);
        vSync = prefs.getBoolean(KEY_VSYNC, vSync);
        volume = clamp(prefs.getFloat(KEY_VOLUME, volume), 0.0f, 1.0f);
        displayMode = Math.max(0, Math.min(2, prefs.getInt(KEY_DISPLAY_MODE, displayMode)));
    }

    /** Persists the current values; called after every change from the debug panel. */
    public void save() {
        prefs.edit()
                .putFloat(KEY_SCALE, scale)
                .putInt(KEY_ALPHA, alpha)
                .putFloat(KEY_MARGIN, margin)
                .putFloat(KEY_DEADZONE, deadzone)
                .putBoolean(KEY_STRETCH, stretch)
                .putBoolean(KEY_STICK_RING, stickRing)
                .putBoolean(KEY_MINI, mini)
                .putFloat(KEY_RENDER_SCALE, renderScale)
                .putBoolean(KEY_SHOW_FPS, showFps)
                .putBoolean(KEY_VSYNC, vSync)
                .putFloat(KEY_VOLUME, volume)
                .putInt(KEY_DISPLAY_MODE, displayMode)
                .apply();
    }

    /** Restores the shipped layout, which is what most players want. */
    public void reset() {
        scale = 1.0f;
        alpha = 150;
        margin = 0.025f;
        deadzone = 0.16f;
        stretch = true;
        stickRing = true;
        mini = false;
        renderScale = 1.0f;
        showFps = false;
        vSync = true;
        volume = 1.0f;
        displayMode = DISPLAY_FULL;
        save();
    }

    /** Recarga los valores desde disco; útil si el proceso murió y revivió. */
    public void reload() {
        load();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}