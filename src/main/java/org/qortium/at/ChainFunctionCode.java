package org.qortium.at;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ciyam.at.ExecutionException;
import org.ciyam.at.FunctionData;
import org.ciyam.at.IllegalFunctionCodeException;
import org.ciyam.at.MachineState;
import org.qortium.account.Account;
import org.qortium.crosschain.BitcoinyChainSpecs;
import org.qortium.crypto.Crypto;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.settings.Settings;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chain-specific CIYAM-AT functions.
 * <p>
 * Function codes need to be between 0x0500 and 0x06ff.
 *
 */
public enum ChainFunctionCode {
	/**
	 * Returns length of message data from transaction in A.<br>
	 * <tt>0x0501</tt><br>
	 * If transaction has no 'message', returns -1.
	 */
	GET_MESSAGE_LENGTH_FROM_TX_IN_A(0x0501, 0, true) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			ChainATAPI api = (ChainATAPI) state.getAPI();

			TransactionData transactionData = api.getTransactionFromA(state);

			byte[] messageData = api.getMessageFromTransaction(transactionData);

			if (messageData == null)
				functionData.returnValue = -1L;
			else
				functionData.returnValue = (long) messageData.length;
		}
	},
	/**
	 * Put offset 'message' from transaction in A into B<br>
	 * <tt>0x0502 start-offset</tt><br>
	 * Copies up to 32 bytes of message data, starting at <tt>start-offset</tt> into B.<br>
	 * If transaction has no 'message', or <tt>start-offset</tt> out of bounds, then zero B<br>
	 * Example 'message' could be 256-bit shared secret
	 */
	PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B(0x0502, 1, false) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			ChainATAPI api = (ChainATAPI) state.getAPI();

			// In case something goes wrong, or we don't have enough message data.
			api.zeroB(state);

			if (functionData.value1 < 0 || functionData.value1 > Integer.MAX_VALUE)
				return;

			int startOffset = functionData.value1.intValue();

			TransactionData transactionData = api.getTransactionFromA(state);

			byte[] messageData = api.getMessageFromTransaction(transactionData);

			if (messageData == null || startOffset > messageData.length)
				return;

			/*
			 * Copy up to 32 bytes of message data into B,
			 * retain order but pad with zeros in lower bytes.
			 * 
			 * So a 4-byte message "a b c d" would copy thusly:
			 * a b c d 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
			 */
			int byteCount = Math.min(32, messageData.length - startOffset);
			byte[] bBytes = new byte[32];

			System.arraycopy(messageData, startOffset, bBytes, 0, byteCount);

			api.setB(state, bBytes);
		}
	},
	/**
	 * Sleep AT until a new message arrives after 'tx-timestamp'.<br>
	 * <tt>0x0503 tx-timestamp</tt>
	 */
	SLEEP_UNTIL_MESSAGE(0x0503, 1, false) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			if (functionData.value1 <= 0)
				return;

			long txTimestamp = functionData.value1;

			ChainATAPI api = (ChainATAPI) state.getAPI();
			api.sleepUntilMessageOrHeight(state, txTimestamp, null);
		}
	},
	/**
	 * Sleep AT until a new message arrives, after 'tx-timestamp', or height reached.<br>
	 * <tt>0x0504 tx-timestamp height</tt>
	 */
	SLEEP_UNTIL_MESSAGE_OR_HEIGHT(0x0504, 2, false) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			if (functionData.value1 <= 0)
				return;

			long txTimestamp = functionData.value1;

			if (functionData.value2 <= 0)
				return;

			long sleepUntilHeight = functionData.value2;

			ChainATAPI api = (ChainATAPI) state.getAPI();
			api.sleepUntilMessageOrHeight(state, txTimestamp, sleepUntilHeight);
		}
	},
	/**
	 * Convert address in B to 20-byte value in LSB of B1, and all of B2 & B3.<br>
	 * <tt>0x0510</tt>
	 */
	CONVERT_B_TO_PKH(0x0510, 0, false) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			// Needs to be 'B' sized
			byte[] pkh = new byte[32];

			// Copy PKH part of B to last 20 bytes
			System.arraycopy(getB(state), 32 - 20 - 4, pkh, 32 - 20, 20);

			setB(state, pkh);
		}
	},
	/**
	 * Convert 20-byte value in LSB of B1, and all of B2 & B3 to P2SH.<br>
	 * <tt>0x0511</tt><br>
	 * P2SH stored in lower 25 bytes of B.
	 */
	CONVERT_B_TO_P2SH(0x0511, 0, false) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			String bitcoinNetworkName = Settings.getInstance().getBitcoinyNetworkName(BitcoinyChainSpecs.BITCOIN_CURRENCY_CODE);
			byte addressPrefix = BitcoinyChainSpecs.MAIN.equals(bitcoinNetworkName) ? 0x05 : (byte) 0xc4;

			convertAddressInB(addressPrefix, state);
		}
	},
	/**
	 * Convert 20-byte value in LSB of B1, and all of B2 & B3 to chain address.<br>
	 * <tt>0x0512</tt><br>
	 * Chain address stored in lower 25 bytes of B.
	 */
	CONVERT_B_TO_CHAIN_ADDRESS(0x0512, 0, false) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			convertAddressInB(Crypto.ADDRESS_VERSION, state);
		}
	},
	/**
	 * Returns account level of account in B.<br>
	 * <tt>0x0520</tt><br>
	 * B should contain either chain address or public key,<br>
	 * e.g. as a result of calling function {@link org.ciyam.at.FunctionCode#PUT_ADDRESS_FROM_TX_IN_A_INTO_B}</code>.
	 * <p></p>
	 * Returns account level, or -1 if account unknown.
	 * <p></p>
	 * @see ChainATAPI#getAccountFromB(MachineState)
	 */
	GET_ACCOUNT_LEVEL_FROM_ACCOUNT_IN_B(0x0520, 0, true) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			ChainATAPI api = (ChainATAPI) state.getAPI();
			Account account = api.getAccountFromB(state);

			Integer accountLevel = null;

			if (account != null) {
				try {
					accountLevel = account.getLevel();
				} catch (DataException e) {
					throw new RuntimeException("AT API unable to fetch account level?", e);
				}
			}

			functionData.returnValue = accountLevel != null
					? accountLevel.longValue()
					: -1;
		}
	},
	/**
	 * Returns account's minted block count of account in B.<br>
	 * <tt>0x0521</tt><br>
	 * B should contain either chain address or public key,<br>
	 * e.g. as a result of calling function {@link org.ciyam.at.FunctionCode#PUT_ADDRESS_FROM_TX_IN_A_INTO_B}</code>.
	 * <p></p>
	 * Returns account level, or -1 if account unknown.
	 * <p></p>
	 * @see ChainATAPI#getAccountFromB(MachineState)
	 */
	GET_BLOCKS_MINTED_FROM_ACCOUNT_IN_B(0x0521, 0, true) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			ChainATAPI api = (ChainATAPI) state.getAPI();
			Account account = api.getAccountFromB(state);

			Integer blocksMinted = null;

			if (account != null) {
				try {
					blocksMinted = account.getBlocksMinted();
				} catch (DataException e) {
					throw new RuntimeException("AT API unable to fetch account's minted block count?", e);
				}
			}

			functionData.returnValue = blocksMinted != null
					? blocksMinted.longValue()
					: -1;
		}
	},
	/**
	 * Returns the AT's configured working asset id.<br>
	 * <tt>0x0530</tt>
	 */
	GET_CONFIGURED_ASSET_ID(0x0530, 0, true) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			ChainATAPI api = (ChainATAPI) state.getAPI();
			functionData.returnValue = api.getConfiguredAssetId();
		}
	},
	/**
	 * Returns AT account's current spendable balance for asset id.<br>
	 * <tt>0x0531 asset-id</tt><br>
	 * Returns -1 if asset id is unknown.
	 */
	GET_ASSET_BALANCE(0x0531, 1, true) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			ChainATAPI api = (ChainATAPI) state.getAPI();
			functionData.returnValue = api.getAssetBalance(functionData.value1, state);
		}
	},
	/**
	 * Returns asset id from payment-like transaction in A.<br>
	 * <tt>0x0532</tt><br>
	 * Returns -1 if transaction in A has no asset amount.
	 */
	GET_ASSET_ID_FROM_TX_IN_A(0x0532, 0, true) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			ChainATAPI api = (ChainATAPI) state.getAPI();
			functionData.returnValue = api.getAssetIdFromTransactionInA(state);
		}
	},
	/**
	 * Pays amount of asset id to address in B.<br>
	 * <tt>0x0533 asset-id amount</tt><br>
	 * Returns amount actually paid, 0 if no balance is available, or -1 if the request is invalid.
	 */
	PAY_ASSET_AMOUNT_TO_B(0x0533, 2, true) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			ChainATAPI api = (ChainATAPI) state.getAPI();
			functionData.returnValue = api.payAssetAmountToB(functionData.value1, functionData.value2, state);
		}
	},
	/**
	 * Returns amount of asset id paid to this AT by transaction in A.<br>
	 * <tt>0x0534 asset-id</tt><br>
	 * Returns 0 if this asset was not paid by a supported transaction.<br>
	 * Returns -1 if asset id is unknown or transaction in A has no payment surface.
	 */
	GET_AMOUNT_FROM_TX_IN_A_FOR_ASSET(0x0534, 1, true) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			ChainATAPI api = (ChainATAPI) state.getAPI();
			functionData.returnValue = api.getAmountFromTransactionInAForAsset(functionData.value1, state);
		}
	},
	/**
	 * Returns count of payment entries addressed to this AT by transaction in A.<br>
	 * <tt>0x0535</tt>
	 */
	GET_PAYMENT_COUNT_FROM_TX_IN_A(0x0535, 0, true) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
			ChainATAPI api = (ChainATAPI) state.getAPI();
			functionData.returnValue = api.getPaymentCountFromTransactionInA(state);
		}
	},
	/** Returns the persistent map value for keys in A1/A2 from the AT identified by B, or self if B is zero. */
	GET_MAP_VALUE_KEYS_IN_A(0x0600, 0, true) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) {
			ChainATAPI api = (ChainATAPI) state.getAPI();
			functionData.returnValue = api.getMapValue(state);
		}
	},
	/** Stores A4 in the calling AT's persistent map under keys A1/A2; zero deletes the entry. */
	SET_MAP_VALUE_KEYS_IN_A(0x0601, 0, false) {
		@Override
		protected void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) {
			ChainATAPI api = (ChainATAPI) state.getAPI();
			api.setMapValue(state);
		}
	};

	public final short value;
	public final int paramCount;
	public final boolean returnsValue;

	private static final Logger LOGGER = LogManager.getLogger(ChainFunctionCode.class);

	private static final Map<Short, ChainFunctionCode> map = Arrays.stream(ChainFunctionCode.values())
			.collect(Collectors.toMap(functionCode -> functionCode.value, functionCode -> functionCode));

	private ChainFunctionCode(int value, int paramCount, boolean returnsValue) {
		this.value = (short) value;
		this.paramCount = paramCount;
		this.returnsValue = returnsValue;
	}

	public static ChainFunctionCode valueOf(int value) {
		return map.get((short) value);
	}

	public boolean isMapFunction() {
		return this == GET_MAP_VALUE_KEYS_IN_A || this == SET_MAP_VALUE_KEYS_IN_A;
	}

	public void preExecuteCheck(int paramCount, boolean returnValueExpected, short rawFunctionCode) throws IllegalFunctionCodeException {
		if (paramCount != this.paramCount)
			throw new IllegalFunctionCodeException(
					"Passed paramCount (" + paramCount + ") does not match function's required paramCount (" + this.paramCount + ")");

		if (returnValueExpected != this.returnsValue)
			throw new IllegalFunctionCodeException(
					"Passed returnValueExpected (" + returnValueExpected + ") does not match function's return signature (" + this.returnsValue + ")");
	}

	/**
	 * Execute Function
	 * <p>
	 * Can modify various fields of <tt>state</tt>, including <tt>programCounter</tt>.
	 * <p>
	 * Throws a subclass of <tt>ExecutionException</tt> on error, e.g. <tt>InvalidAddressException</tt>.
	 *
	 * @param functionData
	 * @param state
	 * @throws ExecutionException
	 */
	public void execute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
		// Check passed functionData against requirements of this function
		preExecuteCheck(functionData.paramCount, functionData.returnValueExpected, rawFunctionCode);

		if (functionData.paramCount >= 1 && functionData.value1 == null)
			throw new IllegalFunctionCodeException("Passed value1 is null but function has paramCount of (" + this.paramCount + ")");

		if (functionData.paramCount == 2 && functionData.value2 == null)
			throw new IllegalFunctionCodeException("Passed value2 is null but function has paramCount of (" + this.paramCount + ")");

		LOGGER.debug(() -> String.format("Function \"%s\"", this.name()));

		postCheckExecute(functionData, state, rawFunctionCode);
	}

	/** Actually execute function */
	protected abstract void postCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException;

	private static void convertAddressInB(byte addressPrefix, MachineState state) {
		byte[] addressNoChecksum = new byte[1 + 20];
		addressNoChecksum[0] = addressPrefix;
		System.arraycopy(getB(state), 0, addressNoChecksum, 1, 20);

		byte[] checksum = Crypto.doubleDigest(addressNoChecksum);

		// Needs to be 'B' sized
		byte[] address = new byte[32];
		System.arraycopy(addressNoChecksum, 0, address, 32 - 1 - 20 - 4, addressNoChecksum.length);
		System.arraycopy(checksum, 0, address, 32 - 4, 4);

		setB(state, address);
	}

	private static byte[] getB(MachineState state) {
		ChainATAPI api = (ChainATAPI) state.getAPI();
		return api.getB(state);
	}

	private static void setB(MachineState state, byte[] bBytes) {
		ChainATAPI api = (ChainATAPI) state.getAPI();
		api.setB(state, bBytes);
	}

}
