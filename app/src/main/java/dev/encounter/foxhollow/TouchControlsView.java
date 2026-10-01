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

    /** Past this the matching d-pad bit is synthesised for code that only reads the d-pad. */
    private static final float DPAD_SYNTHESIS = 0.55f;

    private static final float MINI_SCALE = 0.62f;
    private static final int MINI_ALPHA = 96;

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

        Control(String label, int button, float cx, float cy, float radius) {
            this.label = label;
            this.button = button;
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
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
     * With stretch on (the default) each cluster is anchored to the screen edges, so the controls
     * reach the corners the thumbs naturally rest on instead of floating in the middle. Turning
     * it off keeps the earlier inset layout, which leaves more of the picture visible.
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
            new Control("Y", BTN_Y, diamondX, diamondY - gap, buttonRadius),
            new Control("X", BTN_X, diamondX - gap, diamondY, buttonRadius),
            new Control("A", BTN_A, diamondX + gap, diamondY, buttonRadius),
            new Control("B", BTN_B, diamondX, diamondY + gap, buttonRadius),
            new Control("Z", BTN_Z, zX, zY, shoulderRadius),
            new Control("START", BTN_START, startX, startY, shoulderRadius * 1.35f),
            new Control("L", BTN_L, lX, shoulderRadius, shoulderRadius),
            new Control("R", BTN_R, rX, shoulderRadius, shoulderRadius),
        };

        // The stick floats: its origin is wherever the finger lands inside the left zone.
        stickControl = new Control("", 0, 0, 0, 0);
        stickRadius = unit * 0.125f * scale;
        stickHomeX = 0f;
        stickHomeY = 0f;
        stickX = 0f;
        stickY = 0f;
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
            }
            gestureCandidate = false;
            break;
        }
        case MotionEvent.ACTION_CANCEL: {
            active.clear();
            gestureCandidate = false;
            releaseStick();
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

        return null;
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

    private void releaseStick() {
        stickX = 0f;
        stickY = 0f;
        stickActive = false;
        invalidate();
    }

    /** Releases everything, e.g. when the window loses focus. */
    public void releaseAll() {
        active.clear();
        releaseStick();
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
            if (c != stickControl) {
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

        // Stick: a faint ring at the origin plus the deflected knob.
        if (stickActive && stickControl != null) {
            if (settings.stickRing) {
                ringPaint.setColor(Color.argb(mini ? 60 : 90, 255, 255, 255));
                ringPaint.setStrokeWidth(mini ? 2f : 3f);
                canvas.drawCircle(stickHomeX, stickHomeY, stickRadius, ringPaint);
            }

            float knobX = stickHomeX + stickX * stickRadius;
            float knobY = stickHomeY + stickY * stickRadius;
            knobPaint.setColor(Color.argb(alpha, 255, 255, 255));
            canvas.drawCircle(knobX, knobY, stickRadius * 0.42f, knobPaint);
        }

        for (Control c : controls) {
            boolean pressed = active.containsValue(c);

            fillPaint.setColor(pressed
                    ? Color.argb(Math.min(255, alpha + 70), 255, 255, 255)
                    : Color.argb(alpha / 2, 255, 255, 255));
            canvas.drawCircle(c.cx, c.cy, c.radius, fillPaint);

            ringPaint.setColor(Color.argb(alpha + 40, 255, 255, 255));
            ringPaint.setStrokeWidth(mini ? 2f : 3f);
            canvas.drawCircle(c.cx, c.cy, c.radius, ringPaint);

            textPaint.setColor(pressed ? Color.BLACK : Color.argb(alpha + 70, 255, 255, 255));
            textPaint.setTextSize(c.radius * (c.label.length() > 1 ? 0.42f : 0.8f));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = c.cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(c.label, c.cx, baseline, textPaint);
        }
    }
}