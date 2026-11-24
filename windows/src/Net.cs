using System;
using System.IO;
using System.Net;
using System.Text;

namespace Broadsheet
{
    /// <summary>Plain HTTP with HttpWebRequest (in the box): gzip, a size cap, no exceptions for 4xx/5xx.</summary>
    static class Net
    {
        public static string UserAgent = "Broadsheet (Windows)";

        public sealed class Response
        {
            public int Code;
            public byte[] Body;
            public string ContentType, ETag;

            public string Text() { return Encoding.UTF8.GetString(Body ?? new byte[0]); }
        }

        static Net()
        {
            // Desktop .NET allows two connections per host by default: a story waiting on the Mac
            // (up to 14 s) would hold up the pictures and the next call behind it.
            ServicePointManager.DefaultConnectionLimit = 24;
            ServicePointManager.Expect100Continue = false;
        }

        /// <summary>An HTTP call. Throws IOException/WebException only when nothing came back.</summary>
        public static Response Request(string method, string url, string token, int timeoutMs, int maxBytes)
        {
            return Request(method, url, token, timeoutMs, maxBytes, null, null);
        }

        /// <summary>As above; etag makes it conditional (304 comes back with no body), body is JSON for
        /// POST and PUT. Connections are kept alive for the next call (the relay keeps them too).</summary>
        public static Response Request(string method, string url, string token, int timeoutMs, int maxBytes, string etag, string body)
        {
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create(url);
            r.Method = method;
            r.Timeout = timeoutMs;
            r.ReadWriteTimeout = timeoutMs;
            r.UserAgent = UserAgent;
            r.Accept = "application/json";
            r.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            r.KeepAlive = true;
            r.Proxy = null; // a LAN server; skip proxy auto-detection (it costs seconds on first call)
            if (token != null) r.Headers["Authorization"] = "Bearer " + token;
            if (!string.IsNullOrEmpty(etag)) r.Headers["If-None-Match"] = etag;
            if (method == "POST" || method == "PUT")
            {
                byte[] data = Encoding.UTF8.GetBytes(body ?? "");
                r.ContentLength = data.Length;
                if (data.Length > 0)
                {
                    r.ContentType = "application/json";
                    using (Stream req = r.GetRequestStream()) req.Write(data, 0, data.Length);
                }
            }
            HttpWebResponse resp;
            try
            {
                resp = (HttpWebResponse)r.GetResponse();
            }
            catch (WebException e)
            {
                resp = e.Response as HttpWebResponse;
                if (resp == null) throw new IOException(e.Message, e);
            }
            using (resp)
            {
                Response o = new Response();
                o.Code = (int)resp.StatusCode;
                o.ContentType = resp.ContentType;
                o.ETag = resp.Headers["ETag"];
                using (Stream s = resp.GetResponseStream()) o.Body = Read(s, maxBytes);
                return o;
            }
        }

        /// <summary>An open event stream (text/event-stream); the caller reads it and closes it.</summary>
        public static HttpWebResponse OpenStream(string url, string token, int readTimeoutMs)
        {
            HttpWebRequest r = (HttpWebRequest)WebRequest.Create(url);
            r.Timeout = 6000;
            r.ReadWriteTimeout = readTimeoutMs;
            r.UserAgent = UserAgent;
            r.Accept = "text/event-stream";
            r.KeepAlive = false; // its own connection, closed when it ends
            r.Proxy = null;
            r.AllowReadStreamBuffering = false;
            if (token != null) r.Headers["Authorization"] = "Bearer " + token;
            return (HttpWebResponse)r.GetResponse();
        }

        public static byte[] Read(Stream s, int max)
        {
            MemoryStream m = new MemoryStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.Read(buf, 0, buf.Length)) > 0)
            {
                m.Write(buf, 0, n);
                if (m.Length > max) throw new IOException("Response too big");
            }
            return m.ToArray();
        }
    }
}
