package io.github.vladislavekimtcov.broadsheet;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Server-Sent Events stream (the relay's {@code /v1/events}) one line at a time, as the
 * HTML standard describes it. No Android types, so the shared cases in spec/cases/sse.json test it,
 * as they test the Windows port's Sse.cs.
 */
final class Sse {
    static final class Event {
        /** "message" when the stream named none. */
        final String type, data, id;

        Event(String type, String data, String id) {
            this.type = type;
            this.data = data;
            this.id = id;
        }
    }

    private final StringBuilder data = new StringBuilder();
    private String type = "", lastId;
    private boolean hasData;
    private long retry = -1;

    /** One line, without its line ending. Returns the event a blank line completes, or null. */
    Event line(String line) {
        if (line.isEmpty()) {
            if (!hasData) {
                type = "";
                return null;
            }
            String d = data.toString();
            if (d.endsWith("\n")) d = d.substring(0, d.length() - 1);
            Event e = new Event(type.isEmpty() ? "message" : type, d, lastId);
            data.setLength(0);
            type = "";
            hasData = false;
            return e;
        }
        if (line.charAt(0) == ':') return null;  // a comment: the relay's keep-alive
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        switch (field) {
            case "event":
                type = value;
                break;
            case "data":
                data.append(value).append('\n');
                hasData = true;
                break;
            case "id":
                if (value.indexOf('\0') < 0) lastId = value;
                break;
            case "retry":
                try {
                    retry = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    // ignored, as the standard says
                }
                break;
            default:
                break;
        }
        return null;
    }

    /** The reconnection time the stream asked for (ms), or -1. */
    long retry() {
        return retry;
    }

    /** Every event in a piece of stream text; lines end with CRLF, LF or CR, and an unfinished
     *  last line is left out (as a reader waiting for more would). For the shared cases. */
    static List<Event> parse(String text) {
        Sse sse = new Sse();
        List<Event> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r') continue;
            Event e = sse.line(text.substring(start, i));
            if (e != null) out.add(e);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            start = i + 1;
        }
        return out;
    }
}
