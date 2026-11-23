package io.github.vladislavekimtcov.broadsheet;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Broadsheet: a news reader for Android TV that shows Apple News, by way of Apple News Relay on a
 * Mac. Front page and reader live in one window; the reader slides over the front page.
 */
public final class Main extends Activity implements Front.Listener, Reader.Listener {
    /** Ask the Mac to refresh Today when the front page is older than this. */
    private static final long REFRESH_AFTER_MS = 25 * 60 * 1000L;
    /** Seconds the relay may spend getting a story's body from Apple News before answering. */
    private static final int FETCH_WAIT = 14;
    /** With nothing on screen, look for the relay again this often. */
    private static final long RETRY_MS = 30 * 1000L;

    private Relay relay;
    private Images images;
    private Front front;
    private Reader reader;
    private SharedPreferences prefs;
    private Relay.Section openSection;
    private int openIndex;
    private long loadedAt;
    private boolean loading, refreshing;
    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            load();
        }
    };

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
        new Thread(new Runnable() {
            @Override
            public void run() {
                images.prune(150L * 1024 * 1024);
            }
        }, "prune").start();

        FrameLayout root = new FrameLayout(this);
        front = new Front(this, images, this);
        reader = new Reader(this, images, this);
        reader.setTextSizeStep(prefs.getInt("size", 1));
        reader.setVisibility(View.GONE);
        root.addView(front);
        root.addView(reader);
        setContentView(root);

        List<Relay.Section> cached = relay.cachedFront();
        if (cached != null && !cached.isEmpty()) {
            front.setSections(cached, false);
            loadedAt = relay.cachedFrontTime();
            front.setStatus("From " + time(loadedAt) + "\nConnecting to the Mac…");
        } else {
            front.showMessage("Looking for Apple News Relay on your network…");
        }
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!front.hasContent() || System.currentTimeMillis() - loadedAt > 10 * 60 * 1000L) load();
    }

    @Override
    protected void onStop() {
        super.onStop();
        front.removeCallbacks(retry);
        images.trim();
    }

    /** Says what went wrong; with nothing to show, tries again by itself (OK tries at once). */
    private void fail(String text) {
        if (front.hasContent()) {
            front.showMessage(text);
            return;
        }
        front.showMessage(text + "\n\nTrying again every 30 seconds. Press OK to try now.");
        front.removeCallbacks(retry);
        front.postDelayed(retry, RETRY_MS);
    }

    private static String time(long ms) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ms));
    }

    /** Loads the front page from the relay, finding the relay first if needed. */
    private void load() {
        if (loading) return;
        front.removeCallbacks(retry);
        if (!relay.known()) {
            loading = true;
            relay.discover(new Relay.Done<String>() {
                @Override
                public void done(String url, String error) {
                    loading = false;
                    if (url == null) {
                        fail(error + "\n\nStart Apple News Relay on the Mac (apple-news-relay serve --host 0.0.0.0), "
                                + "or put url=http://<mac>:8787 in relay.conf in this app's files folder.");
                        return;
                    }
                    load();
                }
            });
            return;
        }
        loading = true;
        relay.front(new Relay.Done<List<Relay.Section>>() {
            @Override
            public void done(List<Relay.Section> sections, String error) {
                loading = false;
                if (sections == null) {
                    if ("last known".equals(relay.origin())) {
                        // The Mac may have a new address: look again once.
                        relay.discover(new Relay.Done<String>() {
                            @Override
                            public void done(String url, String e2) {
                                if (url != null) load();
                                else fail(error);
                            }
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
                front.setSections(sections, !first);
                loadedAt = System.currentTimeMillis();
                front.setStatus("Updated " + time(loadedAt) + "\nApple News via " + relay.where());
                maybeRefreshToday();
            }
        });
    }

    /** Asks the Mac to refresh Today now and then; reloads quietly when it's done. */
    private void maybeRefreshToday() {
        long last = prefs.getLong("refreshed", 0);
        if (refreshing || System.currentTimeMillis() - last < REFRESH_AFTER_MS) return;
        refreshing = true;
        prefs.edit().putLong("refreshed", System.currentTimeMillis()).apply();
        relay.refreshToday(new Relay.Done<String>() {
            @Override
            public void done(String jobId, String error) {
                if (jobId == null) {
                    refreshing = false;
                    return;
                }
                front.setStatus("Updated " + time(loadedAt) + "\nRefreshing Apple News on the Mac…");
                relay.job(jobId, 60, new Relay.Done<String>() {
                    @Override
                    public void done(String state, String e) {
                        refreshing = false;
                        load();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------- reading

    @Override
    public void onOpen(Relay.Section section, int index) {
        openSection = section;
        openIndex = index;
        reader.setVisibility(View.VISIBLE);
        reader.bringToFront();
        open(section.stories.get(index));
    }

    private void open(final Story s) {
        reader.show(s);
        relay.article(s.id, FETCH_WAIT, new Relay.Done<Article>() {
            @Override
            public void done(Article a, String error) {
                if (reader.story() != s) return;
                reader.setArticle(a, error);
                if (a != null && a.pendingJob != null) {
                    relay.job(a.pendingJob, 30, new Relay.Done<String>() {
                        @Override
                        public void done(String state, String e) {
                            if (reader.story() != s) return;
                            relay.article(s.id, 0, new Relay.Done<Article>() {
                                @Override
                                public void done(Article again, String e2) {
                                    if (reader.story() == s) reader.setArticle(again, e2);
                                }
                            });
                        }
                    });
                }
            }
        });
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
        reader.setVisibility(View.GONE);
        if (front.currentSection() == openSection) front.focusStory(openIndex);
    }

    @Override
    public void onTextSize(int step) {
        prefs.edit().putInt("size", step).apply();
        reader.setTextSizeStep(step);
        if (reader.story() != null) open(reader.story());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (reader.getVisibility() == View.VISIBLE && reader.onKey(e)) return true;
        if (!front.hasContent() && (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            if (e.getAction() == KeyEvent.ACTION_UP && !loading) {
                front.showMessage("Looking for Apple News Relay on your network…");
                load();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
