package org.example.capes;

import org.rusherhack.client.api.feature.command.Command;
import org.rusherhack.core.command.annotations.CommandExecutor;

/**
 * *cape reload - re-reads the capes folder
 */
public class CapeCommand extends Command {

	private final CapeManager capeManager;

	public CapeCommand(CapeManager capeManager) {
		super("cape", "Custom cape commands");
		this.capeManager = capeManager;
	}

	@CommandExecutor(subCommand = "reload")
	private String reload() {
		final int count = this.capeManager.reload();
		return "Reloaded capes, found " + count + " in " + this.capeManager.getCapesDir();
	}
}
