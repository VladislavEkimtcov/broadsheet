package io.github.vladislavekimtcov.broadsheet;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * The few icons that have no plain glyph, drawn as paths on a 24-unit grid so they sit with the
 * text glyphs beside them (one colour, no emoji): thumbs up and down, and a laptop for Handoff.
 */
final class Icons {
    static final int THUMB_UP = 1, THUMB_DOWN = 2, LAPTOP = 3;

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
        }
        paint.setStyle(style);
        paint.setStrokeWidth(stroke);
    }
}
