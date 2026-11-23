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
        /** video: the stream (HLS or MP4) when the publisher gave one. */
        String stream;

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
    /** queued or running, while {@link #pendingJob} is set. */
    final String jobState;
    final boolean jobFailed;
    /** In Saved Stories on the Mac; liked ("like"), disliked ("dislike") or neither (null). */
    final boolean saved;
    final String reaction;
    /** The story's topics as {id, name}, for "More on …". */
    final List<String[]> topics = new ArrayList<>();
    /** Where the rest of the story starts, when only its opening came; -1 when it's whole.
     *  Moves on as the rest is added ({@link Reader#appendBlocks}). */
    int nextBlock;
    /** As the relay sent it, for the copy kept on the TV ({@link Bodies}). */
    final JSONObject json;
    /** Words in the whole story, as the relay counted them (an opening has fewer of its own). */
    private final int wordCount;

    Article(JSONObject data) {
        story = Story.from(data);
        json = data;
        completeness = data.optString("completeness", "summary");
        JSONObject body = data.optJSONObject("body");
        nextBlock = body == null || body.isNull("next_block") ? -1 : body.optInt("next_block", -1);
        wordCount = body == null ? 0 : body.optInt("word_count");
        subtitle = body == null ? null : Story.str(body, "subtitle");
        byline = body == null ? Story.str(data, "byline") : first(Story.str(body, "byline"), Story.str(data, "byline"));
        truncated = body != null && body.optBoolean("truncated");
        JSONObject job = data.optJSONObject("fetch_job");
        String state = job == null ? null : job.optString("state");
        pendingJob = job != null && ("queued".equals(state) || "running".equals(state)) ? job.optString("id") : null;
        jobState = pendingJob != null ? state : null;
        jobFailed = job != null && ("failed".equals(state)
                || ("succeeded".equals(state) && body == null));
        saved = data.optBoolean("saved");
        reaction = Story.str(data, "reaction");
        JSONArray t = data.optJSONArray("topics");
        for (int i = 0; t != null && i < t.length(); i++) {
            JSONObject o = t.optJSONObject(i);
            if (o != null && Story.str(o, "id") != null && Story.str(o, "name") != null) {
                topics.add(new String[] {o.optString("id"), o.optString("name")});
            }
        }
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
                case "video": {
                    String poster = Story.str(b, "poster"), stream = Story.str(b, "url");
                    if (poster == null && stream == null) break;
                    double ratio = b.optDouble("aspect_ratio", 16 / 9.0);
                    Block v = new Block("video", null, poster, Story.str(b, "caption"), 0, 1000,
                            (int) Math.round(1000 / Math.max(0.3, ratio)), 0);
                    v.stream = stream;
                    blocks.add(v);
                    break;
                }
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

    /** Words in the story, for the reading time: the relay's count of the whole, or the blocks'. */
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
        return Math.max(n, wordCount);
    }
}
