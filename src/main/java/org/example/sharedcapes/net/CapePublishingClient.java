package org.example.sharedcapes.net;

import org.example.sharedcapes.net.CapeDirectoryClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Version-independent opt-in cape publisher. No Minecraft dependencies.
 * All blocking network and file IO runs on a bounded dedicated background thread.
 * Minecraft access tokens go ONLY to Mojang's fixed HTTPS session join endpoint,
 * never to the SharedCapes Worker. SharedCapes bearer tokens stay in RAM only.
 */
public final class CapePublishingClient implements AutoCloseable {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SERVER_ID = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BEARER = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final URI MOJANG_JOIN = URI.create("https://sessionserver.mojang.com/session/minecraft/join");
    private final URI origin;
    private final ExecutorService executor;

    public CapePublishingClient(String endpoint) {
        // Enforce exactly the same HTTPS / loopback origin constraints as v0.2.
        try (CapeDirectoryClient validator = new CapeDirectoryClient(endpoint)) {
            this.origin = validator.origin();
        }
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4), job -> {
            Thread t = new Thread(job, "sharedcapes-publisher");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public CompletableFuture<Published> publish(String username, UUID uuid, String accessToken, Path file) {
        requireSession(username, uuid, accessToken);
        return publishWithProof(username, uuid, file,
                serverId -> joinMojang(uuid, accessToken, serverId));
    }

    public CompletableFuture<Void> unpublish(String username, UUID uuid, String accessToken) {
        requireSession(username, uuid, accessToken);
        return unpublishWithProof(username, uuid,
                serverId -> joinMojang(uuid, accessToken, serverId));
    }

    // Package-private proof seam permits reliable offline tests without any real Minecraft token.
    CompletableFuture<Published> publishWithProof(String username, UUID uuid, Path file, JoinProof proof) {
        requireIdentity(username, uuid);
        if (file == null || proof == null) throw new IllegalArgumentException("Missing cape or join proof");
        return schedule(() -> {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > CapeDirectoryClient.MAX_PNG_BYTES) {
                throw new IOException("Cape missing, symbolic link, or over 256 KiB");
            }
            byte[] cape = Files.readAllBytes(file);
            CapeDirectoryClient.checkPngHeader(cape);
            String expectedHash = sha256(cape);
            String bearer = authenticate(username, uuid, proof);
            byte[] response = request(origin.resolve("/v1/capes/me"), "PUT", cape, "image/png", bearer, 201);
            Map<?, ?> result = json(response);
            if (!CapeDirectoryClient.compactUuid(uuid).equals(result.get("uuid")) ||
                    !expectedHash.equals(result.get("hash")) || !HASH.matcher(expectedHash).matches()) throw new IOException("Publisher response did not match account/cape");
            Object p = result.get("path");
            String path = "/v1/capes/" + CapeDirectoryClient.compactUuid(uuid) + "/" + expectedHash + ".png";
            if (!path.equals(p)) throw new IOException("Invalid published cape path");
            return new Published(expectedHash);
        });
    }

    CompletableFuture<Void> unpublishWithProof(String username, UUID uuid, JoinProof proof) {
        requireIdentity(username, uuid);
        if (proof == null) throw new IllegalArgumentException("Missing join proof");
        return schedule(() -> {
            String bearer = authenticate(username, uuid, proof);
            Map<?, ?> result = json(request(origin.resolve("/v1/capes/me"), "DELETE", null, null, bearer, 200));
            if (!Boolean.TRUE.equals(result.get("removed"))) throw new IOException("Unexpected removal response");
            return null;
        });
    }

    private String authenticate(String username, UUID uuid, JoinProof proof) throws IOException {
        String body = "{\"username\":\"" + username + "\"}";
        Map<?, ?> c = json(request(origin.resolve("/v1/auth/challenge"), "POST", utf8(body), "application/json", null, 201));
        String challengeId = requireString(c.get("challengeId"), 36);
        String serverId = requireString(c.get("serverId"), 64);
        try { UUID.fromString(challengeId); }
        catch (IllegalArgumentException ex) { throw new IOException("Invalid challenge ID"); }
        if (!SERVER_ID.matcher(serverId).matches()) throw new IOException("Invalid session proof challenge");
        proof.join(serverId); // sends Minecraft credential only to Mojang, outside of SharedCapes request()
        Map<?, ?> verified = json(request(origin.resolve("/v1/auth/verify"), "POST",
                utf8("{\"challengeId\":\"" + challengeId + "\"}"), "application/json", null, 200));
        if (!CapeDirectoryClient.compactUuid(uuid).equals(verified.get("uuid"))) {
            throw new IOException("Verified Minecraft account differs from active profile");
        }
        String token = requireString(verified.get("token"), 4096);
        if (!BEARER.matcher(token).matches()) throw new IOException("Invalid publisher token");
        return token;
    }

