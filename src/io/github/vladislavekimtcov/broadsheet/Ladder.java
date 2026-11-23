package io.github.vladislavekimtcov.broadsheet;

/**
 * Where the reader is heading, so the app can have it ready before they get there: which cards
 * around the focused one, which sections beside the one on the rail, which stories while reading,
 * and when to load more. No Android types: the shared cases in spec/cases/ladder.json test it, as
 * they test the Windows port's Ladder.cs.
 *
 * <p>The front page is a hero and two briefs (stories 0 to 2), then rows of three, so the card
 * below story i is about i + 3.
 */
final class Ladder {
    /** Stories ahead of time per list (the relay takes at most 12 per client). */
    static final int MAX = 7;

    private Ladder() {}

    /** The cards around card {@code i}, most likely next first: itself, right, below, left,
     *  two to the right, below right, above. */
    static int[] around(int i, int count) {
        return pick(new int[] {i, i + 1, i + 3, i - 1, i + 2, i + 4, i - 3}, count, MAX);
    }

    /** Ask for the next page of a section once focus reaches its last two rows. */
    static boolean wantMore(int index, int loaded) {
        return loaded > 0 && index >= loaded - 6;
    }

    /** The sections beside {@code s} on the rail, the way the reader is moving first
     *  ({@code direction}: +1 down, -1 up, 0 unknown, taken as down). */
    static int[] railNeighbours(int s, int count, int direction) {
        int d = direction < 0 ? -1 : 1;
        return pick(new int[] {s + d, s - d}, count, 2);
    }

    /** While reading story {@code k} of a section: the next, the one after, the previous. */
    static int[] reading(int k, int count) {
        return pick(new int[] {k + 1, k + 2, k - 1}, count, 3);
    }

    /** Within {@code screens} screen heights of the end of {@code content}. */
    static boolean near(int scrollY, int viewport, int content, float screens) {
        return content - (scrollY + viewport) <= screens * viewport;
    }

    private static int[] pick(int[] candidates, int count, int max) {
        int[] out = new int[Math.min(max, candidates.length)];
        int n = 0;
        for (int c : candidates) {
            if (c < 0 || c >= count || n == out.length) continue;
            boolean seen = false;
            for (int j = 0; j < n; j++) seen |= out[j] == c;
            if (!seen) out[n++] = c;
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }
}
