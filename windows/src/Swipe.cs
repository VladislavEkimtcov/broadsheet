using System;

namespace Broadsheet
{
    /// <summary>
    /// Two-finger sideways swipes on a precision touchpad, read the way Chrome reads them: from the
    /// horizontal scroll Windows sends (WM_MOUSEHWHEEL), so the direction follows the touchpad's own
    /// scrolling-direction setting exactly as it does in Chrome. Scrolling left (on the default
    /// setting, fingers moving from the right of the pad to the left) is Back; scrolling right opens
    /// Options. A tilt wheel's clicks count the same way.
    ///
    /// One swipe is one burst of scroll messages, the touchpad's coasting after the fingers lift
    /// included; a gap of QUIET_MS ends it. A burst gives at most one action, once its sideways travel
    /// passes THRESHOLD and is clearly more across than up or down, so a diagonal page scroll never
    /// goes back by accident. Pure arithmetic, no window: spec/cases/swipe.json tests it.
    /// </summary>
    sealed class Swipe  // feature: win.swipe
    {
        /// <summary>Sideways travel, in wheel units (120 a click), before a swipe counts.</summary>
        public const double THRESHOLD = 300;
        /// <summary>The sideways travel must be this many times the travel up or down.</summary>
        public const double DOMINANCE = 2;
        /// <summary>A pause this long (ms) between scroll messages ends the burst.</summary>
        public const int QUIET_MS = 250;

        public const int NONE = 0, BACK = -1, OPTIONS = 1;

        double across, down;
        int lastAt;
        bool started, fired;

        /// <summary>
        /// One scroll message at time t (ms, any clock that only goes forward, wrapping like
        /// Environment.TickCount): dx is WM_MOUSEHWHEEL's delta (positive scrolls right), dy
        /// WM_MOUSEWHEEL's. Gives BACK or OPTIONS when the burst becomes a swipe, else NONE.
        /// </summary>
        public int Feed(int t, double dx, double dy)
        {
            if (!started || unchecked(t - lastAt) > QUIET_MS)
            {
                across = 0;
                down = 0;
                fired = false;
            }
            started = true;
            lastAt = t;
            across += dx;
            down += Math.Abs(dy);
            if (fired) return NONE;
            if (Math.Abs(across) < THRESHOLD || Math.Abs(across) < DOMINANCE * down) return NONE;
            fired = true;
            return across < 0 ? BACK : OPTIONS;
        }
    }
}
