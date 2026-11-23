package io.github.vladislavekimtcov.broadsheet;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Pictures for cards and the reader. Downloads once to a disk cache, decodes at the size shown
 * (bounds first, then {@code inSampleSize}, RGB_565 for cards), keeps an LRU of decoded bitmaps
 * (a sixth of the heap), and drops a target's job as soon as it scrolls away or gets another picture.
 */
final class Images {
    interface Target {
        /** Main thread. {@code fresh}: just decoded (fade it in); otherwise from memory. */
        void onImage(Bitmap b, boolean fresh);
    }

    private static final int MAX_BYTES = 6 * 1024 * 1024;

    private final File dir;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> mem;
    private final ExecutorService pool = Executors.newFixedThreadPool(3, new ThreadFactory() {
        @Override
        public Thread newThread(final Runnable r) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }
            }, "images");
            t.setDaemon(true);
            return t;
        }
    });
    private final Map<Target, Job> jobs = new HashMap<>();

    Images(File dir) {
        this.dir = dir;
        dir.mkdirs();
        int budget = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 6);
        mem = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };
    }

    /** A picture at about w x h pixels (cropped to fill by the view). {@code quality}: ARGB_8888 for the reader. */
    void load(Target t, String url, int w, int h, boolean quality) {
        if (url == null) {
            cancel(t);
            return;
        }
        String key = url + "#" + w + "x" + h + (quality ? "q" : "");
        Bitmap b = mem.get(key);
        if (b != null) {
            cancel(t);
            t.onImage(b, false);
            return;
        }
        Job j = jobs.get(t);
        if (j != null && j.key.equals(key)) return;
        cancel(t);
        j = new Job(t, key, url, w, h, quality);
        jobs.put(t, j);
        j.future = pool.submit(j);
    }

    void cancel(Target t) {
        Job j = jobs.remove(t);
        if (j != null && j.future != null) j.future.cancel(true);
    }

    /** Leaving the app: forget decoded pictures (the disk copies stay). */
    void trim() {
        mem.evictAll();
    }

    /** Keeps the disk cache under {@code budget} bytes, oldest first. Call off the main thread. */
    void prune(long budget) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File x, File y) {
                return Long.compare(y.lastModified(), x.lastModified());
            }
        });
        long total = 0;
        for (File f : files) {
            total += f.length();
            if (total > budget) f.delete();
        }
    }

    private final class Job implements Runnable {
        final Target target;
        final String key, url;
        final int w, h;
        final boolean quality;
        Future<?> future;

        Job(Target target, String key, String url, int w, int h, boolean quality) {
            this.target = target;
            this.key = key;
            this.url = url;
            this.w = w;
            this.h = h;
            this.quality = quality;
        }

        @Override
        public void run() {
            try {
                File f = new File(dir, hash(url));
                if (!f.isFile() && !download(url, f)) return;
                if (Thread.currentThread().isInterrupted()) return;
                f.setLastModified(System.currentTimeMillis());
                final Bitmap b = decode(f, w, h, quality);
                if (b == null) return;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        mem.put(key, b);
                        if (jobs.get(target) != Job.this) return;
                        jobs.remove(target);
                        target.onImage(b, true);
                    }
                });
            } catch (IOException | RuntimeException e) {
                // no picture then: the view keeps its placeholder
            }
        }
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder(40);
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static boolean download(String url, File f) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", Net.userAgent);
        File tmp = new File(f.getPath() + ".part");
        try {
            if (c.getResponseCode() != 200) return false;
            String type = c.getContentType();
            if (type != null && !type.startsWith("image") && !type.startsWith("application/octet")) return false;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    out.write(buf, 0, n);
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("too big");
                }
            }
            return tmp.renameTo(f);
        } finally {
            c.disconnect();
            tmp.delete();
        }
    }

    /** Decodes at least w x h (so a centre crop fills it) and no more than twice that. */
    static Bitmap decode(File f, int w, int h, boolean quality) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = quality ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }
}
