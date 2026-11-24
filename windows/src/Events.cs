using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using System.Windows.Threading;

namespace Broadsheet
{
    /// <summary>
    /// Follows the relay's event stream (/v1/events) on a thread of its own, so the page changes when
    /// the Mac's does instead of on a timer: hello on every connection, front.updated when sections,
    /// Latest or Saved change, article.body_captured when a story arrives, job.updated as the Mac
    /// works. Reconnects by itself (1 s, then doubling to 30 s).
    /// </summary>
    sealed class Events  // feature: relay.events
    {
        public interface IListener
        {
            /// <summary>Main thread.</summary>
            void OnEvent(string type, JObj data);
            /// <summary>Main thread: the stream connected or dropped.</summary>
            void OnStream(bool up);
        }

        /// <summary>The relay sends a comment every 15 s; three missed means the line is dead.</summary>
        const int READ_TIMEOUT_MS = 45000;

        readonly Relay relay;
        readonly IListener listener;
        readonly Dispatcher ui;
        readonly Stopwatch clock = Stopwatch.StartNew();
        volatile bool running, up;
        volatile HttpWebResponse conn;
        long downSince;
        Thread thread;

        public Events(Relay relay, IListener listener, Dispatcher ui)
        {
            this.relay = relay;
            this.listener = listener;
            this.ui = ui;
        }

        public bool Connected { get { return up; } }

        /// <summary>Milliseconds the stream has been down (0 while up).</summary>
        public long DownFor { get { return up ? 0 : clock.ElapsedMilliseconds - Interlocked.Read(ref downSince); } }

        public void Start()
        {
            if (running) return;
            running = true;
            thread = new Thread(Loop);
            thread.IsBackground = true;
            thread.Name = "events";
            thread.Start();
        }

        public void Stop()
        {
            running = false;
            HttpWebResponse c = conn;
            if (c != null) ThreadPool.QueueUserWorkItem(_ => { try { c.Close(); } catch (Exception) { } }); // unblocks the read
            thread = null;
        }

        void Loop()
        {
            int backoff = 1000;
            while (running)
            {
                string b = relay.Base;
                if (b == null)
                {
                    if (!Pause(2000)) return;
                    continue;
                }
                long opened = clock.ElapsedMilliseconds;
                try
                {
                    using (HttpWebResponse r = Net.OpenStream(b + "/v1/events", relay.Token, READ_TIMEOUT_MS))
                    {
                        conn = r;
                        if (r.StatusCode != HttpStatusCode.OK) throw new IOException("HTTP " + (int)r.StatusCode);
                        SetUp(true);
                        Sse sse = new Sse();
                        using (StreamReader reader = new StreamReader(r.GetResponseStream(), Encoding.UTF8))
                        {
                            string line;
                            while (running && (line = reader.ReadLine()) != null)
                            {
                                Sse.Event e = sse.Line(line);
                                if (e == null) continue;
                                JObj data;
                                try { data = Json.ParseObject(e.Data); }
                                catch (FormatException) { data = new JObj(); }
                                string type = e.Type;
                                ui.BeginInvoke(new Action(() => { if (running) listener.OnEvent(type, data); }));
                            }
                        }
                    }
                }
                catch (Exception)
                {
                    // dropped, or the relay isn't there: try again shortly
                }
                finally
                {
                    conn = null;
                    SetUp(false);
                }
                if (clock.ElapsedMilliseconds - opened > 30000) backoff = 1000; // it had been fine
                if (!Pause(backoff)) return;
                backoff = Math.Min(30000, backoff * 2);
            }
        }

        void SetUp(bool value)
        {
            if (up == value) return;
            up = value;
            if (!value) Interlocked.Exchange(ref downSince, clock.ElapsedMilliseconds);
            ui.BeginInvoke(new Action(() => { if (running) listener.OnStream(value); }));
        }

        bool Pause(int ms)
        {
            for (int waited = 0; waited < ms && running; waited += 100) Thread.Sleep(100);
            return running;
        }
    }
}
