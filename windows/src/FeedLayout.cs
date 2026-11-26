using System;
using System.Collections.Generic;

namespace Broadsheet
{
    /// <summary>
    /// Pure front-page grid and card-role selection, matching android FeedLayout.java and tested by
    /// spec\cases\layout.json.
    ///
    /// Never scales UI typography down below 1.0x when a window narrows; instead collapses the left
    /// rail below RAIL_COLLAPSE_WIDTH and drops cleanly from 3 to 2 to 1 card columns so every
    /// tile stays wide enough to read comfortably.
    /// </summary>
    static class FeedLayout
    {
        public const int HERO = 0;
        public const int TILE = 1;
        public const int BRIEF = 2;
        public const int COMPACT = 3;

        public const int RAIL_WIDTH = 224;
        public const int RAIL_COLLAPSE_WIDTH = 840;
        public const int COL_2_MIN = 540;
        public const int COL_3_MIN = 860;

        public sealed class Slot
        {
            public readonly int Story;
            public readonly int Kind;
            public readonly int Row;
            public readonly int Col;
            public readonly int Span;
            public readonly int GridCols;
            public readonly int StackIndex;
            public readonly int StackCount;

            public Slot(int story, int kind, int row, int col, int span, int gridCols, int stackIndex, int stackCount)
            {
                Story = story;
                Kind = kind;
                Row = row;
                Col = col;
                Span = span;
                GridCols = gridCols;
                StackIndex = stackIndex;
                StackCount = stackCount;
            }

            public override string ToString()
            {
                string k = Kind == HERO ? "H" : Kind == TILE ? "T" : Kind == BRIEF ? "B" : "C";
                string s = Story + ":" + k + "@r" + Row + "c" + Col + "+" + Span + "/" + GridCols;
                return StackCount > 1 ? s + "#" + StackIndex + "/" + StackCount : s;
            }
        }

        public static bool RailCollapsed(int windowWidth)
        {
            return windowWidth > 0 && windowWidth < RAIL_COLLAPSE_WIDTH;
        }

        public static int SidePadding(int windowWidth)
        {
            if (windowWidth > 0 && windowWidth < 520) return 16;
            if (windowWidth > 0 && windowWidth < RAIL_COLLAPSE_WIDTH) return 20;
            return 34;
        }

        public static int Columns(int contentWidth)
        {
            if (contentWidth >= COL_3_MIN) return 3;
            if (contentWidth >= COL_2_MIN) return 2;
            return 1;
        }

        public static List<Slot> Plan(int cols, bool dynamic, bool[] hasImage, bool[] portrait)
        {
            List<Slot> out_ = new List<Slot>();
            int n = hasImage == null ? 0 : hasImage.Length;
            if (n == 0) return out_;
            int c = cols <= 1 ? 1 : (cols == 2 ? 2 : 3);
            int g = c == 3 ? 3 : 2;
            if (!dynamic) return PlanBig(out_, n, c, g);
            return PlanDynamic(out_, n, c, g, hasImage, portrait);
        }

        static List<Slot> PlanBig(List<Slot> out_, int n, int c, int g)
        {
            int row = 0;
            int start = 0;
            if (n >= 3)
            {
                if (c == 3)
                {
                    out_.Add(new Slot(0, HERO, 0, 0, 2, 3, 0, 1));
                    out_.Add(new Slot(1, BRIEF, 0, 2, 1, 3, 0, 2));
                    out_.Add(new Slot(2, BRIEF, 0, 2, 1, 3, 1, 2));
                    row = 1;
                }
                else if (c == 2)
                {
                    out_.Add(new Slot(0, HERO, 0, 0, 2, 2, 0, 1));
                    out_.Add(new Slot(1, BRIEF, 1, 0, 1, 2, 0, 1));
                    out_.Add(new Slot(2, BRIEF, 1, 1, 1, 2, 0, 1));
                    row = 2;
                }
                else
                {
                    out_.Add(new Slot(0, HERO, 0, 0, 2, 2, 0, 1));
                    out_.Add(new Slot(1, BRIEF, 1, 0, 2, 2, 0, 1));
                    out_.Add(new Slot(2, BRIEF, 2, 0, 2, 2, 0, 1));
                    row = 3;
                }
                start = 3;
            }
            if (c == 1)
            {
                for (int i = start; i < n; i++) out_.Add(new Slot(i, TILE, row++, 0, 2, 2, 0, 1));
            }
            else
            {
                for (int i = start; i < n; i += c)
                {
                    int rem = Math.Min(c, n - i);
                    for (int k = 0; k < rem; k++) out_.Add(new Slot(i + k, TILE, row, k, 1, g, 0, 1));
                    row++;
                }
            }
            return out_;
        }

