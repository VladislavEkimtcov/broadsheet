package io.github.vladislavekimtcov.broadsheet;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Broadsheet: a news reader for Android TVs and phones that shows Apple News, by way of Apple
 * News Relay on a Mac. Front page and reader live in one window; the reader slides over the
 * front page, the options panel (Menu, or ☰ on a phone) over both, and a story's video over
 * everything.
 *
 * <p>One APK, two front pages: a TV (UI mode television, or no touchscreen) gets {@code Front}
 * (the rail, for the D-pad) in landscape; anything else {@code Feed} (phone/src) and the touch
 * modes of the reader, options and player ({@link Ui#touch}). Everything else is shared.
 */
public final class Main extends Activity implements FrontView.Listener, Reader.Listener, Player.Listener, Options.Listener,
        Events.Listener, Updater.Listener {
    /** Seconds the relay may spend getting a story's body from Apple News before answering. */
    private static final int FETCH_WAIT = 14;
    /** With nothing on screen, look for the relay again this often. */
    private static final long RETRY_MS = 30 * 1000L;
    /** Without the relay's event stream, check the front page this often. */
    private static final long POLL_MS = 10 * 60 * 1000L;
    /** Resting on a card this long counts as looking at it. */
    private static final int CARD_REST_MS = 300;
    /** Screens of Today to scroll on the Mac when refreshing: four found the most (docs/PLAYBOOK.md). */
    private static final int REFRESH_PAGES = 4;

    private static final int[] AUTO_REFRESH_MIN = {25, 60, 0};
    private static final String[] AUTO_REFRESH_NAMES = {"every 25 minutes", "every hour", "off"};
    private static final String[] THEMES = {"auto", "dark", "light"};
    private static final String[] THEME_NAMES = {"Automatic", "Dark", "Light"};
    private static final String[] READ_MODES = {"show", "dim", "hide"};
    private static final String[] READ_NAMES = {"Do nothing", "Dim after reading", "Hide after reading"};
    private static final String[] LAYOUTS = {"dynamic", "big"};
    private static final String[] LAYOUT_NAMES = {"Dynamic", "Big tile"};

    private Relay relay;
    private Images images;
    private Library library;
    private Bodies bodies;
    private Events events;
    private Updater updater;
    /** This TV's name for its interest list at the relay. */
    private String clientId;
    private final Interest interest = new Interest();
    /** Disk work for the stories kept on the TV, one thing at a time and in order. */
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    /** The front page's version at the relay, to tell whether a change is news. */
    private long frontVersion;
    /** A newer front page that came while reading; shown on the way out. */
    private List<Relay.Section> deferred;
    /** Stories the relay has a body for, learnt since the front page came. */
    private final Set<String> bodied = new HashSet<>();
    private final Set<String> headsAsked = new HashSet<>();
    /** Sections with a page on its way. */
    private final Set<String> paging = new HashSet<>();
    /** The open story: the relay job it waits on, when it opened, and what was asked for it. */
    private String waitingJob;
    private long openedAt;
    private boolean restAsked, aheadAsked;
    private SharedPreferences prefs;
    private FrameLayout root;
    private FrontView front;
    private Reader reader;
    private Player player;
    private Options options;

    /** The front page as the relay sent it, and as shown (read stories hidden, "More from" first). */
    private List<Relay.Section> base = new ArrayList<>(), shown = new ArrayList<>();
    private Relay.Section extra;
    private final Set<String> savedIds = new HashSet<>();
    private final Map<String, Boolean> savedNow = new HashMap<>();
    private Relay.Section openSection;
    private int openIndex;
    private float pendingRestore;
    private long loadedAt;
    private boolean loading, refreshing;
    private String message;

    // the options panel's state
    private Story target;
    private Options.Tile saveTile, reactTile, unreadTile, moreTile, sizeTile, themeTile, readTile, layoutTile, continueTile, refreshTile, updateTile, relayTile;
    /** The open story was marked unread: leaving it mustn't count it as read again. */
    private String keptUnread;
    /** The story on screen in the reader, or null while the front page shows. */
    private Story reading;
    /** More stories like this: {title, path, kind, id} per choice, for the story in Options. */
    private final List<String[]> moreChoices = new ArrayList<>();
    /** Each choice's stories, fetched as soon as Options opens (path → list, when, error). */
    private final java.util.Map<String, List<Story>> moreLists = new java.util.HashMap<>();
    private final java.util.Map<String, Long> moreAt = new java.util.HashMap<>();
    private final java.util.Map<String, String> moreErrors = new java.util.HashMap<>();
    private final Set<String> moreLoading = new HashSet<>();
    /** The choice picked before its stories were in: opens when they are. */
    private String[] moreWanted;
    private List<Options.Tile> moreRows;
    private boolean saveBusy, reactBusy;
    private String saveError, reactError;

    private final Runnable retry = this::load;
    private final Runnable themeTick = this::applyTheme;
    /** A change at the relay: reload once the remote has been still for a moment, so the page
     *  doesn't change under someone moving around it. */
    private final Runnable reloadSoon = new Runnable() {
        @Override
        public void run() {
            long still = SystemClock.uptimeMillis() - lastKeyAt;
            if (still < 8000) {
                root.postDelayed(this, 8000 - still);
                return;
            }
            load();
        }
    };
    private long lastKeyAt;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (events.downFor() > 2 * 60 * 1000L) load();
            root.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean tv = isTv();
        Ui.touch = !tv;
        Ui.device = tv ? "TV" : "phone";
        if (tv) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            edgeToEdge();
        }
        Ui.init(this);
        String kind = tv ? "Android TV" : "Android";
        try {
            Net.userAgent = "Broadsheet/" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " (" + kind + ")";
        } catch (PackageManager.NameNotFoundException e) {
            Net.userAgent = "Broadsheet (" + kind + ")";
        }
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        relay = new Relay(this);
        images = new Images(new File(getCacheDir(), "img"));
        library = new Library(getFilesDir());
        bodies = new Bodies(new File(getCacheDir(), "bodies"));
        events = new Events(relay, this);
        updater = new Updater(this, prefs, this);
        clientId = prefs.getString("client", null);
        if (clientId == null) {
            clientId = (Ui.touch ? "phone-" : "tv-") + Long.toHexString(new java.security.SecureRandom().nextLong() & 0xffffffffffL);
            prefs.edit().putString("client", clientId).apply();
        }
        new Thread(() -> images.prune(150L * 1024 * 1024), "prune").start();
        disk.execute(bodies::prune);
        Ui.setLight(wantLight());
        root = new FrameLayout(this);
        setContentView(root);
        if (Ui.touch) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Ui.insetTop = insets.getSystemWindowInsetTop();
                Ui.insetBottom = insets.getSystemWindowInsetBottom();
                Ui.insetLeft = insets.getSystemWindowInsetLeft();
                Ui.insetRight = insets.getSystemWindowInsetRight();
                applyInsets();
                return insets.consumeSystemWindowInsets();
            });
        }
        buildViews();

        relay.cachedFront((page, error) -> {  // feature: front.cache
            if (front.hasContent()) return;  // the relay answered first
            if (page != null && !page.sections.isEmpty()) {
                setBase(page.sections);
                front.setSections(shown, false);
                loadedAt = relay.cachedFrontTime();
                frontVersion = page.version;
                front.setStatus("From " + time(loadedAt) + "\nConnecting to the Mac…");
            } else if (loading) {
                showMessage("Looking for Apple News Relay on your network…");
            }
        });
        load();
    }

    /** A TV: the system says so, or there is nothing to touch. */
    private boolean isTv() {
        UiModeManager ui = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        return (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    /** Touch: the page runs under the status bar and the gesture bar, which keep their icons. */
    private void edgeToEdge() {  // feature: phone.insets
        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        systemBars();
    }

    private void systemBars() {
        if (!Ui.touch) return;
        Window w = getWindow();
        // A gesture bar floats over the page; a bar of buttons gets the page's own ground under it.
        boolean buttons = Ui.insetBottom >= Ui.dp(40) || Ui.insetLeft > 0 || Ui.insetRight > 0;
        w.setNavigationBarColor(buttons || Build.VERSION.SDK_INT < 29 ? Ui.alpha(Ui.PAPER, 0.94f) : 0);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Ui.light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= 0x10;  // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR (API 26; built against 25)
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    private void applyInsets() {
        systemBars();
        root.setPadding(Ui.insetLeft, 0, Ui.insetRight, 0);
        if (front instanceof Insettable) ((Insettable) front).applyInsets();
        reader.applyInsets();
        options.applyInsets();
    }

    /** A view that makes room for the system bars on touch. */
    interface Insettable {
        void applyInsets();
    }

    private void buildViews() {
        root.removeAllViews();
        root.setBackgroundColor(Ui.PAPER);
        if (Ui.touch) {
            Feed f = new Feed(this, images, this);
            f.setLayoutMode(layoutMode());
            front = f;
        } else {
            front = new Front(this, images, this);
        }
        reader = new Reader(this, images, this);
        reader.setTextSizeStep(textStep());
        reader.setVisibility(View.GONE);
        player = new Player(this, this);
        options = new Options(this, this);
        root.addView((View) front);
        root.addView(reader);
        root.addView(player);
        root.addView(options);
        if (updater != null) front.setUpdateNotice(updater.bannerText());
    }

    /** The text size step; 0.1's four sizes (key "size") map onto the ten of 0.2.1. A phone starts
     *  at 17 pt, read at arm's length. */
    private int textStep() {
        if (prefs.contains("text")) return prefs.getInt("text", Reader.STANDARD);
        if (Ui.touch) return 2;
        int old = prefs.getInt("size", 1);
        return Math.max(0, Math.min(Reader.SIZES.length - 1, Reader.STANDARD - 1 + old));
    }

    @Override
    protected void onStart() {
        super.onStart();
        events.start();
        updater.start();
        root.removeCallbacks(poll);
        root.postDelayed(poll, POLL_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyTheme();
        updater.onResume(this);
        // The event stream says when the Mac's page changes; without it, look after ten minutes.
        if (!front.hasContent() || (!events.connected() && System.currentTimeMillis() - loadedAt > POLL_MS)) load();
    }

    @Override
    protected void onStop() {
        super.onStop();
        events.stop();
        updater.stop();
        interest.clear();
        images.cancelWarm();
        root.removeCallbacks(poll);
        root.removeCallbacks(reloadSoon);
        root.removeCallbacks(retry);
        root.removeCallbacks(themeTick);
        if (player.playing()) player.stop();
        leaveStory();
        library.save();
        images.trim();
    }

    private void showMessage(String text) {
        message = text;
        front.showMessage(text);
    }

    /** Says what went wrong; with nothing to show, tries again by itself (OK tries at once). */
    private void fail(String text) {
        if (front.hasContent()) {
            front.showMessage(text);
            return;
        }
        showMessage(text + (Ui.touch ? "\n\nTrying again every 30 seconds. Tap to try now."
                : "\n\nTrying again every 30 seconds. Press OK to try now."));
        root.removeCallbacks(retry);
        root.postDelayed(retry, RETRY_MS);
    }

    private static String time(long ms) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ms));
    }

    // -------------------------------------------------------------- loading

    /** Loads the front page from the relay, finding the relay first if needed. */
    private void load() {
        if (loading) return;
        root.removeCallbacks(retry);
        if (!relay.known()) {
            loading = true;
            relay.discover((url, error) -> {
                loading = false;
                if (url == null) {
                    fail(error + "\n\nStart Apple News Relay on the Mac (apple-news-relay serve --host 0.0.0.0), "
                            + (Ui.touch ? "or enter its address under ☰ → Relay address."
                            : "or put url=http://<mac>:8787 in relay.conf in this app's files folder."));
                    return;
                }
                load();
            });
            return;
        }
        loading = true;
        relay.front((page, error) -> {
            loading = false;
            if (page == null) {
                if ("last known".equals(relay.origin())) {
                    // The Mac may have a new address: look again once.
                    relay.discover((url, e2) -> {
                        if (url != null) load();
                        else fail(error);
                    });
                    return;
                }
                fail(error);
                return;
            }
            if (page.unchanged) {  // nothing new: nothing on screen moves
                loadedAt = System.currentTimeMillis();
                front.setStatus("Updated " + time(loadedAt) + "\nApple News via " + relay.where());
                maybeRefreshToday();
                return;
            }
            if (page.sections.isEmpty()) {
                fail("The relay has no stories yet. Open Apple News on the Mac once.");
                return;
            }
            frontVersion = page.version;
            loadedAt = System.currentTimeMillis();
            if (reader.getVisibility() == View.VISIBLE && front.hasContent()) {
                deferred = page.sections;  // the page under the story changes when the story closes
                return;
            }
            apply(page.sections);
        });
    }

    private void apply(List<Relay.Section> sections) {
        boolean first = !front.hasContent();
        deferred = null;
        setBase(sections);
        savedNow.clear();
        front.setSections(shown, !first);
        front.setStatus("Updated " + time(loadedAt) + "\nApple News via " + relay.where());
        fillOptions();
        maybeRefreshToday();
    }

    // -------------------------------------------------------------- live

    @Override
    public void onEvent(String type, JSONObject data) {  // feature: front.live
        switch (type) {
            case "hello":  // (re)connected: was anything missed?
                if (data.optLong("front_version") != frontVersion && !loading) load();
                break;
            case "front.updated":
                if (data.optLong("version") == frontVersion) break;
                root.removeCallbacks(reloadSoon);
                root.postDelayed(reloadSoon, 1500);  // changes come in bursts
                break;
            case "article.body_captured": {
                String id = data.optString("id");
                bodied.add(id);
                Article a = reader.article();
                if (reading != null && id.equals(reading.id) && waitingJob == null && a != null
                        && "summary".equals(a.completeness)) {
                    fetchOpen(reading);  // it came after all
                } else if (interest.wants(id)) {
                    pullHead(id);
                }
                break;
            }
            case "job.updated":
                if (waitingJob != null && waitingJob.equals(data.optString("id")) && "running".equals(data.optString("state"))) {
                    reader.setStage("Apple News is fetching it on the Mac…");
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onStream(boolean up) {
        if (up && !front.hasContent() && !loading) load();
    }

    private void setBase(List<Relay.Section> sections) {
        base = sections;
        savedIds.clear();
        for (Relay.Section s : sections) {
            if (!"saved".equals(s.id)) continue;
            for (Story st : s.stories) savedIds.add(st.id);
        }
        shown = arrange();
    }

    /** The sections to show: "More from …" first, read stories left out when asked. */
    private List<Relay.Section> arrange() {
        List<Relay.Section> out = new ArrayList<>();
        if (extra != null) out.add(extra);
        boolean hide = "hide".equals(readMode());
        for (Relay.Section s : base) {
            if (!hide || "saved".equals(s.id)) {
                out.add(s);
                continue;
            }
            List<Story> keep = new ArrayList<>();
            for (Story st : s.stories) if (!library.isRead(st.id)) keep.add(st);
            if (keep.isEmpty()) continue;
            Relay.Section copy = new Relay.Section(s.id, s.title, keep);
            copy.next = s.next;
            copy.total = s.total;
            copy.today = s.today;
            out.add(copy);
        }
        return out;
    }

    private int autoRefreshStep() {
        return Math.max(0, Math.min(AUTO_REFRESH_MIN.length - 1, prefs.getInt("auto_refresh", 0)));
    }

    /** Asks the Mac to refresh Today now and then; reloads quietly when it's done. */
    private void maybeRefreshToday() {  // feature: opt.auto_refresh
        int minutes = AUTO_REFRESH_MIN[autoRefreshStep()];
        long last = prefs.getLong("refreshed", 0);
        if (minutes == 0 || refreshing || System.currentTimeMillis() - last < minutes * 60 * 1000L) return;
        refreshNow();
    }

    private void refreshNow() {
        if (refreshing) return;
        refreshing = true;
        prefs.edit().putLong("refreshed", System.currentTimeMillis()).apply();
        fillOptions();
        relay.refreshToday(REFRESH_PAGES, (jobId, error) -> {
            if (jobId == null) {
                refreshing = false;
                fillOptions();
                return;
            }
            front.setStatus("Updated " + time(loadedAt) + "\nRefreshing Apple News on the Mac…");
            relay.job(jobId, 60, (state, e) -> {
                refreshing = false;
                load();
            });
        });
    }

    // ------------------------------------------------------------- reading

    @Override
    public void onOpen(Relay.Section section, int index) {
        openSection = section;
        openIndex = index;
        reader.resetSlide();
        reader.setVisibility(View.VISIBLE);
        reader.bringToFront();
        player.bringToFront();
        options.bringToFront();
        open(section.stories.get(index));
    }

    @Override
    public boolean isDimmed(String storyId) {
        return "dim".equals(readMode()) && library.isRead(storyId);
    }

    private void open(final Story s) {
        leaveStory();
        keptUnread = null;
        reading = s;
        reader.show(s);
        float at = library.position(s.id);
        pendingRestore = at > 0.04f && at < 0.95f ? at : 0f;
        library.setPosition(s, at);
        library.save();
        waitingJob = null;
        restAsked = aheadAsked = false;
        openedAt = SystemClock.uptimeMillis();
        disk.execute(() -> {  // feature: reader.body_cache
            final JSONObject kept = bodies.get(s.id);
            root.post(() -> {
                if (reader.story() != s) return;
                if (kept == null) {
                    fetchOpen(s);
                    return;
                }
                arrived(s, new Article(kept), null, "kept on the TV");
                // Kept text doesn't change; whether it's saved or liked may have.
                relay.articleState(s.id, (st, e) -> {
                    if (st == null || reader.story() != s) return;
                    library.setReaction(s.id, Story.str(st, "reaction"));
                    if (st.has("saved")) savedNow.put(s.id, st.optBoolean("saved"));
                    fillOptions();
                });
            });
        });
    }

    /** One trip: the relay waits for Apple News (up to {@link #FETCH_WAIT} s) and sends the opening. */
    private void fetchOpen(final Story s) {
        relay.article(s.id, FETCH_WAIT, Relay.HEAD_CHARS, (a, error) -> {
            if (reader.story() != s) return;
            if (a == null || a.pendingJob == null) {
                arrived(s, a, error, "from the Mac");
                return;
            }
            waitingJob = a.pendingJob;
            reader.setStage("queued".equals(a.jobState) ? "Waiting its turn on the Mac…" : "Still fetching: big stories and Apple News+ take longer…");
            follow(s, a.pendingJob, 0);
        });
    }

    /** Waits for the relay's job on a story, then shows what came of it. */
    private void follow(final Story s, final String job, final int round) {
        relay.job(job, 12, (state, e) -> {
            if (reader.story() != s) return;
            if ("running".equals(state) || "queued".equals(state)) {
                if (round < 2) {
                    reader.setStage("Still fetching: big stories and Apple News+ take longer…");
                    follow(s, job, round + 1);
                    return;
                }
            }
            waitingJob = null;
            relay.article(s.id, 0, Relay.HEAD_CHARS, (again, e2) -> {
                if (reader.story() != s) return;
                arrived(s, again, e2, "from the Mac");
            });
        });
    }

    // ------------------------------------------------------------ ahead of time

    private Story focusedStory;
    private int focusedIndex;
    /** Resting on a card: it and the cards around it. */
    private final Runnable aheadFocused = () -> {  // feature: reader.prefetch
        Relay.Section sec = front.currentSection();
        if (focusedStory == null || sec == null || reader.getVisibility() == View.VISIBLE) return;
        int[] around = Ladder.around(focusedIndex, sec.stories.size());
        interest.want(pick(sec, around));
        for (int k = 0; k < Math.min(2, around.length); k++) pullHeadIfThere(sec.stories.get(around[k]));
    };

    @Override
    public void onFocusStory(Story story, int index) {
        focusedStory = story;
        focusedIndex = index;
        root.removeCallbacks(aheadFocused);
        root.postDelayed(aheadFocused, CARD_REST_MS);  // resting on it, not skimming past
    }

    @Override
    public void onRailFocus(int index, int direction) {  // feature: front.warm
        if (index < 0 || index >= shown.size()) return;
        int[] hero = front.heroSize();
        int tile = front.tileWidth();
        int[] beside = Ladder.railNeighbours(index, shown.size(), direction);
        List<String> urls = new ArrayList<>();
        for (int n : beside) {
            Relay.Section s = shown.get(n);
            boolean heroed = s.stories.size() >= 3;
            for (int k = 0; k < Math.min(6, s.stories.size()) && urls.size() < 12; k++) {
                if (heroed && (k == 1 || k == 2)) continue;  // briefs have no picture
                urls.add(s.stories.get(k).imageFor(heroed && k == 0 ? hero[0] : tile));
            }
        }
        images.warm(urls);
        if (beside.length > 0 && shown.get(beside[0]).stories.size() >= 3) {
            // The way the reader is going: that section's hero ready to draw.
            images.preload(shown.get(beside[0]).stories.get(0).imageFor(hero[0]), hero[0], hero[1], false);
        }
    }

    @Override
    public void onRailRest(int index) {
        if (index < 0 || index >= shown.size() || reader.getVisibility() == View.VISIBLE) return;
        Relay.Section s = shown.get(index);
        List<Story> first = new ArrayList<>(s.stories.subList(0, Math.min(3, s.stories.size())));
        interest.want(first);
        for (Story st : first) pullHeadIfThere(st);
    }

    @Override
    public void onNearEnd(Relay.Section section) {
        loadMore(section);
    }

    /** The next page of a section, under the cards already there. */
    private void loadMore(final Relay.Section sec) {  // feature: front.deep
        if (sec.next == null || !paging.add(sec.id)) return;
        relay.page(sec.next, (page, e) -> {
            paging.remove(sec.id);
            if (page == null) return;
            Relay.Section whole = null;
            for (Relay.Section b : base) if (b.id.equals(sec.id)) whole = b;
            if (whole != null && whole != sec) {
                add(whole, page.stories, false);
                whole.next = page.next;
            }
            add(sec, page.stories, whole != null && whole != sec);
            sec.next = page.next;
            front.append(sec);
        });
    }

    private void add(Relay.Section sec, List<Story> more, boolean unreadOnly) {
        Set<String> have = new HashSet<>();
        for (Story st : sec.stories) have.add(st.id);
        for (Story st : more) if (have.add(st.id) && (!unreadOnly || !library.isRead(st.id))) sec.stories.add(st);
    }

    @Override
    public void onNearLoadedEnd() {
        loadRest(reading);
    }

    /** The rest of the open story, once the reader gets near the end of its opening. */
    private void loadRest(final Story s) {  // feature: reader.progressive
        final int from = reader.nextBlock();
        if (s == null || reader.story() != s || from < 0 || restAsked) return;
        restAsked = true;
        relay.articleRest(s.id, from, (rest, e) -> {
            restAsked = false;
            if (reader.story() != s || rest == null) return;
            reader.appendBlocks(rest);
            final JSONObject json = rest.json;
            disk.execute(() -> bodies.merge(s.id, json));
            if (pendingRestore > 0 && reader.nextBlock() < 0) {
                reader.restoreFraction(pendingRestore);
                pendingRestore = 0;
            }
        });
    }

    @Override
    public void onNearStoryEnd() {
        if (aheadAsked || openSection == null) return;
        aheadAsked = true;
        int n = openSection.stories.size();
        interest.want(pick(openSection, Ladder.reading(openIndex, n)));
        if (openIndex + 1 < n) {
            Story next = openSection.stories.get(openIndex + 1);
            pullHeadIfThere(next);
            images.warm(java.util.Collections.singletonList(next.imageFor(Ui.dp(700))));
        }
        if (openSection.next != null && openIndex + 3 >= n) loadMore(openSection);
    }

    private static List<Story> pick(Relay.Section sec, int[] indexes) {
        List<Story> out = new ArrayList<>();
        for (int i : indexes) out.add(sec.stories.get(i));
        return out;
    }

    /** Whether the relay has the story's body (so the TV can take its opening now). */
    private boolean onRelay(Story s) {
        return bodied.contains(s.id) || !"summary".equals(s.completeness);
    }

    private void pullHeadIfThere(Story s) {
        if (onRelay(s)) pullHead(s.id);
    }

    /** Puts a story's opening on the TV, if the relay has it and the TV doesn't. */
    private void pullHead(final String id) {
        if (!relay.modern() || headsAsked.size() > 500 || !headsAsked.add(id)) return;
        disk.execute(() -> {
            if (bodies.has(id)) return;
            relay.head(id, (data, e) -> {
                if (data != null) disk.execute(() -> bodies.put(data));
            });
        });
    }

    /**
     * The stories the reader may open next, sent to the relay (which has Apple News fetch them in
     * that order). Only stories the relay has no body for go; a list the same as the last isn't
     * sent again, and lists go at most every 400 ms, the newest replacing the one before.
     */
    private final class Interest {
        private List<String> sent = new ArrayList<>(), pending;
        private long sentAt;
        private final Runnable send = this::sendNow;

        void want(List<Story> stories) {
            List<String> ids = new ArrayList<>();
            for (Story st : stories) if (st != null && !onRelay(st) && !ids.contains(st.id)) ids.add(st.id);
            if (ids.equals(pending != null ? pending : sent)) return;
            pending = ids;
            root.removeCallbacks(send);
            root.postDelayed(send, Math.max(0, sentAt + 400 - SystemClock.uptimeMillis()));
        }

        boolean wants(String id) {
            return sent.contains(id);
        }

        private void sendNow() {
            if (pending == null) return;
            sent = pending;
            pending = null;
            sentAt = SystemClock.uptimeMillis();
            relay.interest(clientId, sent, (cached, e) -> {
                if (cached == null) return;
                bodied.addAll(cached);
                for (String id : cached) pullHead(id);
            });
        }

        void clear() {
            root.removeCallbacks(send);
            pending = null;
            if (sent.isEmpty()) return;
            sent = new ArrayList<>();
            relay.clearInterest(clientId);
        }
    }

    private void arrived(Story s, Article a, String error, String source) {
        reader.setArticle(a, error);
        if (a == null) return;
        Log.i("Broadsheet", "open " + s.id + " " + (SystemClock.uptimeMillis() - openedAt) + " ms " + source
                + (a.nextBlock >= 0 ? " (opening)" : "") + " " + a.completeness);
        if (a.pendingJob == null && !"summary".equals(a.completeness)) {
            bodied.add(s.id);
            if (!"kept on the TV".equals(source)) {
                final JSONObject json = a.json;
                disk.execute(() -> bodies.put(json));
            }
            // Right should open at once: the next story, fetched ahead.
            if (openSection != null && openIndex + 1 < openSection.stories.size()) {
                Story next = openSection.stories.get(openIndex + 1);
                interest.want(java.util.Collections.singletonList(next));
                pullHeadIfThere(next);
            }
        }
        library.setReaction(s.id, a.reaction);
        if (pendingRestore > 0 && a.pendingJob == null) {
            if (a.nextBlock >= 0) {
                loadRest(s);  // the place is somewhere in the whole story
            } else {
                reader.restoreFraction(pendingRestore);
                pendingRestore = 0;
            }
        }
    }

    /** Remembers how far into the open story the reader got. */
    private void leaveStory() {
        Story s = reading;
        if (s == null || reader.story() != s || reader.article() == null) return;
        if (s.id.equals(keptUnread)) return;
        float f = reader.fraction();
        library.setPosition(s, f > 0.97f ? 1f : f);
    }

    @Override
    public boolean onMove(int delta) {
        if (openSection == null) return false;
        int next = openIndex + delta;
        if (next < 0 || next >= openSection.stories.size()) return false;
        openIndex = next;
        open(openSection.stories.get(next));
        return true;
    }

    @Override
    public Story peekStory(int delta) {
        if (openSection == null) return null;
        int next = openIndex + delta;
        return next >= 0 && next < openSection.stories.size() ? openSection.stories.get(next) : null;
    }

    @Override
    public void onClose() {
        reader.resetSlide();
        leaveStory();
        reading = null;
        waitingJob = null;
        library.save();
        reader.setVisibility(View.GONE);
        if (deferred != null) {
            apply(deferred);  // the page changed while reading
        } else if (front.currentSection() == openSection) {
            front.focusStory(openIndex);
        }
        front.refreshDim();
    }

    @Override
    public void onPlay(String stream, String title) {
        if (stream == null) {
            Toast.makeText(this, "The publisher didn't share this video outside Apple News.", Toast.LENGTH_LONG).show();
            return;
        }
        player.play(stream, title);
    }

    @Override
    public void onPlayerClosed() {
        applyTheme();  // a switch at sunset waits for the video to end
    }

    // ---------------------------------------------------------- appearance

    private String themeMode() {
        String m = prefs.getString("theme", "auto");
        return m == null ? "auto" : m;
    }

    private boolean wantLight() {
        switch (themeMode()) {
            case "light":
                return true;
            case "dark":
                return false;
            default:
                return !Sun.dark(System.currentTimeMillis(), TimeZone.getDefault());
        }
    }

    /** Light by day and dark after sunset (or as chosen); builds the views again on a change. */
    private void applyTheme() {  // feature: theme.sun
        root.removeCallbacks(themeTick);
        if ("auto".equals(themeMode())) {
            long now = System.currentTimeMillis();
            long next = Sun.nextChange(now, TimeZone.getDefault());
            root.postDelayed(themeTick, Math.max(60 * 1000L, next - now + 30 * 1000L));
        }
        boolean light = wantLight();
        if (light == Ui.light || player.playing()) return;
        Ui.setLight(light);
        systemBars();
        rebuild();
    }

    /** New views in the new colours, with everything where it was. */
    private void rebuild() {
        Relay.Section section = front.currentSection();
        Story focused = front.focusedStory();
        boolean rail = front.railFocused();
        String status = front.statusText();
        boolean reading = reader.getVisibility() == View.VISIBLE;
        Story story = reader.story();
        Article article = reader.article();
        float fraction = reader.fraction();
        boolean optionsOpen = options.isOpen();
        int optionIndex = options.selectedIndex();
        buildViews();
        if (!shown.isEmpty()) {
            front.restore(shown, section != null ? section.id : null, focused != null ? focused.id : null, rail, true);
        } else if (message != null) {
            front.showMessage(message);
        }
        front.setStatus(status);
        if (reading && story != null) {
            reader.setVisibility(View.VISIBLE);
            reader.show(story);
            if (article != null) reader.setArticle(article, null);
            reader.restoreFraction(fraction);
        }
        if (Ui.touch) applyInsets();
        if (optionsOpen) {
            openOptions(target, false);
            options.select(optionIndex);
        }
    }

    private String sunHint() {
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        boolean dark = Sun.dark(now, tz);
        double next = Sun.next(now, tz, dark);
        if (Double.isNaN(next)) return dark ? "Dark: the sun stays down today" : "Light: the sun stays up today";
        return (dark ? "Dark until sunrise at " : "Light until sunset at ") + time((long) next);
    }

    // ------------------------------------------------------------- options

    private String readMode() {
        String m = prefs.getString("read", "show");
        return m == null ? "show" : m;
    }

    private String layoutMode() {  // feature: phone.layout
        String m = prefs.getString("layout", "dynamic");
        return "big".equals(m) ? "big" : "dynamic";
    }

    private void setLayoutMode(String mode) {
        if (mode.equals(layoutMode())) return;
        prefs.edit().putString("layout", mode).apply();
        if (front instanceof Feed) ((Feed) front).setLayoutMode(mode);
        fillOptions();
    }

    private static int indexOf(String[] list, String value) {
        for (int i = 0; i < list.length; i++) if (list[i].equals(value)) return i;
        return 0;
    }

    /** The story the panel acts on: the one being read, or the focused card. */
    private Story optionsTarget() {
        return reader.getVisibility() == View.VISIBLE ? reader.story() : front.focusedStory();
    }

    private Article targetArticle() {
        Article a = reader.getVisibility() == View.VISIBLE ? reader.article() : null;
        return a != null && target != null && a.story != null && a.story.id.equals(target.id) ? a : null;
    }

    private boolean isSaved(Story s) {
        Boolean now = savedNow.get(s.id);
        if (now != null) return now;
        Article a = targetArticle();
        if (a != null) return a.saved;
        return savedIds.contains(s.id);
    }

    private void openOptions(Story s, boolean slide) {
        if (s != target) saveError = reactError = null;
        moreWanted = null;
        moreRows = null;
        target = s;
        moreChoices.clear();
        if (s != null) {
            if (s.channelId != null && s.channel != null) {
                moreChoices.add(new String[] {"More from " + s.channel, "/v1/channels/" + s.channelId + "/articles?limit=45",  // feature: opt.more_from
                        "channels", s.channelId});
            }
            Article a = targetArticle();
            if (a != null) {
                for (String[] t : a.topics) {
                    moreChoices.add(new String[] {"More on " + t[1], "/v1/topics/" + t[0] + "/articles?limit=45", "topics", t[0]});
                }
            }
            moreChoices.add(new String[] {"Related stories", "/v1/articles/" + s.id + "/related", null, null});  // feature: opt.related
        }
        for (String[] c : moreChoices) preloadMore(c);

        saveTile = new Options.Tile("☆", "Save story on Mac");  // feature: opt.save
        saveTile.onOk = this::toggleSave;
        reactTile = new Options.Tile("♥", "Like or dislike");  // feature: opt.react
        reactTile.segments = new String[] {"More like this", "Fewer like this"};
        reactTile.segmentIcons = new int[] {Icons.THUMB_UP, Icons.THUMB_DOWN};
        reactTile.checked = new boolean[2];
        reactTile.onOk = this::sendReaction;
        String r = s == null ? null : library.reaction(s.id);
        reactTile.segment = "dislike".equals(r) ? 1 : 0;
        unreadTile = new Options.Tile("○", "Mark as unread");  // feature: opt.unread
        unreadTile.onOk = this::toggleRead;
        moreTile = new Options.Tile("≡", "More stories like this");
        moreTile.onOk = this::openMoreMenu;
        moreTile.opens = true;
        sizeTile = new Options.Tile("Aa", "Text size");  // feature: opt.text_size
        sizeTile.slider = Reader.SIZES.length;
        sizeTile.onStep = d -> {
            int step = Math.max(0, Math.min(Reader.SIZES.length - 1, reader.sizeStep() + d));
            if (step == reader.sizeStep()) return;
            prefs.edit().putInt("text", step).apply();
            reader.setTextSizeStep(step);
            if (reader.getVisibility() == View.VISIBLE) reader.relayout();
            fillOptions();
        };
        themeTile = new Options.Tile("◐", "Dark mode");  // feature: opt.theme
        themeTile.onStep = d -> {
            int i = Math.max(0, Math.min(THEMES.length - 1, indexOf(THEMES, themeMode()) + d));
            prefs.edit().putString("theme", THEMES[i]).apply();
            fillOptions();
            applyTheme();
        };
        readTile = new Options.Tile("◉", "Stories you've read");  // feature: opt.read_mode
        readTile.onStep = d -> {
            int i = Math.max(0, Math.min(READ_MODES.length - 1, indexOf(READ_MODES, readMode()) + d));
            if (READ_MODES[i].equals(readMode())) return;
            prefs.edit().putString("read", READ_MODES[i]).apply();
            shown = arrange();
            if (!shown.isEmpty()) front.setSections(shown, true);
            front.refreshDim();
            fillOptions();
        };
        layoutTile = null;
        if (Ui.touch) {
            layoutTile = new Options.Tile("▤", "Front page layout");  // feature: phone.layout
            layoutTile.segments = LAYOUT_NAMES;
            layoutTile.checked = new boolean[2];
            layoutTile.onOk = () -> setLayoutMode(LAYOUTS[layoutTile.segment]);
        }
        boolean reading = reader.getVisibility() == View.VISIBLE;
        final Story unfinished = reading ? null : library.unfinished();
        continueTile = null;
        if (unfinished != null) {
            continueTile = new Options.Tile("▶", "Continue reading");
            continueTile.onOk = () -> {
                options.close();
                continueReading(unfinished);
            };
        }
        Options.Tile webTile = null;
        if (Ui.touch && s != null && s.link != null) {
            // A phone has a browser at hand: the publisher's own page, for links and embeds.
            final String link = s.link;
            webTile = new Options.Tile("↗", "Open in the browser");  // feature: win.browser
            webTile.onOk = () -> {
                options.close();
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)));
                } catch (RuntimeException e) {
                    Toast.makeText(this, "No browser to open it in.", Toast.LENGTH_LONG).show();
                }
            };
        }
        refreshTile = new Options.Tile("↻", "Refresh now");  // feature: opt.refresh
        refreshTile.onOk = this::refreshNow;
        refreshTile.onStep = d -> {
            int i = Math.max(0, Math.min(AUTO_REFRESH_MIN.length - 1, autoRefreshStep() + d));
            prefs.edit().putInt("auto_refresh", i).apply();
            fillOptions();
        };
        updateTile = new Options.Tile("↓", "Updates");  // feature: opt.update

        List<Options.Tile> tiles = new ArrayList<>();
        tiles.add(saveTile);
        tiles.add(reactTile);
        tiles.add(unreadTile);
        tiles.add(moreTile);
        if (webTile != null) tiles.add(webTile);
        tiles.add(sizeTile);
        tiles.add(themeTile);
        tiles.add(readTile);
        if (layoutTile != null) tiles.add(layoutTile);
        if (continueTile != null) tiles.add(continueTile);
        tiles.add(refreshTile);
        tiles.add(updateTile);
        relayTile = null;
        if (!Ui.touch) {
            // The TV: the last tile; OK opens a box with the keyboard (the phone has the field itself).
            relayTile = new Options.Tile("⌂", "Relay address");  // feature: phone.relay_field
            relayTile.opens = true;
            relayTile.onOk = this::editRelay;
            tiles.add(relayTile);
        }
        fillTiles();
        String about = s == null ? (Ui.touch ? "Long-press a story to save it, like it or find more like it."
                : "Choose a story to save it, like it or find more like it.")
                : (s.channel != null ? s.channel + "  ·  " : "") + s.title;
        options.open(tiles, about, slide);
        if (Ui.touch) options.addField(relayField(null));
        relay.handoff((h, error) -> {  // feature: opt.handoff
            if (!options.isOpen() || h == null) return;
            final Story mac = Story.from(h.optJSONObject("article"));
            if (mac == null || (reader.getVisibility() == View.VISIBLE && reader.story() != null
                    && reader.story().id.equals(mac.id))) {
                return;  // nothing on offer, or already reading it here
            }
            Options.Tile t = new Options.Tile(null, "On the Mac: " + (mac.channel != null ? mac.channel : "Apple News"));
            t.vectorIcon = Icons.LAPTOP;
            long seen = Dates.parse(Story.str(h, "seen_at"));
            t.value = h.optBoolean("on_screen") ? "now" : Dates.ago(seen, System.currentTimeMillis());
            t.hint = mac.title + (Ui.touch ? "  ·  Tap to read it here" : "  ·  OK reads it here");
            t.onOk = () -> {
                options.close();
                continueReading(mac);
            };
            options.addTop(t);
        });
        relay.status((o, error) -> {
            if (!options.isOpen()) return;
            if (o == null) {
                options.setFooter("The Mac isn't answering" + (loadedAt > 0 ? " · showing the " + time(loadedAt) + " page" : ""));
                return;
            }
            JSONObject news = o.optJSONObject("news_app"), jobs = o.optJSONObject("jobs"), acct = o.optJSONObject("account");
            // Stories fetched ahead aren't anyone waiting.
            int waiting = jobs == null ? 0 : Math.max(0, jobs.optInt("queued") + jobs.optInt("running") - jobs.optInt("warming"));
            options.setFooter("Mac connected  ·  News " + (news != null && news.optBoolean("running") ? "open" : "closed")
                    + "  ·  " + (waiting == 0 ? "nothing waiting" : waiting + " waiting")
                    + (acct != null && acct.optBoolean("news_plus") ? "  ·  Apple News+" : ""));
        });
    }

    private void fillOptions() {
        if (!options.isOpen()) return;
        fillTiles();
        options.refresh();
    }

    /** Words on every tile, from the state now. */
    private void fillTiles() {
        Story s = target;
        boolean has = s != null;
        String pick = Ui.touch ? "Long-press a story first" : "Choose a story first";

        saveTile.enabled = has && !saveBusy;
        boolean saved = has && isSaved(s);
        saveTile.icon = saved ? "★" : "☆";
        saveTile.label = saveBusy ? (saved ? "Removing…" : "Saving…") : saved ? "Unsave story on Mac" : "Save story on Mac";
        saveTile.value = saved && !saveBusy ? "✓" : "";
        saveTile.hint = !has ? pick : saveError;
        if (saveBusy) saveTile.enabled = true;  // still selectable; OK waits

        String r = has ? library.reaction(s.id) : null;
        reactTile.enabled = has;
        reactTile.checked[0] = "like".equals(r);
        reactTile.checked[1] = "dislike".equals(r);
        reactTile.value = reactBusy ? "Sending…" : "like".equals(r) ? "Liked" : "dislike".equals(r) ? "Disliked" : "";
        reactTile.hint = !has ? pick : reactError;

        boolean read = has && library.isRead(s.id) && !s.id.equals(keptUnread);
        unreadTile.enabled = has;
        unreadTile.icon = read ? "○" : "●";
        unreadTile.label = read ? "Mark as unread" : "Mark as read";
        unreadTile.hint = !has ? pick : null;

        moreTile.enabled = has && !moreChoices.isEmpty();
        moreTile.hint = !has ? pick : null;
        fillMoreRows();

        int step = reader.sizeStep();
        sizeTile.position = step;
        float pt = Reader.SIZES[step];
        sizeTile.value = (step == 0 ? "Close reading  ·  " : step == Reader.SIZES.length - 1 ? "Across the room  ·  "
                : step == Reader.STANDARD ? "Standard  ·  " : "") + (pt == Math.round(pt) ? String.valueOf(Math.round(pt)) : String.valueOf(pt)) + " pt";
        sizeTile.hint = null;

        themeTile.value = THEME_NAMES[indexOf(THEMES, themeMode())];
        themeTile.hint = "auto".equals(themeMode()) ? sunHint() : Ui.touch ? "Automatic follows sunset" : "◀ Automatic follows sunset";

        readTile.value = "";
        readTile.hint = READ_NAMES[indexOf(READ_MODES, readMode())] + (Ui.touch ? "" : "  ·  ◀ ▶");
        if (layoutTile != null) {
            boolean big = "big".equals(layoutMode());
            layoutTile.segment = big ? 1 : 0;
            layoutTile.checked[0] = !big;
            layoutTile.checked[1] = big;
            layoutTile.hint = big ? "Hero, briefs and large picture tiles"
                    : "Smart mix of 2-up tiles, compact rows and briefs";
        }

        if (continueTile != null) {
            Story u = library.unfinished();
            continueTile.value = u != null ? Math.round(library.position(u.id) * 100) + "%" : "";
            continueTile.hint = u != null ? u.title : "";
        }

        refreshTile.value = loadedAt > 0 ? "Updated " + time(loadedAt) : "";
        refreshTile.hint = refreshing ? "Refreshing Apple News on the Mac…"
                : "Automatically " + AUTO_REFRESH_NAMES[autoRefreshStep()] + (Ui.touch ? "  ·  ‹ › how often" : "  ·  ◀ ▶");
        fillUpdateTile();
        if (relayTile != null) {
            String manual = relay.manualUrl().replaceFirst("^https?://", "");
            relayTile.value = manual.isEmpty() ? "Automatic" : manual;
            relayTile.hint = !relay.known() ? "Not found yet" : manual.isEmpty() ? "Found by itself: " + relay.where() : "Set by hand";
        }
        if (Ui.touch) {
            // The ‹ › next to the value change how often, so the value says that.
            refreshTile.value = new String[] {"25 min", "Hourly", "Off"}[autoRefreshStep()];
            refreshTile.hint = refreshing ? "Refreshing Apple News on the Mac…"
                    : (loadedAt > 0 ? "Updated " + time(loadedAt) + "  ·  " : "") + "Tap refreshes now";
        }
    }

    private void fillUpdateTile() {
        if (updateTile == null) return;
        Updater.State st = updater.state();
        if (st == Updater.State.READY) {
            updateTile.enabled = true;
            updateTile.label = "Install update v" + updater.remoteVersion();
            updateTile.value = "Ready";
            updateTile.hint = updater.hintText();
            updateTile.onOk = () -> {
                options.close();
                updater.install(this);
            };
        } else if (st == Updater.State.CHECKING || st == Updater.State.DOWNLOADING) {
            updateTile.enabled = false;
            updateTile.label = "Updates";
            updateTile.value = st == Updater.State.DOWNLOADING ? "Downloading…" : "Checking…";
            updateTile.hint = updater.hintText();
            updateTile.onOk = null;
        } else {
            updateTile.enabled = true;
            updateTile.label = "Updates";
            updateTile.value = "v" + updater.currentVersion() + (BuildConfig.AUTO_UPDATE ? "" : " · dev");
            updateTile.hint = updater.hintText();
            updateTile.onOk = () -> updater.check(true);
        }
    }

    /** Read on this TV or new again: what dims (or hides) it on the front page. */
    private void toggleRead() {
        Story s = target;
        if (s == null) return;
        boolean reading = reader.getVisibility() == View.VISIBLE && reader.story() != null && reader.story().id.equals(s.id);
        if (library.isRead(s.id) && !s.id.equals(keptUnread)) {
            library.markUnread(s.id);
            if (reading) keptUnread = s.id;
        } else {
            library.markRead(s.id);
            if (s.id.equals(keptUnread)) keptUnread = null;
        }
        library.save();
        if ("hide".equals(readMode()) && !reading) {
            shown = arrange();
            if (!shown.isEmpty()) front.setSections(shown, true);
        }
        front.refreshDim();
        fillOptions();
    }

    private void toggleSave() {
        final Story s = target;
        if (s == null || saveBusy) return;
        final boolean want = !isSaved(s);
        saveBusy = true;
        saveError = null;
        fillOptions();
        relay.setSaved(s.id, want, (result, error) -> {
            saveBusy = false;
            if (result != null) {
                savedNow.put(s.id, result.optBoolean("saved", want));
                load();  // Saved Stories on the rail
            } else {
                saveError = error;
            }
            fillOptions();
        });
    }

    private void sendReaction() {
        final Story s = target;
        if (s == null || reactBusy) return;
        String want = reactTile.segment == 0 ? "like" : "dislike";
        if (want.equals(library.reaction(s.id))) want = null;  // pressed again: take it back
        reactBusy = true;
        reactError = null;
        fillOptions();
        final String asked = want;
        relay.setReaction(s.id, want, (result, error) -> {
            reactBusy = false;
            if (result != null) library.setReaction(s.id, result.isNull("reaction") ? null : result.optString("reaction", asked));
            else reactError = error;
            library.save();
            fillOptions();
        });
    }

    // ------------------------------------------------------- more stories like this

    /** Fetches a choice's stories ahead (once every ten minutes), with their pictures and the
     *  first openings, so picking it in the pop-up shows the section at once. */
    private void preloadMore(final String[] c) {  // feature: opt.more_menu
        Long at = moreAt.get(c[1]);
        if (moreLoading.contains(c[1]) || (at != null && SystemClock.uptimeMillis() - at < 10 * 60_000L)) return;
        moreLoading.add(c[1]);
        moreErrors.remove(c[1]);
        relay.stories(c[1], (list, error) -> {
            moreLoading.remove(c[1]);
            if (list == null || list.isEmpty()) {
                moreErrors.put(c[1], list == null ? error : "Nothing in the archive yet");
                if (moreWanted != null && moreWanted[1].equals(c[1])) moreWanted = null;
            } else {
                moreLists.put(c[1], list);
                moreAt.put(c[1], SystemClock.uptimeMillis());
                warmMore(list);
                if (moreWanted != null && moreWanted[1].equals(c[1]) && options.isOpen()) {
                    openMore(moreWanted);
                    return;
                }
            }
            fillOptions();
        });
    }

    /** The pictures the section's first cards draw, and the first three openings. */
    private void warmMore(List<Story> list) {
        int[] hero = front.heroSize();
        int tile = front.tileWidth();
        boolean heroed = list.size() >= 3;
        List<String> urls = new ArrayList<>();
        for (int k = 0; k < Math.min(8, list.size()); k++) {
            if (heroed && (k == 1 || k == 2)) continue;  // briefs have no picture
            urls.add(list.get(k).imageFor(heroed && k == 0 ? hero[0] : tile));
        }
        images.warm(urls);
        for (int k = 0; k < Math.min(3, list.size()); k++) pullHeadIfThere(list.get(k));
    }

    private void openMoreMenu() {
        if (moreChoices.isEmpty()) return;
        moreRows = new ArrayList<>();
        for (final String[] c : moreChoices) {
            Options.Tile t = new Options.Tile(null, c[0]);
            t.onOk = () -> openMore(c);
            moreRows.add(t);
        }
        fillMoreRows();
        options.openPopup("More stories like this", moreRows);
    }

    /** Each row: how many stories are in, or that they're on their way, or what went wrong. */
    private void fillMoreRows() {
        if (moreRows == null) return;
        for (int i = 0; i < moreRows.size() && i < moreChoices.size(); i++) {
            String[] c = moreChoices.get(i);
            Options.Tile t = moreRows.get(i);
            List<Story> list = moreLists.get(c[1]);
            boolean wanted = moreWanted != null && moreWanted[1].equals(c[1]);
            t.value = wanted ? "Loading…" : list != null ? list.size() + (list.size() == 1 ? " story" : " stories")
                    : moreLoading.contains(c[1]) ? "…" : "";
            t.hint = list == null ? moreErrors.get(c[1]) : null;
        }
    }

    private void openMore(final String[] c) {
        List<Story> list = moreLists.get(c[1]);
        if (list == null) {
            // Not in yet (or it failed): ask now, and open it when it comes.
            moreWanted = c;
            moreAt.remove(c[1]);
            preloadMore(c);
            fillOptions();
            return;
        }
        moreWanted = null;
        showExtra(new Relay.Section("more", c[0], new ArrayList<>(list)), true);
        if (c[2] == null) return;
        // Ask News on the Mac for more of the channel or topic; show them when they're in.
        relay.refreshTag(c[2], c[3], (job, e) -> {
            if (job == null) return;
            relay.job(job, 40, (state, e2) -> relay.stories(c[1], (more, e3) -> {
                if (more != null && !more.isEmpty()) {
                    moreLists.put(c[1], more);
                    moreAt.put(c[1], SystemClock.uptimeMillis());
                }
                if (more != null && extra != null && extra.title.equals(c[0]) && more.size() > extra.stories.size()) {
                    showExtra(new Relay.Section("more", c[0], more), false);
                }
            }));
        });
    }

    private void showExtra(Relay.Section section, boolean go) {
        extra = section;
        shown = arrange();
        if (!go) {
            front.setSections(shown, true);
            return;
        }
        options.close();
        if (reader.getVisibility() == View.VISIBLE) {
            leaveStory();
            reading = null;
            reader.setVisibility(View.GONE);
        }
        front.setSections(shown, false);
        front.open("more");
    }

    private void continueReading(Story s) {  // feature: opt.continue
        Relay.Section in = null;
        int index = 0;
        for (Relay.Section sec : shown) {
            for (int i = 0; i < sec.stories.size() && in == null; i++) {
                if (sec.stories.get(i).id.equals(s.id)) {
                    in = sec;
                    index = i;
                }
            }
        }
        if (in == null) {
            List<Story> one = new ArrayList<>();
            one.add(s);
            in = new Relay.Section("continue", "Continue", one);
        }
        onOpen(in, index);
    }

    @Override
    public void onOptionsClosed() {
        library.save();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        lastKeyAt = SystemClock.uptimeMillis();  // a finger on the page: hold live reloads too
        return super.dispatchTouchEvent(e);
    }

    /** Touch: Back closes the top thing (video, options, story); on the front page it leaves. */
    private boolean back() {  // feature: phone.back
        if (player.playing()) player.closeNow();
        else if (options.isPopupOpen()) options.closePopup();
        else if (options.isOpen()) options.close();
        else if (reader.getVisibility() == View.VISIBLE) onClose();
        else return false;
        return true;
    }

    @Override
    public void onMenu() {
        openOptions(optionsTarget(), true);
    }

    @Override
    public void onRefresh() {  // feature: phone.pull_refresh
        if (!front.hasContent()) showMessage("Looking for Apple News Relay on your network…");
        load();
        if (relay.known()) refreshNow();
    }

    @Override
    public void onInstallUpdate() {
        updater.install(this);
    }

    @Override
    public void onUpdateChanged() {
        if (front != null) front.setUpdateNotice(updater.bannerText());
        fillOptions();
    }

    /** Touch: the relay's address, as relay.conf would hold it; empty finds it by itself. */
    private View relayField(final Runnable done) {  // feature: phone.relay_field
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(12));
        box.setBackgroundColor(Ui.alpha(Ui.INK, 0.045f));
        android.widget.TextView label = Ui.text(this, 17, Ui.INK, Ui.sansMedium);
        label.setText("Relay address");
        box.addView(label);
        final android.widget.EditText edit = new android.widget.EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        edit.setTextSize(16);
        edit.setTypeface(Ui.sans);
        edit.setTextColor(Ui.INK);
        edit.setHintTextColor(Ui.FAINT);
        edit.setHint("Automatic");
        edit.setText(relay.manualUrl());
        edit.setBackground(new android.graphics.drawable.ColorDrawable(Ui.alpha(Ui.INK, 0.06f)));
        edit.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        android.widget.LinearLayout.LayoutParams el = new android.widget.LinearLayout.LayoutParams(-1, -2);
        el.topMargin = Ui.dp(10);
        box.addView(edit, el);
        final android.widget.TextView hint = Ui.text(this, 13, Ui.MUTED, Ui.sans);
        hint.setLineSpacing(0, 1.2f);
        hint.setText(relayHint());
        android.widget.LinearLayout.LayoutParams hl = new android.widget.LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = Ui.dp(8);
        box.addView(hint, hl);
        edit.setOnEditorActionListener((v, action, ev) -> {
            String now = relay.setManual(edit.getText().toString());
            edit.setText(now);
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(edit.getWindowToken(), 0);
            edit.clearFocus();
            hint.setText(now.isEmpty() ? "Looking for the relay on the network…" : "Trying " + now + "…");
            events.stop();
            events.start();
            loading = false;
            load();
            if (done != null) done.run();
            return true;
        });
        return box;
    }

    /** The TV: the phone's relay field in a box over Options, with the on-screen keyboard. OK on
     *  the keyboard (Done) sets it and closes the box; Back leaves it as it was. */
    private void editRelay() {  // feature: phone.relay_field
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        View field = relayField(d::dismiss);
        field.setBackgroundColor(Ui.PAPER);
        field.setPadding(Ui.dp(24), Ui.dp(20), Ui.dp(24), Ui.dp(18));
        d.setContentView(field, new android.view.ViewGroup.LayoutParams(Ui.dp(520), -2));
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.PAPER));
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.setOnDismissListener(x -> fillOptions());
        // The Fire TV keyboard ignores "show on open"; ask for it once the box has focus, as a
        // second OK on the field would.
        final View edit = ((android.view.ViewGroup) field).getChildAt(1);
        d.setOnShowListener(x -> edit.postDelayed(() -> {
            edit.requestFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(edit, 0);
        }, 200));
        d.show();
    }

    private String relayHint() {
        if (!relay.known()) return "Empty: found by itself (Bonjour, or a look around the network). Or type the Mac's address, e.g. 192.168.1.20";
        return (relay.manualUrl().isEmpty() ? "Found by itself: " + relay.where() + "  ·  " : "Set by hand  ·  ")
                + "Empty: find it by itself";
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        lastKeyAt = SystemClock.uptimeMillis();
        if (Ui.touch) {
            // Keys go where Android sends them (the relay field takes typing); Back is ours.
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled() && !back()) moveTaskToBack(true);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (player.playing()) {
            if (player.onKey(e)) return true;
            return super.dispatchKeyEvent(e);
        }
        if (options.isOpen()) {
            if (options.onKey(e)) return true;
            return super.dispatchKeyEvent(e);
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) openOptions(optionsTarget(), true);
            return true;
        }
        if (reader.getVisibility() == View.VISIBLE && reader.onKey(e)) return true;
        if (!front.hasContent() && (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            if (e.getAction() == KeyEvent.ACTION_UP && !loading) {
                showMessage("Looking for Apple News Relay on your network…");
                load();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
