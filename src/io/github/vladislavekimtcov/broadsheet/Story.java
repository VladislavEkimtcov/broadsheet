package io.github.vladislavekimtcov.broadsheet;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One headline as the relay lists it: what a card needs, nothing more. */
final class Story {
    final String id, title, excerpt, byline, channel, channelLogo, image;
    final int imageWidth, imageHeight;
    final long published;
    final boolean paid;
    /** full | preview | summary: how much of the story the relay has. */
    final String completeness;
    final int minutes;
    /** The publisher's colours, from Apple News: used as the card's placeholder and accent. */
    final int background, accent;

    private Story(JSONObject o) {
        id = o.optString("id");
        title = o.optString("title");
        excerpt = str(o, "excerpt");
        byline = str(o, "byline");
        JSONObject ch = o.optJSONObject("channel");
        channel = ch == null ? null : str(ch, "name");
        channelLogo = ch == null ? null : str(ch, "logo");
        JSONObject img = o.optJSONObject("image");
        image = img == null ? null : str(img, "url");
        imageWidth = img == null ? 0 : img.optInt("width");
        imageHeight = img == null ? 0 : img.optInt("height");
        published = Dates.parse(str(o, "published_at"));
        paid = "news_plus".equals(o.optString("access"));
        completeness = o.optString("completeness", "summary");
        minutes = o.optInt("reading_minutes");
        JSONObject colors = o.optJSONObject("colors");
        background = color(colors, "background", Ui.SURFACE_HI);
        accent = readable(color(colors, "accent", Ui.MUTED));
    }

    static Story from(JSONObject o) {
        return o == null || o.optString("id").isEmpty() ? null : new Story(o);
    }

    static List<Story> list(JSONArray a) {
        List<Story> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            Story s = from(a.optJSONObject(i));
            if (s != null && !s.title.isEmpty()) out.add(s);
        }
        return out;
    }

    static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String s = o.optString(key, null);
        return s == null || s.isEmpty() ? null : s;
    }

    private static int color(JSONObject colors, String key, int fallback) {
        String s = colors == null ? null : str(colors, key);
        if (s == null) return fallback;
        try {
            return Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Publisher accents are picked for white pages; lift dark ones so they read on our dark one. */
    private static int readable(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        if (hsv[2] < 0.75f) hsv[2] = 0.75f + hsv[2] * 0.25f;
        if (hsv[1] > 0.75f) hsv[1] = 0.75f;
        return Color.HSVToColor(hsv);
    }

    /** "NPR · 2h ago" for cards. */
    String dateline(long now) {
        String ago = Dates.ago(published, now);
        if (channel == null) return ago;
        return ago.isEmpty() ? channel : channel + "  ·  " + ago;
    }
}
