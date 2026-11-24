package io.github.vladislavekimtcov.broadsheet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Stories kept on the TV (opened, or fetched ahead because the reader was heading for them), so
 * they open at once, even while the Mac sleeps. One file per story: the relay's article JSON,
 * the opening only until the rest has been read. A few megabytes; the least recently opened go
 * first, nothing stays longer than two weeks. Call from background threads.
 */
final class Bodies {  // feature: reader.body_cache
    static final long BUDGET = 8L * 1024 * 1024;
    static final int MAX_FILES = 400;
    static final long MAX_AGE = 14L * 24 * 3600 * 1000;

    private final File dir;
    private int writes;

    Bodies(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    private File file(String id) {
        return new File(dir, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    /** The story as kept, or null. Counts as a use (it stays longer). */
    synchronized JSONObject get(String id) {
        File f = file(id);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(new String(Net.read(in, 4 * 1024 * 1024), StandardCharsets.UTF_8));
            f.setLastModified(System.currentTimeMillis());
            return o;
        } catch (IOException | JSONException e) {
            f.delete();
            return null;
        }
    }

    synchronized boolean has(String id) {
        return file(id).isFile();
    }

    /** Keeps a story the relay sent with its body (a summary isn't worth keeping). */
    synchronized void put(JSONObject data) {
        if (data == null || data.optJSONObject("body") == null || "summary".equals(data.optString("completeness"))) return;
        String id = data.optString("id");
        if (id.isEmpty()) return;
        JSONObject kept = data;
        JSONObject old = readQuietly(id);
        // A head doesn't replace a story kept whole.
        if (old != null && nextBlock(old) < 0 && nextBlock(data) >= 0 && blocks(old) >= blocks(data)) return;
        write(id, kept);
    }

    /** Adds the rest of a story to its opening; returns the whole, or null if the opening is gone. */
    synchronized JSONObject merge(String id, JSONObject rest) {
        JSONObject kept = readQuietly(id);
        JSONObject more = rest == null ? null : rest.optJSONObject("body");
        if (kept == null || more == null) return null;
        try {
            JSONObject body = kept.getJSONObject("body");
            JSONArray blocks = body.optJSONArray("blocks");
            if (blocks == null) body.put("blocks", blocks = new JSONArray());
            if (body.optInt("next_block", -1) != more.optInt("from_block", -2)) return null;  // not the part that follows
            JSONArray add = more.optJSONArray("blocks");
            for (int i = 0; add != null && i < add.length(); i++) blocks.put(add.get(i));
            body.put("next_block", more.isNull("next_block") ? JSONObject.NULL : more.opt("next_block"));
            write(id, kept);
            return kept;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Where the rest of a kept story starts, or -1 when it's whole. */
    static int nextBlock(JSONObject data) {
        JSONObject body = data == null ? null : data.optJSONObject("body");
        return body == null || body.isNull("next_block") ? -1 : body.optInt("next_block", -1);
    }

    private static int blocks(JSONObject data) {
        JSONObject body = data.optJSONObject("body");
        JSONArray a = body == null ? null : body.optJSONArray("blocks");
        return a == null ? 0 : a.length();
    }

    private JSONObject readQuietly(String id) {
        File f = file(id);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(Net.read(in, 4 * 1024 * 1024), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private void write(String id, JSONObject o) {
        File f = file(id), tmp = new File(f.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
        if (++writes % 50 == 0) prune();
    }

    /** Within budget: drops anything over two weeks old, then the least recently used. */
    synchronized void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File x, File y) {
                return Long.compare(y.lastModified(), x.lastModified());
            }
        });
        long now = System.currentTimeMillis(), total = 0;
        int n = 0;
        for (File f : files) {
            total += f.length();
            n++;
            if (now - f.lastModified() > MAX_AGE || total > BUDGET || n > MAX_FILES) f.delete();
        }
    }
}
