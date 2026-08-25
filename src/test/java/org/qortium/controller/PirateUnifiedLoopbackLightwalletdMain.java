package org.qortium.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone entry point for packaged-Core acceptance inside a loopback-only namespace. */
public final class PirateUnifiedLoopbackLightwalletdMain {

	private static final int REGTEST_PORT = 9067;
	private static final int CUTOVER_PORT = 9068;
	private static final String JAVA_CHAIN = "regtest";
	private static final String NATIVE_CHAIN = "main";

	private PirateUnifiedLoopbackLightwalletdMain() {
	}

	public static void main(String[] args) throws Exception {
		boolean cutoverMode = args.length == 4 && "cutover".equals(args[3]);
		if (args.length != 2 && !cutoverMode)
			throw new IllegalArgumentException(
					"Expected <absolute-ready-file> <absolute-audit-a-file> [<absolute-audit-b-file> cutover]");

		Path readyPath = absoluteNewPath(args[0], "ready");
		Path auditAPath = absoluteNewPath(args[1], "audit A");
		Path auditBPath = cutoverMode ? absoluteNewPath(args[2], "audit B") : null;
		CountDownLatch stopped = new CountDownLatch(1);
		PirateUnifiedLoopbackLightwalletd fixtureA = null;
		PirateUnifiedLoopbackLightwalletd fixtureB = null;
		try {
			fixtureA = new PirateUnifiedLoopbackLightwalletd(REGTEST_PORT, JAVA_CHAIN, NATIVE_CHAIN);
			if (!("http://127.0.0.1:" + REGTEST_PORT + "/").equals(fixtureA.endpoint()))
				throw new IOException("Fixture did not bind its exact IPv4 loopback endpoint");
			if (cutoverMode) {
				fixtureB = new PirateUnifiedLoopbackLightwalletd(CUTOVER_PORT, JAVA_CHAIN, NATIVE_CHAIN,
						PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT + 4L);
				if (!("http://127.0.0.1:" + CUTOVER_PORT + "/").equals(fixtureB.endpoint()))
					throw new IOException("Cutover fixture did not bind its exact IPv4 loopback endpoint");
			}
		} catch (Exception e) {
			closeQuietly(fixtureB);
			closeQuietly(fixtureA);
			writeAtomically(auditAPath, "result=FAIL\n");
			if (auditBPath != null)
				writeAtomically(auditBPath, "result=FAIL\n");
			throw e;
		}

		PirateUnifiedLoopbackLightwalletd finalFixtureA = fixtureA;
		PirateUnifiedLoopbackLightwalletd finalFixtureB = fixtureB;
		AtomicBoolean snapshotsRunning = new AtomicBoolean(cutoverMode);
		AtomicReference<Throwable> snapshotFailure = new AtomicReference<>();
		Thread snapshotThread = null;
		if (cutoverMode) {
			writeAtomically(auditAPath, audit(finalFixtureA, "RUNNING"));
			writeAtomically(auditBPath, audit(finalFixtureB, "RUNNING"));
			snapshotThread = new Thread(() -> {
				while (snapshotsRunning.get()) {
					try {
						Thread.sleep(100L);
						if (snapshotsRunning.get()) {
							writeAtomically(auditAPath, audit(finalFixtureA, "RUNNING"));
							writeAtomically(auditBPath, audit(finalFixtureB, "RUNNING"));
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					} catch (Throwable e) {
						snapshotFailure.compareAndSet(null, e);
						return;
					}
				}
			}, "Pirate Unified loopback audit snapshots");
			snapshotThread.setDaemon(true);
			snapshotThread.start();
		}
		Thread finalSnapshotThread = snapshotThread;
		Thread shutdownHook = new Thread(() -> {
			boolean complete = true;
			try {
				snapshotsRunning.set(false);
				if (finalSnapshotThread != null) {
					finalSnapshotThread.interrupt();
					finalSnapshotThread.join(5_000L);
					complete = !finalSnapshotThread.isAlive();
				}
				complete &= closeQuietly(finalFixtureB);
				complete &= closeQuietly(finalFixtureA);
				complete &= snapshotFailure.get() == null;
				writeAtomically(auditAPath, audit(finalFixtureA, complete ? "PASS" : "FAIL"));
				if (auditBPath != null)
					writeAtomically(auditBPath, audit(finalFixtureB, complete ? "PASS" : "FAIL"));
			} catch (Exception e) {
				try {
					writeAtomically(auditAPath, "result=FAIL\n");
					if (auditBPath != null)
						writeAtomically(auditBPath, "result=FAIL\n");
				} catch (IOException ignored) {
					// The process exit remains a failure if no complete audit can be written.
				}
			} finally {
				stopped.countDown();
			}
		}, "Pirate Unified loopback fixture shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
		if (cutoverMode) {
			writeAtomically(readyPath, "mode=cutover\nportA=" + REGTEST_PORT + "\ntipA="
					+ PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT + "\nportB=" + CUTOVER_PORT + "\ntipB="
					+ (PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT + 4L) + "\njavaChainName=" + JAVA_CHAIN
					+ "\nnativeChainName=" + NATIVE_CHAIN + "\n");
		} else {
			writeAtomically(readyPath, "port=" + REGTEST_PORT + "\njavaChainName=" + JAVA_CHAIN
					+ "\nnativeChainName=" + NATIVE_CHAIN + "\n");
		}

		try {
			stopped.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static String audit(PirateUnifiedLoopbackLightwalletd fixture) {
		return audit(fixture, "PASS");
	}

	static String audit(PirateUnifiedLoopbackLightwalletd fixture, String result) {
		return "result=" + result + "\n"
				+ "tipHeight=" + fixture.tipHeight() + "\n"
				+ "nativeRpcCount=" + fixture.rpcCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE) + "\n"
				+ "pirateTipRequests=" + fixture.rpcCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE,
						"GetLatestBlock") + "\n"
				+ "pirateCompleteRanges="
				+ fixture.completeRangeCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE) + "\n"
				+ "pirateTipRanges=" + fixture.pirateTipRangeCount() + "\n"
				+ "pirateScannedBlocks=" + fixture.pirateScannedBlockCount() + "\n"
				+ "pirateTipBlocks=" + fixture.pirateTipBlockCount() + "\n"
				+ "cashCompleteRanges="
				+ fixture.completeRangeCount(PirateUnifiedLoopbackLightwalletd.CASH_SERVICE) + "\n"
				+ "forbiddenRpcs=" + fixture.forbiddenRpcCount() + "\n"
				+ "unexpectedRpcs=" + fixture.unexpectedRpcCount() + "\n"
				+ "activationProbes=" + fixture.activationProbeCount() + "\n"
				+ "subtreeProbes=" + fixture.subtreeProbeCount() + "\n"
				+ "observedRanges=" + fixture.observedRanges().size() + "\n";
	}

	private static boolean closeQuietly(PirateUnifiedLoopbackLightwalletd fixture) {
		if (fixture == null)
			return true;
		try {
			fixture.close();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static Path absoluteNewPath(String value, String label) throws IOException {
		Path path = Path.of(value);
		if (!path.isAbsolute())
			throw new IOException("Fixture " + label + " path must be absolute");
		path = path.normalize();
		if (Files.exists(path))
			throw new IOException("Fixture " + label + " path already exists");
		Files.createDirectories(path.getParent());
		return path;
	}

	private static void writeAtomically(Path target, String contents) throws IOException {
		Path temporary = Files.createTempFile(target.getParent(), ".pirate-loopback-", ".tmp");
		try {
			Files.writeString(temporary, contents, StandardCharsets.UTF_8,
					StandardOpenOption.TRUNCATE_EXISTING);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
