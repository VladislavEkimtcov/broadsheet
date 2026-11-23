using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace Broadsheet
{
    /// <summary>
    /// Where the app keeps things: only under the user profile. Settings, the reading library and
    /// relay.conf roam in %APPDATA%\Broadsheet; the page and picture caches stay in
    /// %LOCALAPPDATA%\Broadsheet. Every write goes to a .part file first and is then swapped in.
    /// </summary>
    static class Paths
    {
        public static readonly string Roaming = Dir(Environment.SpecialFolder.ApplicationData);
        public static readonly string Local = Dir(Environment.SpecialFolder.LocalApplicationData);
        public static readonly string Cache = Sub(Local, "cache");
        public static readonly string Images = Sub(Local, "img");

        static string Dir(Environment.SpecialFolder f)
        {
            return Sub(Environment.GetFolderPath(f), "Broadsheet");
        }

        static string Sub(string parent, string name)
        {
            string p = Path.Combine(parent, name);
            try { Directory.CreateDirectory(p); } catch (Exception) { }
            return p;
        }

        static readonly System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();

        /// <summary>A line in %LOCALAPPDATA%\Broadsheet\broadsheet.log (startup timings, failures); kept small.</summary>
        public static void Log(string line)
        {
            try
            {
                string f = Path.Combine(Local, "broadsheet.log");
                if (File.Exists(f) && new FileInfo(f).Length > 256 * 1024) File.Delete(f);
                File.AppendAllText(f, DateTime.Now.ToString("HH:mm:ss.fff") + " +" + clock.ElapsedMilliseconds + "ms  " + line + "\r\n");
            }
            catch (Exception) { }
        }

        public static void WriteAtomic(string path, string text)
        {
            string tmp = path + ".part";
            File.WriteAllText(tmp, text, new UTF8Encoding(false));
            if (File.Exists(path)) File.Replace(tmp, path, null);
            else File.Move(tmp, path);
        }

        public static string ReadOrNull(string path)
        {
            try { return File.Exists(path) ? File.ReadAllText(path, Encoding.UTF8) : null; }
            catch (Exception) { return null; }
        }
    }

    /// <summary>The app's settings: key=value lines in settings.conf (SharedPreferences on the TV).</summary>
    sealed class Prefs
    {
        readonly string file = Path.Combine(Paths.Roaming, "settings.conf");
        readonly SortedDictionary<string, string> map = new SortedDictionary<string, string>(StringComparer.Ordinal);

        public Prefs()
        {
            string text = Paths.ReadOrNull(file);
            if (text == null) return;
            foreach (string raw in text.Split('\n'))
            {
                string line = raw.Trim();
                int eq = line.IndexOf('=');
                if (line.StartsWith("#") || eq <= 0) continue;
                map[line.Substring(0, eq).Trim()] = line.Substring(eq + 1).Trim();
            }
        }

        public bool Has(string key) { return map.ContainsKey(key); }

        public string Get(string key, string fallback)
        {
            string v;
            return map.TryGetValue(key, out v) ? v : fallback;
        }

        public int GetInt(string key, int fallback)
        {
            int n;
            return int.TryParse(Get(key, null), NumberStyles.Integer, CultureInfo.InvariantCulture, out n) ? n : fallback;
        }

        public long GetLong(string key, long fallback)
        {
            long n;
            return long.TryParse(Get(key, null), NumberStyles.Integer, CultureInfo.InvariantCulture, out n) ? n : fallback;
        }

        public void Set(string key, string value)
        {
            if (value == null) map.Remove(key);
            else map[key] = value;
            Save();
        }

        public void Set(string key, long value) { Set(key, value.ToString(CultureInfo.InvariantCulture)); }

        void Save()
        {
            StringBuilder b = new StringBuilder("# Broadsheet for Windows\n");
            foreach (KeyValuePair<string, string> kv in map) b.Append(kv.Key).Append('=').Append(kv.Value).Append('\n');
            try { Paths.WriteAtomic(file, b.ToString()); } catch (Exception) { }
        }
    }

    /// <summary>
    /// What this machine remembers about reading, kept here only (never sent to the Mac): the stories
    /// opened and how far into each, the likes and dislikes sent from here, and the last story for
    /// "Continue reading". The newest few hundred of each, in one small JSON file.
    /// </summary>
    sealed class Library
    {
        const int KEEP_READ = 1500, KEEP_REACTIONS = 400;

        readonly string file = Path.Combine(Paths.Roaming, "library.json");  // feature: library.local
        readonly Ordered<double> read = new Ordered<double>();
        readonly Ordered<string> reactions = new Ordered<string>();
        JObj last;
        bool dirty;

        public Library() { Load(); }

        public bool IsRead(string id) { return read.Has(id); }

        public int ReadCount { get { return read.Count; } }

        public double Position(string id)  // feature: reader.resume
        {
            double f;
            return read.TryGet(id, out f) ? f : 0;
        }

        public void SetPosition(Story s, double fraction)
        {
            read.Put(s.Id, Math.Max(0, Math.Min(1, fraction)));
            read.Trim(KEEP_READ);
            last = new JObj().Put("story", s.Json).Put("at", (double)Dates.NowMs());
            dirty = true;
        }

        public void MarkUnread(string id)
        {
            read.Remove(id);
            Story s = last == null ? null : Story.From(last.Obj("story"));
            if (s != null && s.Id == id) last = null;
            dirty = true;
        }

        public void MarkRead(string id)
        {
            double f;
            read.Put(id, read.TryGet(id, out f) ? f : 1);
            read.Trim(KEEP_READ);
            dirty = true;
        }

        /// <summary>The last story read here when left unfinished in the past week, or null.</summary>
        public Story Unfinished()
        {
            if (last == null || Dates.NowMs() - last.Long("at") > 7 * 86400000L) return null;
            Story s = Story.From(last.Obj("story"));
            if (s == null) return null;
            double f = Position(s.Id);
            return f > 0.04 && f < 0.9 ? s : null;
        }

        public string Reaction(string id)
        {
            string r;
            return reactions.TryGet(id, out r) ? r : null;
        }

        public void SetReaction(string id, string reaction)
        {
            if (reaction == null) reactions.Remove(id);
            else reactions.Put(id, reaction);
            reactions.Trim(KEEP_REACTIONS);
            dirty = true;
        }

        void Load()
        {
            string text = Paths.ReadOrNull(file);
            if (text == null) return;
            try
            {
                JObj o = Json.ParseObject(text);
                JObj r = o.Obj("read");
                if (r != null) foreach (KeyValuePair<string, object> kv in r) if (kv.Value is double) read.Put(kv.Key, (double)kv.Value);
                JObj x = o.Obj("reactions");
                if (x != null) foreach (KeyValuePair<string, object> kv in x) if (kv.Value is string) reactions.Put(kv.Key, (string)kv.Value);
                last = o.Obj("last");
            }
            catch (FormatException)
            {
                // start afresh
            }
        }

        /// <summary>Writes the file if anything changed; cheap to call often.</summary>
        public void Save()
        {
            if (!dirty) return;
            dirty = false;
            JObj r = new JObj();
            foreach (KeyValuePair<string, double> kv in read.Items) r.Put(kv.Key, Math.Round(kv.Value * 1000) / 1000.0);
            JObj x = new JObj();
            foreach (KeyValuePair<string, string> kv in reactions.Items) x.Put(kv.Key, kv.Value);
            JObj o = new JObj().Put("read", r).Put("reactions", x);
            if (last != null) o.Put("last", last);
            try { Paths.WriteAtomic(file, o.ToString()); }
            catch (Exception) { dirty = true; }
        }

        /// <summary>A map that remembers the order keys were last put in, oldest first.</summary>
        sealed class Ordered<T>
        {
            readonly LinkedList<KeyValuePair<string, T>> order = new LinkedList<KeyValuePair<string, T>>();
            readonly Dictionary<string, LinkedListNode<KeyValuePair<string, T>>> map = new Dictionary<string, LinkedListNode<KeyValuePair<string, T>>>();

            public int Count { get { return map.Count; } }

            public bool Has(string k) { return map.ContainsKey(k); }

            public bool TryGet(string k, out T v)
            {
                LinkedListNode<KeyValuePair<string, T>> n;
                if (map.TryGetValue(k, out n)) { v = n.Value.Value; return true; }
                v = default(T);
                return false;
            }

            public void Put(string k, T v)
            {
                Remove(k);
                map[k] = order.AddLast(new KeyValuePair<string, T>(k, v));
            }

            public void Remove(string k)
            {
                LinkedListNode<KeyValuePair<string, T>> n;
                if (!map.TryGetValue(k, out n)) return;
                order.Remove(n);
                map.Remove(k);
            }

            public void Trim(int keep)
            {
                while (map.Count > keep) Remove(order.First.Value.Key);
            }

            public IEnumerable<KeyValuePair<string, T>> Items { get { return order; } }
        }
    }
}
