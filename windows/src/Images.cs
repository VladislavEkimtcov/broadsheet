using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace Broadsheet
{
    interface IImageTarget
    {
        /// <summary>Main thread. fresh: just downloaded or decoded (fade it in), not from memory.</summary>
        void OnImage(BitmapSource b, bool fresh);
    }

    /// <summary>
    /// Pictures from the publishers' CDNs: kept on disk (%LOCALAPPDATA%\Broadsheet\img, pruned to
    /// 150 MB), decoded on four background threads at the size they are shown (never the full
    /// 3000 px original) and held in memory up to a budget. A view that scrolls far away cancels its
    /// picture and lets it go.
    /// </summary>
    sealed class Images
    {
        const int MAX_BYTES = 12 * 1024 * 1024;
        const long MEMORY_BUDGET = 96L * 1024 * 1024;

        sealed class Job
        {
            public IImageTarget Target;
            public string Url, Key;
            public int W, H;
        }

        readonly Dispatcher ui;
        readonly string dir;
        readonly LinkedList<Job> queue = new LinkedList<Job>();
        readonly Dictionary<IImageTarget, Job> pending = new Dictionary<IImageTarget, Job>();
        readonly Dictionary<string, LinkedListNode<KeyValuePair<string, BitmapSource>>> memory = new Dictionary<string, LinkedListNode<KeyValuePair<string, BitmapSource>>>();
        readonly LinkedList<KeyValuePair<string, BitmapSource>> lru = new LinkedList<KeyValuePair<string, BitmapSource>>();
        long memoryBytes;

        public Images(Dispatcher ui)
        {
            this.ui = ui;
            dir = Paths.Images;
            for (int i = 0; i < 4; i++)
            {
                Thread t = new Thread(Work);
                t.IsBackground = true;
                t.Priority = ThreadPriority.BelowNormal;
                t.Name = "img" + i;
                t.Start();
            }
        }

        /// <summary>Asks for url decoded to cover w x h device pixels; the target hears back once.</summary>
        public void Load(IImageTarget target, string url, int w, int h)
        {
            Cancel(target);
            if (url == null || w <= 0 || h <= 0) return;
            // Round the size up a little so a card and the reader's slightly different sizes share it.
            w = (w + 63) / 64 * 64;
            h = (h + 63) / 64 * 64;
            string key = url + "@" + w + "x" + h;
            LinkedListNode<KeyValuePair<string, BitmapSource>> hit;
            if (memory.TryGetValue(key, out hit))
            {
                lru.Remove(hit);
                lru.AddLast(hit);
                target.OnImage(hit.Value.Value, false);
                return;
            }
            Job job = new Job { Target = target, Url = url, Key = key, W = w, H = h };
            pending[target] = job;
            lock (queue)
            {
                queue.AddLast(job);
                Monitor.Pulse(queue);
            }
        }

        public void Cancel(IImageTarget target)
        {
            Job j;
            if (!pending.TryGetValue(target, out j)) return;
            pending.Remove(target);
            lock (queue) queue.Remove(j);
        }

        void Work()
        {
            while (true)
            {
                Job job;
                lock (queue)
                {
                    while (queue.Count == 0) Monitor.Wait(queue);
                    // Newest first: what was just scrolled to matters more than what was skimmed past.
                    job = queue.Last.Value;
                    queue.RemoveLast();
                }
                BitmapSource b = null;
                try
                {
                    string f = Path.Combine(dir, Hash(job.Url));
                    if (!File.Exists(f)) Download(job.Url, f);
                    if (File.Exists(f)) b = Decode(f, job.W, job.H);
                }
                catch (Exception)
                {
                    b = null;
                }
                BitmapSource done = b;
                ui.BeginInvoke(new Action(() => Deliver(job, done)), DispatcherPriority.Background);
            }
        }

        void Deliver(Job job, BitmapSource b)
        {
            if (b != null) Remember(job.Key, b);
            Job current;
            if (!pending.TryGetValue(job.Target, out current) || current != job) return;
            pending.Remove(job.Target);
            if (b != null) job.Target.OnImage(b, true);
        }

        void Remember(string key, BitmapSource b)
        {
            if (memory.ContainsKey(key)) return;
            LinkedListNode<KeyValuePair<string, BitmapSource>> n = lru.AddLast(new KeyValuePair<string, BitmapSource>(key, b));
            memory[key] = n;
            memoryBytes += (long)b.PixelWidth * b.PixelHeight * 4;
            while (memoryBytes > MEMORY_BUDGET && lru.Count > 1)
            {
                KeyValuePair<string, BitmapSource> old = lru.First.Value;
                lru.RemoveFirst();
                memory.Remove(old.Key);
                memoryBytes -= (long)old.Value.PixelWidth * old.Value.PixelHeight * 4;
            }
        }

        /// <summary>Lets go of the pictures in memory (window minimised).</summary>
        public void Trim()
        {
            memory.Clear();
            lru.Clear();
            memoryBytes = 0;
        }

        static string Hash(string url)
        {
            using (SHA1 sha = SHA1.Create())
            {
                byte[] h = sha.ComputeHash(Encoding.UTF8.GetBytes(url));
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < 12; i++) b.Append(h[i].ToString("x2"));
                return b.ToString();
            }
        }

        static void Download(string url, string f)
        {
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create(url);
            r.Timeout = 15000;
            r.ReadWriteTimeout = 20000;
            r.UserAgent = Net.UserAgent;
            string tmp = f + ".part";
            try
            {
                using (HttpWebResponse resp = (HttpWebResponse)r.GetResponse())
                {
                    if (resp.StatusCode != HttpStatusCode.OK) return;
                    string type = resp.ContentType ?? "";
                    if (type.Length > 0 && !type.StartsWith("image") && !type.StartsWith("application/octet")) return;
                    using (Stream s = resp.GetResponseStream())
                    using (FileStream o = File.Create(tmp))
                    {
                        byte[] buf = new byte[16384];
                        int n, total = 0;
                        while ((n = s.Read(buf, 0, buf.Length)) > 0)
                        {
                            o.Write(buf, 0, n);
                            total += n;
                            if (total > MAX_BYTES) throw new IOException("too big");
                        }
                    }
                }
                File.Move(tmp, f);
            }
            finally
            {
                try { if (File.Exists(tmp)) File.Delete(tmp); } catch (Exception) { }
            }
        }

        /// <summary>Decodes at least w x h (so a centre crop fills it), never bigger than the original.</summary>
        static BitmapSource Decode(string f, int w, int h)
        {
            byte[] data = File.ReadAllBytes(f);
            int sw, sh;
            using (MemoryStream m = new MemoryStream(data))
            {
                BitmapDecoder d = BitmapDecoder.Create(m, BitmapCreateOptions.DelayCreation, BitmapCacheOption.None);
                sw = d.Frames[0].PixelWidth;
                sh = d.Frames[0].PixelHeight;
            }
            if (sw <= 0 || sh <= 0) return null;
            double scale = Math.Min(1.0, Math.Max(w / (double)sw, h / (double)sh));
            BitmapImage img = new BitmapImage();
            img.BeginInit();
            img.CacheOption = BitmapCacheOption.OnLoad;
            img.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            img.StreamSource = new MemoryStream(data);
            img.DecodePixelWidth = Math.Max(1, (int)Math.Ceiling(sw * scale));
            img.EndInit();
            BitmapSource b = img;
            if (b.Format != PixelFormats.Bgr32 && b.Format != PixelFormats.Bgra32 && b.Format != PixelFormats.Pbgra32)
                b = new FormatConvertedBitmap(b, PixelFormats.Bgra32, null, 0);
            b.Freeze();
            return b;
        }

        /// <summary>Keeps the picture cache under max bytes, oldest first. Background thread.</summary>
        public static void Prune(long max)
        {
            try
            {
                FileInfo[] files = new DirectoryInfo(Paths.Images).GetFiles();
                Array.Sort(files, (a, b) => a.LastWriteTimeUtc.CompareTo(b.LastWriteTimeUtc));
                long total = 0;
                foreach (FileInfo fi in files) total += fi.Length;
                foreach (FileInfo fi in files)
                {
                    if (total <= max) break;
                    total -= fi.Length;
                    try { fi.Delete(); } catch (Exception) { }
                }
            }
            catch (Exception) { }
        }

        /// <summary>The colour at (x, y) of a decoded picture (Bgr32/Bgra32), for the hero's contrast check.</summary>
        public static Color Pixel(BitmapSource b, int x, int y)
        {
            byte[] px = new byte[4];
            b.CopyPixels(new System.Windows.Int32Rect(x, y, 1, 1), px, 4, 0);
            return Color.FromRgb(px[2], px[1], px[0]);
        }
    }
}
