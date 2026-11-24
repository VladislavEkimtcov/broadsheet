package io.github.vladislavekimtcov.broadsheet;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * The few icons that have no plain glyph, drawn as paths on a 24-unit grid so they sit with the
 * text glyphs beside them (one colour, no emoji): thumbs up and down, and a laptop for Handoff.
 * For touch: a menu (three bars), a back arrow, chevrons for steppers, and play.
 */
final class Icons {
    static final int THUMB_UP = 1, THUMB_DOWN = 2, LAPTOP = 3, MENU = 4, BACK = 5, LEFT = 6, RIGHT = 7, PLAY = 8;

    private static final Path THUMB = new Path();
    private static final Path tmp = new Path();
    private static final Matrix m = new Matrix();

    static {
        THUMB.addRect(2, 10, 6, 21, Path.Direction.CW);  // the cuff
        THUMB.moveTo(8, 10);                              // the hand, thumb raised
        THUMB.lineTo(11.6f, 3.2f);
        THUMB.quadTo(12.3f, 2.0f, 13.6f, 2.4f);
        THUMB.quadTo(15.0f, 3.0f, 14.7f, 4.6f);
        THUMB.lineTo(13.9f, 9);
        THUMB.lineTo(19.5f, 9);
        THUMB.quadTo(22, 9, 21.6f, 11.6f);
        THUMB.lineTo(20.2f, 19.2f);
        THUMB.quadTo(19.8f, 21, 18, 21);
        THUMB.lineTo(8, 21);
        THUMB.close();
    }

    private Icons() {}

    /** Draws icon {@code which}, {@code size} px square, top-left at (x, y), in the paint's colour. */
    static void draw(Canvas c, int which, float x, float y, float size, Paint paint) {
        float u = size / 24f;
        Paint.Style style = paint.getStyle();
        float stroke = paint.getStrokeWidth();
        if (which == THUMB_UP || which == THUMB_DOWN) {
            m.reset();
            if (which == THUMB_DOWN) m.setRotate(180, 12, 12);
            m.postScale(u, u);
            m.postTranslate(x, y);
            THUMB.transform(m, tmp);
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(tmp, paint);
        } else if (which == LAPTOP) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * u);
            c.drawRect(x + 4.5f * u, y + 5 * u, x + 19.5f * u, y + 15.5f * u, paint);
            paint.setStrokeWidth(2.6f * u);
            c.drawLine(x + 1.5f * u, y + 19 * u, x + 22.5f * u, y + 19 * u, paint);
        } else if (which == MENU) {
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 3; i++) c.drawRect(x + 3 * u, y + (5 + i * 6.25f) * u, x + 21 * u, y + (7.25f + i * 6.25f) * u, paint);
        } else if (which == BACK) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.25f * u);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            c.drawLine(x + 4.5f * u, y + 12 * u, x + 20.5f * u, y + 12 * u, paint);
            c.drawLine(x + 4.5f * u, y + 12 * u, x + 11 * u, y + 5.5f * u, paint);
            c.drawLine(x + 4.5f * u, y + 12 * u, x + 11 * u, y + 18.5f * u, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        } else if (which == LEFT || which == RIGHT) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.25f * u);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            float tip = which == LEFT ? 8.5f : 15.5f, back = which == LEFT ? 15 : 9;
            c.drawLine(x + back * u, y + 5.5f * u, x + tip * u, y + 12 * u, paint);
            c.drawLine(x + tip * u, y + 12 * u, x + back * u, y + 18.5f * u, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        } else if (which == PLAY) {
            tmp.reset();
            tmp.moveTo(x + 6 * u, y + 4 * u);
            tmp.lineTo(x + 20 * u, y + 12 * u);
            tmp.lineTo(x + 6 * u, y + 20 * u);
            tmp.close();
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(tmp, paint);
        }
        paint.setStyle(style);
        paint.setStrokeWidth(stroke);
    }
}
