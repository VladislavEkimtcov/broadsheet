package io.github.vladislavekimtcov.broadsheet;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Sizes, type and colours shared by every view. Two pages: dark (warm, like newsprint at night)
 * and light (newsprint by day). Views read the colours when they are built, so switching
 * ({@link #setLight}) is followed by building the views again.
 */
final class Ui {
    static int PAPER, SURFACE, SURFACE_HI, INK, BODY, MUTED, FAINT, RULE, RAIL, FOCUS;
    /** Broadsheet red: the masthead rule, the selected section, the reading progress. */
    static int ACCENT;
    static boolean light;

    static {
        setLight(false);
    }

    static void setLight(boolean value) {
        light = value;
        if (value) {
            PAPER = 0xFFF5F0E6;
            SURFACE = 0xFFE9E2D4;
            SURFACE_HI = 0xFFDCD3C1;
            INK = 0xFF1B1814;
            BODY = 0xFF2B2722;
            MUTED = 0xFF5B544A;
            FAINT = 0xFF80786C;
            RULE = 0x26000000;
            RAIL = 0xFFEAE3D5;
            FOCUS = 0xF21B1814;
            ACCENT = 0xFFC4262C;
        } else {
            PAPER = 0xFF11100E;
            SURFACE = 0xFF1D1B18;
            SURFACE_HI = 0xFF2B2824;
            INK = 0xFFF4EFE6;
            BODY = 0xFFE4DED3;
            MUTED = 0xFFB1AA9E;
            FAINT = 0xFF7F786E;
            RULE = 0x24FFFFFF;
            RAIL = 0xFF161512;
            FOCUS = 0xF2FFFFFF;
            ACCENT = 0xFFE5484D;
        }
    }

    /** Publisher accents are picked for white pages: on the dark page lift dark ones, on the
     *  light page deepen pale ones, so the dateline always reads. */
    static int readable(int c) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        if (light) {
            if (hsv[2] > 0.62f) hsv[2] = 0.62f;
            if (hsv[1] < 0.35f && hsv[2] > 0.45f) hsv[2] = 0.45f;
        } else {
            if (hsv[2] < 0.75f) hsv[2] = 0.75f + hsv[2] * 0.25f;
            if (hsv[1] > 0.75f) hsv[1] = 0.75f;
        }
        return android.graphics.Color.HSVToColor(hsv);
    }

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
