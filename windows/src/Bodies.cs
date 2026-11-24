using System;
using System.Collections.Generic;
using System.IO;

namespace Broadsheet
{
    /// <summary>
    /// Stories kept on this computer (opened, or fetched ahead because the reader was heading for
    /// them), so they open at once, even while the Mac sleeps. One file per story in
    /// %LOCALAPPDATA%\Broadsheet\bodies: the relay's article JSON, the opening only until the rest has
    /// been read. A few megabytes; the least recently opened go first, nothing stays longer than two
    /// weeks. Call from background threads; one lock keeps them in order.
    /// </summary>
    static class Bodies  // feature: reader.body_cache
    {
        const long BUDGET = 8L * 1024 * 1024;
        const int MAX_FILES = 400;
        static readonly TimeSpan MAX_AGE = TimeSpan.FromDays(14);
        static readonly object gate = new object();
        static int writes;

        static string File_(string id)
        {
            char[] c = id.ToCharArray();
            for (int i = 0; i < c.Length; i++) if (!char.IsLetterOrDigit(c[i]) && c[i] != '-' && c[i] != '_') c[i] = '_';
            return Path.Combine(Paths.Bodies, new string(c) + ".json");
        }

        /// <summary>The story as kept, or null. Counts as a use (it stays longer).</summary>
        public static JObj Get(string id)
        {
            lock (gate)
            {
                JObj o = Read(id);
                if (o != null)
                {
                    try { File.SetLastWriteTimeUtc(File_(id), DateTime.UtcNow); }
                    catch (Exception) { }
                }
                return o;
            }
        }

        public static bool Has(string id)
        {
            lock (gate) return File.Exists(File_(id));
        }

        /// <summary>Keeps a story the relay sent with its body (a summary isn't worth keeping).</summary>
        public static void Put(JObj data)
        {
            if (data == null || data.Obj("body") == null || data.Opt("completeness") == "summary") return;
            string id = data.Opt("id");
            if (id.Length == 0) return;
            lock (gate)
            {
                JObj old = Read(id);
                // A head doesn't replace a story kept whole.
                if (old != null && NextBlock(old) < 0 && NextBlock(data) >= 0 && Blocks(old) >= Blocks(data)) return;
                Write(id, data);
            }
        }

        /// <summary>Adds the rest of a story to its opening; returns the whole, or null if the opening is gone.</summary>
        public static JObj Merge(string id, JObj rest)
        {
            JObj more = rest == null ? null : rest.Obj("body");
            if (more == null) return null;
            lock (gate)
            {
                JObj kept = Read(id);
                JObj body = kept == null ? null : kept.Obj("body");
                if (body == null || body.Int("next_block", -1) != more.Int("from_block", -2)) return null; // not the part that follows
                List<object> blocks = body.Arr("blocks");
                if (blocks == null) body.Put("blocks", blocks = new List<object>());
                List<object> add = more.Arr("blocks");
                if (add != null) blocks.AddRange(add);
                body.Put("next_block", more.IsNull("next_block") ? null : more["next_block"]);
                Write(id, kept);
                return kept;
            }
        }

        /// <summary>Where the rest of a kept story starts, or -1 when it's whole.</summary>
        public static int NextBlock(JObj data)
        {
            JObj body = data == null ? null : data.Obj("body");
            return body == null || body.IsNull("next_block") ? -1 : body.Int("next_block", -1);
        }

        static int Blocks(JObj data)
        {
            JObj body = data.Obj("body");
            List<object> a = body == null ? null : body.Arr("blocks");
            return a == null ? 0 : a.Count;
        }

        static JObj Read(string id)
        {
            string text = Paths.ReadOrNull(File_(id));
            if (text == null) return null;
            try { return Json.ParseObject(text); }
            catch (FormatException)
            {
                try { File.Delete(File_(id)); } catch (Exception) { }
                return null;
            }
        }

        static void Write(string id, JObj o)
        {
            try { Paths.WriteAtomic(File_(id), o.ToString()); }
            catch (Exception) { return; }
            if (++writes % 50 == 0) Prune();
        }

        /// <summary>Within budget: drops anything over two weeks old, then the least recently used.</summary>
        public static void Prune()
        {
            lock (gate)
            {
                try
                {
                    FileInfo[] files = new DirectoryInfo(Paths.Bodies).GetFiles();
                    Array.Sort(files, (a, b) => b.LastWriteTimeUtc.CompareTo(a.LastWriteTimeUtc));
                    long total = 0;
                    int n = 0;
                    DateTime now = DateTime.UtcNow;
                    foreach (FileInfo f in files)
                    {
                        total += f.Length;
                        n++;
                        if (now - f.LastWriteTimeUtc > MAX_AGE || total > BUDGET || n > MAX_FILES)
                        {
                            try { f.Delete(); } catch (Exception) { }
                        }
                    }
                }
                catch (Exception) { }
            }
        }
    }
}
