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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

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

	/**
	 * P-CORE-53 regression coverage: once dispose() has run, the singleton must be reset (so a
	 * later getInstance() doesn't hand back a permanently-inert stale reference by accident) but
	 * nothing may ever construct a second *live* splash after the first has been dismissed -
	 * calling getInstance()/updateStatus()/setVisible() again must all remain safe no-ops. This
	 * can only be asserted black-box here (headless has no real dialog either way), but a real
	 * display's equivalent is exercised by testSplashFrame() below via isDialogDisplayable().
	 */
	@Test
	public void testSplashFrameSingletonResetAndNoReshowAfterDispose() {
		Assume.assumeTrue(GraphicsEnvironment.isHeadless());

		SplashFrame first = SplashFrame.getInstance();
		first.updateStatus("Before dispose");
		first.dispose();

		// Repeat calls after dispose (including a stray late StartupStatus-style update) must
		// not throw and must not resurrect the dialog.
		first.dispose();
		first.updateStatus("After dispose should be a no-op");
		first.setVisible(true);
		assertFalse(first.isDialogDisplayable());

		SplashFrame second = SplashFrame.getInstance();
		assertNotSame("dispose() must reset the singleton so a later getInstance() call rebuilds cleanly",
				first, second);

		// The rebuilt instance must still be inert (dismissed process-wide) rather than showing
		// a fresh splash after the node is already running.
		second.updateStatus("Should also be a no-op");
		second.setVisible(true);
		assertFalse(second.isDialogDisplayable());
		second.dispose();
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
		assertTrue("Splash dialog should be realized on a real display", splashFrame.isDialogDisplayable());

		Thread.sleep(2000L);

		splashFrame.dispose();

		// dispose() marshals the actual teardown onto the EDT; it should already be gone by the
		// time dispose() returns (see SplashFrame.disposeDialogOnEdt), well before the ~3s
		// belt-and-braces watchdog would otherwise force it again.
		assertFalse("Splash dialog must not still be displayable immediately after dispose()",
				splashFrame.isDialogDisplayable());
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
