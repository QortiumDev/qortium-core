package org.qortium.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;

/** Standalone entry point for packaged-Core acceptance inside a loopback-only namespace. */
public final class PirateUnifiedLoopbackLightwalletdMain {

	private static final int REGTEST_PORT = 9067;
	private static final String JAVA_CHAIN = "regtest";
	private static final String NATIVE_CHAIN = "main";

	private PirateUnifiedLoopbackLightwalletdMain() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2)
			throw new IllegalArgumentException("Expected <absolute-ready-file> <absolute-audit-file>");

		Path readyPath = absoluteNewPath(args[0], "ready");
		Path auditPath = absoluteNewPath(args[1], "audit");
		CountDownLatch stopped = new CountDownLatch(1);
		PirateUnifiedLoopbackLightwalletd fixture;
		try {
			fixture = new PirateUnifiedLoopbackLightwalletd(REGTEST_PORT, JAVA_CHAIN, NATIVE_CHAIN);
			if (!("http://127.0.0.1:" + REGTEST_PORT + "/").equals(fixture.endpoint())) {
				fixture.close();
				throw new IOException("Fixture did not bind its exact IPv4 loopback endpoint");
			}
		} catch (Exception e) {
			writeAtomically(auditPath, "result=FAIL\n");
			throw e;
		}
		Thread shutdownHook = new Thread(() -> {
			try {
				fixture.close();
				writeAtomically(auditPath, audit(fixture));
			} catch (Exception e) {
				try {
					writeAtomically(auditPath, "result=FAIL\n");
				} catch (IOException ignored) {
					// The process exit remains a failure if no complete audit can be written.
				}
			} finally {
				stopped.countDown();
			}
		}, "Pirate Unified loopback fixture shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
		writeAtomically(readyPath, "port=" + REGTEST_PORT + "\njavaChainName=" + JAVA_CHAIN
				+ "\nnativeChainName=" + NATIVE_CHAIN + "\n");

		try {
			stopped.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static String audit(PirateUnifiedLoopbackLightwalletd fixture) {
		return "result=PASS\n"
				+ "pirateTipRequests=" + fixture.rpcCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE,
						"GetLatestBlock") + "\n"
				+ "pirateCompleteRanges="
				+ fixture.completeRangeCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE) + "\n"
				+ "pirateTipRanges=" + fixture.pirateTipRangeCount() + "\n"
				+ "pirateScannedBlocks=" + fixture.pirateScannedBlockCount() + "\n"
				+ "cashCompleteRanges="
				+ fixture.completeRangeCount(PirateUnifiedLoopbackLightwalletd.CASH_SERVICE) + "\n"
				+ "forbiddenRpcs=" + fixture.forbiddenRpcCount() + "\n"
				+ "unexpectedRpcs=" + fixture.unexpectedRpcCount() + "\n"
				+ "activationProbes=" + fixture.activationProbeCount() + "\n"
				+ "subtreeProbes=" + fixture.subtreeProbeCount() + "\n"
				+ "observedRanges=" + fixture.observedRanges().size() + "\n";
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
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, target);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
