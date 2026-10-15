package dev.encounter.foxhollow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * On-screen GameCube controls.
 *
 * The touch screen is the only input the phone has, and Star Fox Adventures needs a stick plus
 * face buttons, so this draws them over SDL's SurfaceView and feeds the result to Aurora as a
 * virtual controller (see nativeSetVirtualPad).
 *
 * The stick floats: the origin is wherever the finger lands inside the stick zone, which avoids
 * having to look at the screen to steer. Everything else is a fixed target, which is what makes
 * face buttons usable while the stick is held with a different thumb.
 */
public class TouchControlsView extends View {
    // Mirrors dolphin/pad.h; PAD_BUTTON_MENU and PAD_BUTTON_START share a bit.
    private static final int BTN_LEFT = 0x0001;
    private static final int BTN_RIGHT = 0x0002;
    private static final int BTN_DOWN = 0x0004;
    private static final int BTN_UP = 0x0008;
    private static final int BTN_Z = 0x0010;
    private static final int BTN_R = 0x0020;
    private static final int BTN_L = 0x0040;
    private static final int BTN_A = 0x0100;
    private static final int BTN_B = 0x0200;
    private static final int BTN_X = 0x0400;
    private static final int BTN_Y = 0x0800;
    private static final int BTN_START = 0x1000;

    // C-stick (right analog stick) buttons - mapped to C-stick directions
    private static final int BTN_C_UP = 0x2000;
    private static final int BTN_C_DOWN = 0x4000;
    private static final int BTN_C_LEFT = 0x8000;
    private static final int BTN_C_RIGHT = 0x10000;

    /** Past this the matching d-pad bit is synthesised for code that only reads the d-pad. */
    private static final float DPAD_SYNTHESIS = 0.55f;

    private static final float MINI_SCALE = 0.62f;
    private static final int MINI_ALPHA = 96;

    /** GameCube face-button colours: A green, B red, X yellow, Y blue. */
    private static final int COLOR_A = 0xFF00FF00;
    private static final int COLOR_B = 0xFFFF0000;
    private static final int COLOR_X = 0xFFFFFF00;
    private static final int COLOR_Y = 0xFF0000FF;
    private static final int COLOR_SHOULDER = 0xFFFFFFFF;

    /** Notified when the player swipes in from a screen edge, so the activity can show the menu. */
    public interface OnMenuGestureListener {
        void onMenuGesture();
    }

    /** How close to a screen edge a swipe has to start to count as a menu gesture, in dp. */
    private static final float EDGE_GESTURE_DP = 22f;

    /** A single hit target: a labelled circle, positioned from fractions of the view size. */
    private static final class Control {
        final String label;
        final int button;
        final float cx;
        final float cy;
        final float radius;
        final int color;

        Control(String label, int button, float cx, float cy, float radius) {
            this(label, button, cx, cy, radius, COLOR_SHOULDER);
        }

        Control(String label, int button, float cx, float cy, float radius, int color) {
            this.label = label;
            this.button = button;
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
            this.color = color;
        }
    }

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Pointer id -> control index, so each finger owns exactly one control. */
    private final Map<Integer, Control> active = new HashMap<>();

    private Control[] controls = new Control[0];

    private final TouchControlSettings settings;
    private OnMenuGestureListener menuGestureListener;
    private float edgeGesturePx;
    private boolean gestureCandidate;
    private float downEdgeX;
    private float downEdgeY;

    private boolean mini;
    private int lastMask = Integer.MIN_VALUE;
    private int lastStickX;
    private int lastStickY;

    private float stickHomeX;
    private float stickHomeY;
    private float stickRadius;
    private float stickX;
    private float stickY;
    private Control stickControl;
    /** The stick guide only makes sense while a finger owns the stick. */
    private boolean stickActive;

    // C-stick (right analog stick)
    private float cStickHomeX;
    private float cStickHomeY;
    private float cStickCenterX;
    private float cStickCenterY;
    private float cStickRadius;
    private float cStickX;
    private float cStickY;
    private Control cStickControl;
    private boolean cStickActive;

