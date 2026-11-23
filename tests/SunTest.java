package io.github.vladislavekimtcov.broadsheet;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * When the page turns light and dark. Run from the repo root:
 * {@code javac -d build/test src/io/github/vladislavekimtcov/broadsheet/Sun.java tests/SunTest.java
 * && java -cp build/test io.github.vladislavekimtcov.broadsheet.SunTest}
 */
public final class SunTest {
    private static int failures;

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "ok    " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    private static long at(TimeZone tz, int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, mo - 1, d, h, mi);
        return c.getTimeInMillis();
    }

    public static void main(String[] args) {
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        // New York, 23 November 2026: sunrise 6:53, sunset 16:31 (timeanddate.com).
        check(Sun.dark(at(ny, 2026, 11, 23, 6, 30), ny), "dark before sunrise");
        check(!Sun.dark(at(ny, 2026, 11, 23, 7, 15), ny), "light after sunrise");
        check(!Sun.dark(at(ny, 2026, 11, 23, 16, 20), ny), "light before sunset");
        check(Sun.dark(at(ny, 2026, 11, 23, 16, 45), ny), "dark after sunset");
        long noon = at(ny, 2026, 11, 23, 12, 0);
        long change = Sun.nextChange(noon, ny);
        check(Math.abs(change - at(ny, 2026, 11, 23, 16, 31)) < 6 * 60000L, "next change from noon is sunset");
        long night = at(ny, 2026, 11, 23, 22, 0);
        check(Math.abs(Sun.nextChange(night, ny) - at(ny, 2026, 11, 24, 6, 54)) < 6 * 60000L,
                "next change at night is tomorrow's sunrise");
        check(Math.abs(Sun.next(noon, ny, true) - at(ny, 2026, 11, 24, 6, 54)) < 6 * 60000L, "next sunrise");
        TimeZone tromso = TimeZone.getTimeZone("Europe/Oslo");
        check(Sun.nextChange(noon, tromso) > noon, "always a next change");
        TimeZone odd = TimeZone.getTimeZone("Pacific/Chatham");
        check(Sun.nextChange(noon, odd) - noon <= 24 * 3600000L, "unlisted zone still turns");
        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
    }
}
