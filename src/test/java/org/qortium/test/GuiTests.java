package org.qortium.test;

import org.junit.Before;
import org.junit.Assume;
import org.junit.Test;
import org.qortium.gui.SplashFrame;
import org.qortium.gui.SysTray;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import java.awt.GraphicsEnvironment;
import java.awt.TrayIcon.MessageType;

public class GuiTests {

	private static final String RUN_GUI_DISPLAY_TESTS_PROPERTY = "qortium.runGuiDisplayTests";

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSplashFrameHeadlessNoOp() {
		Assume.assumeTrue(GraphicsEnvironment.isHeadless());

		SplashFrame splashFrame = SplashFrame.getInstance();
		splashFrame.updateStatus("Testing headless splash status");
		splashFrame.setVisible(true);
		splashFrame.setVisible(false);
		splashFrame.dispose();
	}

	@Test
	public void testSysTrayHeadlessNoOp() {
		Assume.assumeTrue(GraphicsEnvironment.isHeadless());

		SysTray sysTray = SysTray.getInstance();
		sysTray.showMessage("Testing...", "Headless tray notifications should be no-op", MessageType.INFO);
		sysTray.setToolTipText("Testing headless tray tooltip");
		sysTray.setTrayIcon(1);
		sysTray.setTrayIcon(2);
		sysTray.setTrayIcon(3);
		sysTray.setTrayIcon(4);
		sysTray.dispose();
	}

	@Test
	public void testSplashFrame() throws InterruptedException {
		assumeDisplayAvailable();

		SplashFrame splashFrame = SplashFrame.getInstance();

		Thread.sleep(2000L);

		splashFrame.dispose();
	}

	@Test
	public void testSysTray() throws InterruptedException {
		assumeDisplayAvailable();

		SysTray.getInstance();

		SysTray.getInstance().showMessage("Testing...", "Tray icon should disappear in 10 seconds", MessageType.INFO);

		Thread.sleep(10_000L);

		SysTray.getInstance().dispose();
	}

	private void assumeDisplayAvailable() {
		Assume.assumeTrue(Boolean.getBoolean(RUN_GUI_DISPLAY_TESTS_PROPERTY));
		Assume.assumeTrue(!GraphicsEnvironment.isHeadless());
	}

}
