package org.example.sharedcapes.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.sharedcapes.net.CapePublishingClient.Published;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone mock-backend tests. No real Mojang accounts, credentials or Cloudflare services. */
public final class CapePublishingClientTest {
    static final UUID USER = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    static final String COMPACT = CapeDirectoryClient.compactUuid(USER);
    static final String CHALLENGE = "30a49a74-f6e3-4c84-a6ee-96d4ea1a30b0";
    static final String SERVER_ID = "a".repeat(64);
    static final String BEARER = "aabbcc.ddeeff";
    static final AtomicInteger checks = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        rejectsUnsafeEndpoints();
        exercisesPublishAndUnpublish();
        rejectsWrongProfile();
        rejectsInvalidCape();
        System.out.println("OK: " + checks.get() + " publishing-core checks passed");
    }

    static void rejectsUnsafeEndpoints() {
        for (String origin : new String[]{"http://rusherhack.example", "https://user:pass@example.com", "http://127.0.0.1.evil.net"}) {
            try (CapePublishingClient ignored = new CapePublishingClient(origin)) { throw new AssertionError("Accepted " + origin); }
            catch (IllegalArgumentException expected) { pass("Rejected unsafe origin"); }
        }
    }

    static void exercisesPublishAndUnpublish() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger published = new AtomicInteger();
        AtomicInteger removed = new AtomicInteger();
        byte[] png = png();
        String hash = sha(png);
        server.createContext("/v1/auth/challenge", ex -> {
            check("POST".equals(ex.getRequestMethod()), "Challenge method");
            reply(ex, 201, "{\"challengeId\":\"" + CHALLENGE + "\",\"serverId\":\"" + SERVER_ID + "\",\"expiresAt\":1234567890}");
        });
        server.createContext("/v1/auth/verify", ex -> {
            check(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).contains(CHALLENGE), "Verify challenge");
            reply(ex, 200, "{\"uuid\":\"" + COMPACT + "\",\"token\":\"" + BEARER + "\",\"expiresIn\":900}");
        });
        server.createContext("/v1/capes/me", ex -> {
            check(("Bearer " + BEARER).equals(ex.getRequestHeaders().getFirst("Authorization")), "Authenticated cape operation");
            check(!ex.getRequestHeaders().toString().contains("MINECRAFT_SECRET"), "Minecraft token never included");
            if (ex.getRequestMethod().equals("PUT")) {
                byte[] sent = ex.getRequestBody().readAllBytes();
                check(java.util.Arrays.equals(sent, png), "Raw PNG uploaded");
                published.incrementAndGet();
                reply(ex, 201, "{\"uuid\":\"" + COMPACT + "\",\"hash\":\"" + hash + "\",\"path\":\"/v1/capes/" + COMPACT + "/" + hash + ".png\"}");
            } else {
                removed.incrementAndGet();
                reply(ex, 200, "{\"removed\":true}");
            }
        });
        server.start();
        Path file = Files.createTempFile("sharedcapes-test", ".png");
        try (CapePublishingClient client = new CapePublishingClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            Files.write(file, png);
            AtomicInteger joined = new AtomicInteger();
            Published r = client.publishWithProof("TestPlayer", USER, file, id -> {
                check(SERVER_ID.equals(id), "Join proof challenged with server ID");
                joined.incrementAndGet();
            }).get();
            check(hash.equals(r.hash()), "Published hash verified");
            client.unpublishWithProof("TestPlayer", USER, id -> joined.incrementAndGet()).get();
            check(published.get() == 1 && removed.get() == 1 && joined.get() == 2, "Publish and revoke completed");
        } finally { Files.deleteIfExists(file); server.stop(0); }
    }

    static void rejectsWrongProfile() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/auth/challenge", ex -> reply(ex, 201, "{\"challengeId\":\"" + CHALLENGE + "\",\"serverId\":\"" + SERVER_ID + "\"}"));
        server.createContext("/v1/auth/verify", ex -> reply(ex, 200, "{\"uuid\":\"" + "b".repeat(32) + "\",\"token\":\"" + BEARER + "\"}"));
        server.start();
        try (CapePublishingClient client = new CapePublishingClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            try { client.unpublishWithProof("TestPlayer", USER, id -> {}).get(); throw new AssertionError("Wrong account accepted"); }
            catch (ExecutionException expected) { pass("Rejected wrong authenticated profile"); }
        } finally { server.stop(0); }
    }

    static void rejectsInvalidCape() throws Exception {
        Path file = Files.createTempFile("sharedcapes-invalid", ".png");
        try (CapePublishingClient client = new CapePublishingClient("http://127.0.0.1:8787")) {
            Files.write(file, new byte[]{1, 2, 3, 4});
            try { client.publishWithProof("TestPlayer", USER, file, id -> {}).get(); throw new AssertionError("Invalid cape accepted"); }
            catch (ExecutionException expected) { pass("Rejected malformed PNG before network"); }
        } finally { Files.deleteIfExists(file); }
    }

    static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(1, 1, 0xff229955);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
    static String sha(byte[] b) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
    static void reply(HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }
    static void check(boolean condition, String msg) { if (!condition) throw new AssertionError(msg); pass(msg); }
    static void pass(String msg) { checks.incrementAndGet(); System.out.println("PASS: " + msg); }
}
