package org.qortium.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.api.ApiRequest;
import org.qortium.gui.NodeTray;
import org.qortium.gui.NodeTrayFactory;
import org.qortium.gui.TrayIconState;

import java.util.Collections;

public final class RestartTrayAnimator implements AutoCloseable {

	private static final Logger LOGGER = LogManager.getLogger(RestartTrayAnimator.class);
	private static final long ANIMATION_INTERVAL_MS = 700L;
	private static final long API_POLL_INTERVAL_MS = 1_000L;

	private final Object animationLock = new Object();
	private final NodeTray nodeTray;
	private volatile Thread animationThread;
	private boolean animationRunning;

	private RestartTrayAnimator(NodeTray nodeTray) {
		this.nodeTray = nodeTray;
	}

	public static RestartTrayAnimator start(String tooltip) {
		NodeTray nodeTray = NodeTrayFactory.createStatusTray();
		RestartTrayAnimator animator = new RestartTrayAnimator(nodeTray);
		animator.startAnimation(tooltip);
		return animator;
	}

	private void startAnimation(String tooltip) {
		synchronized (this.animationLock) {
			if (this.animationRunning)
				return;

			this.animationRunning = true;
			this.nodeTray.setToolTipText(tooltip);

			this.animationThread = new Thread(this::runAnimation, "Restart tray animation");
			this.animationThread.setDaemon(true);
			this.animationThread.start();
		}
	}

	public void waitForNodeApi(int apiPort, long timeoutMillis) {
		long deadline = System.currentTimeMillis() + timeoutMillis;

		while (System.currentTimeMillis() < deadline) {
			if (isNodeApiReachable(apiPort))
				return;

			try {
				Thread.sleep(API_POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}

		LOGGER.debug("Timed out waiting for restarted node API on port {}", apiPort);
	}

	private boolean isNodeApiReachable(int apiPort) {
		try {
			String response = ApiRequest.perform("http://localhost:" + apiPort + "/admin/status", Collections.emptyMap(), Collections.emptyMap());
			return response != null;
		} catch (RuntimeException e) {
			return false;
		}
	}

	@Override
	public void close() {
		Thread threadToStop = null;

		synchronized (this.animationLock) {
			if (this.animationRunning) {
				this.animationRunning = false;
				threadToStop = this.animationThread;
				this.animationThread = null;
			}
		}

		if (threadToStop != null) {
			threadToStop.interrupt();
			try {
				threadToStop.join(1_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.debug("Interrupted while waiting for tray animation thread to stop: {}", e.getMessage());
			}
		}

		this.nodeTray.dispose();
	}

	private void runAnimation() {
		TrayIconState state = TrayIconState.SYNCHRONIZING;

		while (isAnimationRunning()) {
			try {
				this.nodeTray.setTrayIcon(state);
				state = (state == TrayIconState.SYNCHRONIZING) ? TrayIconState.MINTING : TrayIconState.SYNCHRONIZING;
				Thread.sleep(ANIMATION_INTERVAL_MS);
			} catch (InterruptedException e) {
				break;
			}
		}

		synchronized (this.animationLock) {
			this.animationThread = null;
		}
	}

	private boolean isAnimationRunning() {
		synchronized (this.animationLock) {
			return this.animationRunning;
		}
	}
}
