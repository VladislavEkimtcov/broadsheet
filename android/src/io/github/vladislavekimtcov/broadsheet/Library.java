package io.github.vladislavekimtcov.broadsheet;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this TV remembers about reading, kept on the TV only (never sent to the Mac): the stories
 * opened here and how far into each, the likes and dislikes sent from here, and the last story,
 * for "Continue reading". The newest few hundred of each; one small JSON file.
 */
final class Library {
    private static final int KEEP_READ = 1500, KEEP_REACTIONS = 400;

    private final File file;
    /** Story id to how far it was read (0 to 1), oldest first. */
    private final LinkedHashMap<String, Float> read = new LinkedHashMap<>(64, 0.75f, true);
    private final LinkedHashMap<String, String> reactions = new LinkedHashMap<>(64, 0.75f, true);
    private JSONObject last;
    private boolean dirty;

    Library(File dir) {
        file = new File(dir, "library.json");  // feature: library.local
        load();
    }

    boolean isRead(String id) {
        return read.containsKey(id);
    }

    int readCount() {
        return read.size();
    }

    /** How far into the story the reader got, 0 if never opened here. */
    float position(String id) {  // feature: reader.resume
        Float f = read.get(id);
        return f == null ? 0f : f;
    }

    void setPosition(Story s, float fraction) {
        read.put(s.id, Math.max(0f, Math.min(1f, fraction)));
        trim(read, KEEP_READ);
        try {
            last = new JSONObject().put("story", s.json).put("at", System.currentTimeMillis());
        } catch (JSONException e) {
            last = null;
        }
        dirty = true;
    }

    /** Forgets that a story was read here: it is new again (not dimmed or hidden, starts at the top). */
    void markUnread(String id) {
        read.remove(id);
        Story s = last == null ? null : Story.from(last.optJSONObject("story"));
        if (s != null && s.id.equals(id)) last = null;
        dirty = true;
    }

    /** Counts a story as read without opening it (keeps a place already reached in it). */
    void markRead(String id) {
        Float f = read.get(id);
        read.put(id, f == null ? 1f : f);
        trim(read, KEEP_READ);
        dirty = true;
    }

    /** The last story read here when it was left unfinished in the past week, or null. */
    Story unfinished() {
        if (last == null || System.currentTimeMillis() - last.optLong("at") > 7 * 86400000L) return null;
        Story s = Story.from(last.optJSONObject("story"));
        if (s == null) return null;
        float f = position(s.id);
        return f > 0.04f && f < 0.9f ? s : null;
    }

    /** "like", "dislike" or null, as last sent from this TV (or seen on the Mac). */
    String reaction(String id) {
        return reactions.get(id);
    }

    void setReaction(String id, String reaction) {
        if (reaction == null) reactions.remove(id);
        else reactions.put(id, reaction);
        trim(reactions, KEEP_REACTIONS);
        dirty = true;
    }

    private static void trim(LinkedHashMap<String, ?> map, int keep) {
        Iterator<String> it = map.keySet().iterator();
        while (map.size() > keep && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private void load() {
        if (!file.isFile()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            JSONObject o = new JSONObject(new String(Net.read(in, 4 * 1024 * 1024), StandardCharsets.UTF_8));
            JSONObject r = o.optJSONObject("read");
            for (Iterator<String> it = r == null ? null : r.keys(); it != null && it.hasNext(); ) {
                String k = it.next();
                read.put(k, (float) r.optDouble(k));
            }
            JSONObject x = o.optJSONObject("reactions");
            for (Iterator<String> it = x == null ? null : x.keys(); it != null && it.hasNext(); ) {
                String k = it.next();
                reactions.put(k, x.optString(k));
            }
            last = o.optJSONObject("last");
        } catch (IOException | JSONException e) {
            // start afresh
        }
    }

    /** Writes the file if anything changed; cheap to call often. */
    void save() {
        if (!dirty) return;
        dirty = false;
        try {
            JSONObject r = new JSONObject();
            for (Map.Entry<String, Float> e : read.entrySet()) r.put(e.getKey(), Math.round(e.getValue() * 1000) / 1000.0);
            JSONObject x = new JSONObject();
            for (Map.Entry<String, String> e : reactions.entrySet()) x.put(e.getKey(), e.getValue());
            JSONObject o = new JSONObject().put("read", r).put("reactions", x);
            if (last != null) o.put("last", last);
            File tmp = new File(file.getPath() + ".part");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) tmp.delete();
        } catch (IOException | JSONException e) {
            dirty = true;
        }
    }
}
