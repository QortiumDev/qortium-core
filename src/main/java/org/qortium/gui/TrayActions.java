package org.qortium.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.controller.BootstrapNode;
import org.qortium.controller.Controller;
import org.qortium.controller.RestartNode;
import org.qortium.globalization.Translator;
import org.qortium.settings.Settings;
import org.qortium.utils.URLViewer;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class TrayActions {

	private static final Logger LOGGER = LogManager.getLogger(TrayActions.class);
	private static final String NTP_SCRIPT = "ntpcfg.bat";

	private TrayActions() {
	}

	static List<TrayMenuAction> createMenuActions(Runnable beforeAction) {
		List<TrayMenuAction> actions = new ArrayList<>();

		actions.add(new TrayMenuAction(1, Translator.INSTANCE.translate("SysTray", "CHECK_TIME_ACCURACY"),
				() -> runAction(beforeAction, TrayActions::openTimeCheck)));

		if (isWindows()) {
			actions.add(new TrayMenuAction(2, Translator.INSTANCE.translate("SysTray", "SYNCHRONIZE_CLOCK"),
					() -> runAction(beforeAction, TrayActions::synchronizeClock)));
		}

		actions.add(new TrayMenuAction(3, Translator.INSTANCE.translate("SysTray", "BUILD_VERSION"),
				() -> runAction(beforeAction, TrayActions::showAboutDialog)));

		// Only offer Bootstrap when hosts are configured; without them the action would just fail.
		if (Settings.getInstance().hasBootstrapHostsConfigured()) {
			actions.add(new TrayMenuAction(6, Translator.INSTANCE.translate("SysTray", "BOOTSTRAP"),
					() -> runAction(beforeAction, TrayActions::bootstrap)));
		}

		actions.add(new TrayMenuAction(5, Translator.INSTANCE.translate("SysTray", "RESTART"),
				() -> runAction(beforeAction, TrayActions::restart)));
		actions.add(new TrayMenuAction(4, Translator.INSTANCE.translate("SysTray", "EXIT"),
				() -> runAction(beforeAction, TrayActions::exit)));

		return actions;
	}

	private static void runAction(Runnable beforeAction, Runnable action) {
		if (beforeAction != null)
			beforeAction.run();

		action.run();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private static void openTimeCheck() {
		try {
			URLViewer.openWebpage(new URL("https://time.is"));
		} catch (Exception e) {
			LOGGER.error("Unable to open time-check website in browser");
		}
	}

	private static void synchronizeClock() {
		Thread thread = new Thread(TrayActions::synchronizeClockInBackground, "Tray synchronize clock");
		thread.setDaemon(true);
		thread.start();
	}

	private static void synchronizeClockInBackground() {
		String resourceName = "/node-management/" + NTP_SCRIPT;
		Path scriptPath = Paths.get(NTP_SCRIPT);

		try (InputStream in = TrayActions.class.getResourceAsStream(resourceName)) {
			Files.copy(in, scriptPath, StandardCopyOption.REPLACE_EXISTING);
		} catch (IllegalArgumentException | IOException | NullPointerException e) {
			LOGGER.warn(String.format("Couldn't locate NTP configuration resource: %s", resourceName));
			return;
		}

		List<String> scriptCmd = Arrays.asList(NTP_SCRIPT);
		LOGGER.info(String.format("Running NTP configuration script: %s", String.join(" ", scriptCmd)));
		try {
			new ProcessBuilder(scriptCmd).start();
		} catch (IOException e) {
			LOGGER.warn(String.format("Failed to execute NTP configuration script: %s", e.getMessage()));
		}
	}

	private static void showAboutDialog() {
		SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
				"Qortium Core\n" + Translator.INSTANCE.translate("SysTray", "BUILD_VERSION") + ":\n"
						+ Controller.getInstance().getVersionStringWithoutPrefix(),
				"Qortium Core",
				JOptionPane.INFORMATION_MESSAGE));
	}

	private static void bootstrap() {
		// Bootstrapping is destructive: it deletes the local database and downloads a fresh
		// copy, so confirm before scheduling. The dialog/scheduling run on the EDT to stay
		// safe regardless of which tray backend invoked the action.
		SwingUtilities.invokeLater(() -> {
			int choice = JOptionPane.showConfirmDialog(null,
					Translator.INSTANCE.translate("SysTray", "BOOTSTRAP_CONFIRM"),
					Translator.INSTANCE.translate("SysTray", "BOOTSTRAP"),
					JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.YES_OPTION)
				return;

			// scheduleBootstrap() spawns its own worker and returns false if a bootstrap or
			// restart apply is already scheduled or running.
			if (!BootstrapNode.scheduleBootstrap())
				LOGGER.info("Ignoring tray bootstrap request: a bootstrap or restart is already scheduled or running");
		});
	}

	private static void restart() {
		// scheduleRestart() spawns its own worker that performs the shutdown and relaunch,
		// and returns false if a restart/bootstrap apply is already scheduled or running.
		if (!RestartNode.scheduleRestart())
			LOGGER.info("Ignoring tray restart request: a restart is already scheduled or running");
	}

	private static void exit() {
		Thread thread = new Thread(() -> {
			Controller.getInstance().shutdown();
			System.exit(0);
		}, "Tray shutdown");
		thread.setDaemon(false);
		thread.start();
	}
}
