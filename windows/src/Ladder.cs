using System;
using System.Collections.Generic;

namespace Broadsheet
{
    /// <summary>
    /// Where the reader is heading, so the app can have it ready before they get there: which cards
    /// around the focused one, which sections beside the one on the rail, which stories while reading,
    /// and when to load more. No WPF types: the shared cases in spec\cases\ladder.json test it, as
    /// they test the TV app's Ladder.java.
    ///
    /// The front page is a hero and two briefs (stories 0 to 2), then rows of three, so the card below
    /// story i is about i + 3.
    /// </summary>
    static class Ladder
    {
        /// <summary>Stories ahead of time per list (the relay takes at most 12 per client).</summary>
        public const int MAX = 7;

        /// <summary>The cards around card i, most likely next first: itself, right, below, left, two to
        /// the right, below right, above.</summary>
        public static int[] Around(int i, int count)
        {
            return Pick(new[] { i, i + 1, i + 3, i - 1, i + 2, i + 4, i - 3 }, count, MAX);
        }

        /// <summary>Ask for the next page of a section once focus reaches its last two rows.</summary>
        public static bool WantMore(int index, int loaded)
        {
            return loaded > 0 && index >= loaded - 6;
        }

        /// <summary>The sections beside s on the rail, the way the reader is moving first (direction:
        /// +1 down, -1 up, 0 unknown, taken as down).</summary>
        public static int[] RailNeighbours(int s, int count, int direction)
        {
            int d = direction < 0 ? -1 : 1;
            return Pick(new[] { s + d, s - d }, count, 2);
        }

        /// <summary>While reading story k of a section: the next, the one after, the previous.</summary>
        public static int[] Reading(int k, int count)
        {
            return Pick(new[] { k + 1, k + 2, k - 1 }, count, 3);
        }

        /// <summary>Within screens screen heights of the end of content.</summary>
        public static bool Near(double scrollY, double viewport, double content, double screens)
        {
            return content - (scrollY + viewport) <= screens * viewport;
        }

        static int[] Pick(int[] candidates, int count, int max)
        {
            List<int> out_ = new List<int>();
            foreach (int c in candidates)
            {
                if (c < 0 || c >= count || out_.Count == max || out_.Contains(c)) continue;
                out_.Add(c);
            }
            return out_.ToArray();
        }
    }
}
