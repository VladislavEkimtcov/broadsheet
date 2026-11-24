package io.github.vladislavekimtcov.broadsheet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The options panel: slides in from the right with Menu, leaves with Menu or Back. A column of
 * tiles, modelled on dumbtv's quick settings: Up/Down picks a tile, Left/Right changes its value,
 * OK does it. The panel keeps the selection itself, so focus on the page behind (the story the
 * tiles act on) stays where it was. Nothing in it moves at rest.
 *
 * <p>Touch ({@link Ui#touch}): a sheet that rises from the bottom (☰ in the top bar). A tap on
 * a tile does it, the ‹ › on a tile change its value, the text size slider follows the finger,
 * and a tap on the shade above closes it.
 */
final class Options extends FrameLayout {  // feature: opt.panel
    interface Listener {
        void onOptionsClosed();
    }

    /** One row. Main fills it in and calls {@link #refresh} when something changes. */
    static final class Tile {
        String icon, label, value, hint;
        /** An {@link Icons} drawing instead of the {@code icon} glyph, and one per segment. */
        int vectorIcon;
        int[] segmentIcons;
        /** A slider of this many steps under the label (Left/Right moves it), at {@code position}. */
        int slider, position;
        boolean enabled = true;
        /** Two or more choices drawn as pills under the label (Like / Dislike). */
        String[] segments;
        boolean[] checked;
        int segment;
        IntConsumer onStep;
        Runnable onOk;

        Tile(String icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private static final int WIDTH_DP = 410;

    private final Listener listener;
    private final View scrim;
    private final LinearLayout panel, column;
    private final LinearLayout.LayoutParams scrollParams;
    private final ScrollView scroll;
    private final TextView heading, context, footer;
    private final List<Tile> tiles = new ArrayList<>();
    private final List<TileView> views = new ArrayList<>();
    private int selected;
    private boolean open, touched;

    Options(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setVisibility(GONE);
        scrim = new View(c);
        scrim.setBackgroundColor(Ui.light ? 0x4D000000 : 0x73000000);  // light enough to judge the text size
        addView(scrim, new LayoutParams(-1, -1));
        if (Ui.touch) scrim.setOnClickListener(v -> close());

        panel = Ui.touch ? new Sheet(c) : new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(22), Ui.dp(26), Ui.dp(22), Ui.dp(18));
        panel.setBackground(new Edge());
        heading = Ui.text(c, 26, Ui.INK, Ui.display);
        heading.setText("Options");
        panel.addView(heading);
        context = Ui.text(c, 13, Ui.FAINT, Ui.sans);
        context.setMaxLines(2);
        context.setEllipsize(TextUtils.TruncateAt.END);
        context.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = Ui.dp(6);
        cl.bottomMargin = Ui.dp(14);
        panel.addView(context, cl);
        scroll = new ScrollView(c);
        scroll.setFocusable(false);
        // The relay address field on touch takes typing; on the TV the panel keeps the keys.
        scroll.setDescendantFocusability(Ui.touch ? FOCUS_AFTER_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(18));
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ViewGroup.LayoutParams(-1, -2));
        scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        panel.addView(scroll, scrollParams);
        footer = Ui.text(c, 12, Ui.FAINT, Ui.sans);
        footer.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = Ui.dp(12);
        panel.addView(footer, fl);
        if (Ui.touch) {
            panel.setClickable(true);  // taps on the sheet don't reach the shade
            addView(panel, new LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            applyInsets();
        } else {
            addView(panel, new LayoutParams(Ui.dp(WIDTH_DP), -1, Gravity.END));
        }
    }

    /** Touch: clear of the gesture bar, at most 600 dp wide (landscape, tablets). */
    void applyInsets() {
        if (!Ui.touch) return;
        panel.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14) + Ui.insetBottom);
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        int w = getResources().getDisplayMetrics().widthPixels - Ui.insetLeft - Ui.insetRight;
        lp.width = Math.min(w, Ui.dp(600));
        panel.setLayoutParams(lp);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (Ui.touch && w != ow) post(this::applyInsets);
    }

    /** Touch: an extra row under the tiles (the relay address). */
    void addField(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(4);
        column.addView(v, lp);
    }

    /** The sheet's height: what it holds, at most 85% of the screen; the tiles scroll inside. */
    private final class Sheet extends LinearLayout {
        Sheet(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int max = Math.round((MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED
                    ? getResources().getDisplayMetrics().heightPixels : MeasureSpec.getSize(hSpec)) * 0.85f);
            scrollParams.height = -2;
            scrollParams.weight = 0;
            super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int want = Math.min(max, getMeasuredHeight());
            scrollParams.height = 0;
            scrollParams.weight = 1;
            super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(want, MeasureSpec.EXACTLY));
        }
    }

    boolean isOpen() {
        return open;
    }

    /** Opens with these tiles; selects the first one that can do something. {@code slide}: animate
     *  in (not when it is only being built again in new colours). */
    void open(List<Tile> list, String about, boolean slide) {
        tiles.clear();
        tiles.addAll(list);
        views.clear();
        column.removeAllViews();
        for (int i = 0; i < tiles.size(); i++) {
            TileView v = new TileView(getContext(), tiles.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) lp.topMargin = Ui.dp(4);
            column.addView(v, lp);
            views.add(v);
        }
        selected = 0;
        touched = false;
        while (selected < tiles.size() - 1 && !tiles.get(selected).enabled) selected++;
        if (Ui.touch) selected = -1;  // no selection to move with a finger; a press lights a tile
        context.setText(about);
        context.setVisibility(about == null || about.isEmpty() ? GONE : VISIBLE);
        footer.setText("");
        refresh();
        scroll.scrollTo(0, 0);
        if (!open) {
            open = true;
            setVisibility(VISIBLE);
            bringToFront();
            if (slide && Ui.touch) {
                panel.setTranslationY(getResources().getDisplayMetrics().heightPixels);
                scrim.setAlpha(0f);
                panel.animate().translationY(0).setDuration(200).start();
                scrim.animate().alpha(1f).setDuration(200).start();
            } else if (slide) {
                panel.setTranslationX(Ui.dp(WIDTH_DP));
                scrim.setAlpha(0f);
                panel.animate().translationX(0).setDuration(170).start();
                scrim.animate().alpha(1f).setDuration(170).start();
            }
        }
        post(this::reveal);
    }

    /** Reopens on the same tile (after the page behind was rebuilt). */
    void select(int index) {
        if (Ui.touch) return;
        selected = Math.max(0, Math.min(tiles.size() - 1, index));
        refresh();
        post(this::reveal);
    }

    int selectedIndex() {
        return selected;
    }

    /** A tile that arrived late (Handoff) goes on top; it takes the selection if nothing was pressed yet. */
    void addTop(Tile t) {
        tiles.add(0, t);
        TileView v = new TileView(getContext(), t);
        if (!views.isEmpty()) ((LinearLayout.LayoutParams) views.get(0).getLayoutParams()).topMargin = Ui.dp(4);
        views.add(0, v);
        column.addView(v, 0, new LinearLayout.LayoutParams(-1, -2));
        selected = Ui.touch ? -1 : touched ? selected + 1 : 0;
        refresh();
        post(this::reveal);
    }

    void close() {
        if (!open) return;
        open = false;
        if (Ui.touch) {
            View f = findFocus();
            if (f != null) {
                ((android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(f.getWindowToken(), 0);
                f.clearFocus();
            }
            panel.animate().translationY(panel.getHeight() + Ui.dp(40)).setDuration(170).start();
        } else {
            panel.animate().translationX(Ui.dp(WIDTH_DP)).setDuration(150).start();
        }
        scrim.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (!open) setVisibility(GONE);
        }).start();
        listener.onOptionsClosed();
    }

    void setFooter(String text) {  // feature: opt.status_footer
        footer.setText(text);
    }

    /** Redraws the tiles after their state changed. */
    void refresh() {
        for (int i = 0; i < views.size(); i++) views.get(i).bind(i == selected);
    }

    private void reveal() {
        if (selected < 0 || selected >= views.size()) return;
        View v = views.get(selected);
        int top = v.getTop() - Ui.dp(24), bottom = v.getBottom() + Ui.dp(24);
        int y = scroll.getScrollY(), h = scroll.getHeight();
        if (top < y) scroll.smoothScrollTo(0, Math.max(0, top));
        else if (bottom > y + h) scroll.smoothScrollTo(0, bottom - h);
    }

    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        touched = true;
        Tile t = selected < tiles.size() ? tiles.get(selected) : null;
        switch (k) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (e.getRepeatCount() == 0) close();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (selected > 0) selected--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (selected < tiles.size() - 1) selected++;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int d = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                if (t == null || !t.enabled) break;
                if (t.segments != null) t.segment = Math.max(0, Math.min(t.segments.length - 1, t.segment + d));
                else if (t.onStep != null) t.onStep.accept(d);
                break;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (t != null && t.enabled && t.onOk != null && e.getRepeatCount() == 0) t.onOk.run();
                break;
            default:
                return true;
        }
        refresh();
        reveal();
        return true;
    }

    /** A tile, drawn by hand: icon, label and value on top, a hint or the choices below. */
    private final class TileView extends View {
        private final Tile tile;
        private final TextPaint icon = Ui.paint(19, Ui.INK, Ui.sans);
        private final TextPaint label = Ui.paint(17, Ui.INK, Ui.sansMedium);
        private final TextPaint value = Ui.paint(16, Ui.INK, Ui.sansMedium);
        private final TextPaint hint = Ui.paint(13, Ui.MUTED, Ui.sans);
        private final TextPaint pill = Ui.paint(14, Ui.INK, Ui.sansMedium);
        private final TextPaint small = Ui.paint(13, Ui.INK, Ui.text);
        private final TextPaint big = Ui.paint(25, Ui.INK, Ui.text);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;
        /** Touch: where the slider's track and the ‹ › buttons were drawn, and the press. */
        private float trackLeft, trackRight, stepLeft = -1, stepRight = -1, segLeft, segRight;
        private float downX, downY;
        private boolean pressing, sliding;

        TileView(Context c, Tile tile) {
            super(c);
            this.tile = tile;
        }

        /** Touch: a tap does the tile; ‹ › step its value; the slider follows the finger. */
        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {  // feature: phone.sheet
            if (!Ui.touch) return super.onTouchEvent(e);
            float x = e.getX();
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = x;
                    downY = e.getY();
                    pressing = true;
                    sliding = false;
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = Math.abs(x - downX), dy = Math.abs(e.getY() - downY);
                    // The slider takes a sideways drag; an upward one scrolls the sheet.
                    if (tile.slider > 0 && tile.enabled && !sliding && dx > Ui.dp(8) && dx > dy) {
                        sliding = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (sliding) slideTo(x);
                    else if (dx > Ui.dp(10) || dy > Ui.dp(10)) {
                        pressing = false;
                        invalidate();
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                    if (pressing && tile.enabled && tile.slider > 0 && !sliding) slideTo(x);  // a tap on the track
                    else if (pressing && tile.enabled && tile.slider == 0) tap(x);
                    pressing = false;
                    sliding = false;
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    pressing = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        private void slideTo(float x) {
            if (tile.onStep == null || trackRight <= trackLeft) return;
            float step = (trackRight - trackLeft) / Math.max(1, tile.slider - 1);
            int want = Math.max(0, Math.min(tile.slider - 1, Math.round((x - trackLeft) / step)));
            if (want != tile.position) tile.onStep.accept(want - tile.position);
            refresh();
        }

        private void tap(float x) {
            if (tile.segments != null) {
                float span = Math.max(1, segRight - segLeft);
                int i = (int) Math.max(0, Math.min(tile.segments.length - 1, (x - segLeft) / span * tile.segments.length));
                tile.segment = i;
                if (tile.onOk != null) tile.onOk.run();
            } else if (tile.onStep != null && stepLeft >= 0 && x >= stepLeft - Ui.px(6) && x < stepLeft + Ui.px(40)) {
                tile.onStep.accept(-1);
            } else if (tile.onStep != null && stepRight >= 0 && x >= stepRight - Ui.px(6)) {
                tile.onStep.accept(1);
            } else if (tile.onOk != null) {
                tile.onOk.run();
            } else if (tile.onStep != null) {
                tile.onStep.accept(1);
            }
            refresh();
        }

        void bind(boolean selected) {
            on = selected;
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            boolean second = tile.segments != null || (tile.hint != null && !tile.hint.isEmpty());
            boolean hinted = tile.hint != null && !tile.hint.isEmpty();
            int h = tile.slider > 0 ? Ui.dp(84) : tile.segments != null ? Ui.dp(hinted ? 112 : 92) : Ui.dp(second ? 68 : Ui.touch ? 56 : 50);
            setMeasuredDimension(MeasureSpec.getSize(wSpec), h);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            int fg = Ui.INK;
            float a = tile.enabled ? 1f : 0.42f;
            // Like the rail: flat rows, the selected one lit with the accent bar at its edge.
            if (on || (Ui.touch && pressing && tile.slider == 0)) {
                fill.setColor(Ui.SURFACE_HI);
                c.drawRect(0, 0, w, h, fill);
                fill.setColor(Ui.ACCENT);
                c.drawRect(0, 0, Ui.px(3), h, fill);
            } else {
                fill.setColor(Ui.alpha(Ui.INK, 0.045f));
                c.drawRect(0, 0, w, h, fill);
            }
            float pad = Ui.px(16), x = pad;
            float top = Ui.px(16) + label.getTextSize() * 0.8f;
            if (tile.vectorIcon != 0) {
                fill.setColor(Ui.alpha(fg, a));
                Icons.draw(c, tile.vectorIcon, x - Ui.px(1), top - Ui.px(16), Ui.px(20), fill);
                x += Ui.px(32);
            } else if (tile.icon != null) {
                icon.setColor(Ui.alpha(fg, a));
                // Phones draw ♥ and ▶ as colour emoji unless asked for the text glyph.
                c.drawText(Ui.touch ? tile.icon + "\uFE0E" : tile.icon, x, top + Ui.px(1), icon);
                x += Ui.px(32);
            }
            float right = w - pad;
            stepLeft = stepRight = -1;
            if (Ui.touch && tile.onStep != null && tile.slider == 0 && tile.segments == null) {
                // ‹ value › : square buttons a finger can find.
                float b = Ui.px(34), by = top - label.getTextSize() * 0.8f - Ui.px(8);
                stepRight = right - b;
                fill.setColor(Ui.alpha(Ui.INK, tile.enabled ? 0.08f : 0.04f));
                c.drawRect(stepRight, by, right, by + b, fill);
                fill.setColor(Ui.alpha(fg, a));
                Icons.draw(c, Icons.RIGHT, stepRight + Ui.px(7), by + Ui.px(7), Ui.px(20), fill);
                right = stepRight - Ui.px(10);
            }
            String v = tile.value == null ? "" : tile.value;
            value.setColor(Ui.alpha(on ? Ui.INK : Ui.MUTED, a));
            float vw = value.measureText(v);
            c.drawText(v, right - vw, top, value);
            if (stepRight >= 0) {
                float b = Ui.px(34), by = top - label.getTextSize() * 0.8f - Ui.px(8);
                stepLeft = right - vw - Ui.px(10) - b;
                fill.setColor(Ui.alpha(Ui.INK, tile.enabled ? 0.08f : 0.04f));
                c.drawRect(stepLeft, by, stepLeft + b, by + b, fill);
                fill.setColor(Ui.alpha(fg, a));
                Icons.draw(c, Icons.LEFT, stepLeft + Ui.px(7), by + Ui.px(7), Ui.px(20), fill);
                vw += b + Ui.px(10);
            }
            label.setColor(Ui.alpha(fg, a));
            String l = TextUtils.ellipsize(tile.label, label, right - vw - Ui.px(12) - x, TextUtils.TruncateAt.END).toString();
            c.drawText(l, x, top, label);
            hint.setColor(Ui.alpha(Ui.MUTED, a));
            if (tile.slider > 0) {
                // A small A, the track with a tick per step and a square knob, a big A.
                float cy = top + Ui.px(26);
                small.setColor(Ui.alpha(fg, a));
                big.setColor(Ui.alpha(fg, a));
                c.drawText("A", x, cy + small.getTextSize() * 0.36f, small);
                float bx = right - big.measureText("A");
                c.drawText("A", bx, cy + big.getTextSize() * 0.36f, big);
                float tl = x + small.measureText("A") + Ui.px(14), tr = bx - Ui.px(14);
                trackLeft = tl;
                trackRight = tr;
                fill.setColor(Ui.alpha(Ui.INK, 0.18f));
                c.drawRect(tl, cy - Ui.px(1.5f), tr, cy + Ui.px(1.5f), fill);
                float step = (tr - tl) / Math.max(1, tile.slider - 1);
                float kx = tl + step * tile.position;
                fill.setColor(on || Ui.touch ? Ui.ACCENT : Ui.alpha(fg, 0.7f * a));
                c.drawRect(tl, cy - Ui.px(1.5f), kx, cy + Ui.px(1.5f), fill);
                for (int i = 0; i < tile.slider; i++) {
                    fill.setColor(Ui.alpha(Ui.INK, i <= tile.position ? 0.5f : 0.25f));
                    c.drawRect(tl + step * i - Ui.px(1), cy - Ui.px(6), tl + step * i + Ui.px(1), cy + Ui.px(6), fill);
                }
                fill.setColor(on || Ui.touch ? Ui.ACCENT : Ui.alpha(fg, a));
                float k = Ui.touch ? Ui.px(9) : Ui.px(7);
                c.drawRect(kx - k, cy - Ui.px(9) - (k - Ui.px(7)), kx + k, cy + Ui.px(9) + (k - Ui.px(7)), fill);
            } else if (tile.segments != null) {
                float y0 = top + Ui.px(14), gap = Ui.px(6), sh = Ui.px(36);
                float sw = (right - x - gap * (tile.segments.length - 1)) / tile.segments.length;
                segLeft = x;
                segRight = right;
                for (int i = 0; i < tile.segments.length; i++) {
                    float sx = x + i * (sw + gap);
                    boolean ticked = tile.checked != null && tile.checked[i];
                    // Touch has no selection to move: the choice made is the lit one.
                    boolean here = Ui.touch ? tile.enabled && ticked : on && tile.enabled && i == tile.segment;
                    fill.setColor(here ? Ui.ACCENT : Ui.alpha(Ui.INK, ticked ? 0.16f : 0.07f));
                    c.drawRect(sx, y0, sx + sw, y0 + sh, fill);
                    int color = Ui.alpha(here ? 0xFFFFFFFF : fg, a);
                    String text = (ticked ? "✓ " : "") + tile.segments[i];
                    float iconW = tile.segmentIcons != null ? Ui.px(24) : 0;
                    pill.setColor(color);
                    text = TextUtils.ellipsize(text, pill, sw - Ui.px(16) - iconW, TextUtils.TruncateAt.END).toString();
                    float tx = sx + (sw - pill.measureText(text) - iconW) / 2;
                    if (iconW > 0) {
                        fill.setColor(color);
                        Icons.draw(c, tile.segmentIcons[i], tx, y0 + (sh - Ui.px(17)) / 2, Ui.px(17), fill);
                    }
                    c.drawText(text, tx + iconW, y0 + Ui.px(23.5f), pill);
                }
                if (tile.hint != null && !tile.hint.isEmpty()) {
                    String t = TextUtils.ellipsize(tile.hint, hint, right - x, TextUtils.TruncateAt.END).toString();
                    c.drawText(t, x, h - Ui.px(12), hint);
                }
            } else if (tile.hint != null && !tile.hint.isEmpty()) {
                String t = TextUtils.ellipsize(tile.hint, hint, right - x, TextUtils.TruncateAt.END).toString();
                c.drawText(t, x, top + Ui.px(24), hint);
            }
        }
    }

    /** The panel's ground: the rail's colour, a hairline at its left edge. */
    private static final class Edge extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint();

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            paint.setColor(Ui.RAIL);
            c.drawRect(b, paint);
            paint.setColor(Ui.RULE);
            if (Ui.touch) {
                // A sheet: the hairline along its top, and a square grip in the middle.
                c.drawRect(b.left, b.top, b.right, b.top + Ui.px(1), paint);
                paint.setColor(Ui.alpha(Ui.INK, 0.25f));
                c.drawRect(b.centerX() - Ui.px(18), b.top + Ui.px(6), b.centerX() + Ui.px(18), b.top + Ui.px(9), paint);
            } else {
                c.drawRect(b.left, b.top, b.left + Ui.px(1), b.bottom, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {}

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }
}
