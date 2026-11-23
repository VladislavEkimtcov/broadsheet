using System;
using System.Collections.Generic;

namespace Broadsheet
{
    /// <summary>
    /// The tests, as a console program built by the in-box csc (no xUnit, no NuGet): build.cmd test.
    /// Covers the parts with no window: JSON, dates, sunrise and sunset, the relay's story records.
    /// </summary>
    static class Tests
    {
        static int failures;

        static void Check(bool ok, string name)
        {
            Console.WriteLine((ok ? "ok    " : "FAIL  ") + name);
            if (!ok) failures++;
        }

        static long At(TimeZoneInfo tz, int y, int mo, int d, int h, int mi)
        {
            DateTime utc = TimeZoneInfo.ConvertTimeToUtc(new DateTime(y, mo, d, h, mi, 0, DateTimeKind.Unspecified), tz);
            return (long)(utc - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
        }

        static int Main()
        {
            // JSON
            JObj o = Json.ParseObject("{\"a\":1,\"b\":[true,null,\"x\\u00e9\\n\"],\"c\":{\"d\":-2.5e1},\"e\":\"\"}");
            Check(o.Int("a") == 1, "json number");
            Check(o.Arr("b").Count == 3 && (bool)o.Arr("b")[0] && o.Arr("b")[1] == null, "json array");
            Check((string)o.Arr("b")[2] == "xé\n", "json escapes");
            Check(o.Obj("c").Num("d", 0) == -25, "json exponent");
            Check(o.Str("e") == null && o.Opt("e", "z") == "" && o.Str("missing") == null, "json empty and missing strings");
            JObj back = Json.ParseObject(o.ToString());
            Check(back.ToString() == o.ToString(), "json round trip keeps order");
            bool threw = false;
            try { Json.Parse("{\"a\":}"); } catch (FormatException) { threw = true; }
            Check(threw, "json rejects bad input");

            // Dates
            Check(Dates.Parse("2026-11-22T14:03:00Z") == Dates.Utc(2026, 11, 22, 14, 3, 0, 0), "iso date");
            Check(Dates.Parse("2026-11-22T14:03:00.123-05:00") == Dates.Utc(2026, 11, 22, 19, 3, 0, 0), "iso with offset");
            Check(Dates.Parse("Sun, 22 Nov 2026 14:03:00 GMT") == Dates.Utc(2026, 11, 22, 14, 3, 0, 0), "rfc 822");
            Check(Dates.Parse("garbage") == 0, "bad date is 0");
            long now = Dates.Utc(2026, 11, 23, 12, 0, 0, 0);
            Check(Dates.Ago(now - 30000, now) == "Just now", "just now");
            Check(Dates.Ago(now - 12 * 60000, now) == "12m ago", "minutes ago");
            Check(Dates.Ago(now - 3 * 3600000L, now) == "3h ago", "hours ago");
            Check(Dates.Ago(now - 30 * 3600000L, now) == "Yesterday", "yesterday");
            Check(Dates.Ago(Dates.Utc(2025, 7, 18, 12, 0, 0, 0), now) == "Jul 18, 2025", "last year");
            Check(Dates.ReadingTime(1000) == "4 min read", "reading time");

            // Sun: New York, 23 November 2026: sunrise 6:53, sunset 16:31 (timeanddate.com).
            TimeZoneInfo ny = TimeZoneInfo.FindSystemTimeZoneById("Eastern Standard Time");
            Check(Sun.Dark(At(ny, 2026, 11, 23, 6, 30), ny), "dark before sunrise");
            Check(!Sun.Dark(At(ny, 2026, 11, 23, 7, 15), ny), "light after sunrise");
            Check(!Sun.Dark(At(ny, 2026, 11, 23, 16, 20), ny), "light before sunset");
            Check(Sun.Dark(At(ny, 2026, 11, 23, 16, 45), ny), "dark after sunset");
            long noon = At(ny, 2026, 11, 23, 12, 0);
            Check(Math.Abs(Sun.NextChange(noon, ny) - At(ny, 2026, 11, 23, 16, 31)) < 6 * 60000L, "next change from noon is sunset");
            Check(Math.Abs(Sun.NextChange(At(ny, 2026, 11, 23, 22, 0), ny) - At(ny, 2026, 11, 24, 6, 54)) < 6 * 60000L, "next change at night is sunrise");
            Check(Math.Abs(Sun.Next(noon, ny, true) - At(ny, 2026, 11, 24, 6, 54)) < 6 * 60000L, "next sunrise");
            TimeZoneInfo chatham = null;
            try { chatham = TimeZoneInfo.FindSystemTimeZoneById("Chatham Islands Standard Time"); } catch (Exception) { }
            if (chatham != null) Check(Sun.NextChange(noon, chatham) - noon <= 24 * 3600000L, "unlisted zone still turns");

            // Stories as the relay sends them
            JObj a = Json.ParseObject("{\"id\":\"A1\",\"title\":\"Headline\",\"excerpt\":\"\",\"channel\":{\"id\":\"T1\",\"name\":\"NPR\"},"
                + "\"image\":{\"url\":\"https://x/y.jpg\",\"width\":1500,\"height\":1000},\"published_at\":\"2026-11-23T10:00:00Z\","
                + "\"access\":\"news_plus\",\"completeness\":\"preview\",\"colors\":{\"background\":\"#111115\",\"accent\":\"#zz\"}}");
            Story s = Story.From(a);
            Check(s != null && s.Title == "Headline" && s.Excerpt == null, "story fields");
            Check(s.Channel == "NPR" && s.ChannelId == "T1" && s.ImageWidth == 1500, "story channel and image");
            Check(s.Paid && s.Completeness == "preview", "story access");
            Check(s.Background.R == 0x11 && s.AccentRaw.R == 0x8A, "story colours, bad accent falls back");
            Check(s.Dateline(Dates.Utc(2026, 11, 23, 12, 0, 0, 0)) == "NPR  ·  2h ago", "dateline");
            Check(Story.List(Json.Parse("[{\"id\":\"\"},{\"id\":\"B\",\"title\":\"\"},{\"id\":\"C\",\"title\":\"t\"}]") as List<object>).Count == 1, "list skips empty");

            Article art = new Article(Json.ParseObject("{\"id\":\"A1\",\"title\":\"H\",\"completeness\":\"full\",\"saved\":true,"
                + "\"topics\":[{\"id\":\"t\",\"name\":\"Tech\"}],\"fetch_job\":{\"id\":\"j\",\"state\":\"running\"},"
                + "\"body\":{\"byline\":\"By A\",\"blocks\":[{\"type\":\"paragraph\",\"text\":\"one two three\"},{\"type\":\"divider\"},"
                + "{\"type\":\"video\",\"url\":\"https://v/x.m3u8\",\"aspect_ratio\":2},{\"type\":\"embed\"}]}}"));
            Check(art.Blocks.Count == 3 && art.Blocks[2].Type == "video" && art.Blocks[2].Height == 500, "article blocks");
            Check(art.PendingJob == "j" && art.JobState == "running" && art.Saved && art.Topics.Count == 1, "article state");
            Check(art.Words() == 3 && art.Byline == "By A", "article words and byline");

            // Accents stay readable on both pages
            Ui.SetLight(false);
            Check(Ui.Readable(System.Windows.Media.Color.FromRgb(0x0F, 0x0F, 0x0F)).R >= 0xBE, "dark accent lifted on the dark page");
            Ui.SetLight(true);
            Check(Ui.Readable(System.Windows.Media.Colors.White).R <= 0x73, "pale accent deepened on the light page");

            Console.WriteLine(failures == 0 ? "all passed" : failures + " failed");
            return failures == 0 ? 0 : 1;
        }
    }
}
