package org.qortium.controller.repository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.block.BlockChain;
import org.qortium.controller.Controller;
import org.qortium.controller.Synchronizer;
import org.qortium.data.block.BlockData;
import org.qortium.repository.BlockArchiveWriter;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.transform.TransformationException;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.util.List;

import static java.lang.Thread.NORM_PRIORITY;

public class BlockArchiver implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(BlockArchiver.class);

	private static final long INITIAL_SLEEP_PERIOD = 15 * 60 * 1000L; // ms

	public void run() {
		Thread.currentThread().setName("Block archiver");

		if (!Settings.getInstance().isArchiveEnabled() || Settings.getInstance().isLite()) {
			return;
		}

		int startHeight;

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Don't even start building until initial rush has ended
			Thread.sleep(INITIAL_SLEEP_PERIOD);

			startHeight = repository.getBlockArchiveRepository().getBlockArchiveHeight();

			// Don't attempt to archive if we have no ATStatesHeightIndex, as it will be too slow
			boolean hasAtStatesHeightIndex = repository.getATRepository().hasAtStatesHeightIndex();
			if (!hasAtStatesHeightIndex) {
				LOGGER.info("Unable to start block archiver due to missing ATStatesHeightIndex. Bootstrapping is recommended.");
				repository.discardChanges();
				return;
			}
		} catch (InterruptedException e) {
			if (Controller.isStopping()) {
				LOGGER.info("Block Archiving shutting down");
			} else {
				LOGGER.error("Block Archiving interrupted during initialization. Restart ASAP. Report this error immediately to the developers.", e);
			}
			return;
		} catch (Exception e) {
			LOGGER.error("Block Archiving is not working! Not trying again. Restart ASAP. Report this error immediately to the developers.", e);
			return;
		}

		LOGGER.info("Starting block archiver from height {}...", startHeight);

		while (!Controller.isStopping()) {
			try (final Repository repository = RepositoryManager.getRepository()) {

				try {
					repository.discardChanges();

					Thread.sleep(Settings.getInstance().getArchiveInterval());

					BlockData chainTip = Controller.getInstance().getChainTip();
					if (chainTip == null || NTP.getTime() == null) {
						continue;
					}

					// Don't even attempt if we're mid-sync as our repository requests will be delayed for ages
					if (Synchronizer.getInstance().isSynchronizing()) {
						continue;
					}

					// Don't attempt to archive if we're not synced yet
					final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
					if (minLatestBlockTimestamp == null || chainTip.getTimestamp() < minLatestBlockTimestamp) {
						continue;
					}

					// Build cache of blocks
					try {
						final int maximumArchiveHeight = BlockArchiveWriter.getMaxArchiveHeight(repository);
						final int checkpointArchiveHeight = selectCheckpointArchiveHeight(
								BlockChain.getInstance().getCheckpoints(), startHeight, maximumArchiveHeight);
						final int writerEndHeight = checkpointArchiveHeight > 0 ? checkpointArchiveHeight : maximumArchiveHeight;

						BlockArchiveWriter writer = new BlockArchiveWriter(startHeight, writerEndHeight, repository);
						if (checkpointArchiveHeight > 0)
							writer.setShouldEnforceFileSizeTarget(false);

						BlockArchiveWriter.BlockArchiveWriteResult result = writer.write();
						switch (result) {
							case OK:
								// Advance from the height actually written, even if the writer clamped its start.
								startHeight = writer.getLastWrittenHeight() + 1;
								repository.getBlockArchiveRepository().setBlockArchiveHeight(startHeight);
								repository.saveChanges();
								break;

							case STOPPING:
								return;

								// We've reached the limit of the blocks we can archive
								// Sleep for a while to allow more to become available
							case NOT_ENOUGH_BLOCKS:
								// We didn't reach our file size target, so that must mean that we don't have enough blocks
								// yet or something went wrong. Sleep for a while and then try again.
								repository.discardChanges();
								Thread.sleep(2 * 60 * 60 * 1000L); // 1 hour
								break;

							case BLOCK_NOT_FOUND:
								// We tried to archive a block that didn't exist. This is a major failure and likely means
								// that a bootstrap or re-sync is needed. Try again every minute until then.
								LOGGER.info("Error: block not found when building archive. If this error persists, " +
										"a bootstrap or re-sync may be needed.");
								repository.discardChanges();
								Thread.sleep(60 * 1000L); // 1 minute
								break;
						}

					} catch (IOException | TransformationException e) {
						LOGGER.info("Caught exception when creating block cache", e);
					}
				} catch (InterruptedException e) {
					if (Controller.isStopping()) {
						LOGGER.info("Block Archiving Shutting Down");
					} else {
						LOGGER.warn("Block Archiving interrupted. Trying again. Report this error immediately to the developers.", e);
					}
				} catch (Exception e) {
					LOGGER.warn("Block Archiving stopped working. Trying again. Report this error immediately to the developers.", e);
				}
			} catch(Exception e){
				LOGGER.error("Block Archiving is not working! Not trying again. Restart ASAP. Report this error immediately to the developers.", e);
			}
		}
	}

	static int selectCheckpointArchiveHeight(List<BlockChain.Checkpoint> checkpoints, int startHeight, int maximumArchiveHeight) {
		if (checkpoints == null || checkpoints.isEmpty())
			return 0;

		int normalizedStartHeight = Math.max(startHeight, 2);
		return checkpoints.stream()
				.mapToInt(checkpoint -> checkpoint.height)
				.filter(height -> height >= normalizedStartHeight && height <= maximumArchiveHeight)
				.min()
				.orElse(0);
	}
}
