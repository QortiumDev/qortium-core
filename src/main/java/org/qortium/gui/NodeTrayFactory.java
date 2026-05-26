package org.qortium.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.GraphicsEnvironment;
import java.util.function.Supplier;

public final class NodeTrayFactory {

	private static final Logger LOGGER = LogManager.getLogger(NodeTrayFactory.class);

	private static NodeTray instance;

	private NodeTrayFactory() {
	}

	public static synchronized NodeTray getInstance() {
		if (instance == null)
			instance = createTray();

		return instance;
	}

	private static NodeTray createTray() {
		return selectTray(GraphicsEnvironment.isHeadless(), LinuxStatusNotifierTray::create, AwtSysTray::create);
	}

	static NodeTray selectTray(boolean headless, Supplier<NodeTray> linuxTraySupplier, Supplier<NodeTray> awtTraySupplier) {
		if (headless) {
			LOGGER.debug("System tray disabled in headless mode");
			return NoOpNodeTray.INSTANCE;
		}

		NodeTray linuxTray = tryCreateTray(linuxTraySupplier, "Linux native");
		if (linuxTray != null && linuxTray.isAvailable())
			return linuxTray;

		NodeTray awtTray = tryCreateTray(awtTraySupplier, "AWT");
		if (awtTray != null && awtTray.isAvailable())
			return awtTray;

		LOGGER.info("System tray unavailable; using no-op tray");
		return NoOpNodeTray.INSTANCE;
	}

	private static NodeTray tryCreateTray(Supplier<NodeTray> traySupplier, String implementationName) {
		try {
			return traySupplier.get();
		} catch (Throwable e) {
			LOGGER.info("{} system tray failed to initialize: {}", implementationName, e.getMessage());
			return null;
		}
	}
}
