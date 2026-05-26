package org.qortium.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LinuxStatusNotifierTrayTests {

	@Test
	public void testMultiLineTooltipUsesStatusAsTitleAndBuildVersionAsDescription() {
		String tooltip = "Minting - 0 connections - height 13\nBuild version: qortium-1.0.0-test";

		assertEquals("Qortium - Minting - 0 connections - height 13",
				LinuxStatusNotifierTray.getToolTipTitle(tooltip));
		assertEquals("Build version: qortium-1.0.0-test",
				LinuxStatusNotifierTray.getToolTipDescription(tooltip));
	}

	@Test
	public void testSingleLineTooltipUsesStatusAsTitleWithEmptyDescription() {
		String tooltip = "Connecting - 0 connections - height 1";

		assertEquals("Qortium - Connecting - 0 connections - height 1",
				LinuxStatusNotifierTray.getToolTipTitle(tooltip));
		assertEquals("", LinuxStatusNotifierTray.getToolTipDescription(tooltip));
	}

	@Test
	public void testBlankTooltipFallsBackToApplicationTitle() {
		assertEquals("Qortium", LinuxStatusNotifierTray.getToolTipTitle(""));
		assertEquals("", LinuxStatusNotifierTray.getToolTipDescription(""));
		assertEquals("Qortium", LinuxStatusNotifierTray.getToolTipTitle(null));
		assertEquals("", LinuxStatusNotifierTray.getToolTipDescription(null));
	}
}
