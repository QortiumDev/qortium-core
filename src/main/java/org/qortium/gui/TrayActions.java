package org.qortium.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.controller.AutoUpdate;
import org.qortium.controller.BootstrapNode;
import org.qortium.controller.Controller;
import org.qortium.controller.RestartNode;
import org.qortium.globalization.Translator;
import org.qortium.repository.DataException;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
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

		// Offer a manual update check unless the node already installs updates automatically.
		if (Settings.getInstance().getAutoUpdateMode() != Settings.AutoUpdateMode.INSTALL) {
			actions.add(new TrayMenuAction(7, Translator.INSTANCE.translate("SysTray", "CHECK_FOR_UPDATE"),
					() -> runAction(beforeAction, TrayActions::checkForUpdates)));

			// If a check (periodic or manual) already found an update, surface a persistent
			// install entry so a missed/dismissed notification doesn't hide it.
			AutoUpdate.CachedUpdateStatus cached = AutoUpdate.getCachedUpdateStatus();
			if (cached != null) {
				String label = String.format(Translator.INSTANCE.translate("SysTray", "INSTALL_UPDATE"),
						describeBuild(cached.commitHash, cached.updateTimestamp));
				actions.add(new TrayMenuAction(8, label,
						() -> runAction(beforeAction, () -> confirmAndInstall(describeBuild(cached.commitHash, cached.updateTimestamp)))));
			}
		}

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

	private static void checkForUpdates() {
		// checkLatestUpdate() does network/repository work and can throw, so run it off the EDT.
		Thread thread = new Thread(() -> {
			AutoUpdate.UpdateCheckResult result;
			try {
				result = AutoUpdate.checkLatestUpdate();
			} catch (DataException e) {
				LOGGER.warn("Tray update check failed: {}", e.getMessage());
				showUpdateInfo(Translator.INSTANCE.translate("SysTray", "UPDATE_CHECK_FAILED"));
				return;
			}

			if (result == null) {
				showUpdateInfo(Translator.INSTANCE.translate("SysTray", "UPDATE_CHECK_FAILED"));
				return;
			}

			if (!result.qdnEnabled) {
				// QDN is off, so no real check ran: don't claim the node is up to date.
				showUpdateInfo(Translator.INSTANCE.translate("SysTray", "UPDATE_CHECK_FAILED"));
				return;
			}

			if (!result.updateAvailable) {
				showUpdateInfo(Translator.INSTANCE.translate("SysTray", "UP_TO_DATE"));
				return;
			}

			// An update is available: offer to install it (which restarts the node).
			confirmAndInstall(describeBuild(result.commitHash, result.updateTimestamp));
		}, "Tray update check");
		thread.setDaemon(true);
		thread.start();
	}

	/** Confirm on the EDT, then install if the user agrees. */
	private static void confirmAndInstall(String descriptor) {
		final String prompt = String.format(
				Translator.INSTANCE.translate("SysTray", "UPDATE_AVAILABLE_PROMPT"), descriptor);
		SwingUtilities.invokeLater(() -> {
			int choice = JOptionPane.showConfirmDialog(null, prompt,
					Translator.INSTANCE.translate("SysTray", "CHECK_FOR_UPDATE"),
					JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
				installUpdate();
		});
	}

	private static void installUpdate() {
		// requestManualUpdate() schedules the download + install + restart; it can throw, so run off the EDT.
		Thread thread = new Thread(() -> {
			try {
				AutoUpdate.requestManualUpdate();
			} catch (DataException e) {
				LOGGER.warn("Tray update install failed: {}", e.getMessage());
				showUpdateInfo(Translator.INSTANCE.translate("SysTray", "UPDATE_CHECK_FAILED"));
			}
		}, "Tray update install");
		thread.setDaemon(true);
		thread.start();
	}

	/** Short, factual descriptor of the available build (commit and/or build date) for labels/prompts. */
	private static String describeBuild(String commitHash, Long updateTimestamp) {
		StringBuilder sb = new StringBuilder();
		if (commitHash != null && !commitHash.isEmpty())
			sb.append(commitHash.length() > 8 ? commitHash.substring(0, 8) : commitHash);
		if (updateTimestamp != null) {
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(updateTimestamp)));
		}
		return sb.length() > 0 ? sb.toString() : Translator.INSTANCE.translate("SysTray", "AUTO_UPDATE");
	}

	private static void showUpdateInfo(String message) {
		SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null, message,
				Translator.INSTANCE.translate("SysTray", "CHECK_FOR_UPDATE"), JOptionPane.INFORMATION_MESSAGE));
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
		// Restart shuts down and relaunches the node, so confirm before scheduling to guard
		// against an accidental tray click. The dialog/scheduling run on the EDT to stay safe
		// regardless of which tray backend invoked the action.
		SwingUtilities.invokeLater(() -> {
			int choice = JOptionPane.showConfirmDialog(null,
					Translator.INSTANCE.translate("SysTray", "RESTART_CONFIRM"),
					Translator.INSTANCE.translate("SysTray", "RESTART"),
					JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (choice != JOptionPane.YES_OPTION)
				return;

			// scheduleRestart() spawns its own worker that performs the shutdown and relaunch,
			// and returns false if a restart/bootstrap apply is already scheduled or running.
			if (!RestartNode.scheduleRestart())
				LOGGER.info("Ignoring tray restart request: a restart is already scheduled or running");
		});
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
