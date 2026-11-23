package io.github.vladislavekimtcov.broadsheet;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Talks to Apple News Relay (github.com/VladislavEkimtcov/apple-news-relay), the Mac-side server
 * that turns the Apple News app into a JSON API. Everything the app shows comes from it.
 *
 * <p>Where the relay is: {@code relay.conf} in the app's external files folder ({@code url=} and
 * optional {@code token=}), else the last address that worked, else Bonjour (the relay advertises
 * {@code _newsrelay._tcp}), else a look for port 8787 on the TV's own subnet.
 */
final class Relay {
    interface Done<T> {
        /** Main thread. {@code error} is a short sentence for the screen, or null. */
        void done(T value, String error);
    }

    static final class Section {
        final String id, title;
        /** Grows as more pages arrive ({@link #next}). */
        final List<Story> stories;
        /** The relay's link to the next page of this section, or null when there is no more. */
        String next;
        /** Stories in the whole section (more than are loaded while {@link #next} is set). */
        int total;
        /** How many of them Apple News Today itself carries (the rest: more of the same). */
        int today;

        Section(String id, String title, List<Story> stories) {
            this.id = id;
            this.title = title;
            this.stories = new ArrayList<>(stories);
            this.total = stories.size();
        }
    }

    /** A front page from {@link #front}: {@code unchanged} when the relay said 304. */
    static final class FrontPage {
        final List<Section> sections;
        final boolean unchanged;
        final long version;

        FrontPage(List<Section> sections, boolean unchanged, long version) {
            this.sections = sections;
            this.unchanged = unchanged;
            this.version = version;
        }
    }

    /** A page of stories and the link to the next one (null at the end). */
    static final class Page {
        final List<Story> stories;
        final String next;

        Page(List<Story> stories, String next) {
            this.stories = stories;
            this.next = next;
        }
    }

    static final String SERVICE = "_newsrelay._tcp.";  // feature: relay.bonjour
    /** The relay's default port, for the subnet scan. */
    static final int PORT = 8787;
    private static final int MAX_JSON = 8 * 1024 * 1024;
    /** Stories per section on the front page: the hero, two briefs and three rows of three. */
    static final int PER_SECTION = 12;
    /** Stories per further page of a section: five rows. */
    static final int PAGE = 15;
    /** Text in a story's first part: about two screens (the rest follows as it's read). */
    static final int HEAD_CHARS = 2400;
    /** What a card needs of a story, so the front page stays small. */
    private static final String CARD_FIELDS =
            "title,excerpt,byline,channel,thumbnails,published_at,access,completeness,reading_minutes,colors,url";

