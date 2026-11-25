using System;
using System.Collections.Generic;
using System.IO;

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

        /// <summary>spec\cases, looked for upwards from the exe (windows\bin\ in the repo).</summary>
        static string CasesDir()
        {
            DirectoryInfo d = new DirectoryInfo(AppDomain.CurrentDomain.BaseDirectory);
            for (; d != null; d = d.Parent)
            {
                string c = Path.Combine(Path.Combine(d.FullName, "spec"), "cases");
                if (File.Exists(Path.Combine(c, "sun.json"))) return c;
            }
            return null;
        }

        /// <summary>"2026-11-23T06:30[:SS]" as {y, mo, d, h, mi, s}.</summary>
        static int[] Fields(string s)
        {
            int[] f = new int[6];
            string[] p = s.Split('-', 'T', ':');
            for (int i = 0; i < p.Length; i++) f[i] = int.Parse(p[i]);
            return f;
        }

        static long Utc(string s)
        {
            int[] f = Fields(s);
            return Dates.Utc(f[0], f[1], f[2], f[3], f[4], f[5], 0);
        }

        static long Local(TimeZoneInfo tz, string s)
        {
            int[] f = Fields(s);
            return At(tz, f[0], f[1], f[2], f[3], f[4]);
        }

        static IEnumerable<JObj> Each(JObj o, string key)
        {
            foreach (object c in o.Arr(key)) yield return (JObj)c;
        }

        static void DateCases(JObj d)
        {
            foreach (JObj c in Each(d, "parse"))
                Check(Dates.Parse(c.Str("in")) == (c.IsNull("utc") ? 0 : Utc(c.Str("utc"))), "dates: " + c.Str("name"));
            foreach (JObj c in Each(d, "ago"))
                Check(Dates.Ago(Utc(c.Str("when")), Utc(c.Str("now"))) == c.Str("expect"), "dates: " + c.Str("name"));
            foreach (JObj c in Each(d, "reading_time"))
                Check(Dates.ReadingTime(c.Int("words")) == c.Str("expect"), "dates: " + c.Str("name"));
        }

        /// <summary>The cases name IANA zones (the TV's); windows_zones maps them to Windows ids.</summary>
        static TimeZoneInfo Zone(JObj s, JObj c)
        {
            string id = s.Obj("windows_zones").Str(c.Str("zone"));
            try { return id == null ? null : TimeZoneInfo.FindSystemTimeZoneById(id); }
            catch (Exception) { return null; }
        }

        static bool Near(long got, long want, JObj c)
        {
            return Math.Abs(got - want) < c.Int("tolerance_minutes") * 60000L;
        }

        static void SunCases(JObj s)
        {
            foreach (string group in new[] { "dark", "next_change", "next_sunrise", "turns_within_a_day" })
                foreach (JObj c in Each(s, group))
                {
                    string name = "sun: " + c.Str("name");
                    TimeZoneInfo tz = Zone(s, c);
                    if (tz == null) { Console.WriteLine("skip  " + name + " (no zone " + c.Str("zone") + " here)"); continue; }
                    long at = Local(tz, c.Str("local"));
                    if (group == "dark") Check(Sun.Dark(at, tz) == c.Bool("dark"), name);
                    else if (group == "next_change") Check(Near(Sun.NextChange(at, tz), Local(tz, c.Str("expect")), c), name);
                    else if (group == "next_sunrise") Check(Near((long)Sun.Next(at, tz, true), Local(tz, c.Str("expect")), c), name);
                    else
                    {
                        long next = Sun.NextChange(at, tz);
                        Check(next > at && next - at <= 24 * 3600000L, name);
                    }
                }
        }

        static int N(JObj c, string key) { return (int)c.Num(key, 0); }

        static bool Same(int[] got, JObj c)
        {
            List<object> want = c.Arr("expect");
            if (got.Length != want.Count) return false;
            for (int i = 0; i < got.Length; i++) if (got[i] != (int)Convert.ToDouble(want[i])) return false;
            return true;
        }

        static void LadderCases(JObj l)
        {
            foreach (JObj c in Each(l, "around"))
                Check(Same(Ladder.Around(N(c, "index"), N(c, "count")), c), "ladder: around, " + c.Str("name"));
            foreach (JObj c in Each(l, "want_more"))
                Check(Ladder.WantMore(N(c, "index"), N(c, "loaded")) == c.Bool("expect"), "ladder: want more, " + c.Str("name"));
            foreach (JObj c in Each(l, "rail_neighbours"))
                Check(Same(Ladder.RailNeighbours(N(c, "index"), N(c, "count"), N(c, "direction")), c), "ladder: rail, " + c.Str("name"));
            foreach (JObj c in Each(l, "reading"))
                Check(Same(Ladder.Reading(N(c, "index"), N(c, "count")), c), "ladder: reading, " + c.Str("name"));
            foreach (JObj c in Each(l, "near"))
                Check(Ladder.Near(N(c, "scroll"), N(c, "viewport"), N(c, "content"), N(c, "screens")) == c.Bool("expect"), "ladder: near, " + c.Str("name"));
        }

        static void SwipeCases(JObj s)
        {
            foreach (JObj c in Each(s, "bursts"))
            {
                Swipe swipe = new Swipe();
                List<string> got = new List<string>();
                foreach (object e in c.Arr("events"))
                {
                    List<object> ev = (List<object>)e;
                    int r = swipe.Feed((int)Convert.ToDouble(ev[0]), Convert.ToDouble(ev[1]), Convert.ToDouble(ev[2]));
                    if (r != Swipe.NONE) got.Add(r == Swipe.BACK ? "back" : "options");
                }
                List<object> want = c.Arr("expect");
                bool ok = got.Count == want.Count;
                for (int i = 0; ok && i < got.Count; i++) ok = got[i] == (string)want[i];
                Check(ok, "swipe: " + c.Str("name") + (ok ? "" : " (got " + string.Join(", ", got) + ")"));
            }
        }

        static void SseCases(JObj s)
        {
            foreach (JObj c in Each(s, "streams"))
            {
                List<Sse.Event> got = Sse.Parse(c.Opt("stream"));
                List<object> want = c.Arr("events");
                bool ok = got.Count == want.Count;
                for (int i = 0; ok && i < got.Count; i++)
                {
                    JObj w = (JObj)want[i];
                    ok = got[i].Type == w.Opt("type") && got[i].Data == w.Opt("data")
                        && (got[i].Id == null ? w.IsNull("id") : got[i].Id == w.Opt("id"));
                }
                Check(ok, "sse: " + c.Str("name"));
            }
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

            // Dates and Sun: the shared cases in spec/cases, the same files the TV app's tests read.
            string cases = CasesDir();
            Check(cases != null, "found spec/cases");
            if (cases != null)
            {
                DateCases(Json.ParseObject(File.ReadAllText(Path.Combine(cases, "dates.json"))));
                SunCases(Json.ParseObject(File.ReadAllText(Path.Combine(cases, "sun.json"))));
                LadderCases(Json.ParseObject(File.ReadAllText(Path.Combine(cases, "ladder.json"))));
                SseCases(Json.ParseObject(File.ReadAllText(Path.Combine(cases, "sse.json"))));
                SwipeCases(Json.ParseObject(File.ReadAllText(Path.Combine(cases, "swipe.json"))));
            }

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
            Story thumbs = Story.From(Json.ParseObject("{\"id\":\"A2\",\"title\":\"T\",\"thumbnails\":[{\"url\":\"s\",\"width\":300},"
                + "{\"url\":\"m\",\"width\":750},{\"url\":\"l\",\"width\":1600}]}"));
            Check(thumbs.ImageFor(600) == "m" && thumbs.ImageFor(2000) == "l" && thumbs.Image == "l", "story picks the picture size it needs");
            Article head = new Article(Json.ParseObject("{\"id\":\"A3\",\"title\":\"H\",\"completeness\":\"full\","
                + "\"body\":{\"word_count\":900,\"next_block\":6,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"one two\"}]}}"));
            Check(head.NextBlock == 6 && head.Words() == 900, "an opening knows where the rest starts and the whole story's length");
            Check(art.NextBlock == -1, "a whole story has no rest");

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