        static List<Slot> PlanDynamic(List<Slot> out_, int n, int c, int g, bool[] hasImage, bool[] portrait)
        {
            if (n == 1)
            {
                out_.Add(new Slot(0, HERO, 0, 0, g, g, 0, 1));
                return out_;
            }
            if (n == 2)
            {
                out_.Add(new Slot(0, HERO, 0, 0, g, g, 0, 1));
                out_.Add(new Slot(1, Img(hasImage, 1) ? COMPACT : BRIEF, 1, 0, g, g, 0, 1));
                return out_;
            }
            int row = 0;
            int compactRun = 0;
            if (c == 3)
            {
                out_.Add(new Slot(0, HERO, 0, 0, 2, 3, 0, 1));
                out_.Add(new Slot(1, Img(hasImage, 1) ? COMPACT : BRIEF, 0, 2, 1, 3, 0, 2));
                out_.Add(new Slot(2, Img(hasImage, 2) ? COMPACT : BRIEF, 0, 2, 1, 3, 1, 2));
                row = 1;
            }
            else if (c == 2)
            {
                out_.Add(new Slot(0, HERO, 0, 0, 2, 2, 0, 1));
                out_.Add(new Slot(1, Img(hasImage, 1) ? COMPACT : BRIEF, 1, 0, 1, 2, 0, 1));
                out_.Add(new Slot(2, Img(hasImage, 2) ? COMPACT : BRIEF, 1, 1, 1, 2, 0, 1));
                row = 2;
            }
            else
            {
                out_.Add(new Slot(0, HERO, 0, 0, 2, 2, 0, 1));
                if (Img(hasImage, 1) && Img(hasImage, 2))
                {
                    out_.Add(new Slot(1, TILE, 1, 0, 1, 2, 0, 1));
                    out_.Add(new Slot(2, TILE, 1, 1, 1, 2, 0, 1));
                    row = 2;
                }
                else
                {
                    out_.Add(new Slot(1, Img(hasImage, 1) ? COMPACT : BRIEF, 1, 0, 2, 2, 0, 1));
                    out_.Add(new Slot(2, Img(hasImage, 2) ? COMPACT : BRIEF, 2, 0, 2, 2, 0, 1));
                    row = 3;
                    compactRun = 2;
                }
            }
            if (c == 1)
            {
                int i = 3;
                while (i < n)
                {
                    if (compactRun >= 3)
                    {
                        if (i + 1 < n && Img(hasImage, i) && Img(hasImage, i + 1))
                        {
                            out_.Add(new Slot(i, TILE, row, 0, 1, 2, 0, 1));
                            out_.Add(new Slot(i + 1, TILE, row, 1, 1, 2, 0, 1));
                            row++;
                            i += 2;
                            compactRun = 0;
                            continue;
                        }
                        if (Img(hasImage, i) && !Port(hasImage, portrait, i))
                        {
                            out_.Add(new Slot(i, TILE, row, 0, 2, 2, 0, 1));
                            row++;
                            i++;
                            compactRun = 0;
                            continue;
                        }
                    }
                    out_.Add(new Slot(i, Img(hasImage, i) ? COMPACT : BRIEF, row, 0, 2, 2, 0, 1));
                    row++;
                    i++;
                    compactRun++;
                }
                return out_;
            }
            int bodyRow = 0;
            for (int j = 3; j < n; )
            {
                int rem = Math.Min(c, n - j);
                bool showcase = (bodyRow % 3 == 0);
                if (showcase)
                {
                    for (int k = 0; k < rem; k++)
                    {
                        if (!Img(hasImage, j + k) || Port(hasImage, portrait, j + k))
                        {
                            showcase = false;
                            break;
                        }
                    }
                }
                for (int k = 0; k < rem; k++)
                {
                    int kind = showcase ? TILE : (Img(hasImage, j + k) ? COMPACT : BRIEF);
                    out_.Add(new Slot(j + k, kind, row, k, 1, g, 0, 1));
                }
                j += rem;
                row++;
                bodyRow++;
            }
            return out_;
        }

        static bool Img(bool[] hasImage, int i)
        {
            return hasImage != null && i >= 0 && i < hasImage.Length && hasImage[i];
        }

        static bool Port(bool[] hasImage, bool[] portrait, int i)
        {
            return Img(hasImage, i) && portrait != null && i < portrait.Length && portrait[i];
        }
    }
}
