package org.qortium.gui;

public enum TrayIconState {
	SYNCHRONIZING_CLOCK("icons/qortium_ui_tray_syncing_time-alt.png", "qortium-tray-syncing-time"),
	MINTING("icons/qortium_ui_tray_minting.png", "qortium-tray-minting"),
	SYNCHRONIZING("icons/qortium_ui_tray_syncing.png", "qortium-tray-syncing"),
	SYNCED("icons/qortium_ui_tray_synced.png", "qortium-tray-synced");

	private final String resourceName;
	private final String iconName;

	TrayIconState(String resourceName, String iconName) {
		this.resourceName = resourceName;
		this.iconName = iconName;
	}

	public String getResourceName() {
		return this.resourceName;
	}

	public String getIconName() {
		return this.iconName;
	}

	static TrayIconState fromLegacyId(int iconId) {
		switch (iconId) {
			case 1:
				return SYNCHRONIZING_CLOCK;
			case 2:
				return MINTING;
			case 3:
				return SYNCHRONIZING;
			case 4:
			default:
				return SYNCED;
		}
	}
}
