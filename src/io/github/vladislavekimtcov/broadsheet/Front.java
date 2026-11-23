package io.github.vladislavekimtcov.broadsheet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The front page: a rail of sections on the left (Today's sections from Apple News, then Latest
 * and Saved Stories), and the chosen section as a hero, two briefs and rows of three tiles.
 * Moving along the rail switches section after a short pause, so holding Down skims past.
 */
final class Front extends FrameLayout implements Scroller.Watcher {  // feature: front.rail
    interface Listener {
        void onOpen(Relay.Section section, int index);

        /** Shown faded: read on this TV, with "Stories you've read" set to dim. */
        boolean isDimmed(String storyId);

        /** A card got focus (to fetch its story ahead if it stays there). */
        void onFocusStory(Story story);
    }

    private static final int RAIL_DP = 224, GAP_DP = 20, SIDE_DP = 34;

    private final Images images;
    private final Listener listener;
    private final LinearLayout rail, railList;
    private final TextView status;
    private final Scroller page;
    private final LinearLayout grid;
    private final TextView message;
    private final List<RailItem> railItems = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private List<Relay.Section> sections = new ArrayList<>();
    private int current = -1;
    private final Runnable switchSection = new Runnable() {
        @Override
        public void run() {
            View f = findFocus();
            if (f instanceof RailItem) show(((RailItem) f).index, false);
        }
    };

