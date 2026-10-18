package org.example.sharedcapes.publish;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.example.sharedcapes.net.CapeDirectoryClient;
import org.example.sharedcapes.net.CapePublishingClient;
import org.rusherhack.core.logging.ILogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/** 1.21.11-specific adaptation of the version-independent publisher. Client-thread only. */
public final class CapePublishingManager implements AutoCloseable {
    private final ILogger logger;
    private final Path capesDir;
    private final Path marker;
    private final CapePublishingClient publisher;
    private UUID previouslyPublished;
    private String publishedFingerprint;
    private String status = "No cape selected";
    private long nextPollAt, retryAt;
    private boolean busy, closed;

    public CapePublishingManager(ILogger logger, Path dir) {
        this.logger = logger;
        this.capesDir = dir.toAbsolutePath().normalize();
        this.marker = capesDir.resolve("sharedcapes-published-uuid.txt");
        try {
            if (Files.isRegularFile(marker)) previouslyPublished = UUID.fromString(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (IOException | IllegalArgumentException ex) { logger.warn("SharedCapes published marker is unreadable"); }
        CapePublishingClient configured = null;
        try {
            configured = new CapePublishingClient(CapeDirectoryClient.configuredEndpoint());
        } catch (IllegalArgumentException ex) {
            logger.warn("SharedCapes publisher endpoint is invalid");
        }
        publisher = configured;
    }

    public String status() { return status; }

    /** Called from EventUpdate; never blocks for network operations. */
    public void tick(boolean capesModuleEnabled, String selected) {
        if (closed || busy) return;
        long now = System.currentTimeMillis();
        if (now < retryAt || now < nextPollAt) return;
        nextPollAt = now + 3000;
        boolean shouldPublish = CapeSelectionPolicy.shouldPublish(capesModuleEnabled, selected);
        if (!shouldPublish && previouslyPublished == null) {
            status = capesModuleEnabled ? "No cape selected" : "Capes module disabled";
            return;
        }
        if (publisher == null) {
            status = "No SharedCapes endpoint configured";
            return;
        }
        User user = Minecraft.getInstance().getUser();
        if (user == null || user.getProfileId() == null || user.getAccessToken() == null || user.getAccessToken().isBlank()) {
            status = "Signed-in Minecraft Java account required";
            return;
        }
        UUID uuid = user.getProfileId();
        if (previouslyPublished != null && !previouslyPublished.equals(uuid)) {
            status = "Switch to the originally published account to unshare";
            return;
        }
        if (!shouldPublish) {
            remove(user.getName(), uuid, user.getAccessToken());
            return;
        }
        Path cape = capesDir.resolve(selected).normalize();
        if (!cape.startsWith(capesDir) || !selected.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            status = "Invalid cape filename";
            return;
        }
        String fingerprint;
        try {
            if (!Files.isRegularFile(cape, LinkOption.NOFOLLOW_LINKS)) { status = "Cape missing or symbolic link"; return; }
            fingerprint = uuid + ":" + cape.getFileName() + ":" + Files.size(cape) + ":" + Files.getLastModifiedTime(cape).toMillis();
        } catch (IOException ex) { status = "Selected cape unreadable"; return; }
        if (previouslyPublished != null && fingerprint.equals(publishedFingerprint)) { status = "Cape shared"; return; }
        publish(user.getName(), uuid, user.getAccessToken(), cape, fingerprint);
    }

    private void publish(String username, UUID uuid, String accessToken, Path cape, String fingerprint) {
        busy = true;
        status = "Publishing...";
        publisher.publish(username, uuid, accessToken, cape).whenComplete((result, error) ->
                Minecraft.getInstance().execute(() -> {
                    busy = false;
                    if (closed) return;
                    if (error != null) {
                        retryAt = System.currentTimeMillis() + 300000;
                        String detail = safeFailureReason(error);
                        status = "Publish failed: " + detail + "; retry in 5 min";
                        logger.warn("SharedCapes publishing failure: " + detail);
                    } else {
                        previouslyPublished = uuid;
                        publishedFingerprint = fingerprint;
                        retryAt = 0;
                        try { Files.writeString(marker, uuid + "\n", StandardCharsets.UTF_8); }
                        catch (IOException ex) { logger.warn("SharedCapes published but local revocation marker could not be saved"); }
                        status = "Cape shared";
                        logger.info("SharedCapes published selected cape");
                    }
                }));
    }

    private void remove(String username, UUID uuid, String accessToken) {
        busy = true;
        status = "Removing shared cape...";
        publisher.unpublish(username, uuid, accessToken).whenComplete((result, error) ->
                Minecraft.getInstance().execute(() -> {
                    busy = false;
                    if (closed) return;
                    if (error != null) {
                        retryAt = System.currentTimeMillis() + 60000;
                        status = "Unshare failed; retry in 60 sec while game is open";
                        logger.warn("SharedCapes unpublish failed (authentication or network)");
                    } else {
                        previouslyPublished = null;
                        publishedFingerprint = null;
                        retryAt = 0;
                        try { Files.deleteIfExists(marker); }
                        catch (IOException ex) { logger.warn("Could not remove SharedCapes marker"); }
                        status = "Shared cape removed";
                        logger.info("SharedCapes unpublished cape");
                    }
                }));
    }

    /** Human-readable error reasons with a strict allowlist: never log session or bearer tokens. */
    private static String safeFailureReason(Throwable throwable) {
        Throwable cause = throwable;
        for (int depth = 0; depth < 6 && cause.getCause() != null; depth++) {
            // CompletableFuture commonly wraps the original IOException.
            if (!(cause instanceof java.util.concurrent.CompletionException) &&
                    !(cause instanceof java.util.concurrent.ExecutionException)) break;
            cause = cause.getCause();
        }
        if (cause instanceof java.net.ConnectException) return "Local API connection refused";
        if (cause instanceof java.net.SocketTimeoutException) return "Network timeout";
        if (cause instanceof java.net.UnknownHostException) return "API hostname could not be resolved";
        if (cause instanceof javax.net.ssl.SSLException) return "TLS connection failure";
        if (cause instanceof java.io.IOException) {
            String message = cause.getMessage();
            if (message != null) {
                if (message.matches("Cape service (API|Minecraft join) returned HTTP [0-9]{3}"))
                    return message;
                if (message.equals("Minecraft join rejected: invalid session token (refresh Prism account login)") ||
                        message.equals("Minecraft join rejected: multiplayer permission denied") ||
                        message.equals("Minecraft join rejected: multiplayer account restricted") ||
                        message.equals("Minecraft join rejected: HTTP 403 (reason unavailable)"))
                    return message;
                if (message.equals("Cape missing, symbolic link, or over 256 KiB"))
                    return "Cape file missing or exceeds 256 KiB";
                if (message.equals("Invalid cape PNG length") || message.equals("Invalid PNG signature") ||
                        message.equals("Missing PNG IHDR") || message.equals("Unsupported cape format"))
                    return "Unsupported cape PNG format";
                if (message.equals("Verified Minecraft account differs from active profile"))
                    return "Verified Minecraft account mismatch";
                if (message.equals("Publisher response did not match account/cape"))
                    return "Published cape identity/hash mismatch";
                if (message.equals("Invalid published cape path"))
                    return "Invalid published cape path";
                if (message.equals("Invalid publisher token"))
                    return "Invalid publisher token";
                if (message.equals("No valid Minecraft session token"))
                    return "Minecraft session is missing or invalid";
                if (message.equals("Invalid challenge ID") || message.equals("Invalid session proof challenge"))
                    return "Invalid authentication challenge";
                if (message.equals("Unexpected API response type") || message.equals("Expected JSON object") ||
                        message.equals("Invalid API field") || message.equals("API response too large") ||
                        message.equals("Unexpected removal response"))
                    return "Unexpected API response";
            }
            return "I/O failure (" + cause.getClass().getSimpleName() + ")";
        }
        if (cause instanceof IllegalArgumentException) return "Invalid profile or cape selection";
        if (cause instanceof SecurityException) return "Permission/security exception";
        // Only the exception class is included; arbitrary exception text can contain credentials.
        return "Client exception (" + cause.getClass().getSimpleName() + ")";
    }

    @Override public void close() {
        closed = true;
        if (publisher != null) publisher.close();
    }
}
