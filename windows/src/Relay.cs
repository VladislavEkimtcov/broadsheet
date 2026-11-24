using System;
using System.Collections.Concurrent;
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

    /// <summary>A front page from Relay.Front: Unchanged when the relay said 304.</summary>
    sealed class FrontPage
    {
        public readonly List<Section> Sections;
        public readonly bool Unchanged;
        public readonly long Version;

        public FrontPage(List<Section> sections, bool unchanged, long version)
        {
            Sections = sections;
            Unchanged = unchanged;
            Version = version;
        }
    }

    /// <summary>A page of stories and the link to the next one (null at the end).</summary>
    sealed class StoryPage
    {
        public readonly List<Story> Stories;
        public readonly string Next;

        public StoryPage(List<Story> stories, string next)
        {
            Stories = stories;
            Next = next;
        }
    }

    /// <summary>Threads that take work in order: a few for quick calls, others for anything that
    /// waits on the Mac, one for fetching ahead.</summary>
    sealed class Lane
    {
        readonly BlockingCollection<Action> work = new BlockingCollection<Action>();

        public Lane(string name, int threads, ThreadPriority priority)
        {
            for (int i = 0; i < threads; i++)
            {
                Thread t = new Thread(() =>
                {
                    foreach (Action a in work.GetConsumingEnumerable())
                    {
                        try { a(); }
                        catch (Exception) { }
                    }
                });
                t.IsBackground = true;
                t.Priority = priority;
                t.Name = name + i;
                t.Start();
            }
        }

        public void Run(Action a) { work.Add(a); }
    }

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
        /// <summary>Stories per section on the front page: the hero, two briefs and three rows of three.</summary>
        public const int PER_SECTION = 12;
        /// <summary>Stories per further page of a section: five rows.</summary>
        public const int PAGE = 15;
        /// <summary>Text in a story's first part: about two screens (the rest follows as it's read).</summary>
        public const int HEAD_CHARS = 2400;
        /// <summary>What a card needs of a story, so the front page stays small.</summary>
        const string CARD_FIELDS = "title,excerpt,byline,channel,thumbnails,published_at,access,completeness,reading_minutes,colors,url,apple_news_url";

        readonly Dispatcher ui;
        readonly Prefs prefs;
        volatile string baseUrl, token;
        volatile string origin = "";
        volatile string frontEtag;
        /// <summary>The relay predates 0.3 (no /v1/front, no interest lists): the 0.2 calls are used.</summary>
        volatile bool legacy, noInterest;
        /// <summary>Quick calls; anything that waits on the Mac goes to slow, so it can't hold these up.</summary>
        static readonly Lane quick = new Lane("relay", 3, ThreadPriority.Normal);
        static readonly Lane slow = new Lane("relay-wait", 4, ThreadPriority.Normal);
        static readonly Lane warm = new Lane("ahead", 1, ThreadPriority.Lowest);
        /// <summary>The stories kept on this computer: one thing at a time, in order.</summary>
        static readonly Lane disk = new Lane("disk", 1, ThreadPriority.BelowNormal);

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

        /// <summary>"http://10.0.0.4:8787", or null: for the event stream.</summary>
        public string Base { get { return baseUrl; } }

        public string Token { get { return token; } }

        /// <summary>The relay has 0.3's calls (front page in one, interest lists, story parts).</summary>
        public bool Modern { get { return !legacy; } }

        public static string ConfigPath { get { return Path.Combine(Paths.Roaming, "relay.conf"); } }  // feature: relay.conf

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
            quick.Run(a);
        }

        // ------------------------------------------------------------------ finding it

        /// <summary>
        /// Knocks on port 8787 of every address in this machine's subnets (a /24 each at most), 32 at a
        /// time, and asks whatever answers whether it is the relay. Gives up after 10 s.
        /// </summary>
        public void Discover(Done<string> done)  // feature: relay.subnet
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

        /// <summary>A request whose status the caller looks at (304, 404); network trouble and a refusal throw.</summary>
        Net.Response Send(string method, string path, string etag, int timeoutMs, string body)
        {
            string b = baseUrl;
            if (b == null) throw new IOException("Apple News Relay hasn't been found yet.");
            Net.Response r;
            try
            {
                r = Net.Request(method, b + path, token, timeoutMs, MAX_JSON, etag, body);
            }
            catch (Exception e)
            {
                throw new IOException("Can't reach Apple News Relay at " + Where + ". Is the Mac awake?", e);
            }
            if (r.Code == 401 || r.Code == 403) throw new IOException("The relay at " + Where + " turned this computer away (HTTP " + r.Code + ").");
            return r;
        }

        JObj Call(string method, string path, int timeoutMs)
        {
            return Parse(Send(method, path, null, timeoutMs, null));
        }

        static JObj Parse(Net.Response r)
        {
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

        /// <summary>
        /// The front page: Today's sections from Apple News (each deep, its first page), Latest and Saved,
        /// in one call. Conditional on the last one: Unchanged when nothing on it changed. A relay older
        /// than 0.3 gets the three calls of 0.2.
        /// </summary>
        public void Front(Done<FrontPage> done)
        {
            Run(() =>
            {
                try
                {
                    if (!legacy)
                    {
                        Net.Response r = Send("GET", "/v1/front?per_section=" + PER_SECTION + "&fields=" + CARD_FIELDS
                            + "&fetch_missing=true", frontEtag, 20000, null);
                        if (r.Code == 304)
                        {
                            Post(done, new FrontPage(null, true, 0), null);
                            return;
                        }
                        if (r.Code != 404)
                        {
                            JObj data = Parse(r).Obj("data") ?? new JObj();
                            frontEtag = r.ETag;
                            Save("front.json", new JObj().Put("front", data).Put("etag", r.ETag ?? ""));
                            Post(done, new FrontPage(Assemble(data), false, (long)data.Num("version", 0)), null);
                            return;
                        }
                        legacy = true;
                    }
                    JObj sections = Call("GET", "/v1/sections?per_section=30", 20000);
                    JObj latest = Call("GET", "/v1/articles?limit=45", 20000);
                    JObj saved = Call("GET", "/v1/saved?limit=45&fetch_missing=true", 20000);
                    Save("front.json", new JObj().Put("sections", sections).Put("latest", latest).Put("saved", saved));
                    Post(done, new FrontPage(Assemble(sections, latest, saved), false, 0), null);
                }
                catch (Exception e)
                {
                    Post<FrontPage>(done, null, Message(e));
                }
            });
        }

        /// <summary>The last front page that loaded, for an instant start (read off the UI thread); null if none.</summary>
        public void CachedFront(Done<FrontPage> done)  // feature: front.cache
        {
            Run(() =>
            {
                FrontPage page = null;
                try
                {
                    JObj o = Load("front.json");
                    if (o != null && o.Obj("front") != null)
                    {
                        JObj data = o.Obj("front");
                        page = new FrontPage(Assemble(data), false, (long)data.Num("version", 0));
                        string etag = o.Str("etag");
                        if (etag != null && frontEtag == null) frontEtag = etag;
                    }
                    else if (o != null)
                    {
                        page = new FrontPage(Assemble(o.Obj("sections"), o.Obj("latest"), o.Obj("saved")), false, 0);
                    }
                }
                catch (Exception)
                {
                    page = null;
                }
                Post(done, page, null);
            });
        }

        /// <summary>/v1/front's data: sections with a link to their next page, Latest second, Saved last.</summary>
        static List<Section> Assemble(JObj data)
        {
            List<Section> out_ = new List<Section>();
            List<object> sections = data.Arr("sections");
            if (sections != null)
            {
                foreach (object x in sections)
                {
                    JObj s = x as JObj;
                    if (s == null) continue;
                    List<Story> stories = Story.List(s.Arr("articles"));
                    if (stories.Count < 2) continue; // puzzles, empty slots
                    Section sec = new Section(s.Opt("id"), s.Opt("title"), stories);
                    JObj links = s.Obj("links");
                    sec.Next = links == null ? null : links.Str("next");
                    sec.Total = Math.Max(stories.Count, s.Int("article_count", stories.Count));
                    sec.Today = s.Int("today_count", stories.Count);
                    out_.Add(sec);
                }
            }
            JObj latest = data.Obj("latest");
            List<Story> newest = Story.List(latest == null ? null : latest.Arr("articles"));
            if (newest.Count > 0)
            {
                Section sec = new Section("latest", "Latest", newest);
                JObj links = latest.Obj("links");
                sec.Next = links == null ? null : links.Str("next");
                out_.Insert(Math.Min(2, out_.Count), sec);
            }
            JObj saved = data.Obj("saved");
            List<Story> kept = Story.List(saved == null ? null : saved.Arr("articles"));
            if (kept.Count > 0) out_.Add(new Section("saved", "Saved Stories", kept));
            return out_;
        }

        /// <summary>The next page of a section (Section.Next): its stories and the link after it.</summary>
        public void Page(string next, Done<StoryPage> done)  // feature: front.deep
        {
            Run(() =>
            {
                try
                {
                    string path = next.Contains("fields=") ? next : next + (next.Contains("?") ? "&" : "?") + "fields=" + CARD_FIELDS;
                    if (path.StartsWith("/v1/sections/")) path = System.Text.RegularExpressions.Regex.Replace(path, "limit=\\d+", "limit=" + PAGE);
                    JObj o = Call("GET", path, 20000);
                    JObj links = o.Obj("links");
                    Post(done, new StoryPage(Story.List(o.Arr("data")), links == null ? null : links.Str("next")), null);
                }
                catch (Exception e)
                {
                    Post<StoryPage>(done, null, Message(e));
                }
            });
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

        /// <summary>One story with its body. wait: seconds the relay may spend getting it from Apple News;
        /// maxChars: only the opening, about that much text (0: all of it).</summary>
        public void Article(string id, int wait, int maxChars, Done<Article> done)
        {
            (wait > 0 ? slow : quick).Run(() =>
            {
                try
                {
                    JObj o = Call("GET", "/v1/articles/" + Uri.EscapeDataString(id) + "?wait=" + wait
                        + (maxChars > 0 ? "&max_chars=" + maxChars : ""), (wait + 15) * 1000);
                    Post(done, new Article(o.Obj("data") ?? new JObj()), null);
                }
                catch (Exception e)
                {
                    Post<Article>(done, null, Message(e));
                }
            });
        }

        public void Article(string id, int wait, Done<Article> done)
        {
            Article(id, wait, 0, done);
        }

        /// <summary>The rest of a story whose opening is on screen, from block 'from' on.</summary>
        public void ArticleRest(string id, int from, Done<Article> done)  // feature: reader.progressive
        {
            Run(() =>
            {
                try
                {
                    JObj o = Call("GET", "/v1/articles/" + Uri.EscapeDataString(id) + "?fetch=never&from_block=" + from, 15000);
                    Post(done, new Article(o.Obj("data") ?? new JObj()), null);
                }
                catch (Exception e)
                {
                    Post<Article>(done, null, Message(e));
                }
            });
        }

        /// <summary>A story's opening, ahead of time, if the relay has its body (never asks the Mac); on
        /// the fetching-ahead thread. done gets the relay's JSON on that thread, or null.</summary>
        public void Head(string id, Action<JObj> done)
        {
            warm.Run(() =>
            {
                JObj data = null;
                try
                {
                    data = Call("GET", "/v1/articles/" + Uri.EscapeDataString(id) + "?fetch=never&max_chars=" + HEAD_CHARS, 15000).Obj("data");
                    if (data != null && data.Opt("completeness") == "summary") data = null;
                }
                catch (Exception) { data = null; }
                done(data);
            });
        }

        /// <summary>What may have changed about a story kept here: saved, liked, its topics.</summary>
        public void ArticleState(string id, Done<JObj> done)
        {
            Run(() =>
            {
                try
                {
                    Post(done, Call("GET", "/v1/articles/" + Uri.EscapeDataString(id) + "?fetch=never&fields=saved,reaction,topics,completeness", 10000).Obj("data"), null);
                }
                catch (Exception e) { Post<JObj>(done, null, Message(e)); }
            });
        }

        /// <summary>
        /// The stories this computer may open next, most likely first: the relay has Apple News fetch the
        /// ones it lacks, in that order, and forgets this computer's previous list. done gets the ids the
        /// relay already has a body for. A relay older than 0.3 gets a background fetch for the first.
        /// </summary>
        public void Interest(string client, List<string> ids, Done<List<string>> done)  // feature: front.warm
        {
            Run(() =>
            {
                if (noInterest)
                {
                    if (ids.Count > 0) PrefetchNow(ids[0]);
                    return;
                }
                try
                {
                    List<object> a = new List<object>();
                    foreach (string id in ids) a.Add(id);
                    Net.Response r = Send("PUT", "/v1/interest/" + client, null, 8000, new JObj().Put("articles", a).ToString());
                    if (r.Code == 404)
                    {
                        noInterest = true;
                        if (ids.Count > 0) PrefetchNow(ids[0]);
                        return;
                    }
                    JObj data = Parse(r).Obj("data");
                    List<object> states = data == null ? null : data.Arr("articles");
                    List<string> cached = new List<string>();
                    if (states != null)
                        foreach (object x in states)
                        {
                            JObj st = x as JObj;
                            if (st != null && st.Opt("state") == "cached") cached.Add(st.Opt("id"));
                        }
                    Post(done, cached, null);
                }
                catch (Exception) { /* only a guess; never mind */ }
            });
        }

        /// <summary>This computer stops looking ahead (the window was minimised).</summary>
        public void ClearInterest(string client)
        {
            if (noInterest) return;
            Run(() =>
            {
                try { Send("DELETE", "/v1/interest/" + client, null, 5000, null); }
                catch (Exception) { }
            });
        }

        /// <summary>Work on this computer's own files (Bodies) off the UI thread, in order; done on the UI thread.</summary>
        public void Background<T>(Func<T> work, Action<T> done)
        {
            disk.Run(() =>
            {
                T value = default(T);
                try { value = work(); }
                catch (Exception) { }
                ui.BeginInvoke(new Action(() => done(value)));
            });
        }

        /// <summary>Waits for a relay job; reports its final state ("succeeded", "failed", or still "running").</summary>
        public void Job(string id, int wait, Done<string> done)
        {
            slow.Run(() =>
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
            slow.Run(() =>
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
            Run(() => PrefetchNow(id));
        }

        void PrefetchNow(string id)
        {
            try { Call("POST", "/v1/articles/" + Uri.EscapeDataString(id) + "/fetch?priority=background", 8000); }
            catch (Exception) { /* only a guess; never mind */ }
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
            slow.Run(() =>
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
