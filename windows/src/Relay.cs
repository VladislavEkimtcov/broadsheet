using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Threading;
using System.Windows.Threading;

namespace Broadsheet
{
    /// <summary>Main thread. <paramref name="error"/> is a short sentence for the screen, or null.</summary>
    delegate void Done<T>(T value, string error);

    /// <summary>
    /// Talks to Apple News Relay (github.com/VladislavEkimtcov/apple-news-relay), the Mac-side server
    /// that turns the Apple News app into a JSON API. Everything the app shows comes from it.
    ///
    /// Where the relay is: relay.conf in %APPDATA%\Broadsheet (url= and optional token=), else the
    /// last address that worked, else a look for port 8787 on this machine's own subnets. (Windows
    /// has no Bonjour in the box, so the TV app's first choice is left out.)
    /// </summary>
    sealed class Relay
    {
        public const int PORT = 8787;
        const int MAX_JSON = 8 * 1024 * 1024;

        readonly Dispatcher ui;
        readonly Prefs prefs;
        volatile string baseUrl, token;
        volatile string origin = "";

        public Relay(Dispatcher ui, Prefs prefs)
        {
            this.ui = ui;
            this.prefs = prefs;
            ReadConfig();
            if (baseUrl == null)
            {
                baseUrl = prefs.Get("relay_url", null);
                if (baseUrl != null) origin = "last known";
            }
        }

        /// <summary>"10.0.0.4:8787" for the rail's footer, or null before the relay is known.</summary>
        public string Where
        {
            get
            {
                string b = baseUrl;
                if (b == null) return null;
                return b.Replace("http://", "").Replace("https://", "");
            }
        }

        public string Origin { get { return origin; } }

        public bool Known { get { return baseUrl != null; } }

        public static string ConfigPath { get { return Path.Combine(Paths.Roaming, "relay.conf"); } }

        void ReadConfig()
        {
            string text = Paths.ReadOrNull(ConfigPath);
            if (text == null) return;
            foreach (string raw in text.Split('\n'))
            {
                string line = raw.Trim();
                int eq = line.IndexOf('=');
                if (line.StartsWith("#") || eq <= 0) continue;
                string k = line.Substring(0, eq).Trim().ToLowerInvariant();
                string v = line.Substring(eq + 1).Trim();
                if (k == "url" && v.StartsWith("http"))
                {
                    baseUrl = v.TrimEnd('/');
                    origin = "relay.conf";
                }
                else if (k == "token" && v.Length > 0)
                {
                    token = v;
                }
            }
        }

        void Post<T>(Done<T> done, T value, string error)
        {
            ui.BeginInvoke(new Action(() => done(value, error)));
        }

        static void Run(Action a)
        {
            ThreadPool.QueueUserWorkItem(_ => a());
        }

        // ------------------------------------------------------------------ finding it

        /// <summary>
        /// Knocks on port 8787 of every address in this machine's subnets (a /24 each at most), 32 at a
        /// time, and asks whatever answers whether it is the relay. Gives up after 10 s.
        /// </summary>
        public void Discover(Done<string> done)
        {
            int finished = 0;
            Action<string, string> found = (url, how) =>
            {
                if (Interlocked.CompareExchange(ref finished, 1, 0) != 0) return;
                baseUrl = url;
                origin = how;
                ui.BeginInvoke(new Action(() => prefs.Set("relay_url", url)));
                Post(done, url, null);
            };
            Thread scan = new Thread(() =>
            {
                List<IPAddress> hosts = Neighbours();
                Queue<IPAddress> queue = new Queue<IPAddress>(hosts);
                List<Thread> workers = new List<Thread>();
                for (int w = 0; w < 32; w++)
                {
                    Thread t = new Thread(() =>
                    {
                        while (Volatile.Read(ref finished) == 0)
                        {
                            IPAddress a;
                            lock (queue)
                            {
                                if (queue.Count == 0) return;
                                a = queue.Dequeue();
                            }
                            if (!PortOpen(a, PORT, 400)) continue;
                            string url = "http://" + a + ":" + PORT;
                            try
                            {
                                // Even a 401 or 403 from the relay names it, so a token doesn't hide it.
                                if (Net.Request("GET", url + "/v1", token, 3000, 64 * 1024).Text().Contains("apple-news-relay"))
                                    found(url, "found on the network");
                            }
                            catch (Exception)
                            {
                                // something else on that port
                            }
                        }
                    });
                    t.IsBackground = true;
                    t.Start();
                    workers.Add(t);
                }
            });
            scan.IsBackground = true;
            scan.Name = "scan";
            scan.Start();
            DispatcherTimer timeout = new DispatcherTimer(DispatcherPriority.Normal, ui);
            timeout.Interval = TimeSpan.FromSeconds(10);
            timeout.Tick += (s, e) =>
            {
                timeout.Stop();
                if (Interlocked.CompareExchange(ref finished, 1, 0) == 0) done(null, "No Apple News Relay found on this network.");
            };
            timeout.Start();
        }

