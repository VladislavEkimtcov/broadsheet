package io.github.vladislavekimtcov.broadsheet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * The phone's front page: a masthead with ☰, the sections as a strip along the top (the TV's
 * rail turned on its side, with the same square highlight), and the chosen section in one
 * column: hero, briefs, picture cards; two columns where the screen is wide (landscape, tablets).
 * Pull down to refresh; a tap opens a story, a long press opens the options for it.
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
    private final Scroll list;
    private final LinearLayout page;
    private final TextView message;
    private final java.util.List<Tab> tabs = new ArrayList<>();
    private final java.util.List<Card> cards = new ArrayList<>();
    private java.util.List<Relay.Section> sections = new ArrayList<>();
    private int current = -1, chosenAt = -1, built;
    private LinearLayout lastRow;
    private int lastRowCount;
    private TextView sub, status, more, banner, updateBanner;
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
        list = new Scroll(c);
        page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        FrameLayout holder = new FrameLayout(c);
        holder.addView(page, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
        list.addView(holder, new ViewGroup.LayoutParams(-1, -2));
        pull.addView(list, new LayoutParams(-1, -1));
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
        Card c = firstOnScreen();
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
        page.setPadding(0, Ui.dp(18), 0, Ui.dp(28) + Ui.insetBottom);
        message.setPadding(Ui.dp(28), 0, Ui.dp(28), Ui.insetBottom);
        relayoutPage();
    }

    // ------------------------------------------------------------ sizes

    private int viewWidth() {
        int w = list.getWidth() > 0 ? list.getWidth() : getWidth();
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
        if (status != null && !TextUtils.equals(status.getText(), statusText)) status.setText(statusText);
        if (statusText.startsWith("Updated")) setBanner(null);  // the Mac answered
    }

    @Override
    public String statusText() {
        return statusText;
    }

    @Override
    public void setUpdateNotice(String text) {  // feature: opt.update
        updateNotice = text;
        applyUpdateBanner();
    }

    private void applyUpdateBanner() {
        if (updateBanner == null) return;
        updateBanner.setText(updateNotice);
        updateBanner.setVisibility(updateNotice == null || updateNotice.isEmpty() ? GONE : VISIBLE);
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
        if (banner == null) return;
        banner.setText(text);
        banner.setVisibility(text == null ? GONE : VISIBLE);
    }

    @Override
    public void setSections(java.util.List<Relay.Section> list, boolean keepFocus) {
        String id = currentSection() != null ? currentSection().id : null;
        restore(list, id, null, false, keepFocus);
    }

    @Override
    public void restore(java.util.List<Relay.Section> list, String currentId, String focusedStory, boolean railFocused, boolean keepFocus) {
        boolean same = currentSection() != null && currentSection().id.equals(currentId);
        final int y = this.list.getScrollY();
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
        if (list.isEmpty()) return;
        show(keep);
        if (keepFocus && same) this.list.post(() -> this.list.scrollTo(0, y));  // a live reload: stay put
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
        for (Card c : cards) c.setDim(listener.isDimmed(c.story.id));
    }

    @Override
    public Relay.Section currentSection() {
        return current >= 0 && current < sections.size() ? sections.get(current) : null;
    }

    /** Back from the reader: the story that was open is on screen (it may have moved with a swipe). */
    @Override
    public void focusStory(int index) {
        for (final Card c : cards) {
            if (c.index != index) continue;
            list.post(() -> {
                int y = topIn(c), h = list.getHeight();
                if (y < list.getScrollY() || y + c.getHeight() > list.getScrollY() + h) list.scrollTo(0, Math.max(0, y - Ui.dp(12)));
            });
            return;
        }
    }

    // ------------------------------------------------------------ building

    /** A tap on the strip. */
    private void choose(int index) {  // feature: front.warm
        if (index == current) {
            list.smoothScrollTo(0, 0);
            return;
        }
        listener.onRailFocus(index, chosenAt < 0 ? 0 : Integer.signum(index - chosenAt));
        chosenAt = index;
        removeCallbacks(rest);
        postDelayed(rest, REST_MS);
        show(index);
        list.scrollTo(0, 0);
    }

    private void show(int index) {  // feature: phone.strip
        if (index < 0 || index >= sections.size()) return;
        current = index;
        if (chosenAt < 0) chosenAt = index;
        for (Tab t : tabs) t.setChosen(t.index == index);
        final Tab tab = tabs.get(index);
        stripScroll.post(() -> {
            int x = stripScroll.getScrollX(), w = stripScroll.getWidth();
            if (tab.getLeft() < x + Ui.dp(24) || tab.getRight() > x + w - Ui.dp(24)) {
                stripScroll.smoothScrollTo(Math.max(0, tab.getLeft() - Ui.dp(32)), 0);
            }
        });
        build();
    }

    /** Lays out the current section again (a rotation: one column or two), keeping the place. */
    private void relayoutPage() {
        if (currentSection() == null) return;
        Card anchor = firstOnScreen();
        final int index = anchor != null ? anchor.index : -1;
        build();
        if (index > 0) focusStory(index);
    }

    private void build() {
        for (Card c : cards) images.cancel(c);
        cards.clear();
        page.removeAllViews();
        Relay.Section s = currentSection();
        if (s == null) return;
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
        setBanner(bannerText);

        TextView title = Ui.text(c, 26, Ui.INK, Ui.display);
        title.setText(s.title);
        page.addView(title, block(cw, 0, 0));
        sub = Ui.text(c, 12.5f, Ui.FAINT, Ui.sansMedium);
        sub.setText(subText(s));
        page.addView(sub, block(cw, Ui.dp(5), Ui.dp(14)));

        java.util.List<Story> stories = s.stories;
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
        list.post(this::onViewport);
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

    @Override
    public void append(Relay.Section s) {  // feature: front.deep
        if (currentSection() != s || built >= s.stories.size()) return;
        long now = System.currentTimeMillis();
        int tile = tileWidth(), tileH = tile * 9 / 16 + Ui.dp(96);
        for (int j = built; j < s.stories.size(); j++) addTile(s.stories.get(j), j, now, tile, tileH);
        built = s.stories.size();
        if (sub != null) sub.setText(subText(s));
        updateMore(s);
        list.post(this::onViewport);
    }

    private void updateMore(Relay.Section s) {
        if (more == null) return;
        more.setText(s.next != null ? "More stories on their way…" : "");
        more.setVisibility(s.next != null ? VISIBLE : GONE);
    }

    private Card card(int style, Story s, int index, long now) {
        final Card card = new Card(getContext(), style, images);
        card.setFocusable(false);
        card.bind(s, index, now);
        card.setDim(listener.isDimmed(s.id));
        card.setOnClickListener(v -> {
            Relay.Section sec = currentSection();
            if (sec != null) listener.onOpen(sec, card.index);
        });
        card.setOnLongClickListener(v -> {  // feature: phone.long_press
            chosen = card.story;
            listener.onMenu();
            return true;
        });
        cards.add(card);
        return card;
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

    /** A view's top within the scrolling page. */
    private int topIn(View v) {
        int y = 0;
        for (View p = v; p != null && p != list; p = (View) p.getParent()) {
            y += p.getTop();
            if (!(p.getParent() instanceof View)) break;
        }
        return y;
    }

    private Card firstOnScreen() {
        int y = list.getScrollY();
        for (Card c : cards) {
            if (c.getHeight() == 0) continue;
            int t = topIn(c);
            if (t + c.getHeight() / 2 >= y) return c;
        }
        return null;
    }

    /** Pictures for the cards within a screen of the viewport; the next page near the end. */
    private void onViewport() {
        int y = list.getScrollY(), h = list.getHeight();
        if (h == 0) return;
        for (Card c : cards) {
            int t = topIn(c);
            c.setNear(t + c.getHeight() > y - h && t < y + h * 2);
        }
        Relay.Section s = currentSection();
        View content = list.getChildAt(0);
        if (s != null && s.next != null && content != null && content.getHeight() - (y + h) < h * 2) listener.onNearEnd(s);
        removeCallbacks(cardRest);
        postDelayed(cardRest, CARD_REST_MS);
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

    // ------------------------------------------------------------ views

    /** The scrolling page; reports where it is. */
    private final class Scroll extends ScrollView {
        Scroll(Context c) {
            super(c);
            setVerticalScrollBarEnabled(true);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setBackgroundColor(Ui.PAPER);
            setFillViewport(true);
        }

        @Override
        protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            onViewport();
        }
    }

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
            if (sections.isEmpty()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = e.getY();
                    startX = e.getX();
                    dragging = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getY() - startY;
                    if (!dragging && list.getScrollY() == 0 && dy > slop && dy > Math.abs(e.getX() - startX) * 1.5f) {
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
                    list.setTranslationY(offset);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean go = e.getActionMasked() == MotionEvent.ACTION_UP && offset >= full;
                    dragging = false;
                    list.animate().translationY(0).setDuration(180).withEndAction(() -> {
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
            float shown = list.getTranslationY();
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
