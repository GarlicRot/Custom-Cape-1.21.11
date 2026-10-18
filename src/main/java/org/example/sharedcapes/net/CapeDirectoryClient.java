package org.example.sharedcapes.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft-version-independent SharedCapes Protocol v1 HTTP client.
 *
 * Never call blocking I/O on a Minecraft game/render thread. Public methods
 * schedule work on two daemon threads with a bounded queue. This class does
 * not interact with any Minecraft class, profile token or local game server.
 *
 * v0.2 scope: read-only health, directory lookup, bounded image download.
 * Authentication/upload and texture registration belong to later stages.
 */
public final class CapeDirectoryClient implements AutoCloseable {
    public static final String DEFAULT_ENDPOINT = "https://capes.garlicrot.dev";
    public static final String ENDPOINT_PROPERTY = "sharedcapes.apiBaseUrl";

    public static String configuredEndpoint() {
        String override = System.getProperty(ENDPOINT_PROPERTY);
        if (override == null || override.isBlank()) {
            return DEFAULT_ENDPOINT;
        }
        return override.trim();
    }
    public static final int PROTOCOL = 1;
    public static final int MAX_BATCH = 64;
    public static final int MAX_PNG_BYTES = 256 * 1024;
    public static final int MAX_JSON_BYTES = 64 * 1024;
    private static final Pattern UUID_HEX = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern HASH_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern IMAGE_PATH = Pattern.compile("/v1/capes/([0-9a-f]{32})/([0-9a-f]{64})\\.png");
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private final URI origin;
    private final ExecutorService io;

    public CapeDirectoryClient(String origin) {
        this(URI.create(origin));
    }