    Front(Context c, Images images, Listener listener) {
        super(c);
        this.images = images;
        this.listener = listener;
        setBackgroundColor(Ui.PAPER);

        rail = new LinearLayout(c);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(Ui.dp(26), Ui.dp(30), Ui.dp(14), Ui.dp(22));
        rail.setBackgroundColor(Ui.RAIL);
        TextView mast = Ui.text(c, 31, Ui.INK, Ui.display);
        mast.setText("Broadsheet");
        rail.addView(mast);
        View rule = new View(c);
        rule.setBackgroundColor(Ui.ACCENT);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(3));
        rl.topMargin = Ui.dp(12);
        rail.addView(rule, rl);
        TextView date = Ui.text(c, 13, Ui.MUTED, Ui.sansMedium);
        date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(new Date()));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = Ui.dp(10);
        dl.bottomMargin = Ui.dp(22);
        rail.addView(date, dl);
        railList = new LinearLayout(c);
        railList.setOrientation(LinearLayout.VERTICAL);
        // Today can have twenty-odd sections (topic and channel groups): the rail scrolls with focus.
        ScrollView railScroll = new ScrollView(c);
        railScroll.setVerticalScrollBarEnabled(false);
        railScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        railScroll.setVerticalFadingEdgeEnabled(true);
        railScroll.setFadingEdgeLength(Ui.dp(24));
        railScroll.addView(railList, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams rsl = new LinearLayout.LayoutParams(-1, 0, 1);
        rsl.bottomMargin = Ui.dp(12);
        rail.addView(railScroll, rsl);
        status = Ui.text(c, 11.5f, Ui.FAINT, Ui.sans);
        status.setLineSpacing(0, 1.25f);
        rail.addView(status);
        addView(rail, new LayoutParams(Ui.dp(RAIL_DP), -1));

        page = new Scroller(c);
        page.setWatcher(this);
        grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(Ui.dp(SIDE_DP), Ui.dp(26), Ui.dp(SIDE_DP), Ui.dp(40));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        page.addView(grid, new ViewGroup.LayoutParams(-1, -2));
        LayoutParams pl = new LayoutParams(-1, -1);
        pl.leftMargin = Ui.dp(RAIL_DP);
        addView(page, pl);

        message = Ui.text(c, 20, Ui.MUTED, Ui.text);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(0, 1.3f);
        message.setPadding(Ui.dp(60), 0, Ui.dp(60), 0);
        message.setVisibility(GONE);
        LayoutParams ml = new LayoutParams(-1, -1);
        ml.leftMargin = Ui.dp(RAIL_DP);
        addView(message, ml);
    }

    boolean hasContent() {
        return !sections.isEmpty();
    }

    void setStatus(String text) {
        status.setText(text);
    }

    /** Shown instead of the grid when there is nothing to show. */
    void showMessage(String text) {
        if (!sections.isEmpty()) {
            setStatus(text);
            return;
        }
        message.setText(text);
        message.setVisibility(VISIBLE);
        page.setVisibility(GONE);
    }

    /** New sections. Keeps the current section (by id) and, if asked, the focused story. */
    void setSections(List<Relay.Section> list, boolean keepFocus) {
        String currentId = current >= 0 && current < sections.size() ? sections.get(current).id : null;
        Story focused = focusedStory();
        restore(list, currentId, keepFocus && focused != null ? focused.id : null, railFocused(), keepFocus);
    }

    /** The story whose card has focus, or null (focus on the rail). */
    Story focusedStory() {
        View f = findFocus();
        return f instanceof Card ? ((Card) f).story : null;
    }

    boolean railFocused() {
        return findFocus() instanceof RailItem;
    }

    String statusText() {
        return status.getText().toString();
    }

    /** Opens a section by id and focuses its first story. */
    void open(String sectionId) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).id.equals(sectionId)) {
                current = -1;
                show(i, true);
                railItems.get(i).post(() -> {
                    if (!cards.isEmpty()) cards.get(0).requestFocus();
                });
                return;
            }
        }
    }

    /** Fades or restores cards after a story was read or the setting changed. */
    void refreshDim() {
        for (Card c : cards) c.setDim(listener.isDimmed(c.story.id));
    }

    /** Sections with a given section and story chosen: after the views were built again. */
    void restore(List<Relay.Section> list, String currentId, String focusedStory, boolean railFocused, boolean keepFocus) {
        sections = list;
        message.setVisibility(GONE);
        page.setVisibility(VISIBLE);
        railList.removeAllViews();
        railItems.clear();
        int keep = 0;
        for (int i = 0; i < list.size(); i++) {
            RailItem item = new RailItem(getContext(), i, list.get(i).title);
            railItems.add(item);
            railList.addView(item, new LinearLayout.LayoutParams(-1, Ui.dp(38)));
            if (list.get(i).id.equals(currentId)) keep = i;
        }
        current = -1;
        if (list.isEmpty()) return;
        show(keep, false);
        if (keepFocus && focusedStory != null) {
            for (Card c : cards) {
                if (c.story.id.equals(focusedStory)) {
                    c.requestFocus();
                    return;
                }
            }
        }
        if (railFocused || keepFocus || findFocus() == null) railItems.get(keep).requestFocus();
    }

    /** Builds the grid for one section. */
    void show(int index, boolean focusFirst) {
        if (index == current || index < 0 || index >= sections.size()) return;
        current = index;
        for (RailItem r : railItems) r.setSelectedSection(r.index == index);
        for (Card c : cards) images.cancel(c);
        cards.clear();
        grid.removeAllViews();
        Relay.Section s = sections.get(index);
        long now = System.currentTimeMillis();
        Context c = getContext();

        TextView title = Ui.text(c, 30, Ui.INK, Ui.display);
        title.setText(s.title);
        grid.addView(title);
        TextView sub = Ui.text(c, 12.5f, Ui.FAINT, Ui.sansMedium);
        sub.setText(s.stories.size() + (s.stories.size() == 1 ? " story" : " stories")
                + ("saved".equals(s.id) ? "  ·  saved in Apple News" : "latest".equals(s.id)
                ? "  ·  newest in the archive" : "more".equals(s.id) ? "  ·  from the archive on the Mac"
                : "  ·  from Apple News Today"));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = Ui.dp(6);
        sl.bottomMargin = Ui.dp(18);
        grid.addView(sub, sl);

        int width = getResources().getDisplayMetrics().widthPixels - Ui.dp(RAIL_DP) - Ui.dp(SIDE_DP) * 2;
        int gap = Ui.dp(GAP_DP);
        int tile = (width - gap * 2) / 3;
        int tileH = tile * 9 / 16 + Ui.dp(96);
        List<Story> stories = s.stories;
        int i = 0;
        if (stories.size() >= 3) {
            LinearLayout row = row(c);
            int heroH = Ui.dp(300);
            Card hero = card(Card.HERO, stories.get(0), 0, now);
            row.addView(hero, new LinearLayout.LayoutParams(tile * 2 + gap, heroH));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            int briefH = (heroH - gap) / 2;
            col.addView(card(Card.BRIEF, stories.get(1), 1, now), new LinearLayout.LayoutParams(tile, briefH));
            LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(tile, briefH);
            second.topMargin = gap;
            col.addView(card(Card.BRIEF, stories.get(2), 2, now), second);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(tile, heroH);
            cl.leftMargin = gap;
            row.addView(col, cl);
            grid.addView(row, rowParams(0));
            i = 3;
        }
        for (; i < stories.size(); i += 3) {
            LinearLayout row = row(c);
            for (int j = i; j < Math.min(i + 3, stories.size()); j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tileH);
                if (j > i) lp.leftMargin = gap;
                row.addView(card(Card.TILE, stories.get(j), j, now), lp);
            }
            grid.addView(row, rowParams(gap));
        }
        RailItem item = railItems.get(index);
        for (Card card : cards) {
            if (isFirstColumn(card)) card.setNextFocusLeftId(item.getId());
        }
        if (!cards.isEmpty()) item.setNextFocusRightId(cards.get(0).getId());
        page.scrollTo(0, 0);
        grid.post(new Runnable() {
            @Override
            public void run() {
                onViewport(page.getScrollY(), page.getScrollY() + page.getHeight());
            }
        });
        if (focusFirst && !cards.isEmpty()) cards.get(0).requestFocus();
    }

    private boolean isFirstColumn(Card card) {
        ViewGroup parent = (ViewGroup) card.getParent();
        return parent != null && parent.indexOfChild(card) == 0 && parent.getParent() == grid;
    }

    private Card card(int style, Story s, int index, long now) {
        final Card card = new Card(getContext(), style, images);
        card.bind(s, index, now);
        card.setDim(listener.isDimmed(s.id));
        card.setOnFocusChangeListener((v, has) -> {
            if (has) listener.onFocusStory(card.story);
        });
        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onOpen(sections.get(current), card.index);
            }
        });
        cards.add(card);
        return card;
    }

    private static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        return row;
    }

    private static LinearLayout.LayoutParams rowParams(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        return lp;
    }

    /** Back from the reader: focus the story that was open (it may have moved with Left/Right). */
    void focusStory(int index) {
        for (Card c : cards) {
            if (c.index == index) {
                c.requestFocus();
                return;
            }
        }
    }

    Relay.Section currentSection() {
        return current >= 0 && current < sections.size() ? sections.get(current) : null;
    }

    @Override
    public void onViewport(int top, int bottom) {
        int margin = getHeight();
        int[] loc = new int[2];
        int[] gridLoc = new int[2];
        grid.getLocationInWindow(gridLoc);
        for (Card c : cards) {
            c.getLocationInWindow(loc);
            int y = loc[1] - gridLoc[1];
            c.setNear(y + c.getHeight() > top - margin && y < bottom + margin);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        View f = findFocus();
        if (f instanceof RailItem && e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                removeCallbacks(switchSection);
                show(((RailItem) f).index, false);
                if (!cards.isEmpty()) cards.get(0).requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** A section name on the rail. Focus switches section after a pause. */
    private final class RailItem extends TextView {
        final int index;
        private boolean selectedSection;
        private final Paint bar = new Paint();

        RailItem(Context c, int index, String title) {
            super(c);
            this.index = index;
            setId(View.generateViewId());
            setFocusable(true);
            setText(title);
            setSingleLine(true);
            setEllipsize(TextUtils.TruncateAt.END);
            setTextSize(16.5f);
            setTypeface(Ui.sansMedium);
            setTextColor(Ui.MUTED);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(14), 0, Ui.dp(8), 0);
            bar.setColor(Ui.ACCENT);
        }

        void setSelectedSection(boolean value) {
            selectedSection = value;
            refresh();
        }

        private void refresh() {
            boolean f = isFocused();
            setTextColor(f || selectedSection ? Ui.INK : Ui.MUTED);
            setBackgroundColor(f ? Ui.SURFACE_HI : 0);
            invalidate();
        }

        @Override
        protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            refresh();
            if (focused && index != current) {
                removeCallbacks(switchSection);
                postDelayed(switchSection, 260);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (selectedSection) c.drawRect(0, Ui.px(9), Ui.px(3), getHeight() - Ui.px(9), bar);
        }
    }
}
