package io.github.vladislavekimtcov.broadsheet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A story, full screen, as a column of type. Shows the card's headline, summary and picture at
 * once, then the body when the relay has it.
 *
 * <p>Keys: Down/OK scroll about half a screen (OK at the end opens the next story), Up back,
 * Fast-forward/Rewind a full screen, Left/Right the previous/next story, Back the front page.
 * A video scrolled into view is selected (ringed, "Play video"): OK or Play plays it.
 */
final class Reader extends FrameLayout {
    interface Listener {
        void onClose();

        void onMove(int delta);

        /** OK on a video in the story. */
        void onPlay(String stream, String title);
    }

    static final float[] SIZES = {18.5f, 21f, 24f, 27.5f};
    static final String[] SIZE_NAMES = {"Small", "Medium", "Large", "Extra large"};

    private final Images images;
    private final Listener listener;
    private final Page scroll;
    private final LinearLayout column;
    private final View progress;
    private final List<Picture> pictures = new ArrayList<>();
    private Story story;
    private Article article;
    private int sizeStep = 1;
    private TextView note;
    /** The video in view that OK plays, or null. */
    private Picture selectedVideo;

    Reader(Context c, Images images, Listener listener) {
        super(c);
        this.images = images;
        this.listener = listener;
        setBackgroundColor(Ui.PAPER);
        scroll = new Page(c);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, Ui.dp(56), 0, Ui.dp(140));
        FrameLayout holder = new FrameLayout(c);
        holder.addView(column, new FrameLayout.LayoutParams(Ui.dp(700), -2, Gravity.CENTER_HORIZONTAL));
        scroll.addView(holder, new ViewGroup.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -1));
        progress = new View(c);
        progress.setBackgroundColor(Ui.ACCENT);
        progress.setPivotX(0);
        progress.setScaleX(0);
        addView(progress, new LayoutParams(-1, Ui.dp(3)));
    }

    Story story() {
        return story;
    }

    /** The body, once it arrived. */
    Article article() {
        return article;
    }

    int sizeStep() {
        return sizeStep;
    }

    /** How far through the story the page is, 0 to 1. */
    float fraction() {
        View child = scroll.getChildAt(0);
        int range = child == null ? 0 : child.getHeight() - scroll.getHeight();
        return range <= 0 ? 0f : Math.min(1f, scroll.getScrollY() / (float) range);
    }

    /** Scrolls to a place found by {@link #fraction} once the page is laid out. */
    void restoreFraction(final float f) {
        if (f <= 0) return;
        final ViewTreeObserver vto = column.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                column.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                View child = scroll.getChildAt(0);
                int range = child == null ? 0 : child.getHeight() - scroll.getHeight();
                scroll.scrollTo(0, Math.round(range * f));
            }
        });
    }

    void setTextSizeStep(int step) {
        sizeStep = Math.max(0, Math.min(SIZES.length - 1, step));
    }

    /** Sets the open story again (new text size), keeping the place in it. */
    void relayout() {
        if (story == null) return;
        float f = fraction();
        Story s = story;
        Article a = article;
        show(s);
        if (a != null) setArticle(a, null);
        restoreFraction(f);
    }

    /** Opens a story with what the card knows; the body follows with {@link #setArticle}. */
    void show(Story s) {
        story = s;
        article = null;
        clear();
        header(s, null, 0);
        if (s.excerpt != null) column.addView(standfirst(s.excerpt));
        if (s.image != null) addPicture(s.image, s.imageWidth, s.imageHeight, null, 0, true);
        note = note("Getting the story from Apple News on the Mac…");
        scroll.scrollTo(0, 0);
        afterLayout();
    }

    /** The body arrived (or didn't: {@code error}). */
    void setArticle(Article a, String error) {
        if (a == null) {
            if (note != null) note.setText(error != null ? error + "\n\nThis is the summary only." : "This is the summary only.");
            return;
        }
        if (a.story != null && !a.story.id.equals(story.id)) return;
        article = a;
        int y = scroll.getScrollY();
        clear();
        Story s = a.story != null ? a.story : story;
        header(s, a.byline, a.truncated ? 0 : a.words());  // a preview's length isn't the story's
        String sub = a.subtitle != null ? a.subtitle : s.excerpt;
        if (sub != null) column.addView(standfirst(sub));
        String first = a.blocks.isEmpty() ? null : a.blocks.get(0).type;
        boolean leadImage = "image".equals(first) || "video".equals(first);
        if (!leadImage && s.image != null) addPicture(s.image, s.imageWidth, s.imageHeight, null, 0, true);
        for (Article.Block b : a.blocks) block(b);
        if (a.pendingJob != null) {
            note = note("Getting the full story from Apple News on the Mac…");
        } else if ("preview".equals(a.completeness)) {
            note = note("This is the opening of an Apple News+ story. The rest needs an Apple News+ subscription "
                    + "on the Mac.\n\nRight: next story");
        } else if ("summary".equals(a.completeness)) {
            note = note(a.jobFailed ? "Apple News didn't hand over the full story, so this is the summary.\n\nRight: next story"
                    : "This is the summary only.\n\nRight: next story");
        } else {
            note = note("—\n\nOK: next story");
            note.setGravity(Gravity.CENTER_HORIZONTAL);
        }
        scroll.scrollTo(0, y);
        afterLayout();
    }

    private void clear() {
        selectedVideo = null;
        for (Picture p : pictures) images.cancel(p);
        pictures.clear();
        column.removeAllViews();
        note = null;
    }

    private float size() {
        return SIZES[sizeStep];
    }

    private void header(Story s, String byline, int words) {
        Context c = getContext();
        TextView dateline = Ui.text(c, 14, Ui.MUTED, Ui.sansMedium);
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (s.channel != null) {
            b.append(s.channel.toUpperCase(java.util.Locale.getDefault()));
            b.setSpan(new ForegroundColorSpan(s.accent()), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        String ago = Dates.ago(s.published, System.currentTimeMillis());
        if (!ago.isEmpty()) b.append(b.length() > 0 ? "   ·   " : "").append(ago);
        if (words > 0) b.append("   ·   ").append(Dates.readingTime(words));
        else if (s.minutes > 0) b.append("   ·   ").append(String.valueOf(s.minutes)).append(" min read");
        if (s.paid) b.append("   ·   Apple News+");
        dateline.setText(b);
        dateline.setLetterSpacing(0.04f);
        column.addView(dateline);
        TextView title = Ui.text(c, size() * 1.72f, Ui.INK, Ui.head);
        title.setText(s.title);
        title.setLineSpacing(0, 1.08f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Ui.dp(16);
        column.addView(title, tl);
        String by = byline != null ? byline : s.byline;
        if (by != null) {
            TextView bl = Ui.text(c, 14, Ui.FAINT, Ui.sans);
            bl.setText(by);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = Ui.dp(14);
            column.addView(bl, p);
        }
    }

    private TextView standfirst(String text) {
        TextView v = Ui.text(getContext(), size() * 1.0f, Ui.MUTED, Ui.textItalic);
        v.setText(text);
        v.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = Ui.dp(18);
        v.setLayoutParams(p);
        return v;
    }

    private void block(Article.Block b) {
        Context c = getContext();
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        switch (b.type) {
            case "paragraph": {
                TextView v = Ui.text(c, size(), Ui.BODY, Ui.text);
                v.setText(b.text);
                v.setLineSpacing(0, 1.45f);
                p.topMargin = Ui.dp(18);
                column.addView(v, p);
                break;
            }
            case "heading": {
                TextView v = Ui.text(c, size() * (b.level <= 2 ? 1.25f : 1.12f), Ui.INK, Ui.head);
                v.setText(b.text);
                v.setLineSpacing(0, 1.12f);
                p.topMargin = Ui.dp(32);
                column.addView(v, p);
                break;
            }
            case "quote": {
                TextView v = Ui.text(c, size() * 1.12f, Ui.INK, Ui.headItalic);
                v.setText(b.text);
                v.setLineSpacing(0, 1.3f);
                v.setPadding(Ui.dp(22), Ui.dp(2), 0, Ui.dp(2));
                v.setBackground(new Rule());
                p.topMargin = Ui.dp(26);
                p.bottomMargin = Ui.dp(8);
                column.addView(v, p);
                break;
            }
            case "caption": {
                TextView v = Ui.text(c, 13, Ui.FAINT, Ui.sans);
                v.setText(b.text);
                v.setLineSpacing(0, 1.25f);
                p.topMargin = Ui.dp(8);
                column.addView(v, p);
                break;
            }
            case "image":
                addPicture(b.url, b.width, b.height, b.more > 0 ? (b.caption != null ? b.caption + "  ·  " : "")
                        + "+" + b.more + " more photos" : b.caption, 0, false);
                break;
            case "video": {
                Picture v = addPicture(b.url, b.width, b.height, b.caption, 1, false);
                if (v != null) v.stream = b.stream;
                break;
            }
            case "divider": {
                View v = new View(c);
                v.setBackgroundColor(Ui.RULE);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(120), Ui.dp(1));
                dl.gravity = Gravity.CENTER_HORIZONTAL;
                dl.topMargin = Ui.dp(30);
                dl.bottomMargin = Ui.dp(8);
                column.addView(v, dl);
                break;
            }
            default:
                break;
        }
    }

    private Picture addPicture(String url, int w, int h, String caption, int kind, boolean lead) {
        if (url == null && kind != 1) return null;
        Picture pic = new Picture(getContext(), url, w, h, kind == 1);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = Ui.dp(lead ? 26 : 24);
        column.addView(pic, p);
        pictures.add(pic);
        if (caption != null) {
            TextView v = Ui.text(getContext(), 13, Ui.FAINT, Ui.sans);
            v.setText(caption);
            v.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.topMargin = Ui.dp(8);
            column.addView(v, cl);
        }
        return pic;
    }

    private TextView note(String text) {
        TextView v = Ui.text(getContext(), 15, Ui.FAINT, Ui.sans);
        v.setText(text);
        v.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = Ui.dp(40);
        column.addView(v, p);
        return v;
    }

    /** Remote keys while reading. */
    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return handles(e.getKeyCode());
        int h = scroll.getHeight();
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                scroll.smoothScrollBy(0, Math.round(h * 0.45f));
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (selectedVideo != null) play(selectedVideo);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (selectedVideo != null) play(selectedVideo);
                else if (atEnd()) listener.onMove(1);
                else scroll.smoothScrollBy(0, Math.round(h * 0.45f));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                scroll.smoothScrollBy(0, -Math.round(h * 0.45f));
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                scroll.smoothScrollBy(0, Math.round(h * 0.9f));
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                scroll.smoothScrollBy(0, -Math.round(h * 0.9f));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                listener.onMove(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                listener.onMove(-1);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                listener.onClose();
                return true;
            default:
                return false;
        }
    }

    private static boolean handles(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }

    private void play(Picture p) {
        if (p.stream != null) listener.onPlay(p.stream, story != null ? story.title : null);
        else listener.onPlay(null, null);  // Main explains that this one can't play here
    }

    private boolean atEnd() {
        View child = scroll.getChildAt(0);
        return child == null || scroll.getScrollY() + scroll.getHeight() >= child.getHeight() - Ui.dp(8);
    }

    private void updateProgress() {
        View child = scroll.getChildAt(0);
        int range = child == null ? 0 : child.getHeight() - scroll.getHeight();
        progress.setScaleX(range <= 0 ? 1f : Math.min(1f, scroll.getScrollY() / (float) range));
    }

    /** Works out what's near the screen once the new page has been laid out. */
    private void afterLayout() {
        final ViewTreeObserver vto = column.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                column.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                onScroll();
            }
        });
        column.requestLayout();
    }

    private void onScroll() {
        updateProgress();
        int top = scroll.getScrollY(), bottom = top + scroll.getHeight();
        int[] loc = new int[2];
        int[] base = new int[2];
        column.getLocationInWindow(base);
        Picture best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Picture p : pictures) {
            if (p.getHeight() == 0) continue;  // not laid out yet: afterLayout comes back
            p.getLocationInWindow(loc);
            int y = loc[1] - base[1] + column.getTop();
            p.setNear(y + p.getHeight() > top - scroll.getHeight() && y < bottom + scroll.getHeight() * 2);
            if (!p.video) continue;
            // A video becomes the one OK plays once most of it is on screen; of two, the nearer the middle.
            int shown = Math.min(bottom, y + p.getHeight()) - Math.max(top, y);
            if (shown < Math.min(p.getHeight(), scroll.getHeight()) * 0.6f) continue;
            float d = Math.abs(y + p.getHeight() / 2f - (top + bottom) / 2f);
            if (d < bestDistance) {
                best = p;
                bestDistance = d;
            }
        }
        if (best != selectedVideo) {
            if (selectedVideo != null) selectedVideo.invalidate();
            selectedVideo = best;
            if (best != null) best.invalidate();
        }
    }

    /** The scrolling page: never takes focus, reports its position. */
    private final class Page extends ScrollView {
        Page(Context c) {
            super(c);
            setFocusable(false);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setSmoothScrollingEnabled(true);
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            onScroll();
        }
    }

    /** The thin rule left of a quotation. */
    private static final class Rule extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint();

        Rule() {
            paint.setColor(Ui.ACCENT);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            c.drawRect(b.left, b.top, b.left + Ui.px(3), b.bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {}

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** A picture in the story, as wide as the column; its height follows the picture's shape. */
    private final class Picture extends View implements Images.Target {
        final String url;
        final float aspect;
        final boolean video;
        String stream;
        private Bitmap bitmap;
        private boolean near;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect dst = new Rect();

        Picture(Context c, String url, int w, int h, boolean video) {
            super(c);
            this.url = url;
            this.video = video;
            this.aspect = w > 0 && h > 0 ? Math.max(0.4f, Math.min(1.6f, h / (float) w)) : 9 / 16f;
            label.setColor(Ui.INK);
            label.setTypeface(Ui.sansBold);
            label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.px(3));
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, Math.min(Math.round(w * aspect), Ui.dp(620)));
        }

        void setNear(boolean value) {
            if (value == near) return;
            near = value;
            if (url == null) return;
            if (near) {
                if (getWidth() > 0) images.load(this, url, getWidth(), getHeight(), true);  // else onSizeChanged
            } else {
                images.cancel(this);
                bitmap = null;
                invalidate();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (near && url != null && w > 0 && h > 0) images.load(this, url, w, h, true);
        }

        @Override
        public void onImage(Bitmap b, boolean fresh) {
            bitmap = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (bitmap == null) {
                c.drawColor(Ui.SURFACE);
            } else {
                float scale = Math.min(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
                int dw = Math.round(bitmap.getWidth() * scale), dh = Math.round(bitmap.getHeight() * scale);
                dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
                c.drawBitmap(bitmap, null, dst, paint);
            }
            if (video) {
                boolean on = selectedVideo == this;
                String text = on ? (stream != null ? "▶  Play video   OK" : "Plays only in Apple News") : "▶  Video";
                float tw = label.measureText(text);
                label.setColor(on && stream != null ? Ui.ACCENT : 0xCC000000);
                c.drawRoundRect(Ui.px(12), h - Ui.px(48), Ui.px(40) + tw, h - Ui.px(12), Ui.px(18), Ui.px(18), label);
                label.setColor(0xFFFFFFFF);
                c.drawText(text, Ui.px(26), h - Ui.px(24), label);
                if (on) {
                    ring.setColor(Ui.FOCUS);
                    c.drawRect(Ui.px(1.5f), Ui.px(1.5f), w - Ui.px(1.5f), h - Ui.px(1.5f), ring);
                }
            }
        }
    }
}
