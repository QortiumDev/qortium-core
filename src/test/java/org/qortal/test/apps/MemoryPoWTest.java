package org.qortal.test.apps;

import org.qortal.crypto.MemoryPoW;
import org.qortal.utils.NTP;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeoutException;

public class MemoryPoWTest {

	private static final int DATA_LENGTH = 256;
	private static final int LEGACY_SAMPLE_COUNT = 100;
	private static final long BENCHMARK_RANDOM_SEED = 20260524L;

	public static void main(String[] args) {
		NTP.setFixedOffset(0L);

		if (args.length == 2) {
			runLegacyTiming(args);
			return;
		}

		if (args.length >= 4) {
			runBenchmark(args);
			return;
		}

		usage();
	}

	private static void runLegacyTiming(String[] args) {
		int workBufferLength = parseWorkBufferLength(args[0]);
		int difficulty = Integer.parseInt(args[1]);

		Random random = new Random();
		byte[] data = new byte[DATA_LENGTH];
		long[] times = new long[LEGACY_SAMPLE_COUNT];

		int maxNonce = 0;

		for (int i = 0; i < times.length; ++i) {
			random.nextBytes(data);

			long startTime = System.nanoTime();
			int nonce = MemoryPoW.compute2(data, workBufferLength, difficulty);
			times[i] = elapsedMs(startTime);

			if (nonce > maxNonce)
				maxNonce = nonce;
		}

		TimingStats stats = TimingStats.from(times, times.length);
		System.out.println(String.format("%d timings, mean: %.2f ms, stddev: %.2f ms",
				times.length, stats.mean, stats.standardDeviation));
		System.out.println(String.format("Max nonce: %d", maxNonce));
	}

	private static void runBenchmark(String[] args) {
		int workBufferLength = parseWorkBufferLength(args[0]);
		int samples = Integer.parseInt(args[1]);
		long timeout = Long.parseLong(args[2]);

		if (samples <= 0) {
			System.err.println("samples must be greater than 0");
			System.exit(2);
		}

		if (timeout <= 0) {
			System.err.println("timeout-ms must be greater than 0");
			System.exit(2);
		}

		System.out.println(String.format("bufferMiB=%d samples=%d timeoutMs=%d seed=%d",
				workBufferLength / 1024 / 1024, samples, timeout, BENCHMARK_RANDOM_SEED));

		for (int i = 3; i < args.length; ++i)
			runBenchmarkForDifficulty(workBufferLength, samples, timeout, Integer.parseInt(args[i]));
	}

	private static void runBenchmarkForDifficulty(int workBufferLength, int samples, long timeout, int difficulty) {
		Random random = new Random(BENCHMARK_RANDOM_SEED + difficulty);
		long[] times = new long[samples];
		int completed = 0;
		int timeouts = 0;
		int verificationFailures = 0;
		int maxNonce = -1;

		for (int sample = 0; sample < samples; ++sample) {
			byte[] data = new byte[DATA_LENGTH];
			random.nextBytes(data);

			long startTime = System.nanoTime();

			try {
				int nonce = MemoryPoW.compute2(data, workBufferLength, difficulty, timeout);
				long elapsed = elapsedMs(startTime);
				boolean verified = MemoryPoW.verify2(data, workBufferLength, difficulty, nonce);

				if (!verified)
					++verificationFailures;

				times[completed++] = elapsed;
				if (nonce > maxNonce)
					maxNonce = nonce;

				System.out.println(String.format("sample difficulty=%d sample=%d elapsedMs=%d nonce=%d verified=%s",
						difficulty, sample + 1, elapsed, nonce, verified));
			} catch (TimeoutException e) {
				++timeouts;
				System.out.println(String.format("sample difficulty=%d sample=%d timeout elapsedMs=%d",
						difficulty, sample + 1, elapsedMs(startTime)));
			}
		}

		if (completed == 0) {
			System.out.println(String.format("summary difficulty=%d samples=%d completed=0 timeouts=%d verifyFailures=%d meanMs=n/a medianMs=n/a minMs=n/a maxMs=n/a stddevMs=n/a maxNonce=n/a",
					difficulty, samples, timeouts, verificationFailures));
			return;
		}

		TimingStats stats = TimingStats.from(times, completed);
		System.out.println(String.format("summary difficulty=%d samples=%d completed=%d timeouts=%d verifyFailures=%d meanMs=%.2f medianMs=%.2f minMs=%d maxMs=%d stddevMs=%.2f maxNonce=%d",
				difficulty, samples, completed, timeouts, verificationFailures, stats.mean, stats.median,
				stats.min, stats.max, stats.standardDeviation, maxNonce));
	}

	private static int parseWorkBufferLength(String bufferSizeMiB) {
		return Integer.parseInt(bufferSizeMiB) * 1024 * 1024;
	}

	private static long elapsedMs(long startTime) {
		return (System.nanoTime() - startTime) / 1_000_000L;
	}

	private static void usage() {
		System.err.println("usage:");
		System.err.println("  MemoryPoW <buffer-size-MB> <difficulty>");
		System.err.println("  MemoryPoW <buffer-size-MB> <samples> <timeout-ms> <difficulty> [difficulty...]");
		System.exit(2);
	}

	private static class TimingStats {
		private final long min;
		private final long max;
		private final double mean;
		private final double median;
		private final double standardDeviation;

		private TimingStats(long min, long max, double mean, double median, double standardDeviation) {
			this.min = min;
			this.max = max;
			this.mean = mean;
			this.median = median;
			this.standardDeviation = standardDeviation;
		}

		private static TimingStats from(long[] times, int count) {
			long[] sortedTimes = Arrays.copyOf(times, count);
			Arrays.sort(sortedTimes);

			long min = sortedTimes[0];
			long max = sortedTimes[count - 1];
			double sum = 0.0;
			for (int i = 0; i < count; ++i)
				sum += sortedTimes[i];

			double mean = sum / count;
			double median = count % 2 == 0
					? (sortedTimes[count / 2 - 1] + sortedTimes[count / 2]) / 2.0
					: sortedTimes[count / 2];

			double varianceSum = 0.0;
			for (int i = 0; i < count; ++i) {
				double offset = sortedTimes[i] - mean;
				varianceSum += offset * offset;
			}

			double standardDeviation = count > 1 ? Math.sqrt(varianceSum / (count - 1)) : 0.0;
			return new TimingStats(min, max, mean, median, standardDeviation);
		}
	}

}
