package org.qortium.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.JMenuItem;
import javax.swing.JDialog;
import javax.swing.JPopupMenu;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.AWTError;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.util.List;

final class AwtSysTray implements NodeTray {

	private static final Logger LOGGER = LogManager.getLogger(AwtSysTray.class);

	private TrayIcon trayIcon;
	private JPopupMenu popupMenu;
	/** The hidden dialog has 'focus' when menu displayed so closes the menu when user clicks elsewhere. */
	private JDialog hiddenDialog;
	private final boolean menuEnabled;

	private AwtSysTray(boolean menuEnabled) {
		this.menuEnabled = menuEnabled;
	}

	static NodeTray create() {
		return create(true);
	}

	static NodeTray createStatusOnly() {
		return create(false);
	}

	private static NodeTray create(boolean menuEnabled) {
		if (GraphicsEnvironment.isHeadless())
			return null;

		try {
			if (!SystemTray.isSupported())
				return null;

			AwtSysTray tray = new AwtSysTray(menuEnabled);
			tray.initialize();
			return tray.isAvailable() ? tray : null;
		} catch (Throwable e) {
			LOGGER.info("AWT system tray is unavailable: {}", e.getMessage());
			return null;
		}
	}

	private void initialize() {
		LOGGER.info("Launching AWT system tray icon");

		if (this.menuEnabled)
			this.popupMenu = createJPopupMenu();

		this.trayIcon = new TrayIcon(Gui.loadImage(TrayIconState.SYNCED.getResourceName()), "Qortium", null);
		if (this.menuEnabled) {
			this.trayIcon.addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent mouseEvent) {
					this.maybePopupMenu(mouseEvent);
				}

				@Override
				public void mouseReleased(MouseEvent mouseEvent) {
					this.maybePopupMenu(mouseEvent);
				}

				private void maybePopupMenu(MouseEvent mouseEvent) {
					if (!mouseEvent.isPopupTrigger())
						return;

					try {
						if (!popupMenu.isVisible())
							destroyHiddenDialog();

						createHiddenDialog();
						hiddenDialog.setLocation(mouseEvent.getX() + 1, mouseEvent.getY() - 1);
						popupMenu.setLocation(mouseEvent.getX() + 1, mouseEvent.getY() - 1);
						popupMenu.setInvoker(hiddenDialog);
						hiddenDialog.setVisible(true);
						popupMenu.setVisible(true);
					} catch (RuntimeException e) {
						LOGGER.info("Unable to show AWT tray menu: {}", e.getMessage());
					}
				}
			});
		}

		this.trayIcon.setImageAutoSize(true);

		try {
			SystemTray.getSystemTray().add(this.trayIcon);
		} catch (AWTException | AWTError | RuntimeException e) {
			LOGGER.info("Unable to add AWT tray icon: {}", e.getMessage());
			this.trayIcon = null;
		}
	}

	private void createHiddenDialog() {
		if (this.hiddenDialog != null)
			return;

		this.hiddenDialog = new JDialog();
		this.hiddenDialog.setUndecorated(true);
		this.hiddenDialog.setSize(10, 10);
		this.hiddenDialog.addWindowFocusListener(new WindowFocusListener() {
			@Override
			public void windowLostFocus(WindowEvent windowEvent) {
				destroyHiddenDialog();
			}

			@Override
			public void windowGainedFocus(WindowEvent windowEvent) {
			}
		});
	}

	private void destroyHiddenDialog() {
		if (this.hiddenDialog == null)
			return;

		try {
			this.hiddenDialog.setVisible(false);
			this.hiddenDialog.dispose();
		} finally {
			this.hiddenDialog = null;
		}
	}

	private JPopupMenu createJPopupMenu() {
		JPopupMenu menu = new JPopupMenu();

		menu.addPopupMenuListener(new PopupMenuListener() {
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
				destroyHiddenDialog();
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent event) {
			}
		});

		List<TrayMenuAction> actions = TrayActions.createMenuActions(this::destroyHiddenDialog);
		for (TrayMenuAction action : actions) {
			JMenuItem item = new JMenuItem(action.getLabel());
			item.addActionListener(actionEvent -> action.run());
			menu.add(item);
		}

		return menu;
	}

	@Override
	public boolean isAvailable() {
		return this.trayIcon != null;
	}

	@Override
	public void showMessage(String caption, String text, TrayMessageType messageType) {
		try {
			if (this.trayIcon != null)
				this.trayIcon.displayMessage(caption, text, messageType.toAwt());
		} catch (RuntimeException e) {
			LOGGER.info("Unable to show AWT tray message: {}", e.getMessage());
		}
	}

	@Override
	public void setToolTipText(String text) {
		try {
			if (this.trayIcon != null)
				this.trayIcon.setToolTip(text);
		} catch (RuntimeException e) {
			LOGGER.info("Unable to set AWT tray tooltip: {}", e.getMessage());
		}
	}

	@Override
	public void setTrayIcon(TrayIconState iconState) {
		try {
			if (this.trayIcon != null)
				this.trayIcon.setImage(Gui.loadImage(iconState.getResourceName()));
		} catch (RuntimeException e) {
			LOGGER.info("Unable to set AWT tray icon: {}", e.getMessage());
		}
	}

	@Override
	public void dispose() {
		destroyHiddenDialog();

		if (this.trayIcon == null)
			return;

		try {
			SystemTray.getSystemTray().remove(this.trayIcon);
		} catch (AWTError | RuntimeException e) {
			LOGGER.info("Unable to remove AWT tray icon: {}", e.getMessage());
		} finally {
			this.trayIcon = null;
		}
	}
}
