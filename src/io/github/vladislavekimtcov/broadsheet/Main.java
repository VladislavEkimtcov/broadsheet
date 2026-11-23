package io.github.vladislavekimtcov.broadsheet;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
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

/**
 * Broadsheet: a news reader for Android TV that shows Apple News, by way of Apple News Relay on a
 * Mac. Front page and reader live in one window; the reader slides over the front page, the
 * options panel (Menu) over both, and a story's video over everything.
 */
public final class Main extends Activity implements Front.Listener, Reader.Listener, Player.Listener, Options.Listener {
    /** Seconds the relay may spend getting a story's body from Apple News before answering. */
    private static final int FETCH_WAIT = 14;
    /** With nothing on screen, look for the relay again this often. */
    private static final long RETRY_MS = 30 * 1000L;
    /** Screens of Today to scroll on the Mac when refreshing: four found the most (docs/PLAYBOOK.md). */
    private static final int REFRESH_PAGES = 4;

    private static final int[] AUTO_REFRESH_MIN = {25, 60, 0};
    private static final String[] AUTO_REFRESH_NAMES = {"every 25 minutes", "every hour", "off"};
    private static final String[] THEMES = {"auto", "dark", "light"};
    private static final String[] THEME_NAMES = {"Automatic", "Dark", "Light"};
    private static final String[] READ_MODES = {"show", "dim", "hide"};
    private static final String[] READ_NAMES = {"Show", "Dim", "Hide"};

    private Relay relay;
    private Images images;
    private Library library;
    private SharedPreferences prefs;
    private FrameLayout root;
    private Front front;
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
    private Options.Tile saveTile, reactTile, moreTile, sizeTile, themeTile, readTile, continueTile, refreshTile;
    private final List<String[]> moreChoices = new ArrayList<>();
    private int moreIndex;
    private boolean saveBusy, reactBusy, moreBusy;
    private String saveError, reactError, moreError;

    private final Runnable retry = this::load;
    private final Runnable themeTick = this::applyTheme;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        try {
            Net.userAgent = "Broadsheet/" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " (Android TV)";
        } catch (PackageManager.NameNotFoundException e) {
            Net.userAgent = "Broadsheet (Android TV)";
        }
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        relay = new Relay(this);
        images = new Images(new File(getCacheDir(), "img"));
        library = new Library(getFilesDir());
        new Thread(() -> images.prune(150L * 1024 * 1024), "prune").start();
        Ui.setLight(wantLight());
        root = new FrameLayout(this);
        setContentView(root);
        buildViews();

