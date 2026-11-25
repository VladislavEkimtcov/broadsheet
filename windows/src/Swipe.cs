using System;
using System.Collections.Generic;

namespace Broadsheet
{
    /// <summary>
    /// Two-finger sideways swipes on a precision touchpad, followed as they happen. The travel comes
    /// from the horizontal scroll Windows sends (WM_MOUSEHWHEEL), the way Chrome reads its swipe back,
    /// so the direction follows the touchpad's scrolling-direction setting as it does in Chrome:
    /// scrolling left (fingers from the right of the pad to the left, on DOORWEDGE's setting) is
    /// Back, scrolling right is Options.
    ///
    /// The window shows Progress (-1 back … 0 … +1 options) as the swipe moves, so moving the fingers
    /// back and forth rewinds and advances the animation. When the fingers lift, the swipe is decided:
    /// past half way, or flicked toward the end, it goes through; otherwise it springs back. The lift
    /// is known from the touchpad's own reports (WM_INPUT, which a precision touchpad sends all the
    /// while a finger is down; Touch); a device that sends none (a tilt wheel) ends at a pause in its
    /// scrolling. The coasting Windows adds after a lift is ignored until the fingers come down again.
    ///
    /// Pure arithmetic on times and deltas, no window: spec/cases/swipe.json tests it.
    /// </summary>
    sealed class Swipe  // feature: win.swipe
    {
        /// <summary>Sideways travel (wheel units, 120 a click) before a scroll counts as a swipe.</summary>
        public const double ENGAGE = 16;
        /// <summary>Until then, the travel across must be this many times the travel up or down.</summary>
        public const double DOMINANCE = 2;
        /// <summary>Travel for the whole animation, from the page at rest to the next view in place.</summary>
        public const double FULL = 480;
        /// <summary>Progress past which lifting the fingers goes through.</summary>
        public const double COMMIT = 0.5;
        /// <summary>A flick this fast (units a second) at the lift decides by its direction alone.</summary>
        public const double FLING = 2000;
        /// <summary>No touchpad report for this long (ms): the fingers are up.</summary>
        public const int LIFT_MS = 100;
        /// <summary>No scroll message for this long (ms) ends a swipe from a device without reports,
        /// and a burst of scrolling that never became one.</summary>
        public const int QUIET_MS = 250;
        /// <summary>The flick speed is measured over the last this many ms before the lift.</summary>
        public const int VELOCITY_MS = 80;

        public const int NONE = 0, STARTED = 1, MOVED = 2, BACK = -1, OPTIONS = 3, CANCEL = 4;

        double across, down, min = -1, max = 1;
        bool started, engaged, ignoring, everTouched;
        int lastWheel, lastTouch, startedAt, touchSession, endSession;
        readonly List<double[]> samples = new List<double[]>();

        public bool Engaged { get { return engaged; } }

        /// <summary>-1 (back done) … 0 (at rest) … +1 (options in place), within the limits.</summary>
        public double Progress { get { return Math.Max(min, Math.Min(max, across / FULL)); } }

        /// <summary>
        /// Where this swipe may go, set by the window when it starts (STARTED): 0 for a way that
        /// leads nowhere. Travel past a limit isn't kept, so turning back moves at once.
        /// </summary>
        public void Limit(double lo, double hi)
        {
            min = lo;
            max = hi;
            across = Math.Max(min * FULL, Math.Min(max * FULL, across));
        }

        /// <summary>A report from the touchpad at time t (ms): a finger is down.</summary>
        public void Touch(int t)
        {
            if (!everTouched || unchecked(t - lastTouch) > LIFT_MS) touchSession++;
            everTouched = true;
            lastTouch = t;
        }

        bool FingersSeen
        {
            get { return everTouched && unchecked(lastTouch - startedAt) >= -LIFT_MS; }
        }

        /// <summary>
        /// One scroll message at time t (ms, wrapping like Environment.TickCount): dx is
        /// WM_MOUSEHWHEEL's delta (positive scrolls right), dy WM_MOUSEWHEEL's. STARTED when the
        /// scrolling becomes a swipe, MOVED while one goes on (Progress changed), else NONE.
        /// </summary>
        public int Feed(int t, double dx, double dy)
        {
            if (ignoring)
            {
                // Coasting after a lift: the fingers are up, Windows is still scrolling.
                if (touchSession == endSession && unchecked(t - lastWheel) <= QUIET_MS)
                {
                    lastWheel = t;
                    return NONE;
                }
                ignoring = false;
            }
            if (!started || (!engaged && unchecked(t - lastWheel) > QUIET_MS))
            {
                started = true;
                engaged = false;
                across = 0;
                down = 0;
                min = -1;
                max = 1;
                startedAt = t;
                samples.Clear();
            }
            lastWheel = t;
            across += dx;
            if (!engaged)
            {
                down += Math.Abs(dy);
                if (Math.Abs(across) < ENGAGE || Math.Abs(across) < DOMINANCE * down) return NONE;
                engaged = true;
                Sample(t);
                return STARTED;
            }
            across = Math.Max(min * FULL, Math.Min(max * FULL, across));
            Sample(t);
            return MOVED;
        }

        void Sample(int t)
        {
            samples.Add(new[] { t, across });
            while (samples.Count > 2 && unchecked(t - (int)samples[1][0]) >= VELOCITY_MS) samples.RemoveAt(0);
        }

        /// <summary>Units a second over the last VELOCITY_MS of the swipe.</summary>
        public double Velocity
        {
            get
            {
                if (samples.Count < 2) return 0;
                double[] a = samples[0], b = samples[samples.Count - 1];
                int dt = unchecked((int)b[0] - (int)a[0]);
                return dt <= 0 ? 0 : (b[1] - a[1]) * 1000.0 / dt;
            }
        }

        /// <summary>
        /// Call every frame while Engaged: once the fingers are up, the swipe is decided and over.
        /// BACK, OPTIONS or CANCEL then, else NONE.
        /// </summary>
        public int Poll(int t)
        {
            if (!engaged) return NONE;
            bool tracked = FingersSeen;
            if (unchecked(t - (tracked ? lastTouch : lastWheel)) <= (tracked ? LIFT_MS : QUIET_MS)) return NONE;
            // A flick counts only if the fingers were still moving when they lifted.
            bool moving = tracked && unchecked(lastTouch - lastWheel) <= VELOCITY_MS;
            int result = Decide(Progress, moving ? Velocity : 0);
            engaged = false;
            started = false;
            ignoring = tracked;
            endSession = touchSession;
            return result;
        }

        static int Decide(double p, double v)
        {
            if (p == 0) return CANCEL;
            if (Math.Abs(v) >= FLING) return Math.Sign(v) == Math.Sign(p) ? (p < 0 ? BACK : OPTIONS) : CANCEL;
            if (Math.Abs(p) >= COMMIT) return p < 0 ? BACK : OPTIONS;
            return CANCEL;
        }
    }
}
