package org.example.sharedcapes.net;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Dependency-free integration checks; run separately from Gradle. */
public final class CapeDirectoryClientTest {
    private static int passed;
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String ID = CapeDirectoryClient.compactUuid(PLAYER);
    private static final String HASH = "a".repeat(64);
    private static final String PATH = "/v1/capes/" + ID + "/" + HASH + ".png";

    public static void main(String[] args) throws Exception {
        check("UUID normalization", ID::length, 32);
        reject("Remote HTTP origin", () -> new CapeDirectoryClient("http://example.com"));
        reject("Origin with path", () -> new CapeDirectoryClient("https://example.com/other"));
        reject("Origin with credentials", () -> new CapeDirectoryClient("https://person:secret@example.com"));
        reject("Lookalike loopback", () -> new CapeDirectoryClient("http://localhost.evil.example"));

        String response = "{\"capes\":{\"" + ID + "\":{\"hash\":\"" + HASH + "\",\"path\":\"" + PATH + "\"}},\"ttlSeconds\":300}";
        CapeDirectoryClient.Lookup parsed = CapeDirectoryClient.parseLookup(response, List.of(ID));
        check("Valid directory response", () -> parsed.capes().get(PLAYER).hash().length(), 64);
        rejectIo("Unexpected UUID", () -> CapeDirectoryClient.parseLookup(response, List.of()));
        rejectIo("Path traversal", () -> CapeDirectoryClient.parseLookup(response.replace(PATH, "/v1/capes/../wrong.png"), List.of(ID)));
        rejectIo("Hash mismatch", () -> CapeDirectoryClient.parseLookup(response.replace(PATH, PATH.replace(HASH, "b".repeat(64))), List.of(ID)));
        rejectIo("Bad JSON", () -> CapeDirectoryClient.parseLookup(response + "[]", List.of(ID)));
        rejectIo("Unbounded TTL", () -> CapeDirectoryClient.parseLookup(response.replace("300}", "99999}"), List.of(ID)));
        rejectIo("Duplicate key", () -> CapeDirectoryClient.parseLookup("{\"capes\":{},\"capes\":{},\"ttlSeconds\":300}", List.of(ID)));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/health", ex -> {
            byte[] data = "{\"status\":\"ok\",\"protocol\":1}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, data.length);
            ex.getResponseBody().write(data);
            ex.close();
        });
        server.createContext("/v1/capes/lookup", ex -> {
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, data.length);
            ex.getResponseBody().write(data);
            ex.close();
        });
        server.createContext(PATH, ex -> {
            byte[] image = pngHeaderForTesting();
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, image.length);
            ex.getResponseBody().write(image);
            ex.close();
        });
        server.start();
        try (CapeDirectoryClient client = new CapeDirectoryClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            if (!client.health().get(4, TimeUnit.SECONDS)) throw new AssertionError("Health failed");
            passed++; System.out.println("PASS: HTTP health");
            CapeDirectoryClient.Lookup found = client.lookup(List.of(PLAYER)).get(4, TimeUnit.SECONDS);
            check("HTTP lookup", () -> found.capes().size(), 1);
            byte[] downloaded = client.download(found.capes().get(PLAYER)).get(4, TimeUnit.SECONDS);
            check("Bounded PNG download", () -> downloaded.length, 57);
        } finally { server.stop(0); }
        System.out.println("OK: " + passed + " network-core checks passed");
    }

    private static byte[] pngHeaderForTesting() {
        byte[] b = new byte[57];
        byte[] magic = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        System.arraycopy(magic, 0, b, 0, 8);
        b[11] = 13;
        b[12] = 'I'; b[13] = 'H'; b[14] = 'D'; b[15] = 'R';
        b[19] = 64; b[23] = 32; b[24] = 8; b[25] = 6;
        return b; // header-only synthetic fixture, not a renderable PNG
    }

    private static void check(String name, CheckedInt got, int expected) throws Exception {
        int actual = got.get();
        if (actual != expected) throw new AssertionError(name + " expected " + expected + " but was " + actual);
        passed++; System.out.println("PASS: " + name);
    }
    private static void reject(String name, Runnable runnable) {
        try { runnable.run(); throw new AssertionError("Expected rejection: " + name); }
        catch (IllegalArgumentException expected) { passed++; System.out.println("PASS: " + name); }
    }
    private static void rejectIo(String name, IoRunnable runnable) throws Exception {
        try { runnable.run(); throw new AssertionError("Expected I/O rejection: " + name); }
        catch (IOException expected) { passed++; System.out.println("PASS: " + name); }
    }
    @FunctionalInterface private interface CheckedInt { int get() throws Exception; }
    @FunctionalInterface private interface IoRunnable { void run() throws Exception; }
}
