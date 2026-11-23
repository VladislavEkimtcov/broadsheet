using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace Broadsheet
{
    /// <summary>
    /// Broadsheet for Windows: Apple News by way of Apple News Relay on a Mac, a port of the TV app.
    /// Front page and reader live in one window; the reader covers the front page, the options
    /// panel slides over both, and a story's video over everything. The whole page is laid out in
    /// the TV's units and scaled to the window, so it keeps its proportions from a laptop to a wall.
    ///
    /// DIVERGENCE FROM THE TV APP (src/io/github/vladislavekimtcov/broadsheet/, Java): this port
    /// started as a line-by-line copy of Main.java/Front.java/Reader.java/Options.java and has since
    /// grown Windows-only parts, on the owner's request. Whoever syncs the two should know these are
    /// deliberate, not drift: mouse and touchpad (hover focus, clicks, browser-like wheel and 1:1
    /// precision-touchpad scrolling in Scroller.cs), an icon-only back arrow in the reader and a
    /// hamburger for the options panel (IconButton), "Open in the browser" in Options, F5/F11 keys,
    /// subnet scan without Bonjour (Relay.cs), Windows time zone ids (Sun.cs), video via
    /// MediaElement with a browser fallback (Player.cs), and paragraphs split on line breaks
    /// (Reader.Block). Features added to the TV app later need porting here by hand; there is no
    /// shared code (C# 5 / .NET 4.8 vs Java / Android).
    /// </summary>
    sealed class MainWindow : Window, Front.IListener, Reader.IListener, Player.IListener, Options.IListener
    {
        /// <summary>Seconds the relay may spend getting a story's body from Apple News before answering.</summary>
        const int FETCH_WAIT = 14;
        const int REFRESH_PAGES = 4;
        static readonly int[] AUTO_REFRESH_MIN = { 25, 60, 0 };
        static readonly string[] AUTO_REFRESH_NAMES = { "every 25 minutes", "every hour", "off" };
        static readonly string[] THEMES = { "auto", "dark", "light" };
        static readonly string[] THEME_NAMES = { "Automatic", "Dark", "Light" };
        static readonly string[] READ_MODES = { "show", "dim", "hide" };
        static readonly string[] READ_NAMES = { "Show", "Dim", "Hide" };

        readonly Prefs prefs = new Prefs();
        readonly Relay relay;
        readonly Images images;
        readonly Library library = new Library();
        readonly Grid scaler = new Grid();
        readonly Grid root = new Grid();
        Front front;
        Reader reader;
        Player player;
        Options options;

        List<Section> baseSections = new List<Section>(), shown = new List<Section>();
        Section extra;
        readonly HashSet<string> savedIds = new HashSet<string>();
        readonly Dictionary<string, bool> savedNow = new Dictionary<string, bool>();
        Section openSection;
        int openIndex;
        double pendingRestore;
        long loadedAt;
        bool loading, refreshing;
        string message;

        // the options panel's state
        Story target;
        Options.Tile saveTile, reactTile, unreadTile, moreTile, sizeTile, themeTile, readTile, continueTile, refreshTile, webTile;
        string keptUnread;
        Story reading;
        readonly List<string[]> moreChoices = new List<string[]>();
        int moreIndex;
        bool saveBusy, reactBusy, moreBusy;
        string saveError, reactError, moreError;

        readonly DispatcherTimer retry, themeTick, prefetchTimer, periodic;
        readonly HashSet<string> prefetched = new HashSet<string>();
        Story focusedStory;
        WindowState beforeFull = WindowState.Maximized;

        public MainWindow()
        {
            Paths.Log("start");
            Title = "Broadsheet";
            Width = 1280;
            Height = 760;
            MinWidth = 720;
            MinHeight = 460;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            WindowState = prefs.Get("window", "max") == "max" ? WindowState.Maximized : WindowState.Normal;
            UseLayoutRounding = true;
            TextOptions.SetTextFormattingMode(this, TextFormattingMode.Ideal);
            RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.HighQuality);
            string version = typeof(MainWindow).Assembly.GetName().Version.ToString(3);
            Net.UserAgent = "Broadsheet/" + version + " (Windows)";

            relay = new Relay(Dispatcher, prefs);
            images = new Images(Dispatcher);
            ThreadPool.QueueUserWorkItem(_ => Images.Prune(150L * 1024 * 1024));

            scaler.Children.Add(root);
            Content = scaler;
            SizeChanged += (s, e) => Rescale();

            retry = Timer(30000, () => { retry.Stop(); Load(); });
            themeTick = Timer(60000, ApplyTheme);
            prefetchTimer = Timer(700, () =>
            {
                prefetchTimer.Stop();
                if (focusedStory != null && reader.Visibility != Visibility.Visible) Prefetch(focusedStory);
            });
            // A window left open all day: fetch the page again every ten minutes while it's showing.
            periodic = Timer(10 * 60000, () =>
            {
                if (WindowState != WindowState.Minimized && !player.IsPlaying) { front.UpdateDate(); Load(); }
            });
            periodic.Start();

            Paths.Log("timers");
            Ui.SetLight(WantLight());
            Paths.Log("fonts and theme");
            BuildViews();
            Icon = MakeIcon();
            Paths.Log("views");

            List<Section> cached = relay.CachedFront();
            if (cached != null && cached.Count > 0)
            {
                SetBase(cached);
                front.SetSections(shown, false);
                loadedAt = relay.CachedFrontTime();
                front.StatusText = "From " + Dates.Time(loadedAt) + "\nConnecting to the Mac…";
                Paths.Log("cached front page");
            }
            else
            {
                ShowMessage("Looking for Apple News Relay on your network…");
            }

            SourceInitialized += (s, e) => DarkTitleBar();
            ContentRendered += (s, e) => Paths.Log("first frame");
            Loaded += (s, e) =>
            {
                Paths.Log("loaded");
                Ui.PixelsPerDip = VisualTreeHelper.GetDpi(this).PixelsPerDip;
                ApplyTheme();
                Load();
            };
            Activated += (s, e) =>
            {
                if (!front.HasContent || Dates.NowMs() - loadedAt > 10 * 60 * 1000L) Load();
            };
            StateChanged += (s, e) =>
            {
                if (WindowState == WindowState.Minimized)
                {
                    LeaveStory();
                    library.Save();
                    images.Trim();
                }
                else if (WindowStyle != WindowStyle.None)
                {
                    prefs.Set("window", WindowState == WindowState.Maximized ? "max" : "normal");
                }
            };
            Closing += (s, e) =>
            {
                if (player.IsPlaying) player.Close();
                LeaveStory();
                library.Save();
            };
            PreviewKeyDown += OnKeyDown;
            PreviewKeyUp += (s, e) => { if (ReaderShown) reader.OnKeyUp(e.Key); };
            PreviewMouseRightButtonUp += (s, e) =>
            {
                if (player.IsPlaying) return;
                if (options.IsOpen) options.Close();
                else OpenOptions(OptionsTarget(), true);
                e.Handled = true;
            };
        }

        DispatcherTimer Timer(int ms, Action a)
        {
            DispatcherTimer t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(ms) };
            t.Tick += (s, e) => a();
            return t;
        }

        void Rescale()
        {
            double w = scaler.ActualWidth > 0 ? scaler.ActualWidth : ActualWidth;
            double h = scaler.ActualHeight > 0 ? scaler.ActualHeight : ActualHeight;
            if (w <= 0 || h <= 0) return;
            double s = Math.Max(0.8, Math.Min(2.0, Math.Min(w / 1240, h / 700)));
            s = Math.Round(s * 20) / 20; // steps of 5%, so a drag doesn't re-lay out every pixel
            if (Math.Abs(s - Ui.Scale) < 0.001 && root.LayoutTransform is ScaleTransform) return;
            Ui.Scale = s;
            root.LayoutTransform = new ScaleTransform(s, s);
        }

        void BuildViews()
        {
            root.Children.Clear();
            Background = Ui.Brush(Ui.PAPER);
            root.Background = Ui.Brush(Ui.PAPER);
            front = new Front(images, this);
            reader = new Reader(images, this);
            reader.SetTextSizeStep(prefs.GetInt("text", Reader.STANDARD));
            reader.Visibility = Visibility.Collapsed;
            player = new Player(this);
            options = new Options(this);
            root.Children.Add(front);
            root.Children.Add(reader);
            // Windows only: a hamburger at the top right slides the options panel out (Menu key, M
            // and a right-click do too); above the page and the reader, under video and the panel.
            IconButton menu = new IconButton(Icons.MENU, "Options", () => { if (options.IsOpen) options.Close(); else OpenOptions(OptionsTarget(), true); });
            menu.HorizontalAlignment = HorizontalAlignment.Right;
            menu.VerticalAlignment = VerticalAlignment.Top;
            menu.Margin = new Thickness(0, 16, 18, 0);
            root.Children.Add(menu);
            root.Children.Add(player);
            root.Children.Add(options);
            DarkTitleBar();
        }

        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        /// <summary>The title bar follows the page: dark at night (Windows 10 2004 and later).</summary>
        void DarkTitleBar()
        {
            IntPtr hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd == IntPtr.Zero) return;
            int dark = Ui.Light ? 0 : 1;
            try
            {
                if (DwmSetWindowAttribute(hwnd, 20, ref dark, 4) != 0) DwmSetWindowAttribute(hwnd, 19, ref dark, 4);
            }
            catch (Exception) { }
        }

        /// <summary>The window's icon, drawn here: a serif B on Broadsheet red (no image files to ship).</summary>
        static BitmapSource MakeIcon()
        {
            DrawingVisual v = new DrawingVisual();
            using (DrawingContext dc = v.RenderOpen())
            {
                dc.DrawRectangle(new SolidColorBrush(Ui.Rgb(0xC4262C)), null, new Rect(0, 0, 64, 64));
                FormattedText b = Ui.Format("B", Ui.DisplayFace, 50, Colors.White);
                dc.DrawText(b, new Point((64 - b.Width) / 2, (64 - b.Height) / 2 + 1));
                dc.DrawRectangle(Brushes.White, null, new Rect(14, 54, 36, 3));
            }
            RenderTargetBitmap r = new RenderTargetBitmap(64, 64, 96, 96, PixelFormats.Pbgra32);
            r.Render(v);
            r.Freeze();
            return r;
        }

        void ShowMessage(string text)
        {
            message = text;
            front.ShowMessage(text);
        }

        /// <summary>Says what went wrong; with nothing to show, tries again by itself (Enter tries at once).</summary>
        void Fail(string text)
        {
            if (front.HasContent)
            {
                front.ShowMessage(text);
                return;
            }
            ShowMessage(text + "\n\nTrying again every 30 seconds. Press Enter to try now.");
            retry.Stop();
            retry.Start();
        }

        // ------------------------------------------------------------------ loading

        /// <summary>Loads the front page from the relay, finding the relay first if needed.</summary>
        void Load()
        {
            if (loading) return;
            retry.Stop();
            if (!relay.Known)
            {
                loading = true;
                relay.Discover((url, error) =>
                {
                    loading = false;
                    if (url == null)
                    {
                        Fail(error + "\n\nStart Apple News Relay on the Mac (apple-news-relay serve --host 0.0.0.0), "
                            + "or put url=http://<mac>:8787 in " + Relay.ConfigPath + ".");
                        return;
                    }
                    Load();
                });
                return;
            }
            loading = true;
            relay.Front((sections, error) =>
            {
                loading = false;
                if (sections == null)
                {
                    if (relay.Origin == "last known")
                    {
                        // The Mac may have a new address: look again once.
                        relay.Discover((url, e2) =>
                        {
                            if (url != null) Load();
                            else Fail(error);
                        });
                        return;
                    }
                    Fail(error);
                    return;
                }
                if (sections.Count == 0)
                {
                    Fail("The relay has no stories yet. Open Apple News on the Mac once.");
                    return;
                }
                bool first = !front.HasContent;
                SetBase(sections);
                savedNow.Clear();
                front.SetSections(shown, !first);
                loadedAt = Dates.NowMs();
                front.StatusText = "Updated " + Dates.Time(loadedAt) + "\nApple News via " + relay.Where;
                FillOptions();
                MaybeRefreshToday();
            });
        }

        void SetBase(List<Section> sections)
        {
            baseSections = sections;
            savedIds.Clear();
            foreach (Section s in sections)
            {
                if (s.Id != "saved") continue;
                foreach (Story st in s.Stories) savedIds.Add(st.Id);
            }
            shown = Arrange();
        }

        /// <summary>The sections to show: "More from …" first, read stories left out when asked.</summary>
        List<Section> Arrange()
        {
            List<Section> out_ = new List<Section>();
            if (extra != null) out_.Add(extra);
            bool hide = ReadMode == "hide";
            foreach (Section s in baseSections)
            {
                if (!hide || s.Id == "saved")
                {
                    out_.Add(s);
                    continue;
                }
                List<Story> keep = new List<Story>();
                foreach (Story st in s.Stories) if (!library.IsRead(st.Id)) keep.Add(st);
                if (keep.Count > 0) out_.Add(new Section(s.Id, s.Title, keep));
            }
            return out_;
        }

        int AutoRefreshStep { get { return Math.Max(0, Math.Min(AUTO_REFRESH_MIN.Length - 1, prefs.GetInt("auto_refresh", 0))); } }

        /// <summary>Asks the Mac to refresh Today now and then; reloads quietly when it's done.</summary>
        void MaybeRefreshToday()
        {
            int minutes = AUTO_REFRESH_MIN[AutoRefreshStep];
            long last = prefs.GetLong("refreshed", 0);
            if (minutes == 0 || refreshing || Dates.NowMs() - last < minutes * 60 * 1000L) return;
            RefreshNow();
        }

        void RefreshNow()
        {
            if (refreshing) return;
            refreshing = true;
            prefs.Set("refreshed", Dates.NowMs());
            FillOptions();
            relay.RefreshToday(REFRESH_PAGES, (jobId, error) =>
            {
                if (jobId == null)
                {
                    refreshing = false;
                    FillOptions();
                    return;
                }
                front.StatusText = "Updated " + Dates.Time(loadedAt) + "\nRefreshing Apple News on the Mac…";
                relay.Job(jobId, 60, (state, e) =>
                {
                    refreshing = false;
                    Load();
                });
            });
        }

        // ------------------------------------------------------------------ reading

        public void OnOpen(Section section, int index)
        {
            openSection = section;
            openIndex = index;
            reader.Visibility = Visibility.Visible;
            Open(section.Stories[index]);
        }

        public bool IsDimmed(string storyId)
        {
            return ReadMode == "dim" && library.IsRead(storyId);
        }

        void Open(Story s)
        {
            LeaveStory();
            keptUnread = null;
            reading = s;
            reader.Show(s);
            Title = s.Title + " — Broadsheet";
            double at = library.Position(s.Id);
            pendingRestore = at > 0.04 && at < 0.95 ? at : 0;
            library.SetPosition(s, at);
            library.Save();
            // Ask without waiting, to say at once what the Mac is doing; then wait on its job.
            relay.Article(s.Id, 0, (a, error) =>
            {
                if (reader.Story != s) return;
                if (a == null || a.PendingJob == null)
                {
                    Arrived(s, a, error);
                    return;
                }
                reader.SetStage(a.JobState == "queued" ? "Waiting its turn on the Mac…" : "Apple News is fetching it on the Mac…");
                Follow(s, a.PendingJob, 0);
            });
        }

        /// <summary>Waits for the relay's job on a story, then shows what came of it.</summary>
        void Follow(Story s, string job, int round)
        {
            relay.Job(job, 12, (state, e) =>
            {
                if (reader.Story != s) return;
                if ((state == "running" || state == "queued") && round < 3)
                {
                    reader.SetStage("Still fetching: big stories and Apple News+ take longer…");
                    Follow(s, job, round + 1);
                    return;
                }
                relay.Article(s.Id, 0, (again, e2) =>
                {
                    if (reader.Story != s) return;
                    Arrived(s, again, e2);
                });
            });
        }

        public void OnFocusStory(Story story)
        {
            focusedStory = story;
            prefetchTimer.Stop();
            prefetchTimer.Start(); // resting on it, not skimming past
        }

        void PrefetchNext()
        {
            if (openSection != null && openIndex + 1 < openSection.Stories.Count) Prefetch(openSection.Stories[openIndex + 1]);
        }

        void Prefetch(Story s)
        {
            if (s.Completeness != "summary" || !prefetched.Add(s.Id)) return;
            relay.Prefetch(s.Id);
        }

        void Arrived(Story s, Article a, string error)
        {
            reader.SetArticle(a, error);
            if (a == null) return;
            if (a.PendingJob == null) PrefetchNext();
            library.SetReaction(s.Id, a.Reaction);
            if (pendingRestore > 0 && a.PendingJob == null)
            {
                reader.RestoreFraction(pendingRestore);
                pendingRestore = 0;
            }
        }

        /// <summary>Remembers how far into the open story the reader got.</summary>
        void LeaveStory()
        {
            Story s = reading;
            if (s == null || reader.Story != s || reader.Article == null) return;
            if (s.Id == keptUnread) return;
            double f = reader.Fraction;
            library.SetPosition(s, f > 0.97 ? 1 : f);
        }

        public void OnMove(int delta)
        {
            if (openSection == null) return;
            int next = openIndex + delta;
            if (next < 0 || next >= openSection.Stories.Count) return;
            openIndex = next;
            Open(openSection.Stories[next]);
        }

        public void OnClose()
        {
            LeaveStory();
            reading = null;
            library.Save();
            reader.Visibility = Visibility.Collapsed;
            Title = "Broadsheet";
            if (front.CurrentSection == openSection) front.FocusStory(openIndex);
            front.RefreshDim();
        }

        public void OnPlay(string stream, string title)
        {
            if (stream == null)
            {
                reader.SetStage("The publisher didn't share this video outside Apple News.");
                return;
            }
            player.Play(stream, title);
        }

        public void OnPlayerClosed()
        {
            ApplyTheme(); // a switch at sunset waits for the video to end
        }

        // ------------------------------------------------------------------ appearance

        string ThemeMode { get { return prefs.Get("theme", "auto"); } }

        bool WantLight()
        {
            switch (ThemeMode)
            {
                case "light": return true;
                case "dark": return false;
                default: return !Sun.Dark(Dates.NowMs(), TimeZoneInfo.Local);
            }
        }

        /// <summary>Light by day and dark after sunset (or as chosen); builds the views again on a change.</summary>
        void ApplyTheme()
        {
            themeTick.Stop();
            if (ThemeMode == "auto")
            {
                long now = Dates.NowMs();
                long next = Sun.NextChange(now, TimeZoneInfo.Local);
                themeTick.Interval = TimeSpan.FromMilliseconds(Math.Max(60 * 1000L, next - now + 30 * 1000L));
                themeTick.Start();
            }
            bool light = WantLight();
            if (light == Ui.Light || player.IsPlaying) return;
            Ui.SetLight(light);
            Rebuild();
        }

        /// <summary>New views in the new colours, with everything where it was.</summary>
        void Rebuild()
        {
            Section section = front.CurrentSection;
            Story focused = front.FocusedStory;
            bool rail = front.RailFocused;
            string status = front.StatusText;
            bool wasReading = reader.Visibility == Visibility.Visible;
            Story story = reader.Story;
            Article article = reader.Article;
            double fraction = reader.Fraction;
            bool optionsOpen = options.IsOpen;
            int optionIndex = options.SelectedIndex;
            BuildViews();
            if (shown.Count > 0) front.Restore(shown, section != null ? section.Id : null, focused != null ? focused.Id : null, rail, true);
            else if (message != null) front.ShowMessage(message);
            front.StatusText = status;
            if (wasReading && story != null)
            {
                reader.Visibility = Visibility.Visible;
                reader.Show(story);
                if (article != null) reader.SetArticle(article, null);
                reader.RestoreFraction(fraction);
            }
            if (optionsOpen)
            {
                OpenOptions(target, false);
                options.Select(optionIndex);
            }
        }

        string SunHint()
        {
            long now = Dates.NowMs();
            bool dark = Sun.Dark(now, TimeZoneInfo.Local);
            double next = Sun.Next(now, TimeZoneInfo.Local, dark);
            if (double.IsNaN(next)) return dark ? "Dark: the sun stays down today" : "Light: the sun stays up today";
            return (dark ? "Dark until sunrise at " : "Light until sunset at ") + Dates.Time((long)next);
        }

        void ToggleFullScreen()
        {
            if (WindowStyle == WindowStyle.None)
            {
                WindowStyle = WindowStyle.SingleBorderWindow;
                ResizeMode = ResizeMode.CanResize;
                WindowState = beforeFull;
            }
            else
            {
                beforeFull = WindowState;
                WindowState = WindowState.Normal; // so the taskbar is covered when maximised again
                WindowStyle = WindowStyle.None;
                ResizeMode = ResizeMode.NoResize;
                WindowState = WindowState.Maximized;
            }
        }

        // ------------------------------------------------------------------ options

        string ReadMode { get { return prefs.Get("read", "show"); } }

        static int IndexOf(string[] list, string value)
        {
            for (int i = 0; i < list.Length; i++) if (list[i] == value) return i;
            return 0;
        }

        bool ReaderShown { get { return reader.Visibility == Visibility.Visible; } }

        /// <summary>The story the panel acts on: the one being read, or the focused card.</summary>
        Story OptionsTarget()
        {
            return ReaderShown ? reader.Story : front.FocusedStory;
        }

        Article TargetArticle()
        {
            Article a = ReaderShown ? reader.Article : null;
            return a != null && target != null && a.Story != null && a.Story.Id == target.Id ? a : null;
        }

        bool IsSaved(Story s)
        {
            bool now;
            if (savedNow.TryGetValue(s.Id, out now)) return now;
            Article a = TargetArticle();
            if (a != null) return a.Saved;
            return savedIds.Contains(s.Id);
        }

        void OpenOptions(Story s, bool slide)
        {
            if (s != target)
            {
                saveError = reactError = moreError = null;
                moreIndex = 0;
            }
            target = s;
            moreChoices.Clear();
            if (s != null)
            {
                if (s.ChannelId != null && s.Channel != null)
                    moreChoices.Add(new[] { "More from " + s.Channel, "/v1/channels/" + s.ChannelId + "/articles?limit=45", "channels", s.ChannelId });
                Article a = TargetArticle();
                if (a != null)
                    foreach (string[] t in a.Topics)
                        moreChoices.Add(new[] { "More on " + t[1], "/v1/topics/" + t[0] + "/articles?limit=45", "topics", t[0] });
                moreChoices.Add(new[] { "Related stories", "/v1/articles/" + s.Id + "/related", null, null });
            }
            moreIndex = Math.Min(moreIndex, Math.Max(0, moreChoices.Count - 1));

            saveTile = new Options.Tile("☆", "Save story") { OnOk = ToggleSave };
            reactTile = new Options.Tile("♥", "Like or dislike")
            {
                Segments = new[] { "More like this", "Fewer like this" },
                SegmentIcons = new[] { Icons.THUMB_UP, Icons.THUMB_DOWN },
                Checked = new bool[2],
                OnOk = SendReaction
            };
            string r = s == null ? null : library.Reaction(s.Id);
            reactTile.Segment = r == "dislike" ? 1 : 0;
            unreadTile = new Options.Tile("○", "Mark as unread") { OnOk = ToggleRead };
            moreTile = new Options.Tile("≡", "More like this")
            {
                OnStep = d =>
                {
                    moreIndex = Math.Max(0, Math.Min(moreChoices.Count - 1, moreIndex + d));
                    moreError = null;
                    FillOptions();
                },
                OnOk = OpenMore
            };
            sizeTile = new Options.Tile("Aa", "Text size")
            {
                Slider = Reader.SIZES.Length,
                OnStep = d =>
                {
                    int step = Math.Max(0, Math.Min(Reader.SIZES.Length - 1, reader.SizeStep + d));
                    if (step == reader.SizeStep) return;
                    prefs.Set("text", step);
                    reader.SetTextSizeStep(step);
                    if (ReaderShown) reader.Relayout();
                    FillOptions();
                }
            };
            themeTile = new Options.Tile("◐", "Appearance")
            {
                OnStep = d =>
                {
                    int i = Math.Max(0, Math.Min(THEMES.Length - 1, IndexOf(THEMES, ThemeMode) + d));
                    prefs.Set("theme", THEMES[i]);
                    FillOptions();
                    ApplyTheme();
                }
            };
            readTile = new Options.Tile("◉", "Stories you've read")
            {
                OnStep = d =>
                {
                    int i = Math.Max(0, Math.Min(READ_MODES.Length - 1, IndexOf(READ_MODES, ReadMode) + d));
                    if (READ_MODES[i] == ReadMode) return;
                    prefs.Set("read", READ_MODES[i]);
                    shown = Arrange();
                    if (shown.Count > 0) front.SetSections(shown, true);
                    front.RefreshDim();
                    FillOptions();
                }
            };
            Story unfinished = ReaderShown ? null : library.Unfinished();
            continueTile = null;
            if (unfinished != null)
            {
                continueTile = new Options.Tile("▶", "Continue reading")
                {
                    OnOk = () =>
                    {
                        options.Close();
                        ContinueReading(unfinished);
                    }
                };
            }
            // On a computer there is a browser at hand: the publisher's own page, for links and embeds.
            webTile = null;
            if (s != null && (s.Url ?? s.AppleNewsUrl) != null)
            {
                string link = s.Url ?? s.AppleNewsUrl;
                webTile = new Options.Tile("↗", "Open in the browser")
                {
                    OnOk = () =>
                    {
                        try { Process.Start(new ProcessStartInfo(link) { UseShellExecute = true }); } catch (Exception) { }
                        options.Close();
                    }
                };
            }
            refreshTile = new Options.Tile("↻", "Refresh now")
            {
                OnOk = () => { RefreshNow(); Load(); },
                OnStep = d =>
                {
                    int i = Math.Max(0, Math.Min(AUTO_REFRESH_MIN.Length - 1, AutoRefreshStep + d));
                    prefs.Set("auto_refresh", i);
                    FillOptions();
                }
            };

            List<Options.Tile> tiles = new List<Options.Tile> { saveTile, reactTile, unreadTile, moreTile };
            if (webTile != null) tiles.Add(webTile);
            tiles.Add(sizeTile);
            tiles.Add(themeTile);
            tiles.Add(readTile);
            if (continueTile != null) tiles.Add(continueTile);
            tiles.Add(refreshTile);
            FillTiles();
            string about = s == null ? "Choose a story to save it, like it or find more like it."
                : (s.Channel != null ? s.Channel + "  ·  " : "") + s.Title;
            options.Open(tiles, about, slide);
            relay.Handoff((h, error) =>
            {
                if (!options.IsOpen || h == null) return;
                Story mac = Story.From(h.Obj("article"));
                if (mac == null || (ReaderShown && reader.Story != null && reader.Story.Id == mac.Id)) return;
                Options.Tile t = new Options.Tile(null, "On the Mac: " + (mac.Channel ?? "Apple News")) { VectorIcon = Icons.LAPTOP };
                long seen = Dates.Parse(h.Str("seen_at"));
                t.Value = h.Bool("on_screen") ? "now" : Dates.Ago(seen, Dates.NowMs());
                t.Hint = mac.Title + "  ·  Enter reads it here";
                t.OnOk = () =>
                {
                    options.Close();
                    ContinueReading(mac);
                };
                options.AddTop(t);
            });
            relay.Status((o, error) =>
            {
                if (!options.IsOpen) return;
                if (o == null)
                {
                    options.SetFooter("The Mac isn't answering" + (loadedAt > 0 ? " · showing the " + Dates.Time(loadedAt) + " page" : ""));
                    return;
                }
                JObj news = o.Obj("news_app"), jobs = o.Obj("jobs"), acct = o.Obj("account");
                int waiting = jobs == null ? 0 : jobs.Int("queued") + jobs.Int("running");
                options.SetFooter("Mac connected  ·  News " + (news != null && news.Bool("running") ? "open" : "closed")
                    + "  ·  " + (waiting == 0 ? "nothing waiting" : waiting + " waiting")
                    + (acct != null && acct.Bool("news_plus") ? "  ·  Apple News+" : ""));
            });
        }

        void FillOptions()
        {
            if (!options.IsOpen) return;
            FillTiles();
            options.Refresh();
        }

        /// <summary>Words on every tile, from the state now.</summary>
        void FillTiles()
        {
            Story s = target;
            bool has = s != null;
            const string pick = "Choose a story first";

            bool saved = has && IsSaved(s);
            saveTile.Enabled = has;
            saveTile.Icon = saved ? "★" : "☆";
            saveTile.Label = !has ? "Save story" : saveBusy ? (saved ? "Removing…" : "Saving…") : saved ? "Saved" : "Save story";
            saveTile.Value = saved && !saveBusy ? "✓" : "";
            saveTile.Hint = !has ? pick : saveBusy ? "Apple News on the Mac is doing it" : saveError ?? (saved
                ? "In Saved Stories on the Mac  ·  Enter takes it out" : "Enter puts it in Saved Stories on the Mac");

            string r = has ? library.Reaction(s.Id) : null;
            reactTile.Enabled = has;
            reactTile.Checked[0] = r == "like";
            reactTile.Checked[1] = r == "dislike";
            reactTile.Value = reactBusy ? "Sending…" : r == "like" ? "Liked" : r == "dislike" ? "Disliked" : "";
            reactTile.Hint = !has ? pick : reactError ?? (r != null ? "Enter on it again takes it back" : "Apple News suggests more or fewer like it");

            bool read = has && library.IsRead(s.Id) && s.Id != keptUnread;
            unreadTile.Enabled = has;
            unreadTile.Icon = read ? "○" : "●";
            unreadTile.Label = read ? "Mark as unread" : "Mark as read";
            unreadTile.Hint = !has ? pick : read ? "Read on this computer  ·  Enter makes it new again"
                : ReadMode == "dim" ? "Enter fades it on the front page" : ReadMode == "hide"
                ? "Enter hides it from the front page" : "Enter counts it as read";

            moreTile.Enabled = has && moreChoices.Count > 0;
            string[] choice = moreTile.Enabled ? moreChoices[moreIndex] : null;
            moreTile.Label = choice != null ? choice[0] : "More like this";
            moreTile.Value = moreChoices.Count > 1 ? (moreIndex + 1) + " of " + moreChoices.Count : "";
            moreTile.Hint = !has ? pick : moreBusy ? "Loading…" : moreError ?? (moreChoices.Count > 1
                ? "◀ ▶ more choices  ·  Enter shows them" : "Enter shows them");

            if (webTile != null) webTile.Hint = "The publisher's page, with its links and embeds";

            int step = reader.SizeStep;
            sizeTile.Position = step;
            double pt = Reader.SIZES[step];
            sizeTile.Value = (step == 0 ? "Close reading  ·  " : step == Reader.SIZES.Length - 1 ? "Across the room  ·  "
                : step == Reader.STANDARD ? "Standard  ·  " : "") + pt.ToString("0.#", System.Globalization.CultureInfo.InvariantCulture) + " pt";
            sizeTile.Hint = null;

            themeTile.Value = THEME_NAMES[IndexOf(THEMES, ThemeMode)];
            themeTile.Hint = ThemeMode == "auto" ? SunHint() : "◀ Automatic follows sunset";

            readTile.Value = READ_NAMES[IndexOf(READ_MODES, ReadMode)];
            int n = library.ReadCount;
            readTile.Hint = (n == 0 ? "None yet" : n + (n == 1 ? " story" : " stories") + " read on this computer") + "  ·  ◀ ▶";

            if (continueTile != null)
            {
                Story u = library.Unfinished();
                continueTile.Value = u != null ? Math.Round(library.Position(u.Id) * 100) + "%" : "";
                continueTile.Hint = u != null ? u.Title : "";
            }

            refreshTile.Value = loadedAt > 0 ? "Updated " + Dates.Time(loadedAt) : "";
            refreshTile.Hint = refreshing ? "Refreshing Apple News on the Mac…" : "Automatically " + AUTO_REFRESH_NAMES[AutoRefreshStep] + "  ·  ◀ ▶";
        }

        /// <summary>Read here or new again: what dims (or hides) it on the front page.</summary>
        void ToggleRead()
        {
            Story s = target;
            if (s == null) return;
            bool isOpen = ReaderShown && reader.Story != null && reader.Story.Id == s.Id;
            if (library.IsRead(s.Id) && s.Id != keptUnread)
            {
                library.MarkUnread(s.Id);
                if (isOpen) keptUnread = s.Id;
            }
            else
            {
                library.MarkRead(s.Id);
                if (s.Id == keptUnread) keptUnread = null;
            }
            library.Save();
            if (ReadMode == "hide" && !isOpen)
            {
                shown = Arrange();
                if (shown.Count > 0) front.SetSections(shown, true);
            }
            front.RefreshDim();
            FillOptions();
        }

        void ToggleSave()
        {
            Story s = target;
            if (s == null || saveBusy) return;
            bool want = !IsSaved(s);
            saveBusy = true;
            saveError = null;
            FillOptions();
            relay.SetSaved(s.Id, want, (result, error) =>
            {
                saveBusy = false;
                if (result != null)
                {
                    savedNow[s.Id] = result.Bool("saved", want);
                    Load(); // Saved Stories on the rail
                }
                else
                {
                    saveError = error;
                }
                FillOptions();
            });
        }

        void SendReaction()
        {
            Story s = target;
            if (s == null || reactBusy) return;
            string want = reactTile.Segment == 0 ? "like" : "dislike";
            if (want == library.Reaction(s.Id)) want = null; // pressed again: take it back
            reactBusy = true;
            reactError = null;
            FillOptions();
            string asked = want;
            relay.SetReaction(s.Id, want, (result, error) =>
            {
                reactBusy = false;
                if (result != null) library.SetReaction(s.Id, result.Has("reaction") && result.IsNull("reaction") ? null : (result.Str("reaction") ?? asked));
                else reactError = error;
                library.Save();
                FillOptions();
            });
        }

        void OpenMore()
        {
            if (moreChoices.Count == 0 || moreBusy) return;
            string[] c = moreChoices[moreIndex];
            moreBusy = true;
            moreError = null;
            FillOptions();
            relay.Stories(c[1], (list, error) =>
            {
                moreBusy = false;
                if (list == null || list.Count == 0)
                {
                    moreError = list == null ? error : "Nothing in the archive yet";
                    FillOptions();
                    return;
                }
                ShowExtra(new Section("more", c[0], list), true);
                if (c[2] == null) return;
                // Ask News on the Mac for more of the channel or topic; show them when they're in.
                relay.RefreshTag(c[2], c[3], (job, e) =>
                {
                    if (job == null) return;
                    relay.Job(job, 40, (state, e2) => relay.Stories(c[1], (more, e3) =>
                    {
                        if (more != null && extra != null && extra.Title == c[0] && more.Count > extra.Stories.Count)
                            ShowExtra(new Section("more", c[0], more), false);
                    }));
                });
            });
        }

        void ShowExtra(Section section, bool go)
        {
            extra = section;
            shown = Arrange();
            if (!go)
            {
                front.SetSections(shown, true);
                return;
            }
            options.Close();
            if (ReaderShown)
            {
                LeaveStory();
                reading = null;
                reader.Visibility = Visibility.Collapsed;
                Title = "Broadsheet";
            }
            front.SetSections(shown, false);
            front.Open("more");
        }

        void ContinueReading(Story s)
        {
            Section inSection = null;
            int index = 0;
            foreach (Section sec in shown)
            {
                for (int i = 0; i < sec.Stories.Count && inSection == null; i++)
                {
                    if (sec.Stories[i].Id == s.Id)
                    {
                        inSection = sec;
                        index = i;
                    }
                }
            }
            if (inSection == null) inSection = new Section("continue", "Continue", new List<Story> { s });
            OnOpen(inSection, index);
        }

        public void OnOptionsClosed()
        {
            library.Save();
        }

        // ------------------------------------------------------------------ keys

        void OnKeyDown(object sender, KeyEventArgs e)
        {
            Key k = e.Key == Key.System ? e.SystemKey : e.Key;
            if (k == Key.F11 || (k == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Alt) != 0))
            {
                ToggleFullScreen();
                e.Handled = true;
                return;
            }
            if (player.IsPlaying)
            {
                e.Handled = player.OnKey(k);
                return;
            }
            if (options.IsOpen)
            {
                e.Handled = options.OnKey(k, e.IsRepeat);
                return;
            }
            if (k == Key.Apps || k == Key.M || (k == Key.F10 && (Keyboard.Modifiers & ModifierKeys.Shift) != 0))
            {
                if (!e.IsRepeat) OpenOptions(OptionsTarget(), true);
                e.Handled = true;
                return;
            }
            if (k == Key.F5)
            {
                if (!e.IsRepeat) { RefreshNow(); Load(); }
                e.Handled = true;
                return;
            }
            if (k == Key.Escape && WindowStyle == WindowStyle.None && !ReaderShown)
            {
                ToggleFullScreen();
                e.Handled = true;
                return;
            }
            if (ReaderShown)
            {
                e.Handled = reader.OnKey(k);
                return;
            }
            if (!front.HasContent && k == Key.Enter)
            {
                if (!loading)
                {
                    ShowMessage("Looking for Apple News Relay on your network…");
                    Load();
                }
                e.Handled = true;
                return;
            }
            e.Handled = front.OnKey(k);
        }
    }
}
