package com.karaoketv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tiny HTTP server so guests can add songs from their phones (same Wi-Fi) without
 * touching the remote. Deliberately small and defensive:
 *  - only answers devices on the local network (private / link-local addresses)
 *  - every request needs the secret party key from the QR code
 *  - small request limits, short timeouts, a bounded worker pool and per-phone rate limits
 */
public final class PartyServer {

    public interface Backend {
        /** JSON array of search results. Called on a worker thread. */
        String searchJson(String query) throws Exception;
        /** JSON object with "now" and "queue". */
        String stateJson();
        /** Adds a song; returns null on success or a short message for the guest. */
        /** [partner]: second singer for a duet, or null/empty for a solo. */
        String add(String videoId, String singer, String partner);

        /** Removes a player and their scores; returns an error message or null. */
        String removePlayer(String name);
    }

    private static final int MAX_HEAD = 8 * 1024;
    private static final int MAX_BODY = 2 * 1024;
    /** A whole request must arrive within this time, however slowly it trickles in. */
    private static final long REQUEST_DEADLINE_MS = 8_000;

    /** Cleared on stop() so a lingering worker thread can never keep the app's screen in memory. */
    private volatile Backend backend;
    private final byte[] token;
    private final byte[] page;
    private final Map<String, ArrayDeque<Long>> hits = new HashMap<>();

    private volatile ServerSocket server;
    private boolean stopped;
    private ThreadPoolExecutor pool;

    public PartyServer(Backend backend, String token, byte[] pageHtml) {
        this.backend = backend;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.page = pageHtml;
    }

    /** Starts listening; returns the port, or -1 if no port could be opened. */
    public synchronized int start() {
        if (stopped) return -1;                 // stop() was called before we got going
        if (server != null) return server.getLocalPort();
        ServerSocket ss = null;
        for (int port = 8080; port <= 8089 && ss == null; port++) ss = tryBind(port);
        if (ss == null) ss = tryBind(0);
        if (ss == null) return -1;
        server = ss;
        pool = new ThreadPoolExecutor(2, 3, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(6));
        final ServerSocket listening = ss;
        Thread acceptor = new Thread(() -> acceptLoop(listening), "party-server");
        acceptor.setDaemon(true);
        acceptor.start();
        return ss.getLocalPort();
    }

    /** Quick and safe to call on the main thread: closes the socket and drops the backend. */
    public synchronized void stop() {
        stopped = true;
        backend = null;
        ServerSocket ss = server;
        server = null;
        if (ss != null) try { ss.close(); } catch (IOException ignored) { }
        if (pool != null) { pool.shutdownNow(); pool = null; }
    }

