using System;
using System.Collections.Generic;

namespace Broadsheet
{
    /// <summary>
    /// Sunrise and sunset with no network and no location: the place comes from the machine's time
    /// zone, the times from the US Naval Observatory's sunrise equation (zenith 90.833°). A port
    /// of the TV app's Sun.java, keyed by Windows time zone ids.
    /// </summary>
    static class Sun
    {
        /// <summary>Between sunset and sunrise, where the time zone says this machine is.</summary>
        public static bool Dark(long now, TimeZoneInfo tz)
        {
            double[] at = Place(tz);
            double[] rs = Times(now, tz, at[0], at[1]);
            if (double.IsNaN(rs[0]) || double.IsNaN(rs[1]))
                return (at[0] >= 0) == IsNorthernWinter(now, tz);
            return now < rs[0] || now >= rs[1];
        }

        /// <summary>The next sunrise or sunset after now; six hours on when the sun doesn't cross.</summary>
        public static long NextChange(long now, TimeZoneInfo tz)
        {
            double[] at = Place(tz);
            long best = long.MaxValue;
            for (int day = 0; day <= 1; day++)
            {
                foreach (double t in Times(now + day * 86400000L, tz, at[0], at[1]))
                    if (!double.IsNaN(t) && (long)t > now) best = Math.Min(best, (long)t);
            }
            return best == long.MaxValue ? now + 6 * 3600000L : best;
        }

        /// <summary>The next sunrise (rising) or sunset after now; NaN if none in two days.</summary>
        public static double Next(long now, TimeZoneInfo tz, bool rising)
        {
            double[] at = Place(tz);
            for (int day = 0; day <= 1; day++)
            {
                double t = Times(now + day * 86400000L, tz, at[0], at[1])[rising ? 0 : 1];
                if (!double.IsNaN(t) && (long)t > now) return t;
            }
            return double.NaN;
        }

        static readonly Dictionary<string, double[]> PLACES = new Dictionary<string, double[]>
        {
            { "Eastern Standard Time", new[] { 40.7, -74.0 } },
            { "US Eastern Standard Time", new[] { 39.8, -86.2 } },
            { "Central Standard Time", new[] { 41.9, -87.6 } },
            { "Canada Central Standard Time", new[] { 50.4, -104.6 } },
            { "Mountain Standard Time", new[] { 39.7, -105.0 } },
            { "US Mountain Standard Time", new[] { 33.4, -112.1 } },
            { "Pacific Standard Time", new[] { 34.1, -118.2 } },
            { "Alaskan Standard Time", new[] { 61.2, -149.9 } },
            { "Hawaiian Standard Time", new[] { 21.3, -157.9 } },
            { "Atlantic Standard Time", new[] { 44.6, -63.6 } },
            { "Newfoundland Standard Time", new[] { 47.6, -52.7 } },
            { "Central Standard Time (Mexico)", new[] { 19.4, -99.1 } },
            { "SA Pacific Standard Time", new[] { 4.7, -74.1 } },
            { "Pacific SA Standard Time", new[] { -33.4, -70.7 } },
            { "E. South America Standard Time", new[] { -23.5, -46.6 } },
            { "Argentina Standard Time", new[] { -34.6, -58.4 } },
            { "GMT Standard Time", new[] { 51.5, -0.1 } },
            { "W. Europe Standard Time", new[] { 52.5, 13.4 } },
            { "Romance Standard Time", new[] { 48.9, 2.4 } },
            { "Central Europe Standard Time", new[] { 50.1, 14.4 } },
            { "Central European Standard Time", new[] { 52.2, 21.0 } },
            { "FLE Standard Time", new[] { 60.2, 24.9 } },
            { "GTB Standard Time", new[] { 38.0, 23.7 } },
            { "Turkey Standard Time", new[] { 41.0, 29.0 } },
            { "Russian Standard Time", new[] { 55.8, 37.6 } },
            { "Egypt Standard Time", new[] { 30.0, 31.2 } },
            { "W. Central Africa Standard Time", new[] { 6.5, 3.4 } },
            { "South Africa Standard Time", new[] { -26.2, 28.0 } },
            { "E. Africa Standard Time", new[] { -1.3, 36.8 } },
            { "Arabian Standard Time", new[] { 25.2, 55.3 } },
            { "Pakistan Standard Time", new[] { 24.9, 67.0 } },
            { "India Standard Time", new[] { 19.1, 72.9 } },
            { "Bangladesh Standard Time", new[] { 23.8, 90.4 } },
            { "SE Asia Standard Time", new[] { 13.8, 100.5 } },
            { "Singapore Standard Time", new[] { 1.3, 103.8 } },
            { "China Standard Time", new[] { 31.2, 121.5 } },
            { "Taipei Standard Time", new[] { 25.0, 121.6 } },
            { "Korea Standard Time", new[] { 37.6, 127.0 } },
            { "Tokyo Standard Time", new[] { 35.7, 139.7 } },
            { "W. Australia Standard Time", new[] { -31.95, 115.9 } },
            { "Cen. Australia Standard Time", new[] { -34.9, 138.6 } },
            { "E. Australia Standard Time", new[] { -27.5, 153.0 } },
            { "AUS Eastern Standard Time", new[] { -33.9, 151.2 } },
            { "New Zealand Standard Time", new[] { -36.8, 174.8 } },
        };

