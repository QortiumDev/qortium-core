package org.qortium.gui;

final class NoOpNodeTray implements NodeTray {

	static final NoOpNodeTray INSTANCE = new NoOpNodeTray();

	private NoOpNodeTray() {
	}

	@Override
	public boolean isAvailable() {
		return false;
	}

	@Override
	public void showMessage(String caption, String text, TrayMessageType messageType) {
	}

	@Override
	public void setToolTipText(String text) {
	}

	@Override
	public void setTrayIcon(TrayIconState iconState) {
	}

	@Override
	public void dispose() {
	}
}
