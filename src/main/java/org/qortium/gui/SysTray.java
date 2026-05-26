package org.qortium.gui;

import java.awt.TrayIcon;

/**
 * Compatibility facade for callers that still use the legacy SysTray API.
 */
public class SysTray {

	private static SysTray instance;

	private final NodeTray delegate;

	private SysTray(NodeTray delegate) {
		this.delegate = delegate;
	}

	public static synchronized SysTray getInstance() {
		if (instance == null)
			instance = new SysTray(NodeTrayFactory.getInstance());

		return instance;
	}

	public boolean isAvailable() {
		return this.delegate.isAvailable();
	}

	public void showMessage(String caption, String text, TrayIcon.MessageType messageType) {
		this.delegate.showMessage(caption, text, TrayMessageType.fromAwt(messageType));
	}

	public void setToolTipText(String text) {
		this.delegate.setToolTipText(text);
	}

	public void setTrayIcon(int iconId) {
		this.delegate.setTrayIcon(TrayIconState.fromLegacyId(iconId));
	}

	public void dispose() {
		this.delegate.dispose();
	}
}
