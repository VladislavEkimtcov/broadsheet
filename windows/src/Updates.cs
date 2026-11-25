using System;

namespace Broadsheet
{
    /// <summary>
    /// Pure update-tag and semantic-version helpers shared with spec/cases/update.json.
    /// Mirrors android/src/io/github/vladislavekimtcov/broadsheet/Updates.java.
    /// </summary>
    static class Updates
    {
        const string TagMarker = "/releases/tag/";

        /// <summary>
        /// Extracts a clean numeric version ("0.6.0") from a GitHub /releases/latest redirect
        /// Location header, or null for non-release URLs and pre-release tags.
        /// </summary>
        public static string TagFromLocation(string location)
        {
            if (string.IsNullOrEmpty(location)) return null;
            int idx = location.IndexOf(TagMarker, StringComparison.Ordinal);
            if (idx < 0) return null;
            string tag = location.Substring(idx + TagMarker.Length);
            int q = tag.IndexOf('?');
            if (q >= 0) tag = tag.Substring(0, q);
            int h = tag.IndexOf('#');
            if (h >= 0) tag = tag.Substring(0, h);
            tag = tag.TrimEnd('/');
            int slash = tag.IndexOf('/');
            if (slash >= 0) tag = tag.Substring(0, slash);
            if (tag.StartsWith("v", StringComparison.OrdinalIgnoreCase)) tag = tag.Substring(1);
            return ParseVersion(tag) != null ? tag : null;
        }

        /// <summary>True when remote and local are valid dotted versions and remote &gt; local.</summary>
        public static bool IsNewer(string remote, string local)
        {
            int[] r = ParseVersion(remote);
            int[] l = ParseVersion(local);
            if (r == null || l == null) return false;
            for (int i = 0; i < 4; i++)
            {
                if (r[i] != l[i]) return r[i] > l[i];
            }
            return false;
        }

        public static int[] ParseVersion(string s)
        {
            if (s == null) return null;
            s = s.Trim();
            if (s.StartsWith("v", StringComparison.OrdinalIgnoreCase)) s = s.Substring(1);
            if (s.Length == 0) return null;
            string[] parts = s.Split('.');
            if (parts.Length < 2 || parts.Length > 4) return null;
            int[] out_ = new int[4];
            for (int i = 0; i < parts.Length; i++)
            {
                string p = parts[i];
                if (p.Length == 0) return null;
                for (int j = 0; j < p.Length; j++)
                {
                    if (p[j] < '0' || p[j] > '9') return null;
                }
                int n;
                if (!int.TryParse(p, out n)) return null;
                out_[i] = n;
            }
            return out_;
        }
    }
}
