package org.qortium.list;

import org.junit.Before;
import org.junit.Test;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;
import org.qortium.utils.ListUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for a ConcurrentModificationException reaching the API.
 * <p>
 * Resource lists are read on API threads - a chat search reaches them through
 * {@code ListUtils.blockedChatNames()} -> {@code getStringsInList} -> {@code getList}, and the
 * result is then iterated to build SQL. Meanwhile any thread may add to or reload a list. Both
 * {@link ResourceListManager} and {@link ResourceList} held their collections in a
 * {@code Collections.synchronizedList}, which does NOT make traversal safe (only individual
 * operations), and in both classes the wrapper was then discarded anyway - the manager's
 * constructor and {@code ResourceList.load()} each replaced the field with a plain ArrayList.
 * <p>
 * The result was that a read concurrent with a write threw ConcurrentModificationException out of
 * {@code ChatResource.searchChat}. It surfaced as an intermittent failure in
 * ChatCleanupManagerTests; these tests reproduce the same race directly and deterministically
 * enough to guard the fix.
 */
public class ResourceListConcurrencyTests extends Common {

	private static final String LIST_NAME = "blockedChatNames";
	private static final int ITERATIONS = 3_000;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		ResourceListManager.reset();
	}

	/**
	 * A reader traversing the manager's list of lists while another thread adds new ones. This is
	 * the exact shape that failed: getList() iterated this.lists with no lock while a concurrent
	 * getList() for an unseen name appended to it.
	 */
	@Test
	public void testReadingListsWhileNewListsAreCreatedDoesNotThrow() throws Exception {
		ResourceListManager manager = ResourceListManager.getInstance();

		assertNoConcurrentModification(
				() -> {
					for (int i = 0; i < ITERATIONS; ++i)
						manager.getListNames();
				},
				() -> {
					for (int i = 0; i < ITERATIONS; ++i)
						manager.addToList("concurrentList" + i, "item", false);
				});
	}

	/**
	 * The same race one level down, on the read path an API request actually takes: the strings
	 * inside a single list, traversed while that list is being written.
	 */
	@Test
	public void testReadingBlockedNamesWhileTheyChangeDoesNotThrow() throws Exception {
		ResourceListManager manager = ResourceListManager.getInstance();
		manager.addToList(LIST_NAME, "seed", false);

		assertNoConcurrentModification(
				() -> {
					for (int i = 0; i < ITERATIONS; ++i) {
						// Traverse exactly as addChatBlockClauses does with the returned list.
						List<String> blocked = ListUtils.blockedChatNames();
						int counted = 0;
						for (String name : blocked)
							if (name != null)
								counted++;
						assertTrue(counted >= 0);
					}
				},
				() -> {
					for (int i = 0; i < ITERATIONS; ++i) {
						manager.addToList(LIST_NAME, "name" + i, false);
						manager.removeFromList(LIST_NAME, "name" + i, false);
					}
				});
	}

	/** Runs reader and writer together and fails with whatever either of them threw. */
	private static void assertNoConcurrentModification(Runnable reader, Runnable writer) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch startLine = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(2);

		Runnable[] bodies = { reader, writer };
		for (Runnable body : bodies) {
			Thread thread = new Thread(() -> {
				try {
					startLine.await();
					body.run();
				} catch (Throwable t) {
					failure.compareAndSet(null, t);
				} finally {
					finished.countDown();
				}
			});
			thread.setDaemon(true);
			thread.start();
		}

		startLine.countDown();
		assertTrue("Reader and writer should finish well inside this window",
				finished.await(60, TimeUnit.SECONDS));

		Throwable thrown = failure.get();
		assertNull(thrown == null ? null : "Concurrent read/write threw " + thrown, thrown);
	}
}
