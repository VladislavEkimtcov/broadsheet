package io.github.vladislavekimtcov.broadsheet;

/**
 * Pure update-tag and semantic-version helpers shared with the spec cases (spec/cases/update.json).
 * Mirrors windows/src/Updates.cs.
 */
final class Updates {
    private static final String TAG_MARKER = "/releases/tag/";

    private Updates() {}

    /**
     * Extracts a clean numeric version ("0.6.0") from a GitHub {@code /releases/latest} redirect
     * {@code Location} header, or {@code null} for non-release URLs and pre-release tags.
     */
    static String tagFromLocation(String location) {
        if (location == null || location.isEmpty()) return null;
        int idx = location.indexOf(TAG_MARKER);
        if (idx < 0) return null;
        String tag = location.substring(idx + TAG_MARKER.length());
        int q = tag.indexOf('?');
        if (q >= 0) tag = tag.substring(0, q);
        int h = tag.indexOf('#');
        if (h >= 0) tag = tag.substring(0, h);
        while (tag.endsWith("/")) tag = tag.substring(0, tag.length() - 1);
        int slash = tag.indexOf('/');
        if (slash >= 0) tag = tag.substring(0, slash);
        if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
        return parseVersion(tag) != null ? tag : null;
    }

    /** True when {@code remote} and {@code local} are valid dotted versions and remote &gt; local. */
    static boolean isNewer(String remote, String local) {
        int[] r = parseVersion(remote);
        int[] l = parseVersion(local);
        if (r == null || l == null) return false;
        for (int i = 0; i < 4; i++) {
            if (r[i] != l[i]) return r[i] > l[i];
        }
        return false;
    }

    static int[] parseVersion(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\.", -1);
        if (parts.length < 2 || parts.length > 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) return null;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') return null;
            }
            try {
                out[i] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }
}
