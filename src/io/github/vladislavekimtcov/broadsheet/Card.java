package io.github.vladislavekimtcov.broadsheet;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * A story on the front page, drawn by hand: one view per card instead of a small tree, so a page
 * of thirty cards stays cheap to lay out and to redraw when focus moves.
 *
 * <ul>
 * <li>{@link #HERO}: the picture fills the card, headline and summary sit on a dark scrim. Where
 * the picture is light under the words even so (snow, sky, a white page), each line gets a dark
 * block behind it: see {@link #checkContrast}.</li>
 * <li>{@link #TILE}: 16:9 picture on top, dateline and a three-line headline below.</li>
 * <li>{@link #BRIEF}: no picture: dateline, a four-line headline and the start of the summary.</li>
 * </ul>
 */
final class Card extends View implements Images.Target {
    static final int HERO = 0, TILE = 1, BRIEF = 2;

    final int style;
    Story story;
    int index;
    private final Images images;
    private Bitmap bitmap;
    private float fade = 1f;
    private boolean near, dim;
    /** HERO: the picture under the words is too light for light type; the words get dark blocks. */
    private boolean blocks;
    private long now;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final TextPaint metaPaint, titlePaint, excerptPaint, badgePaint;
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private StaticLayout title, excerpt;
    private String meta;
    private final float radius = Ui.px(6);

    Card(Context c, int style, Images images) {
        super(c);
        this.style = style;
        this.images = images;
        setFocusable(true);
        setId(View.generateViewId());
        metaPaint = Ui.paint(style == HERO ? 13 : 11.5f, Ui.MUTED, Ui.sansMedium);
        // The hero's words sit on a dark scrim over its picture, light or dark page alike.
        titlePaint = Ui.paint(style == HERO ? 27 : (style == BRIEF ? 17 : 16), style == HERO ? 0xFFF4EFE6 : Ui.INK, Ui.head);
        excerptPaint = Ui.paint(style == HERO ? 15 : 13, style == HERO ? 0xFFC9C2B6 : Ui.MUTED, Ui.text);
        badgePaint = Ui.paint(10, Ui.INK, Ui.sansBold);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Ui.px(3));
        border.setColor(Ui.FOCUS);
    }

    void bind(Story s, int index, long now) {
        if (s != story) {
            images.cancel(this);
            bitmap = null;
        }
        this.story = s;
        this.index = index;
        this.now = now;
        meta = s.dateline(now);
        setContentDescription(s.title);
        buildText();
        if (near) images.load(this, s.image, imageWidth(), imageHeight(), false);
        invalidate();
    }

    /** Within a screen of the viewport: load the picture; far away: let it go. */
    void setNear(boolean value) {
        if (value == near || story == null) return;
        near = value;
        if (style == BRIEF) return;
        if (near) {
            images.load(this, story.image, imageWidth(), imageHeight(), false);
        } else {
            images.cancel(this);
            if (bitmap != null) {
                bitmap = null;
                invalidate();
            }
        }
    }

    private int imageWidth() {
        return Math.max(1, getWidth());
    }

    private int imageHeight() {
        return style == HERO ? Math.max(1, getHeight()) : Math.max(1, getWidth() * 9 / 16);
    }

    @Override
    public void onImage(Bitmap b, boolean fresh) {
        bitmap = b;
        checkContrast();
        if (fresh) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(220);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator v) {
                    fade = (Float) v.getAnimatedValue();
                    invalidate();
                }
            });
            a.start();
        } else {
            fade = 1f;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bounds.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        if (style == HERO) {
            scrim.setShader(new LinearGradient(0, h * 0.25f, 0, h, 0x00000000, 0xF0000000, Shader.TileMode.CLAMP));
        }
        buildText();
        if (near && story != null && style != BRIEF) images.load(this, story.image, imageWidth(), imageHeight(), false);
    }

    private void buildText() {
        int w = getWidth();
        if (story == null || w <= 0) return;
        int pad = Ui.dp(style == HERO ? 22 : 12);
        int tw = w - pad * 2;
        int titleLines = style == HERO ? 3 : (style == BRIEF ? 4 : 3);
        title = layout(story.title, titlePaint, tw, titleLines, 1.04f);
        excerpt = null;
        if (story.excerpt != null && style == HERO) {
            excerpt = layout(story.excerpt, excerptPaint, tw, 2, 1.15f);
        } else if (story.excerpt != null && style == BRIEF && getHeight() > 0) {
            // As many whole lines as fit under the headline, the last one ellipsized.
            float room = getHeight() - pad - briefExcerptTop(pad);
            for (int lines = 3; lines > 0 && excerpt == null; lines--) {
                StaticLayout l = layout(story.excerpt, excerptPaint, tw, lines, 1.15f);
                if (l.getLineBottom(l.getLineCount() - 1) - l.getSpacingAdd() <= room) excerpt = l;
            }
        }
        // Over a picture the publisher's colour can vanish (red on red): keep the hero's light.
        metaPaint.setColor(style == HERO ? 0xE6FFFFFF : story.accent());
        checkContrast();
    }

    /** Top of the hero's words (dateline, headline, summary), which sit at the bottom of the card. */
    private float heroTextTop(int h) {
        float y = h - Ui.dp(22);
        if (excerpt != null) y -= excerpt.getHeight() + Ui.dp(10);
        y -= title.getHeight() + Ui.dp(10) + metaPaint.getTextSize();
        return y;
    }

    /**
     * Looks at the part of the picture the hero's words cover, as it shows after the scrim, and
     * decides whether light type stays readable there. Samples a grid over the words' lines and
     * counts the points where the contrast with the headline's colour falls below 4.5:1 (WCAG's
     * line for text); more than one in twelve and the words get blocks.
     */
    private void checkContrast() {
        boolean was = blocks;
        blocks = false;
        int w = getWidth(), h = getHeight();
        if (style != HERO || bitmap == null || title == null || w <= 0 || h <= 0) {
            if (was) invalidate();
            return;
        }
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        float scale = Math.max(w / (float) bw, h / (float) bh);
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int x0 = (bw - cw) / 2, y0 = Math.max(0, (bh - ch) / 3);
        int pad = Ui.dp(22);
        float top = heroTextTop(h), bottom = h - pad;
        float right = pad + Math.max(widest(title), excerpt != null ? widest(excerpt) : 0);
        int bad = 0, all = 0;
        for (int iy = 0; iy < 12; iy++) {
            float vy = top + (bottom - top) * (iy + 0.5f) / 12f;
            float shade = 0.94f * Math.max(0f, Math.min(1f, (vy - h * 0.25f) / (h * 0.75f)));  // the scrim
            for (int ix = 0; ix < 24; ix++) {
                float vx = pad + (right - pad) * (ix + 0.5f) / 24f;
                int px = Math.min(bw - 1, Math.max(0, Math.round(x0 + vx / scale)));
                int py = Math.min(bh - 1, Math.max(0, Math.round(y0 + vy / scale)));
                int c = bitmap.getPixel(px, py);
                float lum = luminance(((c >> 16) & 0xFF) * (1 - shade), ((c >> 8) & 0xFF) * (1 - shade), (c & 0xFF) * (1 - shade));
                if ((TEXT_LUMINANCE + 0.05f) / (lum + 0.05f) < 4.5f) bad++;
                all++;
            }
        }
        blocks = bad * 12 > all;
        if (blocks != was) invalidate();
    }

    /** Relative luminance (WCAG) of the headline's colour, 0xF4EFE6. */
    private static final float TEXT_LUMINANCE = luminance(0xF4, 0xEF, 0xE6);

    private static float luminance(float r, float g, float b) {
        return 0.2126f * linear(r) + 0.7152f * linear(g) + 0.0722f * linear(b);
    }

    private static float linear(float v) {
        v /= 255f;
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
    }

    private static float widest(StaticLayout l) {
        float w = 0;
        for (int i = 0; i < l.getLineCount(); i++) w = Math.max(w, l.getLineRight(i));
        return w;
    }

    private float briefExcerptTop(int pad) {
        return pad + metaPaint.getTextSize() * 0.85f + Ui.dp(10) + title.getHeight() + Ui.dp(8);
    }

    private static StaticLayout layout(String text, TextPaint p, int width, int lines, float spacing) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, spacing)
                .setIncludePad(false)
                .setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        float s = focused ? 1.035f : 1f;
        animate().scaleX(s).scaleY(s).translationZ(focused ? Ui.px(8) : 0).alpha(dim && !focused ? 0.42f : 1f)
                .setDuration(140).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (story == null) return;
        int w = getWidth(), h = getHeight();
        c.save();
        c.clipPath(clip);
        fill.setColor(Ui.SURFACE);
        c.drawRect(0, 0, w, h, fill);
        if (style == HERO) {
            drawPicture(c, 0, 0, w, h);
            c.drawRect(0, 0, w, h, scrim);
            int pad = Ui.dp(22);
            float y = h - pad;
            if (excerpt != null) {
                y -= excerpt.getHeight();
                if (blocks) drawBlocks(c, excerpt, pad, y);
                drawLayout(c, excerpt, pad, y);
                y -= Ui.dp(10);
            }
            y -= title.getHeight();
            if (blocks) drawBlocks(c, title, pad, y);
            drawLayout(c, title, pad, y);
            y -= Ui.dp(10) + metaPaint.getTextSize();
            if (blocks && meta != null) {
                fill.setColor(BLOCK);
                c.drawRect(pad - Ui.px(8), y - Ui.px(3), pad + Math.min(metaPaint.measureText(meta), w - pad * 2) + Ui.px(8),
                        y + metaPaint.getTextSize() * 1.15f + Ui.px(2), fill);
            }
            drawMeta(c, pad, y + metaPaint.getTextSize() * 0.85f, w - pad * 2);
            if (story.paid) drawBadge(c, pad, pad);
        } else if (style == TILE) {
            int ih = w * 9 / 16;
            drawPicture(c, 0, 0, w, ih);
            int pad = Ui.dp(12);
            float y = ih + pad + metaPaint.getTextSize() * 0.85f;
            drawMeta(c, pad, y, w - pad * 2);
            drawLayout(c, title, pad, y + Ui.dp(9));
            if (story.paid) drawBadge(c, Ui.dp(8), Ui.dp(8));
        } else {
            fill.setColor(Ui.mix(Ui.SURFACE, tint(), 0.12f));
            c.drawRect(0, 0, w, h, fill);
            int pad = Ui.dp(12);
            float metaWidth = w - pad * 2 - (story.paid ? badgeWidth() + Ui.dp(8) : 0);
            drawMeta(c, pad, pad + metaPaint.getTextSize() * 0.85f, metaWidth);
            drawLayout(c, title, pad, pad + metaPaint.getTextSize() * 0.85f + Ui.dp(10));
            drawLayout(c, excerpt, pad, briefExcerptTop(pad));
            if (story.paid) drawBadge(c, w - pad - badgeWidth(), pad - Ui.dp(2));
        }
        c.restore();
        if (isFocused()) c.drawRoundRect(bounds.left + 1.5f, bounds.top + 1.5f, bounds.right - 1.5f,
                bounds.bottom - 1.5f, radius, radius, border);
    }

    private int tint() {
        return story.background != 0 ? story.background : Ui.SURFACE_HI;
    }

    /** A story already read on this TV, shown faded (full strength while focused). */
    void setDim(boolean value) {
        dim = value;
        setAlpha(dim && !isFocused() ? 0.42f : 1f);
    }

    private void drawPicture(Canvas c, int l, int t, int r, int b) {
        fill.setColor(Ui.mix(Ui.SURFACE, tint(), 0.35f));
        c.drawRect(l, t, r, b, fill);
        if (bitmap == null) return;
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        float scale = Math.max((r - l) / (float) bw, (b - t) / (float) bh);
        int cw = Math.round((r - l) / scale), ch = Math.round((b - t) / scale);
        int x = (bw - cw) / 2, y = Math.max(0, (bh - ch) / 3);
        src.set(x, y, x + cw, y + ch);
        dst.set(l, t, r, b);
        fill.setAlpha(Math.round(255 * fade));
        c.drawBitmap(bitmap, src, dst, fill);
        fill.setAlpha(255);
    }

    private void drawMeta(Canvas c, float x, float baseline, float width) {
        if (meta == null) return;
        String m = meta;
        if (metaPaint.measureText(m) > width) {
            // Short of room: shorten the publisher, keep the date.
            String when = Dates.ago(story.published, now);
            String tail = story.channel != null && !when.isEmpty() ? "  ·  " + when : "";
            m = TextUtils.ellipsize(tail.isEmpty() ? meta : story.channel, metaPaint,
                    width - metaPaint.measureText(tail), TextUtils.TruncateAt.END) + tail;
        }
        c.drawText(m, x, baseline, metaPaint);
    }

    private static final int BLOCK = 0xD6000000;

    /** A dark block behind each line, like a caption over video. */
    private void drawBlocks(Canvas c, StaticLayout l, float x, float y) {
        fill.setColor(BLOCK);
        for (int i = 0; i < l.getLineCount(); i++) {
            c.drawRect(x + l.getLineLeft(i) - Ui.px(8), y + l.getLineTop(i) - Ui.px(2),
                    x + l.getLineRight(i) + Ui.px(8), y + l.getLineBottom(i) + Ui.px(3), fill);
        }
    }

    private static void drawLayout(Canvas c, StaticLayout l, float x, float y) {
        if (l == null) return;
        c.save();
        c.translate(x, y);
        l.draw(c);
        c.restore();
    }

    private float badgeWidth() {
        return badgePaint.measureText("News+") + Ui.px(12);
    }

    private void drawBadge(Canvas c, float x, float y) {
        float bw = badgeWidth(), bh = badgePaint.getTextSize() + Ui.px(8);
        fill.setColor(0xCC000000);
        c.drawRoundRect(x, y, x + bw, y + bh, bh / 2, bh / 2, fill);
        badgePaint.setColor(0xFFFF5A5F);
        c.drawText("News+", x + Ui.px(6), y + bh - Ui.px(5.5f), badgePaint);
    }
}
