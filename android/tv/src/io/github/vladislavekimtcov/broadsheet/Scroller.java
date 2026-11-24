package io.github.vladislavekimtcov.broadsheet;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;

/**
 * A ScrollView that glides to keep the focused row comfortably on screen (with room above it),
 * instead of jumping the minimum distance, and tells a watcher where the viewport is so cards
 * far off screen can drop their pictures.
 */
final class Scroller extends ScrollView {
    /** Told the visible band (in page coordinates) whenever it moves. */
    interface Watcher {
        void onViewport(int top, int bottom);
    }

    private final Rect tmp = new Rect();
    private Watcher watcher;

    Scroller(Context c) {
        super(c);
        setSmoothScrollingEnabled(true);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setClipChildren(false);
        setFillViewport(true);
    }

    void setWatcher(Watcher w) {
        watcher = w;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (watcher != null) watcher.onViewport(t, t + getHeight());
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0; // scrolling is done in requestChildFocus, smoothly
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (focused == null || getChildCount() == 0) return;
        focused.getDrawingRect(tmp);
        offsetDescendantRectToMyCoords(focused, tmp);
        int margin = Ui.dp(30);
        int y = getScrollY(), h = getHeight();
        int target = y;
        if (tmp.bottom + margin > y + h) target = tmp.bottom + margin - h;
        if (tmp.top - margin * 2 < target) target = Math.min(target, tmp.top - margin * 2);
        if (tmp.top < h / 2) target = 0;
        int max = Math.max(0, getChildAt(0).getHeight() - getHeight());
        smoothScrollTo(0, Math.max(0, Math.min(max, target)));
    }
}
