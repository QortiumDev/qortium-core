package org.qortium.chat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.utils.NTP;
import org.qortium.utils.NamedThreadFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatCleanupManager {

	private static final Logger LOGGER = LogManager.getLogger(ChatCleanupManager.class);

	private static final ChatCleanupManager INSTANCE = new ChatCleanupManager();

	private static final long INITIAL_CLEANUP_DELAY = 1L;
	private static final long CLEANUP_INTERVAL = 5L;

	private final Object lifecycleLock = new Object();

	private ScheduledExecutorService executor;

	public static ChatCleanupManager getInstance() {
		return INSTANCE;
	}

	private ChatCleanupManager() {
	}

	public void start() {
		synchronized (this.lifecycleLock) {
			if (this.executor != null && !this.executor.isShutdown())
				return;

			this.executor = Executors.newSingleThreadScheduledExecutor(
					new NamedThreadFactory("Chat Cleanup", Thread.NORM_PRIORITY));
			this.executor.scheduleWithFixedDelay(this::runScheduledCleanup, INITIAL_CLEANUP_DELAY, CLEANUP_INTERVAL, TimeUnit.MINUTES);
		}
	}

	public void shutdown() {
		ScheduledExecutorService executorToShutdown;

		synchronized (this.lifecycleLock) {
			if (this.executor == null)
				return;

			executorToShutdown = this.executor;
			this.executor = null;
		}

		executorToShutdown.shutdownNow();
		try {
			if (!executorToShutdown.awaitTermination(5, TimeUnit.SECONDS))
				LOGGER.warn("Timed out waiting for chat cleanup manager to terminate");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Interrupted while waiting for chat cleanup manager to terminate", e);
		}
	}

	void cleanupOnce() {
		Long now = NTP.getTime();
		if (now == null)
			return;

		long cutoffTimestamp = now - Settings.getInstance().getChatMessageRetentionPeriod();

		try (final Repository repository = RepositoryManager.getRepository()) {
			try {
				int deleted = repository.getChatStoreRepository().deleteOlderThan(cutoffTimestamp);
				repository.saveChanges();

				if (deleted > 0)
					LOGGER.debug("Removed {} expired chat message(s)", deleted);
			} catch (DataException e) {
				try {
					repository.discardChanges();
				} catch (DataException discardException) {
					LOGGER.debug("Unable to discard failed chat cleanup changes", discardException);
				}

				throw e;
			}
		} catch (DataException e) {
			LOGGER.warn("Unable to clean expired chat messages", e);
		}
	}

	private void runScheduledCleanup() {
		try {
			cleanupOnce();
		} catch (RuntimeException e) {
			LOGGER.warn("Unexpected chat cleanup failure", e);
		}
	}

}
