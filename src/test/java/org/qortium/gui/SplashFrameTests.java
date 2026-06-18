package org.qortium.gui;

import org.junit.Test;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Dimension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SplashFrameTests {

	@Test
	public void testSplashPanelUsesHalfSizeLogoAndShorterHeight() {
		SplashFrame.SplashPanel splashPanel = new SplashFrame.SplashPanel(SplashTheme.DARK);

		assertEquals(new Dimension(500, 336), splashPanel.getPreferredSize());

		Component logoComponent = splashPanel.getComponent(0);
		assertTrue(logoComponent instanceof JLabel);

		ImageIcon logoIcon = (ImageIcon) ((JLabel) logoComponent).getIcon();
		assertEquals(250, logoIcon.getIconWidth());
		assertEquals(250, logoIcon.getIconHeight());
	}
}
