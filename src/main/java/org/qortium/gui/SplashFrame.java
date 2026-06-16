package org.qortium.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.utils.StartupStatus;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;

public class SplashFrame {

	protected static final Logger LOGGER = LogManager.getLogger(SplashFrame.class);
	private static final String TITLE = "Qortium Core";

	private static SplashFrame instance;
	private JFrame splashDialog;
	private SplashPanel splashPanel;

	@SuppressWarnings("serial")
	public static class SplashPanel extends JPanel {
		private BufferedImage image;

		private JLabel statusLabel;

		public SplashPanel() {
			this(SplashTheme.detect());
		}

		SplashPanel(SplashTheme theme) {
			image = Gui.loadImage(theme.getSplashImageResource());

			setOpaque(true);
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBorder(new EmptyBorder(10, 10, 10, 10));
			setBackground(theme.getBackgroundColor());

			// Add logo
			JLabel imageLabel = new JLabel(new ImageIcon(image));
			imageLabel.setSize(new Dimension(300, 300));
			add(imageLabel);

			// Add spacing
			add(Box.createRigidArea(new Dimension(0, 16)));

			// Add status label
			statusLabel = new JLabel("Starting Qortium Core...", JLabel.CENTER);
			statusLabel.setMaximumSize(new Dimension(500, 50));
			statusLabel.setFont(new Font("Verdana", Font.PLAIN, 20));
			statusLabel.setBackground(theme.getBackgroundColor());
			statusLabel.setForeground(theme.getForegroundColor());
			statusLabel.setOpaque(true);
			statusLabel.setBorder(null);
			add(statusLabel);
		}

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(500, 580);
		}

		public void updateStatus(String text) {
			if (statusLabel != null) {
				statusLabel.setText(text);
			}
		}
	}

	private SplashFrame() {
		if (GraphicsEnvironment.isHeadless()) {
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
		if (this.splashDialog != null)
			this.splashDialog.setVisible(b);
	}

	public void dispose() {
		StartupStatus.clearUpdater();

		if (this.splashDialog != null)
			this.splashDialog.dispose();

		this.splashDialog = null;
		this.splashPanel = null;
	}

	public void updateStatus(String text) {
		if (this.splashPanel == null)
			return;

		SwingUtilities.invokeLater(() -> {
			if (this.splashPanel != null)
				this.splashPanel.updateStatus(text);
		});
	}

}
