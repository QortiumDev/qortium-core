package org.qortium.crosschain;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Deterministic coverage for the ElectrumX multi-server height consensus helpers (no network). */
public class ElectrumXHeightConsensusTests {

	@Test
	public void testMedianOfThreeIgnoresHighOutlier() {
		// One server lying high must not move the result.
		List<Integer> heights = Arrays.asList(800000, 800001, 800500);
		assertEquals(800001, ElectrumX.medianHeight(heights));
	}

	@Test
	public void testMedianOfThreeIgnoresLowOutlier() {
		// One server lying low must not move the result.
		List<Integer> heights = Arrays.asList(799500, 800000, 800001);
		assertEquals(800000, ElectrumX.medianHeight(heights));
	}

	@Test
	public void testMedianIsOrderIndependent() {
		List<Integer> heights = Arrays.asList(800500, 800000, 800001);
		assertEquals(800001, ElectrumX.medianHeight(heights));
	}

	@Test
	public void testMedianOfTwoPrefersLower() {
		// With only two readings we cannot attribute blame; the lower (refund-safe) height is chosen.
		assertEquals(800000, ElectrumX.medianHeight(Arrays.asList(800000, 800050)));
		assertEquals(800000, ElectrumX.medianHeight(Arrays.asList(800050, 800000)));
	}

	@Test
	public void testMedianOfAgreeingReadings() {
		assertEquals(800000, ElectrumX.medianHeight(Arrays.asList(800000, 800000, 800000)));
	}

	@Test
	public void testMedianSingleReading() {
		assertEquals(800000, ElectrumX.medianHeight(Collections.singletonList(800000)));
	}

	@Test
	public void testOutlierDetectionRespectsTolerance() {
		// Within tolerance (normal propagation lag) is not an outlier.
		assertFalse(ElectrumX.isHeightOutlier(800001, 800001, 2));
		assertFalse(ElectrumX.isHeightOutlier(800003, 800001, 2));
		assertFalse(ElectrumX.isHeightOutlier(799999, 800001, 2));

		// Beyond tolerance in either direction is an outlier.
		assertTrue(ElectrumX.isHeightOutlier(800004, 800001, 2));
		assertTrue(ElectrumX.isHeightOutlier(800500, 800001, 2));
		assertTrue(ElectrumX.isHeightOutlier(799000, 800001, 2));
	}
}
