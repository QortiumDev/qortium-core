package org.qortium.gui;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;

public class NodeTrayFactoryTests {

	@Test
	public void testHeadlessSelectsNoOpWithoutTryingTrayImplementations() {
		AtomicBoolean triedLinux = new AtomicBoolean(false);
		AtomicBoolean triedAwt = new AtomicBoolean(false);

		NodeTray tray = NodeTrayFactory.selectTray(true,
				() -> {
					triedLinux.set(true);
					return new FakeNodeTray(true);
				},
				() -> {
					triedAwt.set(true);
					return new FakeNodeTray(true);
				});

		assertSame(NoOpNodeTray.INSTANCE, tray);
		assertFalse(triedLinux.get());
		assertFalse(triedAwt.get());
	}

	@Test
	public void testHeadlessStatusTraySelectsNoOpWithoutTryingTrayImplementations() {
		AtomicBoolean triedLinux = new AtomicBoolean(false);
		AtomicBoolean triedAwt = new AtomicBoolean(false);

		NodeTray tray = NodeTrayFactory.selectStatusTray(true,
				() -> {
					triedLinux.set(true);
					return new FakeNodeTray(true);
				},
				() -> {
					triedAwt.set(true);
					return new FakeNodeTray(true);
				});

		assertSame(NoOpNodeTray.INSTANCE, tray);
		assertFalse(triedLinux.get());
		assertFalse(triedAwt.get());
	}

	@Test
	public void testLinuxTrayPreferredWhenAvailable() {
		FakeNodeTray linuxTray = new FakeNodeTray(true);
		FakeNodeTray awtTray = new FakeNodeTray(true);

		NodeTray tray = NodeTrayFactory.selectTray(false, () -> linuxTray, () -> awtTray);

		assertSame(linuxTray, tray);
	}

	@Test
	public void testLinuxStatusTrayPreferredWhenAvailable() {
		FakeNodeTray linuxTray = new FakeNodeTray(true);
		FakeNodeTray awtTray = new FakeNodeTray(true);

		NodeTray tray = NodeTrayFactory.selectStatusTray(false, () -> linuxTray, () -> awtTray);

		assertSame(linuxTray, tray);
	}

	@Test
	public void testAwtTrayUsedWhenLinuxTrayUnavailable() {
		FakeNodeTray linuxTray = new FakeNodeTray(false);
		FakeNodeTray awtTray = new FakeNodeTray(true);

		NodeTray tray = NodeTrayFactory.selectTray(false, () -> linuxTray, () -> awtTray);

		assertSame(awtTray, tray);
	}

	@Test
	public void testAwtTrayUsedWhenLinuxTrayThrows() {
		FakeNodeTray awtTray = new FakeNodeTray(true);

		NodeTray tray = NodeTrayFactory.selectTray(false,
				() -> {
					throw new AssertionError("Linux tray failed");
				},
				() -> awtTray);

		assertSame(awtTray, tray);
	}

	@Test
	public void testNoOpUsedWhenAllTraysUnavailable() {
		FakeNodeTray linuxTray = new FakeNodeTray(false);
		FakeNodeTray awtTray = new FakeNodeTray(false);

		NodeTray tray = NodeTrayFactory.selectTray(false, () -> linuxTray, () -> awtTray);

		assertSame(NoOpNodeTray.INSTANCE, tray);
	}

	@Test
	public void testNoOpUsedWhenAllTrayImplementationsThrow() {
		NodeTray tray = NodeTrayFactory.selectTray(false,
				() -> {
					throw new AssertionError("Linux tray failed");
				},
				() -> {
					throw new AssertionError("AWT tray failed");
				});

		assertSame(NoOpNodeTray.INSTANCE, tray);
	}

	private static class FakeNodeTray implements NodeTray {
		private final boolean available;

		private FakeNodeTray(boolean available) {
			this.available = available;
		}

		@Override
		public boolean isAvailable() {
			return this.available;
		}

		@Override
		public void showMessage(String caption, String text, TrayMessageType messageType) {
		}

		@Override
		public void setToolTipText(String text) {
		}

		@Override
		public void setTrayIcon(TrayIconState iconState) {
		}

		@Override
		public void dispose() {
		}
	}
}
