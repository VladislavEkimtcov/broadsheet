using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace Broadsheet
{
    /// <summary>
    /// A small JSON reader and writer. The in-box .NET Framework has no System.Text.Json and no
    /// NuGet here, so this is the whole of it: objects become <see cref="JObj"/>, arrays
    /// <see cref="List{Object}"/>, numbers double, plus string, bool and null.
    /// </summary>
    static class Json
    {
        public static object Parse(string s)
        {
            int i = 0;
            Skip(s, ref i);
            object v = Value(s, ref i, 0);
            Skip(s, ref i);
            if (i != s.Length) throw new FormatException("JSON: trailing text at " + i);
            return v;
        }

        public static JObj ParseObject(string s)
        {
            JObj o = Parse(s) as JObj;
            if (o == null) throw new FormatException("JSON: not an object");
            return o;
        }

        static void Skip(string s, ref int i)
        {
            while (i < s.Length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++;
        }

        static object Value(string s, ref int i, int depth)
        {
            if (depth > 200) throw new FormatException("JSON: too deep");
            if (i >= s.Length) throw new FormatException("JSON: unexpected end");
            char c = s[i];
            if (c == '{')
            {
                i++;
                JObj o = new JObj();
                Skip(s, ref i);
                if (i < s.Length && s[i] == '}') { i++; return o; }
                while (true)
                {
                    Skip(s, ref i);
                    if (i >= s.Length || s[i] != '"') throw new FormatException("JSON: key expected at " + i);
                    string k = Str(s, ref i);
                    Skip(s, ref i);
                    if (i >= s.Length || s[i] != ':') throw new FormatException("JSON: ':' expected at " + i);
                    i++;
                    Skip(s, ref i);
                    o[k] = Value(s, ref i, depth + 1);
                    Skip(s, ref i);
                    if (i < s.Length && s[i] == ',') { i++; continue; }
                    if (i < s.Length && s[i] == '}') { i++; return o; }
                    throw new FormatException("JSON: ',' or '}' expected at " + i);
                }
            }
            if (c == '[')
            {
                i++;
                List<object> a = new List<object>();
                Skip(s, ref i);
                if (i < s.Length && s[i] == ']') { i++; return a; }
                while (true)
                {
                    Skip(s, ref i);
                    a.Add(Value(s, ref i, depth + 1));
                    Skip(s, ref i);
                    if (i < s.Length && s[i] == ',') { i++; continue; }
                    if (i < s.Length && s[i] == ']') { i++; return a; }
                    throw new FormatException("JSON: ',' or ']' expected at " + i);
                }
            }
            if (c == '"') return Str(s, ref i);
            if (Word(s, ref i, "true")) return true;
            if (Word(s, ref i, "false")) return false;
            if (Word(s, ref i, "null")) return null;
            int start = i;
            if (i < s.Length && s[i] == '-') i++;
            while (i < s.Length && (char.IsDigit(s[i]) || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++;
            if (i == start) throw new FormatException("JSON: unexpected '" + c + "' at " + i);
            return double.Parse(s.Substring(start, i - start), NumberStyles.Float, CultureInfo.InvariantCulture);
        }

        static bool Word(string s, ref int i, string w)
        {
            if (string.CompareOrdinal(s, i, w, 0, w.Length) != 0) return false;
            i += w.Length;
            return true;
        }

        static string Str(string s, ref int i)
        {
            i++; // opening quote
            StringBuilder b = null;
            int run = i;
            while (i < s.Length)
            {
                char c = s[i];
                if (c == '"')
                {
                    string tail = s.Substring(run, i - run);
                    i++;
                    return b == null ? tail : b.Append(tail).ToString();
                }
                if (c == '\\')
                {
                    if (b == null) b = new StringBuilder();
                    b.Append(s, run, i - run);
                    i++;
                    if (i >= s.Length) break;
                    char e = s[i++];
                    switch (e)
                    {
                        case 'n': b.Append('\n'); break;
                        case 't': b.Append('\t'); break;
                        case 'r': b.Append('\r'); break;
                        case 'b': b.Append('\b'); break;
                        case 'f': b.Append('\f'); break;
                        case 'u':
                            if (i + 4 > s.Length) throw new FormatException("JSON: bad \\u");
                            b.Append((char)int.Parse(s.Substring(i, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture));
                            i += 4;
                            break;
                        default: b.Append(e); break;
                    }
                    run = i;
                    continue;
                }
                i++;
            }
            throw new FormatException("JSON: unterminated string");
        }

        public static string Write(object v)
        {
            StringBuilder b = new StringBuilder();
            Write(b, v);
            return b.ToString();
        }

        static void Write(StringBuilder b, object v)
        {
            if (v == null) { b.Append("null"); return; }
            if (v is bool) { b.Append((bool)v ? "true" : "false"); return; }
            if (v is string) { Quote(b, (string)v); return; }
            if (v is double || v is float || v is int || v is long || v is decimal)
            {
                double d = Convert.ToDouble(v, CultureInfo.InvariantCulture);
                if (double.IsNaN(d) || double.IsInfinity(d)) b.Append("null");
                else b.Append(d.ToString("R", CultureInfo.InvariantCulture));
                return;
            }
            JObj o = v as JObj;
            if (o != null)
            {
                b.Append('{');
                bool first = true;
                foreach (KeyValuePair<string, object> kv in o)
                {
                    if (!first) b.Append(',');
                    first = false;
                    Quote(b, kv.Key);
                    b.Append(':');
                    Write(b, kv.Value);
                }
                b.Append('}');
                return;
            }
            System.Collections.IEnumerable list = v as System.Collections.IEnumerable;
            if (list != null)
            {
                b.Append('[');
                bool first = true;
                foreach (object x in list)
                {
                    if (!first) b.Append(',');
                    first = false;
                    Write(b, x);
                }
                b.Append(']');
                return;
            }
            Quote(b, v.ToString());
        }

        static void Quote(StringBuilder b, string s)
        {
            b.Append('"');
            foreach (char c in s)
            {
                switch (c)
                {
                    case '"': b.Append("\\\""); break;
                    case '\\': b.Append("\\\\"); break;
                    case '\n': b.Append("\\n"); break;
                    case '\r': b.Append("\\r"); break;
                    case '\t': b.Append("\\t"); break;
                    default:
                        if (c < 0x20) b.Append("\\u").Append(((int)c).ToString("x4"));
                        else b.Append(c);
                        break;
                }
            }
            b.Append('"');
        }
    }

    /// <summary>A JSON object that keeps its keys in order, with forgiving getters (org.json's opt*).</summary>
    sealed class JObj : IEnumerable<KeyValuePair<string, object>>
    {
        readonly List<string> keys = new List<string>();
        readonly Dictionary<string, object> map = new Dictionary<string, object>();

        public object this[string key]
        {
            get { object v; return map.TryGetValue(key, out v) ? v : null; }
            set
            {
                if (!map.ContainsKey(key)) keys.Add(key);
                map[key] = value;
            }
        }

        public bool Has(string key) { return map.ContainsKey(key); }

        public bool IsNull(string key) { return this[key] == null; }

        public void Remove(string key)
        {
            if (map.Remove(key)) keys.Remove(key);
        }

        public int Count { get { return keys.Count; } }

        public IEnumerable<string> Keys { get { return keys; } }

        public JObj Put(string key, object value)
        {
            this[key] = value;
            return this;
        }

        /// <summary>The string, or null when missing, null or empty (Story.str on the TV).</summary>
        public string Str(string key)
        {
            object v = this[key];
            if (v == null) return null;
            string s = v as string;
            if (s == null)
            {
                if (v is double) s = ((double)v).ToString(CultureInfo.InvariantCulture);
                else if (v is bool) s = (bool)v ? "true" : "false";
                else return null;
            }
            return s.Length == 0 ? null : s;
        }

        /// <summary>The string, or <paramref name="fallback"/> when missing (empty kept).</summary>
        public string Opt(string key, string fallback)
        {
            string s = this[key] as string;
            return s ?? fallback;
        }

        public string Opt(string key) { return Opt(key, ""); }

        public int Int(string key, int fallback)
        {
            object v = this[key];
            if (v is double) return (int)Math.Round((double)v);
            string s = v as string;
            int n;
            if (s != null && int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out n)) return n;
            return fallback;
        }

        public int Int(string key) { return Int(key, 0); }

        public long Long(string key)
        {
            object v = this[key];
            return v is double ? (long)(double)v : 0L;
        }

        public double Num(string key, double fallback)
        {
            object v = this[key];
            return v is double ? (double)v : fallback;
        }

        public bool Bool(string key, bool fallback)
        {
            object v = this[key];
            return v is bool ? (bool)v : fallback;
        }

        public bool Bool(string key) { return Bool(key, false); }

        public JObj Obj(string key) { return this[key] as JObj; }

        public List<object> Arr(string key) { return this[key] as List<object>; }

        public override string ToString() { return Json.Write(this); }

        public IEnumerator<KeyValuePair<string, object>> GetEnumerator()
        {
            foreach (string k in keys) yield return new KeyValuePair<string, object>(k, map[k]);
        }

        System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() { return GetEnumerator(); }
    }
}
