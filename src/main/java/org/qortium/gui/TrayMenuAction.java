package org.qortium.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class TrayMenuAction {

	private static final Logger LOGGER = LogManager.getLogger(TrayMenuAction.class);

	private final int id;
	private final String label;
	private final Runnable runnable;

	TrayMenuAction(int id, String label, Runnable runnable) {
		this.id = id;
		this.label = label;
		this.runnable = runnable;
	}

	int getId() {
		return this.id;
	}

	String getLabel() {
		return this.label;
	}

	void run() {
		try {
			this.runnable.run();
		} catch (RuntimeException e) {
			LOGGER.warn("Tray menu action failed: {}", e.getMessage());
		}
	}
}
