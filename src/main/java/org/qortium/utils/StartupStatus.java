package org.qortium.utils;

import java.util.function.Consumer;

public final class StartupStatus {

	private static final Consumer<String> NO_OP_UPDATER = text -> {
	};

	private static volatile Consumer<String> updater = NO_OP_UPDATER;
	private static volatile String latestStatus;

	private StartupStatus() {
	}

	public static void update(String text) {
		latestStatus = text;

		try {
			updater.accept(text);
		} catch (RuntimeException e) {
			updater = NO_OP_UPDATER;
		}
	}

	public static void setUpdater(Consumer<String> newUpdater) {
		updater = newUpdater == null ? NO_OP_UPDATER : newUpdater;

		if (latestStatus != null)
			update(latestStatus);
	}

	public static void clearUpdater() {
		updater = NO_OP_UPDATER;
	}
}
