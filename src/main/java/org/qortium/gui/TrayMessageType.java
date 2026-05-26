package org.qortium.gui;

import java.awt.TrayIcon;

public enum TrayMessageType {
	ERROR,
	WARNING,
	INFO,
	NONE;

	static TrayMessageType fromAwt(TrayIcon.MessageType messageType) {
		if (messageType == null)
			return NONE;

		switch (messageType) {
			case ERROR:
				return ERROR;
			case WARNING:
				return WARNING;
			case INFO:
				return INFO;
			case NONE:
			default:
				return NONE;
		}
	}

	TrayIcon.MessageType toAwt() {
		switch (this) {
			case ERROR:
				return TrayIcon.MessageType.ERROR;
			case WARNING:
				return TrayIcon.MessageType.WARNING;
			case INFO:
				return TrayIcon.MessageType.INFO;
			case NONE:
			default:
				return TrayIcon.MessageType.NONE;
		}
	}
}
