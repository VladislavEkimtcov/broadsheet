using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows;
using System.Windows.Media;

namespace Broadsheet
{
    /// <summary>
    /// Sizes, type and colours shared by every view. Two pages: dark (warm, like newsprint at night)
    /// and light (newsprint by day). Views read the colours when they are built, so switching
    /// (<see cref="SetLight"/>) is followed by building the views again, as on the TV.
    /// </summary>
    static class Ui
    {
        public static Color PAPER, SURFACE, SURFACE_HI, INK, BODY, MUTED, FAINT, RULE, RAIL, FOCUS, ACCENT;
        public static bool Light;
        /// <summary>The hero's words sit on a dark scrim over its picture, light or dark page alike.</summary>
        public static readonly Color HERO_TEXT = Color.FromRgb(0xF4, 0xEF, 0xE6), HERO_EXCERPT = Color.FromRgb(0xC9, 0xC2, 0xB6);

        public static FontFamily Display, Head, HeadItalic, Text, TextBold, TextItalic;
        public static Typeface DisplayFace, HeadFace, HeadItalicFace, TextFace, TextBoldFace, TextItalicFace, Sans, SansMedium, SansBold;
        public static double PixelsPerDip = 1.0;
        /// <summary>The whole window is laid out in TV-like units and scaled by this to fit (MainWindow).</summary>
        public static double Scale = 1.0;

        static readonly Dictionary<Color, SolidColorBrush> brushes = new Dictionary<Color, SolidColorBrush>();

