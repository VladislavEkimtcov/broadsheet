package io.github.vladislavekimtcov.broadsheet;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The phone's front page: a masthead with ☰, the sections as a strip along the top (the TV's
 * rail turned on its side, with the same square highlight), and a controllable horizontal gallery
 * pager over the sections: hero, briefs, picture cards; two columns where the screen is wide
 * (landscape, tablets). Swiping sideways slides 1:1 between categories under the finger, pull down
 * refreshes, a tap opens a story, a long press opens the options for it.
 *
 * <p>Cheap like the TV's page: one view per card (the shared {@link Card}, drawn square),
 * pictures decoded at the size shown and dropped when far off screen, nothing moving at rest.
 */
final class Feed extends FrameLayout implements FrontView, Main.Insettable {  // feature: phone.feed, front.rail
    /** A section chosen this long counts as looked at; a card on screen this long, as resting on it. */
    private static final int REST_MS = 375, CARD_REST_MS = 300;
    private static final int GUTTER_DP = 16, GAP_DP = 12, MAX_DP = 760;

    private final Images images;
    private final Listener listener;
    private final LinearLayout top, mastRow, mastCol, dateRow;
    private final TextView mast;
    private final IconButton menu;
    private final View hair;
    private boolean wide;
    private final HorizontalScrollView stripScroll;
    private final LinearLayout strip;
    private final Pull pull;
    private final Pager pager;
    private final TextView message;
    private final List<Tab> tabs = new ArrayList<>();
    private final Map<String, Integer> scrollMemory = new HashMap<>();
    private List<Relay.Section> sections = new ArrayList<>();
    private int current = -1, chosenAt = -1;
    private String statusText = "", bannerText, updateNotice;
    private Story chosen;
    private int lastWidth;

    private final Runnable rest = this::restOnSection;
    private final Runnable cardRest = this::restOnCard;

    Feed(Context c, Images images, Listener listener) {
        super(c);
        this.images = images;
        this.listener = listener;
        setBackgroundColor(Ui.PAPER);

        LinearLayout whole = new LinearLayout(c);
        whole.setOrientation(LinearLayout.VERTICAL);
        addView(whole, new LayoutParams(-1, -1));

        top = new LinearLayout(c);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(Ui.PAPER);
        mastRow = new LinearLayout(c);
        mastRow.setOrientation(LinearLayout.HORIZONTAL);
        mastRow.setGravity(Gravity.CENTER_VERTICAL);
        mastRow.setPadding(Ui.dp(GUTTER_DP), Ui.dp(8), Ui.dp(4), Ui.dp(6));
        mastCol = new LinearLayout(c);
        mastCol.setOrientation(LinearLayout.VERTICAL);
        mast = Ui.text(c, 27, Ui.INK, Ui.display);
        mast.setText("Broadsheet");
        mastCol.addView(mast);
        dateRow = new LinearLayout(c);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        View rule = new View(c);
        rule.setBackgroundColor(Ui.ACCENT);
        dateRow.addView(rule, new LinearLayout.LayoutParams(Ui.dp(30), Ui.dp(3)));
        TextView date = Ui.text(c, 12.5f, Ui.MUTED, Ui.sansMedium);
        date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(new Date()));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.leftMargin = Ui.dp(10);
        dateRow.addView(date, dl);
        LinearLayout.LayoutParams drl = new LinearLayout.LayoutParams(-2, -2);
        drl.topMargin = Ui.dp(6);
        mastCol.addView(dateRow, drl);
        menu = new IconButton(c, Icons.MENU);
        menu.setContentDescription("Options");
        menu.setOnClickListener(v -> {  // feature: opt.panel
            chosen = null;
            listener.onMenu();
        });

        stripScroll = new HorizontalScrollView(c);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        stripScroll.setHorizontalFadingEdgeEnabled(true);
        stripScroll.setFadingEdgeLength(Ui.dp(20));
        strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(Ui.dp(GUTTER_DP - 10), 0, Ui.dp(GUTTER_DP - 10), 0);
        stripScroll.addView(strip, new ViewGroup.LayoutParams(-2, Ui.dp(44)));
        hair = new View(c);
        hair.setBackgroundColor(Ui.RULE);
        arrangeTop(getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels);
        whole.addView(top, new LinearLayout.LayoutParams(-1, -2));