        List<Relay.Section> cached = relay.cachedFront();
        if (cached != null && !cached.isEmpty()) {
            setBase(cached);
            front.setSections(shown, false);
            loadedAt = relay.cachedFrontTime();
            front.setStatus("From " + time(loadedAt) + "\nConnecting to the Mac…");
        } else {
            showMessage("Looking for Apple News Relay on your network…");
        }
        load();
    }

    private void buildViews() {
        root.removeAllViews();
        root.setBackgroundColor(Ui.PAPER);
        front = new Front(this, images, this);
        reader = new Reader(this, images, this);
        reader.setTextSizeStep(textStep());
        reader.setVisibility(View.GONE);
        player = new Player(this, this);
        options = new Options(this, this);
        root.addView(front);
        root.addView(reader);
        root.addView(player);
        root.addView(options);
    }

    /** The text size step; 0.1's four sizes (key "size") map onto the ten of 0.2.1. */
    private int textStep() {
        if (prefs.contains("text")) return prefs.getInt("text", Reader.STANDARD);
        int old = prefs.getInt("size", 1);
        return Math.max(0, Math.min(Reader.SIZES.length - 1, Reader.STANDARD - 1 + old));
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyTheme();
        if (!front.hasContent() || System.currentTimeMillis() - loadedAt > 10 * 60 * 1000L) load();
    }

    @Override
    protected void onStop() {
        super.onStop();
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
        showMessage(text + "\n\nTrying again every 30 seconds. Press OK to try now.");
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
                            + "or put url=http://<mac>:8787 in relay.conf in this app's files folder.");
                    return;
                }
                load();
            });
            return;
        }
        loading = true;
        relay.front((sections, error) -> {
            loading = false;
            if (sections == null) {
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
            if (sections.isEmpty()) {
                fail("The relay has no stories yet. Open Apple News on the Mac once.");
                return;
            }
            boolean first = !front.hasContent();
            setBase(sections);
            savedNow.clear();
            front.setSections(shown, !first);
            loadedAt = System.currentTimeMillis();
            front.setStatus("Updated " + time(loadedAt) + "\nApple News via " + relay.where());
            fillOptions();
            maybeRefreshToday();
        });
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
            if (!keep.isEmpty()) out.add(new Relay.Section(s.id, s.title, keep));
        }
        return out;
    }

    private int autoRefreshStep() {
        return Math.max(0, Math.min(AUTO_REFRESH_MIN.length - 1, prefs.getInt("auto_refresh", 0)));
    }

    /** Asks the Mac to refresh Today now and then; reloads quietly when it's done. */
    private void maybeRefreshToday() {
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
        reader.show(s);
        float at = library.position(s.id);
        pendingRestore = at > 0.04f && at < 0.95f ? at : 0f;
        library.setPosition(s, at);
        library.save();
        // Ask without waiting, to say at once what the Mac is doing; then wait on its job.
        relay.article(s.id, 0, (a, error) -> {
            if (reader.story() != s) return;
            if (a == null || a.pendingJob == null) {
                arrived(s, a, error);
                return;
            }
            reader.setStage("queued".equals(a.jobState) ? "Waiting its turn on the Mac…" : "Apple News is fetching it on the Mac…");
            follow(s, a.pendingJob, 0);
        });
    }

    /** Waits for the relay's job on a story, then shows what came of it. */
    private void follow(final Story s, final String job, final int round) {
        relay.job(job, 12, (state, e) -> {
            if (reader.story() != s) return;
            if ("running".equals(state) || "queued".equals(state)) {
                if (round < 3) {
                    reader.setStage("Still fetching: big stories and Apple News+ take longer…");
                    follow(s, job, round + 1);
                    return;
                }
            }
            relay.article(s.id, 0, (again, e2) -> {
                if (reader.story() != s) return;
                arrived(s, again, e2);
            });
        });
    }

    // ------------------------------------------------------------ ahead of time

    private final Set<String> prefetched = new HashSet<>();
    private Story focusedStory;
    private final Runnable prefetchFocused = () -> {
        if (focusedStory != null && reader.getVisibility() != View.VISIBLE) prefetch(focusedStory);
    };

    @Override
    public void onFocusStory(Story story) {
        focusedStory = story;
        root.removeCallbacks(prefetchFocused);
        root.postDelayed(prefetchFocused, 700);  // resting on it, not skimming past
    }

    /** The story after the open one, so Right opens at once. */
    private void prefetchNext() {
        if (openSection != null && openIndex + 1 < openSection.stories.size()) prefetch(openSection.stories.get(openIndex + 1));
    }

    private void prefetch(Story s) {
        if (!"summary".equals(s.completeness) || !prefetched.add(s.id)) return;
        relay.prefetch(s.id);
    }

    private void arrived(Story s, Article a, String error) {
        reader.setArticle(a, error);
        if (a == null) return;
        if (a.pendingJob == null) prefetchNext();
        library.setReaction(s.id, a.reaction);
        if (pendingRestore > 0 && a.pendingJob == null) {
            reader.restoreFraction(pendingRestore);
            pendingRestore = 0;
        }
    }

    /** Remembers how far into the open story the reader got. */
    private void leaveStory() {
        Story s = reader.story();
        if (s == null || reader.getVisibility() != View.VISIBLE || reader.article() == null) return;
        float f = reader.fraction();
        library.setPosition(s, f > 0.97f ? 1f : f);
    }

    @Override
    public void onMove(int delta) {
        if (openSection == null) return;
        int next = openIndex + delta;
        if (next < 0 || next >= openSection.stories.size()) return;
        openIndex = next;
        open(openSection.stories.get(next));
    }

    @Override
    public void onClose() {
        leaveStory();
        library.save();
        reader.setVisibility(View.GONE);
        if (front.currentSection() == openSection) front.focusStory(openIndex);
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
    private void applyTheme() {
        root.removeCallbacks(themeTick);
        if ("auto".equals(themeMode())) {
            long now = System.currentTimeMillis();
            long next = Sun.nextChange(now, TimeZone.getDefault());
            root.postDelayed(themeTick, Math.max(60 * 1000L, next - now + 30 * 1000L));
        }
        boolean light = wantLight();
        if (light == Ui.light || player.playing()) return;
        Ui.setLight(light);
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
        if (s != target) {
            saveError = reactError = moreError = null;
            moreIndex = 0;
        }
        target = s;
        moreChoices.clear();
        if (s != null) {
            if (s.channelId != null && s.channel != null) {
                moreChoices.add(new String[] {"More from " + s.channel, "/v1/channels/" + s.channelId + "/articles?limit=45",
                        "channels", s.channelId});
            }
            Article a = targetArticle();
            if (a != null) {
                for (String[] t : a.topics) {
                    moreChoices.add(new String[] {"More on " + t[1], "/v1/topics/" + t[0] + "/articles?limit=45", "topics", t[0]});
                }
            }
            moreChoices.add(new String[] {"Related stories", "/v1/articles/" + s.id + "/related", null, null});
        }
        moreIndex = Math.min(moreIndex, Math.max(0, moreChoices.size() - 1));

        saveTile = new Options.Tile("☆", "Save story");
        saveTile.onOk = this::toggleSave;
        reactTile = new Options.Tile("♥", "Like or dislike");
        reactTile.segments = new String[] {"More like this", "Fewer like this"};
        reactTile.segmentIcons = new int[] {Icons.THUMB_UP, Icons.THUMB_DOWN};
        reactTile.checked = new boolean[2];
        reactTile.onOk = this::sendReaction;
        String r = s == null ? null : library.reaction(s.id);
        reactTile.segment = "dislike".equals(r) ? 1 : 0;
        moreTile = new Options.Tile("≡", "More like this");
        moreTile.onStep = d -> {
            moreIndex = Math.max(0, Math.min(moreChoices.size() - 1, moreIndex + d));
            moreError = null;
            fillOptions();
        };
        moreTile.onOk = this::openMore;
        sizeTile = new Options.Tile("Aa", "Text size");
        sizeTile.slider = Reader.SIZES.length;
        sizeTile.onStep = d -> {
            int step = Math.max(0, Math.min(Reader.SIZES.length - 1, reader.sizeStep() + d));
            if (step == reader.sizeStep()) return;
            prefs.edit().putInt("text", step).apply();
            reader.setTextSizeStep(step);
            if (reader.getVisibility() == View.VISIBLE) reader.relayout();
            fillOptions();
        };
        themeTile = new Options.Tile("◐", "Appearance");
        themeTile.onStep = d -> {
            int i = Math.max(0, Math.min(THEMES.length - 1, indexOf(THEMES, themeMode()) + d));
            prefs.edit().putString("theme", THEMES[i]).apply();
            fillOptions();
            applyTheme();
        };
        readTile = new Options.Tile("◉", "Stories you've read");
        readTile.onStep = d -> {
            int i = Math.max(0, Math.min(READ_MODES.length - 1, indexOf(READ_MODES, readMode()) + d));
            if (READ_MODES[i].equals(readMode())) return;
            prefs.edit().putString("read", READ_MODES[i]).apply();
            shown = arrange();
            if (!shown.isEmpty()) front.setSections(shown, true);
            front.refreshDim();
            fillOptions();
        };
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
        refreshTile = new Options.Tile("↻", "Refresh now");
        refreshTile.onOk = this::refreshNow;
        refreshTile.onStep = d -> {
            int i = Math.max(0, Math.min(AUTO_REFRESH_MIN.length - 1, autoRefreshStep() + d));
            prefs.edit().putInt("auto_refresh", i).apply();
            fillOptions();
        };

        List<Options.Tile> tiles = new ArrayList<>();
        tiles.add(saveTile);
        tiles.add(reactTile);
        tiles.add(moreTile);
        tiles.add(sizeTile);
        tiles.add(themeTile);
        tiles.add(readTile);
        if (continueTile != null) tiles.add(continueTile);
        tiles.add(refreshTile);
        fillTiles();
        String about = s == null ? "Choose a story to save it, like it or find more like it."
                : (s.channel != null ? s.channel + "  ·  " : "") + s.title;
        options.open(tiles, about, slide);
        relay.handoff((h, error) -> {
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
            t.hint = mac.title + "  ·  OK reads it here";
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
            int waiting = jobs == null ? 0 : jobs.optInt("queued") + jobs.optInt("running");
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
        String pick = "Choose a story first";

        saveTile.enabled = has && !saveBusy;
        boolean saved = has && isSaved(s);
        saveTile.icon = saved ? "★" : "☆";
        saveTile.label = !has ? "Save story" : saveBusy ? (saved ? "Removing…" : "Saving…") : saved ? "Saved" : "Save story";
        saveTile.value = saved && !saveBusy ? "✓" : "";
        saveTile.hint = !has ? pick : saveBusy ? "Apple News on the Mac is doing it" : saveError != null ? saveError
                : saved ? "In Saved Stories on the Mac  ·  OK takes it out" : "OK puts it in Saved Stories on the Mac";
        if (saveBusy) saveTile.enabled = true;  // still selectable; OK waits

        String r = has ? library.reaction(s.id) : null;
        reactTile.enabled = has;
        reactTile.checked[0] = "like".equals(r);
        reactTile.checked[1] = "dislike".equals(r);
        reactTile.value = reactBusy ? "Sending…" : "like".equals(r) ? "Liked" : "dislike".equals(r) ? "Disliked" : "";
        reactTile.hint = !has ? pick : reactError != null ? reactError : r != null ? "OK on it again takes it back"
                : "Apple News suggests more or fewer like it";

        moreTile.enabled = has && !moreChoices.isEmpty();
        String[] choice = moreTile.enabled ? moreChoices.get(moreIndex) : null;
        moreTile.label = choice != null ? choice[0] : "More like this";
        moreTile.value = moreChoices.size() > 1 ? (moreIndex + 1) + " of " + moreChoices.size() : "";
        moreTile.hint = !has ? pick : moreBusy ? "Loading…" : moreError != null ? moreError
                : moreChoices.size() > 1 ? "◀ ▶ more choices  ·  OK shows them" : "OK shows them";

        int step = reader.sizeStep();
        sizeTile.position = step;
        float pt = Reader.SIZES[step];
        sizeTile.value = (step == 0 ? "Close reading  ·  " : step == Reader.SIZES.length - 1 ? "Across the room  ·  "
                : step == Reader.STANDARD ? "Standard  ·  " : "") + (pt == Math.round(pt) ? String.valueOf(Math.round(pt)) : String.valueOf(pt)) + " pt";
        sizeTile.hint = null;

        themeTile.value = THEME_NAMES[indexOf(THEMES, themeMode())];
        themeTile.hint = "auto".equals(themeMode()) ? sunHint() : "◀ Automatic follows sunset";

        readTile.value = READ_NAMES[indexOf(READ_MODES, readMode())];
        int n = library.readCount();
        readTile.hint = (n == 0 ? "None yet" : n + (n == 1 ? " story" : " stories") + " read on this TV") + "  ·  ◀ ▶";

        if (continueTile != null) {
            Story u = library.unfinished();
            continueTile.value = u != null ? Math.round(library.position(u.id) * 100) + "%" : "";
            continueTile.hint = u != null ? u.title : "";
        }

        refreshTile.value = loadedAt > 0 ? "Updated " + time(loadedAt) : "";
        refreshTile.hint = refreshing ? "Refreshing Apple News on the Mac…"
                : "Automatically " + AUTO_REFRESH_NAMES[autoRefreshStep()] + "  ·  ◀ ▶";
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

    private void openMore() {
        if (moreChoices.isEmpty() || moreBusy) return;
        final String[] c = moreChoices.get(moreIndex);
        moreBusy = true;
        moreError = null;
        fillOptions();
        relay.stories(c[1], (list, error) -> {
            moreBusy = false;
            if (list == null || list.isEmpty()) {
                moreError = list == null ? error : "Nothing in the archive yet";
                fillOptions();
                return;
            }
            showExtra(new Relay.Section("more", c[0], list), true);
            if (c[2] == null) return;
            // Ask News on the Mac for more of the channel or topic; show them when they're in.
            relay.refreshTag(c[2], c[3], (job, e) -> {
                if (job == null) return;
                relay.job(job, 40, (state, e2) -> relay.stories(c[1], (more, e3) -> {
                    if (more != null && extra != null && extra.title.equals(c[0]) && more.size() > extra.stories.size()) {
                        showExtra(new Relay.Section("more", c[0], more), false);
                    }
                }));
            });
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
            reader.setVisibility(View.GONE);
        }
        front.setSections(shown, false);
        front.open("more");
    }

    private void continueReading(Story s) {
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
    public boolean dispatchKeyEvent(KeyEvent e) {
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
