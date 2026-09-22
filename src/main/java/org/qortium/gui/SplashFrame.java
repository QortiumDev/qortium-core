package org.qortium.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.utils.StartupStatus;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

public class SplashFrame {

	protected static final Logger LOGGER = LogManager.getLogger(SplashFrame.class);
	private static final String TITLE = "Qortium Core";
	/** Belt-and-braces delay before checking the splash actually went away after dispose(). */
	private static final long WATCHDOG_DELAY_MS = 3_000L;

	/**
	 * Once true, no further SplashFrame is ever constructed with a real dialog: the startup
	 * splash has already been dismissed for this JVM and must not reappear, even if something
	 * calls {@link #getInstance()} again after the singleton has been reset to null.
	 */
	private static volatile boolean dismissed;

	private static volatile SplashFrame instance;
	private volatile JFrame splashDialog;
	private volatile SplashPanel splashPanel;
	/** Guards against double-disposal and makes updateStatus/setVisible no-ops once set. */
	private volatile boolean disposed;

	@SuppressWarnings("serial")
	public static class SplashPanel extends JPanel {
		private static final int PANEL_WIDTH = 500;
		private static final int LOGO_SIZE = 250;
		private static final int BORDER_SIZE = 10;
		private static final int LOGO_STATUS_SPACING = 16;
		private static final int STATUS_HEIGHT = 50;
		private static final Dimension PANEL_SIZE = new Dimension(PANEL_WIDTH,
				BORDER_SIZE + LOGO_SIZE + LOGO_STATUS_SPACING + STATUS_HEIGHT + BORDER_SIZE);

		private BufferedImage image;

		private JLabel statusLabel;

		public SplashPanel() {
			this(SplashTheme.detect());
		}

		SplashPanel(SplashTheme theme) {
			image = Gui.loadImage(theme.getSplashImageResource());

			setOpaque(true);
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBorder(new EmptyBorder(BORDER_SIZE, BORDER_SIZE, BORDER_SIZE, BORDER_SIZE));
			setBackground(theme.getBackgroundColor());

			// Add logo
			ImageIcon imageIcon = image == null ? new ImageIcon()
					: new ImageIcon(image.getScaledInstance(LOGO_SIZE, LOGO_SIZE, Image.SCALE_SMOOTH));
			JLabel imageLabel = new JLabel(imageIcon);
			imageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			imageLabel.setPreferredSize(new Dimension(LOGO_SIZE, LOGO_SIZE));
			imageLabel.setMaximumSize(new Dimension(LOGO_SIZE, LOGO_SIZE));
			add(imageLabel);

			// Add spacing
			add(Box.createRigidArea(new Dimension(0, LOGO_STATUS_SPACING)));

			// Add status label
			statusLabel = new JLabel("Starting Qortium Core...", JLabel.CENTER);
			statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			statusLabel.setPreferredSize(new Dimension(PANEL_WIDTH, STATUS_HEIGHT));
			statusLabel.setMaximumSize(new Dimension(PANEL_WIDTH, STATUS_HEIGHT));
			statusLabel.setFont(new Font("Verdana", Font.PLAIN, 20));
			statusLabel.setBackground(theme.getBackgroundColor());
			statusLabel.setForeground(theme.getForegroundColor());
			statusLabel.setOpaque(true);
			statusLabel.setBorder(null);
			add(statusLabel);
		}

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(PANEL_SIZE);
		}

