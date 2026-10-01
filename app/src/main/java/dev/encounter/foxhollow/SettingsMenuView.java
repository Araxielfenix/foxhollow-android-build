package dev.encounter.foxhollow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings menu, opened with the Android back button or by swiping in from a screen edge.
 *
 * Drawn by hand rather than from widgets so it can sit above the game surface without pulling in a
 * theme that would fight the fullscreen layout. Every row writes straight to
 * {@link TouchControlSettings}, which persists and re-lays out the controls as it goes, so the
 * effect of a change is visible immediately behind the menu.
 */
public class SettingsMenuView extends View {
    private enum Kind {
        HEADER, SLIDER, TOGGLE, CHOICE, BUTTON
    }

    /** One line of the menu. Sliders and toggles hold a live reference into the settings. */
    private static final class Row {
        final Kind kind;
        final String label;
        final String value;
        final float min;
        final float max;
        final boolean integer;
        final float[] number;
        final boolean[] flag;
        final Runnable action;
        final String[] choices;
        final int[] choiceIndex;

        private Row(Kind kind, String label, String value, float min, float max, boolean integer,
                    float[] number, boolean[] flag, Runnable action) {
            this(kind, label, value, min, max, integer, number, flag, action, null, null);
        }

        private Row(Kind kind, String label, String value, float min, float max, boolean integer,
                    float[] number, boolean[] flag, Runnable action, String[] choices, int[] choiceIndex) {
            this.kind = kind;
            this.label = label;
            this.value = value;
            this.min = min;
            this.max = max;
            this.integer = integer;
            this.number = number;
            this.flag = flag;
            this.action = action;
            this.choices = choices;
            this.choiceIndex = choiceIndex;
        }

        static Row header(String label) {
            return new Row(Kind.HEADER, label, null, 0f, 0f, false, null, null, null);
        }

        static Row slider(String label, float min, float max, boolean integer, float[] target, String value) {
            return new Row(Kind.SLIDER, label, value, min, max, integer, target, null, null);
        }

        static Row toggle(String label, boolean[] target) {
            return new Row(Kind.TOGGLE, label, null, 0f, 0f, false, null, target, null);
        }

        /** A row that steps through a fixed list of options on each tap. */
        static Row choice(String label, int[] index, String[] names) {
            return new Row(Kind.CHOICE, label, null, 0f, names.length - 1f, true, null, null, null,
                    names, index);
        }

        static Row button(String label, Runnable action) {
            return new Row(Kind.BUTTON, label, null, 0f, 0f, false, null, null, action);
        }
    }

    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final TouchControlSettings settings;
    private final TouchControlsView controls;
    private final SettingsHost host;
    private final int touchSlop;

    private final List<Row> rows = new ArrayList<>();
    private final List<RectF> rowBounds = new ArrayList<>();

    // Held directly so commit() does not have to rely on the order of the list above.
    private Row scaleRow;
    private Row alphaRow;
    private Row marginRow;
    private Row deadzoneRow;
    private Row stretchRow;
    private Row stickRingRow;
    private Row miniRow;
    private Row renderScaleRow;
    private Row showFpsRow;
    private Row displayModeRow;

    private float rowHeight;
    private float headerHeight;
    private float pad;
    private RectF panelBounds;
    private RectF listBounds;

    private float scroll;
    private float maxScroll;
    private float downX;
    private float downY;
    private int pressedRow = -1;
    private boolean dragging;

    /** What the menu needs from the activity, kept narrow so the view stays testable. */
    public interface SettingsHost {
        void onSettingsChanged(TouchControlSettings settings);

        void onExitRequested();
    }

    public SettingsMenuView(Context context, TouchControlSettings settings, TouchControlsView controls,
                            SettingsHost host) {
        super(context);
        this.settings = settings;
        this.controls = controls;
        this.host = host;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        panelPaint.setStyle(Paint.Style.FILL);
        fillPaint.setStyle(Paint.Style.FILL);
        trackPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        borderPaint.setColor(Color.argb(70, 235, 240, 250));
        textPaint.setTextAlign(Paint.Align.LEFT);
        valuePaint.setTextAlign(Paint.Align.RIGHT);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }

        rowHeight = Math.min(112f, Math.max(56f, h * 0.078f));
        headerHeight = rowHeight * 0.78f;
        pad = rowHeight * 0.45f;

        buildRows();
        layoutPanel(w, h);
    }

    /**
     * Rebuilds the rows so their live targets point back at the settings.
     *
     * Held separately from layout because a reset has to re-read the values, and the arrays are
     * captured by reference at construction time.
     */
    private void buildRows() {
        rows.clear();

        rows.add(Row.header("TOUCH"));
        scaleRow = Row.slider("SIZE", TouchControlSettings.SCALE_MIN, TouchControlSettings.SCALE_MAX,
                false, new float[] { settings.scale }, "x");
        alphaRow = Row.slider("OPACITY", 40f, 255f, true, new float[] { settings.alpha }, "");
        marginRow = Row.slider("EDGE GAP", TouchControlSettings.MARGIN_MIN, TouchControlSettings.MARGIN_MAX,
                false, new float[] { settings.margin }, "");
        deadzoneRow = Row.slider("DEADZONE", TouchControlSettings.DEADZONE_MIN,
                TouchControlSettings.DEADZONE_MAX, false, new float[] { settings.deadzone }, "");
        stretchRow = Row.toggle("STRETCH TO EDGES", new boolean[] { settings.stretch });
        stickRingRow = Row.toggle("STICK GUIDE", new boolean[] { settings.stickRing });
        miniRow = Row.toggle("COMPACT", new boolean[] { settings.mini });

        rows.add(scaleRow);
        rows.add(alphaRow);
        rows.add(marginRow);
        rows.add(deadzoneRow);
        rows.add(stretchRow);
        rows.add(stickRingRow);
        rows.add(miniRow);

        rows.add(Row.header("DISPLAY"));
        displayModeRow = Row.choice("PICTURE", new int[] { settings.displayMode },
                TouchControlSettings.DISPLAY_MODE_NAMES);
        renderScaleRow = Row.slider("RENDER SCALE", TouchControlSettings.RENDER_SCALE_MIN,
                TouchControlSettings.RENDER_SCALE_MAX, false, new float[] { settings.renderScale }, "x");
        showFpsRow = Row.toggle("FPS COUNTER", new boolean[] { settings.showFps });

        rows.add(displayModeRow);
        rows.add(renderScaleRow);
        rows.add(showFpsRow);

        rows.add(Row.button("RESET TO DEFAULTS", new Runnable() {
            @Override
            public void run() {
                settings.reset();
                buildRows();
                layoutRows();
                host.onSettingsChanged(settings);
                invalidate();
            }
        }));
        rows.add(Row.button("EXIT GAME", new Runnable() {
            @Override
            public void run() {
                host.onExitRequested();
            }
        }));
    }

    private void layoutPanel(int w, int h) {
        float total = 0f;
        for (Row r : rows) {
            total += r.kind == Kind.HEADER ? headerHeight : rowHeight;
        }

        // Wide enough to read comfortably: the old cap pinned it near a fifth of the screen on a
        // landscape phone, which made the menu feel like a tooltip.
        float listWidth = Math.min(w * 0.80f, rowHeight * 10f);
        float panelHeight = Math.min(h * 0.86f, total);
        panelBounds = new RectF((w - listWidth) / 2f, (h - panelHeight) / 2f,
                (w + listWidth) / 2f, (h + panelHeight) / 2f);

        listBounds = new RectF(panelBounds.left, panelBounds.top + headerHeight,
                panelBounds.right, panelBounds.bottom);

        maxScroll = Math.max(0f, total - listBounds.height());
        scroll = Math.max(0f, Math.min(maxScroll, scroll));

        layoutRows();
    }

    private void layoutRows() {
        rowBounds.clear();
        float y = listBounds.top - scroll;
        for (Row r : rows) {
            float height = r.kind == Kind.HEADER ? headerHeight : rowHeight;
            rowBounds.add(new RectF(listBounds.left, y, listBounds.right, y + height));
            y += height;
        }
    }

    /** Called when the panel is reused after the screen resized or the settings were replaced. */
    public void refresh() {
        buildRows();
        onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (panelBounds == null) {
            return true;
        }

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: {
            downX = x;
            downY = y;
            dragging = false;
            pressedRow = -1;

            if (!panelBounds.contains(x, y)) {
                dismiss();
                return true;
            }
            if (y < listBounds.top) {
                dismiss();
                return true;
            }
            if (maxScroll > 0f) {
                pressedRow = rowAt(y);
            }
            return true;
        }

        case MotionEvent.ACTION_MOVE: {
            float dy = y - downY;
            if (!dragging && Math.abs(dy) > touchSlop) {
                dragging = true;
            }
            if (dragging) {
                scroll = clampScroll(scroll - dy);
                downY = y;
                layoutRows();
                invalidate();
            }
            return true;
        }

        case MotionEvent.ACTION_UP: {
            if (!dragging) {
                activate(x, y);
            }
            pressedRow = -1;
            dragging = false;
            return true;
        }

        case MotionEvent.ACTION_CANCEL: {
            pressedRow = -1;
            dragging = false;
            return true;
        }

        default:
            return true;
        }
    }

    private float clampScroll(float value) {
        return Math.max(0f, Math.min(maxScroll, value));
    }

    /**
     * Finds the row under a vertical position.
     *
     * Only the vertical range is tested: rows always span the full panel width, and the caller has
     * already established that the touch is inside the panel.
     */
    private int rowAt(float y) {
        for (int i = 0; i < rowBounds.size(); i++) {
            RectF bounds = rowBounds.get(i);
            if (y >= bounds.top && y <= bounds.bottom) {
                return i;
            }
        }
        return -1;
    }

    private void activate(float x, float y) {
        int index = rowAt(y);
        if (index < 0 || index >= rows.size()) {
            return;
        }

        Row row = rows.get(index);
        RectF bounds = rowBounds.get(index);

        switch (row.kind) {
        case SLIDER: {
            float t = (x - (bounds.left + pad)) / Math.max(1f, bounds.width() - pad * 2f);
            float value = row.min + (row.max - row.min) * Math.max(0f, Math.min(1f, t));
            row.number[0] = row.integer ? Math.round(value) : value;
            commit();
            break;
        }
        case TOGGLE: {
            row.flag[0] = !row.flag[0];
            commit();
            break;
        }
        case CHOICE: {
            row.choiceIndex[0] = (int) ((row.choiceIndex[0] + 1) % row.choices.length);
            commit();
            break;
        }
        case BUTTON:
            row.action.run();
            break;
        default:
            break;
        }
    }

    /** Pushes every row's live value back into the settings object. */
    private void commit() {
        settings.scale = scaleRow.number[0];
        settings.alpha = (int) alphaRow.number[0];
        settings.margin = marginRow.number[0];
        settings.deadzone = deadzoneRow.number[0];
        settings.stretch = stretchRow.flag[0];
        settings.stickRing = stickRingRow.flag[0];
        settings.mini = miniRow.flag[0];
        settings.renderScale = renderScaleRow.number[0];
        settings.showFps = showFpsRow.flag[0];
        settings.displayMode = displayModeRow.choiceIndex[0];
        settings.save();

        controls.applySettings(settings);
        host.onSettingsChanged(settings);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (panelBounds == null) {
            return;
        }

        panelPaint.setColor(Color.argb(238, 18, 20, 26));
        canvas.drawRoundRect(panelBounds, pad * 0.7f, pad * 0.7f, panelPaint);
        canvas.drawRoundRect(panelBounds, pad * 0.7f, pad * 0.7f, borderPaint);

        float titleSize = rowHeight * 0.30f;
        textPaint.setTextSize(titleSize);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float titleBaseline = panelBounds.top + headerHeight * 0.5f - (fm.ascent + fm.descent) / 2f;
        textPaint.setColor(Color.argb(215, 235, 240, 250));
        canvas.drawText("SETTINGS", panelBounds.left + pad, titleBaseline, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(Color.argb(150, 235, 240, 250));
        canvas.drawText("CLOSE", panelBounds.right - pad, titleBaseline, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);

        // Clip so rows scrolled past the panel edge do not paint over the game.
        canvas.save();
        canvas.clipRect(listBounds);
        textPaint.setTextSize(rowHeight * 0.26f);
        valuePaint.setTextSize(rowHeight * 0.24f);

        for (int i = 0; i < rows.size(); i++) {
            RectF bounds = rowBounds.get(i);
            if (bounds.bottom < listBounds.top || bounds.top > listBounds.bottom) {
                continue;
            }
            drawRow(canvas, rows.get(i), bounds, i == pressedRow);
        }
        canvas.restore();
    }

    private void drawRow(Canvas canvas, Row row, RectF bounds, boolean pressed) {
        switch (row.kind) {
        case HEADER: {
            float size = rowHeight * 0.22f;
            textPaint.setTextSize(size);
            textPaint.setColor(Color.argb(160, 140, 200, 255));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = bounds.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(row.label, bounds.left + pad * 0.6f, baseline, textPaint);
            break;
        }

        case SLIDER: {
            float value = row.number[0];
            float t = Math.max(0f, Math.min(1f, (value - row.min) / (row.max - row.min)));

            textPaint.setColor(Color.argb(200, 235, 240, 250));
            canvas.drawText(row.label, bounds.left + pad, bounds.top + rowHeight * 0.34f, textPaint);

            valuePaint.setColor(Color.argb(235, 255, 255, 255));
            String text = row.integer ? String.valueOf((int) value)
                    : String.format(Locale.US, "%.2f%s", value, row.value);
            canvas.drawText(text, bounds.right - pad, bounds.top + rowHeight * 0.34f, valuePaint);

            float trackY = bounds.top + rowHeight * 0.68f;
            float left = bounds.left + pad;
            float right = bounds.right - pad;

            trackPaint.setColor(Color.argb(70, 235, 240, 250));
            canvas.drawRoundRect(new RectF(left, trackY - 4f, right, trackY + 4f), 4f, 4f, trackPaint);
            trackPaint.setColor(pressed ? Color.argb(230, 150, 205, 255) : Color.argb(200, 120, 190, 255));
            canvas.drawRoundRect(new RectF(left, trackY - 4f, left + (right - left) * t, trackY + 4f),
                    4f, 4f, trackPaint);

            fillPaint.setColor(Color.argb(255, 235, 240, 250));
            canvas.drawCircle(left + (right - left) * t, trackY, rowHeight * 0.10f, fillPaint);
            break;
        }

        case TOGGLE: {
            textPaint.setColor(Color.argb(200, 235, 240, 250));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(row.label, bounds.left + pad,
                    bounds.centerY() - (fm.ascent + fm.descent) / 2f, textPaint);

            float half = Math.min(rowHeight * 0.26f, bounds.height() * 0.3f);
            float cy = bounds.centerY();
            float right = bounds.right - pad;
            float left = right - half * 2f;

            trackPaint.setColor(row.flag[0] ? Color.argb(210, 120, 190, 255) : Color.argb(80, 235, 240, 250));
            canvas.drawRoundRect(new RectF(left, cy - half, right, cy + half), half, half, trackPaint);
            fillPaint.setColor(Color.argb(255, 240, 245, 255));
            canvas.drawCircle(row.flag[0] ? right - half : left + half, cy, half * 0.7f, fillPaint);
            break;
        }

        case CHOICE: {
            textPaint.setColor(Color.argb(200, 235, 240, 250));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(row.label, bounds.left + pad,
                    bounds.centerY() - (fm.ascent + fm.descent) / 2f, textPaint);

            valuePaint.setColor(Color.argb(235, 150, 205, 255));
            String name = row.choices[Math.max(0, Math.min(row.choices.length - 1, row.choiceIndex[0]))];
            canvas.drawText(name, bounds.right - pad,
                    bounds.centerY() - (fm.ascent + fm.descent) / 2f, valuePaint);
            break;
        }

        case BUTTON: {
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(row.label.startsWith("EXIT")
                    ? Color.argb(225, 250, 150, 140)
                    : Color.argb(225, 255, 200, 130));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(row.label, bounds.centerX(),
                    bounds.centerY() - (fm.ascent + fm.descent) / 2f, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            break;
        }

        default:
            break;
        }
    }

    /** Hides the menu and gives the touch back to the game. */
    private void dismiss() {
        setVisibility(GONE);
    }
}