    public TouchControlsView(Context context, TouchControlSettings settings) {
        super(context);
        this.settings = settings;
        this.mini = settings.mini;
        this.edgeGesturePx = EDGE_GESTURE_DP * getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);

        fillPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3f);

        textPaint.setTextAlign(Paint.Align.CENTER);
        knobPaint.setStyle(Paint.Style.FILL);
    }

    public void setOnMenuGestureListener(OnMenuGestureListener listener) {
        menuGestureListener = listener;
    }

    /** Applies edited settings immediately, keeping the same pointer ownership. */
    public void applySettings(TouchControlSettings updated) {
        releaseAll();
        mini = updated.mini;
        layoutControls();
        invalidate();
    }

    /**
     * Positions the controls from the current view size.
     *
     * Layout follows the real GameCube pad: the C-stick zone floats on the left, the XYAB diamond
     * sits on the right, and the Z/L/R/Start shoulder row runs across the top. With stretch on
     * (the default) each cluster is anchored to the screen edges so the controls reach the corners
     * the thumbs naturally rest on instead of floating in the middle. Turning it off keeps the
     * earlier inset layout, which leaves more of the picture visible.
     */
    private void layoutControls() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // Sizes follow the shorter axis so the controls stay thumb-sized on any screen; scale is
        // the player's multiplier on top of that.
        float unit = Math.min(w, h);
        float scale = settings.scale * (mini ? MINI_SCALE : 1f);
        float buttonRadius = unit * 0.070f * scale;
        float shoulderRadius = unit * 0.044f * scale;

        float edgeX = w * settings.margin;
        float edgeY = h * settings.margin;
        float gap = buttonRadius * 1.85f;

        // Face buttons sit in the GameCube diamond: Y up, X left, A right, B down. A is the
        // rightmost and B the lowest, so anchoring those two fixes the whole cluster.
        float diamondX;
        float diamondY;
        if (settings.stretch) {
            diamondX = w - edgeX - gap - buttonRadius;
            diamondY = h - edgeY - gap - buttonRadius;
        } else {
            diamondX = w * 0.80f;
            diamondY = h * 0.62f;
        }

        float zX;
        float zY;
        float startX;
        float startY;
        float lX;
        float rX;
        if (settings.stretch) {
            zY = edgeY + shoulderRadius;
            startY = zY;
            lX = edgeX + shoulderRadius;
            rX = w - edgeX - shoulderRadius;
            // Kept clear of the diamond, which owns the bottom-right corner.
            zX = w * 0.58f;
            startX = w * 0.80f;
        } else {
            zX = w * 0.62f;
            zY = h * 0.14f;
            startX = w * 0.78f;
            startY = zY;
            lX = w * 0.09f;
            rX = w * 0.91f;
        }

        controls = new Control[] {
            new Control("Y", BTN_Y, diamondX, diamondY - gap, buttonRadius, COLOR_Y),
            new Control("X", BTN_X, diamondX - gap, diamondY, buttonRadius, COLOR_X),
            new Control("A", BTN_A, diamondX + gap, diamondY, buttonRadius, COLOR_A),
            new Control("B", BTN_B, diamondX, diamondY + gap, buttonRadius, COLOR_B),
            new Control("Z", BTN_Z, zX, zY, shoulderRadius, COLOR_SHOULDER),
            new Control("START", BTN_START, startX, startY, shoulderRadius * 1.35f, COLOR_SHOULDER),
            new Control("L", BTN_L, lX, shoulderRadius, shoulderRadius, COLOR_SHOULDER),
            new Control("R", BTN_R, rX, shoulderRadius, shoulderRadius, COLOR_SHOULDER),
        };

        // The main stick floats: its origin is wherever the finger lands inside the left zone.
        stickControl = new Control("", 0, 0, 0, 0);
        stickRadius = unit * 0.125f * scale;
        stickHomeX = 0f;
        stickHomeY = 0f;
        stickX = 0f;
        stickY = 0f;

        // C-stick (right analog stick): positioned ABOVE the face button diamond (like real GameCube)
        cStickControl = new Control("", 0, 0, 0, 0);
        cStickRadius = unit * 0.085f * scale; // Smaller than main stick
        cStickCenterX = diamondX;
        cStickCenterY = diamondY - gap * 2.5f; // Above the diamond (GameCube layout)
        cStickHomeX = 0f;
        cStickHomeY = 0f;
        cStickX = 0f;
        cStickY = 0f;
        // Yellow/mustard color for C-stick to differentiate from main stick
        final int COLOR_CSTICK = 0xFFE6B800; // Mustard yellow
    }

    private boolean insideStickZone(float x, float y) {
        return x < getWidth() * 0.45f && y > getHeight() * 0.30f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutControls();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (controls.length == 0) {
            layoutControls();
        }

        int action = event.getActionMasked();
        switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN: {
            int index = event.getActionIndex();
            int id = event.getPointerId(index);
            float x = event.getX(index);
            float y = event.getY(index);
            Control hit = hitTest(x, y);
            if (hit != null) {
                active.put(id, hit);
            }
            gestureCandidate = nearEdge(x, y);
            downEdgeX = x;
            downEdgeY = y;
            break;
        }
        case MotionEvent.ACTION_MOVE: {
            // The stick follows the finger wherever it moves; buttons are press-and-hold, so a
            // slide off a button does not release it.
            for (int i = 0; i < event.getPointerCount(); i++) {
                int id = event.getPointerId(i);
                if (active.get(id) == stickControl) {
                    updateStick(event.getX(i), event.getY(i));
                } else if (active.get(id) == cStickControl) {
                    updateCStick(event.getX(i), event.getY(i));
                }
            }

            if (gestureCandidate && !active.isEmpty()) {
                // A touch that claimed a control is a button press, never a menu swipe.
                gestureCandidate = false;
            } else if (gestureCandidate) {
                float travelled = Math.abs(event.getX() - downEdgeX) + Math.abs(event.getY() - downEdgeY);
                if (travelled > getWidth() * 0.06f) {
                    gestureCandidate = false;
                    if (menuGestureListener != null) {
                        menuGestureListener.onMenuGesture();
                    }
                }
            }
            break;
        }
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP: {
            int index = event.getActionIndex();
            Control released = active.remove(event.getPointerId(index));
            if (released == stickControl) {
                releaseStick();
            } else if (released == cStickControl) {
                releaseCStick();
            }
            gestureCandidate = false;
            break;
        }
        case MotionEvent.ACTION_CANCEL: {
            active.clear();
            gestureCandidate = false;
            releaseStick();
            releaseCStick();
            break;
        }
        default:
            break;
        }

        publish();
        return true;
    }

    private Control hitTest(float x, float y) {
        for (Control c : controls) {
            float dx = x - c.cx;
            float dy = y - c.cy;
            // A slightly generous target: fingers are imprecise on a glass screen.
            float hitRadius = c.radius * 1.35f;
            if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                return c;
            }
        }

        if (insideStickZone(x, y)) {
            stickHomeX = x;
            stickHomeY = y;
            stickActive = true;
            updateStick(x, y);
            return stickControl;
        }

        // C-stick zone: right side, below the face button diamond
        if (insideCStickZone(x, y)) {
            cStickHomeX = x;
            cStickHomeY = y;
            cStickActive = true;
            updateCStick(x, y);
            return cStickControl;
        }

        return null;
    }

    private boolean insideCStickZone(float x, float y) {
        // Right side, above the face button diamond area (where C-stick visually sits)
        float w = getWidth();
        float h = getHeight();
        // C-stick is at cStickCenterX (diamondX) and cStickCenterY (diamondY - gap * 2.5f)
        // diamondX is around w * 0.80, diamondY is around h * 0.62 (or anchored to edge)
        // So C-stick is roughly at x ~ 0.80w, y ~ 0.35-0.45h
        return x > w * 0.60f && y < h * 0.60f && y > h * 0.25f;
    }

    /**
     * True when a touch could begin a menu swipe.
     *
     * Only the top band qualifies. The floating stick claims most of the left side, so a swipe
     * from the lower edges would be indistinguishable from steering; up there the only targets are
     * the shoulder buttons, which the caller excludes by requiring a hit on no control at all.
     */
    private boolean nearEdge(float x, float y) {
        if (y > getHeight() * 0.28f) {
            return false;
        }
        return x <= edgeGesturePx || x >= getWidth() - edgeGesturePx;
    }

    private void updateStick(float x, float y) {
        float dx = x - stickHomeX;
        float dy = y - stickHomeY;
        float distance = (float) Math.hypot(dx, dy);

        if (distance < 1e-3f) {
            stickX = 0f;
            stickY = 0f;
            return;
        }

        float nx = dx / distance;
        float ny = dy / distance;
        float magnitude = Math.min(1f, distance / stickRadius);
        if (magnitude <= settings.deadzone) {
            stickX = 0f;
            stickY = 0f;
            return;
        }

        // Rescale past the deadzone so the usable range still reaches full deflection.
        float scaled = (magnitude - settings.deadzone) / (1f - settings.deadzone);
        stickX = nx * scaled;
        stickY = ny * scaled;
    }

    private void updateCStick(float x, float y) {
        float dx = x - cStickHomeX;
        float dy = y - cStickHomeY;
        float distance = (float) Math.hypot(dx, dy);

        if (distance < 1e-3f) {
            cStickX = 0f;
            cStickY = 0f;
            return;
        }

        float nx = dx / distance;
        float ny = dy / distance;
        float magnitude = Math.min(1f, distance / cStickRadius);
        if (magnitude <= settings.deadzone) {
            cStickX = 0f;
            cStickY = 0f;
            return;
        }

        // Rescale past the deadzone so the usable range still reaches full deflection.
        float scaled = (magnitude - settings.deadzone) / (1f - settings.deadzone);
        cStickX = nx * scaled;
        cStickY = ny * scaled;
    }

    private void releaseStick() {
        stickX = 0f;
        stickY = 0f;
        stickActive = false;
        invalidate();
    }

    private void releaseCStick() {
        cStickX = 0f;
        cStickY = 0f;
        cStickActive = false;
        invalidate();
    }

    /** Releases everything, e.g. when the window loses focus. */
    public void releaseAll() {
        active.clear();
        releaseStick();
        releaseCStick();
        publish();
    }

    /** Shrinks the controls instead of removing them, so one tap brings them back. */
    public void setMini(boolean value) {
        if (mini == value) {
            return;
        }
        mini = value;
        settings.mini = value;
        settings.save();
        releaseAll();
        layoutControls();
        invalidate();
    }

    public boolean isMini() {
        return mini;
    }

    private void publish() {
        int mask = 0;
        for (Control c : active.values()) {
            if (c != stickControl && c != cStickControl) {
                mask |= c.button;
            }
        }

        float magnitude = (float) Math.hypot(stickX, stickY);
        if (magnitude >= DPAD_SYNTHESIS) {
            float nx = stickX / magnitude;
            float ny = stickY / magnitude;
            if (nx > 0.38f) {
                mask |= BTN_RIGHT;
            }
            if (nx < -0.38f) {
                mask |= BTN_LEFT;
            }
            if (ny > 0.38f) {
                mask |= BTN_DOWN;
            }
            if (ny < -0.38f) {
                mask |= BTN_UP;
            }
        }

        // C-stick synthesis: map C-stick to C-buttons
        float cMagnitude = (float) Math.hypot(cStickX, cStickY);
        if (cMagnitude >= DPAD_SYNTHESIS) {
            float nx = cStickX / cMagnitude;
            float ny = cStickY / cMagnitude;
            if (nx > 0.38f) {
                mask |= BTN_C_RIGHT;
            }
            if (nx < -0.38f) {
                mask |= BTN_C_LEFT;
            }
            if (ny > 0.38f) {
                mask |= BTN_C_DOWN;
            }
            if (ny < -0.38f) {
                mask |= BTN_C_UP;
            }
        }

        // The GameCube stick points up for negative Y, which is what the game expects.
        int outX = Math.round(stickX * 127f);
        int outY = Math.round(-stickY * 127f);

        if (mask == lastMask && outX == lastStickX && outY == lastStickY) {
            return;
        }
        lastMask = mask;
        lastStickX = outX;
        lastStickY = outY;

        FoxhollowActivity.setVirtualPad(mask, outX, outY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (controls.length == 0) {
            return;
        }

        int alpha = mini ? MINI_ALPHA : settings.alpha;
        // Sticks use MAXIMUM opacity for best visibility
        int stickAlpha = 255;

        // Draw buttons FIRST (underneath)
        for (Control c : controls) {
            boolean pressed = active.containsValue(c);

            int base = c.color;
            fillPaint.setColor(pressed
                    ? Color.argb(Math.min(255, alpha + 70), Color.red(base), Color.green(base), Color.blue(base))
                    : Color.argb(alpha / 2, Color.red(base), Color.green(base), Color.blue(base)));
            canvas.drawCircle(c.cx, c.cy, c.radius, fillPaint);

            ringPaint.setColor(Color.argb(alpha + 40, Color.red(base), Color.green(base), Color.blue(base)));
            ringPaint.setStrokeWidth(mini ? 2f : 3f);
            canvas.drawCircle(c.cx, c.cy, c.radius, ringPaint);

            textPaint.setColor(pressed ? Color.argb(255, 0, 0, 0) : Color.argb(alpha + 70, 255, 255, 255));
            textPaint.setTextSize(c.radius * (c.label.length() > 1 ? 0.42f : 0.8f));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = c.cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(c.label, c.cx, baseline, textPaint);
        }

        // Main stick: ALWAYS draw guide ring (even when not active) so user knows where to touch
        // When active, draw at finger position; when inactive, draw at default left zone center
        float drawStickHomeX = stickActive ? stickHomeX : (getWidth() * 0.18f);
        float drawStickHomeY = stickActive ? stickHomeY : (getHeight() * 0.65f);
        
        // Always draw guide ring for main stick
        if (settings.stickRing) {
            ringPaint.setColor(Color.argb(mini ? 100 : 160, 255, 255, 255));
            ringPaint.setStrokeWidth(mini ? 3f : 4f);
            canvas.drawCircle(drawStickHomeX, drawStickHomeY, stickRadius, ringPaint);
        }
        
        // Draw knob only when active
        if (stickActive && stickControl != null) {
            float knobX = stickHomeX + stickX * stickRadius;
            float knobY = stickHomeY + stickY * stickRadius;
            knobPaint.setColor(Color.argb(stickAlpha, 255, 255, 255));
            canvas.drawCircle(knobX, knobY, stickRadius * 0.5f, knobPaint);
        }

        // C-stick: ALWAYS draw guide ring above face buttons
        float drawCStickHomeX = cStickActive ? cStickHomeX : cStickCenterX;
        float drawCStickHomeY = cStickActive ? cStickHomeY : cStickCenterY;
        
        // Always draw guide ring for C-stick (mustard yellow)
        if (settings.stickRing) {
            ringPaint.setColor(Color.argb(mini ? 140 : 200, 0xE6, 0xB8, 0x00)); // Mustard yellow
            ringPaint.setStrokeWidth(mini ? 3f : 4f);
            canvas.drawCircle(drawCStickHomeX, drawCStickHomeY, cStickRadius, ringPaint);
        }
        
        // Draw C-stick knob only when active (mustard yellow)
        if (cStickActive && cStickControl != null) {
            float knobX = cStickHomeX + cStickX * cStickRadius;
            float knobY = cStickHomeY + cStickY * cStickRadius;
            knobPaint.setColor(Color.argb(stickAlpha, 0xE6, 0xB8, 0x00)); // Mustard yellow
            canvas.drawCircle(knobX, knobY, cStickRadius * 0.5f, knobPaint);
        }
    }
}

