package dev.encounter.foxhollow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/**
 * Frames-per-second readout drawn over the game.
 *
 * The number comes from Aurora's present timestamps rather than from this view's own redraws: the
 * UI thread only ticks at the display refresh rate, so counting here would report the screen's
 * rate instead of the game's. The value is therefore sampled on a timer instead of every frame.
 */
public class FpsOverlayView extends View {
    private static final long REFRESH_MS = 500;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String label = "-- FPS";
    private int labelWidth;
    private int labelHeight;
    private float pad;
    private float density;

    public FpsOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;

        textPaint.setColor(Color.argb(235, 235, 245, 255));
        textPaint.setTextSize(13f * density);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        backPaint.setColor(Color.argb(140, 0, 0, 0));

        measureLabel();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(update);
        super.onDetachedFromWindow();
    }

    private void measureLabel() {
        pad = 4f * density;
        labelWidth = (int) Math.ceil(textPaint.measureText(label));
        labelHeight = (int) Math.ceil(textPaint.descent() - textPaint.ascent());
    }

    private final Runnable update = new Runnable() {
        @Override
        public void run() {
            // Re-check on every tick: the overlay may have been hidden while this was pending.
            if (getVisibility() != VISIBLE || !isShown()) {
                return;
            }

            float fps = FoxhollowActivity.getFps();

            // Green above 50, amber to 30, red below: the usual way to read a frame rate at a
            // glance without stopping the game.
            int colour;
            if (fps >= 50f) {
                colour = Color.argb(235, 130, 235, 150);
            } else if (fps >= 30f) {
                colour = Color.argb(235, 250, 215, 120);
            } else {
                colour = Color.argb(235, 250, 130, 120);
            }
            textPaint.setColor(colour);

            label = String.format(Locale.US, "%2.0f FPS", fps);
            measureLabel();
            invalidate();

            postDelayed(this, REFRESH_MS);
        }
    };

    private void scheduleUpdate() {
        removeCallbacks(update);
        postDelayed(update, REFRESH_MS);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) {
            scheduleUpdate();
        } else {
            removeCallbacks(update);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (labelWidth == 0) {
            return;
        }

        canvas.drawRoundRect(0, 0, labelWidth + pad * 2f, labelHeight + pad * 2f, pad, pad, backPaint);
        canvas.drawText(label, pad, pad - textPaint.ascent(), textPaint);
    }
}