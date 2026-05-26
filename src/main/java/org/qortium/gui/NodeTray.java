package org.qortium.gui;

public interface NodeTray {

	boolean isAvailable();

	void showMessage(String caption, String text, TrayMessageType messageType);

	void setToolTipText(String text);

	void setTrayIcon(TrayIconState iconState);

	void dispose();

}
