package org.qortal.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceConfigurationError;

public class Gui {

	private static final Logger LOGGER = LogManager.getLogger(Gui.class);
	private static final String[] APP_ICON_RESOURCES = {
			"icons/icon16.png",
			"icons/icon64.png",
			"icons/Qlogo_128.png",
			"Qlogo_512.png"
	};
	private static Gui instance;

	private boolean isHeadless;
	private SplashFrame splashFrame = null;
	private NodeTray nodeTray = null;

	private Gui() {
		try {
			this.isHeadless = GraphicsEnvironment.isHeadless();

			if (!this.isHeadless) {
				try {
					UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
						| UnsupportedLookAndFeelException e) {
					// Use whatever look-and-feel comes by default then
				}

				applyTaskbarIcon();
				showSplash();
			}
		} catch (Exception e) {
			LOGGER.info("Unable to initialize GUI: {}", e.getMessage());
		}
	}

	private void showSplash() {
		LOGGER.trace(() -> "Splash");
		this.splashFrame = SplashFrame.getInstance();
	}

	protected static BufferedImage loadImage(String resourceName) {
		try (InputStream in = Gui.class.getResourceAsStream("/images/" + resourceName)) {
			return ImageIO.read(in);
		} catch (IllegalArgumentException | IOException | ServiceConfigurationError e) {
			LOGGER.warn(String.format("Couldn't locate image resource \"images/%s\"", resourceName));
			return null;
		}
	}

	static List<Image> loadAppIcons() {
		List<Image> icons = new ArrayList<>();

		for (String resourceName : APP_ICON_RESOURCES) {
			Image image = loadImage(resourceName);
			if (image != null)
				icons.add(image);
		}

		return Collections.unmodifiableList(icons);
	}

	static void applyWindowIcon(Window window) {
		if (window == null || GraphicsEnvironment.isHeadless())
			return;

		List<Image> icons = loadAppIcons();
		if (icons.isEmpty())
			return;

		try {
			window.setIconImages(icons);
		} catch (AWTError | RuntimeException e) {
			LOGGER.debug("Unable to set Qortium window icon: {}", e.getMessage());
		}
	}

	static void applyTaskbarIcon() {
		if (GraphicsEnvironment.isHeadless())
			return;

		List<Image> icons = loadAppIcons();
		if (icons.isEmpty())
			return;

		try {
			if (!Taskbar.isTaskbarSupported())
				return;

			Taskbar taskbar = Taskbar.getTaskbar();
			if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE))
				return;

			taskbar.setIconImage(icons.get(icons.size() - 1));
		} catch (AWTError | RuntimeException e) {
			LOGGER.debug("Unable to set Qortium taskbar icon: {}", e.getMessage());
		}
	}

	public static Gui getInstance() {
		if (instance == null)
			instance = new Gui();

		return instance;
	}

	public void notifyRunning() {
		if (this.isHeadless)
			return;

		this.splashFrame.dispose();
		this.splashFrame = null;

		this.nodeTray = NodeTrayFactory.getInstance();
	}

	public void shutdown() {
		if (this.isHeadless)
			return;

		if (this.splashFrame != null)
			this.splashFrame.dispose();

		if (this.nodeTray != null)
			this.nodeTray.dispose();
	}

	public void fatalError(String title, String message) {
		if (this.isHeadless)
			return;

		shutdown();

		JOptionPane.showConfirmDialog(null, message, title, JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE);

		System.exit(0);
	}

	public void fatalError(String title, Exception e) {
		String message = e.getLocalizedMessage();
		if (e.getCause() != null && e.getCause().getLocalizedMessage() != null)
			message += ": " + e.getCause().getLocalizedMessage();

		this.fatalError(title, message);
	}

}