    private static void joinMojang(UUID uuid, String accessToken, String serverId) throws IOException {
        // This is the documented Minecraft Java session join request. No custom host,
        // redirects, client-side URLs, or SharedCapes server ever receive accessToken.
        if (accessToken == null || accessToken.isBlank() || accessToken.length() > 8192) {
            throw new IOException("No valid Minecraft session token");
        }
        String body = "{\"accessToken\":\"" + escape(accessToken) + "\",\"selectedProfile\":\"" +
                CapeDirectoryClient.compactUuid(uuid) + "\",\"serverId\":\"" + serverId + "\"}";
        // Mojang's successful join endpoint sends HTTP 204 No Content.
        request(MOJANG_JOIN, "POST", utf8(body), "application/json", null, 204);
    }

    private static byte[] request(URI url, String method, byte[] body, String type, String bearer, int expected) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout((int) Duration.ofSeconds(4).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(6).toMillis());
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "SharedCapes-Client/0.4");
        // Wrangler's local dev simulator has no Cloudflare proxy to supply this
        // header. Never supply or override CF-Connecting-IP on public HTTPS API calls.
        if ("http".equalsIgnoreCase(url.getScheme()) &&
                ("127.0.0.1".equalsIgnoreCase(url.getHost()) || "localhost".equalsIgnoreCase(url.getHost()))) {
            connection.setRequestProperty("CF-Connecting-IP", "127.0.0.1");
        }
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", type);
        }
        try {
            if (body != null) try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            if (status != expected) {
                if (status == 400 && "PUT".equals(method)) throw new IOException("Cape rejected: use a plain RGB/RGBA 2:1 PNG, max 256 KiB");
                // Don't echo server response bodies: they may contain secrets.
                throw new IOException("Cape service " + (url.equals(MOJANG_JOIN) ? "Minecraft join" : "API") + " returned HTTP " + status);
            }
            if (expected == 204) return new byte[0];
            String ct = connection.getContentType();
            if (ct == null || !ct.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
                throw new IOException("Unexpected API response type");
            if (connection.getContentLengthLong() > 8192) throw new IOException("API response too large");
            try (InputStream in = connection.getInputStream()) {
                byte[] result = in.readNBytes(8193);
                if (result.length > 8192) throw new IOException("API response too large");
                return result;
            }
        } finally { connection.disconnect(); }
    }

    private static Map<?, ?> json(byte[] bytes) throws IOException {
        // Minimal bounded JSON parser with duplicate-key rejection (already used by v0.2).
        String value = new String(bytes, StandardCharsets.UTF_8);
        Object parsed = CapeDirectoryClient.Json.parse(value);
        if (!(parsed instanceof Map<?, ?> m)) throw new IOException("Expected JSON object");
        return m;
    }

    private static String requireString(Object value, int max) throws IOException {
        if (!(value instanceof String s) || s.isEmpty() || s.length() > max) throw new IOException("Invalid API field");
        return s;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 unavailable", ex); }
    }
    private static void requireIdentity(String username, UUID uuid) {
        if (username == null || !USERNAME.matcher(username).matches() || uuid == null) throw new IllegalArgumentException("Invalid Minecraft profile");
    }
    private static void requireSession(String username, UUID uuid, String token) {
        requireIdentity(username, uuid);
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Signed-in Minecraft account required");
    }
    private static String escape(String value) {
        StringBuilder s = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') s.append('\\');
            if (ch < 0x20) s.append(String.format("\\u%04x", (int)ch));
            else s.append(ch);
        }
        return s.toString();
    }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private <T> CompletableFuture<T> schedule(Work<T> work) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return work.run(); }
                catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
            }, executor);
        } catch (java.util.concurrent.RejectedExecutionException e) { return CompletableFuture.failedFuture(e); }
    }
    @Override public void close() { executor.shutdownNow(); }
    @FunctionalInterface interface JoinProof { void join(String serverId) throws IOException; }
    @FunctionalInterface private interface Work<T> { T run() throws IOException; }
    public record Published(String hash) { }
}
