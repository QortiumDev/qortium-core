package org.qortium.crosschain;

@SuppressWarnings("serial")
public class ForeignBlockchainException extends Exception {

	public ForeignBlockchainException() {
		super();
	}

	public ForeignBlockchainException(String message) {
		super(message);
	}

	public static class NetworkException extends ForeignBlockchainException {
		private final Integer daemonErrorCode;
		private final transient Object server;

		public NetworkException() {
			super();
			this.daemonErrorCode = null;
			this.server = null;
		}

		public NetworkException(String message) {
			super(message);
			this.daemonErrorCode = null;
			this.server = null;
		}

		public NetworkException(int errorCode, String message) {
			super(message);
			this.daemonErrorCode = errorCode;
			this.server = null;
		}

		public NetworkException(String message, Object server) {
			super(message);
			this.daemonErrorCode = null;
			this.server = server;
		}

		public NetworkException(int errorCode, String message, Object server) {
			super(message);
			this.daemonErrorCode = errorCode;
			this.server = server;
		}

		public Integer getDaemonErrorCode() {
			return this.daemonErrorCode;
		}

		public Object getServer() {
			return this.server;
		}
	}

	public static class NotFoundException extends ForeignBlockchainException {
		public NotFoundException() {
			super();
		}

		public NotFoundException(String message) {
			super(message);
		}
	}

	public static class InsufficientFundsException extends ForeignBlockchainException {
		public InsufficientFundsException() {
			super();
		}

		public InsufficientFundsException(String message) {
			super(message);
		}
	}

	/**
	 * The native wallet coordinator is bound to a different wallet and cannot be safely stopped or
	 * switched right now. Callers must never serve another wallet's cached data in this case; the
	 * message is a stable, machine-readable reason (e.g. "ARRR_WALLET_BUSY") rather than free text.
	 */
	public static class WalletBusyException extends ForeignBlockchainException {
		public WalletBusyException(String message) {
			super(message);
		}
	}

	/**
	 * A specific balance figure (e.g. the verified/spendable amount) was explicitly requested but the
	 * active wallet backend cannot determine it truthfully. Callers must never substitute another
	 * figure (such as the total balance) for the missing one.
	 */
	public static class BalanceUnavailableException extends ForeignBlockchainException {
		public BalanceUnavailableException(String message) {
			super(message);
		}
	}

}
