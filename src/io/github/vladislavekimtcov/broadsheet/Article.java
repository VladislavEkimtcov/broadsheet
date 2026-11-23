package io.github.vladislavekimtcov.broadsheet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A story with its body, as the relay serves it (already cleaned: no ads, no promotions). */
final class Article {
    static final class Block {
        /** heading, paragraph, quote, caption, image, gallery, video, divider. */
        final String type, text, url, caption;
        final int level, width, height, more;

        Block(String type, String text, String url, String caption, int level, int width, int height, int more) {
            this.type = type;
            this.text = text;
            this.url = url;
            this.caption = caption;
            this.level = level;
            this.width = width;
            this.height = height;
            this.more = more;
        }
    }

    final Story story;
    final String subtitle, byline;
    final List<Block> blocks = new ArrayList<>();
    /** full | preview | summary. */
    final String completeness;
    final boolean truncated;
    /** A relay job still fetching the body from Apple News, or null. */
    final String pendingJob;
    final boolean jobFailed;

    Article(JSONObject data) {
        story = Story.from(data);
        completeness = data.optString("completeness", "summary");
        JSONObject body = data.optJSONObject("body");
        subtitle = body == null ? null : Story.str(body, "subtitle");
        byline = body == null ? Story.str(data, "byline") : first(Story.str(body, "byline"), Story.str(data, "byline"));
        truncated = body != null && body.optBoolean("truncated");
        JSONObject job = data.optJSONObject("fetch_job");
        String state = job == null ? null : job.optString("state");
        pendingJob = job != null && ("queued".equals(state) || "running".equals(state)) ? job.optString("id") : null;
        jobFailed = job != null && ("failed".equals(state)
                || ("succeeded".equals(state) && body == null));
        JSONArray a = body == null ? null : body.optJSONArray("blocks");
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject b = a.optJSONObject(i);
            if (b == null) continue;
            String type = b.optString("type");
            switch (type) {
                case "paragraph":
                case "heading":
                case "quote":
                case "caption":
                    String text = Story.str(b, "text");
                    if (text != null) blocks.add(new Block(type, text, null, null, b.optInt("level", 2), 0, 0, 0));
                    break;
                case "image":
                    image(b, 0);
                    break;
                case "gallery":
                    JSONArray imgs = b.optJSONArray("images");
                    if (imgs != null && imgs.length() > 0) image(imgs.optJSONObject(0), imgs.length() - 1);
                    break;
                case "video":
                    String poster = Story.str(b, "poster");
                    if (poster != null) blocks.add(new Block("video", null, poster, "Video", 0, 0, 0, 0));
                    break;
                case "divider":
                    if (!blocks.isEmpty()) blocks.add(new Block("divider", null, null, null, 0, 0, 0, 0));
                    break;
                default:
                    break; // embeds and link buttons can't be followed on a TV
            }
        }
    }

    private void image(JSONObject b, int more) {
        if (b == null) return;
        String url = Story.str(b, "url");
        if (url == null) return;
        blocks.add(new Block("image", null, url, Story.str(b, "caption"), 0, b.optInt("width"), b.optInt("height"), more));
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }

    /** Words in the text blocks, for the reading time. */
    int words() {
        int n = 0;
        for (Block b : blocks) {
            if (b.text == null) continue;
            boolean in = false;
            for (int i = 0; i < b.text.length(); i++) {
                boolean ws = Character.isWhitespace(b.text.charAt(i));
                if (!ws && !in) n++;
                in = !ws;
            }
        }
        return n;
    }
}
