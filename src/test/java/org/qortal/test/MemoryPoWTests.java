package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.crypto.MemoryPoW;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;
import org.qortal.utils.NTP;

import java.util.Random;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class MemoryPoWTests {

	private static final String RUN_LONG_MEMPOW_TESTS_PROPERTY = "qortium.runLongMempowTests";
	private static final int FAST_WORK_BUFFER_LENGTH = 64 * 1024;
	private static final int FULL_WORK_BUFFER_LENGTH = 8 * 1024 * 1024;

	@Before
	public void beforeTest() {
		NTP.setFixedOffset(0L);
	}

	@Test
	public void testCompute() {
		byte[] data = new byte[] { (byte) 0xaa, (byte) 0xbb, (byte) 0xcc };
		int difficulty = 8;
		int expectedNonce = 55;

		long startTime = System.currentTimeMillis();

		Integer	nonce = MemoryPoW.compute2(data, FAST_WORK_BUFFER_LENGTH, difficulty);

		long finishTime = System.currentTimeMillis();

		System.out.printf("Memory-hard PoW (buffer size: %dKB, leading zeros: %d) took %dms, nonce: %d%n", FAST_WORK_BUFFER_LENGTH / 1024,
				difficulty,
				finishTime - startTime,
				nonce);

		assertEquals(expectedNonce, nonce.intValue());
		assertTrue(MemoryPoW.verify2(data, FAST_WORK_BUFFER_LENGTH, difficulty, nonce));
	}

	@Test
	public void testMultipleComputes() throws DataException {
		assumeTrue(Boolean.getBoolean(RUN_LONG_MEMPOW_TESTS_PROPERTY));

		Common.useDefaultSettings();
		Random random = new Random();

		final int sampleSize = 10;
		final long stddevDivisor = sampleSize * (sampleSize - 1);

		for (int difficulty = 8; difficulty <= 16; difficulty++) {
			byte[] data = new byte[256];
			long[] times = new long[sampleSize];

			long timesS1 = 0;
			long timesS2 = 0;

			int maxNonce = 0;

			for (int i = 0; i < sampleSize; ++i) {
				random.nextBytes(data);

				final long startTime = System.currentTimeMillis();
				int nonce = MemoryPoW.compute2(data, FULL_WORK_BUFFER_LENGTH, difficulty);
				times[i] = System.currentTimeMillis() - startTime;

				timesS1 += times[i];
				timesS2 += times[i] * times[i];

				if (nonce > maxNonce)
					maxNonce = nonce;
			}

			double stddev = (double) Math.sqrt( (sampleSize * timesS2 - timesS1 * timesS1) / stddevDivisor );

			System.out.printf("Difficulty: %d, %d timings, mean: %d ms, stddev: %.2f ms, max nonce: %d%n",
					difficulty,
					sampleSize,
					timesS1 / sampleSize,
					stddev,
					maxNonce);
		}
	}

	@Test
	public void testKnownCompute2() {
		byte[] data = new byte[] { (byte) 0xaa, (byte) 0xbb, (byte) 0xcc };

		int difficulty = 8;
		int expectedNonce = 55;
		int nonce = MemoryPoW.compute2(data, FAST_WORK_BUFFER_LENGTH, difficulty);

		System.out.println(String.format("Difficulty %d, nonce: %d", difficulty, nonce));
		assertEquals(expectedNonce, nonce);

		difficulty = 10;
		expectedNonce = 1356;
		nonce = MemoryPoW.compute2(data, FAST_WORK_BUFFER_LENGTH, difficulty);

		System.out.printf("Difficulty %d, nonce: %d%n", difficulty, nonce);
		assertEquals(expectedNonce, nonce);
	}

	@Test
	public void testKnownVerify() {
		byte[] data = new byte[] { (byte) 0xaa, (byte) 0xbb, (byte) 0xcc };

		int difficulty = 8;
		int expectedNonce = 326;
		assertTrue(MemoryPoW.verify2(data, FULL_WORK_BUFFER_LENGTH, difficulty, expectedNonce));

		difficulty = 14;
		expectedNonce = 11032;
		assertTrue(MemoryPoW.verify2(data, FULL_WORK_BUFFER_LENGTH, difficulty, expectedNonce));
	}

}
