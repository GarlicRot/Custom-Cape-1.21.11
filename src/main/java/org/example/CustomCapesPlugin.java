package org.example;

import org.example.capes.CapeCommand;
import org.example.capes.CapeManager;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.plugin.Plugin;
import org.rusherhack.core.event.subscribe.Subscribe;

/**
 * Adds custom capes from .minecraft/rusherhack/capes to the Capes module
 */
public class CustomCapesPlugin extends Plugin {

	public static CustomCapesPlugin INSTANCE;

	private CapeManager capeManager;
	private boolean attached = false;

	@Override
	public void onLoad() {
		INSTANCE = this;

		this.capeManager = new CapeManager(this.getLogger());
		this.capeManager.init();

		RusherHackAPI.getCommandManager().registerFeature(new CapeCommand(this.capeManager));

		//capes module may not be registered yet, retry on tick
		this.attached = this.capeManager.tryAttach();
		RusherHackAPI.getEventBus().subscribe(this);
	}

	@Override
	public void onUnload() {
		RusherHackAPI.getEventBus().unsubscribe(this);
		if(this.capeManager != null) this.capeManager.close();
		INSTANCE = null;
	}

	@Subscribe
	private void onUpdate(EventUpdate event) {
		if(!this.attached) {
			this.attached = this.capeManager.tryAttach();
		}
		this.capeManager.tickSharedCapes();
	}

	public CapeManager getCapeManager() {
		return this.capeManager;
	}
}
