package io.github.vladislavekimtcov.broadsheet;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * The shared behaviour cases in spec/cases/*.json, the same files windows/tests/Tests.cs reads.
 * Run from the repo root:
 * {@code javac -d build/test src/io/github/vladislavekimtcov/broadsheet/Sun.java
 * src/io/github/vladislavekimtcov/broadsheet/Dates.java src/io/github/vladislavekimtcov/broadsheet/Ladder.java
 * src/io/github/vladislavekimtcov/broadsheet/Sse.java tests/CasesTest.java
 * && java -cp build/test io.github.vladislavekimtcov.broadsheet.CasesTest}
 */
public final class CasesTest {
    private static int failures;

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "ok    " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "spec/cases";
        sun(load(dir + "/sun.json"));
        dates(load(dir + "/dates.json"));
        ladder(load(dir + "/ladder.json"));
        sse(load(dir + "/sse.json"));
        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        if (failures > 0) System.exit(1);
    }

    private static void ladder(Map<String, Object> l) {
        for (Map<String, Object> c : list(l, "around"))
            check(same(Ladder.around(num(c, "index"), num(c, "count")), c), "ladder: around, " + str(c, "name"));
        for (Map<String, Object> c : list(l, "want_more"))
            check(Ladder.wantMore(num(c, "index"), num(c, "loaded")) == (Boolean) c.get("expect"), "ladder: want more, " + str(c, "name"));
        for (Map<String, Object> c : list(l, "rail_neighbours"))
            check(same(Ladder.railNeighbours(num(c, "index"), num(c, "count"), num(c, "direction")), c), "ladder: rail, " + str(c, "name"));
        for (Map<String, Object> c : list(l, "reading"))
            check(same(Ladder.reading(num(c, "index"), num(c, "count")), c), "ladder: reading, " + str(c, "name"));
        for (Map<String, Object> c : list(l, "near"))
            check(Ladder.near(num(c, "scroll"), num(c, "viewport"), num(c, "content"), num(c, "screens"))
                    == (Boolean) c.get("expect"), "ladder: near, " + str(c, "name"));
    }

    private static void sse(Map<String, Object> s) {
        for (Map<String, Object> c : list(s, "streams")) {
            List<Sse.Event> got = Sse.parse(str(c, "stream"));
            List<Map<String, Object>> want = list(c, "events");
            boolean ok = got.size() == want.size();
            for (int i = 0; ok && i < got.size(); i++) {
                Sse.Event g = got.get(i);
                Map<String, Object> w = want.get(i);
                ok = g.type.equals(str(w, "type")) && g.data.equals(str(w, "data"))
                        && (g.id == null ? w.get("id") == null : g.id.equals(str(w, "id")));
            }
            check(ok, "sse: " + str(c, "name"));
        }
    }

    private static int num(Map<String, Object> o, String key) {
        return ((Number) o.get(key)).intValue();
    }

    private static boolean same(int[] got, Map<String, Object> c) {
        @SuppressWarnings("unchecked")
        List<Object> want = (List<Object>) c.get("expect");
        if (got.length != want.size()) return false;
        for (int i = 0; i < got.length; i++) if (got[i] != ((Number) want.get(i)).intValue()) return false;
        return true;
    }

    private static void sun(Map<String, Object> s) {
        for (Map<String, Object> c : list(s, "dark")) {
            TimeZone tz = TimeZone.getTimeZone(str(c, "zone"));
            check(Sun.dark(local(tz, str(c, "local")), tz) == (Boolean) c.get("dark"), "sun: " + str(c, "name"));
        }
        for (Map<String, Object> c : list(s, "next_change")) {
            TimeZone tz = TimeZone.getTimeZone(str(c, "zone"));
            long got = Sun.nextChange(local(tz, str(c, "local")), tz);
            check(near(got, local(tz, str(c, "expect")), c), "sun: " + str(c, "name"));
        }
        for (Map<String, Object> c : list(s, "next_sunrise")) {
            TimeZone tz = TimeZone.getTimeZone(str(c, "zone"));
            long got = (long) Sun.next(local(tz, str(c, "local")), tz, true);
            check(near(got, local(tz, str(c, "expect")), c), "sun: " + str(c, "name"));
        }
        for (Map<String, Object> c : list(s, "turns_within_a_day")) {
            TimeZone tz = TimeZone.getTimeZone(str(c, "zone"));
            long from = local(tz, str(c, "local"));
            long next = Sun.nextChange(from, tz);
            check(next > from && next - from <= 24 * 3600000L, "sun: " + str(c, "name"));
        }
    }

    private static void dates(Map<String, Object> d) {
        for (Map<String, Object> c : list(d, "parse")) {
            Object utc = c.get("utc");
            long want = utc == null ? 0 : utc((String) utc);
            check(Dates.parse(str(c, "in")) == want, "dates: " + str(c, "name"));
        }
        for (Map<String, Object> c : list(d, "ago"))
            check(str(c, "expect").equals(Dates.ago(utc(str(c, "when")), utc(str(c, "now")))), "dates: " + str(c, "name"));
        for (Map<String, Object> c : list(d, "reading_time"))
            check(str(c, "expect").equals(Dates.readingTime(((Number) c.get("words")).intValue())), "dates: " + str(c, "name"));
    }

    private static boolean near(long got, long want, Map<String, Object> c) {
        return Math.abs(got - want) < ((Number) c.get("tolerance_minutes")).longValue() * 60000L;
    }

    /** "2026-11-23T06:30[:SS]" as wall-clock time in tz. */
    private static long local(TimeZone tz, String s) {
        int[] f = fields(s);
        Calendar cal = Calendar.getInstance(tz);
        cal.clear();
        cal.set(f[0], f[1] - 1, f[2], f[3], f[4], f[5]);
        return cal.getTimeInMillis();
    }

    private static long utc(String s) {
        int[] f = fields(s);
        return Dates.utc(f[0], f[1], f[2], f[3], f[4], f[5], 0);
    }

    private static int[] fields(String s) {
        int[] f = new int[6];
        String[] p = s.split("[-T:]");
        for (int i = 0; i < p.length; i++) f[i] = Integer.parseInt(p[i]);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> o, String key) {
        return (List<Map<String, Object>>) (List<?>) o.get(key);
    }

    private static String str(Map<String, Object> o, String key) {
        return (String) o.get(key);
    }

    // A small JSON reader: the JVM has none, and org.json is Android's.

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String path) throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        return (Map<String, Object>) value(s, new int[] {0});
    }

    private static Object value(String s, int[] at) {
        skip(s, at);
        char ch = s.charAt(at[0]);
        if (ch == '{') {
            Map<String, Object> m = new LinkedHashMap<>();
            at[0]++;
            skip(s, at);
            if (s.charAt(at[0]) == '}') { at[0]++; return m; }
            while (true) {
                skip(s, at);
                String k = string(s, at);
                skip(s, at);
                at[0]++; // ':'
                m.put(k, value(s, at));
                skip(s, at);
                if (s.charAt(at[0]++) == '}') return m;
            }
        }
        if (ch == '[') {
            List<Object> l = new ArrayList<>();
            at[0]++;
            skip(s, at);
            if (s.charAt(at[0]) == ']') { at[0]++; return l; }
            while (true) {
                l.add(value(s, at));
                skip(s, at);
                if (s.charAt(at[0]++) == ']') return l;
            }
        }
        if (ch == '"') return string(s, at);
        if (s.startsWith("true", at[0])) { at[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", at[0])) { at[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", at[0])) { at[0] += 4; return null; }
        int start = at[0];
        while (at[0] < s.length() && "+-.eE0123456789".indexOf(s.charAt(at[0])) >= 0) at[0]++;
        return Double.parseDouble(s.substring(start, at[0]));
    }

    private static String string(String s, int[] at) {
        StringBuilder b = new StringBuilder();
        at[0]++; // opening quote
        while (true) {
            char ch = s.charAt(at[0]++);
            if (ch == '"') return b.toString();
            if (ch == '\\') {
                char e = s.charAt(at[0]++);
                if (e == 'u') { b.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16)); at[0] += 4; }
                else b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
            } else b.append(ch);
        }
    }

    private static void skip(String s, int[] at) {
        while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) at[0]++;
    }
}
