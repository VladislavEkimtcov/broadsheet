package io.github.vladislavekimtcov.broadsheet;

import java.util.Calendar;
import java.util.Locale;

/**
 * Feed and page dates to epoch milliseconds, and "2h ago" labels. Hand-rolled (no
 * SimpleDateFormat): feeds disagree about the format, and this runs the same on a desktop JVM.
 */
final class Dates {
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    private Dates() {}

    /** RFC 822 ("Sun, 22 Nov 2026 14:03:00 GMT") or ISO 8601 ("2026-11-22T14:03:00Z"); 0 if neither. */
    static long parse(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.isEmpty()) return 0;
        long t = Character.isDigit(s.charAt(0)) && s.length() >= 10 && s.charAt(4) == '-' ? iso(s) : rfc822(s);
        return t;
    }

    private static long iso(String s) {
        try {
            int y = Integer.parseInt(s.substring(0, 4));
            int mo = Integer.parseInt(s.substring(5, 7));
            int d = Integer.parseInt(s.substring(8, 10));
            int h = 0, mi = 0, sec = 0, i = 10;
            if (s.length() > 15 && (s.charAt(10) == 'T' || s.charAt(10) == ' ')) {
                h = Integer.parseInt(s.substring(11, 13));
                mi = Integer.parseInt(s.substring(14, 16));
                i = 16;
                if (s.length() > 18 && s.charAt(16) == ':') {
                    sec = Integer.parseInt(s.substring(17, 19));
                    i = 19;
                }
                while (i < s.length() && (s.charAt(i) == '.' || Character.isDigit(s.charAt(i)))) i++;
            }
            int offset = i < s.length() ? zone(s.substring(i).trim()) : 0;
            return utc(y, mo, d, h, mi, sec, offset);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long rfc822(String s) {
        try {
            int comma = s.indexOf(',');
            String[] p = s.substring(comma + 1).trim().split("\\s+");
            if (p.length < 4) return 0;
            int d = Integer.parseInt(p[0]);
            int mo = month(p[1]);
            int y = Integer.parseInt(p[2]);
            if (y < 100) y += 2000;
            String[] hms = p[3].split(":");
            int h = Integer.parseInt(hms[0]);
            int mi = hms.length > 1 ? Integer.parseInt(hms[1]) : 0;
            int sec = hms.length > 2 ? Integer.parseInt(hms[2]) : 0;
            int offset = p.length > 4 ? zone(p[4]) : 0;
            if (mo < 1) return 0;
            return utc(y, mo, d, h, mi, sec, offset);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int month(String m) {
        String l = m.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 12; i++) if (l.startsWith(MONTHS[i])) return i + 1;
        return -1;
    }

    /** Offset east of UTC in minutes: Z, GMT, UT, +0100, -05:00, EST/EDT/CST/.../PDT. */
    private static int zone(String z) {
        if (z.isEmpty()) return 0;
        char c = z.charAt(0);
        if (c == '+' || c == '-') {
            String digits = z.substring(1).replace(":", "");
            if (digits.length() < 2) return 0;
            int hh = Integer.parseInt(digits.substring(0, 2));
            int mm = digits.length() >= 4 ? Integer.parseInt(digits.substring(2, 4)) : 0;
            int m = hh * 60 + mm;
            return c == '-' ? -m : m;
        }
        switch (z.toUpperCase(Locale.ROOT)) {
            case "EST": return -300;
            case "EDT": return -240;
            case "CST": return -360;
            case "CDT": return -300;
            case "MST": return -420;
            case "MDT": return -360;
            case "PST": return -480;
            case "PDT": return -420;
            case "BST": return 60;
            case "CET": return 60;
            case "CEST": return 120;
            default: return 0;
        }
    }

    static long utc(int y, int mo, int d, int h, int mi, int sec, int offsetMinutes) {
        long days = days(y, mo, d);
        return ((days * 86400L) + h * 3600L + mi * 60L + sec - offsetMinutes * 60L) * 1000L;
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm). */
    static long days(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** "Just now", "12m ago", "3h ago", "Yesterday", "2d ago"; from a week on "Sep 5" or "Sep 5, 2025". */
    static String ago(long when, long now) {
        if (when <= 0) return "";
        long m = (now - when) / 60000;
        if (m < 2) return "Just now";
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        long d = h / 24;
        if (d < 7) return d == 1 ? "Yesterday" : d + "d ago";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int thisYear = c.get(Calendar.YEAR);
        c.setTimeInMillis(when);
        String month = MONTHS[c.get(Calendar.MONTH)];
        String day = Character.toUpperCase(month.charAt(0)) + month.substring(1) + " " + c.get(Calendar.DAY_OF_MONTH);
        return c.get(Calendar.YEAR) == thisYear ? day : day + ", " + c.get(Calendar.YEAR);
    }

    /** Reading time for this many words at ~230 words a minute: "4 min read". */
    static String readingTime(int words) {
        int min = Math.max(1, Math.round(words / 230f));
        return min + " min read";
    }
}