        pull = new Pull(c);
        pager = new Pager(c);
        pull.addView(pager, new LayoutParams(-1, -1));
        whole.addView(pull, new LinearLayout.LayoutParams(-1, 0, 1));

        message = Ui.text(c, 18, Ui.MUTED, Ui.text);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(0, 1.3f);
        message.setPadding(Ui.dp(28), 0, Ui.dp(28), 0);
        message.setBackgroundColor(Ui.PAPER);
        message.setVisibility(GONE);
        message.setOnClickListener(v -> listener.onRefresh());
        pull.addView(message, new LayoutParams(-1, -1));
        applyInsets();
    }

    private void restOnSection() {
        if (chosenAt >= 0) listener.onRailRest(chosenAt);
    }

    /** The card nearest the top of the screen, once the page has stopped: what the reader looks at. */
    private void restOnCard() {  // feature: reader.prefetch
        Card c = pager.curr != null ? pager.curr.firstOnScreen() : null;
        if (c != null) listener.onFocusStory(c.story, c.index);
    }

    /**
     * Upright: the masthead with ☰ on one row and the strip under it. Sideways, where height is
     * short: one row, the masthead's name, the strip and ☰.
     */
    private void arrangeTop(boolean sideways) {
        wide = sideways;
        for (View v : new View[] {mastRow, mastCol, stripScroll, menu, hair}) {
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        }
        top.removeAllViews();
        mastRow.removeAllViews();
        dateRow.setVisibility(sideways ? GONE : VISIBLE);
        mast.setTextSize(sideways ? 22 : 27);
        if (sideways) {
            mastRow.setPadding(Ui.dp(GUTTER_DP), 0, Ui.dp(4), 0);
            mastRow.addView(mastCol, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, Ui.dp(44), 1);
            sl.leftMargin = Ui.dp(12);
            mastRow.addView(stripScroll, sl);
            mastRow.addView(menu, new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(52)));
            top.addView(mastRow);
        } else {
            mastRow.setPadding(Ui.dp(GUTTER_DP), Ui.dp(8), Ui.dp(4), Ui.dp(6));
            mastRow.addView(mastCol, new LinearLayout.LayoutParams(0, -2, 1));
            mastRow.addView(menu, new LinearLayout.LayoutParams(Ui.dp(52), Ui.dp(52)));
            top.addView(mastRow);
            top.addView(stripScroll, new LinearLayout.LayoutParams(-1, Ui.dp(44)));
        }
        top.addView(hair, new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(1))));
    }

    @Override
    public void applyInsets() {  // feature: phone.insets
        top.setPadding(0, Ui.insetTop, 0, 0);
        pager.applyInsets();
        message.setPadding(Ui.dp(28), 0, Ui.dp(28), Ui.insetBottom);
        relayoutPage();
    }

    // ------------------------------------------------------------ sizes

    private int viewWidth() {
        int w = pager.getWidth() > 0 ? pager.getWidth() : getWidth();
        return w > 0 ? w : getResources().getDisplayMetrics().widthPixels - Ui.insetLeft - Ui.insetRight;
    }

    private int contentWidth() {
        return Math.min(Ui.dp(MAX_DP), viewWidth() - Ui.dp(GUTTER_DP) * 2);
    }

    /** One column on a phone held upright; two where there's room. */
    private int columns() {
        return contentWidth() >= Ui.dp(540) ? 2 : 1;
    }

    @Override
    public int tileWidth() {
        int n = columns();
        return (contentWidth() - Ui.dp(GAP_DP) * (n - 1)) / n;
    }

    private int heroHeight() {
        int screen = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        int want = Math.round(contentWidth() * (columns() == 1 ? 1.0f : 0.5f));
        return Math.max(Ui.dp(260), Math.min(want, Math.min(Ui.dp(460), Math.round(screen * 0.62f))));
    }

    @Override
    public int[] heroSize() {
        return new int[] {contentWidth(), heroHeight()};
    }

    // ------------------------------------------------------------ FrontView

    @Override
    public boolean hasContent() {
        return !sections.isEmpty();
    }

    @Override
    public void setStatus(String text) {
        statusText = text == null ? "" : text;
        pager.updateStatus();
        if (statusText.startsWith("Updated")) setBanner(null);  // the Mac answered
    }

    @Override
    public String statusText() {
        return statusText;
    }

    @Override
    public void setUpdateNotice(String text) {  // feature: opt.update
        updateNotice = text;
        pager.updateNotice();
    }

    @Override
    public void showMessage(String text) {  // feature: phone.offline
        if (!sections.isEmpty()) {
            setBanner(text);  // what's here stays readable; the note says what's wrong
            return;
        }
        message.setText(text);
        message.setVisibility(VISIBLE);
    }

    /** A plain note above the section: the Mac isn't answering, say. A tap tries again. */
    private void setBanner(String text) {
        bannerText = text;
        pager.updateBanner();
    }

    @Override
    public void setSections(List<Relay.Section> list, boolean keepFocus) {
        String id = currentSection() != null ? currentSection().id : null;
        restore(list, id, null, false, keepFocus);
    }

    @Override
    public void restore(List<Relay.Section> list, String currentId, String focusedStory, boolean railFocused, boolean keepFocus) {
        boolean same = currentSection() != null && currentSection().id.equals(currentId);
        int y = same && pager.curr != null ? pager.curr.getScrollY() : 0;
        sections = list;
        message.setVisibility(GONE);
        bannerText = null;
        strip.removeAllViews();
        tabs.clear();
        int keep = 0;
        for (int i = 0; i < list.size(); i++) {
            Tab t = new Tab(getContext(), i, list.get(i).title);
            tabs.add(t);
            strip.addView(t, new LinearLayout.LayoutParams(-2, -1));
            if (list.get(i).id.equals(currentId)) keep = i;
        }
        current = -1;
        pager.reset();
        if (list.isEmpty()) return;
        show(keep);
        if (keepFocus && same && pager.curr != null) {
            final PageView target = pager.curr;
            target.post(() -> target.scrollTo(0, y));  // a live reload: stay put
        }
    }

    @Override
    public Story focusedStory() {
        return chosen;
    }

    @Override
    public boolean railFocused() {
        return false;
    }

    @Override
    public void open(String sectionId) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).id.equals(sectionId)) {
                current = -1;
                choose(i);
                return;
            }
        }
    }

    @Override
    public void refreshDim() {
        pager.refreshDim();
    }

    @Override
    public Relay.Section currentSection() {
        return current >= 0 && current < sections.size() ? sections.get(current) : null;
    }

    /** Back from the reader: the story that was open is on screen (it may have moved with a swipe). */
    @Override
    public void focusStory(int index) {
        if (pager.curr != null) pager.curr.focusStory(index);
    }

    @Override
    public void append(Relay.Section s) {  // feature: front.deep
        pager.append(s);
    }

    // ------------------------------------------------------------ building & paging

    /** A tap on the strip. */
    private void choose(int index) {  // feature: front.warm
        if (index < 0 || index >= sections.size()) return;
        if (index == current && pager.curr != null) {
            pager.curr.smoothScrollTo(0, 0);
            return;
        }
        if (current >= 0) {
            pager.animateTo(index);
            return;
        }
        onSectionSelected(index);
        show(index);
    }

    private void onSectionSelected(int index) {
        if (index < 0 || index >= sections.size()) return;
        listener.onRailFocus(index, chosenAt < 0 ? 0 : Integer.signum(index - chosenAt));
        chosenAt = index;
        removeCallbacks(rest);
        postDelayed(rest, REST_MS);
    }

    private void show(int index) {  // feature: phone.strip
        if (index < 0 || index >= sections.size()) return;
        current = index;
        if (chosenAt < 0) chosenAt = index;
        syncStrip(index);
        pager.bindWindow(index);
    }

    private void syncStrip(int index) {
        for (Tab t : tabs) t.setChosen(t.index == index);
        if (index < 0 || index >= tabs.size()) return;
        final Tab tab = tabs.get(index);
        stripScroll.post(() -> {
            int x = stripScroll.getScrollX(), w = stripScroll.getWidth();
            if (tab.getLeft() < x + Ui.dp(24) || tab.getRight() > x + w - Ui.dp(24)) {
                stripScroll.smoothScrollTo(Math.max(0, tab.getLeft() - Ui.dp(32)), 0);
            }
        });
    }

    /** Lays out the current and adjacent sections again (e.g. rotation), keeping the place. */
    private void relayoutPage() {
        if (currentSection() == null || pager.curr == null) return;
        Card anchor = pager.curr.firstOnScreen();
        final int index = anchor != null ? anchor.index : -1;
        pager.bindWindow(current);
        if (index > 0) focusStory(index);
    }

    private static String subText(Relay.Section s) {
        int n = Math.max(s.total, s.stories.size());
        String count = n + (n == 1 ? " story" : " stories");
        if ("saved".equals(s.id)) return count + "  ·  saved in Apple News";
        if ("latest".equals(s.id)) return count + "  ·  newest in the archive";
        if ("more".equals(s.id)) return count + "  ·  from the archive on the Mac";
        if (s.today > 0 && n > s.today) return count + "  ·  " + s.today + " from Apple News Today, then more";
        return count + "  ·  from Apple News Today";
    }

    private static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private static LinearLayout.LayoutParams block(int width, int top, int bottom) {
        return block(width, top, bottom, -2);
    }

    private static LinearLayout.LayoutParams block(int width, int top, int bottom, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        boolean sideways = w > h;
        if (sideways != wide) post(() -> arrangeTop(sideways));
        if (ow != 0 && w != ow) post(() -> {
            if (viewWidth() != lastWidth) relayoutPage();  // a rotation: keep the place
        });
    }

    // ------------------------------------------------------------ pager & page views

    /**
     * Controllable photo-gallery pager across categories: keeps three {@link PageView} slots
     * (previous, current, next) positioned side by side, tracks horizontal drags 1:1 with edge
     * damping, and lets the user catch a settling animation mid-glide.
     */
    private final class Pager extends FrameLayout {  // feature: phone.strip
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private final PageView[] pool = new PageView[3];
        PageView prev, curr, next;
        private float downX, downY, dragX;
        private boolean dragging, settling;
        private VelocityTracker velocity;
        private ValueAnimator anim;

        Pager(Context c) {
            super(c);
            for (int i = 0; i < 3; i++) {
                pool[i] = new PageView(c);
                addView(pool[i], new LayoutParams(-1, -1));
            }
            prev = pool[0];
            curr = pool[1];
            next = pool[2];
            applyInsets();
        }

        void applyInsets() {
            for (PageView p : pool) p.applyInsets();
        }

        void reset() {
            cancelAnim();
            dragX = 0;
            for (PageView p : pool) p.clear();
            positionSlots(0);
        }

        void bindWindow(int index) {
            cancelAnim();
            dragX = 0;
            prev.bind(index - 1);
            curr.bind(index);
            next.bind(index + 1);
            int y = curr.section != null && scrollMemory.containsKey(curr.section.id) ? scrollMemory.get(curr.section.id) : 0;
            final PageView cPage = curr;
            cPage.post(() -> cPage.scrollTo(0, y));
            positionSlots(0);
        }

        void updateStatus() {
            for (PageView p : pool) p.updateStatus();
        }

        void updateNotice() {
            for (PageView p : pool) p.applyUpdateBanner();
        }

        void updateBanner() {
            for (PageView p : pool) p.applyBanner();
        }

        void refreshDim() {
            for (PageView p : pool) p.refreshDim();
        }

        void append(Relay.Section s) {
            for (PageView p : pool) {
                if (p.section == s) p.append(s);
            }
        }

        boolean canPull() {
            return !dragging && !settling && curr != null && curr.getScrollY() == 0;
        }

        boolean isDragging() {
            return dragging;
        }

        private int pageW() {
            return getWidth() > 0 ? getWidth() : viewWidth();
        }

        private void positionSlots(float offset) {
            int w = pageW();
            dragX = offset;
            if (prev != null) {
                prev.setVisibility(prev.section != null && offset > 0 ? VISIBLE : INVISIBLE);
                prev.setTranslationX(offset - w);
            }
            if (curr != null) {
                curr.setVisibility(curr.section != null ? VISIBLE : INVISIBLE);
                curr.setTranslationX(offset);
            }
            if (next != null) {
                next.setVisibility(next.section != null && offset < 0 ? VISIBLE : INVISIBLE);
                next.setTranslationX(offset + w);
            }
        }

        private float damp(float raw) {
            if (raw > 0 && current <= 0) return raw * 0.25f;
            if (raw < 0 && current >= sections.size() - 1) return raw * 0.25f;
            return raw;
        }

        private void cancelAnim() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            settling = false;
        }

        void animateTo(final int target) {
            if (target == current || target < 0 || target >= sections.size()) return;
            int delta = target - current;
            if (Math.abs(delta) == 1) {
                cancelAnim();
                settle(delta, 0);
                return;
            }
            // Multi-tab jump: bind the destination into the incoming slot and slide cleanly across.
            cancelAnim();
            dragX = 0;
            final int dir = Integer.signum(delta);
            final PageView incoming = dir > 0 ? next : prev;
            incoming.bind(target);
            int w = pageW();
            incoming.setTranslationX(dir * w);
            incoming.setVisibility(VISIBLE);
            settling = true;
            anim = ValueAnimator.ofFloat(0f, -dir * w);
            anim.setDuration(240);
            anim.setInterpolator(new DecelerateInterpolator(2f));
            anim.addUpdateListener(a -> {
                float x = (Float) a.getAnimatedValue();
                dragX = x;
                curr.setTranslationX(x);
                incoming.setTranslationX(x + dir * w);
            });
            anim.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    anim = null;
                    settling = false;
                    if (cancelled) return;
                    if (curr.section != null) scrollMemory.put(curr.section.id, curr.getScrollY());
                    PageView oldCurr = curr;
                    curr = incoming;
                    if (dir > 0) {
                        next = prev;
                        prev = oldCurr;
                    } else {
                        prev = next;
                        next = oldCurr;
                    }
                    current = target;
                    onSectionSelected(target);
                    syncStrip(target);
                    prev.bind(target - 1);
                    next.bind(target + 1);
                    positionSlots(0);
                    curr.onViewport();
                }
            });
            anim.start();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (sections.isEmpty()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    if (settling) {
                        // Catch a settling gallery page mid-glide, keeping its current offset.
                        cancelAnim();
                        downX -= dragX;
                        dragging = true;
                        if (velocity == null) velocity = VelocityTracker.obtain();
                        velocity.clear();
                        velocity.addMovement(e);
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    dragging = false;
                    if (velocity != null) velocity.clear();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                        dragging = true;
                        downX = e.getX() - (dx > 0 ? slop : -slop);
                        if (velocity == null) velocity = VelocityTracker.obtain();
                        velocity.clear();
                        velocity.addMovement(e);
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    break;
                }
                default:
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (sections.isEmpty()) return false;
            if (velocity == null) velocity = VelocityTracker.obtain();
            velocity.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = damp(e.getX() - downX);
                    positionSlots(dx);
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    velocity.computeCurrentVelocity(1000);
                    float vx = velocity.getXVelocity();
                    dragging = false;
                    int w = pageW();
                    int delta = 0;
                    if (dragX < -w * 0.28f || (vx < -Ui.dp(500) && dragX < -slop)) {
                        if (current + 1 < sections.size()) delta = 1;
                    } else if (dragX > w * 0.28f || (vx > Ui.dp(500) && dragX > slop)) {
                        if (current - 1 >= 0) delta = -1;
                    }
                    settle(delta, vx);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    settle(0, 0);
                    return true;
                default:
                    return true;
            }
        }

        private void settle(final int delta, float vx) {
            cancelAnim();
            int w = pageW();
            float start = dragX;
            float end = -delta * w;
            float dist = Math.abs(end - start);
            long dur = w > 0 ? Math.max(120L, Math.min(240L, Math.round(220f * dist / w))) : 180L;
            if (Math.abs(vx) > Ui.dp(900) && dist > 0) dur = Math.min(dur, 170L);
            settling = true;
            anim = ValueAnimator.ofFloat(start, end);
            anim.setDuration(dur);
            anim.setInterpolator(new DecelerateInterpolator(2f));
            anim.addUpdateListener(a -> positionSlots((Float) a.getAnimatedValue()));
            anim.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    anim = null;
                    settling = false;
                    if (cancelled) return;
                    if (delta != 0) commitShift(delta);
                    else positionSlots(0);
                }
            });
            anim.start();
        }

        private void commitShift(int delta) {
            if (curr.section != null) scrollMemory.put(curr.section.id, curr.getScrollY());
            if (delta > 0) {
                PageView recycled = prev;
                prev = curr;
                curr = next;
                next = recycled;
            } else {
                PageView recycled = next;
                next = curr;
                curr = prev;
                prev = recycled;
            }
            current += delta;
            onSectionSelected(current);
            syncStrip(current);
            if (delta > 0) next.bind(current + 1);
            else prev.bind(current - 1);
            positionSlots(0);
            curr.onViewport();
        }
    }

    /** One scrollable section page (reused across the 3 pager slots). */
    private final class PageView extends ScrollView {
        private final LinearLayout page;
        private final List<Card> cards = new ArrayList<>();
        Relay.Section section;
        private int built;
        private LinearLayout lastRow;
        private int lastRowCount;
        private TextView sub, status, more, banner, updateBanner;

        PageView(Context c) {
            super(c);
            setVerticalScrollBarEnabled(true);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setBackgroundColor(Ui.PAPER);
            setFillViewport(true);
            page = new LinearLayout(c);
            page.setOrientation(LinearLayout.VERTICAL);
            FrameLayout holder = new FrameLayout(c);
            holder.addView(page, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
            addView(holder, new ViewGroup.LayoutParams(-1, -2));
            applyInsets();
        }

        void applyInsets() {
            page.setPadding(0, Ui.dp(18), 0, Ui.dp(28) + Ui.insetBottom);
        }

        void clear() {
            for (Card c : cards) images.cancel(c);
            cards.clear();
            page.removeAllViews();
            section = null;
            built = 0;
            lastRow = null;
            lastRowCount = 0;
            sub = status = more = banner = updateBanner = null;
        }

        void bind(int index) {
            clear();
            if (index < 0 || index >= sections.size()) return;
            Relay.Section s = sections.get(index);
            section = s;
            Context c = getContext();
            long now = System.currentTimeMillis();
            lastWidth = viewWidth();
            int cw = contentWidth(), n = columns(), gap = Ui.dp(GAP_DP), tile = tileWidth();
            int tileH = tile * 9 / 16 + Ui.dp(96);

            updateBanner = Ui.text(c, 14, Ui.INK, Ui.sansMedium);
            updateBanner.setLineSpacing(0, 1.25f);
            updateBanner.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            updateBanner.setBackground(new RailBlock());
            updateBanner.setOnClickListener(v -> listener.onInstallUpdate());
            page.addView(updateBanner, block(cw, 0, Ui.dp(12)));
            applyUpdateBanner();

            banner = Ui.text(c, 14, Ui.INK, Ui.sans);
            banner.setLineSpacing(0, 1.25f);
            banner.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            banner.setBackground(new RailBlock());
            banner.setOnClickListener(v -> listener.onRefresh());
            page.addView(banner, block(cw, 0, Ui.dp(16)));
            applyBanner();

            TextView title = Ui.text(c, 26, Ui.INK, Ui.display);
            title.setText(s.title);
            page.addView(title, block(cw, 0, 0));
            sub = Ui.text(c, 12.5f, Ui.FAINT, Ui.sansMedium);
            sub.setText(subText(s));
            page.addView(sub, block(cw, Ui.dp(5), Ui.dp(14)));

            List<Story> stories = s.stories;
            int i = 0;
            lastRow = null;
            lastRowCount = n;
            if (stories.size() >= 3) {
                page.addView(card(Card.HERO, stories.get(0), 0, now), block(cw, 0, 0, heroHeight()));
                int briefH = Ui.dp(n == 1 ? 132 : 150);
                if (n == 1) {
                    page.addView(card(Card.BRIEF, stories.get(1), 1, now), block(cw, gap, 0, briefH));
                    page.addView(card(Card.BRIEF, stories.get(2), 2, now), block(cw, gap, 0, briefH));
                } else {
                    LinearLayout row = row(c);
                    row.addView(card(Card.BRIEF, stories.get(1), 1, now), new LinearLayout.LayoutParams(tile, briefH));
                    LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(tile, briefH);
                    second.leftMargin = gap;
                    row.addView(card(Card.BRIEF, stories.get(2), 2, now), second);
                    page.addView(row, block(cw, gap, 0));
                }
                i = 3;
            }
            for (; i < stories.size(); i++) addTile(stories.get(i), i, now, tile, tileH);
            built = stories.size();

            more = Ui.text(c, 13, Ui.FAINT, Ui.sansMedium);
            more.setGravity(Gravity.CENTER_HORIZONTAL);
            page.addView(more, block(cw, Ui.dp(18), 0));
            status = Ui.text(c, 11.5f, Ui.FAINT, Ui.sans);
            status.setLineSpacing(0, 1.25f);
            status.setText(statusText);
            page.addView(status, block(cw, Ui.dp(18), 0));
            updateMore(s);
            scrollTo(0, 0);
            post(this::onViewport);
        }

        private void addTile(Story st, int index, long now, int tile, int tileH) {
            int n = columns(), gap = Ui.dp(GAP_DP);
            if (lastRow == null || lastRowCount >= n) {
                lastRow = row(getContext());
                lastRowCount = 0;
                int at = more != null ? page.indexOfChild(more) : -1;
                if (at >= 0) page.addView(lastRow, at, block(contentWidth(), gap, 0));
                else page.addView(lastRow, block(contentWidth(), gap, 0));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tileH);
            if (lastRowCount > 0) lp.leftMargin = gap;
            lastRow.addView(card(Card.TILE, st, index, now), lp);
            lastRowCount++;
        }

        private Card card(int style, Story s, int index, long now) {
            final Card card = new Card(getContext(), style, images);
            card.setFocusable(false);
            card.bind(s, index, now);
            card.setDim(listener.isDimmed(s.id));
            card.setOnClickListener(v -> {
                if (section != null) listener.onOpen(section, card.index);
            });
            card.setOnLongClickListener(v -> {  // feature: phone.long_press
                chosen = card.story;
                listener.onMenu();
                return true;
            });
            cards.add(card);
            return card;
        }

        void append(Relay.Section s) {  // feature: front.deep
            if (section != s || built >= s.stories.size()) return;
            long now = System.currentTimeMillis();
            int tile = tileWidth(), tileH = tile * 9 / 16 + Ui.dp(96);
            for (int j = built; j < s.stories.size(); j++) addTile(s.stories.get(j), j, now, tile, tileH);
            built = s.stories.size();
            if (sub != null) sub.setText(subText(s));
            updateMore(s);
            post(this::onViewport);
        }

        void refreshDim() {
            for (Card c : cards) c.setDim(listener.isDimmed(c.story.id));
        }

        void updateStatus() {
            if (status != null && !TextUtils.equals(status.getText(), statusText)) status.setText(statusText);
        }

        void applyUpdateBanner() {
            if (updateBanner == null) return;
            updateBanner.setText(updateNotice);
            updateBanner.setVisibility(updateNotice == null || updateNotice.isEmpty() ? GONE : VISIBLE);
        }

        void applyBanner() {
            if (banner == null) return;
            banner.setText(bannerText);
            banner.setVisibility(bannerText == null ? GONE : VISIBLE);
        }

        private void updateMore(Relay.Section s) {
            if (more == null) return;
            more.setText(s.next != null ? "More stories on their way…" : "");
            more.setVisibility(s.next != null ? VISIBLE : GONE);
        }

        void focusStory(int index) {
            for (final Card c : cards) {
                if (c.index != index) continue;
                post(() -> {
                    int y = topIn(c), h = getHeight();
                    if (y < getScrollY() || y + c.getHeight() > getScrollY() + h) scrollTo(0, Math.max(0, y - Ui.dp(12)));
                });
                return;
            }
        }

        private int topIn(View v) {
            int y = 0;
            for (View p = v; p != null && p != this; p = (View) p.getParent()) {
                y += p.getTop();
                if (!(p.getParent() instanceof View)) break;
            }
            return y;
        }

        Card firstOnScreen() {
            int y = getScrollY();
            for (Card c : cards) {
                if (c.getHeight() == 0) continue;
                int t = topIn(c);
                if (t + c.getHeight() / 2 >= y) return c;
            }
            return null;
        }

        void onViewport() {
            int y = getScrollY(), h = getHeight();
            if (h == 0) return;
            for (Card c : cards) {
                int t = topIn(c);
                c.setNear(t + c.getHeight() > y - h && t < y + h * 2);
            }
            View content = getChildAt(0);
            if (section != null && section.next != null && content != null && content.getHeight() - (y + h) < h * 2) {
                listener.onNearEnd(section);
            }
            if (this == pager.curr) {
                Feed.this.removeCallbacks(cardRest);
                Feed.this.postDelayed(cardRest, CARD_REST_MS);
            }
        }

        @Override
        protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            onViewport();
        }
    }

    // ------------------------------------------------------------ pull, tabs & drawables

    /** Pull down at the top of the page to refresh: the page follows the finger, a line fills
     *  under "Release to refresh", and letting go past it asks the Mac for a fresh Today. */
    private final class Pull extends FrameLayout {  // feature: phone.pull_refresh
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private final int full = Ui.dp(84);
        private final TextPaint text = Ui.paint(13, Ui.MUTED, Ui.sansMedium);
        private final Paint fill = new Paint();
        private float startY, startX, offset;
        private boolean dragging;

        Pull(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (sections.isEmpty() || !pager.canPull()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = e.getY();
                    startX = e.getX();
                    dragging = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getY() - startY;
                    if (!dragging && pager.canPull() && dy > slop && dy > Math.abs(e.getX() - startX) * 1.5f) {
                        dragging = true;
                        startY = e.getY();
                        return true;
                    }
                    return false;
                }
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!dragging) return super.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    offset = Math.max(0, Math.min(full * 1.4f, (e.getY() - startY) * 0.5f));
                    pager.setTranslationY(offset);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean go = e.getActionMasked() == MotionEvent.ACTION_UP && offset >= full;
                    dragging = false;
                    pager.animate().translationY(0).setDuration(180).withEndAction(() -> {
                        offset = 0;
                        invalidate();
                    }).start();
                    if (go) listener.onRefresh();
                    return true;
                }
                default:
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float shown = pager.getTranslationY();
            if (shown <= 0) return;
            float p = Math.min(1f, offset / full);
            fill.setColor(Ui.SURFACE);
            c.drawRect(0, 0, getWidth(), shown, fill);
            String t = p >= 1f ? "Release to refresh" : "Pull to refresh";
            float tw = text.measureText(t);
            c.drawText(t, (getWidth() - tw) / 2, shown / 2 + text.getTextSize() * 0.35f, text);
            fill.setColor(Ui.ACCENT);
            float bw = Ui.px(120) * p;
            c.drawRect((getWidth() - bw) / 2, shown - Ui.px(3), (getWidth() + bw) / 2, shown, fill);
        }
    }

    /** A section on the strip: square, lit when chosen with the accent bar along its foot. */
    private final class Tab extends TextView {
        final int index;
        private boolean chosenTab;
        private final Paint bar = new Paint();

        Tab(Context c, int index, String title) {
            super(c);
            this.index = index;
            setText(title);
            setSingleLine(true);
            setTextSize(15);
            setTypeface(Ui.sansMedium);
            setGravity(Gravity.CENTER);
            setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
            bar.setColor(Ui.ACCENT);
            setOnClickListener(v -> choose(this.index));
            refresh();
        }

        void setChosen(boolean value) {
            chosenTab = value;
            refresh();
        }

        private void refresh() {
            setTextColor(chosenTab || isPressed() ? Ui.INK : Ui.MUTED);
            setBackgroundColor(chosenTab || isPressed() ? Ui.SURFACE_HI : 0);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            refresh();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (chosenTab) c.drawRect(0, getHeight() - Ui.px(3), getWidth(), getHeight(), bar);
        }
    }

    /** ☰ and friends: a drawn icon, lit like the rail while pressed. */
    static final class IconButton extends View {
        private final int icon;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconButton(Context c, int icon) {
            super(c);
            this.icon = icon;
            setClickable(true);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (isPressed()) {
                fill.setColor(Ui.SURFACE_HI);
                c.drawRect(0, 0, w, h, fill);
                fill.setColor(Ui.ACCENT);
                c.drawRect(0, h - Ui.px(3), w, h, fill);
            }
            fill.setColor(Ui.INK);
            float s = Ui.px(24);
            Icons.draw(c, icon, (w - s) / 2, (h - s) / 2, s, fill);
        }
    }

    /** The note's ground: the surface with an accent bar at its left edge. */
    private static final class RailBlock extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint();

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            paint.setColor(Ui.SURFACE_HI);
            c.drawRect(b, paint);
            paint.setColor(Ui.ACCENT);
            c.drawRect(b.left, b.top, b.left + Ui.px(3), b.bottom, paint);
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
