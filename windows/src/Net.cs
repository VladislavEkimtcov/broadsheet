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
            public string ContentType;

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
            if (method == "POST" || method == "PUT") r.ContentLength = 0;
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
                using (Stream s = resp.GetResponseStream()) o.Body = Read(s, maxBytes);
                return o;
            }
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