        /// <summary>{latitude, longitude}; an unlisted zone gets 40° north and its UTC offset's longitude.</summary>
        public static double[] Place(TimeZoneInfo tz)
        {
            double[] p;
            if (PLACES.TryGetValue(tz.Id, out p)) return p;
            return new[] { 40.0, tz.BaseUtcOffset.TotalHours * 15.0 };
        }

        /// <summary>Sunrise and sunset (epoch ms) on the local day containing now; NaN on a polar day or night.</summary>
        public static double[] Times(long now, TimeZoneInfo tz, double lat, double lon)
        {
            DateTime utc = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddMilliseconds(now);
            DateTime local = TimeZoneInfo.ConvertTimeFromUtc(utc, tz);
            int dayOfYear = local.DayOfYear;
            DateTime noon = new DateTime(local.Year, local.Month, local.Day, 12, 0, 0, DateTimeKind.Unspecified);
            long localNoon = Ms(TimeZoneInfo.ConvertTimeToUtc(noon, tz));
            long utcMidnight = Ms(new DateTime(local.Year, local.Month, local.Day, 0, 0, 0, DateTimeKind.Utc));
            double rise = HourUtc(dayOfYear, lat, lon, true);
            double set = HourUtc(dayOfYear, lat, lon, false);
            return new[] { NearNoon(utcMidnight, rise, localNoon), NearNoon(utcMidnight, set, localNoon) };
        }

        static long Ms(DateTime utc)
        {
            return (long)(utc - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
        }

        static double NearNoon(long utcMidnight, double hour, long localNoon)
        {
            if (double.IsNaN(hour)) return hour;
            double t = utcMidnight + hour * 3600000.0;
            while (t - localNoon > 12 * 3600000.0) t -= 24 * 3600000.0;
            while (localNoon - t > 12 * 3600000.0) t += 24 * 3600000.0;
            return t;
        }

        /// <summary>Hour of day (UTC) of sunrise or sunset; NaN if the sun doesn't cross the horizon.</summary>
        public static double HourUtc(int dayOfYear, double lat, double lon, bool rising)
        {
            double lngHour = lon / 15.0;
            double t = dayOfYear + ((rising ? 6 : 18) - lngHour) / 24.0;
            double m = 0.9856 * t - 3.289;
            double l = Norm360(m + 1.916 * Sin(m) + 0.020 * Sin(2 * m) + 282.634);
            double ra = Norm360(Deg(Math.Atan(0.91764 * Tan(l))));
            ra = (ra + Math.Floor(l / 90) * 90 - Math.Floor(ra / 90) * 90) / 15.0;
            double sinDec = 0.39782 * Sin(l);
            double cosDec = Math.Cos(Math.Asin(sinDec));
            double cosH = (Cos(90.833) - sinDec * Sin(lat)) / (cosDec * Cos(lat));
            if (cosH > 1 || cosH < -1) return double.NaN;
            double h = (rising ? 360 - Deg(Math.Acos(cosH)) : Deg(Math.Acos(cosH))) / 15.0;
            double localT = h + ra - 0.06571 * t - 6.622;
            return ((localT - lngHour) % 24 + 24) % 24;
        }

        static bool IsNorthernWinter(long now, TimeZoneInfo tz)
        {
            DateTime local = TimeZoneInfo.ConvertTimeFromUtc(new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddMilliseconds(now), tz);
            return local.Month <= 3 || local.Month >= 9;
        }

        static double Norm360(double d) { return (d % 360 + 360) % 360; }
        static double Rad(double deg) { return deg * Math.PI / 180.0; }
        static double Deg(double rad) { return rad * 180.0 / Math.PI; }
        static double Sin(double deg) { return Math.Sin(Rad(deg)); }
        static double Cos(double deg) { return Math.Cos(Rad(deg)); }
        static double Tan(double deg) { return Math.Tan(Rad(deg)); }
    }
}