        static bool PortOpen(IPAddress a, int port, int ms)
        {
            using (Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp))
            {
                try
                {
                    IAsyncResult r = s.BeginConnect(a, port, null, null);
                    if (!r.AsyncWaitHandle.WaitOne(ms)) return false;
                    s.EndConnect(r);
                    return true;
                }
                catch (Exception)
                {
                    return false;
                }
            }
        }

        /// <summary>This machine's neighbours on its Ethernet and Wi-Fi subnets (VPN and virtual adapters skipped).</summary>
        public static List<IPAddress> Neighbours()
        {
            List<IPAddress> out_ = new List<IPAddress>();
            HashSet<uint> seen = new HashSet<uint>();
            NetworkInterface[] all;
            try { all = NetworkInterface.GetAllNetworkInterfaces(); }
            catch (Exception) { return out_; }
            foreach (NetworkInterface ni in all)
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                NetworkInterfaceType type = ni.NetworkInterfaceType;
                if (type == NetworkInterfaceType.Loopback || type == NetworkInterfaceType.Tunnel || type == NetworkInterfaceType.Ppp) continue;
                string d = (ni.Description + " " + ni.Name).ToLowerInvariant();
                if (d.Contains("virtual") || d.Contains("vpn") || d.Contains("tap-") || d.Contains("wireguard")
                    || d.Contains("tailscale") || d.Contains("zerotier") || d.Contains("hyper-v") || d.Contains("vethernet")) continue;
                IPInterfaceProperties props;
                try { props = ni.GetIPProperties(); }
                catch (Exception) { continue; }
                foreach (UnicastIPAddressInformation ua in props.UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    byte[] b = ua.Address.GetAddressBytes();
                    bool siteLocal = b[0] == 10 || (b[0] == 172 && (b[1] & 0xF0) == 16) || (b[0] == 192 && b[1] == 168);
                    if (!siteLocal) continue;
                    int prefix = 24;
                    try
                    {
                        if (ua.IPv4Mask != null)
                        {
                            byte[] m = ua.IPv4Mask.GetAddressBytes();
                            int bits = 0;
                            foreach (byte x in m) for (int i = 7; i >= 0; i--) if ((x >> i & 1) == 1) bits++;
                            prefix = Math.Max(bits, 24);
                        }
                    }
                    catch (Exception) { }
                    if (prefix > 30) continue;
                    uint me = (uint)(b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3]);
                    uint size = 1u << (32 - prefix);
                    uint net = me & ~(size - 1);
                    for (uint i = 1; i < size - 1; i++)
                    {
                        uint h = net | i;
                        if (h == me || !seen.Add(h)) continue;
                        out_.Add(new IPAddress(new[] { (byte)(h >> 24), (byte)(h >> 16), (byte)(h >> 8), (byte)h }));
                    }
                }
            }
            return out_;
        }

        // ------------------------------------------------------------------ calls

        JObj Call(string method, string path, int timeoutMs)
        {
            string b = baseUrl;
            if (b == null) throw new IOException("Apple News Relay hasn't been found yet.");
            Net.Response r;
            try
            {
                r = Net.Request(method, b + path, token, timeoutMs, MAX_JSON);
            }
            catch (Exception e)
            {
                throw new IOException("Can't reach Apple News Relay at " + Where + ". Is the Mac awake?", e);
            }
            if (r.Code == 401 || r.Code == 403) throw new IOException("The relay at " + Where + " turned this computer away (HTTP " + r.Code + ").");
            if (r.Code >= 400)
            {
                string detail = null;
                try { detail = Json.ParseObject(r.Text()).Str("detail"); }
                catch (FormatException) { }
                throw new IOException(detail ?? "The relay answered HTTP " + r.Code + ".");
            }
            try
            {
                return Json.ParseObject(r.Text());
            }
            catch (FormatException e)
            {
                throw new IOException("The relay sent something that isn't JSON.", e);
            }
        }

        static string Message(Exception e)
        {
            return e is IOException || e is FormatException ? e.Message : "Something went wrong: " + e.Message;
        }

        /// <summary>The front page: Today's sections from Apple News, plus Latest and Saved from the archive.</summary>
        public void Front(Done<List<Section>> done)
        {
            Run(() =>
            {
                try
                {
                    JObj sections = Call("GET", "/v1/sections?per_section=30", 20000);
                    JObj latest = Call("GET", "/v1/articles?limit=45", 20000);
                    JObj saved = Call("GET", "/v1/saved?limit=45&fetch_missing=true", 20000);
                    Save("front.json", new JObj().Put("sections", sections).Put("latest", latest).Put("saved", saved));
                    Post(done, Assemble(sections, latest, saved), null);
                }
                catch (Exception e)
                {
                    Post(done, null, Message(e));
                }
            });
        }

        /// <summary>The last front page that loaded, for an instant start; null if none.</summary>
        public List<Section> CachedFront()
        {
            try
            {
                JObj o = Load("front.json");
                if (o == null) return null;
                return Assemble(o.Obj("sections"), o.Obj("latest"), o.Obj("saved"));
            }
            catch (Exception)
            {
                return null;
            }
        }

        public long CachedFrontTime()
        {
            string f = Path.Combine(Paths.Cache, "front.json");
            if (!File.Exists(f)) return 0;
            return (long)(File.GetLastWriteTimeUtc(f) - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
        }

        static List<Section> Assemble(JObj sections, JObj latest, JObj saved)
        {
            List<Section> out_ = new List<Section>();
            List<object> data = sections == null ? null : sections.Arr("data");
            if (data != null)
            {
                foreach (object x in data)
                {
                    JObj s = x as JObj;
                    if (s == null) continue;
                    List<Story> stories = Story.List(s.Arr("articles"));
                    if (stories.Count < 2) continue; // puzzles, empty slots
                    out_.Add(new Section(s.Opt("id"), s.Opt("title"), stories));
                }
            }
            List<Story> newest = Story.List(latest == null ? null : latest.Arr("data"));
            if (newest.Count > 0) out_.Insert(Math.Min(2, out_.Count), new Section("latest", "Latest", newest));
            List<Story> kept = Story.List(saved == null ? null : saved.Arr("data"));
            if (kept.Count > 0) out_.Add(new Section("saved", "Saved Stories", kept));
            return out_;
        }

        /// <summary>One story with its body. wait: seconds the relay may spend getting it from Apple News.</summary>
        public void Article(string id, int wait, Done<Article> done)
        {
            Run(() =>
            {
                try
                {
                    JObj o = Call("GET", "/v1/articles/" + Uri.EscapeDataString(id) + "?wait=" + wait, (wait + 15) * 1000);
                    Post(done, new Article(o.Obj("data") ?? new JObj()), null);
                }
                catch (Exception e)
                {
                    Post<Article>(done, null, Message(e));
                }
            });
        }

        /// <summary>Waits for a relay job; reports its final state ("succeeded", "failed", or still "running").</summary>
        public void Job(string id, int wait, Done<string> done)
        {
            Run(() =>
            {
                try
                {
                    JObj o = Call("GET", "/v1/jobs/" + Uri.EscapeDataString(id) + "?wait=" + wait, (wait + 15) * 1000);
                    JObj d = o.Obj("data");
                    Post(done, d == null ? null : d.Opt("state"), null);
                }
                catch (Exception e)
                {
                    Post<string>(done, null, Message(e));
                }
            });
        }

        /// <summary>Asks the Mac to refresh Today, scrolling this many screens of the feed; job id back.</summary>
        public void RefreshToday(int pages, Done<string> done)
        {
            JobCall("POST", "/v1/today/refresh?pages=" + pages, done);
        }

        /// <summary>Opens a channel or topic (kind: channels or topics) in News on the Mac to load more of it; job id back.</summary>
        public void RefreshTag(string kind, string id, Done<string> done)
        {
            JobCall("POST", "/v1/" + kind + "/" + Uri.EscapeDataString(id) + "/refresh", done);
        }

        void JobCall(string method, string path, Done<string> done)
        {
            Run(() =>
            {
                try
                {
                    JObj d = Call(method, path, 20000).Obj("data");
                    Post(done, d == null ? null : d.Opt("id"), null);
                }
                catch (Exception e)
                {
                    Post<string>(done, null, Message(e));
                }
            });
        }

        /// <summary>Asks the Mac to get a story's body ahead of time, behind anything someone is waiting on.</summary>
        public void Prefetch(string id)
        {
            Run(() =>
            {
                try { Call("POST", "/v1/articles/" + Uri.EscapeDataString(id) + "/fetch?priority=background", 8000); }
                catch (Exception) { /* only a guess; never mind */ }
            });
        }

        /// <summary>A list of stories: a channel's or topic's, or the ones related to a story.</summary>
        public void Stories(string path, Done<List<Story>> done)
        {
            Run(() =>
            {
                try { Post(done, Story.List(Call("GET", path, 20000).Arr("data")), null); }
                catch (Exception e) { Post<List<Story>>(done, null, Message(e)); }
            });
        }

        /// <summary>Saves a story in Apple News on the Mac, or takes it out; answers {saved, reaction}.</summary>
        public void SetSaved(string id, bool saved, Done<JObj> done)
        {
            StoryAction(saved ? "PUT" : "DELETE", "/v1/saved/" + Uri.EscapeDataString(id), done);
        }

        /// <summary>Suggest More ("like") or Fewer ("dislike") Stories Like This; null takes it back.</summary>
        public void SetReaction(string id, string reaction, Done<JObj> done)
        {
            StoryAction(reaction == null ? "DELETE" : "PUT", "/v1/articles/" + Uri.EscapeDataString(id) + "/reaction"
                + (reaction == null ? "" : "?reaction=" + reaction), done);
        }

        void StoryAction(string method, string path, Done<JObj> done)
        {
            Run(() =>
            {
                try
                {
                    string sep = path.Contains("?") ? "&" : "?";
                    JObj job = Call(method, path + sep + "wait=25", 40000).Obj("data") ?? new JObj();
                    for (int i = 0; i < 2 && (job.Opt("state") == "queued" || job.Opt("state") == "running"); i++)
                        job = Call("GET", "/v1/jobs/" + job.Opt("id") + "?wait=25", 40000).Obj("data") ?? new JObj();
                    if (job.Opt("state") == "succeeded") Post(done, job.Obj("result") ?? new JObj(), null);
                    else Post<JObj>(done, null, "Apple News on the Mac: " + job.Opt("error", "it didn't happen"));
                }
                catch (Exception e)
                {
                    Post<JObj>(done, null, Message(e));
                }
            });
        }

        /// <summary>What the Mac is reading in Apple News now (or was in the last half hour): {article, on_screen, seen_at}.</summary>
        public void Handoff(Done<JObj> done)
        {
            Run(() =>
            {
                try { Post(done, Call("GET", "/v1/handoff", 5000).Obj("data"), null); }
                catch (Exception e) { Post<JObj>(done, null, Message(e)); }
            });
        }

        /// <summary>The relay's health (News running, jobs queued), for the options panel's footer.</summary>
        public void Status(Done<JObj> done)
        {
            Run(() =>
            {
                try { Post(done, Call("GET", "/v1/status", 5000), null); }
                catch (Exception e) { Post<JObj>(done, null, Message(e)); }
            });
        }

        // ------------------------------------------------------------------ cache

        static void Save(string name, JObj o)
        {
            try { Paths.WriteAtomic(Path.Combine(Paths.Cache, name), o.ToString()); }
            catch (Exception) { }
        }

        static JObj Load(string name)
        {
            string text = Paths.ReadOrNull(Path.Combine(Paths.Cache, name));
            if (text == null) return null;
            try { return Json.ParseObject(text); }
            catch (FormatException) { return null; }
        }
    }
}