    private final Context context;
    private final File cache;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Quick calls. Anything that waits on the Mac goes to {@link #slow}, so it can't hold these up. */
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final ExecutorService slow = Executors.newFixedThreadPool(4);
    /** Fetching ahead: one at a time, at background priority. */
    private final ExecutorService warm = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "warm");
        t.setDaemon(true);
        return t;
    });
    private volatile String base, token;
    private volatile String origin = "";
    private volatile String frontEtag;
    /** The relay predates 0.3 (no /v1/front, no interest lists): the 0.2 calls are used. */
    private volatile boolean legacy, noInterest;

    Relay(Context c) {
        context = c.getApplicationContext();
        cache = new File(c.getCacheDir(), "relay");
        cache.mkdirs();
        prefs = c.getSharedPreferences("relay", Context.MODE_PRIVATE);
        readConfig();
        if (base == null) {
            base = prefs.getString("url", null);
            if (base != null) origin = "last known";
        }
    }

    /** "10.0.0.4:8787" for the rail's footer, or null before the relay is known. */
    String where() {
        if (base == null) return null;
        return base.replaceFirst("^https?://", "");
    }

    String origin() {
        return origin;
    }

    boolean known() {
        return base != null;
    }

    /** "http://10.0.0.4:8787", or null: for the event stream. */
    String base() {
        return base;
    }

    String token() {
        return token;
    }

    /** The relay has 0.3's calls (front page in one, interest lists, story parts). */
    boolean modern() {
        return !legacy;
    }

    private void readConfig() {
        File dir = context.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, "relay.conf");  // feature: relay.conf
        if (f == null || !f.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.startsWith("#") || !line.contains("=")) continue;
                String k = line.substring(0, line.indexOf('=')).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(line.indexOf('=') + 1).trim();
                if (k.equals("url") && v.startsWith("http")) {
                    base = v.replaceAll("/+$", "");
                    origin = "relay.conf";
                } else if (k.equals("token")) {
                    token = v;
                }
            }
        } catch (IOException e) {
            // no config then
        }
    }

    /**
     * Finds the relay and remembers it: Bonjour first, and after a moment a look at port 8787 on
     * this TV's own subnet too, for networks that don't pass Bonjour between Wi-Fi and the Mac.
     * Whichever answers first wins; gives up after 10 s.
     */
    void discover(final Done<String> done) {
        final AtomicBoolean finished = new AtomicBoolean();
        final NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        final NsdManager.DiscoveryListener[] holder = new NsdManager.DiscoveryListener[1];
        final WifiManager.MulticastLock lock = multicastLock();
        final Runnable stop = new Runnable() {
            @Override
            public void run() {
                if (lock != null && lock.isHeld()) lock.release();
                if (nsd == null || holder[0] == null) return;
                try {
                    nsd.stopServiceDiscovery(holder[0]);
                } catch (RuntimeException e) {
                    // already stopped
                }
            }
        };
        final Found found = new Found() {
            @Override
            public void at(String url, String how) {
                if (!finished.compareAndSet(false, true)) return;
                base = url;
                origin = how;
                prefs.edit().putString("url", url).apply();
                ui.post(stop);
                post(done, url, null);
            }
        };
        if (nsd != null) {
            holder[0] = new NsdManager.DiscoveryListener() {
                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    if (finished.get()) return;
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo i, int code) {}

                        @Override
                        public void onServiceResolved(NsdServiceInfo i) {
                            InetAddress host = i.getHost();
                            if (host == null) return;
                            String h = host.getHostAddress();
                            if (h.contains(":")) h = "[" + h + "]";
                            found.at("http://" + h + ":" + i.getPort(), "found with Bonjour");
                        }
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {}

                @Override
                public void onDiscoveryStarted(String type) {}

                @Override
                public void onDiscoveryStopped(String type) {}

                @Override
                public void onStartDiscoveryFailed(String type, int code) {}

                @Override
                public void onStopDiscoveryFailed(String type, int code) {}
            };
            try {
                nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, holder[0]);
            } catch (RuntimeException e) {
                holder[0] = null;
            }
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished.get()) return;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        scan(finished, found);
                    }
                }, "scan").start();
            }
        }, nsd == null ? 0 : 1500);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished.compareAndSet(false, true)) {
                    stop.run();
                    done.done(null, "No Apple News Relay found on this network.");
                }
            }
        }, 10000);
    }

    private interface Found {
        /** Any thread; the first call wins. */
        void at(String url, String how);
    }

    /** Some Wi-Fi chips drop multicast (so Bonjour) unless an app asks for it. */
    private WifiManager.MulticastLock multicastLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return null;
            WifiManager.MulticastLock lock = wifi.createMulticastLock("broadsheet-discovery");
            lock.setReferenceCounted(false);
            lock.acquire();
            return lock;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Knocks on port 8787 of every address in this TV's subnet (a /24 at most), 32 at a time, and
     * asks whatever answers whether it is the relay. Takes a few seconds; stops once found.
     */
    private void scan(final AtomicBoolean finished, final Found found) {  // feature: relay.subnet
        List<InetAddress> hosts = neighbours();
        if (hosts.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (final InetAddress a : hosts) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (finished.get()) return;
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(a, PORT), 400);
                    } catch (IOException e) {
                        return;
                    }
                    String url = "http://" + a.getHostAddress() + ":" + PORT;
                    try {
                        // Even a 401 or 403 from the relay names it, so a token doesn't hide it.
                        if (Net.request("GET", url + "/v1", null, token, 3000, 64 * 1024).text().contains("apple-news-relay")) {
                            found.at(url, "found on the network");
                        }
                    } catch (IOException e) {
                        // something else on that port
                    }
                }
            });
        }
        pool.shutdown();
    }

    /** This TV's neighbours on its Wi-Fi or Ethernet subnet (VPN interfaces skipped). */
    static List<InetAddress> neighbours() {
        List<InetAddress> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                String name = ni.getName();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()
                        || name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress self = ia.getAddress();
                    if (!(self instanceof Inet4Address) || !self.isSiteLocalAddress()) continue;
                    int prefix = Math.max(ia.getNetworkPrefixLength(), 24);
                    if (prefix > 30) continue;
                    byte[] b = self.getAddress();
                    int me = (b[0] & 255) << 24 | (b[1] & 255) << 16 | (b[2] & 255) << 8 | (b[3] & 255);
                    int size = 1 << (32 - prefix);
                    int net = me & -size;
                    for (int i = 1; i < size - 1; i++) {
                        int h = net | i;
                        if (h == me) continue;
                        out.add(InetAddress.getByAddress(new byte[] {(byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h}));
                    }
                }
            }
        } catch (IOException e) {
            // no interfaces to look at
        }
        return out;
    }

    // ----------------------------------------------------------------- calls

    /** A request whose status the caller looks at (304, 404); network trouble and a refusal throw. */
    private Net.Response send(String method, String path, String etag, int readTimeoutMs, String body) throws IOException {
        String b = base;
        if (b == null) throw new IOException("Apple News Relay hasn't been found yet.");
        Net.Response r;
        try {
            r = Net.request(method, b + path, etag, token, readTimeoutMs, MAX_JSON, body);
        } catch (IOException e) {
            throw new IOException("Can't reach Apple News Relay at " + where() + ". Is the Mac awake?", e);
        }
        if (r.code == 401 || r.code == 403) throw new IOException("The relay at " + where() + " turned this TV away (HTTP " + r.code + ").");
        return r;
    }

    private JSONObject call(String method, String path, int readTimeoutMs) throws IOException {
        return json(send(method, path, null, readTimeoutMs, null));
    }

    private static JSONObject json(Net.Response r) throws IOException {
        if (r.code >= 400) {
            String detail = null;
            try {
                detail = new JSONObject(r.text()).optString("detail", null);
            } catch (JSONException e) {
                detail = null;
            }
            throw new IOException(detail != null ? detail : "The relay answered HTTP " + r.code + ".");
        }
        try {
            return new JSONObject(r.text());
        } catch (JSONException e) {
            throw new IOException("The relay sent something that isn't JSON.", e);
        }
    }

    private <T> void post(final Done<T> done, final T value, final String error) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                done.done(value, error);
            }
        });
    }

    /**
     * The front page: Today's sections from Apple News (each deep, its first page), Latest and Saved,
     * in one call. Conditional on the last one: {@code unchanged} when nothing on it changed. A
     * relay older than 0.3 gets the three calls of 0.2.
     */
    void front(final Done<FrontPage> done) {
        io.execute(() -> {
            try {
                if (!legacy) {
                    Net.Response r = send("GET", "/v1/front?per_section=" + PER_SECTION + "&fields=" + CARD_FIELDS
                            + "&fetch_missing=true", frontEtag, 20000, null);
                    if (r.code == 304) {
                        post(done, new FrontPage(null, true, 0), null);
                        return;
                    }
                    if (r.code != 404) {
                        JSONObject data = json(r).getJSONObject("data");
                        frontEtag = r.etag;
                        save("front.json", new JSONObject().put("front", data).put("etag", r.etag == null ? "" : r.etag));
                        post(done, new FrontPage(assemble(data), false, data.optLong("version")), null);
                        return;
                    }
                    legacy = true;
                }
                JSONObject sections = call("GET", "/v1/sections?per_section=30", 20000);
                JSONObject latest = call("GET", "/v1/articles?limit=45", 20000);
                JSONObject saved = call("GET", "/v1/saved?limit=45&fetch_missing=true", 20000);
                save("front.json", new JSONObject().put("sections", sections).put("latest", latest).put("saved", saved));
                post(done, new FrontPage(assemble(sections, latest, saved), false, 0), null);
            } catch (IOException | JSONException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    /** The last front page that loaded, for an instant start (read off the main thread); null if none. */
    void cachedFront(final Done<FrontPage> done) {  // feature: front.cache
        io.execute(() -> {
            List<Section> out = null;
            long version = 0;
            try {
                JSONObject o = load("front.json");
                if (o != null && o.optJSONObject("front") != null) {
                    out = assemble(o.getJSONObject("front"));
                    version = o.getJSONObject("front").optLong("version");
                    String etag = o.optString("etag", "");
                    if (!etag.isEmpty() && frontEtag == null) frontEtag = etag;
                } else if (o != null) {
                    out = assemble(o.optJSONObject("sections"), o.optJSONObject("latest"), o.optJSONObject("saved"));
                }
            } catch (RuntimeException | JSONException e) {
                out = null;
            }
            post(done, out == null ? null : new FrontPage(out, false, version), null);
        });
    }

    long cachedFrontTime() {
        return new File(cache, "front.json").lastModified();
    }

    /** {@code /v1/front}'s data: sections with a link to their next page, Latest second, Saved last. */
    private static List<Section> assemble(JSONObject data) {
        List<Section> out = new ArrayList<>();
        JSONArray sections = data.optJSONArray("sections");
        for (int i = 0; sections != null && i < sections.length(); i++) {
            JSONObject s = sections.optJSONObject(i);
            if (s == null) continue;
            List<Story> stories = Story.list(s.optJSONArray("articles"));
            if (stories.size() < 2) continue; // puzzles, empty slots
            Section sec = new Section(s.optString("id"), s.optString("title"), stories);
            JSONObject links = s.optJSONObject("links");
            sec.next = links == null ? null : Story.str(links, "next");
            sec.total = Math.max(stories.size(), s.optInt("article_count", stories.size()));
            sec.today = s.optInt("today_count", stories.size());
            out.add(sec);
        }
        JSONObject latest = data.optJSONObject("latest");
        List<Story> newest = Story.list(latest == null ? null : latest.optJSONArray("articles"));
        if (!newest.isEmpty()) {
            Section sec = new Section("latest", "Latest", newest);
            JSONObject links = latest.optJSONObject("links");
            sec.next = links == null ? null : Story.str(links, "next");
            out.add(Math.min(2, out.size()), sec);
        }
        JSONObject saved = data.optJSONObject("saved");
        List<Story> kept = Story.list(saved == null ? null : saved.optJSONArray("articles"));
        if (!kept.isEmpty()) out.add(new Section("saved", "Saved Stories", kept));
        return out;
    }

    /** The next page of a section ({@link Section#next}): its stories and the link after it. */
    void page(final String next, final Done<Page> done) {  // feature: front.deep
        io.execute(() -> {
            try {
                String path = next.contains("fields=") ? next : next + (next.contains("?") ? "&" : "?") + "fields=" + CARD_FIELDS;
                if (path.startsWith("/v1/sections/")) path = path.replaceFirst("limit=\\d+", "limit=" + PAGE);
                JSONObject o = call("GET", path, 20000);
                JSONObject links = o.optJSONObject("links");
                String more = links == null ? null : Story.str(links, "next");
                post(done, new Page(Story.list(o.optJSONArray("data")), more), null);
            } catch (IOException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    private static List<Section> assemble(JSONObject sections, JSONObject latest, JSONObject saved) {
        List<Section> out = new ArrayList<>();
        JSONArray data = sections == null ? null : sections.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject s = data.optJSONObject(i);
                if (s == null) continue;
                List<Story> stories = Story.list(s.optJSONArray("articles"));
                if (stories.size() < 2) continue; // puzzles, empty slots
                out.add(new Section(s.optString("id"), s.optString("title"), stories));
            }
        }
        List<Story> newest = Story.list(latest == null ? null : latest.optJSONArray("data"));
        if (!newest.isEmpty()) out.add(Math.min(2, out.size()), new Section("latest", "Latest", newest));
        List<Story> kept = Story.list(saved == null ? null : saved.optJSONArray("data"));
        if (!kept.isEmpty()) out.add(new Section("saved", "Saved Stories", kept));
        return out;
    }

    /** One story with its body. {@code wait}: seconds the relay may spend getting it from Apple News;
     *  {@code maxChars}: only the opening, about that much text (0: all of it). */
    void article(final String id, final int wait, final int maxChars, final Done<Article> done) {
        (wait > 0 ? slow : io).execute(() -> {
            try {
                JSONObject o = call("GET", "/v1/articles/" + id + "?wait=" + wait
                        + (maxChars > 0 ? "&max_chars=" + maxChars : ""), (wait + 15) * 1000);
                post(done, new Article(o.getJSONObject("data")), null);
            } catch (IOException | JSONException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    void article(final String id, final int wait, final Done<Article> done) {
        article(id, wait, 0, done);
    }

    /** The rest of a story whose opening is on screen, from block {@code from} on. */
    void articleRest(final String id, final int from, final Done<Article> done) {  // feature: reader.progressive
        io.execute(() -> {
            try {
                JSONObject o = call("GET", "/v1/articles/" + id + "?fetch=never&from_block=" + from, 15000);
                post(done, new Article(o.getJSONObject("data")), null);
            } catch (IOException | JSONException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    /** A story's opening, ahead of time, if the relay has its body (never asks the Mac); on the
     *  warming thread. {@code done} gets the relay's JSON (any thread), or null. */
    void head(final String id, final Done<JSONObject> done) {
        warm.execute(() -> {
            try {
                JSONObject data = call("GET", "/v1/articles/" + id + "?fetch=never&max_chars=" + HEAD_CHARS, 15000)
                        .getJSONObject("data");
                done.done("summary".equals(data.optString("completeness")) ? null : data, null);
            } catch (IOException | JSONException e) {
                done.done(null, e.getMessage());
            }
        });
    }

    /** What may have changed about a story kept on the TV: saved, liked, its topics. */
    void articleState(final String id, final Done<JSONObject> done) {
        io.execute(() -> {
            try {
                post(done, call("GET", "/v1/articles/" + id + "?fetch=never&fields=saved,reaction,topics,completeness", 10000)
                        .getJSONObject("data"), null);
            } catch (IOException | JSONException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    /** Reads a file of the TV's own off the main thread. */
    <T> void background(final java.util.concurrent.Callable<T> work, final Done<T> done) {
        io.execute(() -> {
            T value = null;
            try {
                value = work.call();
            } catch (Exception e) {
                value = null;
            }
            post(done, value, null);
        });
    }

    /**
     * The stories this TV may open next, most likely first: the relay has Apple News fetch the ones
     * it lacks, in that order, and forgets this TV's previous list. {@code done} gets the ids the
     * relay already has a body for. A relay older than 0.3 gets a background fetch for the first.
     */
    void interest(final String client, final List<String> ids, final Done<List<String>> done) {  // feature: front.warm
        io.execute(() -> {
            if (noInterest) {
                if (!ids.isEmpty()) prefetchNow(ids.get(0));
                return;
            }
            try {
                JSONArray a = new JSONArray();
                for (String id : ids) a.put(id);
                Net.Response r = send("PUT", "/v1/interest/" + client, null, 8000, new JSONObject().put("articles", a).toString());
                if (r.code == 404) {
                    noInterest = true;
                    if (!ids.isEmpty()) prefetchNow(ids.get(0));
                    return;
                }
                JSONArray states = json(r).getJSONObject("data").optJSONArray("articles");
                List<String> cached = new ArrayList<>();
                for (int i = 0; states != null && i < states.length(); i++) {
                    JSONObject s = states.optJSONObject(i);
                    if (s != null && "cached".equals(s.optString("state"))) cached.add(s.optString("id"));
                }
                post(done, cached, null);
            } catch (IOException | JSONException e) {
                // only a guess; never mind
            }
        });
    }

    /** This TV stops looking ahead (it went to the background). */
    void clearInterest(final String client) {
        if (noInterest) return;
        io.execute(() -> {
            try {
                send("DELETE", "/v1/interest/" + client, null, 5000, null);
            } catch (IOException e) {
                // the relay forgets it anyway when the list is replaced
            }
        });
    }

    /** Waits for a relay job; reports its final state ("succeeded", "failed", or still "running"). */
    void job(final String id, final int wait, final Done<String> done) {
        slow.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = call("GET", "/v1/jobs/" + id + "?wait=" + wait, (wait + 15) * 1000);
                    post(done, o.getJSONObject("data").optString("state"), null);
                } catch (IOException | JSONException e) {
                    post(done, null, e.getMessage());
                }
            }
        });
    }

    /** Asks the Mac to refresh Today in Apple News, scrolling {@code pages} screens of the feed
     *  there (a minute of work); returns the job id. */
    void refreshToday(final int pages, final Done<String> done) {
        slow.execute(() -> {
            try {
                JSONObject o = call("POST", "/v1/today/refresh?pages=" + pages, 20000);
                post(done, o.getJSONObject("data").optString("id"), null);
            } catch (IOException | JSONException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    /** Asks the Mac to get a story's body ahead of time, behind anything someone is waiting on. */
    void prefetch(final String id) {
        io.execute(() -> prefetchNow(id));
    }

    private void prefetchNow(String id) {
        try {
            call("POST", "/v1/articles/" + id + "/fetch?priority=background", 8000);
        } catch (IOException e) {
            // only a guess; never mind
        }
    }

    /** A list of stories: a channel's or topic's ({@code /v1/channels/{id}/articles}), or the ones
     *  related to a story ({@code /v1/articles/{id}/related}). */
    void stories(final String path, final Done<List<Story>> done) {
        io.execute(() -> {
            try {
                JSONObject o = call("GET", path, 20000);
                post(done, Story.list(o.optJSONArray("data")), null);
            } catch (IOException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    /** Opens a channel or topic ({@code kind}: channels or topics) in News on the Mac so it loads
     *  more of its stories; job id back. */
    void refreshTag(final String kind, final String id, final Done<String> done) {
        io.execute(() -> {
            try {
                JSONObject o = call("POST", "/v1/" + kind + "/" + id + "/refresh", 20000);
                post(done, o.getJSONObject("data").optString("id"), null);
            } catch (IOException | JSONException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    /** Saves a story in Apple News on the Mac, or takes it out of Saved Stories. Answers with the
     *  story's state there afterwards: {@code {saved, reaction}}. */
    void setSaved(final String id, final boolean saved, final Done<JSONObject> done) {
        storyAction(saved ? "PUT" : "DELETE", "/v1/saved/" + id, done);
    }

    /** Suggest More ("like") or Fewer ("dislike") Stories Like This in Apple News; null takes it back. */
    void setReaction(final String id, final String reaction, final Done<JSONObject> done) {
        storyAction(reaction == null ? "DELETE" : "PUT", "/v1/articles/" + id + "/reaction"
                + (reaction == null ? "" : "?reaction=" + reaction), done);
    }

    private void storyAction(final String method, final String path, final Done<JSONObject> done) {
        slow.execute(() -> {
            try {
                String sep = path.contains("?") ? "&" : "?";
                JSONObject job = call(method, path + sep + "wait=25", 40000).getJSONObject("data");
                for (int i = 0; i < 2 && ("queued".equals(job.optString("state")) || "running".equals(job.optString("state"))); i++) {
                    job = call("GET", "/v1/jobs/" + job.optString("id") + "?wait=25", 40000).getJSONObject("data");
                }
                if ("succeeded".equals(job.optString("state"))) post(done, job.optJSONObject("result"), null);
                else post(done, null, "Apple News on the Mac: " + job.optString("error", "it didn't happen"));
            } catch (IOException | JSONException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    /** What the Mac is reading in Apple News right now (or was, in the last half hour), like
     *  Handoff: {@code {article, on_screen, since, seen_at}}, or null when nothing is on offer. */
    void handoff(final Done<JSONObject> done) {
        io.execute(() -> {
            try {
                JSONObject o = call("GET", "/v1/handoff", 5000);
                post(done, o.optJSONObject("data"), null);
            } catch (IOException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    /** The relay's health (News running, jobs queued), for the options panel's footer. */
    void status(final Done<JSONObject> done) {
        io.execute(() -> {
            try {
                post(done, call("GET", "/v1/status", 5000), null);
            } catch (IOException e) {
                post(done, null, e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- cache

    private void save(String name, JSONObject o) {
        File tmp = new File(cache, name + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(new File(cache, name))) tmp.delete();
    }

    private JSONObject load(String name) {
        File f = new File(cache, name);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(Net.read(in, MAX_JSON * 3), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }
}
