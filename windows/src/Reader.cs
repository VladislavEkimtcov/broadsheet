using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace Broadsheet
{
    /// <summary>
    /// A story, full window, as a column of type. Shows the card's headline, summary and picture at
    /// once, then the body when the relay has it.
    ///
    /// Keys: Down/Up half a screen, gliding (as on the TV), Space and Page Down/Up a screen; Enter half a screen (at the end it opens the next story), Left/Right the previous/next story, Esc or Backspace the
    /// front page. A video scrolled into view is selected (ringed, "Play video"): Enter plays it.
    /// </summary>
    sealed class Reader : Grid
    {
        public interface IListener
        {
            void OnClose();
            void OnMove(int delta);
            /// <summary>Enter (or a click) on a video in the story.</summary>
            void OnPlay(string stream, string title);
        }

        /// <summary>Body text sizes (pt), from close reading to across the room; STANDARD is the default.</summary>
        public static readonly double[] SIZES = { 14, 15.5, 17, 18.5, 21, 24, 27.5, 31, 35, 40 };
        public const int STANDARD = 4;

        readonly Images images;
        readonly IListener listener;
        readonly Scroller scroll = new Scroller();
        readonly StackPanel column = new StackPanel();
        readonly Rectangle progress = new Rectangle();
        readonly ScaleTransform progressScale = new ScaleTransform(0, 1);
        readonly List<Picture> pictures = new List<Picture>();
        readonly Ticker ticker;
        Story story;
        Article article;
        int sizeStep = STANDARD;
        TextBlock note;
        Loader loader;
        Picture selectedVideo;

        public Reader(Images images, IListener listener)
        {
            this.images = images;
            this.listener = listener;
            Background = Ui.Brush(Ui.PAPER);
            column.Margin = new Thickness(0, 56, 0, 140);
            column.HorizontalAlignment = HorizontalAlignment.Center;
            column.Width = 700;
            scroll.Content = column;
            scroll.Moved += OnScroll;
            scroll.SizeChanged += (s, e) => FitColumn();
            Children.Add(scroll);
            progress.Fill = Ui.Brush(Ui.ACCENT);
            progress.Height = 3;
            progress.VerticalAlignment = VerticalAlignment.Top;
            progress.RenderTransform = progressScale;
            Children.Add(progress);
            // Windows only: an icon-only back arrow at the top left (the TV has its remote's Back).
            IconButton back = new IconButton(Icons.BACK, "Back to the front page", () => listener.OnClose());
            back.HorizontalAlignment = HorizontalAlignment.Left;
            back.VerticalAlignment = VerticalAlignment.Top;
            back.Margin = new Thickness(18, 16, 0, 0);
            Children.Add(back);
            ticker = new Ticker(this);
            ticker.Height = 58;
            ticker.VerticalAlignment = VerticalAlignment.Bottom;
            Children.Add(ticker);
            MouseUp += (s, e) =>
            {
                if (e.ChangedButton == MouseButton.XButton1) { listener.OnClose(); e.Handled = true; }
                else if (e.ChangedButton == MouseButton.XButton2) { listener.OnMove(1); e.Handled = true; }
            };
        }

        public Story Story { get { return story; } }

        /// <summary>The body, once it arrived.</summary>
        public Article Article { get { return article; } }

        public int SizeStep { get { return sizeStep; } }

        double Size { get { return SIZES[sizeStep]; } }

        /// <summary>How far through the story the page is, 0 to 1.</summary>
        public double Fraction
        {
            get
            {
                double range = scroll.Max;
                return range <= 0 ? 0 : Math.Min(1, scroll.VerticalOffset / range);
            }
        }

        /// <summary>Scrolls to a place found by Fraction once the page is laid out.</summary>
        public void RestoreFraction(double f)
        {
            if (f <= 0) return;
            Dispatcher.BeginInvoke(new Action(() =>
            {
                scroll.UpdateLayout();
                scroll.ScrollTo(scroll.Max * f, false);
            }), DispatcherPriority.Loaded);
        }

        public void SetTextSizeStep(int step)
        {
            sizeStep = Math.Max(0, Math.Min(SIZES.Length - 1, step));
            FitColumn();
        }

        /// <summary>Big type gets a wider column, so lines keep a few words; never wider than the window.</summary>
        void FitColumn()
        {
            double want = 700 + Math.Max(0, Size - 24) * 10;
            double room = scroll.ActualWidth > 0 ? scroll.ActualWidth - 64 : want;
            column.Width = Math.Max(320, Math.Min(want, room));
        }

        /// <summary>What the Mac is doing about the story, while the body is on its way.</summary>
        public void SetStage(string stage) { ticker.SetStage(stage); }

        /// <summary>Sets the open story again (new text size), keeping the place in it.</summary>
        public void Relayout()
        {
            if (story == null) return;
            double f = Fraction;
            Story s = story;
            Article a = article;
            Show(s);
            if (a != null) SetArticle(a, null);
            RestoreFraction(f);
        }

        /// <summary>Opens a story with what the card knows; the body follows with SetArticle.</summary>
        public void Show(Story s)
        {
            story = s;
            article = null;
            Clear();
            Header(s, null, 0);
            if (s.Excerpt != null) column.Children.Add(Standfirst(s.Excerpt));
            if (s.Image != null) AddPicture(s.Image, s.ImageWidth, s.ImageHeight, null, false, true);
            loader = new Loader(this);
            loader.Margin = new Thickness(0, 34, 0, 0);
            column.Children.Add(loader);
            ticker.Start("Asking the Mac for the story…");
            scroll.ScrollTo(0, false);
            AfterLayout();
        }

        /// <summary>The body arrived (or didn't: error).</summary>
        public void SetArticle(Article a, string error)
        {
            if (a == null)
            {
                ticker.Stop();
                if (loader != null)
                {
                    column.Children.Remove(loader);
                    loader.Finish();
                    loader = null;
                    note = Note("");
                }
                if (note != null) note.Text = error != null ? error + "\n\nThis is the summary only." : "This is the summary only.";
                return;
            }
            if (a.Story != null && story != null && a.Story.Id != story.Id) return;
            article = a;
            if (a.PendingJob == null) ticker.Stop();
            double y = scroll.VerticalOffset;
            Clear();
            Story s = a.Story ?? story;
            Header(s, a.Byline, a.Truncated ? 0 : a.Words()); // a preview's length isn't the story's
            string sub = a.Subtitle ?? s.Excerpt;
            if (sub != null) column.Children.Add(Standfirst(sub));
            string first = a.Blocks.Count == 0 ? null : a.Blocks[0].Type;
            bool leadImage = first == "image" || first == "video";
            if (!leadImage && s.Image != null) AddPicture(s.Image, s.ImageWidth, s.ImageHeight, null, false, true);
            foreach (Article.Block b in a.Blocks) Block(b);
            if (a.PendingJob != null)
            {
                loader = new Loader(this);
                loader.Margin = new Thickness(0, 34, 0, 0);
                ticker.SetStage("Apple News is fetching the whole story…");
                column.Children.Add(loader);
            }
            else if (a.Completeness == "preview")
            {
                note = Note("This is the opening of an Apple News+ story. The rest needs an Apple News+ subscription on the Mac.\n\nRight: next story");
            }
            else if (a.Completeness == "summary")
            {
                note = Note(a.JobFailed ? "Apple News didn't hand over the full story, so this is the summary.\n\nRight: next story"
                    : "This is the summary only.\n\nRight: next story");
            }
            else
            {
                note = Note("—\n\nEnter: next story");
                note.TextAlignment = TextAlignment.Center;
            }
            scroll.UpdateLayout();
            scroll.ScrollTo(y, false);
            AfterLayout();
        }

        void Clear()
        {
            selectedVideo = null;
            if (loader != null) loader.Finish();
            loader = null;
            foreach (Picture p in pictures) images.Cancel(p);
            pictures.Clear();
            column.Children.Clear();
            note = null;
        }

        static TextBlock Text(double size, Color color, Typeface face, double lineSpacing)
        {
            TextBlock t = new TextBlock
            {
                FontSize = size, Foreground = Ui.Brush(color), FontFamily = face.FontFamily, FontWeight = face.Weight, FontStyle = face.Style,
                TextWrapping = TextWrapping.Wrap
            };
            if (lineSpacing > 0)
            {
                t.LineHeight = size * lineSpacing * 1.17; // Android's spacing multiplies the font's own line height
                t.LineStackingStrategy = LineStackingStrategy.BlockLineHeight;
            }
            return t;
        }

        void Header(Story s, string byline, int words)
        {
            TextBlock dateline = Text(14, Ui.MUTED, Ui.SansMedium, 0);
            if (s.Channel != null) dateline.Inlines.Add(new Run(s.Channel.ToUpper()) { Foreground = Ui.Brush(s.Accent) });
            string ago = Dates.Ago(s.Published, Dates.NowMs());
            string rest = "";
            if (ago.Length > 0) rest += (s.Channel != null ? "   ·   " : "") + ago;
            if (words > 0) rest += "   ·   " + Dates.ReadingTime(words);
            else if (s.Minutes > 0) rest += "   ·   " + s.Minutes + " min read";
            if (s.Paid) rest += "   ·   Apple News+";
            if (rest.Length > 0) dateline.Inlines.Add(new Run(rest));
            column.Children.Add(dateline);
            TextBlock title = Text(Size * 1.72, Ui.INK, Ui.HeadFace, 1.08);
            title.Text = s.Title;
            title.Margin = new Thickness(0, 16, 0, 0);
            column.Children.Add(title);
            string by = byline ?? s.Byline;
            if (by != null)
            {
                TextBlock bl = Text(14, Ui.FAINT, Ui.Sans, 0);
                bl.Text = by;
                bl.Margin = new Thickness(0, 14, 0, 0);
                column.Children.Add(bl);
            }
        }

        TextBlock Standfirst(string text)
        {
            TextBlock v = Text(Size, Ui.MUTED, Ui.TextItalicFace, 1.35);
            v.Text = text;
            v.Margin = new Thickness(0, 18, 0, 0);
            return v;
        }

        void Block(Article.Block b)
        {
            switch (b.Type)
            {
                case "paragraph":
                    {
                        // Some publishers send several paragraphs as one, split by line breaks.
                        foreach (string part in b.Text.Split('\n'))
                        {
                            if (part.Trim().Length == 0) continue;
                            TextBlock v = Text(Size, Ui.BODY, Ui.TextFace, 1.45);
                            v.Text = part.Trim();
                            v.Margin = new Thickness(0, 18, 0, 0);
                            column.Children.Add(v);
                        }
                        break;
                    }
                case "heading":
                    {
                        TextBlock v = Text(Size * (b.Level <= 2 ? 1.25 : 1.12), Ui.INK, Ui.HeadFace, 1.12);
                        v.Text = b.Text;
                        v.Margin = new Thickness(0, 32, 0, 0);
                        column.Children.Add(v);
                        break;
                    }
                case "quote":
                    {
                        TextBlock v = Text(Size * 1.12, Ui.INK, Ui.HeadItalicFace, 1.3);
                        v.Text = b.Text;
                        // The thin accent rule left of a quotation.
                        Border rule = new Border
                        {
                            BorderBrush = Ui.Brush(Ui.ACCENT), BorderThickness = new Thickness(3, 0, 0, 0),
                            Padding = new Thickness(22, 2, 0, 2), Margin = new Thickness(0, 26, 0, 8), Child = v
                        };
                        column.Children.Add(rule);
                        break;
                    }
                case "caption":
                    {
                        TextBlock v = Text(13, Ui.FAINT, Ui.Sans, 1.25);
                        v.Text = b.Text;
                        v.Margin = new Thickness(0, 8, 0, 0);
                        column.Children.Add(v);
                        break;
                    }
                case "image":
                    AddPicture(b.Url, b.Width, b.Height, b.More > 0 ? (b.Caption != null ? b.Caption + "  ·  " : "") + "+" + b.More + " more photos" : b.Caption, false, false);
                    break;
                case "video":
                    {
                        Picture v = AddPicture(b.Url, b.Width, b.Height, b.Caption, true, false);
                        if (v != null) v.Stream = b.Stream;
                        break;
                    }
                case "divider":
                    column.Children.Add(new Border { Width = 120, Height = 1, Background = Ui.Brush(Ui.RULE), HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 30, 0, 8) });
                    break;
            }
        }

        Picture AddPicture(string url, int w, int h, string caption, bool video, bool lead)
        {
            if (url == null && !video) return null;
            Picture pic = new Picture(this, url, w, h, video);
            pic.Margin = new Thickness(0, lead ? 26 : 24, 0, 0);
            column.Children.Add(pic);
            pictures.Add(pic);
            if (caption != null)
            {
                TextBlock v = Text(13, Ui.FAINT, Ui.Sans, 1.25);
                v.Text = caption;
                v.Margin = new Thickness(0, 8, 0, 0);
                column.Children.Add(v);
            }
            return pic;
        }

        TextBlock Note(string text)
        {
            TextBlock v = Text(15, Ui.FAINT, Ui.Sans, 1.35);
            v.Text = text;
            v.Margin = new Thickness(0, 40, 0, 0);
            column.Children.Add(v);
            return v;
        }

        /// <summary>Keys while reading. True if used.</summary>
        public bool OnKey(Key k)
        {
            double h = scroll.ViewportHeight;
            switch (k)
            {
                case Key.Down:
                    scroll.ScrollBy(h * 0.45); // the TV's step, glided
                    return true;
                case Key.Space:
                    scroll.ScrollBy((Keyboard.Modifiers & ModifierKeys.Shift) != 0 ? -h * 0.9 : h * 0.9);
                    return true;
                case Key.MediaPlayPause:
                case Key.Play:
                    if (selectedVideo != null) Play(selectedVideo);
                    return true;
                case Key.Enter:
                    if (selectedVideo != null) Play(selectedVideo);
                    else if (AtEnd()) listener.OnMove(1);
                    else scroll.ScrollBy(h * 0.45);
                    return true;
                case Key.Up:
                    scroll.ScrollBy(-h * 0.45);
                    return true;
                case Key.PageDown:
                case Key.MediaNextTrack:
                    scroll.ScrollBy(h * 0.9);
                    return true;
                case Key.PageUp:
                case Key.MediaPreviousTrack:
                    scroll.ScrollBy(-h * 0.9);
                    return true;
                case Key.Home:
                    scroll.ScrollTo(0, true);
                    return true;
                case Key.End:
                    scroll.ScrollTo(scroll.Max, true);
                    return true;
                case Key.Right:
                    listener.OnMove(1);
                    return true;
                case Key.Left:
                    listener.OnMove(-1);
                    return true;
                case Key.Escape:
                case Key.Back:
                case Key.BrowserBack:
                    listener.OnClose();
                    return true;
            }
            return false;
        }

        /// <summary>An arrow let go: the glide eases to a stop.</summary>
        public void OnKeyUp(Key k)
        {
            // Arrows step as on the TV; switch to scroll.Hold(±1) in OnKey for a browser-style glide.
            if (k == Key.Down || k == Key.Up) scroll.Release();
        }

        void Play(Picture p)
        {
            listener.OnPlay(p.Stream, story != null ? story.Title : null);
        }

        bool AtEnd()
        {
            return scroll.Target >= scroll.Max - 8;
        }

        void AfterLayout()
        {
            Dispatcher.BeginInvoke(new Action(OnScroll), DispatcherPriority.Loaded);
        }

        void OnScroll()
        {
            double range = scroll.Max;
            progressScale.ScaleX = range <= 0 ? 1 : Math.Min(1, scroll.VerticalOffset / range);
            double top = scroll.VerticalOffset, vh = scroll.ViewportHeight, bottom = top + vh;
            Picture best = null;
            double bestDistance = double.MaxValue;
            foreach (Picture p in pictures)
            {
                if (p.ActualHeight == 0 || !p.IsVisible) continue;
                double y = p.TranslatePoint(new Point(0, 0), column).Y + column.Margin.Top;
                double ph = p.ActualHeight;
                p.SetNear(y + ph > top - vh && y < bottom + vh * 2);
                if (!p.Video) continue;
                // A video becomes the one Enter plays once most of it is on screen; of two, the nearer the middle.
                double shown = Math.Min(bottom, y + ph) - Math.Max(top, y);
                if (shown < Math.Min(ph, vh) * 0.6) continue;
                double d = Math.Abs(y + ph / 2 - (top + bottom) / 2);
                if (d < bestDistance)
                {
                    best = p;
                    bestDistance = d;
                }
            }
            if (best != selectedVideo)
            {
                if (selectedVideo != null) selectedVideo.InvalidateVisual();
                selectedVideo = best;
                if (best != null) best.InvalidateVisual();
            }
        }

        /// <summary>A frame-clock hook that runs only while something on screen is alive.</summary>
        abstract class Animated : FrameworkElement
        {
            bool hooked;

            protected void Hook(bool on)
            {
                if (on == hooked) return;
                hooked = on;
                if (on) CompositionTarget.Rendering += Tick;
                else CompositionTarget.Rendering -= Tick;
            }

            void Tick(object sender, EventArgs e)
            {
                if (!IsVisible) return;
                InvalidateVisual();
            }

            protected Animated()
            {
                Unloaded += (s, e) => Hook(false);
            }
        }

        /// <summary>Grey lines where the paragraphs will be set, a sheen sweeping across them, while
        /// the body is on its way. Animates only while waiting, a minute at most.</summary>
        sealed class Loader : Animated
        {
            readonly Reader reader;
            readonly DateTime started = DateTime.UtcNow;
            readonly double[] widths = { 1, 0.97, 0.99, 0.94, 1, 0.62 };

            public Loader(Reader r)
            {
                reader = r;
                Hook(true);
            }

            public void Finish() { Hook(false); }

            protected override Size MeasureOverride(Size available)
            {
                return new Size(0, reader.Size * 1.45 * 1.17 * widths.Length);
            }

            protected override void OnRender(DrawingContext dc)
            {
                double t = (DateTime.UtcNow - started).TotalSeconds;
                if (t > 60) Hook(false);
                double w = ActualWidth, lh = reader.Size * 1.45 * 1.17, bar = reader.Size * 0.5;
                double sx = ((t % 1.8) / 1.8) * (w + 400) - 200;
                LinearGradientBrush sheen = new LinearGradientBrush { MappingMode = BrushMappingMode.Absolute, StartPoint = new Point(sx - 160, 0), EndPoint = new Point(sx + 160, 0) };
                sheen.GradientStops.Add(new GradientStop(Ui.SURFACE, 0));
                sheen.GradientStops.Add(new GradientStop(Ui.SURFACE_HI, 0.5));
                sheen.GradientStops.Add(new GradientStop(Ui.SURFACE, 1));
                for (int i = 0; i < widths.Length; i++) dc.DrawRectangle(sheen, null, new Rect(0, i * lh, w * widths[i], bar));
            }
        }

        /// <summary>
        /// Pinned to the bottom while a story is on its way: what the Mac is doing and for how long, a
        /// bar that runs quickly to a third and then ever slower (it never claims to be done), and the
        /// press room at work underneath.
        /// </summary>
        sealed class Ticker : Animated
        {
            readonly Reader reader;
            DateTime started;
            string stage = "";
            bool running;
            static readonly string[] PRESS = { "Setting the type", "Inking the plates", "Running the press", "Folding the pages" };

            public Ticker(Reader r)
            {
                reader = r;
                Visibility = Visibility.Collapsed;
            }

            public void Start(string s)
            {
                started = DateTime.UtcNow;
                stage = s;
                running = true;
                BeginAnimation(OpacityProperty, null);
                Opacity = 1;
                Visibility = Visibility.Visible;
                Hook(true);
            }

            public void SetStage(string s)
            {
                stage = s;
                InvalidateVisual();
            }

            public void Stop()
            {
                if (!running) return;
                running = false;
                Hook(false);
                DoubleAnimation a = new DoubleAnimation(0, TimeSpan.FromMilliseconds(220));
                a.Completed += (o, e) => { if (!running) Visibility = Visibility.Collapsed; };
                BeginAnimation(OpacityProperty, a);
            }

            protected override void OnRender(DrawingContext dc)
            {
                double t = (DateTime.UtcNow - started).TotalSeconds;
                if (t > 60 && running)
                {
                    running = false; // stop moving; the page must go quiet
                    Hook(false);
                    stage = "Apple News hasn't sent the story yet. Right: next story";
                }
                double w = ActualWidth, h = ActualHeight;
                dc.DrawRectangle(Ui.Brush(Ui.SURFACE), null, new Rect(0, 0, w, h));
                dc.DrawRectangle(Ui.Brush(Ui.RULE), null, new Rect(0, 0, w, 3));
                double p = 0.94 * (1 - Math.Exp(-t / 2.2));
                dc.DrawRectangle(Ui.Brush(Ui.ACCENT), null, new Rect(0, 0, w * p, 3));
                double pip = (t * 0.9) % 1; // a bright run along the filled part keeps it visibly alive
                double px = w * p * pip;
                dc.DrawRectangle(Ui.Brush(Ui.Alpha(Ui.INK, 0.6)), null, new Rect(px, 0, Math.Max(0, Math.Min(w * p, px + 36) - px), 3));
                double pad = 40;
                FormattedText st = Ui.Format(stage, Ui.SansMedium, 16, Ui.INK);
                double y = (h - st.Height) / 2 + 1.5;
                dc.DrawText(st, new Point(pad, y));
                if (t >= 1.5)
                {
                    FormattedText tt = Ui.Format(Math.Round(t) + " s", Ui.SansMedium, 15, Ui.MUTED);
                    dc.DrawText(tt, new Point(pad + st.WidthIncludingTrailingWhitespace + 14, y + 1));
                }
                FormattedText press = Ui.Format(PRESS[(int)(t / 1.6) % PRESS.Length] + "…", Ui.TextItalicFace, 15, Ui.MUTED);
                dc.DrawText(press, new Point(w - pad - press.Width, y + 1));
            }
        }

        /// <summary>A picture in the story, as wide as the column; its height follows the picture's shape.</summary>
        sealed class Picture : FrameworkElement, IImageTarget
        {
            readonly Reader reader;
            readonly string url;
            readonly double aspect;
            public readonly bool Video;
            public string Stream;
            BitmapSource bitmap;
            bool near;

            public Picture(Reader reader, string url, int w, int h, bool video)
            {
                this.reader = reader;
                this.url = url;
                Video = video;
                aspect = w > 0 && h > 0 ? Math.Max(0.4, Math.Min(1.6, h / (double)w)) : 9 / 16.0;
                if (video)
                {
                    Cursor = Cursors.Hand;
                    MouseLeftButtonUp += (s, e) => { reader.Play(this); e.Handled = true; };
                }
                SizeChanged += (s, e) => { if (near) Load(); };
            }

            protected override Size MeasureOverride(Size available)
            {
                double w = double.IsInfinity(available.Width) ? 700 : available.Width;
                return new Size(w, Math.Min(Math.Round(w * aspect), 620));
            }

            void Load()
            {
                if (url == null || ActualWidth <= 0) return;
                double px = Ui.Scale * Ui.PixelsPerDip;
                reader.images.Load(this, url, (int)Math.Ceiling(ActualWidth * px), (int)Math.Ceiling(ActualHeight * px));
            }

            public void SetNear(bool value)
            {
                if (value == near) return;
                near = value;
                if (url == null) return;
                if (near) Load();
                else
                {
                    reader.images.Cancel(this);
                    bitmap = null;
                    InvalidateVisual();
                }
            }

            public void OnImage(BitmapSource b, bool fresh)
            {
                bitmap = b;
                InvalidateVisual();
            }

            protected override void OnRender(DrawingContext dc)
            {
                double w = ActualWidth, h = ActualHeight;
                if (bitmap == null)
                {
                    dc.DrawRectangle(Ui.Brush(Ui.SURFACE), null, new Rect(0, 0, w, h));
                }
                else
                {
                    double scale = Math.Min(w / bitmap.PixelWidth, h / bitmap.PixelHeight);
                    double dw = bitmap.PixelWidth * scale, dh = bitmap.PixelHeight * scale;
                    dc.DrawImage(bitmap, new Rect((w - dw) / 2, (h - dh) / 2, dw, dh));
                }
                if (!Video) return;
                bool on = reader.selectedVideo == this;
                string text = on ? (Stream != null ? "▶  Play video   Enter" : "Plays only in Apple News") : "▶  Video";
                FormattedText f = Ui.Format(text, Ui.SansBold, 14, Colors.White);
                dc.DrawRectangle(Ui.Brush(on && Stream != null ? Ui.ACCENT : Color.FromArgb(0xCC, 0, 0, 0)), null,
                    new Rect(12, h - 48, f.Width + 28, 36));
                dc.DrawText(f, new Point(26, h - 30 - f.Height / 2));
                if (on) dc.DrawRectangle(null, new Pen(Ui.Brush(Ui.FOCUS), 3), new Rect(1.5, 1.5, w - 3, h - 3));
            }
        }
    }
}
