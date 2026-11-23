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
        final List<Story> stories;

        Section(String id, String title, List<Story> stories) {
            this.id = id;
            this.title = title;
            this.stories = stories;
        }
    }

    static final String SERVICE = "_newsrelay._tcp.";
    /** The relay's default port, for the subnet scan. */
    static final int PORT = 8787;
    private static final int MAX_JSON = 8 * 1024 * 1024;

    private final Context context;
    private final File cache;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private volatile String base, token;
    private volatile String origin = "";

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

    private void readConfig() {
        File dir = context.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, "relay.conf");
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
    private void scan(final AtomicBoolean finished, final Found found) {
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

    private JSONObject call(String method, String path, int readTimeoutMs) throws IOException {
        String b = base;
        if (b == null) throw new IOException("Apple News Relay hasn't been found yet.");
        Net.Response r;
        try {
            r = Net.request(method, b + path, null, token, readTimeoutMs, MAX_JSON);
        } catch (IOException e) {
            throw new IOException("Can't reach Apple News Relay at " + where() + ". Is the Mac awake?", e);
        }
        if (r.code == 401 || r.code == 403) throw new IOException("The relay at " + where() + " turned this TV away (HTTP " + r.code + ").");
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

    /** The front page: Today's sections from Apple News, plus Latest and Saved from the archive. */
    void front(final Done<List<Section>> done) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject sections = call("GET", "/v1/sections?per_section=30", 20000);
                    JSONObject latest = call("GET", "/v1/articles?limit=45", 20000);
                    JSONObject saved = call("GET", "/v1/saved?limit=45&fetch_missing=true", 20000);
                    save("front.json", new JSONObject().put("sections", sections).put("latest", latest).put("saved", saved));
                    post(done, assemble(sections, latest, saved), null);
                } catch (IOException | JSONException e) {
                    post(done, null, e.getMessage());
                }
            }
        });
    }

    /** The last front page that loaded, for an instant start; null if none. */
    List<Section> cachedFront() {
        try {
            JSONObject o = load("front.json");
            if (o == null) return null;
            return assemble(o.optJSONObject("sections"), o.optJSONObject("latest"), o.optJSONObject("saved"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    long cachedFrontTime() {
        return new File(cache, "front.json").lastModified();
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

    /** One story with its body. {@code wait}: seconds the relay may spend getting it from Apple News. */
    void article(final String id, final int wait, final Done<Article> done) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = call("GET", "/v1/articles/" + id + "?wait=" + wait, (wait + 15) * 1000);
                    post(done, new Article(o.getJSONObject("data")), null);
                } catch (IOException | JSONException e) {
                    post(done, null, e.getMessage());
                }
            }
        });
    }

    /** Waits for a relay job; reports its final state ("succeeded", "failed", or still "running"). */
    void job(final String id, final int wait, final Done<String> done) {
        io.execute(new Runnable() {
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

    /** Asks the Mac to refresh Today in Apple News (a minute of work there); returns the job id. */
    void refreshToday(final Done<String> done) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = call("POST", "/v1/today/refresh?pages=6", 20000);
                    post(done, o.getJSONObject("data").optString("id"), null);
                } catch (IOException | JSONException e) {
                    post(done, null, e.getMessage());
                }
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
