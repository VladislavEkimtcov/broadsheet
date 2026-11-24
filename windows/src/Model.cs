using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows.Media;

namespace Broadsheet
{
    /// <summary>One headline as the relay lists it: what a card needs, nothing more.</summary>
    sealed class Story
    {
        public readonly string Id, Title, Excerpt, Byline, Channel, ChannelId, Image, AppleNewsUrl, Url;
        public readonly int ImageWidth, ImageHeight, Minutes;
        readonly List<string> thumbUrls = new List<string>();
        readonly List<int> thumbWidths = new List<int>();
        public readonly long Published;
        public readonly bool Paid;
        /// <summary>full | preview | summary: how much of the story the relay has.</summary>
        public readonly string Completeness;
        /// <summary>The publisher's colours from Apple News; Background is transparent when none.</summary>
        public readonly Color Background, AccentRaw;
        /// <summary>As the relay sent it, to keep a story (the last one read) across restarts.</summary>
        public readonly JObj Json;

        Story(JObj o)
        {
            Id = o.Opt("id");
            Title = o.Opt("title");
            Excerpt = o.Str("excerpt");
            Byline = o.Str("byline");
            JObj ch = o.Obj("channel");
            Channel = ch == null ? null : ch.Str("name");
            ChannelId = ch == null ? null : ch.Str("id");
            // The sizes Apple News has of the picture (300, 750, 1600, 2048 wide, as a rule).
            JObj largest = null;
            List<object> thumbs = o.Arr("thumbnails");
            if (thumbs != null)
            {
                foreach (object x in thumbs)
                {
                    JObj t = x as JObj;
                    if (t == null || t.Str("url") == null) continue;
                    thumbUrls.Add(t.Str("url"));
                    thumbWidths.Add(t.Int("width"));
                    if (largest == null || t.Int("width") > largest.Int("width")) largest = t;
                }
            }
            JObj img = o.Obj("image") ?? largest; // the front page leaves "image" out: it's the largest thumbnail
            Image = img == null ? null : img.Str("url");
            ImageWidth = img == null ? 0 : img.Int("width");
            ImageHeight = img == null ? 0 : img.Int("height");
            Published = Dates.Parse(o.Str("published_at"));
            Paid = "news_plus" == o.Opt("access");
            Completeness = o.Opt("completeness", "summary");
            Minutes = o.Int("reading_minutes");
            AppleNewsUrl = o.Str("apple_news_url");
            Url = o.Str("url");
            JObj colors = o.Obj("colors");
            Background = ParseColor(colors == null ? null : colors.Str("background"), Colors.Transparent);
            AccentRaw = ParseColor(colors == null ? null : colors.Str("accent"), Color.FromRgb(0x8A, 0x83, 0x78));
            Json = o;
        }

        /// <summary>The publisher's colour, made readable on the current page.</summary>
        public Color Accent { get { return Ui.Readable(AccentRaw); } }

        /// <summary>The smallest picture at least widthPx wide (a tile needs the 750 one, not 2048), else the largest.</summary>
        public string ImageFor(int widthPx)
        {
            string best = null;
            int bestWidth = int.MaxValue;
            for (int i = 0; i < thumbUrls.Count; i++)
            {
                if (thumbWidths[i] >= widthPx && thumbWidths[i] < bestWidth)
                {
                    best = thumbUrls[i];
                    bestWidth = thumbWidths[i];
                }
            }
            return best ?? Image;
        }

        public static Story From(JObj o)
        {
            return o == null || o.Opt("id").Length == 0 ? null : new Story(o);
        }

        public static List<Story> List(List<object> a)
        {
            List<Story> out_ = new List<Story>();
            if (a == null) return out_;
            foreach (object x in a)
            {
                Story s = From(x as JObj);
                if (s != null && s.Title.Length > 0) out_.Add(s);
            }
            return out_;
        }

        public static Color ParseColor(string s, Color fallback)
        {
            if (s == null || s.Length < 7 || s[0] != '#') return fallback;
            uint v;
            if (!uint.TryParse(s.Substring(1), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out v)) return fallback;
            if (s.Length == 7) return Color.FromRgb((byte)(v >> 16), (byte)(v >> 8), (byte)v);
            if (s.Length == 9) return Color.FromArgb((byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v);
            return fallback;
        }

        /// <summary>"NPR · 2h ago" for cards.</summary>
        public string Dateline(long now)
        {
            string ago = Dates.Ago(Published, now);
            if (Channel == null) return ago;
            return ago.Length == 0 ? Channel : Channel + "  ·  " + ago;
        }
    }

    sealed class Section
    {
        public readonly string Id, Title;
        /// <summary>Grows as more pages arrive (Next).</summary>
        public readonly List<Story> Stories;
        /// <summary>The relay's link to the next page of this section, or null when there is no more.</summary>
        public string Next;
        /// <summary>Stories in the whole section; how many of them Apple News Today itself carries.</summary>
        public int Total, Today;

