package io.github.vladislavekimtcov.broadsheet;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One headline as the relay lists it: what a card needs, nothing more. */
final class Story {
    final String id, title, excerpt, byline, channel, channelId, channelLogo, image;
    /** The publisher's page (or Apple News's), for "Open in the browser" on a phone. */
    final String link;
    final int imageWidth, imageHeight;
    private final String[] thumbUrls;
    private final int[] thumbWidths;
    final long published;
    final boolean paid;
    /** full | preview | summary: how much of the story the relay has. */
    final String completeness;
    final int minutes;
    /** The publisher's colours, from Apple News: used as the card's placeholder and accent. */
    final int background, accentRaw;
    /** As the relay sent it, to keep a story (the last one read) across restarts. */
    final JSONObject json;

    private Story(JSONObject o) {
        id = o.optString("id");
        title = o.optString("title");
        excerpt = str(o, "excerpt");
        link = str(o, "url") != null ? str(o, "url") : str(o, "apple_news_url");
        byline = str(o, "byline");
        JSONObject ch = o.optJSONObject("channel");
        channel = ch == null ? null : str(ch, "name");
        channelId = ch == null ? null : str(ch, "id");
        channelLogo = ch == null ? null : str(ch, "logo");
        // The sizes Apple News has of the picture (300, 750, 1600, 2048 wide, as a rule).
        JSONArray thumbs = o.optJSONArray("thumbnails");
        List<String> urls = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        JSONObject largest = null;
        for (int i = 0; thumbs != null && i < thumbs.length(); i++) {
            JSONObject t = thumbs.optJSONObject(i);
            if (t == null || str(t, "url") == null) continue;
            urls.add(t.optString("url"));
            widths.add(t.optInt("width"));
            if (largest == null || t.optInt("width") > largest.optInt("width")) largest = t;
        }
        thumbUrls = urls.toArray(new String[0]);
        thumbWidths = new int[widths.size()];
        for (int i = 0; i < thumbWidths.length; i++) thumbWidths[i] = widths.get(i);
        JSONObject img = o.optJSONObject("image");
        if (img == null) img = largest;  // the front page leaves "image" out: it's the largest thumbnail
        image = img == null ? null : str(img, "url");
        imageWidth = img == null ? 0 : img.optInt("width");
        imageHeight = img == null ? 0 : img.optInt("height");
        published = Dates.parse(str(o, "published_at"));
        paid = "news_plus".equals(o.optString("access"));
        completeness = o.optString("completeness", "summary");
        minutes = o.optInt("reading_minutes");
        JSONObject colors = o.optJSONObject("colors");
        background = color(colors, "background", 0);  // 0: none, the page's own surface
        accentRaw = color(colors, "accent", 0xFF8A8378);
        json = o;
    }

    /** The smallest picture at least {@code widthPx} wide (a tile needs the 750 one, not 2048), else the largest. */
    String imageFor(int widthPx) {
        String best = null;
        int bestWidth = Integer.MAX_VALUE;
        for (int i = 0; i < thumbUrls.length; i++) {
            int w = thumbWidths[i];
            if (w >= widthPx && w < bestWidth) {
                best = thumbUrls[i];
                bestWidth = w;
            }
        }
        return best != null ? best : image;
    }

    /** The publisher's colour, made readable on the current page. */
    int accent() {
        return Ui.readable(accentRaw);
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

    /** "NPR · 2h ago" for cards. */
    String dateline(long now) {
        String ago = Dates.ago(published, now);
        if (channel == null) return ago;
        return ago.isEmpty() ? channel : channel + "  ·  " + ago;
    }
}
