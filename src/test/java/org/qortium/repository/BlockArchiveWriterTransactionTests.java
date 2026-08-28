package org.qortium.repository;

import org.junit.Before;
import org.junit.Test;
import org.qortium.test.common.Common;

import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlockArchiveWriterTransactionTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testReleasesIdleReadTransactionSoCheckpointCanFinish() throws Exception {
		ExecutorService checkpointExecutor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "archive-checkpoint-test");
			thread.setDaemon(true);
			return thread;
		});

		try (Repository archiveRepository = RepositoryManager.getRepository();
			 Repository checkpointRepository = RepositoryManager.getRepository()) {
			// This SELECT opens the same kind of idle read transaction that block construction and
			// serialization can leave behind in the long-lived archive repository.
			archiveRepository.getBlockRepository().getBlockchainHeight();

			CountDownLatch checkpointStarted = new CountDownLatch(1);
			Future<Boolean> checkpoint = checkpointExecutor.submit(() -> {
				try (Statement statement = checkpointRepository.getConnection().createStatement()) {
					checkpointStarted.countDown();
					return statement.execute("CHECKPOINT");
				}
			});

			assertTrue(checkpointStarted.await(5, TimeUnit.SECONDS));
			Thread.sleep(100L);
			assertFalse("checkpoint should wait for the archive read transaction", checkpoint.isDone());

			BlockArchiveWriter.releaseRepositoryBeforeWait(archiveRepository);

			checkpoint.get(5, TimeUnit.SECONDS);
			assertTrue("checkpoint should finish after the archive transaction is released", checkpoint.isDone());
		} finally {
			checkpointExecutor.shutdownNow();
			assertTrue(checkpointExecutor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}
}