        public Section(string id, string title, List<Story> stories)
        {
            Id = id;
            Title = title;
            Stories = new List<Story>(stories);
            Total = stories.Count;
        }
    }

    /// <summary>A story with its body, as the relay serves it (already cleaned: no ads, no promotions).</summary>
    sealed class Article
    {
        public sealed class Block
        {
            /// <summary>heading, paragraph, quote, caption, image, video, divider.</summary>
            public string Type, Text, Url, Caption, Stream;
            public int Level, Width, Height, More;
        }

        public readonly Story Story;
        public readonly string Subtitle, Byline, Completeness, PendingJob, JobState, Reaction;
        public readonly bool Truncated, JobFailed, Saved;
        public readonly List<Block> Blocks = new List<Block>();
        /// <summary>The story's topics as {id, name}, for "More on …".</summary>
        public readonly List<string[]> Topics = new List<string[]>();
        /// <summary>Where the rest of the story starts, when only its opening came; -1 when it's whole.
        /// Moves on as the rest is added (Reader.AppendBlocks).</summary>
        public int NextBlock;
        /// <summary>As the relay sent it, for the copy kept on this computer (Bodies).</summary>
        public readonly JObj Json;
        /// <summary>Words in the whole story, as the relay counted them (an opening has fewer of its own).</summary>
        readonly int wordCount;

        public Article(JObj data)
        {
            Story = Story.From(data);
            Json = data;
            Completeness = data.Opt("completeness", "summary");
            JObj body = data.Obj("body");
            NextBlock = body == null || body.IsNull("next_block") ? -1 : body.Int("next_block", -1);
            wordCount = body == null ? 0 : body.Int("word_count");
            Subtitle = body == null ? null : body.Str("subtitle");
            Byline = body == null ? data.Str("byline") : (body.Str("byline") ?? data.Str("byline"));
            Truncated = body != null && body.Bool("truncated");
            JObj job = data.Obj("fetch_job");
            string state = job == null ? null : job.Opt("state");
            PendingJob = job != null && (state == "queued" || state == "running") ? job.Opt("id") : null;
            JobState = PendingJob != null ? state : null;
            JobFailed = job != null && (state == "failed" || (state == "succeeded" && body == null));
            Saved = data.Bool("saved");
            Reaction = data.Str("reaction");
            List<object> t = data.Arr("topics");
            if (t != null)
            {
                foreach (object x in t)
                {
                    JObj o = x as JObj;
                    if (o != null && o.Str("id") != null && o.Str("name") != null) Topics.Add(new[] { o.Str("id"), o.Str("name") });
                }
            }
            List<object> a = body == null ? null : body.Arr("blocks");
            if (a == null) return;
            foreach (object x in a)
            {
                JObj b = x as JObj;
                if (b == null) continue;
                string type = b.Opt("type");
                switch (type)
                {
                    case "paragraph":
                    case "heading":
                    case "quote":
                    case "caption":
                        string text = b.Str("text");
                        if (text != null) Blocks.Add(new Block { Type = type, Text = text, Level = b.Int("level", 2) });
                        break;
                    case "image":
                        AddImage(b, 0);
                        break;
                    case "gallery":
                        List<object> imgs = b.Arr("images");
                        if (imgs != null && imgs.Count > 0) AddImage(imgs[0] as JObj, imgs.Count - 1);
                        break;
                    case "video":
                        {
                            string poster = b.Str("poster"), stream = b.Str("url");
                            if (poster == null && stream == null) break;
                            double ratio = b.Num("aspect_ratio", 16 / 9.0);
                            Blocks.Add(new Block
                            {
                                Type = "video", Url = poster, Caption = b.Str("caption"), Stream = stream,
                                Width = 1000, Height = (int)Math.Round(1000 / Math.Max(0.3, ratio))
                            });
                            break;
                        }
                    case "divider":
                        if (Blocks.Count > 0) Blocks.Add(new Block { Type = "divider" });
                        break;
                }
            }
        }

        void AddImage(JObj b, int more)
        {
            if (b == null) return;
            string url = b.Str("url");
            if (url == null) return;
            Blocks.Add(new Block { Type = "image", Url = url, Caption = b.Str("caption"), Width = b.Int("width"), Height = b.Int("height"), More = more });
        }

        /// <summary>Words in the story, for the reading time: the relay's count of the whole, or the blocks'.</summary>
        public int Words()
        {
            int n = 0;
            foreach (Block b in Blocks)
            {
                if (b.Text == null) continue;
                bool inWord = false;
                foreach (char c in b.Text)
                {
                    bool ws = char.IsWhiteSpace(c);
                    if (!ws && !inWord) n++;
                    inWord = !ws;
                }
            }
            return Math.Max(n, wordCount);
        }
    }
}
