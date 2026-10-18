package org.example.sharedcapes.publish;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
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
    private String status = "Sharing disabled";
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
        Path endpoint = capesDir.resolve("sharedcapes-url.txt");
        if (Files.isRegularFile(endpoint)) {
            try {
                String url = Files.readString(endpoint, StandardCharsets.UTF_8).trim();
                if (!url.isEmpty()) configured = new CapePublishingClient(url);
            } catch (IOException | IllegalArgumentException ex) { logger.warn("SharedCapes publisher endpoint is invalid"); }
        }
        publisher = configured;
    }

    public String status() { return status; }

    /** Called from EventUpdate; never blocks for network operations. */
    public void tick(boolean shareEnabled, String selected) {
        if (closed || busy) return;
        long now = System.currentTimeMillis();
        if (now < retryAt || now < nextPollAt) return;
        nextPollAt = now + 3000;
        if (publisher == null) {
            status = shareEnabled || previouslyPublished != null ? "No SharedCapes endpoint configured" : "Sharing disabled";
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
        if (!shareEnabled || selected == null || selected.equals("None")) {
            if (previouslyPublished == null) status = "Sharing disabled";
            else remove(user.getName(), uuid, user.getAccessToken());
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
                        status = "Publish failed; retry in 5 min";
                        logger.warn("SharedCapes publish failed (authentication, network, or cape format)");
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

    @Override public void close() {
        closed = true;
        if (publisher != null) publisher.close();
    }
}
