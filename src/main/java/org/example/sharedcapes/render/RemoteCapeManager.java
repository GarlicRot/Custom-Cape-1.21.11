package org.example.sharedcapes.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import org.example.sharedcapes.net.CapeDirectoryClient;
import org.rusherhack.core.logging.ILogger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Minecraft 1.21.11 render adapter for the version-independent cape directory.
 * All public methods must be called on the Minecraft client/render thread.
 * Network calls run on CapeDirectoryClient's bounded IO executor.
 * This adapter is read-only: it never publishes a cape or authenticates.
 */
public final class RemoteCapeManager implements AutoCloseable {
    private static final int MAX_TRACKED = 128;
    private static final int MAX_BATCH = 64;
    private static final int MAX_DOWNLOADS = 2;
    private static final long FAILURE_RETRY_MS = 30_000;
    private static final long BATCH_PAUSE_MS = 750;

    private final ILogger logger;
    private final CapeDirectoryClient directory;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>(32, 0.75f, true);
    private final LinkedHashSet<UUID> observed = new LinkedHashSet<>();
    private final Map<UUID, CapeDirectoryClient.CapeRef> downloadQueue = new LinkedHashMap<>();
    private Object world;
    private int generation;
    private int inFlightDownloads;
    private boolean inFlightLookup;
    private boolean active;
    private boolean closed;
    private long nextBatchAt;

    public RemoteCapeManager(ILogger logger, Path capesDirectory) {
        this.logger = logger;
        CapeDirectoryClient configured = null;
        Path urlFile = capesDirectory.resolve("sharedcapes-url.txt");
        if (Files.isRegularFile(urlFile)) {
            try {
                String address = Files.readString(urlFile, StandardCharsets.UTF_8).trim();
                if (!address.isEmpty()) {
                    configured = new CapeDirectoryClient(address);
                    logger.info("SharedCapes read-only directory configured (off by default)");
                }
            } catch (IOException | IllegalArgumentException e) {
                logger.warn("Invalid SharedCapes endpoint file; remote capes disabled");
            }
        }
        this.directory = configured;
    }

    /** Called by the renderer; does not perform network or disk IO. */
    public ClientAsset.ResourceTexture get(UUID playerId) {
        if (closed || !active || directory == null || playerId == null) return null;
        Entry existing = entries.get(playerId);
        if (observed.size() < MAX_TRACKED) observed.add(playerId);
        return existing == null ? null : existing.texture;
    }

    /** Called from RusherHack's client update event. */
    public void tick(boolean showSharedCapes) {
        if (closed) return;
        Minecraft mc = Minecraft.getInstance();
        Object currentWorld = mc.level;
        if (currentWorld != world) {
            reset();
            world = currentWorld;
        }
        boolean enabled = showSharedCapes && currentWorld != null && directory != null;
        if (!enabled) {
            if (active) reset();
            active = false;
            return;
        }
        active = true;
        pumpDownloads();
        long now = System.currentTimeMillis();
        if (inFlightLookup || now < nextBatchAt) return;

        List<UUID> batch = new ArrayList<>(MAX_BATCH);
        Iterator<UUID> iterator = observed.iterator();
        while (iterator.hasNext() && batch.size() < MAX_BATCH) {
            UUID id = iterator.next();
            iterator.remove();
            Entry entry = findOrCreate(id);
            if (entry != null && entry.refreshAt <= now) {
                entry.refreshAt = now + FAILURE_RETRY_MS; // suppress repeated requests while in flight
                batch.add(id);
            }
        }
        if (batch.isEmpty()) return;
        inFlightLookup = true;
        nextBatchAt = now + BATCH_PAUSE_MS;
        int expectedGeneration = generation;
        CompletableFuture<CapeDirectoryClient.Lookup> task = directory.lookup(batch);
        task.whenComplete((lookup, error) -> mc.execute(() -> {
            if (closed || expectedGeneration != generation) return;
            inFlightLookup = false;
            long completedAt = System.currentTimeMillis();
            if (error != null) {
                for (UUID id : batch) {
                    Entry entry = entries.get(id);
                    if (entry != null) {
                        clearTexture(entry);
                        entry.hash = null;
                        entry.refreshAt = completedAt + FAILURE_RETRY_MS;
                    }
                }
                return;
            }
            long ttlMillis = (long) lookup.ttlSeconds() * 1000;
            for (UUID id : batch) {
                Entry entry = entries.get(id);
                if (entry == null) continue;
                entry.refreshAt = completedAt + ttlMillis;
                CapeDirectoryClient.CapeRef ref = lookup.capes().get(id);
                if (ref == null) {
                    clearTexture(entry);
                    entry.hash = null;
                    downloadQueue.remove(id);
                } else if (!ref.hash().equals(entry.hash) || entry.texture == null) {
                    clearTexture(entry);
                    entry.hash = ref.hash();
                    downloadQueue.put(id, ref);
                }
            }
            pumpDownloads();
        }));
    }

