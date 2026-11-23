using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace Broadsheet
{
    /// <summary>
    /// A ScrollViewer that scrolls like a browser: a precision touchpad moves the page 1:1 with the
    /// fingers; a notched wheel, a key press or a page jump glides to its destination (ease-out,
    /// about 200 ms), and further input while moving extends the glide instead of restarting it.
    /// Arrow keys step half a screen, as on the TV (Reader); Hold/Release give a browser-style
    /// held-arrow glide for whoever wants it. It hooks the frame clock only while moving, so a page at rest costs nothing.
    /// No scroll bars (the reader shows its own progress rule).
    /// </summary>
    sealed class Scroller : ScrollViewer
    {
        /// <summary>The offset changed (each frame while moving).</summary>
        public event Action Moved;

        /// <summary>Pixels per second while an arrow key is held (in the page's own units).</summary>
        const double HOLD_SPEED = 900;
        /// <summary>How quickly the offset closes on the target: ~90% of the way in 150 ms.</summary>
        const double EASE = 15;

        double pos, target;
        bool animating;
        int held; // -1 up, +1 down, 0 none
        double holdSpeed;
        TimeSpan last;

        public Scroller()
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Hidden;
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled;
            Focusable = false;
            CanContentScroll = false;
            PanningMode = PanningMode.VerticalOnly;
            ScrollChanged += (s, e) => { if (Moved != null) Moved(); };
        }

        public double Max { get { return Math.Max(0, ExtentHeight - ViewportHeight); } }

        /// <summary>Where the page is going (or is, at rest).</summary>
        public double Target { get { return animating ? target : VerticalOffset; } }

        public void ScrollBy(double dy)
        {
            ScrollTo(Target + dy, true);
        }

        public void ScrollTo(double y, bool animate)
        {
            y = Math.Max(0, Math.Min(Max, y));
            if (!animate)
            {
                Stop();
                ScrollToVerticalOffset(y);
                return;
            }
            target = y;
            Start();
        }

        /// <summary>An arrow key went down: a short step at once, then a steady glide while it stays down.</summary>
        public void Hold(int direction)
        {
            if (held == direction) return; // key repeat: already gliding
            held = direction;
            holdSpeed = 0;
            target = Math.Max(0, Math.Min(Max, Target + direction * 64));
            Start();
        }

        /// <summary>The arrow key came up: coast a little and ease to a stop.</summary>
        public void Release()
        {
            if (held == 0) return;
            target = Math.Max(0, Math.Min(Max, target + held * holdSpeed * 0.08));
            held = 0;
            holdSpeed = 0;
        }

        void Start()
        {
            if (animating) return;
            animating = true;
            pos = !double.IsNaN(direct) && Environment.TickCount - directAt < 200 ? direct : VerticalOffset;
            direct = double.NaN;
            last = TimeSpan.Zero;
            CompositionTarget.Rendering += Frame;
        }

        public void Stop()
        {
            held = 0;
            if (!animating) return;
            animating = false;
            CompositionTarget.Rendering -= Frame;
        }

        void Frame(object sender, EventArgs e)
        {
            TimeSpan now = ((RenderingEventArgs)e).RenderingTime;
            if (now == last) return; // the same frame twice
            double dt = last == TimeSpan.Zero ? 1 / 60.0 : Math.Min(0.05, (now - last).TotalSeconds);
            last = now;
            double max = Max;
            if (held != 0)
            {
                // Ramp up over a quarter second so a tap stays a tap, then move the target steadily.
                holdSpeed = Math.Min(HOLD_SPEED, holdSpeed + HOLD_SPEED * dt * 4);
                target += held * holdSpeed * dt;
            }
            target = Math.Max(0, Math.Min(max, target));
            // Our own idea of where the page is: VerticalOffset only catches up at the next layout,
            // and reading it back each frame makes the motion stutter.
            double k = 1 - Math.Exp(-dt * EASE);
            pos += (target - pos) * k;
            if (held == 0 && Math.Abs(target - pos) < 0.5) pos = target;
            ScrollToVerticalOffset(pos);
            if (held == 0 && pos == target) Stop();
        }

        double direct = double.NaN;
        int directAt;

        protected override void OnMouseWheel(MouseWheelEventArgs e)
        {
            e.Handled = true;
            // The page is laid out in TV units and scaled to the window: move by screen pixels.
            double dy = -e.Delta / Math.Max(0.5, Ui.Scale);
            if (e.Delta % 120 != 0)
            {
                // A precision touchpad (or a free-spinning wheel) sends many small deltas: follow the
                // fingers 1:1 with no easing, like a browser; any glide in progress is overtaken.
                // Several events can arrive between layouts, so add up from our own position.
                bool recent = !double.IsNaN(direct) && Environment.TickCount - directAt < 200;
                double from = animating ? pos : recent ? direct : VerticalOffset;
                Stop();
                direct = Math.Max(0, Math.Min(Max, from + dy * 1.0));
                directAt = Environment.TickCount;
                ScrollToVerticalOffset(direct);
                return;
            }
            // A notched wheel: each click glides 100 px, and quick clicks extend one smooth motion.
            direct = double.NaN;
            ScrollBy(dy * 100.0 / 120.0);
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            // Keys are the page's business (MainWindow routes them); don't let the viewer eat arrows.
        }
    }
}
