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
    /// PARITY WITH THE TV APP (src/io/github/vladislavekimtcov/broadsheet/, Java): there is no shared
    /// code (C# 5 / .NET 4.8 vs Java / Android), so features are ported by hand. PARITY.md at the repo
    /// root lists every feature, what each app does, and which differences are deliberate (mouse,
    /// touchpad, icon buttons, Open in the browser, F5/F11, no Bonjour, MediaElement video, paragraph
    /// splitting). Code is tagged "// feature: <id>"; grep the ID to find the other side.
    /// </summary>
    sealed class MainWindow : Window, Front.IListener, Reader.IListener, Player.IListener, Options.IListener, Events.IListener
    {
        /// <summary>Seconds the relay may spend getting a story's body from Apple News before answering.</summary>
        const int FETCH_WAIT = 14;
        const int REFRESH_PAGES = 4;
        static readonly int[] AUTO_REFRESH_MIN = { 25, 60, 0 };
        static readonly string[] AUTO_REFRESH_NAMES = { "every 25 minutes", "every hour", "off" };
        static readonly string[] THEMES = { "auto", "dark", "light" };
        static readonly string[] THEME_NAMES = { "Automatic", "Dark", "Light" };
        static readonly string[] READ_MODES = { "show", "dim", "hide" };
        static readonly string[] READ_NAMES = { "Do nothing", "Dim after reading", "Hide after reading" };

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
        /// <summary>More stories like this: {title, path, kind, id} per choice, for the story in Options.</summary>
        readonly List<string[]> moreChoices = new List<string[]>();
        /// <summary>Each choice's stories, fetched as soon as Options opens (path → list, when, error).</summary>
        readonly Dictionary<string, List<Story>> moreLists = new Dictionary<string, List<Story>>();
        readonly Dictionary<string, long> moreAt = new Dictionary<string, long>();
        readonly Dictionary<string, string> moreErrors = new Dictionary<string, string>();
        readonly HashSet<string> moreLoading = new HashSet<string>();
        /// <summary>The choice picked before its stories were in: opens when they are.</summary>
        string[] moreWanted;
        List<Options.Tile> moreRows;
        bool saveBusy, reactBusy;
        string saveError, reactError;

        readonly DispatcherTimer retry, themeTick, prefetchTimer, periodic, reloadSoon;
        Story focusedStory;
        int focusedIndex;
        WindowState beforeFull = WindowState.Maximized;

        readonly Events events;
        /// <summary>This computer's name for its interest list at the relay.</summary>
        readonly string clientId;
        /// <summary>The front page's version at the relay, to tell whether a change is news.</summary>
        long frontVersion;
        /// <summary>A newer front page that came while reading; shown on the way out.</summary>
        List<Section> deferred;
        /// <summary>Stories the relay has a body for, learnt since the front page came.</summary>
        readonly HashSet<string> bodied = new HashSet<string>();
        readonly HashSet<string> headsAsked = new HashSet<string>();
        /// <summary>Sections with a page on its way.</summary>
        readonly HashSet<string> paging = new HashSet<string>();
        /// <summary>The open story: the relay job it waits on, when it opened, what was asked for it.</summary>
        string waitingJob;
        readonly System.Diagnostics.Stopwatch openClock = new System.Diagnostics.Stopwatch();
        bool restAsked, aheadAsked;
        /// <summary>The last key, wheel or click: live changes wait until it's been still a moment.</summary>
        long lastInputAt;
        // The interest list (see Want): what was sent, what waits to go, and when the last one went.
        List<string> interestSent = new List<string>(), interestPending;
        long interestSentAt;
        readonly DispatcherTimer interestTimer;

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
            ThreadPool.QueueUserWorkItem(_ => Bodies.Prune());
            events = new Events(relay, this, Dispatcher);
            clientId = prefs.Get("client", null);
            if (clientId == null)
            {
                clientId = "win-" + Guid.NewGuid().ToString("N").Substring(0, 10);
                prefs.Set("client", clientId);
            }

            scaler.Children.Add(root);
            Content = scaler;
            SizeChanged += (s, e) => Rescale();

            retry = Timer(30000, () => { retry.Stop(); Load(); });
            themeTick = Timer(60000, ApplyTheme);
            prefetchTimer = Timer(300, () =>  // resting on a card, not skimming past
            {
                prefetchTimer.Stop();
                AheadOfFocus();
            });
            // The event stream says when the Mac's page changes; without it, look every ten minutes.
            periodic = Timer(10 * 60000, () =>
            {
                front.UpdateDate();
                if (WindowState != WindowState.Minimized && !player.IsPlaying && events.DownFor > 2 * 60000) Load();
            });
            periodic.Start();
            // A change at the relay: reload once the keys and pointer have been still for a moment, so
            // the page doesn't change under someone moving around it.
            reloadSoon = Timer(1500, () =>
            {
                long still = Dates.NowMs() - lastInputAt;
                if (still < 8000)
                {
                    reloadSoon.Interval = TimeSpan.FromMilliseconds(8000 - still);
                    return;
                }
                reloadSoon.Stop();
                reloadSoon.Interval = TimeSpan.FromMilliseconds(1500);
                Load();
            });
            interestTimer = Timer(400, () =>
            {
                interestTimer.Stop();
                SendInterest();
            });

            Paths.Log("timers");
            Ui.SetLight(WantLight());
            Paths.Log("fonts and theme");
            BuildViews();
            Icon = MakeIcon();
            Paths.Log("views");

            relay.CachedFront((page, error) =>  // feature: front.cache
            {
                if (front.HasContent) return; // the relay answered first
                if (page != null && page.Sections.Count > 0)
                {
                    SetBase(page.Sections);
                    front.SetSections(shown, false);
                    loadedAt = relay.CachedFrontTime();
                    frontVersion = page.Version;
                    front.StatusText = "From " + Dates.Time(loadedAt) + "\nConnecting to the Mac…";
                    Paths.Log("cached front page");
                }
                else if (!front.HasContent)
                {
                    ShowMessage("Looking for Apple News Relay on your network…");
                }
            });

            SourceInitialized += (s, e) => DarkTitleBar();
            ContentRendered += (s, e) => Paths.Log("first frame");
            Loaded += (s, e) =>
            {
                Paths.Log("loaded");
                Ui.PixelsPerDip = VisualTreeHelper.GetDpi(this).PixelsPerDip;
                ApplyTheme();
                events.Start();
                Load();
            };
            Activated += (s, e) =>
            {
                if (!front.HasContent || (!events.Connected && Dates.NowMs() - loadedAt > 10 * 60 * 1000L)) Load();
            };
            StateChanged += (s, e) =>
            {
                if (WindowState == WindowState.Minimized)
                {
                    LeaveStory();
                    library.Save();
                    images.Trim();
                    // Out of sight: no stream, nothing fetched ahead.
                    events.Stop();
                    ClearInterest();
                    images.CancelWarm();
                }
                else
                {
                    events.Start();
                    if (WindowStyle != WindowStyle.None) prefs.Set("window", WindowState == WindowState.Maximized ? "max" : "normal");
                }
            };
            Closing += (s, e) =>
            {
                if (player.IsPlaying) player.Close();
                LeaveStory();
                library.Save();
                events.Stop();
                ClearInterest();
            };
            PreviewKeyDown += (s, e) => lastInputAt = Dates.NowMs();
            PreviewMouseWheel += (s, e) => lastInputAt = Dates.NowMs();
            PreviewMouseDown += (s, e) => lastInputAt = Dates.NowMs();
            PreviewKeyDown += OnKeyDown;
            PreviewKeyUp += (s, e) => { if (ReaderShown) reader.OnKeyUp(e.Key); };
            PreviewMouseRightButtonUp += (s, e) =>
            {
                if (player.IsPlaying) return;
                if (options.IsOpen) options.Close();
                else OpenOptions(OptionsTarget(), true);
                e.Handled = true;
            };
            MouseUp += (s, e) =>  // the reader handles its own back button first
            {
                if (e.ChangedButton != MouseButton.XButton1 || player.IsPlaying || options.IsOpen || ReaderShown) return;
                front.OnBack();  // feature: front.back
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
            IconButton menu = new IconButton(Icons.MENU, "Options", () => { if (options.IsOpen) options.Close(); else OpenOptions(OptionsTarget(), true); });  // feature: win.icon_buttons
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
            relay.Front((page, error) =>
            {
                loading = false;
                if (page == null)
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
                if (page.Unchanged) // nothing new: nothing on screen moves
                {
                    loadedAt = Dates.NowMs();
                    front.StatusText = "Updated " + Dates.Time(loadedAt) + "\nApple News via " + relay.Where;
                    Paths.Log("front 304");
                    MaybeRefreshToday();
                    return;
                }
                if (page.Sections.Count == 0)
                {
                    Fail("The relay has no stories yet. Open Apple News on the Mac once.");
                    return;
                }
                frontVersion = page.Version;
                loadedAt = Dates.NowMs();
                if (ReaderShown && front.HasContent)
                {
                    deferred = page.Sections; // the page under the story changes when the story closes
                    return;
                }
                Apply(page.Sections);
            });
        }

        void Apply(List<Section> sections)
        {
            bool first = !front.HasContent;
            deferred = null;
            SetBase(sections);
            savedNow.Clear();
            front.SetSections(shown, !first);
            front.StatusText = "Updated " + Dates.Time(loadedAt) + "\nApple News via " + relay.Where;
            FillOptions();
            MaybeRefreshToday();
        }

        // ------------------------------------------------------------------ live

        public void OnEvent(string type, JObj data)  // feature: front.live
        {
            switch (type)
            {
                case "hello": // (re)connected: was anything missed?
                    if ((long)data.Num("front_version", 0) != frontVersion && !loading) Load();
                    break;
                case "front.updated":
                    if ((long)data.Num("version", 0) == frontVersion) break;
                    reloadSoon.Stop();
                    reloadSoon.Start(); // changes come in bursts
                    break;
                case "article.body_captured":
                    {
                        string id = data.Opt("id");
                        bodied.Add(id);
                        Article a = reader.Article;
                        if (reading != null && id == reading.Id && waitingJob == null && a != null && a.Completeness == "summary") FetchOpen(reading); // it came after all
                        else if (interestSent.Contains(id)) PullHead(id);
                        break;
                    }
                case "job.updated":
                    if (waitingJob != null && waitingJob == data.Opt("id") && data.Opt("state") == "running")
                        reader.SetStage("Apple News is fetching it on the Mac…");
                    break;
            }
        }

        public void OnStream(bool up)
        {
            if (up && !front.HasContent && !loading) Load();
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
                if (keep.Count == 0) continue;
                Section copy = new Section(s.Id, s.Title, keep);
                copy.Next = s.Next;
                copy.Total = s.Total;
                copy.Today = s.Today;
                out_.Add(copy);
            }
            return out_;
        }

        int AutoRefreshStep { get { return Math.Max(0, Math.Min(AUTO_REFRESH_MIN.Length - 1, prefs.GetInt("auto_refresh", 0))); } }

        /// <summary>Asks the Mac to refresh Today now and then; reloads quietly when it's done.</summary>
        void MaybeRefreshToday()  // feature: opt.auto_refresh
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
            waitingJob = null;
            restAsked = aheadAsked = false;
            openClock.Restart();
            relay.Background(() => Bodies.Get(s.Id), kept =>  // feature: reader.body_cache
            {
                if (reader.Story != s) return;
                if (kept == null)
                {
                    FetchOpen(s);
                    return;
                }
                Arrived(s, new Article(kept), null, "kept here");
                // Kept text doesn't change; whether it's saved or liked may have.
                relay.ArticleState(s.Id, (st, e) =>
                {
                    if (st == null || reader.Story != s) return;
                    library.SetReaction(s.Id, st.Str("reaction"));
                    if (st.Has("saved")) savedNow[s.Id] = st.Bool("saved");
                    FillOptions();
                });
            });
        }

        /// <summary>One trip: the relay waits for Apple News (up to FETCH_WAIT s) and sends the opening.</summary>
        void FetchOpen(Story s)
        {
            relay.Article(s.Id, FETCH_WAIT, Relay.HEAD_CHARS, (a, error) =>
            {
                if (reader.Story != s) return;
                if (a == null || a.PendingJob == null)
                {
                    Arrived(s, a, error, "from the Mac");
                    return;
                }
                waitingJob = a.PendingJob;
                reader.SetStage(a.JobState == "queued" ? "Waiting its turn on the Mac…" : "Still fetching: big stories and Apple News+ take longer…");
                Follow(s, a.PendingJob, 0);
            });
        }

        /// <summary>Waits for the relay's job on a story, then shows what came of it.</summary>
        void Follow(Story s, string job, int round)
        {
            relay.Job(job, 12, (state, e) =>
            {
                if (reader.Story != s) return;
                if ((state == "running" || state == "queued") && round < 2)
                {
                    reader.SetStage("Still fetching: big stories and Apple News+ take longer…");
                    Follow(s, job, round + 1);
                    return;
                }
                waitingJob = null;
                relay.Article(s.Id, 0, Relay.HEAD_CHARS, (again, e2) =>
                {
                    if (reader.Story != s) return;
                    Arrived(s, again, e2, "from the Mac");
                });
            });
        }

        // ------------------------------------------------------------------ ahead of time

        public void OnFocusStory(Story story, int index)
        {
            focusedStory = story;
            focusedIndex = index;
            prefetchTimer.Stop();
            prefetchTimer.Start(); // resting on it, not skimming past
        }

        /// <summary>Resting on a card: it and the cards around it.</summary>
        void AheadOfFocus()  // feature: reader.prefetch
        {
            Section sec = front.CurrentSection;
            if (focusedStory == null || sec == null || ReaderShown) return;
            int[] around = Ladder.Around(focusedIndex, sec.Stories.Count);
            Want(Pick(sec, around));
            for (int k = 0; k < Math.Min(2, around.Length); k++) PullHeadIfThere(sec.Stories[around[k]]);
        }

        public void OnRailFocus(int index, int direction)  // feature: front.warm
        {
            if (index < 0 || index >= shown.Count) return;
            int[] hero = front.HeroPixels;
            int tile = front.TilePixels;
            int[] beside = Ladder.RailNeighbours(index, shown.Count, direction);
            List<string> urls = new List<string>();
            foreach (int n in beside)
            {
                Section s = shown[n];
                bool heroed = s.Stories.Count >= 3;
                for (int k = 0; k < Math.Min(6, s.Stories.Count) && urls.Count < 12; k++)
                {
                    if (heroed && (k == 1 || k == 2)) continue; // briefs have no picture
                    urls.Add(s.Stories[k].ImageFor(heroed && k == 0 ? hero[0] : tile));
                }
            }
            images.Warm(urls);
            if (beside.Length > 0 && shown[beside[0]].Stories.Count >= 3)
            {
                // The way the reader is going: that section's hero ready to draw.
                images.Preload(shown[beside[0]].Stories[0].ImageFor(hero[0]), hero[0], hero[1]);
            }
        }

        public void OnRailRest(int index)
        {
            if (index < 0 || index >= shown.Count || ReaderShown) return;
            Section s = shown[index];
            List<Story> first = s.Stories.GetRange(0, Math.Min(3, s.Stories.Count));
            Want(first);
            foreach (Story st in first) PullHeadIfThere(st);
        }

        public void OnNearEnd(Section section)
        {
            LoadMore(section);
        }

        /// <summary>The next page of a section, under the cards already there.</summary>
        void LoadMore(Section sec)  // feature: front.deep
        {
            if (sec.Next == null || !paging.Add(sec.Id)) return;
            relay.Page(sec.Next, (page, e) =>
            {
                paging.Remove(sec.Id);
                if (page == null) return;
                Section whole = null;
                foreach (Section b in baseSections) if (b.Id == sec.Id) whole = b;
                if (whole != null && whole != sec)
                {
                    Add(whole, page.Stories, false);
                    whole.Next = page.Next;
                }
                Add(sec, page.Stories, whole != null && whole != sec);
                sec.Next = page.Next;
                front.Append(sec);
            });
        }

        void Add(Section sec, List<Story> more, bool unreadOnly)
        {
            HashSet<string> have = new HashSet<string>();
            foreach (Story st in sec.Stories) have.Add(st.Id);
            foreach (Story st in more) if (have.Add(st.Id) && (!unreadOnly || !library.IsRead(st.Id))) sec.Stories.Add(st);
        }

        public void OnNearLoadedEnd()
        {
            LoadRest(reading);
        }

        /// <summary>The rest of the open story, once the reader gets near the end of its opening.</summary>
        void LoadRest(Story s)  // feature: reader.progressive
        {
            int from = reader.NextBlock;
            if (s == null || reader.Story != s || from < 0 || restAsked) return;
            restAsked = true;
            relay.ArticleRest(s.Id, from, (rest, e) =>
            {
                restAsked = false;
                if (reader.Story != s || rest == null) return;
                reader.AppendBlocks(rest);
                JObj json = rest.Json;
                relay.Background(() => Bodies.Merge(s.Id, json), x => { });
                if (pendingRestore > 0 && reader.NextBlock < 0)
                {
                    reader.RestoreFraction(pendingRestore);
                    pendingRestore = 0;
                }
            });
        }

        public void OnNearStoryEnd()
        {
            if (aheadAsked || openSection == null) return;
            aheadAsked = true;
            int n = openSection.Stories.Count;
            Want(Pick(openSection, Ladder.Reading(openIndex, n)));
            if (openIndex + 1 < n)
            {
                Story next = openSection.Stories[openIndex + 1];
                PullHeadIfThere(next);
                images.Warm(new List<string> { next.ImageFor((int)Math.Ceiling(700 * Ui.Scale * Ui.PixelsPerDip)) });
            }
            if (openSection.Next != null && openIndex + 3 >= n) LoadMore(openSection);
        }

        static List<Story> Pick(Section sec, int[] indexes)
        {
            List<Story> out_ = new List<Story>();
            foreach (int i in indexes) out_.Add(sec.Stories[i]);
            return out_;
        }

        /// <summary>Whether the relay has the story's body (so its opening can come here now).</summary>
        bool OnRelay(Story s)
        {
            return bodied.Contains(s.Id) || s.Completeness != "summary";
        }

        void PullHeadIfThere(Story s)
        {
            if (OnRelay(s)) PullHead(s.Id);
        }

        /// <summary>Puts a story's opening on this computer, if the relay has it and this computer doesn't.</summary>
        void PullHead(string id)
        {
            if (!relay.Modern || headsAsked.Count > 500 || !headsAsked.Add(id)) return;
            relay.Background(() => Bodies.Has(id), has =>
            {
                if (has) return;
                relay.Head(id, data => { if (data != null) Bodies.Put(data); });
            });
        }

        /// <summary>
        /// The stories the reader may open next, sent to the relay (which has Apple News fetch them in
        /// that order). Only stories the relay has no body for go; a list the same as the last isn't sent
        /// again, and lists go at most every 400 ms, the newest replacing the one before.
        /// </summary>
        void Want(List<Story> stories)
        {
            List<string> ids = new List<string>();
            foreach (Story st in stories) if (st != null && !OnRelay(st) && !ids.Contains(st.Id)) ids.Add(st.Id);
            List<string> last = interestPending ?? interestSent;
            if (ids.Count == last.Count && ids.TrueForAll(last.Contains)) return;
            interestPending = ids;
            interestTimer.Stop();
            interestTimer.Interval = TimeSpan.FromMilliseconds(Math.Max(1, interestSentAt + 400 - Dates.NowMs()));
            interestTimer.Start();
        }

        void SendInterest()
        {
            if (interestPending == null) return;
            interestSent = interestPending;
            interestPending = null;
            interestSentAt = Dates.NowMs();
            relay.Interest(clientId, interestSent, (cached, e) =>
            {
                if (cached == null) return;
                foreach (string id in cached)
                {
                    bodied.Add(id);
                    PullHead(id);
                }
            });
        }

        void ClearInterest()
        {
            interestTimer.Stop();
            interestPending = null;
            if (interestSent.Count == 0) return;
            interestSent = new List<string>();
            relay.ClearInterest(clientId);
        }

        void Arrived(Story s, Article a, string error, string source)
        {
            reader.SetArticle(a, error);
            if (a == null) return;
            Paths.Log("open " + s.Id + " " + openClock.ElapsedMilliseconds + " ms " + source + (a.NextBlock >= 0 ? " (opening)" : "") + " " + a.Completeness);
            if (a.PendingJob == null && a.Completeness != "summary")
            {
                bodied.Add(s.Id);
                if (source != "kept here")
                {
                    JObj json = a.Json;
                    relay.Background(() => { Bodies.Put(json); return true; }, x => { });
                }
                // Right should open at once: the next story, fetched ahead.
                if (openSection != null && openIndex + 1 < openSection.Stories.Count)
                {
                    Story next = openSection.Stories[openIndex + 1];
                    Want(new List<Story> { next });
                    PullHeadIfThere(next);
                }
            }
            library.SetReaction(s.Id, a.Reaction);
            if (pendingRestore > 0 && a.PendingJob == null)
            {
                if (a.NextBlock >= 0) LoadRest(s); // the place is somewhere in the whole story
                else
                {
                    reader.RestoreFraction(pendingRestore);
                    pendingRestore = 0;
                }
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
            waitingJob = null;
            library.Save();
            reader.Visibility = Visibility.Collapsed;
            Title = "Broadsheet";
            if (deferred != null) Apply(deferred); // the page changed while reading
            else if (front.CurrentSection == openSection) front.FocusStory(openIndex);
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
        void ApplyTheme()  // feature: theme.sun
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
            if (s != target) saveError = reactError = null;
            moreWanted = null;
            moreRows = null;
            target = s;
            moreChoices.Clear();
            if (s != null)
            {
                if (s.ChannelId != null && s.Channel != null)
                    moreChoices.Add(new[] { "More from " + s.Channel, "/v1/channels/" + s.ChannelId + "/articles?limit=45", "channels", s.ChannelId });  // feature: opt.more_from
                Article a = TargetArticle();
                if (a != null)
                    foreach (string[] t in a.Topics)
                        moreChoices.Add(new[] { "More on " + t[1], "/v1/topics/" + t[0] + "/articles?limit=45", "topics", t[0] });
                moreChoices.Add(new[] { "Related stories", "/v1/articles/" + s.Id + "/related", null, null });  // feature: opt.related
            }
            foreach (string[] c in moreChoices) PreloadMore(c);

            saveTile = new Options.Tile("☆", "Save story on Mac") { OnOk = ToggleSave };  // feature: opt.save
            reactTile = new Options.Tile("♥", "Like or dislike")  // feature: opt.react
            {
                Segments = new[] { "More like this", "Fewer like this" },
                SegmentIcons = new[] { Icons.THUMB_UP, Icons.THUMB_DOWN },
                Checked = new bool[2],
                OnOk = SendReaction
            };
            string r = s == null ? null : library.Reaction(s.Id);
            reactTile.Segment = r == "dislike" ? 1 : 0;
            unreadTile = new Options.Tile("○", "Mark as unread") { OnOk = ToggleRead };  // feature: opt.unread
            moreTile = new Options.Tile("≡", "More stories like this") { OnOk = OpenMoreMenu, Opens = true };
            sizeTile = new Options.Tile("Aa", "Text size")  // feature: opt.text_size
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
            themeTile = new Options.Tile("◐", "Dark mode")  // feature: opt.theme
            {
                OnStep = d =>
                {
                    int i = Math.Max(0, Math.Min(THEMES.Length - 1, IndexOf(THEMES, ThemeMode) + d));
                    SetTheme(THEMES[i]);
                },
                OnCycle = () => SetTheme(THEMES[(IndexOf(THEMES, ThemeMode) + 1) % THEMES.Length])
            };
            readTile = new Options.Tile("◉", "Stories you've read")  // feature: opt.read_mode
            {
                OnStep = d =>
                {
                    int i = Math.Max(0, Math.Min(READ_MODES.Length - 1, IndexOf(READ_MODES, ReadMode) + d));
                    SetReadMode(READ_MODES[i]);
                },
                OnCycle = () => SetReadMode(READ_MODES[(IndexOf(READ_MODES, ReadMode) + 1) % READ_MODES.Length])
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
                webTile = new Options.Tile("↗", "Open in the browser")  // feature: win.browser
                {
                    OnOk = () =>
                    {
                        try { Process.Start(new ProcessStartInfo(link) { UseShellExecute = true }); } catch (Exception) { }
                        options.Close();
                    }
                };
            }
            refreshTile = new Options.Tile("↻", "Refresh now")  // feature: opt.refresh
            {
                Stepper = true,
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
            relay.Handoff((h, error) =>  // feature: opt.handoff
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
                // Stories fetched ahead aren't anyone waiting.
                int waiting = jobs == null ? 0 : Math.Max(0, jobs.Int("queued") + jobs.Int("running") - jobs.Int("warming"));
                options.SetFooter("Mac connected  ·  News " + (news != null && news.Bool("running") ? "open" : "closed")
                    + "  ·  " + (waiting == 0 ? "nothing waiting" : waiting + " waiting")
                    + (acct != null && acct.Bool("news_plus") ? "  ·  Apple News+" : ""));
            });
        }

        void SetTheme(string mode)
        {
            prefs.Set("theme", mode);
            FillOptions();
            ApplyTheme();
        }

        void SetReadMode(string mode)
        {
            if (mode == ReadMode) return;
            prefs.Set("read", mode);
            shown = Arrange();
            if (shown.Count > 0) front.SetSections(shown, true);
            front.RefreshDim();
            FillOptions();
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
            saveTile.Label = saveBusy ? (saved ? "Removing…" : "Saving…") : saved ? "Unsave story on Mac" : "Save story on Mac";
            saveTile.Value = saved && !saveBusy ? "✓" : "";
            saveTile.Hint = !has ? pick : saveError;

            string r = has ? library.Reaction(s.Id) : null;
            reactTile.Enabled = has;
            reactTile.Checked[0] = r == "like";
            reactTile.Checked[1] = r == "dislike";
            reactTile.Value = reactBusy ? "Sending…" : r == "like" ? "Liked" : r == "dislike" ? "Disliked" : "";
            reactTile.Hint = !has ? pick : reactError;

            bool read = has && library.IsRead(s.Id) && s.Id != keptUnread;
            unreadTile.Enabled = has;
            unreadTile.Icon = read ? "○" : "●";
            unreadTile.Label = read ? "Mark as unread" : "Mark as read";
            unreadTile.Hint = !has ? pick : null;

            moreTile.Enabled = has && moreChoices.Count > 0;
            moreTile.Hint = !has ? pick : null;
            FillMoreRows();

            int step = reader.SizeStep;
            sizeTile.Position = step;
            double pt = Reader.SIZES[step];
            sizeTile.Value = (step == 0 ? "Close reading  ·  " : step == Reader.SIZES.Length - 1 ? "Across the room  ·  "
                : step == Reader.STANDARD ? "Standard  ·  " : "") + pt.ToString("0.#", System.Globalization.CultureInfo.InvariantCulture) + " pt";
            sizeTile.Hint = null;

            themeTile.Value = THEME_NAMES[IndexOf(THEMES, ThemeMode)];
            themeTile.Hint = ThemeMode == "auto" ? SunHint() : "◀ Automatic follows sunset";

            readTile.Value = "";
            readTile.Hint = READ_NAMES[IndexOf(READ_MODES, ReadMode)] + "  ·  ◀ ▶";

            if (continueTile != null)
            {
                Story u = library.Unfinished();
                continueTile.Value = u != null ? Math.Round(library.Position(u.Id) * 100) + "%" : "";
                continueTile.Hint = u != null ? u.Title : "";
            }

            // ‹ how often › beside the label, like the phone; a click elsewhere refreshes now.
            refreshTile.Value = new[] { "25 min", "Hourly", "Off" }[AutoRefreshStep];
            refreshTile.Hint = refreshing ? "Refreshing Apple News on the Mac…" : loadedAt > 0 ? "Updated " + Dates.Time(loadedAt) : null;
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

        // ------------------------------------------------------------------ more stories like this

        /// <summary>Fetches a choice's stories ahead (once every ten minutes), with their pictures and
        /// the first openings, so picking it in the pop-up shows the section at once.</summary>
        void PreloadMore(string[] c)  // feature: opt.more_menu
        {
            long at;
            if (moreLoading.Contains(c[1]) || (moreAt.TryGetValue(c[1], out at) && Dates.NowMs() - at < 10 * 60000L)) return;
            moreLoading.Add(c[1]);
            moreErrors.Remove(c[1]);
            relay.Stories(c[1], (list, error) =>
            {
                moreLoading.Remove(c[1]);
                if (list == null || list.Count == 0)
                {
                    moreErrors[c[1]] = list == null ? error : "Nothing in the archive yet";
                    if (moreWanted != null && moreWanted[1] == c[1]) moreWanted = null;
                }
                else
                {
                    moreLists[c[1]] = list;
                    moreAt[c[1]] = Dates.NowMs();
                    WarmMore(list);
                    if (moreWanted != null && moreWanted[1] == c[1] && options.IsOpen)
                    {
                        OpenMore(moreWanted);
                        return;
                    }
                }
                FillOptions();
            });
        }

        /// <summary>The pictures the section's first cards draw, and the first three openings.</summary>
        void WarmMore(List<Story> list)
        {
            int[] hero = front.HeroPixels;
            int tile = front.TilePixels;
            bool heroed = list.Count >= 3;
            List<string> urls = new List<string>();
            for (int k = 0; k < Math.Min(8, list.Count); k++)
            {
                if (heroed && (k == 1 || k == 2)) continue; // briefs have no picture
                urls.Add(list[k].ImageFor(heroed && k == 0 ? hero[0] : tile));
            }
            images.Warm(urls);
            for (int k = 0; k < Math.Min(3, list.Count); k++) PullHeadIfThere(list[k]);
        }

        void OpenMoreMenu()
        {
            if (moreChoices.Count == 0) return;
            moreRows = new List<Options.Tile>();
            foreach (string[] c in moreChoices)
            {
                string[] choice = c;
                moreRows.Add(new Options.Tile(null, c[0]) { OnOk = () => OpenMore(choice) });
            }
            FillMoreRows();
            options.OpenPopup("More stories like this", moreRows);
        }

        /// <summary>Each row: how many stories are in, or that they're on their way, or what went wrong.</summary>
        void FillMoreRows()
        {
            if (moreRows == null) return;
            for (int i = 0; i < moreRows.Count && i < moreChoices.Count; i++)
            {
                string[] c = moreChoices[i];
                Options.Tile t = moreRows[i];
                List<Story> list;
                bool have = moreLists.TryGetValue(c[1], out list);
                bool wanted = moreWanted != null && moreWanted[1] == c[1];
                t.Value = wanted ? "Loading…" : have ? list.Count + (list.Count == 1 ? " story" : " stories")
                    : moreLoading.Contains(c[1]) ? "…" : "";
                string err;
                t.Hint = !have && moreErrors.TryGetValue(c[1], out err) ? err : null;
            }
        }

        void OpenMore(string[] c)
        {
            List<Story> list;
            if (!moreLists.TryGetValue(c[1], out list))
            {
                // Not in yet (or it failed): ask now, and open it when it comes.
                moreWanted = c;
                moreAt.Remove(c[1]);
                PreloadMore(c);
                FillOptions();
                return;
            }
            moreWanted = null;
            ShowExtra(new Section("more", c[0], new List<Story>(list)), true);
            if (c[2] == null) return;
            // Ask News on the Mac for more of the channel or topic; show them when they're in.
            relay.RefreshTag(c[2], c[3], (job, e) =>
            {
                if (job == null) return;
                relay.Job(job, 40, (state, e2) => relay.Stories(c[1], (more, e3) =>
                {
                    if (more != null && more.Count > 0)
                    {
                        moreLists[c[1]] = more;
                        moreAt[c[1]] = Dates.NowMs();
                    }
                    if (more != null && extra != null && extra.Title == c[0] && more.Count > extra.Stories.Count)
                        ShowExtra(new Section("more", c[0], more), false);
                }));
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

        void ContinueReading(Story s)  // feature: opt.continue
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
            if (k == Key.F11 || (k == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Alt) != 0))  // feature: win.keys
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
            if (k == Key.Back || k == Key.BrowserBack || k == Key.Escape)
            {
                if (!e.IsRepeat) front.OnBack();  // one step per press
                e.Handled = true;
                return;
            }
            e.Handled = front.OnKey(k);
        }
    }
}
