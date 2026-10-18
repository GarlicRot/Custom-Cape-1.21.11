package org.example.capes;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.core.interfaces.IToggleable;
import org.rusherhack.core.logging.ILogger;
import org.rusherhack.core.setting.StringSetting;
import org.example.sharedcapes.render.RemoteCapeManager;
import org.example.sharedcapes.publish.CapePublishingManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Loads cape pngs from .minecraft/rusherhack/capes and adds the "Custom Cape" setting to the Capes module
 */
public class CapeManager {

	public static final String NONE = "None";

	private final Path capesDir = FabricLoader.getInstance().getGameDir().resolve("rusherhack").resolve("capes");
	private final Path selectedFile = this.capesDir.resolve("selected.txt");
	private final ILogger logger;

	private final StringSetting customCape = new StringSetting("Custom Cape", "Select a PNG; when the service is connected, it is automatically visible to other SharedCapes users", NONE, NONE);
	private RemoteCapeManager remoteCapes;
	private CapePublishingManager publisher;

	//file name -> loaded texture, filled lazily on the render thread
	private final Map<String, ClientAsset.ResourceTexture> textures = new HashMap<>();
	private final List<String> failed = new ArrayList<>();
	private int textureCounter = 0;

	private IModule capesModule;
	private boolean restoring = false;

	public CapeManager(ILogger logger) {
		this.logger = logger;

		this.customCape.onChange((String name) -> {
			if(!this.restoring) {
				this.saveSelected(name);
			}
		});
	}

	/**
	 * Creates the capes folder on first launch and reads available capes
	 */
	public void init() {
		try {
			Files.createDirectories(this.capesDir);
		} catch(IOException e) {
			this.logger.error("Failed to create capes folder " + this.capesDir, e);
		}

		this.remoteCapes = new RemoteCapeManager(this.logger, this.capesDir);
		this.publisher = new CapePublishingManager(this.logger, this.capesDir);
		this.scan();
	}

	/**
	 * Adds the setting to the Capes module
	 *
	 * @return true if attached
	 */
	public boolean tryAttach() {
		if(this.capesModule != null) {
			return true;
		}

		final Optional<IModule> module = RusherHackAPI.getModuleManager().getFeature("Capes");
		if(module.isEmpty()) {
			return false;
		}

		this.capesModule = module.get();
		this.capesModule.registerSettings(this.customCape);
		this.logger.info("Added Custom Cape setting to Capes module");
		return true;
	}

	/**
	 * Re-reads the capes folder and drops cached textures
	 *
	 * @return number of capes found
	 */
	public int reload() {
		final Minecraft mc = Minecraft.getInstance();
		if(mc != null) {
			for(ClientAsset.ResourceTexture texture : this.textures.values()) {
				mc.getTextureManager().release(texture.id());
			}
		}
		this.textures.clear();
		this.failed.clear();

		return this.scan();
	}

	private int scan() {
		final List<String> names = new ArrayList<>();

		if(Files.isDirectory(this.capesDir)) {
			try(Stream<Path> files = Files.list(this.capesDir)) {
				files.filter(Files::isRegularFile)
					 .map(p -> p.getFileName().toString())
					 .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png"))
					 .sorted(String.CASE_INSENSITIVE_ORDER)
					 .forEach(names::add);
			} catch(IOException e) {
				this.logger.error("Failed to read capes folder", e);
			}
		}

		//keep selection if file still exists, else fall back to saved or none
		final String current = this.customCape.getValue();
		final String wanted = NONE.equals(current) ? this.loadSelected() : current;

		this.restoring = true;
		this.customCape.getOptions().clear();
		this.customCape.addOptions(NONE);
		this.customCape.addOptions(names.toArray(new String[0]));
		this.customCape.setValue(names.contains(wanted) ? wanted : NONE);
		this.restoring = false;

		return names.size();
	}

	/**
	 * @return texture of the selected cape, or null if none should be shown. Call on render thread.
	 */
	public ClientAsset.ResourceTexture getActiveCape() {
		if(this.capesModule instanceof IToggleable toggleable && !toggleable.isToggled()) {
			return null;
		}

		final String name = this.customCape.getValue();
		if(name == null || NONE.equals(name) || this.failed.contains(name)) {
			return null;
		}

		ClientAsset.ResourceTexture texture = this.textures.get(name);
		if(texture == null) {
			texture = this.loadTexture(name);
			if(texture == null) {
				this.failed.add(name);
				return null;
			}
			this.textures.put(name, texture);
		}

		return texture;
	}

	private ClientAsset.ResourceTexture loadTexture(String name) {
		final Path file = this.capesDir.resolve(name);

		try(InputStream in = Files.newInputStream(file)) {
			final NativeImage image = NativeImage.read(in);
			final String path = "cape/" + (this.textureCounter++) + "_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
			final Identifier id = Identifier.fromNamespaceAndPath("customcapes", path);

			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
			return new ClientAsset.ResourceTexture(id, id);
		} catch(Throwable t) {
			this.logger.error("Failed to load cape " + file, t);
			return null;
		}
	}

	private String loadSelected() {
		try {
			if(Files.exists(this.selectedFile)) {
				return Files.readString(this.selectedFile, StandardCharsets.UTF_8).trim();
			}
		} catch(IOException e) {
			this.logger.warn("Failed to read selected cape");
		}
		return NONE;
	}

	private void saveSelected(String name) {
		try {
			Files.writeString(this.selectedFile, name, StandardCharsets.UTF_8);
		} catch(IOException e) {
			this.logger.warn("Failed to save selected cape");
		}
	}

	/** Called only on the main client thread, never performs network IO directly. */
	public void tickSharedCapes() {
		boolean moduleEnabled = this.capesModule instanceof IToggleable toggleable && toggleable.isToggled();
		if(this.remoteCapes != null) this.remoteCapes.tick(moduleEnabled);
		if(this.publisher != null) this.publisher.tick(moduleEnabled, this.customCape.getValue());
	}

	/** The mixin calls this for other players, never the local player. */
	public ClientAsset.ResourceTexture getSharedCape(UUID player) {
		if(this.remoteCapes == null) return null;
		if(this.capesModule instanceof IToggleable toggleable && !toggleable.isToggled()) return null;
		return this.remoteCapes.get(player);
	}

	public void close() {
		if(this.remoteCapes != null) this.remoteCapes.close();
		if(this.publisher != null) this.publisher.close();
	}

	public String getSharedStatus() {
		return this.publisher == null ? "Publisher not initialized" : this.publisher.status();
	}

	public Path getCapesDir() {
		return this.capesDir;
	}
}
