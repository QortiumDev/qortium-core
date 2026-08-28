package org.qortium.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.network.Network;
import org.qortium.network.NetworkData;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.utils.ExecuteProduceConsume;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Detects a repository call that remains blocked long enough that ordinary Core recovery can no
 * longer make progress. The probe runs outside this watchdog thread so a wedged JDBC call cannot
 * prevent the watchdog from capturing evidence and starting the emergency restart helper.
 */
public class RepositoryHealthWatchdog extends Thread {

	@FunctionalInterface
	interface EmergencyRecovery {
		boolean restart(String reason, Path evidencePath);
	}

	@FunctionalInterface
	interface EvidenceWriter {
		Path write(String reason, long probeAge, int lastHealthyHeight, long lastHealthyAt) throws Exception;
	}

	private static final Logger LOGGER = LogManager.getLogger(RepositoryHealthWatchdog.class);
	private static final DateTimeFormatter SNAPSHOT_TIME =
			DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
	private static final long PLANNED_MAINTENANCE_GRACE = 60 * 60 * 1000L;

	private final long checkInterval;
	private final long restartTimeout;
	private final LongSupplier clock;
	private final Supplier<Future<Integer>> probeSubmitter;
	private final EmergencyRecovery emergencyRecovery;
	private final EvidenceWriter evidenceWriter;
	private final ExecutorService probeExecutor;

	private volatile boolean stopping;
	private Future<Integer> activeProbe;
	private long activeProbeStartedAt;
	private long lastHealthyAt;
	private int lastHealthyHeight;
	private boolean stallWarningLogged;
	private boolean recoveryStarted;