    public CapeDirectoryClient(URI origin) {
        Objects.requireNonNull(origin, "origin");
        String scheme = origin.getScheme();
        String host = origin.getHost();
        boolean loopback = host != null &&
                (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
        if (scheme == null || host == null || origin.getUserInfo() != null ||
                origin.getRawQuery() != null || origin.getRawFragment() != null ||
                !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/")) ||
                !(scheme.equalsIgnoreCase("https") || (loopback && scheme.equalsIgnoreCase("http")))) {
            throw new IllegalArgumentException("Expected an HTTPS origin (HTTP only for localhost testing)");
        }
        this.origin = URI.create(origin.toString().replaceAll("/+$", ""));
        this.io = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), runnable -> {
                    Thread thread = new Thread(runnable, "sharedcapes-http-" + THREAD_ID.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public URI origin() { return origin; }

    public CompletableFuture<Boolean> health() {
        return submit(() -> {
            Object parsed = Json.parse(new String(request("GET", "/v1/health", null, null, 4096), StandardCharsets.UTF_8));
            Map<?, ?> object = requireObject(parsed);
            return "ok".equals(object.get("status")) && number(object.get("protocol")) == PROTOCOL;
        });
    }

    public CompletableFuture<Lookup> lookup(Collection<UUID> players) {
        Objects.requireNonNull(players, "players");
        List<String> ids = players.stream().filter(Objects::nonNull).distinct().limit(MAX_BATCH + 1)
                .map(CapeDirectoryClient::compactUuid).toList();
        if (ids.size() > MAX_BATCH) throw new IllegalArgumentException("Maximum 64 UUIDs per lookup");
        StringBuilder body = new StringBuilder("{\"uuids\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) body.append(',');
            body.append('"').append(ids.get(i)).append('"');
        }
        body.append("]}");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        return submit(() -> parseLookup(new String(request("POST", "/v1/capes/lookup", payload,
                "application/json", MAX_JSON_BYTES), StandardCharsets.UTF_8), ids));
    }

    /** Download bytes only. Decode/register Minecraft textures on the Minecraft thread. */
    public CompletableFuture<byte[]> download(CapeRef ref) {
        Objects.requireNonNull(ref, "ref");
        validateRef(ref);
        return submit(() -> {
            byte[] image = request("GET", ref.path(), null, null, MAX_PNG_BYTES);
            checkPngHeader(image);
            return image;
        });
    }

    static Lookup parseLookup(String json, Collection<String> requested) throws IOException {
        Map<?, ?> root = requireObject(Json.parse(json));
        long ttl = number(root.get("ttlSeconds"));
        if (ttl < 1 || ttl > 3600) throw new IOException("Invalid lookup TTL");
        Map<?, ?> rawCapes = requireObject(root.get("capes"));
        if (rawCapes.size() > MAX_BATCH) throw new IOException("Too many cape entries");
        Map<UUID, CapeRef> found = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawCapes.entrySet()) {
            if (!(e.getKey() instanceof String id) || !UUID_HEX.matcher(id).matches() || !requested.contains(id)) {
                throw new IOException("Unrequested or invalid player ID");
            }
            Map<?, ?> info = requireObject(e.getValue());
            if (!(info.get("hash") instanceof String hash) || !(info.get("path") instanceof String path)) {
                throw new IOException("Invalid cape entry");
            }
            try {
                CapeRef ref = new CapeRef(UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12)
                        + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20)), hash, path);
                validateRef(ref);
                found.put(ref.player(), ref);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Invalid cape entry", ex);
            }
        }
        return new Lookup(Collections.unmodifiableMap(found), (int) ttl);
    }

    private static void validateRef(CapeRef ref) {
        Matcher m = IMAGE_PATH.matcher(ref.path());
        if (!HASH_HEX.matcher(ref.hash()).matches() || !m.matches() ||
                !compactUuid(ref.player()).equals(m.group(1)) || !ref.hash().equals(m.group(2))) {
            throw new IllegalArgumentException("Cape path must match UUID and SHA-256 hash");
        }
    }

    static void checkPngHeader(byte[] bytes) throws IOException {
        byte[] magic = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length < 57 || bytes.length > MAX_PNG_BYTES) throw new IOException("Invalid cape PNG length");
        for (int i = 0; i < magic.length; i++) if (bytes[i] != magic[i]) throw new IOException("Invalid PNG signature");
        if (readU32(bytes, 8) != 13 || bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            throw new IOException("Missing PNG IHDR");
        }
        long w = readU32(bytes, 16), h = readU32(bytes, 20);
        if (w < 64 || w > 512 || h < 32 || h > 256 || w != h * 2 || bytes[24] != 8 ||
                !(bytes[25] == 2 || bytes[25] == 6) || bytes[26] != 0 || bytes[27] != 0 || bytes[28] != 0) {
            throw new IOException("Unsupported cape format");
        }
        // Minecraft's NativeImage decoder provides the complete decoding check later.
    }

    private static long readU32(byte[] b, int i) {
        return ((long) (b[i] & 255) << 24) | ((long) (b[i+1] & 255) << 16) |
                ((long) (b[i+2] & 255) << 8) | (b[i+3] & 255);
    }

    private byte[] request(String method, String path, byte[] payload, String mime, int maxBytes) throws IOException {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("..") || path.contains("?") || path.contains("#")) {
            throw new IOException("Invalid API path");
        }
        URL target = origin.resolve(path).toURL();
        HttpURLConnection con = (HttpURLConnection) target.openConnection();
        con.setInstanceFollowRedirects(false);
        con.setConnectTimeout((int) Duration.ofSeconds(4).toMillis());
        con.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        con.setRequestMethod(method);
        con.setRequestProperty("Accept", method.equals("GET") && path.endsWith(".png") ? "image/png" : "application/json");
        con.setRequestProperty("User-Agent", "SharedCapes-Client/0.2");
        if (payload != null) {
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(payload.length);
            con.setRequestProperty("Content-Type", mime);
        }
        try {
            if (payload != null) try (OutputStream out = con.getOutputStream()) { out.write(payload); }
            int status = con.getResponseCode();
            if (status != 200) throw new IOException("SharedCapes HTTP " + status);
            String contentType = con.getContentType();
            String expected = path.endsWith(".png") ? "image/png" : "application/json";
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith(expected)) {
                throw new IOException("Unexpected content type");
            }
            long contentLength = con.getContentLengthLong();
            if (contentLength > maxBytes) throw new IOException("Response too large");
            try (InputStream in = con.getInputStream()) {
                byte[] bytes = in.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) throw new IOException("Response too large");
                return bytes;
            }
        } finally {
            con.disconnect();
        }
    }

    private <T> CompletableFuture<T> submit(IoTask<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return task.run(); }
                catch (IOException ex) { throw new java.util.concurrent.CompletionException(ex); }
            }, io);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private static Map<?, ?> requireObject(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException("Expected JSON object");
        return map;
    }

    private static long number(Object value) throws IOException {
        if (!(value instanceof Long n)) throw new IOException("Expected JSON integer");
        return n;
    }

    public static String compactUuid(UUID id) { return id.toString().replace("-", ""); }

    @Override public void close() { io.shutdownNow(); }

    @FunctionalInterface private interface IoTask<T> { T run() throws IOException; }

    public record CapeRef(UUID player, String hash, String path) { }
    public record Lookup(Map<UUID, CapeRef> capes, int ttlSeconds) { }

    /** Minimal strict JSON parser; independent of bundled Minecraft libraries. */
    static final class Json {
        private final String input;
        private int pos;
        private Json(String input) { this.input = input; }
        static Object parse(String input) throws IOException {
            if (input.length() > MAX_JSON_BYTES) throw new IOException("JSON too large");
            Json p = new Json(input);
            Object value = p.value(0);
            p.space();
            if (p.pos != input.length()) throw new IOException("Trailing JSON data");
            return value;
        }
        private void space() {
            while (pos < input.length() && " \n\r\t".indexOf(input.charAt(pos)) >= 0) pos++;
        }
        private char peek() throws IOException {
            if (pos >= input.length()) throw new IOException("Unexpected JSON end");
            return input.charAt(pos);
        }
        private void expect(char ch) throws IOException {
            space();
            if (peek() != ch) throw new IOException("Expected " + ch);
            pos++;
        }
        private Object value(int depth) throws IOException {
            if (depth > 8) throw new IOException("JSON nesting too deep");
            space();
            char ch = peek();
            if (ch == '{') {
                pos++;
                Map<String, Object> object = new LinkedHashMap<>();
                space();
                if (peek() == '}') { pos++; return object; }
                while (true) {
                    space();
                    if (peek() != '"') throw new IOException("Expected object key");
                    String key = string();
                    expect(':');
                    Object val = value(depth + 1);
                    if (object.putIfAbsent(key, val) != null) throw new IOException("Duplicate JSON key");
                    space();
                    if (peek() == '}') { pos++; return object; }
                    expect(',');
                }
            }
            if (ch == '[') {
                pos++;
                List<Object> list = new ArrayList<>();
                space();
                if (peek() == ']') { pos++; return list; }
                while (true) {
                    list.add(value(depth + 1));
                    space();
                    if (peek() == ']') { pos++; return list; }
                    expect(',');
                }
            }
            if (ch == '"') return string();
            if (ch == '-' || (ch >= '0' && ch <= '9')) {
                int from = pos;
                if (ch == '-') pos++;
                if (peek() == '0') pos++;
                else {
                    if (peek() < '1' || peek() > '9') throw new IOException("Invalid JSON number");
                    while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
                }
                if (pos < input.length() && ".eE".indexOf(input.charAt(pos)) >= 0) throw new IOException("Noninteger JSON number");
                try { return Long.parseLong(input.substring(from, pos)); }
                catch (NumberFormatException ex) { throw new IOException("Invalid JSON number"); }
            }
            for (String literal : List.of("true", "false", "null")) {
                if (input.startsWith(literal, pos)) {
                    pos += literal.length();
                    return switch (literal) {
                        case "true" -> Boolean.TRUE;
                        case "false" -> Boolean.FALSE;
                        default -> JsonNull.INSTANCE;
                    };
                }
            }
            throw new IOException("Invalid JSON token");
        }
        private String string() throws IOException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = peek(); pos++;
                if (c == '"') return out.toString();
                if (c < 32) throw new IOException("Control character in JSON");
                if (c == '\\') {
                    char escape = peek(); pos++;
                    switch (escape) {
                        case '"', '\\', '/' -> out.append(escape);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > input.length()) throw new IOException("Invalid JSON escape");
                            String hex = input.substring(pos, pos + 4);
                            try { out.append((char) Integer.parseInt(hex, 16)); }
                            catch (NumberFormatException ex) { throw new IOException("Invalid JSON escape"); }
                            pos += 4;
                        }
                        default -> throw new IOException("Invalid JSON escape");
                    }
                } else out.append(c);
                if (out.length() > MAX_JSON_BYTES) throw new IOException("JSON string too long");
            }
        }
        private enum JsonNull { INSTANCE }
    }
}
