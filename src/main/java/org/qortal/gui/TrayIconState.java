package org.qortal.gui;

public enum TrayIconState {
	SYNCHRONIZING_CLOCK("icons/qortium_ui_tray_syncing_time-alt.png"),
	MINTING("icons/qortium_ui_tray_minting.png"),
	SYNCHRONIZING("icons/qortium_ui_tray_syncing.png"),
	SYNCED("icons/qortium_ui_tray_synced.png");

	private final String resourceName;

	TrayIconState(String resourceName) {
		this.resourceName = resourceName;
	}

	public String getResourceName() {
		return this.resourceName;
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
