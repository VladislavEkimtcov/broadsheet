using System;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace Broadsheet
{
    /// <summary>
    /// A story's video, full window. Windows' own player decodes it (HLS or MP4, see Video) and
    /// the frames are drawn into an Image here, under the controls. When it can't play, Enter
    /// hands the video to the default browser instead, so it still plays.
    ///
    /// Keys: Space/Enter pause and resume, Left/Right 10 s, Page Up/Down 30 s, Esc closes. Clicking
    /// pauses; the controls hide after a few seconds of playing.
    /// </summary>
    sealed class Player : Grid  // feature: video.inline
    {
        public interface IListener
        {
            void OnPlayerClosed();
        }

        readonly IListener listener;
        readonly Image picture = new Image();
        Video video;
        readonly StackPanel controls = new StackPanel();
        readonly TextBlock title, time, hint, status;
        readonly Border bar, barFill;
        readonly DispatcherTimer tick, hide, wait;
        string url;
        bool playing, opened, failed;
        string reason;

        public Player(IListener listener)
        {
            this.listener = listener;
            Background = Brushes.Black;
            Visibility = Visibility.Collapsed;
            picture.Stretch = Stretch.Uniform;
            RenderOptions.SetBitmapScalingMode(picture, BitmapScalingMode.Linear);
            Children.Add(picture);
            SizeChanged += (s, e) => Target();

            status = new TextBlock
            {
                FontFamily = Ui.Sans.FontFamily, FontSize = 18, Foreground = Ui.Brush(Ui.Rgb(0xE4DED3)), TextAlignment = TextAlignment.Center,
                TextWrapping = TextWrapping.Wrap, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(60, 0, 60, 0)
            };
            Children.Add(status);

            // A deep shade from well above the title down, so the words read over any picture.
            controls.VerticalAlignment = VerticalAlignment.Bottom;
            LinearGradientBrush shade = new LinearGradientBrush(Color.FromArgb(0, 0, 0, 0), Color.FromArgb(0xE0, 0, 0, 0), 90);
            controls.Background = shade;
            controls.Margin = new Thickness(0);
            title = new TextBlock { FontFamily = Ui.Head, FontSize = 24, Foreground = Brushes.White, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(48, 150, 48, 0) };
            controls.Children.Add(title);
            barFill = new Border { Background = Ui.Brush(Ui.Rgb(0xE5484D)), HorizontalAlignment = HorizontalAlignment.Left, Width = 0 };
            bar = new Border { Height = 4, Background = new SolidColorBrush(Color.FromArgb(0x50, 0xFF, 0xFF, 0xFF)), Margin = new Thickness(48, 18, 48, 0), Child = barFill, Cursor = Cursors.Hand };
            bar.MouseLeftButtonUp += (s, e) =>
            {
                if (!opened || video == null || video.Duration <= TimeSpan.Zero) return;
                double f = e.GetPosition(bar).X / Math.Max(1, bar.ActualWidth);
                video.Position = TimeSpan.FromSeconds(video.Duration.TotalSeconds * f);
                e.Handled = true;
                Update();
            };
            controls.Children.Add(bar);
            DockPanel row = new DockPanel { Margin = new Thickness(48, 12, 48, 30) };
            time = new TextBlock { FontFamily = Ui.Sans.FontFamily, FontSize = 15, Foreground = new SolidColorBrush(Color.FromRgb(0xE4, 0xDE, 0xD3)) };
            hint = new TextBlock { FontFamily = Ui.Sans.FontFamily, FontSize = 14, Foreground = new SolidColorBrush(Color.FromRgb(0xB1, 0xAA, 0x9E)), HorizontalAlignment = HorizontalAlignment.Right };
            DockPanel.SetDock(time, Dock.Left);
            row.Children.Add(time);
            row.Children.Add(hint);
            controls.Children.Add(row);
            Children.Add(controls);

            tick = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
            tick.Tick += (s, e) => Update();
            hide = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(3500) };
            hide.Tick += (s, e) =>
            {
                hide.Stop();
                if (playing) { controls.Visibility = Visibility.Collapsed; tick.Stop(); Cursor = Cursors.None; }
            };
            // A stream Media Foundation can't open sometimes never says so: give it 15 s.
            wait = new DispatcherTimer { Interval = TimeSpan.FromSeconds(15) };
            wait.Tick += (s, e) => { wait.Stop(); if (!opened && !failed && IsPlaying) { reason = "it didn't start within 15 s"; Failed(); } };
            MouseMove += (s, e) => { if (IsPlaying) ShowControls(); };
            MouseLeftButtonUp += (s, e) => { if (IsPlaying) Toggle(); };
        }

        public bool IsPlaying { get { return Visibility == Visibility.Visible; } }

        public void Play(string stream, string storyTitle)
        {
            url = stream;
            reason = null;
            opened = failed = false;
            title.Text = storyTitle ?? "";
            status.Text = "Loading the video…";
            status.Visibility = Visibility.Visible;
            Visibility = Visibility.Visible;
            controls.Visibility = Visibility.Visible;
            Cursor = null;
            picture.Source = null;
            Stop();
            video = new Video(Dispatcher);
            Video v = video;
            v.Opened += () =>
            {
                if (v != video) return;
                opened = true;
                ShowControls();
            };
            v.FrameShown += () =>
            {
                if (v != video) return;
                if (picture.Source != v.Bitmap) picture.Source = v.Bitmap;
                if (status.Visibility == Visibility.Visible && opened && !failed) status.Visibility = Visibility.Collapsed;
            };
            v.Ended += () => { if (v == video) Close(); };
            v.Failed += r => { if (v == video) { reason = r; Failed(); } };
            Target();
            playing = true;
            wait.Stop();
            wait.Start();
            v.Open(stream);
            Update();
        }

        /// <summary>Frames no bigger than the player is on screen, in device pixels.</summary>
        void Target()
        {
            if (video == null) return;
            PresentationSource src = PresentationSource.FromVisual(this);
            double sx = 1, sy = 1;
            if (src != null && src.CompositionTarget != null)
            {
                sx = src.CompositionTarget.TransformToDevice.M11;
                sy = src.CompositionTarget.TransformToDevice.M22;
            }
            double w = ActualWidth > 0 ? ActualWidth : SystemParameters.PrimaryScreenWidth;
            double h = ActualHeight > 0 ? ActualHeight : SystemParameters.PrimaryScreenHeight;
            video.SetTarget((int)Math.Ceiling(w * sx), (int)Math.Ceiling(h * sy));
        }

        void Stop()
        {
            if (video == null) return;
            Video v = video;
            video = null;
            v.Dispose();
        }

        void Failed()
        {
            failed = true;
            playing = false;
            wait.Stop();
            Stop();
            picture.Source = null;
            status.Visibility = Visibility.Visible;
            status.Text = "This video won't play here." + (reason != null ? "\n(" + reason.Trim() + ")" : "") + "\n\nEnter opens it in your web browser  ·  Esc goes back to the story";
            controls.Visibility = Visibility.Collapsed;
            Cursor = null;
        }

        void Toggle()
        {
            if (!opened || video == null) return;
            if (playing) video.Pause();
            else video.Play();
            playing = !playing;
            ShowControls();
        }

        void Seek(double seconds)
        {
            if (!opened || video == null) return;
            TimeSpan p = video.Position + TimeSpan.FromSeconds(seconds);
            if (p < TimeSpan.Zero) p = TimeSpan.Zero;
            TimeSpan d = video.Duration;
            if (d > TimeSpan.Zero && p > d) p = d;
            video.Position = p;
            ShowControls();
        }

        void ShowControls()
        {
            controls.Visibility = Visibility.Visible;
            Cursor = null;
            Update();
            tick.Start();
            hide.Stop();
            if (playing) hide.Start();
        }

        static string Clock(TimeSpan t)
        {
            return t.TotalHours >= 1 ? string.Format("{0}:{1:00}:{2:00}", (int)t.TotalHours, t.Minutes, t.Seconds)
                : string.Format("{0}:{1:00}", t.Minutes, t.Seconds);
        }

        void Update()
        {
            bool live = opened && video != null;
            TimeSpan pos = live ? video.Position : TimeSpan.Zero;
            TimeSpan dur = live ? video.Duration : TimeSpan.Zero;
            bool known = dur > TimeSpan.Zero;
            time.Text = (!playing ? "Paused  ·  " : live && video.Buffering ? "Loading  ·  " : "") + Clock(pos) + (known ? " / " + Clock(dur) : "");
            hint.Text = "Space pause  ·  ◀ ▶ 10 s  ·  Esc back";
            barFill.Width = known && dur.TotalSeconds > 0 ? bar.ActualWidth * Math.Min(1, pos.TotalSeconds / dur.TotalSeconds) : 0;
        }

        public void Close()
        {
            if (!IsPlaying) return;
            tick.Stop();
            hide.Stop();
            wait.Stop();
            Stop();
            picture.Source = null;
            playing = false;
            Visibility = Visibility.Collapsed;
            listener.OnPlayerClosed();
        }

        public bool OnKey(Key k)
        {
            switch (k)
            {
                case Key.Escape:
                case Key.Back:
                case Key.BrowserBack:
                    Close();
                    return true;
                case Key.Enter:
                    if (failed)
                    {
                        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch (Exception) { }
                        Close();
                    }
                    else Toggle();
                    return true;
                case Key.Space:
                case Key.MediaPlayPause:
                case Key.Play:
                case Key.Pause:
                    Toggle();
                    return true;
                case Key.Left:
                    Seek(-10);
                    return true;
                case Key.Right:
                    Seek(10);
                    return true;
                case Key.PageUp:
                    Seek(-30);
                    return true;
                case Key.PageDown:
                    Seek(30);
                    return true;
                case Key.Up:
                case Key.Down:
                    ShowControls();
                    return true;
            }
            return true;
        }
    }
}