        static Ui()
        {
            SetLight(false);
            Display = Font("newsreader-display.ttf");
            Head = Font("newsreader-head.ttf");
            HeadItalic = Font("newsreader-head-italic.ttf");
            Text = Font("newsreader-text.ttf");
            TextBold = Font("newsreader-text-bold.ttf");
            TextItalic = Font("newsreader-text-italic.ttf");
            // One cut per file: ask for the face as it is, so WPF never fakes a bolder or slanted one.
            DisplayFace = new Typeface(Display, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            HeadFace = new Typeface(Head, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            HeadItalicFace = new Typeface(HeadItalic, FontStyles.Italic, FontWeights.Normal, FontStretches.Normal);
            TextFace = new Typeface(Text, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            TextBoldFace = new Typeface(TextBold, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            TextItalicFace = new Typeface(TextItalic, FontStyles.Italic, FontWeights.Normal, FontStretches.Normal);
            FontFamily segoe = new FontFamily("Segoe UI");
            Sans = new Typeface(segoe, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            SansMedium = new Typeface(segoe, FontStyles.Normal, FontWeights.SemiBold, FontStretches.Normal);
            SansBold = new Typeface(segoe, FontStyles.Normal, FontWeights.Bold, FontStretches.Normal);
        }

        /// <summary>Newsreader (SIL OFL) from the exe's own resources; Georgia if a cut is missing.
        /// The family is asked for by the name the file itself gives: the display cut calls itself
        /// "Newsreader 72pt", and a name that doesn't match falls back to Segoe UI without a word
        /// (GetTypefaces still lists faces then, so only a resolved glyph typeface proves it).</summary>
        static FontFamily Font(string file)
        {
            List<string> names = new List<string>();
            GlyphTypeface own = null;
            try
            {
                own = new GlyphTypeface(new Uri("pack://application:,,,/fonts/" + file));
                foreach (KeyValuePair<System.Globalization.CultureInfo, string> n in own.Win32FamilyNames)
                    if (n.Key.Name == "en-US" && !names.Contains(n.Value)) names.Insert(0, n.Value);
                    else if (!names.Contains(n.Value)) names.Add(n.Value);
            }
            catch (Exception) { }
            if (!names.Contains("Newsreader")) names.Add("Newsreader");
            foreach (string name in names)
            {
                try
                {
                    FontFamily f = new FontFamily(new Uri("pack://application:,,,/"), "./fonts/" + file + "#" + name);
                    Typeface t = own != null ? new Typeface(f, own.Style, own.Weight, own.Stretch)
                        : new Typeface(f, FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
                    GlyphTypeface found;
                    if (t.TryGetGlyphTypeface(out found)) return f;
                }
                catch (Exception) { }
            }
            return new FontFamily("Georgia");
        }

        public static void SetLight(bool value)
        {
            Light = value;
            if (value)
            {
                PAPER = Rgb(0xF5F0E6); SURFACE = Rgb(0xE9E2D4); SURFACE_HI = Rgb(0xDCD3C1);
                INK = Rgb(0x1B1814); BODY = Rgb(0x2B2722); MUTED = Rgb(0x5B544A); FAINT = Rgb(0x80786C);
                RULE = Argb(0x26000000); RAIL = Rgb(0xEAE3D5); FOCUS = Argb(0xF21B1814); ACCENT = Rgb(0xC4262C);
            }
            else
            {
                PAPER = Rgb(0x11100E); SURFACE = Rgb(0x1D1B18); SURFACE_HI = Rgb(0x2B2824);
                INK = Rgb(0xF4EFE6); BODY = Rgb(0xE4DED3); MUTED = Rgb(0xB1AA9E); FAINT = Rgb(0x7F786E);
                RULE = Argb(0x24FFFFFF); RAIL = Rgb(0x161512); FOCUS = Argb(0xF2FFFFFF); ACCENT = Rgb(0xE5484D);
            }
        }

        public static Color Rgb(uint v) { return Color.FromRgb((byte)(v >> 16), (byte)(v >> 8), (byte)v); }

        public static Color Argb(uint v) { return Color.FromArgb((byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v); }

        /// <summary>A frozen brush per colour, shared.</summary>
        public static SolidColorBrush Brush(Color c)
        {
            SolidColorBrush b;
            if (!brushes.TryGetValue(c, out b))
            {
                b = new SolidColorBrush(c);
                b.Freeze();
                brushes[c] = b;
            }
            return b;
        }

        public static Color Alpha(Color c, double a)
        {
            return Color.FromArgb((byte)Math.Round(c.A * Math.Max(0, Math.Min(1, a))), c.R, c.G, c.B);
        }

        public static Color Mix(Color a, Color b, double t)
        {
            return Color.FromRgb((byte)Math.Round(a.R + (b.R - a.R) * t), (byte)Math.Round(a.G + (b.G - a.G) * t),
                (byte)Math.Round(a.B + (b.B - a.B) * t));
        }

        /// <summary>Publisher accents are picked for white pages: on the dark page lift dark ones, on
        /// the light page deepen pale ones, so the dateline always reads.</summary>
        public static Color Readable(Color c)
        {
            double h, s, v;
            ToHsv(c, out h, out s, out v);
            if (Light)
            {
                if (v > 0.62) v = 0.62;
                if (s < 0.35 && v > 0.45) v = 0.45;
            }
            else
            {
                if (v < 0.75) v = 0.75 + v * 0.25;
                if (s > 0.75) s = 0.75;
            }
            return FromHsv(h, s, v);
        }

        static void ToHsv(Color c, out double h, out double s, out double v)
        {
            double r = c.R / 255.0, g = c.G / 255.0, b = c.B / 255.0;
            double max = Math.Max(r, Math.Max(g, b)), min = Math.Min(r, Math.Min(g, b)), d = max - min;
            v = max;
            s = max == 0 ? 0 : d / max;
            if (d == 0) h = 0;
            else if (max == r) h = 60 * (((g - b) / d) % 6);
            else if (max == g) h = 60 * ((b - r) / d + 2);
            else h = 60 * ((r - g) / d + 4);
            if (h < 0) h += 360;
        }

        static Color FromHsv(double h, double s, double v)
        {
            double c = v * s, x = c * (1 - Math.Abs((h / 60) % 2 - 1)), m = v - c;
            double r, g, b;
            if (h < 60) { r = c; g = x; b = 0; }
            else if (h < 120) { r = x; g = c; b = 0; }
            else if (h < 180) { r = 0; g = c; b = x; }
            else if (h < 240) { r = 0; g = x; b = c; }
            else if (h < 300) { r = x; g = 0; b = c; }
            else { r = c; g = 0; b = x; }
            return Color.FromRgb((byte)Math.Round((r + m) * 255), (byte)Math.Round((g + m) * 255), (byte)Math.Round((b + m) * 255));
        }

        // ------------------------------------------------------------------ type

        public static FormattedText Format(string text, Typeface face, double size, Color color)
        {
            FormattedText t = new FormattedText(text ?? "", CultureInfo.CurrentUICulture, FlowDirection.LeftToRight, face, size,
                Brush(color), PixelsPerDip);
            return t;
        }

        public static double Measure(string text, Typeface face, double size)
        {
            return Format(text, face, size, Colors.Black).WidthIncludingTrailingWhitespace;
        }

        /// <summary>Cuts text to width with an ellipsis.</summary>
        public static string Ellipsize(string text, Typeface face, double size, double width)
        {
            if (text == null) return "";
            if (Measure(text, face, size) <= width) return text;
            int lo = 0, hi = text.Length;
            while (lo < hi)
            {
                int mid = (lo + hi + 1) / 2;
                if (Measure(text.Substring(0, mid).TrimEnd() + "…", face, size) <= width) lo = mid;
                else hi = mid - 1;
            }
            return lo == 0 ? "…" : text.Substring(0, lo).TrimEnd() + "…";
        }

        /// <summary>
        /// Word wraps text into at most maxLines lines of width, the last one ellipsized if words are
        /// left over. Lines come back one by one so a card can set a block behind each.
        /// </summary>
        public static List<string> Wrap(string text, Typeface face, double size, double width, int maxLines)
        {
            List<string> lines = new List<string>();
            if (string.IsNullOrEmpty(text) || width <= 0 || maxLines <= 0) return lines;
            string[] words = text.Replace('\n', ' ').Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            int i = 0;
            while (i < words.Length && lines.Count < maxLines)
            {
                string line = words[i++];
                while (i < words.Length)
                {
                    string next = line + " " + words[i];
                    if (Measure(next, face, size) > width) break;
                    line = next;
                    i++;
                }
                bool last = lines.Count == maxLines - 1;
                if ((last && i < words.Length) || Measure(line, face, size) > width)
                    line = Ellipsize(i < words.Length ? line + " " + words[i] : line, face, size, width);
                lines.Add(line);
            }
            return lines;
        }
    }
}
