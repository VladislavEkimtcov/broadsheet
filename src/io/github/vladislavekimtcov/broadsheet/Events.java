package io.github.vladislavekimtcov.broadsheet;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Follows the relay's event stream ({@code /v1/events}) on a thread of its own, so the page changes
 * when the Mac's does instead of on a timer: {@code hello} on every connection, {@code front.updated}
 * when sections, Latest or Saved change, {@code article.body_captured} when a story arrives,
 * {@code job.updated} as the Mac works. Reconnects by itself (1 s, then doubling to 30 s).
 */
final class Events {  // feature: relay.events
    interface Listener {
        /** Main thread. */
        void onEvent(String type, JSONObject data);

        /** Main thread: the stream connected or dropped. */
        void onStream(boolean up);
    }

    /** The relay sends a comment every 15 s; three missed means the line is dead. */
    private static final int READ_TIMEOUT_MS = 45000;

    private final Relay relay;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running, up;
    private volatile HttpURLConnection conn;
    private volatile long downSince = SystemClock.elapsedRealtime();
    private Thread thread;

    Events(Relay relay, Listener listener) {
        this.relay = relay;
        this.listener = listener;
    }

    boolean connected() {
        return up;
    }

    /** Milliseconds the stream has been down (0 while up). */
    long downFor() {
        return up ? 0 : SystemClock.elapsedRealtime() - downSince;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "events");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        final HttpURLConnection c = conn;
        if (c != null) new Thread(c::disconnect, "events-stop").start();  // unblocks the read
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop() {
        long backoff = 1000;
        while (running) {
            String base = relay.base();
            if (base == null) {
                if (!pause(2000)) return;
                continue;
            }
            long opened = SystemClock.elapsedRealtime();
            try {
                HttpURLConnection c = Net.stream(base + "/v1/events", relay.token(), READ_TIMEOUT_MS);
                conn = c;
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                setUp(true);
                Sse sse = new Sse();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; running && (line = r.readLine()) != null; ) {
                        final Sse.Event e = sse.line(line);
                        if (e == null) continue;
                        final JSONObject data = parse(e.data);
                        ui.post(() -> {
                            if (running) listener.onEvent(e.type, data);
                        });
                    }
                }
            } catch (IOException | RuntimeException e) {
                // dropped, or the relay isn't there: try again shortly
            } finally {
                HttpURLConnection c = conn;
                conn = null;
                if (c != null) c.disconnect();
                setUp(false);
            }
            if (SystemClock.elapsedRealtime() - opened > 30000) backoff = 1000;  // it had been fine
            if (!pause(backoff)) return;
            backoff = Math.min(30000, backoff * 2);
        }
    }

    private void setUp(final boolean value) {
        if (up == value) return;
        up = value;
        if (!value) downSince = SystemClock.elapsedRealtime();
        ui.post(() -> {
            if (running) listener.onStream(value);
        });
    }

    private boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return running;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static JSONObject parse(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
