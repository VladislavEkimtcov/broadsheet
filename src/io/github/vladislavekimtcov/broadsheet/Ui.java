package io.github.vladislavekimtcov.broadsheet;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.TextView;

/** Sizes, type and colours shared by every view: a dark, warm page, like newsprint at night. */
final class Ui {
    static final int PAPER = 0xFF11100E;
    static final int SURFACE = 0xFF1D1B18;
    static final int SURFACE_HI = 0xFF2B2824;
    static final int INK = 0xFFF4EFE6;
    static final int BODY = 0xFFE4DED3;
    static final int MUTED = 0xFFB1AA9E;
    static final int FAINT = 0xFF7F786E;
    static final int RULE = 0x24FFFFFF;
    /** Broadsheet red: the masthead rule, the selected section, drop caps, the reading progress. */
    static final int ACCENT = 0xFFE5484D;

    static Typeface display = Typeface.create("serif", Typeface.BOLD);
    static Typeface head = Typeface.create("serif", Typeface.BOLD);
    static Typeface headItalic = Typeface.create("serif", Typeface.ITALIC);
    static Typeface text = Typeface.create("serif", Typeface.NORMAL);
    static Typeface textBold = Typeface.create("serif", Typeface.BOLD);
    static Typeface textItalic = Typeface.create("serif", Typeface.ITALIC);
    static Typeface sans = Typeface.create("sans-serif", Typeface.NORMAL);
    static Typeface sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static Typeface sansBold = Typeface.create("sans-serif", Typeface.BOLD);

    private static float density = 2f, scaled = 2f;

    private Ui() {}

    /** Newsreader from the APK's assets; Noto Serif (the TV's "serif") if one is missing. */
    static void init(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        density = m.density;
        scaled = m.scaledDensity;
        display = asset(c, "fonts/newsreader-display.ttf", display);
        head = asset(c, "fonts/newsreader-head.ttf", head);
        headItalic = asset(c, "fonts/newsreader-head-italic.ttf", headItalic);
        text = asset(c, "fonts/newsreader-text.ttf", text);
        textBold = asset(c, "fonts/newsreader-text-bold.ttf", textBold);
        textItalic = asset(c, "fonts/newsreader-text-italic.ttf", textItalic);
    }

    private static Typeface asset(Context c, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static float px(float dp) {
        return dp * density;
    }

    static float sp(float v) {
        return v * scaled;
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    static TextPaint paint(float sp, int color, Typeface face) {
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(sp(sp));
        p.setColor(color);
        p.setTypeface(face);
        return p;
    }

    static int alpha(int color, float a) {
        int base = color >>> 24;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }
}
