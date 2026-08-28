package org.qortium.controller;

import org.junit.Test;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class RepositoryHealthWatchdogTests {

	@Test
	public void testHealthyProbeCompletesWithoutRecovery() {
		AtomicLong clock = new AtomicLong(1000L);
		CompletableFuture<Integer> probe = CompletableFuture.completedFuture(123);
		AtomicInteger recoveryCalls = new AtomicInteger();

		RepositoryHealthWatchdog watchdog = new RepositoryHealthWatchdog(
				100L, 500L, clock::get, () -> probe,
				(reason, evidence) -> {
					recoveryCalls.incrementAndGet();
					return true;
				},
				(reason, age, height, healthyAt) -> null);

		watchdog.poll();
		clock.addAndGet(100L);
		watchdog.poll();

		assertEquals(0, recoveryCalls.get());
	}

	@Test
	public void testSustainedBlockedProbeCapturesEvidenceAndStartsRecoveryOnce() {
		AtomicLong clock = new AtomicLong(1000L);
		CompletableFuture<Integer> blockedProbe = new CompletableFuture<>();
		AtomicInteger evidenceCalls = new AtomicInteger();
		AtomicInteger recoveryCalls = new AtomicInteger();
		Path evidencePath = Path.of("repository-stall-test.txt");

		RepositoryHealthWatchdog watchdog = new RepositoryHealthWatchdog(
				100L, 500L, clock::get, () -> blockedProbe,
				(reason, evidence) -> {
					recoveryCalls.incrementAndGet();
					assertEquals(evidencePath, evidence);
					return true;
				},
				(reason, age, height, healthyAt) -> {
					evidenceCalls.incrementAndGet();
					assertEquals(500L, age);
					return evidencePath;
				});

		watchdog.poll();
		clock.set(1499L);
		watchdog.poll();
		assertEquals(0, recoveryCalls.get());

		clock.set(1500L);
		watchdog.poll();
		watchdog.poll();

		assertEquals(1, evidenceCalls.get());
		assertEquals(1, recoveryCalls.get());
	}

	@Test
	public void testFailedRecoveryLaunchCanBeRetried() {
		AtomicLong clock = new AtomicLong(1000L);
		CompletableFuture<Integer> blockedProbe = new CompletableFuture<>();
		AtomicInteger recoveryCalls = new AtomicInteger();

		RepositoryHealthWatchdog watchdog = new RepositoryHealthWatchdog(
				100L, 500L, clock::get, () -> blockedProbe,
				(reason, evidence) -> {
					recoveryCalls.incrementAndGet();
					return false;
				},
				(reason, age, height, healthyAt) -> null);

		watchdog.poll();
		clock.set(1500L);
		watchdog.poll();
		watchdog.poll();

		assertEquals(2, recoveryCalls.get());
	}
}
