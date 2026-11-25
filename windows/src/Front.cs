using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace Broadsheet
{
    /// <summary>
    /// The front page: a rail of sections on the left (Today's sections from Apple News, then Latest
    /// and Saved Stories), and the chosen section as a hero, two briefs and rows of three tiles.
    /// Moving along the rail switches section after a short pause, so holding Down skims past.
    ///
    /// Focus is kept here, not in WPF's keyboard focus: one card or one rail item is lit, arrows move
    /// it by geometry (as a D-pad does on the TV), the mouse moves it by hovering.
    /// </summary>
    sealed class Front : Grid  // feature: front.rail
    {
        public interface IListener
        {
            void OnOpen(Section section, int index);
            /// <summary>Shown faded: read here, with "Stories you've read" set to dim.</summary>
            bool IsDimmed(string storyId);
            /// <summary>A card got focus (to fetch its story ahead if it stays there).</summary>
            void OnFocusStory(Story story, int index);
            /// <summary>Focus came to a section on the rail (direction: +1 down, -1 up, 0 first time).</summary>
            void OnRailFocus(int index, int direction);
            /// <summary>Focus (or the pointer) stayed on a section of the rail for a moment.</summary>
            void OnRailRest(int index);
            /// <summary>Focus reached the last rows of a section that has more to load.</summary>
            void OnNearEnd(Section section);
        }

        /// <summary>Resting on the rail this long counts as looking at the section.</summary>
        const int RAIL_REST_MS = 375;

        const double RAIL = 224, GAP = 20, SIDE = 34, TOP = 26;

        readonly Images images;
        readonly IListener listener;
        readonly StackPanel railList = new StackPanel();
        readonly Scroller railScroll = new Scroller();
        readonly TextBlock updateNotice, status, message, dateLine;
        readonly Scroller page = new Scroller();
        readonly Canvas grid = new Canvas();
        readonly List<RailItem> railItems = new List<RailItem>();
        readonly List<Card> cards = new List<Card>();
        TextBlock title, sub;
        List<Section> sections = new List<Section>();
        int current = -1;
        /// <summary>The lit rail item, or -1 when a card (or nothing) is lit.</summary>
        int railFocus = -1;
        Card cardFocus;
        readonly DispatcherTimer switchSection, railRest;
        int restAt = -1, railAt = -1;

        public Front(Images images, IListener listener)
        {
            this.images = images;
            this.listener = listener;
            Background = Ui.Brush(Ui.PAPER);
            ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(RAIL) });
            ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            DockPanel rail = new DockPanel { Background = Ui.Brush(Ui.RAIL), LastChildFill = true };
            rail.Margin = new Thickness(0);
            StackPanel head = new StackPanel { Margin = new Thickness(26, 30, 14, 0) };
            head.Children.Add(new TextBlock { Text = "Broadsheet", FontFamily = Ui.Display, FontSize = 31, Foreground = Ui.Brush(Ui.INK) });
            head.Children.Add(new Border { Width = 44, Height = 3, Background = Ui.Brush(Ui.ACCENT), HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 12, 0, 0) });
            dateLine = Text(13, Ui.MUTED, Ui.SansMedium);
            dateLine.Margin = new Thickness(0, 10, 0, 22);
            head.Children.Add(dateLine);
            DockPanel.SetDock(head, Dock.Top);
            rail.Children.Add(head);
            status = Text(11.5, Ui.FAINT, Ui.Sans);
            status.LineHeight = 11.5 * 1.45;
            status.TextWrapping = TextWrapping.Wrap;
            status.Margin = new Thickness(26, 12, 14, 22);
            DockPanel.SetDock(status, Dock.Bottom);
            rail.Children.Add(status);
            updateNotice = Text(11.5, Ui.ACCENT, Ui.SansMedium);
            updateNotice.LineHeight = 11.5 * 1.35;
            updateNotice.TextWrapping = TextWrapping.Wrap;
            updateNotice.Margin = new Thickness(26, 8, 14, 0);
            updateNotice.Visibility = Visibility.Collapsed;
            DockPanel.SetDock(updateNotice, Dock.Bottom);
            rail.Children.Add(updateNotice);
            railScroll.Content = railList;
            railScroll.Margin = new Thickness(26 - 14, 0, 14, 0);
            // Today can have twenty-odd sections: the rail scrolls with focus, its ends fading out.
            railScroll.OpacityMask = Fades();
            rail.Children.Add(railScroll);
            SetColumn(rail, 0);
            Children.Add(rail);

            grid.Margin = new Thickness(0);
            page.Content = grid;
            page.Moved += Viewport;
            page.SizeChanged += (s, e) => Arrange_();
            SetColumn(page, 1);
            Children.Add(page);

            message = Text(20, Ui.MUTED, Ui.TextFace);
            message.TextAlignment = TextAlignment.Center;
            message.TextWrapping = TextWrapping.Wrap;
            message.LineHeight = 20 * 1.45;
            message.Margin = new Thickness(60, 0, 60, 0);
            message.VerticalAlignment = VerticalAlignment.Center;
            message.Visibility = Visibility.Collapsed;
            SetColumn(message, 1);
            Children.Add(message);

            switchSection = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(260) };
            switchSection.Tick += (s, e) =>
            {
                switchSection.Stop();
                if (railFocus >= 0) Show(railFocus, false);
            };
            railRest = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(RAIL_REST_MS) };
            railRest.Tick += (s, e) =>
            {
                railRest.Stop();
                if (restAt >= 0 && restAt < sections.Count) listener.OnRailRest(restAt);
            };
            UpdateDate();
        }

        Point lastMouse = new Point(double.NaN, 0);

        /// <summary>The pointer really moved (not a card rebuilt under a resting pointer, which WPF also reports).</summary>
        bool Moved(MouseEventArgs e)
        {
            Point p = e.GetPosition(this);
            bool moved = !double.IsNaN(lastMouse.X) && (Math.Abs(p.X - lastMouse.X) > 2 || Math.Abs(p.Y - lastMouse.Y) > 2);
            lastMouse = p;
            return moved;
        }

        static LinearGradientBrush Fades()
        {
            LinearGradientBrush b = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(0, 1) };
            b.GradientStops.Add(new GradientStop(Colors.Transparent, 0));
            b.GradientStops.Add(new GradientStop(Colors.Black, 0.04));
            b.GradientStops.Add(new GradientStop(Colors.Black, 0.96));
            b.GradientStops.Add(new GradientStop(Colors.Transparent, 1));
            return b;
        }

        static TextBlock Text(double size, Color color, Typeface face)
        {
            return new TextBlock
            {
                FontSize = size, Foreground = Ui.Brush(color), FontFamily = face.FontFamily, FontWeight = face.Weight, FontStyle = face.Style
            };
        }

        public void UpdateDate()
        {
            dateLine.Text = DateTime.Now.ToString("dddd, MMMM d", CultureInfo.CurrentCulture);
        }

        public bool HasContent { get { return sections.Count > 0; } }

        public string StatusText
        {
            get { return status.Text; }
            set { if (status.Text != value) status.Text = value; } // nothing to redraw when it's the same
        }

        public string UpdateNotice  // feature: opt.update
        {
            get { return updateNotice.Text; }
            set
            {
                string next = value ?? "";
                if (updateNotice.Text != next) updateNotice.Text = next;
                updateNotice.Visibility = string.IsNullOrEmpty(next) ? Visibility.Collapsed : Visibility.Visible;
            }
        }

        /// <summary>Shown instead of the grid when there is nothing to show.</summary>
        public void ShowMessage(string text)
        {
            if (sections.Count > 0)
            {
                StatusText = text;
                return;
            }
            message.Text = text;
            message.Visibility = Visibility.Visible;
            page.Visibility = Visibility.Collapsed;
        }

        /// <summary>New sections. Keeps the current section (by id) and, if asked, the focused story.</summary>
        public void SetSections(List<Section> list, bool keepFocus)
        {
            string currentId = current >= 0 && current < sections.Count ? sections[current].Id : null;
            // Moving down the rail when a new page comes: stay on the section focus was on, not the one
            // still showing, so no key press is lost.
            if (railFocus >= 0 && railFocus < sections.Count) currentId = sections[railFocus].Id;
            Story focused = FocusedStory;
            restoreIndex = cardFocus != null ? cardFocus.Index : -1;
            Restore(list, currentId, keepFocus && focused != null ? focused.Id : null, RailFocused, keepFocus);
        }

        /// <summary>The focused card's place before a rebuild, for when its story isn't there any more.</summary>
        int restoreIndex = -1;

        public Story FocusedStory { get { return cardFocus == null ? null : cardFocus.Story; } }

        public bool RailFocused { get { return railFocus >= 0; } }

        public Section CurrentSection { get { return current >= 0 && current < sections.Count ? sections[current] : null; } }

        /// <summary>Opens a section by id and focuses its first story.</summary>
        public void Open(string sectionId)
        {
            for (int i = 0; i < sections.Count; i++)
            {
                if (sections[i].Id != sectionId) continue;
                current = -1;
                Show(i, true);
                return;
            }
        }

        /// <summary>Fades or restores cards after a story was read or the setting changed.</summary>
        public void RefreshDim()
        {
            foreach (Card c in cards) c.SetDim(listener.IsDimmed(c.Story.Id));
        }

        /// <summary>Sections with a given section and story chosen: after the views were built again.</summary>
        public void Restore(List<Section> list, string currentId, string focusedStory, bool railFocused, bool keepFocus)
        {
            sections = list;
            message.Visibility = Visibility.Collapsed;
            page.Visibility = Visibility.Visible;
            railList.Children.Clear();
            railItems.Clear();
            int keep = 0;
            for (int i = 0; i < list.Count; i++)
            {
                RailItem item = new RailItem(this, i, list[i].Title);
                railItems.Add(item);
                railList.Children.Add(item);
                if (list[i].Id == currentId) keep = i;
            }
            current = -1;
            railFocus = -1;
            cardFocus = null;
            if (list.Count == 0) return;
            Show(keep, false);
            int at = restoreIndex;
            restoreIndex = -1;
            if (keepFocus && focusedStory != null)
            {
                foreach (Card c in cards)
                {
                    if (c.Story.Id == focusedStory)
                    {
                        FocusCard(c, true);
                        return;
                    }
                }
                // The story went (the page changed under it): the card in its place instead.
                if (at >= 0 && cards.Count > 0)
                {
                    FocusCard(cards[Math.Min(at, cards.Count - 1)], true);
                    return;
                }
            }
            FocusRail(keep, false);
        }

        /// <summary>Builds the grid for one section.</summary>
        public void Show(int index, bool focusFirst)
        {
            if (index < 0 || index >= sections.Count) return;
            if (index == current)
            {
                if (focusFirst && cards.Count > 0) FocusCard(cards[0], true);
                return;
            }
            current = index;
            foreach (RailItem r in railItems) r.SetSelected(r.Index == index);
            foreach (Card c in cards) images.Cancel(c);
            if (cardFocus != null) cardFocus = null;
            cards.Clear();
            grid.Children.Clear();
            Section s = sections[index];
            long now = Dates.NowMs();

            title = Text(30, Ui.INK, Ui.DisplayFace);
            title.Text = s.Title;
            grid.Children.Add(title);
            sub = Text(12.5, Ui.FAINT, Ui.SansMedium);
            sub.Text = SubText(s);
            grid.Children.Add(sub);

            List<Story> stories = s.Stories;
            for (int i = 0; i < stories.Count; i++)
            {
                int style = stories.Count >= 3 && i == 0 ? Card.HERO : stories.Count >= 3 && i < 3 ? Card.BRIEF : Card.TILE;
                AddCard(style, stories[i], i, now);
            }
            Arrange_();
            page.ScrollTo(0, false);
            Dispatcher.BeginInvoke(new Action(Viewport), DispatcherPriority.Loaded);
            if (focusFirst && cards.Count > 0) FocusCard(cards[0], true);
        }

        void AddCard(int style, Story story, int i, long now)
        {
            Card card = new Card(style, story, i, now, images);
            card.SetDim(listener.IsDimmed(story.Id));
            Card c = card;
            card.MouseMove += (o, e) => { if (Moved(e)) FocusCard(c, false); };
            card.MouseLeftButtonUp += (o, e) =>
            {
                FocusCard(c, false);
                listener.OnOpen(sections[current], c.Index);
                e.Handled = true;
            };
            cards.Add(card);
            grid.Children.Add(card);
        }

        /// <summary>More stories arrived for a section (its next page): their cards go under the others,
        /// if it's the one on screen. Focus and scroll stay where they are.</summary>
        public void Append(Section s)
        {
            if (current < 0 || current >= sections.Count || sections[current] != s || cards.Count >= s.Stories.Count) return;
            long now = Dates.NowMs();
            for (int i = cards.Count; i < s.Stories.Count; i++) AddCard(Card.TILE, s.Stories[i], i, now);
            if (sub != null) sub.Text = SubText(s);
            Arrange_();
        }

        static string SubText(Section s)
        {
            int n = Math.Max(s.Total, s.Stories.Count);
            string count = n + (n == 1 ? " story" : " stories");
            if (s.Id == "saved") return count + "  ·  saved in Apple News";
            if (s.Id == "latest") return count + "  ·  newest in the archive";
            if (s.Id == "more") return count + "  ·  from the archive on the Mac";
            if (s.Today > 0 && n > s.Today) return count + "  ·  " + s.Today + " from Apple News Today, then more from the Mac";
            return count + "  ·  from Apple News Today";
        }

        /// <summary>A tile's width in device pixels (the size its picture is fetched at).</summary>
        public int TilePixels
        {
            get
            {
                double width = Math.Max(300, page.ActualWidth - SIDE * 2);
                return (int)Math.Ceiling(Math.Floor((width - GAP * 2) / 3) * Ui.Scale * Ui.PixelsPerDip);
            }
        }

        /// <summary>The hero card's size in device pixels, so its picture can be decoded before the section shows.</summary>
        public int[] HeroPixels
        {
            get
            {
                double width = Math.Max(300, page.ActualWidth - SIDE * 2);
                double tile = Math.Floor((width - GAP * 2) / 3);
                double px = Ui.Scale * Ui.PixelsPerDip;
                return new[] { (int)Math.Ceiling((tile * 2 + GAP) * px), (int)Math.Ceiling(Math.Max(300, Math.Round(tile * 0.62)) * px) };
            }
        }

        /// <summary>Places the cards for the page's width: a hero and two briefs, then rows of three.</summary>
        void Arrange_()
        {
            if (title == null) return;
            double width = Math.Max(300, page.ActualWidth - SIDE * 2);
            double tile = Math.Floor((width - GAP * 2) / 3);
            double tileH = Math.Round(tile * 9 / 16 + 96);
            Canvas.SetLeft(title, SIDE);
            Canvas.SetTop(title, TOP);
            Canvas.SetLeft(sub, SIDE);
            Canvas.SetTop(sub, TOP + 30 * 1.33 + 6);
            double y = TOP + 30 * 1.33 + 6 + 12.5 * 1.33 + 18;
            int i = 0;
            if (cards.Count >= 3)
            {
                double heroH = Math.Max(300, Math.Round(tile * 0.62));
                double briefH = (heroH - GAP) / 2;
                Place(cards[0], SIDE, y, tile * 2 + GAP, heroH);
                Place(cards[1], SIDE + tile * 2 + GAP * 2, y, tile, briefH);
                Place(cards[2], SIDE + tile * 2 + GAP * 2, y + briefH + GAP, tile, briefH);
                y += heroH + GAP;
                i = 3;
            }
            for (; i < cards.Count; i += 3)
            {
                for (int j = i; j < Math.Min(i + 3, cards.Count); j++) Place(cards[j], SIDE + (j - i) * (tile + GAP), y, tile, tileH);
                y += tileH + GAP;
            }
            grid.Height = y - GAP + 40;
            grid.Width = page.ActualWidth;
            Viewport();
        }

        static void Place(Card c, double x, double y, double w, double h)
        {
            Canvas.SetLeft(c, x);
            Canvas.SetTop(c, y);
            c.Width = w;
            c.Height = h;
        }

        static Rect Bounds(Card c)
        {
            return new Rect(Canvas.GetLeft(c), Canvas.GetTop(c), c.Width, c.Height);
        }

        /// <summary>Pictures for cards within a screen of the viewport; the rest let go.</summary>
        void Viewport()
        {
            double top = page.VerticalOffset, bottom = top + page.ViewportHeight, margin = page.ViewportHeight;
            foreach (Card c in cards)
            {
                Rect r = Bounds(c);
                if (double.IsNaN(r.Y)) continue;
                c.SetNear(r.Bottom > top - margin && r.Y < bottom + margin);
            }
        }

        // ------------------------------------------------------------------ focus

        void FocusRail(int index, bool switchLater)
        {
            if (cardFocus != null) cardFocus.SetFocused(false);
            cardFocus = null;
            int was = railFocus;
            railFocus = index;
            for (int i = 0; i < railItems.Count; i++) railItems[i].SetLit(i == index);
            if (index >= 0 && index != was)  // feature: front.warm
            {
                listener.OnRailFocus(index, railAt < 0 ? 0 : Math.Sign(index - railAt));
                railAt = index;
                RestOn(index);
            }
            if (index >= 0 && index < railItems.Count) Reveal(railScroll, railItems[index], 24);
            if (switchLater && index != current && index != was)
            {
                switchSection.Stop();
                switchSection.Start();
            }
        }

        void FocusCard(Card c, bool scroll)
        {
            if (railFocus >= 0)
            {
                foreach (RailItem ri in railItems) ri.SetLit(false);
                railFocus = -1;
            }
            if (cardFocus != c)
            {
                if (cardFocus != null) cardFocus.SetFocused(false);
                cardFocus = c;
                c.SetFocused(true);
                listener.OnFocusStory(c.Story, c.Index);
                Section sec = CurrentSection;
                if (sec != null && sec.Next != null && Ladder.WantMore(c.Index, sec.Stories.Count)) listener.OnNearEnd(sec);
            }
            if (!scroll) return;
            Rect r = Bounds(c);
            // The first row shows the section's name above it; others scroll just into view.
            if (r.Y < 200) page.ScrollTo(0, true);
            else if (r.Y - 28 < page.Target) page.ScrollTo(r.Y - 28, true);
            else if (r.Bottom + 28 > page.Target + page.ViewportHeight) page.ScrollTo(r.Bottom + 28 - page.ViewportHeight, true);
        }

        static void Reveal(Scroller s, FrameworkElement e, double pad)
        {
            Point p = e.TranslatePoint(new Point(0, 0), (UIElement)s.Content);
            double top = p.Y - pad, bottom = p.Y + e.ActualHeight + pad;
            if (top < s.Target) s.ScrollTo(top, true);
            else if (bottom > s.Target + s.ViewportHeight) s.ScrollTo(bottom - s.ViewportHeight, true);
        }

        /// <summary>Back from the reader: focus the story that was open (it may have moved with Left/Right).</summary>
        public void FocusStory(int index)
        {
            foreach (Card c in cards)
            {
                if (c.Index == index)
                {
                    FocusCard(c, true);
                    return;
                }
            }
        }

        /// <summary>Arrow keys, Enter, Page Up/Down. True if used.</summary>
        public bool OnKey(Key k)
        {
            if (sections.Count == 0) return false;
            if (railFocus < 0 && cardFocus == null)
            {
                if (k == Key.Up || k == Key.Down || k == Key.Left || k == Key.Right)
                {
                    FocusRail(Math.Max(0, current), false);
                    return true;
                }
                return false;
            }
            if (railFocus >= 0)
            {
                switch (k)
                {
                    case Key.Up:
                        if (railFocus > 0) FocusRail(railFocus - 1, true);
                        return true;
                    case Key.Down:
                        if (railFocus < railItems.Count - 1) FocusRail(railFocus + 1, true);
                        return true;
                    case Key.Home:
                        FocusRail(0, true);
                        return true;
                    case Key.End:
                        FocusRail(railItems.Count - 1, true);
                        return true;
                    case Key.Right:
                    case Key.Enter:
                        switchSection.Stop();
                        int ri = railFocus;
                        if (ri != current) Show(ri, false);
                        if (cards.Count > 0) FocusCard(cards[0], true);
                        return true;
                }
                return false;
            }
            switch (k)
            {
                case Key.Enter:
                    listener.OnOpen(sections[current], cardFocus.Index);
                    return true;
                case Key.Left:
                case Key.Right:
                case Key.Up:
                case Key.Down:
                    Card next = Nearest(cardFocus, k);
                    if (next != null) FocusCard(next, true);
                    else if (k == Key.Left) FocusRail(current, false);
                    else if (k == Key.Up) page.ScrollTo(0, true);
                    return true;
                case Key.PageDown:
                case Key.PageUp:
                    {
                        // A screen on, and the card nearest the same place on the new screen.
                        double d = (k == Key.PageDown ? 1 : -1) * page.ViewportHeight * 0.9;
                        Rect from = Bounds(cardFocus);
                        Point aim = new Point(from.X + from.Width / 2, from.Y + from.Height / 2 + d);
                        Card best = null;
                        double bestD = double.MaxValue;
                        foreach (Card c in cards)
                        {
                            Rect r = Bounds(c);
                            double dd = Math.Abs(r.Y + r.Height / 2 - aim.Y) * 2 + Math.Abs(r.X + r.Width / 2 - aim.X);
                            if (dd < bestD) { bestD = dd; best = c; }
                        }
                        if (best != null) FocusCard(best, true);
                        return true;
                    }
                case Key.Home:
                    if (cards.Count > 0) FocusCard(cards[0], true);
                    return true;
                case Key.End:
                    if (cards.Count > 0) FocusCard(cards[cards.Count - 1], true);
                    return true;
            }
            return false;
        }

        /// <summary>Back (Backspace, Esc, the mouse's back button) walks out of the front page a step at
        /// a time: from a story to its section on the rail, then to the rail's top entry. The window
        /// stays open at the top. True if it took a step.</summary>
        public bool OnBack()  // feature: front.back
        {
            if (sections.Count == 0 || railItems.Count == 0) return false;
            if (railFocus == 0) return false;
            if (railFocus > 0)
            {
                FocusRail(0, true);
                return true;
            }
            FocusRail(current >= 0 && current < railItems.Count ? current : 0, false);
            page.ScrollTo(0, true);
            return true;
        }

        /// <summary>The card a D-pad press would move to: nearest in that direction, favouring the same row or column.</summary>
        Card Nearest(Card from, Key k)
        {
            Rect a = Bounds(from);
            Card best = null;
            double bestScore = double.MaxValue;
            foreach (Card c in cards)
            {
                if (c == from) continue;
                Rect b = Bounds(c);
                double major, minor;
                switch (k)
                {
                    case Key.Left:
                        if (b.Right > a.Left + 1) continue;
                        major = a.Left - b.Right;
                        minor = Overlap(a.Top, a.Bottom, b.Top, b.Bottom);
                        break;
                    case Key.Right:
                        if (b.Left < a.Right - 1) continue;
                        major = b.Left - a.Right;
                        minor = Overlap(a.Top, a.Bottom, b.Top, b.Bottom);
                        break;
                    case Key.Up:
                        if (b.Bottom > a.Top + 1) continue;
                        major = a.Top - b.Bottom;
                        minor = Overlap(a.Left, a.Right, b.Left, b.Right);
                        break;
                    default:
                        if (b.Top < a.Bottom - 1) continue;
                        major = b.Top - a.Bottom;
                        minor = Overlap(a.Left, a.Right, b.Left, b.Right);
                        break;
                }
                double score = major * 13 + minor * minor;
                if (score < bestScore)
                {
                    bestScore = score;
                    best = c;
                }
            }
            return best;
        }

        /// <summary>0 when two spans overlap, else the gap between them.</summary>
        static double Overlap(double a0, double a1, double b0, double b1)
        {
            if (b1 < a0) return a0 - b1;
            if (b0 > a1) return b0 - a1;
            // overlapping: prefer the one most aligned with our start (reading order)
            return Math.Abs(b0 - a0) * 0.05;
        }

        /// <summary>Looking at a section on the rail, by key or by pointer: say so if it lasts a moment.</summary>
        void RestOn(int index)
        {
            restAt = index;
            railRest.Stop();
            railRest.Start();
        }

        void RailClicked(int index)
        {
            switchSection.Stop();
            FocusRail(index, false);
            Show(index, false);
        }

        /// <summary>A section name on the rail: lit while focused, an accent bar while chosen.</summary>
        sealed class RailItem : FrameworkElement
        {
            public readonly int Index;
            readonly Front front;
            readonly string text;
            bool selected, lit, hover;

            public RailItem(Front front, int index, string text)
            {
                this.front = front;
                Index = index;
                this.text = text;
                Height = 38;
                Cursor = Cursors.Hand;
            }

            public void SetSelected(bool v) { selected = v; InvalidateVisual(); }

            public void SetLit(bool v) { lit = v; InvalidateVisual(); }

            protected override void OnMouseEnter(MouseEventArgs e) { hover = true; InvalidateVisual(); front.RestOn(Index); }

            protected override void OnMouseLeave(MouseEventArgs e) { hover = false; InvalidateVisual(); }

            protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)  // feature: win.mouse
            {
                front.RailClicked(Index);
                e.Handled = true;
            }

            protected override void OnRender(DrawingContext dc)
            {
                double w = ActualWidth, h = ActualHeight;
                dc.DrawRectangle(lit ? Ui.Brush(Ui.SURFACE_HI) : hover ? Ui.Brush(Ui.Alpha(Ui.SURFACE_HI, 0.55)) : Brushes.Transparent, null, new Rect(0, 0, w, h));
                if (selected) dc.DrawRectangle(Ui.Brush(Ui.ACCENT), null, new Rect(0, 9, 3, h - 18));
                string t = Ui.Ellipsize(text, Ui.SansMedium, 16.5, w - 14 - 8);
                FormattedText f = Ui.Format(t, Ui.SansMedium, 16.5, lit || selected ? Ui.INK : Ui.MUTED);
                dc.DrawText(f, new Point(14, (h - f.Height) / 2));
            }
        }
    }
}
