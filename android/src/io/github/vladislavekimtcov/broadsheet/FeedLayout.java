package io.github.vladislavekimtcov.broadsheet;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure front-page grid and card-role selection, matching {@code windows/src/FeedLayout.cs} and
 * tested by {@code spec/cases/layout.json}.
 *
 * <p>Never scales UI typography down below 1.0x when a window or screen narrows; instead collapses
 * the desktop rail below {@link #RAIL_COLLAPSE_WIDTH} and drops cleanly from 3 to 2 to 1 card
 * columns so every tile stays wide enough to read comfortably.
 */
final class FeedLayout {
    static final int HERO = 0;
    static final int TILE = 1;
    static final int BRIEF = 2;
    static final int COMPACT = 3;

    static final int RAIL_WIDTH = 224;
    static final int RAIL_COLLAPSE_WIDTH = 840;
    static final int COL_2_MIN = 540;
    static final int COL_3_MIN = 860;
    static final int MAX_PHONE_CONTENT = 1040;

    static final class Slot {
        final int story;
        final int kind;
        final int row;
        final int col;
        final int span;
        final int gridCols;
        final int stackIndex;
        final int stackCount;

        Slot(int story, int kind, int row, int col, int span, int gridCols, int stackIndex, int stackCount) {
            this.story = story;
            this.kind = kind;
            this.row = row;
            this.col = col;
            this.span = span;
            this.gridCols = gridCols;
            this.stackIndex = stackIndex;
            this.stackCount = stackCount;
        }

        @Override
        public String toString() {
            String k = kind == HERO ? "H" : kind == TILE ? "T" : kind == BRIEF ? "B" : "C";
            String s = story + ":" + k + "@r" + row + "c" + col + "+" + span + "/" + gridCols;
            return stackCount > 1 ? s + "#" + stackIndex + "/" + stackCount : s;
        }
    }

    private FeedLayout() {}

    static boolean railCollapsed(int windowWidth) {
        return windowWidth > 0 && windowWidth < RAIL_COLLAPSE_WIDTH;
    }

    static int sidePadding(int windowWidth) {
        if (windowWidth > 0 && windowWidth < 520) return 16;
        if (windowWidth > 0 && windowWidth < RAIL_COLLAPSE_WIDTH) return 20;
        return 34;
    }

    static int columns(int contentWidth) {
        if (contentWidth >= COL_3_MIN) return 3;
        if (contentWidth >= COL_2_MIN) return 2;
        return 1;
    }

    /**
     * Plans card kinds and grid positions for a section. In {@code !dynamic} ("Big tile") mode,
     * preserves the classic Broadsheet grid (hero + two briefs, then picture-top tiles in {@code cols}
     * columns). In {@code dynamic} mode, uses Apple News's pacing: hero lead, 2-up pairs or compact
     * square-thumbnail rows, text briefs when a story has no photo, and periodic picture tiles.
     */
    static List<Slot> plan(int cols, boolean dynamic, boolean[] hasImage, boolean[] portrait) {
        List<Slot> out = new ArrayList<>();
        int n = hasImage == null ? 0 : hasImage.length;
        if (n == 0) return out;
        int c = cols <= 1 ? 1 : (cols == 2 ? 2 : 3);
        int g = c == 3 ? 3 : 2;
        if (!dynamic) return planBig(out, n, c, g);
        return planDynamic(out, n, c, g, hasImage, portrait);
    }

    private static List<Slot> planBig(List<Slot> out, int n, int c, int g) {
        int row = 0;
        int start = 0;
        if (n >= 3) {
            if (c == 3) {
                out.add(new Slot(0, HERO, 0, 0, 2, 3, 0, 1));
                out.add(new Slot(1, BRIEF, 0, 2, 1, 3, 0, 2));
                out.add(new Slot(2, BRIEF, 0, 2, 1, 3, 1, 2));
                row = 1;
            } else if (c == 2) {
                out.add(new Slot(0, HERO, 0, 0, 2, 2, 0, 1));
                out.add(new Slot(1, BRIEF, 1, 0, 1, 2, 0, 1));
                out.add(new Slot(2, BRIEF, 1, 1, 1, 2, 0, 1));
                row = 2;
            } else {
                out.add(new Slot(0, HERO, 0, 0, 2, 2, 0, 1));
                out.add(new Slot(1, BRIEF, 1, 0, 2, 2, 0, 1));
                out.add(new Slot(2, BRIEF, 2, 0, 2, 2, 0, 1));
                row = 3;
            }
            start = 3;
        }
        if (c == 1) {
            for (int i = start; i < n; i++) out.add(new Slot(i, TILE, row++, 0, 2, 2, 0, 1));
        } else {
            for (int i = start; i < n; i += c) {
                int rem = Math.min(c, n - i);
                for (int k = 0; k < rem; k++) out.add(new Slot(i + k, TILE, row, k, 1, g, 0, 1));
                row++;
            }
        }
        return out;
    }

    private static List<Slot> planDynamic(List<Slot> out, int n, int c, int g,
            boolean[] hasImage, boolean[] portrait) {
        if (n == 1) {
            out.add(new Slot(0, HERO, 0, 0, g, g, 0, 1));
            return out;
        }
        if (n == 2) {
            out.add(new Slot(0, HERO, 0, 0, g, g, 0, 1));
            out.add(new Slot(1, img(hasImage, 1) ? COMPACT : BRIEF, 1, 0, g, g, 0, 1));
            return out;
        }
        int row = 0;
        int compactRun = 0;
        if (c == 3) {
            out.add(new Slot(0, HERO, 0, 0, 2, 3, 0, 1));
            out.add(new Slot(1, img(hasImage, 1) ? COMPACT : BRIEF, 0, 2, 1, 3, 0, 2));
            out.add(new Slot(2, img(hasImage, 2) ? COMPACT : BRIEF, 0, 2, 1, 3, 1, 2));
            row = 1;
        } else if (c == 2) {
            out.add(new Slot(0, HERO, 0, 0, 2, 2, 0, 1));
            out.add(new Slot(1, img(hasImage, 1) ? COMPACT : BRIEF, 1, 0, 1, 2, 0, 1));
            out.add(new Slot(2, img(hasImage, 2) ? COMPACT : BRIEF, 1, 1, 1, 2, 0, 1));
            row = 2;
        } else {
            out.add(new Slot(0, HERO, 0, 0, 2, 2, 0, 1));
            if (img(hasImage, 1) && img(hasImage, 2)) {
                out.add(new Slot(1, TILE, 1, 0, 1, 2, 0, 1));
                out.add(new Slot(2, TILE, 1, 1, 1, 2, 0, 1));
                row = 2;
            } else {
                out.add(new Slot(1, img(hasImage, 1) ? COMPACT : BRIEF, 1, 0, 2, 2, 0, 1));
                out.add(new Slot(2, img(hasImage, 2) ? COMPACT : BRIEF, 2, 0, 2, 2, 0, 1));
                row = 3;
                compactRun = 2;
            }
        }
        if (c == 1) {
            int i = 3;
            while (i < n) {
                if (compactRun >= 3) {
                    if (i + 1 < n && img(hasImage, i) && img(hasImage, i + 1)) {
                        out.add(new Slot(i, TILE, row, 0, 1, 2, 0, 1));
                        out.add(new Slot(i + 1, TILE, row, 1, 1, 2, 0, 1));
                        row++;
                        i += 2;
                        compactRun = 0;
                        continue;
                    }
                    if (img(hasImage, i) && !port(hasImage, portrait, i)) {
                        out.add(new Slot(i, TILE, row, 0, 2, 2, 0, 1));
                        row++;
                        i++;
                        compactRun = 0;
                        continue;
                    }
                }
                out.add(new Slot(i, img(hasImage, i) ? COMPACT : BRIEF, row, 0, 2, 2, 0, 1));
                row++;
                i++;
                compactRun++;
            }
            return out;
        }
        int bodyRow = 0;
        for (int i = 3; i < n; ) {
            int rem = Math.min(c, n - i);
            boolean showcase = (bodyRow % 3 == 0);
            if (showcase) {
                for (int k = 0; k < rem; k++) {
                    if (!img(hasImage, i + k) || port(hasImage, portrait, i + k)) {
                        showcase = false;
                        break;
                    }
                }
            }
            for (int k = 0; k < rem; k++) {
                int kind = showcase ? TILE : (img(hasImage, i + k) ? COMPACT : BRIEF);
                out.add(new Slot(i + k, kind, row, k, 1, g, 0, 1));
            }
            i += rem;
            row++;
            bodyRow++;
        }
        return out;
    }

    private static boolean img(boolean[] hasImage, int i) {
        return hasImage != null && i >= 0 && i < hasImage.length && hasImage[i];
    }

    private static boolean port(boolean[] hasImage, boolean[] portrait, int i) {
        return img(hasImage, i) && portrait != null && i < portrait.length && portrait[i];
    }
}
