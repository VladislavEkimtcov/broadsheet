using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace Broadsheet
{
    /// <summary>The few icons with no plain glyph, as paths on a 24-unit grid: thumbs and a laptop.</summary>
    static class Icons
    {
        public const int THUMB_UP = 1, THUMB_DOWN = 2, LAPTOP = 3, BACK = 4, MENU = 5;

        static readonly Geometry THUMB = Geometry.Parse(
            "M2,10 L6,10 L6,21 L2,21 Z M8,10 L11.6,3.2 Q12.3,2 13.6,2.4 Q15,3 14.7,4.6 L13.9,9 L19.5,9 Q22,9 21.6,11.6 L20.2,19.2 Q19.8,21 18,21 L8,21 Z");

        public static void Draw(DrawingContext dc, int which, double x, double y, double size, Color color)
        {
            double u = size / 24;
            dc.PushTransform(new TranslateTransform(x, y));
            dc.PushTransform(new ScaleTransform(u, u));
            if (which == THUMB_UP || which == THUMB_DOWN)
            {
                if (which == THUMB_DOWN) dc.PushTransform(new RotateTransform(180, 12, 12));
                dc.DrawGeometry(Ui.Brush(color), null, THUMB);
                if (which == THUMB_DOWN) dc.Pop();
            }
            else if (which == BACK)
            {
                // A plain arrow pointing left, square ends to match the blocky style.
                Pen pen = new Pen(Ui.Brush(color), 2.2) { StartLineCap = PenLineCap.Flat, EndLineCap = PenLineCap.Flat, LineJoin = PenLineJoin.Miter };
                dc.DrawLine(pen, new Point(4, 12), new Point(21, 12));
                StreamGeometry g = new StreamGeometry();
                using (StreamGeometryContext c = g.Open())
                {
                    c.BeginFigure(new Point(11.5, 4.5), false, false);
                    c.LineTo(new Point(4, 12), true, false);
                    c.LineTo(new Point(11.5, 19.5), true, false);
                }
                dc.DrawGeometry(null, pen, g);
            }
            else if (which == MENU)
            {
                Brush b = Ui.Brush(color);
                dc.DrawRectangle(b, null, new Rect(3, 5, 18, 2.2));
                dc.DrawRectangle(b, null, new Rect(3, 11, 18, 2.2));
                dc.DrawRectangle(b, null, new Rect(3, 17, 18, 2.2));
            }
            else if (which == LAPTOP)
            {
                dc.DrawRectangle(null, new Pen(Ui.Brush(color), 2), new Rect(4.5, 5, 15, 10.5));
                dc.DrawLine(new Pen(Ui.Brush(color), 2.6), new Point(1.5, 19), new Point(22.5, 19));
            }
            dc.Pop();
            dc.Pop();
        }
    }

    /// <summary>
    /// A square, icon-only button for the mouse (Windows only; the TV is driven by its remote): a
    /// tinted square behind the icon so it reads over pictures, lit on hover, the accent on press.
    /// </summary>
    sealed class IconButton : FrameworkElement
    {
        readonly int icon;
        readonly Action click;
        bool hover, down;

        public IconButton(int icon, string tip, Action click)
        {
            this.icon = icon;
            this.click = click;
            Width = 44;
            Height = 44;
            ToolTip = tip;
            Cursor = System.Windows.Input.Cursors.Hand;
        }

        protected override void OnMouseEnter(System.Windows.Input.MouseEventArgs e) { hover = true; InvalidateVisual(); }

        protected override void OnMouseLeave(System.Windows.Input.MouseEventArgs e) { hover = down = false; InvalidateVisual(); }

        protected override void OnMouseLeftButtonDown(System.Windows.Input.MouseButtonEventArgs e)
        {
            down = true;
            CaptureMouse();
            InvalidateVisual();
            e.Handled = true;
        }

        protected override void OnMouseLeftButtonUp(System.Windows.Input.MouseButtonEventArgs e)
        {
            bool was = down;
            down = false;
            ReleaseMouseCapture();
            InvalidateVisual();
            e.Handled = true;
            if (was && hover) click();
        }

        protected override void OnRender(DrawingContext dc)
        {
            Color bg = down ? Ui.ACCENT : hover ? Ui.SURFACE_HI : Ui.Alpha(Ui.PAPER, 0.86);
            dc.DrawRectangle(Ui.Brush(bg), null, new Rect(0, 0, ActualWidth, ActualHeight));
            Icons.Draw(dc, icon, (ActualWidth - 24) / 2, (ActualHeight - 24) / 2, 24, down ? Colors.White : Ui.INK);
        }
    }

    /// <summary>
    /// The options panel: slides in from the right with Menu (M, the Menu key or a right-click),
    /// leaves with Menu or Esc. A column of tiles, modelled on dumbtv's quick settings: Up/Down picks
    /// a tile, Left/Right changes its value, Enter does it; the mouse clicks the same things. The
    /// panel keeps the selection itself, so focus on the page behind stays where it was.
    /// </summary>
    sealed class Options : Grid  // feature: opt.panel
    {
        public interface IListener
        {
            void OnOptionsClosed();
        }

        /// <summary>One row. MainWindow fills it in and calls Refresh when something changes.</summary>
        public sealed class Tile
        {
            public string Icon, Label, Value, Hint;
            public int VectorIcon;
            public int[] SegmentIcons;
            /// <summary>A slider of this many steps under the label, at Position.</summary>
            public int Slider, Position;
            public bool Enabled = true;
            /// <summary>Two or more choices drawn as pills under the label (Like / Dislike).</summary>
            public string[] Segments;
            public bool[] Checked;
            public int Segment;
            public Action<int> OnStep;
            public Action OnOk;

            public Tile(string icon, string label)
            {
                Icon = icon;
                Label = label;
            }
        }

        const double WIDTH = 410;

        readonly IListener listener;
        readonly Border scrim, panel;
        readonly TranslateTransform slide = new TranslateTransform();
        readonly StackPanel column = new StackPanel();
        readonly Scroller scroll = new Scroller();
        readonly TextBlock context, footer;
        readonly List<Tile> tiles = new List<Tile>();
        readonly List<TileView> views = new List<TileView>();
        int selected;
        bool open, touched;

        public Options(IListener listener)
        {
            this.listener = listener;
            Visibility = Visibility.Collapsed;
            scrim = new Border { Background = Ui.Brush(Ui.Light ? Ui.Argb(0x4D000000) : Ui.Argb(0x73000000)) };
            scrim.MouseLeftButtonUp += (s, e) => { Close(); e.Handled = true; };
            Children.Add(scrim);

            DockPanel inner = new DockPanel();
            TextBlock heading = new TextBlock { Text = "Options", FontFamily = Ui.Display, FontSize = 26, Foreground = Ui.Brush(Ui.INK) };
            DockPanel.SetDock(heading, Dock.Top);
            inner.Children.Add(heading);
            context = new TextBlock
            {
                FontFamily = Ui.Sans.FontFamily, FontSize = 13, Foreground = Ui.Brush(Ui.FAINT), TextWrapping = TextWrapping.Wrap,
                TextTrimming = TextTrimming.CharacterEllipsis, MaxHeight = 13 * 1.36 * 2, Margin = new Thickness(0, 6, 0, 14)
            };
            DockPanel.SetDock(context, Dock.Top);
            inner.Children.Add(context);
            footer = new TextBlock
            {
                FontFamily = Ui.Sans.FontFamily, FontSize = 12, Foreground = Ui.Brush(Ui.FAINT), TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 12, 0, 0)
            };
            DockPanel.SetDock(footer, Dock.Bottom);
            inner.Children.Add(footer);
            scroll.Content = column;
            inner.Children.Add(scroll);

            panel = new Border
            {
                Width = WIDTH, HorizontalAlignment = HorizontalAlignment.Right, Background = Ui.Brush(Ui.RAIL),
                BorderBrush = Ui.Brush(Ui.RULE), BorderThickness = new Thickness(1, 0, 0, 0),
                Padding = new Thickness(22, 26, 22, 18), Child = inner, RenderTransform = slide
            };
            panel.MouseLeftButtonUp += (s, e) => e.Handled = true;
            Children.Add(panel);
        }

        public bool IsOpen { get { return open; } }

        public int SelectedIndex { get { return selected; } }

        /// <summary>Opens with these tiles; selects the first that can do something. animate: slide in.</summary>
        public void Open(List<Tile> list, string about, bool animate)
        {
            tiles.Clear();
            tiles.AddRange(list);
            views.Clear();
            column.Children.Clear();
            for (int i = 0; i < tiles.Count; i++) AddView(tiles[i], i);
            selected = 0;
            touched = false;
            while (selected < tiles.Count - 1 && !tiles[selected].Enabled) selected++;
            context.Text = about ?? "";
            context.Visibility = string.IsNullOrEmpty(about) ? Visibility.Collapsed : Visibility.Visible;
            footer.Text = "";
            Refresh();
            scroll.ScrollTo(0, false);
            if (!open)
            {
                open = true;
                Visibility = Visibility.Visible;
                Duration d = new Duration(TimeSpan.FromMilliseconds(170));
                if (animate)
                {
                    slide.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(WIDTH, 0, d) { EasingFunction = new CubicEase() });
                    scrim.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, d));
                }
                else
                {
                    slide.BeginAnimation(TranslateTransform.XProperty, null);
                    scrim.BeginAnimation(OpacityProperty, null);
                    slide.X = 0;
                    scrim.Opacity = 1;
                }
            }
            Reveal();
        }

        void AddView(Tile t, int at)
        {
            TileView v = new TileView(this, t);
            v.Margin = new Thickness(0, at > 0 ? 4 : 0, 0, 0);
            if (at >= views.Count)
            {
                views.Add(v);
                column.Children.Add(v);
            }
            else
            {
                views.Insert(at, v);
                column.Children.Insert(at, v);
            }
        }

        /// <summary>Reopens on the same tile (after the page behind was rebuilt).</summary>
        public void Select(int index)
        {
            selected = Math.Max(0, Math.Min(tiles.Count - 1, index));
            Refresh();
            Reveal();
        }

        /// <summary>A tile that arrived late (Handoff) goes on top; it takes the selection if nothing was pressed yet.</summary>
        public void AddTop(Tile t)
        {
            tiles.Insert(0, t);
            if (views.Count > 0) views[0].Margin = new Thickness(0, 4, 0, 0);
            AddView(t, 0);
            selected = touched ? selected + 1 : 0;
            Refresh();
            Reveal();
        }

        public void Close()
        {
            if (!open) return;
            open = false;
            Duration d = new Duration(TimeSpan.FromMilliseconds(150));
            slide.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(WIDTH, d));
            DoubleAnimation fade = new DoubleAnimation(0, d);
            fade.Completed += (s, e) => { if (!open) Visibility = Visibility.Collapsed; };
            scrim.BeginAnimation(OpacityProperty, fade);
            listener.OnOptionsClosed();
        }

        public void SetFooter(string text) { footer.Text = text; }  // feature: opt.status_footer

        /// <summary>Redraws the tiles after their state changed.</summary>
        public void Refresh()
        {
            for (int i = 0; i < views.Count; i++) views[i].Bind(i == selected);
        }

        void Reveal()
        {
            if (selected >= views.Count) return;
            TileView v = views[selected];
            Dispatcher.BeginInvoke(new Action(() =>
            {
                if (!v.IsLoaded) return;
                double y = v.TranslatePoint(new Point(0, 0), column).Y;
                double top = y - 24, bottom = y + v.ActualHeight + 24;
                if (top < scroll.Target) scroll.ScrollTo(top, true);
                else if (bottom > scroll.Target + scroll.ViewportHeight) scroll.ScrollTo(bottom - scroll.ViewportHeight, true);
            }), System.Windows.Threading.DispatcherPriority.Loaded);
        }

        /// <summary>Keys while open. Always true: the page behind doesn't see them.</summary>
        public bool OnKey(Key k, bool repeat)
        {
            touched = true;
            Tile t = selected < tiles.Count ? tiles[selected] : null;
            switch (k)
            {
                case Key.Apps:
                case Key.M:
                case Key.Escape:
                case Key.Back:
                case Key.BrowserBack:
                    if (!repeat) Close();
                    return true;
                case Key.Up:
                    if (selected > 0) selected--;
                    break;
                case Key.Down:
                    if (selected < tiles.Count - 1) selected++;
                    break;
                case Key.Home:
                    selected = 0;
                    break;
                case Key.End:
                    selected = tiles.Count - 1;
                    break;
                case Key.Left:
                case Key.Right:
                    Step(t, k == Key.Left ? -1 : 1);
                    break;
                case Key.Enter:
                case Key.Space:
                    if (!repeat) Ok(t);
                    break;
                default:
                    return true;
            }
            Refresh();
            Reveal();
            return true;
        }

        static void Step(Tile t, int d)
        {
            if (t == null || !t.Enabled) return;
            if (t.Segments != null) t.Segment = Math.Max(0, Math.Min(t.Segments.Length - 1, t.Segment + d));
            else if (t.OnStep != null) t.OnStep(d);
        }

        static void Ok(Tile t)
        {
            if (t != null && t.Enabled && t.OnOk != null) t.OnOk();
        }

        /// <summary>A click on a tile: selects it, then does what its spot means (a pill, a slider step, a side, or OK).</summary>
        void Clicked(TileView v, Point p, bool wheel, int wheelDir)
        {
            touched = true;
            int i = views.IndexOf(v);
            if (i < 0) return;
            selected = i;
            Tile t = tiles[i];
            if (wheel)
            {
                if (t.OnStep != null || t.Segments != null) Step(t, wheelDir);
            }
            else if (t.Segments != null && p.Y > 16 + 17 * 0.8 + 14)
            {
                int seg = Math.Max(0, Math.Min(t.Segments.Length - 1, (int)(p.X / (v.ActualWidth / t.Segments.Length))));
                t.Segment = seg;
                Refresh();
                Ok(t);
            }
            else if (t.Slider > 0 && t.OnStep != null)
            {
                // Jump the knob to where the track was clicked, a step at a time.
                double tl = 16 + 32 + 20, tr = v.ActualWidth - 16 - 30;
                int want = (int)Math.Round((p.X - tl) / Math.Max(1, (tr - tl) / Math.Max(1, t.Slider - 1)));
                want = Math.Max(0, Math.Min(t.Slider - 1, want));
                int guard = 20;
                while (t.Position != want && guard-- > 0)
                {
                    int before = t.Position;
                    t.OnStep(want > t.Position ? 1 : -1);
                    if (t.Position == before) break;
                }
            }
            else if (t.OnOk != null)
            {
                Ok(t);
            }
            else if (t.OnStep != null)
            {
                Step(t, p.X < v.ActualWidth / 2 ? -1 : 1);
            }
            Refresh();
        }

        /// <summary>A tile, drawn by hand: icon, label and value on top, a hint or the choices below.</summary>
        sealed class TileView : FrameworkElement
        {
            readonly Options owner;
            readonly Tile tile;
            bool on, hover;

            public TileView(Options owner, Tile tile)
            {
                this.owner = owner;
                this.tile = tile;
                Cursor = Cursors.Hand;
            }

            public void Bind(bool selected)
            {
                on = selected;
                InvalidateMeasure();
                InvalidateVisual();
            }

            protected override void OnMouseEnter(MouseEventArgs e) { hover = true; InvalidateVisual(); }

            protected override void OnMouseLeave(MouseEventArgs e) { hover = false; InvalidateVisual(); }

            protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
            {
                owner.Clicked(this, e.GetPosition(this), false, 0);
                e.Handled = true;
            }

            protected override void OnMouseWheel(MouseWheelEventArgs e)
            {
                if (tile.Slider > 0 || tile.Segments != null || (tile.OnStep != null && tile.OnOk == null))
                {
                    owner.Clicked(this, e.GetPosition(this), true, e.Delta > 0 ? 1 : -1);
                    e.Handled = true;
                }
            }

            protected override Size MeasureOverride(Size available)
            {
                bool hinted = !string.IsNullOrEmpty(tile.Hint);
                double h = tile.Slider > 0 ? 84 : tile.Segments != null ? (hinted ? 112 : 92) : (hinted ? 68 : 50);
                return new Size(0, h);
            }

            protected override void OnRender(DrawingContext dc)
            {
                double w = ActualWidth, h = ActualHeight;
                double a = tile.Enabled ? 1 : 0.42;
                Color fg = Ui.Alpha(Ui.INK, a);
                // Like the rail: flat rows, the selected one lit with the accent bar at its edge.
                if (on)
                {
                    dc.DrawRectangle(Ui.Brush(Ui.SURFACE_HI), null, new Rect(0, 0, w, h));
                    dc.DrawRectangle(Ui.Brush(Ui.ACCENT), null, new Rect(0, 0, 3, h));
                }
                else
                {
                    dc.DrawRectangle(Ui.Brush(Ui.Alpha(Ui.INK, hover ? 0.09 : 0.045)), null, new Rect(0, 0, w, h));
                }
                double pad = 16, x = pad, top = 16; // top of the first line
                if (tile.VectorIcon != 0)
                {
                    Icons.Draw(dc, tile.VectorIcon, x - 1, top - 2, 20, fg);
                    x += 32;
                }
                else if (tile.Icon != null)
                {
                    FormattedText ic = Ui.Format(tile.Icon, Ui.Sans, 19, fg);
                    dc.DrawText(ic, new Point(x, top - 4));
                    x += 32;
                }
                double right = w - pad;
                string v = tile.Value ?? "";
                FormattedText val = Ui.Format(v, Ui.SansMedium, 16, Ui.Alpha(on ? Ui.INK : Ui.MUTED, a));
                dc.DrawText(val, new Point(right - val.Width, top));
                string l = Ui.Ellipsize(tile.Label, Ui.SansMedium, 17, right - val.Width - 12 - x);
                dc.DrawText(Ui.Format(l, Ui.SansMedium, 17, fg), new Point(x, top - 1));
                Color hintColor = Ui.Alpha(Ui.MUTED, a);
                if (tile.Slider > 0)
                {
                    // A small A, the track with a tick per step and a square knob, a big A.
                    double cy = top + 12 + 30;
                    FormattedText small = Ui.Format("A", Ui.TextFace, 13, fg), big = Ui.Format("A", Ui.TextFace, 25, fg);
                    dc.DrawText(small, new Point(x, cy - small.Baseline * 0.72));
                    double bx = right - big.Width;
                    dc.DrawText(big, new Point(bx, cy - big.Baseline * 0.72));
                    double tl = x + small.Width + 14, tr = bx - 14;
                    dc.DrawRectangle(Ui.Brush(Ui.Alpha(Ui.INK, 0.18)), null, new Rect(tl, cy - 1.5, tr - tl, 3));
                    double step = (tr - tl) / Math.Max(1, tile.Slider - 1);
                    double kx = tl + step * tile.Position;
                    dc.DrawRectangle(Ui.Brush(on ? Ui.ACCENT : Ui.Alpha(Ui.INK, 0.7 * a)), null, new Rect(tl, cy - 1.5, kx - tl, 3));
                    for (int i = 0; i < tile.Slider; i++)
                        dc.DrawRectangle(Ui.Brush(Ui.Alpha(Ui.INK, i <= tile.Position ? 0.5 : 0.25)), null, new Rect(tl + step * i - 1, cy - 6, 2, 12));
                    dc.DrawRectangle(Ui.Brush(on ? Ui.ACCENT : fg), null, new Rect(kx - 7, cy - 9, 14, 18));
                }
                else if (tile.Segments != null)
                {
                    double y0 = top + 17 * 0.8 + 14 + 4, gap = 6, sh = 36;
                    double sw = (right - x - gap * (tile.Segments.Length - 1)) / tile.Segments.Length;
                    for (int i = 0; i < tile.Segments.Length; i++)
                    {
                        double sx = x + i * (sw + gap);
                        bool here = on && tile.Enabled && i == tile.Segment;
                        bool ticked = tile.Checked != null && tile.Checked[i];
                        dc.DrawRectangle(Ui.Brush(here ? Ui.ACCENT : Ui.Alpha(Ui.INK, ticked ? 0.16 : 0.07)), null, new Rect(sx, y0, sw, sh));
                        Color color = Ui.Alpha(here ? Colors.White : Ui.INK, a);
                        double iconW = tile.SegmentIcons != null ? 24 : 0;
                        string text = Ui.Ellipsize((ticked ? "✓ " : "") + tile.Segments[i], Ui.SansMedium, 14, sw - 16 - iconW);
                        FormattedText ft = Ui.Format(text, Ui.SansMedium, 14, color);
                        double tx = sx + (sw - ft.Width - iconW) / 2;
                        if (iconW > 0) Icons.Draw(dc, tile.SegmentIcons[i], tx, y0 + (sh - 17) / 2, 17, color);
                        dc.DrawText(ft, new Point(tx + iconW, y0 + (sh - ft.Height) / 2));
                    }
                    if (!string.IsNullOrEmpty(tile.Hint))
                    {
                        FormattedText ht = Ui.Format(Ui.Ellipsize(tile.Hint, Ui.Sans, 13, right - x), Ui.Sans, 13, hintColor);
                        dc.DrawText(ht, new Point(x, h - 12 - ht.Height + 3));
                    }
                }
                else if (!string.IsNullOrEmpty(tile.Hint))
                {
                    FormattedText ht = Ui.Format(Ui.Ellipsize(tile.Hint, Ui.Sans, 13, right - x), Ui.Sans, 13, hintColor);
                    dc.DrawText(ht, new Point(x, top + 26));
                }
            }
        }
    }
}