    private static ServerSocket tryBind(int port) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port), 16);
            return ss;
        } catch (IOException e) {
            return null;
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            final Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                return;
            }
            ThreadPoolExecutor p = pool;
            try {
                if (p == null) throw new RejectedExecutionException();
                p.execute(() -> handle(s));
            } catch (RejectedExecutionException busy) {
                closeQuietly(s);
            }
        }
    }

    // ---------------- request handling ----------------

    private void handle(Socket s) {
        try {
            s.setSoTimeout(5000);
            InetAddress addr = s.getInetAddress();
            if (!isLocalNetwork(addr)) { closeQuietly(s); return; }
            String ip = addr.getHostAddress();

            long deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS;
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String head = readHead(in, deadline);
            if (head == null) { send(out, 400, "text/plain", "Bad request"); return; }

            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) { send(out, 400, "text/plain", "Bad request"); return; }
            String method = req[0];
            String target = req[1];

            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    try { contentLength = Integer.parseInt(lines[i].substring(c + 1).trim()); }
                    catch (NumberFormatException e) { contentLength = -1; }
                }
            }
            if (contentLength < 0 || contentLength > MAX_BODY) { send(out, 413, "text/plain", "Too large"); return; }

            String path = target;
            String query = "";
            int qm = target.indexOf('?');
            if (qm >= 0) { path = target.substring(0, qm); query = target.substring(qm + 1); }
            Map<String, String> params = parseForm(query);

            if (method.equals("POST") && contentLength > 0) {
                byte[] body = readFully(in, contentLength, deadline);
                if (body == null) { send(out, 400, "text/plain", "Bad request"); return; }
                params.putAll(parseForm(new String(body, StandardCharsets.UTF_8)));
            }

            Backend b = backend;
            if (b == null) { send(out, 503, "text/plain", "The party is paused"); return; }

            if (!tokenOk(params.get("k"))) {
                // Wrong or missing key: allowed now and then (a stale QR code), never as a guessing game
                if (!allow("x" + ip, 30)) { send(out, 429, "text/plain", "Too many tries"); return; }
                send(out, 403, "text/html; charset=utf-8",
                        "<meta name=viewport content='width=device-width'><body style='font:20px sans-serif;"
                                + "background:#0b0614;color:#fff;text-align:center;padding:40px'>"
                                + "&#127908; Scan the QR code on the TV to join the party.</body>");
                return;
            }

            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                sendBytes(out, 200, "text/html; charset=utf-8", page, true);
            } else if (method.equals("GET") && path.equals("/api/state")) {
                send(out, 200, "application/json; charset=utf-8", b.stateJson());
            } else if (method.equals("GET") && path.equals("/api/search")) {
                String q = params.get("q");
                if (q == null || q.trim().isEmpty() || q.length() > 80) {
                    send(out, 400, "application/json", "{\"error\":\"Type a song name\"}");
                } else if (!allow("s" + ip, 20)) {
                    send(out, 429, "application/json", "{\"error\":\"Slow down a little and try again in a minute\"}");
                } else {
                    try {
                        send(out, 200, "application/json; charset=utf-8", b.searchJson(q.trim()));
                    } catch (Exception e) {
                        send(out, 502, "application/json", "{\"error\":\"Search failed. Try again.\"}");
                    }
                }
            } else if (method.equals("POST") && path.equals("/api/add")) {
                if (!allow("a" + ip, 8)) {
                    send(out, 429, "application/json", "{\"error\":\"Too many songs at once! Wait a minute.\"}");
                } else {
                    String err = b.add(params.get("id"), params.get("singer"), params.get("partner"));
                    send(out, err == null ? 200 : 400, "application/json; charset=utf-8",
                            err == null ? "{\"ok\":true}" : "{\"error\":" + jsonString(err) + "}");
                }
            } else if (method.equals("POST") && path.equals("/api/players/remove")) {
                if (!allow("r" + ip, 6)) {
                    send(out, 429, "application/json", "{\"error\":\"Too many changes at once! Wait a minute.\"}");
                } else {
                    String err = b.removePlayer(params.get("name"));
                    send(out, err == null ? 200 : 400, "application/json; charset=utf-8",
                            err == null ? "{\"ok\":true}" : "{\"error\":" + jsonString(err) + "}");
                }
            } else {
                send(out, 404, "text/plain", "Not found");
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(s);
        }
    }

    /** Only phones on the same home network may talk to the TV. */
    static boolean isLocalNetwork(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet6Address) {
            byte b0 = a.getAddress()[0];
            return (b0 & 0xFE) == 0xFC;   // fc00::/7 unique local
        }
        return false;
    }

    private boolean tokenOk(String given) {
        if (given == null) return false;
        return MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
    }

    /** Sliding one-minute window rate limit per phone. */
    private boolean allow(String key, int perMinute) {
        long now = System.currentTimeMillis();
        synchronized (hits) {
            if (hits.size() > 500) {
                // Forget devices that have been quiet for a minute; never reset active limits
                hits.values().removeIf(d -> d.isEmpty() || now - d.peekLast() > 60_000);
                if (hits.size() > 500) return false;
            }
            ArrayDeque<Long> q = hits.get(key);
            if (q == null) { q = new ArrayDeque<>(); hits.put(key, q); }
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= perMinute) return false;
            q.addLast(now);
            return true;
        }
    }

    private static String readHead(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0;
        while (buf.size() < MAX_HEAD) {
            if (System.currentTimeMillis() > deadline) return null;
            int b = in.read();
            if (b < 0) return null;
            buf.write(b);
            // look for \r\n\r\n
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = (b == '\r') ? 1 : 0;
            if (state == 4) return new String(buf.toByteArray(), 0, buf.size() - 4, StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    private static byte[] readFully(InputStream in, int n, long deadline) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            if (System.currentTimeMillis() > deadline) return null;
            int r = in.read(b, off, n - off);
            if (r < 0) return null;
            off += r;
        }
        return b;
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            if (m.size() > 10) break;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) {
            }
        }
        return m;
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        sendBytes(out, code, type, body.getBytes(StandardCharsets.UTF_8), false);
    }

    private static void sendBytes(OutputStream out, int code, String type, byte[] body, boolean isPage)
            throws IOException {
        StringBuilder h = new StringBuilder(320);
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("X-Frame-Options: DENY\r\n");
        if (isPage) {
            h.append("Content-Security-Policy: default-src 'none'; connect-src 'self'; ")
                    .append("img-src https://i.ytimg.com; style-src 'unsafe-inline'; ")
                    .append("script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }

    /** JSON string literal with escaping, for building small responses by hand. */
    public static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '<': b.append("\\u003c"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }
}
