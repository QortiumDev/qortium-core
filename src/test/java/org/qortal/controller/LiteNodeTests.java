package org.qortal.controller;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiteNodeTests {

	@Test
	public void testLiteDataCapabilityRequiresSupportedNumericVersion() {
		assertFalse(LiteNode.isSupportedLiteDataCapability(null));
		assertFalse(LiteNode.isSupportedLiteDataCapability("1"));
		assertFalse(LiteNode.isSupportedLiteDataCapability(0));

		assertTrue(LiteNode.isSupportedLiteDataCapability(1));
		assertTrue(LiteNode.isSupportedLiteDataCapability(2L));
	}

}
