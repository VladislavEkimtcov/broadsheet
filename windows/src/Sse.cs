using System;
using System.Collections.Generic;
using System.Text;

namespace Broadsheet
{
    /// <summary>
    /// Reads a Server-Sent Events stream (the relay's /v1/events) one line at a time, as the HTML
    /// standard describes it. No WPF types, so the shared cases in spec\cases\sse.json test it, as they
    /// test the TV app's Sse.java.
    /// </summary>
    sealed class Sse
    {
        public sealed class Event
        {
            /// <summary>"message" when the stream named none.</summary>
            public readonly string Type, Data, Id;

            public Event(string type, string data, string id)
            {
                Type = type;
                Data = data;
                Id = id;
            }
        }

        readonly StringBuilder data = new StringBuilder();
        string type = "", lastId;
        bool hasData;
        long retry = -1;

        /// <summary>One line, without its line ending. Returns the event a blank line completes, or null.</summary>
        public Event Line(string line)
        {
            if (line.Length == 0)
            {
                if (!hasData)
                {
                    type = "";
                    return null;
                }
                string d = data.ToString();
                if (d.EndsWith("\n")) d = d.Substring(0, d.Length - 1);
                Event e = new Event(type.Length == 0 ? "message" : type, d, lastId);
                data.Length = 0;
                type = "";
                hasData = false;
                return e;
            }
            if (line[0] == ':') return null; // a comment: the relay's keep-alive
            int colon = line.IndexOf(':');
            string field = colon < 0 ? line : line.Substring(0, colon);
            string value = colon < 0 ? "" : line.Substring(colon + 1);
            if (value.StartsWith(" ")) value = value.Substring(1);
            switch (field)
            {
                case "event":
                    type = value;
                    break;
                case "data":
                    data.Append(value).Append('\n');
                    hasData = true;
                    break;
                case "id":
                    if (value.IndexOf('\0') < 0) lastId = value;
                    break;
                case "retry":
                    long r;
                    if (long.TryParse(value, out r)) retry = r;
                    break;
            }
            return null;
        }

        /// <summary>The reconnection time the stream asked for (ms), or -1.</summary>
        public long Retry { get { return retry; } }

        /// <summary>Every event in a piece of stream text; lines end with CRLF, LF or CR, and an unfinished
        /// last line is left out (as a reader waiting for more would). For the shared cases.</summary>
        public static List<Event> Parse(string text)
        {
            Sse sse = new Sse();
            List<Event> out_ = new List<Event>();
            int start = 0;
            for (int i = 0; i < text.Length; i++)
            {
                char c = text[i];
                if (c != '\n' && c != '\r') continue;
                Event e = sse.Line(text.Substring(start, i - start));
                if (e != null) out_.Add(e);
                if (c == '\r' && i + 1 < text.Length && text[i + 1] == '\n') i++;
                start = i + 1;
            }
            return out_;
        }
    }
}
