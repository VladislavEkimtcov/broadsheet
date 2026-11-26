using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;

namespace Broadsheet
{
    /// <summary>
    /// A story on the front page, drawn by hand: one element per card instead of a small tree, so a
    /// page of thirty cards stays cheap to lay out and to redraw when focus moves.
    ///
    /// HERO: the picture fills the card, headline and summary on a dark scrim; where the picture is
    /// light under the words even so, each line gets a dark block (see CheckContrast).
    /// TILE: 16:9 picture on top, dateline and a three-line headline below.
    /// BRIEF: no picture: dateline, a four-line headline and the start of the summary.
    /// COMPACT: dateline and headline beside a square 1:1 thumbnail on the right.
    /// </summary>
    sealed class Card : FrameworkElement, IImageTarget
    {
        public const int HERO = 0, TILE = 1, BRIEF = 2, COMPACT = 3;
        const double BLOCK_ALPHA = 0xD6 / 255.0;

        public static readonly DependencyProperty FadeProperty = DependencyProperty.Register("Fade", typeof(double), typeof(Card),
            new FrameworkPropertyMetadata(1.0, FrameworkPropertyMetadataOptions.AffectsRender));

        public readonly int Kind;
        public readonly Story Story;
        public readonly int Index;
        readonly Images images;
        readonly long now;
        BitmapSource bitmap;
        bool near, dim, focused, blocks;
        readonly ScaleTransform zoom = new ScaleTransform(1, 1);

        // laid-out text, rebuilt when the size changes
        List<string> title = new List<string>(), excerpt = new List<string>();
        double titleSize, excerptSize, metaSize, titleLine, excerptLine;
        Size laidOut;

        public Card(int style, Story story, int index, long now, Images images)
        {
            Kind = style;
            Story = story;
            Index = index;
            this.now = now;
            this.images = images;
            RenderTransform = zoom;
            RenderTransformOrigin = new Point(0.5, 0.5);
            metaSize = style == HERO ? 13 : 11.5;
            titleSize = style == HERO ? 27 : (style == BRIEF ? 17 : (style == COMPACT ? 15.5 : 16));
            excerptSize = style == HERO ? 15 : 13;
            titleLine = titleSize * 1.18;
            excerptLine = excerptSize * 1.36;
            Cursor = System.Windows.Input.Cursors.Hand;
            SnapsToDevicePixels = true;
        }

        public double Fade
        {
            get { return (double)GetValue(FadeProperty); }
            set { SetValue(FadeProperty, value); }
        }

        /// <summary>Within a screen of the viewport: load the picture; far away: let it go.</summary>
        public void SetNear(bool value)
        {
            if (value == near) return;
            near = value;
            if (Kind == BRIEF) return;
            if (near) LoadPicture();
            else
            {
                images.Cancel(this);
                if (bitmap != null)
                {
                    bitmap = null;
                    InvalidateVisual();
                }
            }
        }

        double CompactThumb(double w, double h)
        {
            double pad = 12;
            return Math.Max(0, Math.Min(h - pad * 2, Math.Round(w * 0.28)));
        }

        void LoadPicture()
        {
            if (!near || Story.Image == null || Kind == BRIEF || ActualWidth <= 0) return;
            double px = Ui.Scale * Ui.PixelsPerDip;
            if (Kind == COMPACT)
            {
                double thumb = CompactThumb(ActualWidth, ActualHeight);
                if (thumb <= 0) return;
                int tw = (int)Math.Ceiling(thumb * px);
                images.Load(this, Story.ImageFor(tw), tw, tw);
                return;
            }
            double h = Kind == HERO ? ActualHeight : ActualWidth * 9 / 16;
            int w = (int)Math.Ceiling(ActualWidth * px);
            images.Load(this, Story.ImageFor(w), w, (int)Math.Ceiling(h * px));
        }

        public void OnImage(BitmapSource b, bool fresh)
        {
            bitmap = b;
            CheckContrast();
            if (fresh)
            {
                DoubleAnimation a = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(220));
                a.FillBehavior = FillBehavior.Stop;
                a.Completed += (s, e) => Fade = 1;
                Fade = 1;
                BeginAnimation(FadeProperty, a);
            }
            else
            {
                InvalidateVisual();
            }
        }