		public void updateStatus(String text) {
			if (statusLabel != null) {
				statusLabel.setText(text);
			}
		}
	}

	private SplashFrame() {
		if (GraphicsEnvironment.isHeadless() || dismissed) {
			return;
		}

		try {
			SplashTheme theme = SplashTheme.detect();

			this.splashDialog = new JFrame(TITLE);
			Gui.applyWindowIcon(this.splashDialog);

			this.splashPanel = new SplashPanel(theme);
			this.splashDialog.getContentPane().add(this.splashPanel);
			this.splashDialog.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			this.splashDialog.setUndecorated(true);
			this.splashDialog.pack();
			this.splashDialog.setLocationRelativeTo(null);
			this.splashDialog.setBackground(theme.getBackgroundColor());
			this.splashDialog.setVisible(true);

			StartupStatus.setUpdater(this::updateStatus);
		} catch (AWTError | RuntimeException e) {
			LOGGER.info("Unable to initialize splash screen: {}", e.getMessage());
			try {
				if (this.splashDialog != null)
					this.splashDialog.dispose();
			} catch (AWTError | RuntimeException ignored) {
				// Already handling a GUI initialization failure.
			}
			this.splashPanel = null;
			this.splashDialog = null;
		}
	}

	public static SplashFrame getInstance() {
		if (instance == null)
			instance = new SplashFrame();

		return instance;
	}

	public void setVisible(boolean b) {
		if (this.disposed)
			return;

		if (this.splashDialog != null)
			this.splashDialog.setVisible(b);
	}

	/**
	 * Dismiss the startup splash, reliably, from whichever thread the caller happens to be on.
	 * <p>
	 * Order matters here: the {@link StartupStatus} updater is cleared first so no further
	 * {@code updateStatus} calls can be queued against this frame; the singleton is then marked
	 * dismissed (both per-instance and process-wide) so nothing can re-show or reconstruct a
	 * live splash after this point; and the actual Swing teardown is marshalled onto the EDT,
	 * because the dialog was originally realized/shown on the caller's thread (usually the main
	 * startup thread, not the EDT) and disposing it off-EDT is what let the X11/XWayland peer
	 * end up in a state where the window stayed mapped even though the Java side considered it
	 * gone (see P-CORE-53).
	 */
	public void dispose() {
		if (this.disposed)
			return;

		this.disposed = true;
		dismissed = true;

		StartupStatus.clearUpdater();

		JFrame dialog = this.splashDialog;
		this.splashDialog = null;
		this.splashPanel = null;

		if (SplashFrame.instance == this)
			SplashFrame.instance = null;

		if (dialog != null) {
			disposeDialogOnEdt(dialog, "notifyRunning");
			scheduleDisposalWatchdog(dialog);

			LOGGER.info("Startup splash dismissed");
		}
	}

	/**
	 * Runs the actual {@link JFrame} teardown on the EDT (via {@code invokeAndWait} when called
	 * from elsewhere), with a bounded wait and a logged fallback if that isn't possible.
	 */
	private static void disposeDialogOnEdt(JFrame dialog, String reason) {
		Runnable teardown = () -> {
			try {
				// Once we're disposing because the node is running, a stray close request
				// (e.g. a leftover window-manager event) should actually close the window
				// rather than do nothing.
				dialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
				dialog.setVisible(false);
				dialog.dispose();
			} catch (AWTError | RuntimeException e) {
				LOGGER.warn("Unable to dispose startup splash ({}): {}", reason, e.getMessage());
			}
		};

		if (SwingUtilities.isEventDispatchThread()) {
			teardown.run();
			return;
		}

		try {
			SwingUtilities.invokeAndWait(teardown);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Interrupted waiting for EDT to dispose startup splash ({}); disposing directly", reason);
			teardown.run();
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			LOGGER.warn("EDT dispose of startup splash ({}) failed: {}", reason, cause.getMessage());
		}
	}

	/**
	 * ~3s after we believe the splash has been disposed, double-check it actually went away.
	 * If the native peer is still displayable/showing, log a WARN naming the reason and dispose
	 * it again on the EDT.
	 */
	private static void scheduleDisposalWatchdog(JFrame dialog) {
		if (GraphicsEnvironment.isHeadless())
			return;

		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(WATCHDOG_DELAY_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}

			boolean stillShowing = dialog.isShowing();
			boolean stillDisplayable = dialog.isDisplayable();
			if (stillShowing || stillDisplayable) {
				LOGGER.warn("Startup splash still {} {} ms after dispose() - forcing disposal again",
						stillShowing ? "showing" : "displayable", WATCHDOG_DELAY_MS);
				disposeDialogOnEdt(dialog, "watchdog");
			}
		}, "SplashFrame-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	public void updateStatus(String text) {
		if (this.disposed || this.splashPanel == null)
			return;

		SwingUtilities.invokeLater(() -> {
			if (this.disposed || this.splashPanel == null)
				return;

			this.splashPanel.updateStatus(text);
		});
	}

	/** Visible for tests: true only while a real dialog exists and its native peer is still realized. */
	public boolean isDialogDisplayable() {
		JFrame dialog = this.splashDialog;
		return dialog != null && dialog.isDisplayable();
	}

}
