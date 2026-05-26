package org.qortium.controller;

import org.eclipse.jetty.ee8.websocket.api.Session;
import org.junit.After;
import org.junit.Test;
import org.qortium.test.common.Common;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChatNotifierTests extends Common {

	private final List<Session> sessions = new ArrayList<>();

	@After
	public void afterTest() {
		for (Session session : this.sessions)
			ChatNotifier.getInstance().deregister(session);
	}

	@Test
	public void testRegisterDeregisterWhileNotifyingIsSafe() throws Exception {
		final int mutableListenerCount = 6;
		final int notificationCount = 100;
		final Session stableSession = newSession("stable");
		final AtomicInteger stableNotifications = new AtomicInteger();

		ChatNotifier.getInstance().register(stableSession, chatTransactionData -> stableNotifications.incrementAndGet());

		runConcurrently(mutableListenerCount + 1, index -> {
			if (index == 0) {
				for (int i = 0; i < notificationCount; ++i)
					ChatNotifier.getInstance().onNewChatTransaction(null);

				return;
			}

			Session session = newSession("mutable-" + index);
			for (int i = 0; i < notificationCount; ++i) {
				ChatNotifier.getInstance().register(session, chatTransactionData -> { });
				ChatNotifier.getInstance().deregister(session);
			}
		});

		assertEquals(notificationCount, stableNotifications.get());
	}

	private synchronized Session newSession(String name) {
		Session session = (Session) Proxy.newProxyInstance(
				ChatNotifierTests.class.getClassLoader(),
				new Class[] { Session.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "hashCode":
							return System.identityHashCode(proxy);

						case "equals":
							return proxy == args[0];

						case "toString":
							return name;

						default:
							Class<?> returnType = method.getReturnType();
							if (returnType == boolean.class)
								return false;
							if (returnType == int.class)
								return 0;
							if (returnType == long.class)
								return 0L;
							return null;
					}
				});

		this.sessions.add(session);
		return session;
	}

	private static void runConcurrently(int workerCount, ConcurrentAction action) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(workerCount);
		CountDownLatch readyLatch = new CountDownLatch(workerCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		for (int i = 0; i < workerCount; ++i) {
			final int index = i;
			executor.submit(() -> {
				readyLatch.countDown();

				try {
					startLatch.await();
					action.run(index);
				} catch (Throwable e) {
					failure.compareAndSet(null, e);
				}
			});
		}

		assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
		startLatch.countDown();
		executor.shutdown();
		assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

		rethrowFailure(failure.get());
	}

	private static void rethrowFailure(Throwable failure) throws Exception {
		if (failure == null)
			return;

		if (failure instanceof Exception)
			throw (Exception) failure;

		if (failure instanceof Error)
			throw (Error) failure;

		throw new AssertionError(failure);
	}

	@FunctionalInterface
	private interface ConcurrentAction {
		void run(int index) throws Exception;
	}

}
