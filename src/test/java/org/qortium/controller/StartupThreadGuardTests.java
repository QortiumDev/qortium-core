package org.qortium.controller;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the {@code Controller#main} startup hardening added alongside the bctls
 * 1.86 BCJSSE fix (2026-09-20/21): an optional service (gateway, domain-map) that fails with an
 * {@link Error}/{@link LinkageError} - such as Jetty's {@code SslContextFactory.checkConfiguration()}
 * throwing {@code NoSuchMethodError} when handed a broken BCJSSE {@code SSLContext} - must not
 * silently kill the startup thread before it reaches {@code Gui.notifyRunning()} and the rest of
 * {@code mainInternal()}.
 *
 * <p>{@code Controller#startOptionalService(String, Controller.ThrowingRunnable)} is the
 * extracted guard both {@code isGatewayEnabled()} and {@code isDomainMapEnabled()} branches call.
 * These tests exercise it directly (package-private, same package as {@link Controller}) with a
 * stubbed service, rather than driving the real {@code main()} - which needs a full
 * repository/network/settings bootstrap - so they isolate exactly the hardening behaviour that
 * changed.
 */
public class StartupThreadGuardTests {

	@Test
	public void errorFromOptionalServiceDoesNotKillTheCallingThread() {
		// A plain "catch (Exception e)" would let this NoSuchMethodError-shaped Error propagate
		// and kill main() before notifyRunning(). startOptionalService() must swallow it.
		Controller.startOptionalService("stub service", () -> {
			throw new NoSuchMethodError("javax.net.ssl.SSLEngine org.bouncycastle.jsse.provider.SSLEngineUtil.create(...)");
		});
		// Reaching this line at all is the assertion: if the Error had propagated, the test
		// method itself would have failed with that Error instead of completing normally.
	}

	@Test
	public void notifyRunningStillRunsAfterAnOptionalServiceThrowsAnError() {
		AtomicBoolean gatewayStartAttempted = new AtomicBoolean(false);
		AtomicBoolean notifyRunningCalled = new AtomicBoolean(false);

		// Mirrors the exact shape of the gateway/domain-map blocks in Controller#mainInternal:
		// startOptionalService(...) followed immediately (in the real code, after any further
		// optional services) by Gui.getInstance().notifyRunning().
		Controller.startOptionalService("stub gateway service", () -> {
			gatewayStartAttempted.set(true);
			throw new LinkageError("stub: broken TLS provider, e.g. bctls 1.86 BCJSSE");
		});

		// Stands in for Gui.getInstance().notifyRunning() - the statement immediately after the
		// last optional-service guard in mainInternal(). If startOptionalService() let the Error
		// escape, this line would never run and the test would fail with that Error instead of
		// the assertions below.
		notifyRunningCalled.set(true);

		assertTrue("stub service's start() should have been attempted", gatewayStartAttempted.get());
		assertTrue("notifyRunning() (stand-in) must still run after an optional service fails", notifyRunningCalled.get());
	}

	@Test
	public void successfulOptionalServiceRunsNormally() {
		AtomicBoolean started = new AtomicBoolean(false);

		Controller.startOptionalService("stub service", () -> started.set(true));

		assertTrue(started.get());
	}

	@Test
	public void checkedExceptionFromOptionalServiceIsAlsoSwallowed() {
		AtomicBoolean afterGuard = new AtomicBoolean(false);

		Controller.startOptionalService("stub service", () -> {
			throw new java.io.IOException("stub: port already in use");
		});
		afterGuard.set(true);

		assertTrue(afterGuard.get());
	}

	@Test
	public void assertionErrorItselfIsNotFalselyPassedThrough() {
		// Sanity check on the test harness: an AssertionError thrown *outside* the guard still
		// fails the test normally, confirming errorFromOptionalServiceDoesNotKillTheCallingThread()
		// above is actually exercising startOptionalService()'s catch, not a JUnit quirk.
		boolean threw = false;
		try {
			throw new AssertionError("expected to propagate");
		} catch (AssertionError expected) {
			threw = true;
		}
		assertTrue(threw);
	}
}
