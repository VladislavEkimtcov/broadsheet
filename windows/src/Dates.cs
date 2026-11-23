using System;
using System.Globalization;

namespace Broadsheet
{
    /// <summary>
    /// Relay dates to epoch milliseconds, and "2h ago" labels. A port of the TV app's Dates.java,
    /// hand-rolled so it reads every format the feeds use the same way.
    /// </summary>
    static class Dates
    {
        static readonly string[] MONTHS = { "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec" };

        public static long NowMs()
        {
            return (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
        }

        public static DateTime Local(long ms)
        {
            return new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddMilliseconds(ms).ToLocalTime();
        }

        /// <summary>"3:05 PM" (in the machine's own short time format).</summary>
        public static string Time(long ms)
        {
            return Local(ms).ToString("t", CultureInfo.CurrentCulture);
        }

        /// <summary>RFC 822 or ISO 8601; 0 if neither.</summary>
        public static long Parse(string s)
        {
            if (s == null) return 0;
            s = s.Trim();
            if (s.Length == 0) return 0;
            return char.IsDigit(s[0]) && s.Length >= 10 && s[4] == '-' ? Iso(s) : Rfc822(s);
        }

        static int Num(string s, int start, int len)
        {
            return int.Parse(s.Substring(start, len), NumberStyles.None, CultureInfo.InvariantCulture);
        }

        static long Iso(string s)
        {
            try
            {
                int y = Num(s, 0, 4), mo = Num(s, 5, 2), d = Num(s, 8, 2);
                int h = 0, mi = 0, sec = 0, i = 10;
                if (s.Length > 15 && (s[10] == 'T' || s[10] == ' '))
                {
                    h = Num(s, 11, 2);
                    mi = Num(s, 14, 2);
                    i = 16;
                    if (s.Length > 18 && s[16] == ':')
                    {
                        sec = Num(s, 17, 2);
                        i = 19;
                    }
                    while (i < s.Length && (s[i] == '.' || char.IsDigit(s[i]))) i++;
                }
                int offset = i < s.Length ? Zone(s.Substring(i).Trim()) : 0;
                return Utc(y, mo, d, h, mi, sec, offset);
            }
            catch (Exception)
            {
                return 0;
            }
        }

        static long Rfc822(string s)
        {
            try
            {
                int comma = s.IndexOf(',');
                string[] p = s.Substring(comma + 1).Trim().Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
                if (p.Length < 4) return 0;
                int d = int.Parse(p[0], CultureInfo.InvariantCulture);
                int mo = Month(p[1]);
                int y = int.Parse(p[2], CultureInfo.InvariantCulture);
                if (y < 100) y += 2000;
                string[] hms = p[3].Split(':');
                int h = int.Parse(hms[0], CultureInfo.InvariantCulture);
                int mi = hms.Length > 1 ? int.Parse(hms[1], CultureInfo.InvariantCulture) : 0;
                int sec = hms.Length > 2 ? int.Parse(hms[2], CultureInfo.InvariantCulture) : 0;
                int offset = p.Length > 4 ? Zone(p[4]) : 0;
                if (mo < 1) return 0;
                return Utc(y, mo, d, h, mi, sec, offset);
            }
            catch (Exception)
            {
                return 0;
            }
        }

        static int Month(string m)
        {
            string l = m.ToLowerInvariant();
            for (int i = 0; i < 12; i++) if (l.StartsWith(MONTHS[i], StringComparison.Ordinal)) return i + 1;
            return -1;
        }

        static int Zone(string z)
        {
            if (z.Length == 0) return 0;
            char c = z[0];
            if (c == '+' || c == '-')
            {
                string digits = z.Substring(1).Replace(":", "");
                if (digits.Length < 2) return 0;
                int hh = Num(digits, 0, 2);
                int mm = digits.Length >= 4 ? Num(digits, 2, 2) : 0;
                int m = hh * 60 + mm;
                return c == '-' ? -m : m;
            }
            switch (z.ToUpperInvariant())
            {
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

        public static long Utc(int y, int mo, int d, int h, int mi, int sec, int offsetMinutes)
        {
            long days = Days(y, mo, d);
            return ((days * 86400L) + h * 3600L + mi * 60L + sec - offsetMinutes * 60L) * 1000L;
        }

        /// <summary>Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm).</summary>
        public static long Days(int y, int m, int d)
        {
            y -= m <= 2 ? 1 : 0;
            long era = (y >= 0 ? y : y - 399) / 400;
            long yoe = y - era * 400;
            long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
            long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
            return era * 146097 + doe - 719468;
        }

        /// <summary>"Just now", "12m ago", "3h ago", "Yesterday", "2d ago"; from a week on "Sep 5" or "Sep 5, 2025".</summary>
        public static string Ago(long when, long now)
        {
            if (when <= 0) return "";
            long m = (now - when) / 60000;
            if (m < 2) return "Just now";
            if (m < 60) return m + "m ago";
            long h = m / 60;
            if (h < 24) return h + "h ago";
            long d = h / 24;
            if (d < 7) return d == 1 ? "Yesterday" : d + "d ago";
            DateTime w = Local(when), n = Local(now);
            string month = MONTHS[w.Month - 1];
            string day = char.ToUpperInvariant(month[0]) + month.Substring(1) + " " + w.Day;
            return w.Year == n.Year ? day : day + ", " + w.Year;
        }

        /// <summary>Reading time for this many words at ~230 words a minute: "4 min read".</summary>
        public static string ReadingTime(int words)
        {
            int min = Math.Max(1, (int)Math.Round(words / 230.0, MidpointRounding.AwayFromZero));
            return min + " min read";
        }
    }
}