	public RepositoryHealthWatchdog() {
		Settings settings = Settings.getInstance();
		this.checkInterval = settings.getRepositoryHealthCheckInterval();
		this.restartTimeout = settings.getRepositoryHealthRestartTimeout();
		this.clock = System::currentTimeMillis;
		this.emergencyRecovery = RestartNode::emergencyRestart;
		this.evidenceWriter = RepositoryHealthWatchdog::writeEvidenceSnapshot;
		this.probeExecutor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "Repository health probe");
			thread.setDaemon(true);
			return thread;
		});
		this.probeSubmitter = () -> this.probeExecutor.submit(RepositoryHealthWatchdog::probeRepository);
		this.lastHealthyAt = this.clock.getAsLong();
		this.setName("Repository health watchdog");
		this.setDaemon(true);
	}

	RepositoryHealthWatchdog(long checkInterval, long restartTimeout, LongSupplier clock,
			Supplier<Future<Integer>> probeSubmitter, EmergencyRecovery emergencyRecovery,
			EvidenceWriter evidenceWriter) {
		this.checkInterval = checkInterval;
		this.restartTimeout = restartTimeout;
		this.clock = clock;
		this.probeSubmitter = probeSubmitter;
		this.emergencyRecovery = emergencyRecovery;
		this.evidenceWriter = evidenceWriter;
		this.probeExecutor = null;
		this.lastHealthyAt = this.clock.getAsLong();
		this.setName("Repository health watchdog test");
		this.setDaemon(true);
	}

	@Override
	public void run() {
		LOGGER.info("Repository health watchdog started (checkInterval={}ms, restartTimeout={}ms)",
				this.checkInterval, this.restartTimeout);

		try {
			while (!this.stopping && !Controller.isStopping()) {
				poll();
				Thread.sleep(this.checkInterval);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			if (this.probeExecutor != null)
				this.probeExecutor.shutdownNow();
		}
	}

	synchronized void poll() {
		long now = this.clock.getAsLong();
		long suppressedSince = RepositoryManager.getHealthCheckSuppressedSince();
		if (RepositoryManager.isHealthCheckSuppressed()
				&& (suppressedSince == 0L
				|| System.currentTimeMillis() - suppressedSince < PLANNED_MAINTENANCE_GRACE))
			return;

		if (this.activeProbe == null) {
			this.activeProbe = this.probeSubmitter.get();
			this.activeProbeStartedAt = now;
			this.stallWarningLogged = false;
			return;
		}

		if (this.activeProbe.isDone()) {
			try {
				this.lastHealthyHeight = this.activeProbe.get();
				this.lastHealthyAt = now;
				if (this.stallWarningLogged)
					LOGGER.info("Repository health probe recovered at height {}", this.lastHealthyHeight);
			} catch (Exception e) {
				LOGGER.warn("Repository health probe failed: {}", e.getMessage());
			}

			this.activeProbe = null;
			this.activeProbeStartedAt = 0L;
			this.stallWarningLogged = false;
			return;
		}

		long probeAge = now - this.activeProbeStartedAt;
		if (!this.stallWarningLogged && probeAge >= this.checkInterval * 2L) {
			this.stallWarningLogged = true;
			LOGGER.error("Repository health probe has been blocked for {}ms", probeAge);
		}

		if (probeAge < this.restartTimeout || this.recoveryStarted)
			return;

		String reason = String.format("repository probe blocked for %dms (last healthy height %d at %d)",
				probeAge, this.lastHealthyHeight, this.lastHealthyAt);
		Path evidencePath = null;
		try {
			evidencePath = this.evidenceWriter.write(reason, probeAge, this.lastHealthyHeight, this.lastHealthyAt);
		} catch (Exception e) {
			LOGGER.error("Unable to write repository-stall evidence before recovery", e);
		}

		LOGGER.fatal("Repository is unresponsive; starting managed emergency recovery. Evidence: {}",
				evidencePath == null ? "unavailable" : evidencePath);
		this.recoveryStarted = this.emergencyRecovery.restart(reason, evidencePath);
	}

	public synchronized void shutdown() {
		this.stopping = true;
		if (this.activeProbe != null)
			this.activeProbe.cancel(true);
		this.interrupt();
		if (this.probeExecutor != null)
			this.probeExecutor.shutdownNow();
	}

	private static int probeRepository() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			repository.discardChanges();
			return height;
		}
	}

	static Path writeEvidenceSnapshot(String reason, long probeAge, int lastHealthyHeight,
			long lastHealthyAt) throws IOException {
		Path logDirectory = Paths.get(System.getProperty("qortium.log.dir", ".")).toAbsolutePath().normalize();
		Files.createDirectories(logDirectory);
		Path outputPath = logDirectory.resolve("repository-stall-" + SNAPSHOT_TIME.format(Instant.now()) + ".txt");

		StringBuilder output = new StringBuilder(64 * 1024);
		output.append("captured=").append(Instant.now()).append('\n');
		output.append("pid=").append(ProcessHandle.current().pid()).append('\n');
		output.append("reason=").append(reason).append('\n');
		output.append("probeAgeMs=").append(probeAge).append('\n');
		output.append("lastHealthyHeight=").append(lastHealthyHeight).append('\n');
		output.append("lastHealthyAt=").append(lastHealthyAt).append('\n');
		output.append("uptimeMs=").append(Controller.uptime()).append('\n');
		output.append("plannedRepositoryOperation=").append(RepositoryManager.isHealthCheckSuppressed()).append('\n');
		output.append("plannedRepositoryOperationSince=").append(RepositoryManager.getHealthCheckSuppressedSince()).append('\n');

		try {
			output.append("isSynchronizing=").append(Synchronizer.getInstance().isSynchronizing()).append('\n');
			appendWorkerStats(output, "chainWorkers", Network.getInstance().getStatsSnapshot());
			appendWorkerStats(output, "dataWorkers", NetworkData.getInstance().getStatsSnapshot());
		} catch (Exception e) {
			output.append("runtimeSummaryError=").append(e.getClass().getSimpleName()).append(':')
					.append(e.getMessage()).append('\n');
		}

		ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
		ThreadInfo[] threadInfos = threadMxBean.dumpAllThreads(true, true);
		output.append("threadCount=").append(threadInfos.length).append("\n\n");
		for (ThreadInfo threadInfo : threadInfos)
			output.append(threadInfo).append('\n');

		Files.writeString(outputPath, output.toString(), StandardCharsets.UTF_8);
		return outputPath;
	}

	private static void appendWorkerStats(StringBuilder output, String prefix,
			ExecuteProduceConsume.StatsSnapshot stats) {
		output.append(prefix).append("Active=").append(stats.activeThreadCount).append('\n');
		output.append(prefix).append("Consumers=").append(stats.consumerCount).append('\n');
		output.append(prefix).append("SpawnFailures=").append(stats.spawnFailures).append('\n');
	}
}