        public void SetDim(bool value)
        {
            dim = value;
            Opacity = dim && !focused ? 0.42 : 1;
        }

        public bool Focused { get { return focused; } }

        public void SetFocused(bool value)
        {
            if (value == focused) return;
            focused = value;
            double s = focused ? 1.035 : 1;
            Duration d = new Duration(TimeSpan.FromMilliseconds(140));
            zoom.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(s, d));
            zoom.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(s, d));
            System.Windows.Controls.Panel.SetZIndex(this, focused ? 10 : 0);
            Opacity = dim && !focused ? 0.42 : 1;
            InvalidateVisual();
        }

        protected override void OnRenderSizeChanged(SizeChangedInfo info)
        {
            base.OnRenderSizeChanged(info);
            Layout();
            if (near) LoadPicture();
        }

        void Layout()
        {
            double w = ActualWidth;
            if (w <= 0) return;
            laidOut = RenderSize;
            double pad = Kind == HERO ? 22 : 12;
            double tw = Kind == COMPACT ? Math.Max(1, w - pad * 3 - CompactThumb(w, ActualHeight)) : w - pad * 2;
            int titleLines = Kind == BRIEF ? 4 : (Kind == COMPACT ? (ActualHeight >= 150 ? 4 : 3) : (Kind == TILE && w < 220 ? 4 : 3));
            title = Ui.Wrap(Story.Title, Ui.HeadFace, titleSize, tw, titleLines);
            excerpt = new List<string>();
            if (Story.Excerpt != null && Kind == HERO)
            {
                excerpt = Ui.Wrap(Story.Excerpt, Ui.TextFace, excerptSize, tw, 2);
            }
            else if (Story.Excerpt != null && Kind == BRIEF)
            {
                // As many whole lines as fit under the headline, the last one ellipsized.
                double room = ActualHeight - pad - BriefExcerptTop(pad);
                int lines = Math.Min(3, (int)Math.Floor(room / excerptLine));
                if (lines > 0) excerpt = Ui.Wrap(Story.Excerpt, Ui.TextFace, excerptSize, tw, lines);
            }
            CheckContrast();
        }

        double BriefExcerptTop(double pad)
        {
            return pad + metaSize * 1.2 + 10 + title.Count * titleLine + 8;
        }

        double HeroTextTop(double h)
        {
            double y = h - 22;
            if (excerpt.Count > 0) y -= excerpt.Count * excerptLine + 10;
            y -= title.Count * titleLine + 10 + metaSize * 1.2;
            return y;
        }

        /// <summary>Relative luminance (WCAG) of the hero headline's colour.</summary>
        static readonly double TEXT_LUMINANCE = Luminance(0xF4, 0xEF, 0xE6);

        static double Luminance(double r, double g, double b)  // feature: front.hero_contrast
        {
            return 0.2126 * Linear(r) + 0.7152 * Linear(g) + 0.0722 * Linear(b);
        }

        static double Linear(double v)
        {
            v /= 255.0;
            return v <= 0.04045 ? v / 12.92 : Math.Pow((v + 0.055) / 1.055, 2.4);
        }

        double Widest(List<string> lines, Typeface face, double size)
        {
            double w = 0;
            foreach (string l in lines) w = Math.Max(w, Ui.Measure(l, face, size));
            return w;
        }

        /// <summary>
        /// Looks at the part of the picture the hero's words cover, as it shows after the scrim, and
        /// counts the points where the contrast with the headline falls below 4.5:1 (WCAG's line for
        /// text); more than one in twelve and the words get blocks.
        /// </summary>
        void CheckContrast()
        {
            bool was = blocks;
            blocks = false;
            double w = ActualWidth, h = ActualHeight;
            if (Kind != HERO || bitmap == null || title.Count == 0 || w <= 0 || h <= 0)
            {
                if (was) InvalidateVisual();
                return;
            }
            int bw = bitmap.PixelWidth, bh = bitmap.PixelHeight;
            double scale = Math.Max(w / bw, h / bh);
            double cw = w / scale, ch = h / scale;
            double x0 = (bw - cw) / 2, y0 = Math.Max(0, (bh - ch) / 3);
            double pad = 22;
            double top = HeroTextTop(h), bottom = h - pad;
            double right = pad + Math.Max(Widest(title, Ui.HeadFace, titleSize), Widest(excerpt, Ui.TextFace, excerptSize));
            int bad = 0, all = 0;
            for (int iy = 0; iy < 12; iy++)
            {
                double vy = top + (bottom - top) * (iy + 0.5) / 12;
                double shade = 0.94 * Math.Max(0, Math.Min(1, (vy - h * 0.25) / (h * 0.75))); // the scrim
                for (int ix = 0; ix < 24; ix++)
                {
                    double vx = pad + (right - pad) * (ix + 0.5) / 24;
                    int px = Math.Min(bw - 1, Math.Max(0, (int)Math.Round(x0 + vx / scale)));
                    int py = Math.Min(bh - 1, Math.Max(0, (int)Math.Round(y0 + vy / scale)));
                    Color c = Images.Pixel(bitmap, px, py);
                    double lum = Luminance(c.R * (1 - shade), c.G * (1 - shade), c.B * (1 - shade));
                    if ((TEXT_LUMINANCE + 0.05) / (lum + 0.05) < 4.5) bad++;
                    all++;
                }
            }
            blocks = bad * 12 > all;
            if (blocks != was) InvalidateVisual();
        }

        Color Tint()
        {
            return Story.Background.A != 0 ? Story.Background : Ui.SURFACE_HI;
        }

        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight;
            if (w <= 0 || h <= 0) return;
            if (laidOut != RenderSize) Layout();
            dc.PushClip(new RectangleGeometry(new Rect(0, 0, w, h)));
            dc.DrawRectangle(Ui.Brush(Ui.SURFACE), null, new Rect(0, 0, w, h));
            if (Kind == HERO)
            {
                DrawPicture(dc, new Rect(0, 0, w, h));
                LinearGradientBrush scrim = new LinearGradientBrush(Color.FromArgb(0, 0, 0, 0), Color.FromArgb(0xF0, 0, 0, 0), 90);
                scrim.GradientStops[0].Offset = 0.25;
                dc.DrawRectangle(scrim, null, new Rect(0, 0, w, h));
                double pad = 22, y = h - pad;
                if (excerpt.Count > 0)
                {
                    y -= excerpt.Count * excerptLine;
                    DrawLines(dc, excerpt, Ui.TextFace, excerptSize, excerptLine, Ui.HERO_EXCERPT, pad, y, blocks);
                    y -= 10;
                }
                y -= title.Count * titleLine;
                DrawLines(dc, title, Ui.HeadFace, titleSize, titleLine, Ui.HERO_TEXT, pad, y, blocks);
                y -= 10 + metaSize * 1.2;
                DrawMeta(dc, pad, y, w - pad * 2, Color.FromArgb(0xE6, 0xFF, 0xFF, 0xFF), blocks);
                if (Story.Paid) DrawBadge(dc, pad, pad);
            }
            else if (Kind == TILE)
            {
                double ih = w * 9 / 16, pad = 12;
                DrawPicture(dc, new Rect(0, 0, w, ih));
                double y = ih + pad;
                DrawMeta(dc, pad, y, w - pad * 2, Story.Accent, false);
                DrawLines(dc, title, Ui.HeadFace, titleSize, titleLine, Ui.INK, pad, y + metaSize * 1.2 + 7, false);
                if (Story.Paid) DrawBadge(dc, 8, 8);
            }
            else if (Kind == COMPACT)
            {
                double pad = 12;
                double thumb = CompactThumb(w, h);
                double rx = w - pad - thumb;
                DrawPicture(dc, new Rect(rx, pad, thumb, thumb));
                double tw = Math.Max(1, rx - pad * 2);
                DrawMeta(dc, pad, pad, tw, Story.Accent, false);
                double titleTop = pad + metaSize * 1.2 + 7;
                DrawLines(dc, title, Ui.HeadFace, titleSize, titleLine, Ui.INK, pad, titleTop, false);
                if (Story.Paid)
                {
                    double bh = 18;
                    double tBot = titleTop + title.Count * titleLine;
                    if (tBot + 6 + bh <= h - pad) DrawBadge(dc, pad, h - pad - bh);
                    else if (thumb >= 72) DrawBadge(dc, rx + 4, pad + 4);
                }
            }
            else
            {
                dc.DrawRectangle(Ui.Brush(Ui.Mix(Ui.SURFACE, Tint(), 0.12)), null, new Rect(0, 0, w, h));
                double pad = 12;
                double metaWidth = w - pad * 2 - (Story.Paid ? BadgeWidth() + 8 : 0);
                DrawMeta(dc, pad, pad, metaWidth, Story.Accent, false);
                DrawLines(dc, title, Ui.HeadFace, titleSize, titleLine, Ui.INK, pad, pad + metaSize * 1.2 + 10, false);
                DrawLines(dc, excerpt, Ui.TextFace, excerptSize, excerptLine, Ui.MUTED, pad, BriefExcerptTop(pad), false);
                if (Story.Paid) DrawBadge(dc, w - pad - BadgeWidth(), pad - 2);
            }
            dc.Pop();
            if (focused)
            {
                Pen border = new Pen(Ui.Brush(Ui.FOCUS), 3);
                dc.DrawRectangle(null, border, new Rect(1.5, 1.5, w - 3, h - 3));
            }
        }

        void DrawPicture(DrawingContext dc, Rect r)
        {
            dc.DrawRectangle(Ui.Brush(Ui.Mix(Ui.SURFACE, Tint(), 0.35)), null, r);
            if (bitmap == null) return;
            // Centre crop, biased up a little (faces sit in the top third), like the TV.
            double bw = bitmap.PixelWidth, bh = bitmap.PixelHeight;
            double scale = Math.Max(r.Width / bw, r.Height / bh);
            double dw = bw * scale, dh = bh * scale;
            double x = r.X + (r.Width - dw) / 2;
            double y = r.Y - Math.Max(0, (dh - r.Height) / 3);
            dc.PushClip(new RectangleGeometry(r));
            dc.PushOpacity(Fade);
            dc.DrawImage(bitmap, new Rect(x, y, dw, dh));
            dc.Pop();
            dc.Pop();
        }

        void DrawLines(DrawingContext dc, List<string> lines, Typeface face, double size, double lineH, Color color, double x, double y, bool block)
        {
            for (int i = 0; i < lines.Count; i++)
            {
                FormattedText t = Ui.Format(lines[i], face, size, color);
                double ty = y + i * lineH + (lineH - t.Height) / 2;
                if (block)
                    dc.DrawRectangle(Ui.Brush(Color.FromArgb((byte)(BLOCK_ALPHA * 255), 0, 0, 0)), null,
                        new Rect(x - 8, y + i * lineH - 1, t.WidthIncludingTrailingWhitespace + 16, lineH + 2));
                dc.DrawText(t, new Point(x, ty));
            }
        }

        void DrawMeta(DrawingContext dc, double x, double top, double width, Color color, bool block)
        {
            string meta = Story.Dateline(now);
            if (string.IsNullOrEmpty(meta)) return;
            Typeface f = Ui.SansMedium;
            if (Ui.Measure(meta, f, metaSize) > width)
            {
                // Short of room: shorten the publisher, keep the date.
                string when = Dates.Ago(Story.Published, now);
                string tail = Story.Channel != null && when.Length > 0 ? "  ·  " + when : "";
                meta = Ui.Ellipsize(tail.Length == 0 ? meta : Story.Channel, f, metaSize, width - Ui.Measure(tail, f, metaSize)) + tail;
            }
            FormattedText t = Ui.Format(meta, f, metaSize, color);
            if (block) dc.DrawRectangle(Ui.Brush(Color.FromArgb((byte)(BLOCK_ALPHA * 255), 0, 0, 0)), null,
                new Rect(x - 8, top - 3, Math.Min(t.Width, width) + 16, t.Height + 5));
            dc.DrawText(t, new Point(x, top));
        }

        double BadgeWidth()
        {
            return Ui.Measure("News+", Ui.SansBold, 10) + 12;
        }

        void DrawBadge(DrawingContext dc, double x, double y)
        {
            double bw = BadgeWidth(), bh = 10 + 8;
            dc.DrawRectangle(Ui.Brush(Color.FromArgb(0xCC, 0, 0, 0)), null, new Rect(x, y, bw, bh));
            FormattedText t = Ui.Format("News+", Ui.SansBold, 10, Color.FromRgb(0xFF, 0x5A, 0x5F));
            dc.DrawText(t, new Point(x + 6, y + (bh - t.Height) / 2));
        }
    }
}
