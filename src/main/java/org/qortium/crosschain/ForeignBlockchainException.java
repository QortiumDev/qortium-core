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

	/**
	 * The wallet exists and is owned by the caller but cannot spend right now: the wallet backend is
	 * disabled, the wallet is not initialized/synchronized, its verified (spendable) balance is not
	 * known, or the native lane is degraded. Nothing was broadcast. Like
	 * {@link WalletBusyException}, the message is a stable, machine-readable reason (e.g.
	 * "ARRR_WALLET_DISABLED", "ARRR_VERIFIED_BALANCE_UNKNOWN") rather than free text, so it can be
	 * returned to API callers without leaking request contents.
	 */
	public static class WalletNotReadyException extends ForeignBlockchainException {
		public WalletNotReadyException(String message) {
			super(message);
		}
	}

	/**
	 * The recipient address passed Core's encoding checks but the native wallet's semantic
	 * validation (curve point, diversifier, network) rejected it, or the native send refused it.
	 * Nothing was broadcast. The message is a stable reason token.
	 */
	public static class InvalidRecipientException extends ForeignBlockchainException {
		public InvalidRecipientException(String message) {
			super(message);
		}
	}

	/**
	 * The native send was started but Core cannot tell whether the transaction was broadcast:
	 * the native call threw, timed out, or returned something that is neither a txid nor an
	 * explicit error. The payment MAY have gone out. Callers must not retry until the wallet's
	 * history has been checked; the message is a stable reason token.
	 */
	public static class SendOutcomeUnknownException extends ForeignBlockchainException {
		public SendOutcomeUnknownException(String message) {
			super(message);
		}
	}

}
