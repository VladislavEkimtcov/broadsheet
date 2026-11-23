package io.github.vladislavekimtcov.broadsheet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * HTTP for the relay's JSON and for pictures. One honest user agent ("Broadsheet/x.y (Android
 * TV)"), gzip, conditional requests by ETag, and nothing else.
 */
final class Net {
    /** Set by Main from the package version. */
    static String userAgent = "Broadsheet (Android TV)";

    static final class Response {
        int code;
        byte[] body;
        String etag;

        String text() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    private Net() {}

    /**
     * One request. {@code etag} makes it conditional (304 comes back with no body); {@code token}
     * is sent as a bearer token when the relay asks for one; {@code readTimeoutMs} must cover any
     * {@code wait=} the URL asks the relay for.
     */
    static Response request(String method, String url, String etag, String token, int readTimeoutMs, int maxBytes)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "gzip");
        if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-None-Match", etag);
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        if ("POST".equals(method) || "PUT".equals(method)) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            Response r = new Response();
            r.code = c.getResponseCode();
            r.etag = c.getHeaderField("ETag");
            InputStream in = r.code >= 400 ? c.getErrorStream() : (r.code == 304 ? null : c.getInputStream());
            if (in != null) {
                if ("gzip".equalsIgnoreCase(c.getHeaderField("Content-Encoding"))) in = new GZIPInputStream(in);
                try (InputStream s = in) {
                    r.body = read(s, maxBytes);
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    static byte[] read(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            if (Thread.interrupted()) throw new InterruptedIOException();
            out.write(buf, 0, n);
            if (out.size() > maxBytes) throw new IOException("response too large");
        }
        return out.toByteArray();
    }
}
