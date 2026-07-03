package org.qortium.controller;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.repository.BlockArchiveReader;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.NTP;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArchiveFastSyncReplayStateTests extends Common {

	@Before
	public void beforeTest() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-block-archive.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
		BlockArchiveReader.getInstance().invalidateFileListCache();
		FieldUtils.writeField(Settings.getInstance(), "defaultArchiveVersion", 1, true);
	}

	@After
	public void afterTest() {
		BlockArchiveReader.getInstance().invalidateFileListCache();
	}

	@Test
	public void testReplayStatePersistsAndClears() throws Exception {
		byte[] checkpointSignature = new byte[128];
		Arrays.fill(checkpointSignature, (byte) 7);

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertFalse(ArchiveFastSyncManager.hasActiveReplayState(repository));

			ArchiveFastSyncManager.ReplayState replayState =
					new ArchiveFastSyncManager.ReplayState(2, 250, checkpointSignature, 250, 50);
			ArchiveFastSyncManager.saveReplayState(repository, replayState);
			repository.saveChanges();

			assertTrue(ArchiveFastSyncManager.hasActiveReplayState(repository));

			ArchiveFastSyncManager.ReplayState loaded = ArchiveFastSyncManager.loadActiveReplayState(repository);
			assertEquals(2, loaded.startHeight);
			assertEquals(250, loaded.checkpointHeight);
			assertArrayEquals(checkpointSignature, loaded.checkpointSignature);
			assertEquals(250, loaded.targetHeight);
			assertEquals(50, loaded.lastReplayedHeight);

			ArchiveFastSyncManager.clearReplayState(repository);
			repository.saveChanges();

			assertFalse(ArchiveFastSyncManager.hasActiveReplayState(repository));
		}
	}

	@Test
	public void testReplayWindowPausesAfterWorkLimit() {
		long windowStart = 1_000L;

		assertFalse(ArchiveFastSyncManager.shouldPauseReplayWindow(1, windowStart, windowStart + 1));
		assertTrue(ArchiveFastSyncManager.shouldPauseReplayWindow(Integer.MAX_VALUE, windowStart, windowStart + 1));
	}

	@Test
	public void testReplayWindowPausesAfterTimeLimit() {
		long windowStart = 1_000L;

		assertFalse(ArchiveFastSyncManager.shouldPauseReplayWindow(0, windowStart, windowStart));
		assertTrue(ArchiveFastSyncManager.shouldPauseReplayWindow(0, windowStart, Long.MAX_VALUE));
	}
}