    private Entry findOrCreate(UUID player) {
        Entry existing = entries.get(player);
        if (existing != null) return existing;
        if (entries.size() >= MAX_TRACKED) {
            Iterator<Map.Entry<UUID, Entry>> it = entries.entrySet().iterator();
            if (it.hasNext()) {
                Map.Entry<UUID, Entry> oldest = it.next();
                clearTexture(oldest.getValue());
                downloadQueue.remove(oldest.getKey());
                it.remove();
            }
        }
        Entry created = new Entry();
        entries.put(player, created);
        return created;
    }

    private void pumpDownloads() {
        if (closed || !active || directory == null) return;
        Minecraft mc = Minecraft.getInstance();
        while (inFlightDownloads < MAX_DOWNLOADS && !downloadQueue.isEmpty()) {
            Iterator<Map.Entry<UUID, CapeDirectoryClient.CapeRef>> it = downloadQueue.entrySet().iterator();
            Map.Entry<UUID, CapeDirectoryClient.CapeRef> pending = it.next();
            UUID id = pending.getKey();
            CapeDirectoryClient.CapeRef ref = pending.getValue();
            it.remove();
            inFlightDownloads++;
            int expectedGeneration = generation;
            directory.download(ref).thenApply(bytes -> {
                try {
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!digest.equals(ref.hash())) throw new IOException("Cape SHA-256 mismatch");
                    return bytes;
                } catch (NoSuchAlgorithmException | IOException ex) {
                    throw new java.util.concurrent.CompletionException(ex);
                }
            }).whenComplete((bytes, error) -> mc.execute(() -> {
                if (closed || generation != expectedGeneration) return;
                inFlightDownloads--;
                Entry entry = entries.get(id);
                if (entry != null && ref.hash().equals(entry.hash)) {
                    if (error == null) registerTexture(id, ref, bytes, entry);
                    else {
                        entry.hash = null;
                        entry.refreshAt = System.currentTimeMillis() + FAILURE_RETRY_MS;
                    }
                }
                pumpDownloads();
            }));
        }
    }

    private void registerTexture(UUID id, CapeDirectoryClient.CapeRef ref, byte[] bytes, Entry entry) {
        NativeImage image = null;
        try {
            image = NativeImage.read(new ByteArrayInputStream(bytes));
            Identifier textureId = Identifier.fromNamespaceAndPath("sharedcapes", "remote/" +
                    CapeDirectoryClient.compactUuid(id) + "/" + ref.hash());
            Minecraft.getInstance().getTextureManager().register(textureId,
                    new DynamicTexture(textureId::toString, image));
            image = null; // ownership transferred to the texture manager
            entry.texture = new ClientAsset.ResourceTexture(textureId, textureId);
        } catch (Exception ex) {
            entry.hash = null;
            entry.refreshAt = System.currentTimeMillis() + FAILURE_RETRY_MS;
            logger.warn("Rejected invalid SharedCapes image for " + id);
        } finally {
            if (image != null) image.close();
        }
    }

    private void clearTexture(Entry entry) {
        if (entry.texture != null) {
            Minecraft.getInstance().getTextureManager().release(entry.texture.id());
            entry.texture = null;
        }
    }

    private void reset() {
        generation++;
        inFlightLookup = false;
        inFlightDownloads = 0;
        nextBatchAt = 0;
        for (Entry entry : entries.values()) clearTexture(entry);
        entries.clear();
        observed.clear();
        downloadQueue.clear();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        reset();
        if (directory != null) directory.close();
    }

    private static final class Entry {
        private String hash;
        private ClientAsset.ResourceTexture texture;
        private long refreshAt;
    }
}
