package org.qortium.test;

import org.junit.Before;
import org.junit.Test;
import org.qortium.crypto.MemoryPoW;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;
import org.qortium.utils.NTP;

import java.util.Random;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class MemoryPoWTests {

	private static final String RUN_LONG_MEMPOW_TESTS_PROPERTY = "qortium.runLongMempowTests";
	private static final int FAST_WORK_BUFFER_LENGTH = 64 * 1024;
	private static final int FULL_WORK_BUFFER_LENGTH = 8 * 1024 * 1024;
	private static final int LONG_SAMPLE_SIZE = 10;
	private static final long LONG_STDDEV_DIVISOR = LONG_SAMPLE_SIZE * (LONG_SAMPLE_SIZE - 1);
	private static final byte[] TEST_DATA = new byte[] { (byte) 0xaa, (byte) 0xbb, (byte) 0xcc };
	private static final int[][] FULL_BUFFER_VERIFY_FIXTURES = new int[][] {
			{ 8, 326 },
			{ 9, 326 },
			{ 10, 643 },
			{ 11, 1671 },
			{ 12, 9059 },
			{ 13, 9059 },
			{ 14, 11032 }
	};

	@Before
	public void beforeTest() {
		NTP.setFixedOffset(0L);
	}

	@Test
	public void testCompute() {
		int difficulty = 8;
		int expectedNonce = 55;

		long startTime = System.currentTimeMillis();

		Integer	nonce = MemoryPoW.compute2(TEST_DATA, FAST_WORK_BUFFER_LENGTH, difficulty);

		long finishTime = System.currentTimeMillis();

		System.out.printf("Memory-hard PoW (buffer size: %dKB, leading zeros: %d) took %dms, nonce: %d%n", FAST_WORK_BUFFER_LENGTH / 1024,
				difficulty,
				finishTime - startTime,
				nonce);

		assertEquals(expectedNonce, nonce.intValue());
		assertTrue(MemoryPoW.verify2(TEST_DATA, FAST_WORK_BUFFER_LENGTH, difficulty, nonce));
	}

	@Test
	public void testFullBufferComputeDifficulty8() {
		int difficulty = 8;
		int expectedNonce = 326;

		long startTime = System.currentTimeMillis();
		int nonce = MemoryPoW.compute2(TEST_DATA, FULL_WORK_BUFFER_LENGTH, difficulty);
		long finishTime = System.currentTimeMillis();

		System.out.printf("Memory-hard PoW (buffer size: %dKB, leading zeros: %d) took %dms, nonce: %d%n", FULL_WORK_BUFFER_LENGTH / 1024,
				difficulty,
				finishTime - startTime,
				nonce);

		assertEquals(expectedNonce, nonce);
		assertTrue(MemoryPoW.verify2(TEST_DATA, FULL_WORK_BUFFER_LENGTH, difficulty, nonce));
	}

	@Test
	public void testLongComputeDifficulty8() throws DataException {
		runLongComputeBenchmark(8);
	}

	@Test
	public void testLongComputeDifficulty9() throws DataException {
		runLongComputeBenchmark(9);
	}

	@Test
	public void testLongComputeDifficulty10() throws DataException {
		runLongComputeBenchmark(10);
	}

	@Test
	public void testLongComputeDifficulty11() throws DataException {
		runLongComputeBenchmark(11);
	}

	@Test
	public void testLongComputeDifficulty12() throws DataException {
		runLongComputeBenchmark(12);
	}

	@Test
	public void testKnownCompute2() {
		int difficulty = 8;
		int expectedNonce = 55;
		int nonce = MemoryPoW.compute2(TEST_DATA, FAST_WORK_BUFFER_LENGTH, difficulty);

		System.out.println(String.format("Difficulty %d, nonce: %d", difficulty, nonce));
		assertEquals(expectedNonce, nonce);

		difficulty = 10;
		expectedNonce = 1356;
		nonce = MemoryPoW.compute2(TEST_DATA, FAST_WORK_BUFFER_LENGTH, difficulty);

		System.out.printf("Difficulty %d, nonce: %d%n", difficulty, nonce);
		assertEquals(expectedNonce, nonce);
	}

	@Test
	public void testKnownVerify() {
		for (int[] fixture : FULL_BUFFER_VERIFY_FIXTURES) {
			int difficulty = fixture[0];
			int expectedNonce = fixture[1];

			assertTrue(String.format("Difficulty %d should verify nonce %d", difficulty, expectedNonce),
					MemoryPoW.verify2(TEST_DATA, FULL_WORK_BUFFER_LENGTH, difficulty, expectedNonce));
		}
	}

	@Test(expected = TimeoutException.class)
	public void testComputeTimeout() throws TimeoutException {
		MemoryPoW.compute2(TEST_DATA, FULL_WORK_BUFFER_LENGTH, 64, 1L);
	}

	private void runLongComputeBenchmark(int difficulty) throws DataException {
		assumeTrue(Boolean.getBoolean(RUN_LONG_MEMPOW_TESTS_PROPERTY));

		Common.useDefaultSettings();
		Random random = new Random();
		byte[] data = new byte[256];
		long[] times = new long[LONG_SAMPLE_SIZE];

		long timesS1 = 0;
		long timesS2 = 0;
		int maxNonce = 0;

		for (int i = 0; i < LONG_SAMPLE_SIZE; ++i) {
			random.nextBytes(data);

			final long startTime = System.currentTimeMillis();
			int nonce = MemoryPoW.compute2(data, FULL_WORK_BUFFER_LENGTH, difficulty);
			times[i] = System.currentTimeMillis() - startTime;

			timesS1 += times[i];
			timesS2 += times[i] * times[i];

			if (nonce > maxNonce)
				maxNonce = nonce;
		}

		double stddev = (double) Math.sqrt((LONG_SAMPLE_SIZE * timesS2 - timesS1 * timesS1) / LONG_STDDEV_DIVISOR);

		System.out.printf("Difficulty: %d, %d timings, mean: %d ms, stddev: %.2f ms, max nonce: %d%n",
				difficulty,
				LONG_SAMPLE_SIZE,
				timesS1 / LONG_SAMPLE_SIZE,
				stddev,
				maxNonce);
	}

}
