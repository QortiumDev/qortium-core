package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.ciyam.at.API;
import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.ciyam.at.Timestamp;
import org.qortium.account.Account;
import org.qortium.api.resource.CrossChainUtils;
import org.qortium.at.ChainFunctionCode;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATData;
import org.qortium.data.at.ATStateData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.transaction.MessageTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Base58;
import org.qortium.utils.BitTwiddling;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

import static org.ciyam.at.OpCode.calcOffset;

public class BitcoinyForeignForeignACCTv1 implements ACCT {

	public static final String NAME = BitcoinyForeignForeignACCTv1.class.getSimpleName();
	public static final byte[] CODE_BYTES_HASH = HashCode.fromString("6dc141b66fc86093b46179748769c5b5fbc53840083088870d04fef204ace3d6").asBytes();

	public static final int SECRET_LENGTH = 32;
	public static final int PUBLIC_KEY_HASH_LENGTH = 20;
	public static final int PADDED_PUBLIC_KEY_HASH_LENGTH = 32;
	public static final int CHAIN_ID_REFERENCE_LENGTH = Bip122ChainId.REFERENCE_BYTE_LENGTH;

	public static final int RESERVE_MESSAGE_LENGTH = 2 * PADDED_PUBLIC_KEY_HASH_LENGTH;
	public static final int MAKER_HTLC_MESSAGE_LENGTH = 8;
	public static final int TAKER_HTLC_MESSAGE_LENGTH = 8;
	public static final int SECRET_REVEAL_MESSAGE_LENGTH = SECRET_LENGTH;
	public static final int CANCEL_MESSAGE_LENGTH = 32;

	public static final int REFUND_LOCKTIME_SAFETY_MARGIN_MINUTES = 30;

	private static final Layout LAYOUT = new Layout();

	private static final int MODE_VALUE_OFFSET = LAYOUT.addrMode;
	public static final int MODE_BYTE_OFFSET = MachineState.HEADER_LENGTH + MODE_VALUE_OFFSET * MachineState.VALUE_SIZE;

	private static BitcoinyForeignForeignACCTv1 instance;

	private BitcoinyForeignForeignACCTv1() {
	}

	public static synchronized BitcoinyForeignForeignACCTv1 getInstance() {
		if (instance == null)
			instance = new BitcoinyForeignForeignACCTv1();

		return instance;
	}

	@Override
	public byte[] getCodeBytesHash() {
		return CODE_BYTES_HASH;
	}

	@Override
	public int getModeByteOffset() {
		return MODE_BYTE_OFFSET;
	}

	public static byte[] buildTradeAT(ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain, String creatorTradeAddress,
			byte[] makerOfferedForeignPublicKeyHash, byte[] makerRequestedForeignPublicKeyHash, byte[] hashOfSecret,
			long offeredForeignAmount, long requestedForeignAmount, int tradeTimeout) {
		if (!isSupportedBitcoinyPair(offeredForeignBlockchain, requestedForeignBlockchain))
			throw new IllegalArgumentException("Foreign/foreign trades require two supported Bitcoiny blockchains");

		requireHash160(makerOfferedForeignPublicKeyHash, "maker offered-chain public key hash");
		requireHash160(makerRequestedForeignPublicKeyHash, "maker requested-chain public key hash");
		requireHash160(hashOfSecret, "hash of secret");
		requirePositive(offeredForeignAmount, "offered foreign amount");
		requirePositive(requestedForeignAmount, "requested foreign amount");
		requirePositive(tradeTimeout, "trade timeout");
		if (tradeTimeout <= REFUND_LOCKTIME_SAFETY_MARGIN_MINUTES)
			throw new IllegalArgumentException("trade timeout must be greater than refund locktime safety margin");

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(LAYOUT.valueCount * MachineState.VALUE_SIZE);

		putBytes(dataByteBuffer, LAYOUT.addrCreatorTradeAddress1, Base58.decode(creatorTradeAddress));
		putBytes(dataByteBuffer, LAYOUT.addrCreatorOfferedForeignPKH, makerOfferedForeignPublicKeyHash);
		putBytes(dataByteBuffer, LAYOUT.addrCreatorRequestedForeignPKH, makerRequestedForeignPublicKeyHash);
		putBytes(dataByteBuffer, LAYOUT.addrHashOfSecretA, hashOfSecret);
		putLong(dataByteBuffer, LAYOUT.addrOfferedForeignAmount, offeredForeignAmount);
		putLong(dataByteBuffer, LAYOUT.addrRequestedForeignAmount, requestedForeignAmount);
		putLong(dataByteBuffer, LAYOUT.addrTradeTimeout, tradeTimeout);
		putLong(dataByteBuffer, LAYOUT.addrLocktimeSafetyMarginSeconds, REFUND_LOCKTIME_SAFETY_MARGIN_MINUTES * 60L);
		putLong(dataByteBuffer, LAYOUT.addrTakerRefundTimeoutMinutes, tradeTimeout - REFUND_LOCKTIME_SAFETY_MARGIN_MINUTES);
		putLong(dataByteBuffer, LAYOUT.addrMessageTxnType, API.ATTransactionType.MESSAGE.value);
		putLong(dataByteBuffer, LAYOUT.addrExpectedReserveMessageLength, RESERVE_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedMakerHtlcMessageLength, MAKER_HTLC_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedTakerHtlcMessageLength, TAKER_HTLC_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedSecretRevealMessageLength, SECRET_REVEAL_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedCancelMessageLength, CANCEL_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrReserveMessageRequestedPKHOffset, PADDED_PUBLIC_KEY_HASH_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrCreatorAddressPointer, LAYOUT.addrCreatorAddress1);
		putLong(dataByteBuffer, LAYOUT.addrMessageSenderPointer, LAYOUT.addrMessageSender1);
		putLong(dataByteBuffer, LAYOUT.addrTakerAddressPointer, LAYOUT.addrTakerAddress1);
		putLong(dataByteBuffer, LAYOUT.addrTempPointer, LAYOUT.addrTempData1);
		putLong(dataByteBuffer, LAYOUT.addrTempMessageDataPointer, LAYOUT.addrTempMessageData);
		putLong(dataByteBuffer, LAYOUT.addrTempMessageDataLength, SECRET_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrZero, 0);
		putLong(dataByteBuffer, LAYOUT.addrOne, 1);
		putLong(dataByteBuffer, LAYOUT.addrReservedModeValue, AcctMode.RESERVED.value);
		putLong(dataByteBuffer, LAYOUT.addrForeignLockedModeValue, AcctMode.FOREIGN_LOCKED.value);
		putLong(dataByteBuffer, LAYOUT.addrTradingModeValue, AcctMode.TRADING.value);
		putRawBytes(dataByteBuffer, LAYOUT.addrOfferedForeignBlockchainChainId, offeredForeignBlockchain.getActiveChainIdReferenceBytes());
		putRawBytes(dataByteBuffer, LAYOUT.addrRequestedForeignBlockchainChainId, requestedForeignBlockchain.getActiveChainIdReferenceBytes());

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(4096);

		Integer labelLoop = null;
		Integer labelStop = null;
		Integer labelCheckOfferingMessages = null;
		Integer labelCheckReservedMessages = null;
		Integer labelCheckForeignLockedMessages = null;
		Integer labelCheckTradingMessages = null;
		Integer labelCheckReserve = null;
		Integer labelCheckMakerHtlc = null;
		Integer labelCheckTakerHtlc = null;
		Integer labelCheckSecretReveal = null;
		Integer labelCheckCancel = null;
		Integer labelNotRefund = null;
		Integer labelRefund = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, LAYOUT.addrLastTxnTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_CREATOR_INTO_B));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrCreatorAddressPointer));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				labelLoop = codeByteBuffer.position();
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_BLOCK_TIMESTAMP, LAYOUT.addrBlockTimestamp));
				emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrMode, LAYOUT.addrTradingModeValue, labelNotRefund);
				codeByteBuffer.put(OpCode.BLT_DAT.compile(LAYOUT.addrBlockTimestamp, LAYOUT.addrRefundTimestamp,
						calcOffset(codeByteBuffer, labelNotRefund)));
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRefund == null ? 0 : labelRefund));

				labelNotRefund = codeByteBuffer.position();
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, LAYOUT.addrLastTxnTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, LAYOUT.addrResult));
				emitFarJumpIfNonZero(codeByteBuffer, LAYOUT.addrResult, labelStop);

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, LAYOUT.addrLastTxnTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, LAYOUT.addrTxnType));
				emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTxnType, LAYOUT.addrMessageTxnType, labelLoop);

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_PAYMENT_COUNT_FROM_TX_IN_A.value, LAYOUT.addrTempPaymentCount));
				emitRefundAnyPaymentIfNonZero(codeByteBuffer, labelLoop);

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_MESSAGE_LENGTH_FROM_TX_IN_A.value, LAYOUT.addrMessageLength));
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMode, LAYOUT.addrZero, labelCheckOfferingMessages);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMode, LAYOUT.addrReservedModeValue, labelCheckReservedMessages);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMode, LAYOUT.addrForeignLockedModeValue, labelCheckForeignLockedMessages);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMode, LAYOUT.addrTradingModeValue, labelCheckTradingMessages);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckOfferingMessages = codeByteBuffer.position();
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedReserveMessageLength, labelCheckReserve);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedCancelMessageLength, labelCheckCancel);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckReservedMessages = codeByteBuffer.position();
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedMakerHtlcMessageLength, labelCheckMakerHtlc);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedCancelMessageLength, labelCheckCancel);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckForeignLockedMessages = codeByteBuffer.position();
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedTakerHtlcMessageLength, labelCheckTakerHtlc);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedCancelMessageLength, labelCheckCancel);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckTradingMessages = codeByteBuffer.position();
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedSecretRevealMessageLength, labelCheckSecretReveal);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckReserve = codeByteBuffer.position();
				emitProcessReserveMessage(codeByteBuffer, labelLoop);

				labelCheckMakerHtlc = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorTradeAddress1, labelLoop);
				emitProcessMakerHtlcMessage(codeByteBuffer, labelLoop);

				labelCheckTakerHtlc = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrTakerAddress1, labelLoop);
				emitProcessTakerHtlcMessage(codeByteBuffer, labelLoop);

				labelCheckSecretReveal = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorTradeAddress1, labelLoop);
				emitProcessSecretRevealMessage(codeByteBuffer, labelLoop);

				labelCheckCancel = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorAddress1, LAYOUT.addrCreatorTradeAddress1, labelLoop);
				codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.CANCELLED.value));
				codeByteBuffer.put(OpCode.FIN_IMD.compile());

				labelRefund = codeByteBuffer.position();
				emitProcessRefund(codeByteBuffer);

				labelStop = codeByteBuffer.position();
				codeByteBuffer.put(OpCode.STP_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile Bitcoiny foreign/foreign ACCT?", e);
			}
		}

		codeByteBuffer.flip();
		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		assert Arrays.equals(Crypto.digest(codeBytes), BitcoinyForeignForeignACCTv1.CODE_BYTES_HASH)
				: String.format("BitcoinyForeignForeignACCTv1.CODE_BYTES_HASH mismatch: expected %s, actual %s",
						HashCode.fromBytes(CODE_BYTES_HASH), HashCode.fromBytes(Crypto.digest(codeBytes)));

		return MachineState.toCreationBytes((short) 2, codeBytes, dataByteBuffer.array(), (short) 0, (short) 0, 0L);
	}

	private static void emitProcessReserveMessage(ByteBuffer codeByteBuffer, Integer rejectLabel) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTakerAddressPointer));

		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrPartnerOfferedForeignPKH));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));

		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value,
				LAYOUT.addrReserveMessageRequestedPKHOffset));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrPartnerRequestedForeignPKH));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));

		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.RESERVED.value));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
	}

	private static void emitProcessMakerHtlcMessage(ByteBuffer codeByteBuffer, Integer rejectLabel) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrZero));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrLockTimeA));
		emitFarJumpIfLessOrEqual(codeByteBuffer, LAYOUT.addrLockTimeA, LAYOUT.addrZero, rejectLabel);
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.FOREIGN_LOCKED.value));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
	}

	private static void emitProcessTakerHtlcMessage(ByteBuffer codeByteBuffer, Integer rejectLabel) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrZero));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrTempLocktime));
		emitFarJumpIfLessOrEqual(codeByteBuffer, LAYOUT.addrTempLocktime, LAYOUT.addrZero, rejectLabel);

		codeByteBuffer.put(OpCode.SET_DAT.compile(LAYOUT.addrTempLocktimeWithMargin, LAYOUT.addrTempLocktime));
		codeByteBuffer.put(OpCode.ADD_DAT.compile(LAYOUT.addrTempLocktimeWithMargin, LAYOUT.addrLocktimeSafetyMarginSeconds));
		emitFarJumpIfGreaterOrEqual(codeByteBuffer, LAYOUT.addrTempLocktimeWithMargin, LAYOUT.addrLockTimeA, rejectLabel);

		codeByteBuffer.put(OpCode.SET_DAT.compile(LAYOUT.addrLockTimeB, LAYOUT.addrTempLocktime));
		codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, LAYOUT.addrRefundTimestamp,
				LAYOUT.addrLastTxnTimestamp, LAYOUT.addrTakerRefundTimeoutMinutes));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.TRADING.value));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
	}

	private static void emitProcessSecretRevealMessage(ByteBuffer codeByteBuffer, Integer rejectLabel) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempMessageDataPointer));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrHashOfSecretA));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, LAYOUT.addrTempPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.CHECK_HASH160_WITH_B, LAYOUT.addrResult,
				LAYOUT.addrTempMessageDataPointer, LAYOUT.addrTempMessageDataLength));
		emitFarJumpIfZero(codeByteBuffer, LAYOUT.addrResult, rejectLabel);

		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.REDEEMED.value));
		codeByteBuffer.put(OpCode.FIN_IMD.compile());
	}

	private static void emitProcessRefund(ByteBuffer codeByteBuffer) throws CompilationException {
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.REFUNDED.value));
		codeByteBuffer.put(OpCode.FIN_IMD.compile());
	}

	private static void emitRefundAnyPaymentIfNonZero(ByteBuffer codeByteBuffer, Integer rejectLabel) throws CompilationException {
		Integer labelNoPayment = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			emitFarJumpIfZero(codeByteBuffer, LAYOUT.addrTempPaymentCount, labelNoPayment);
			codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_ASSET_ID_FROM_TX_IN_A.value, LAYOUT.addrTempPaymentAssetId));
			codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_AMOUNT_FROM_TX_IN_A, LAYOUT.addrTempAmount));
			codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, LAYOUT.addrResult,
					LAYOUT.addrTempPaymentAssetId, LAYOUT.addrTempAmount));
			codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
			labelNoPayment = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	@Override
	public CrossChainTradeData populateTradeData(Repository repository, ATData atData) throws DataException {
		ATStateData atStateData = repository.getATRepository().getLatestATState(atData.getATAddress());
		return populateTradeData(repository, atData.getCreatorPublicKey(), atData.getCreation(), atStateData, OptionalLong.empty());
	}

	@Override
	public List<CrossChainTradeData> populateTradeDataList(Repository repository, List<ATData> atDataList) throws DataException {
		return CrossChainUtils.populateTradeDataList(repository, this, atDataList);
	}

	@Override
	public CrossChainTradeData populateTradeData(Repository repository, ATStateData atStateData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(atStateData.getATAddress());
		return populateTradeData(repository, atData.getCreatorPublicKey(), atData.getCreation(), atStateData, OptionalLong.empty());
	}

	@Override
	public CrossChainTradeData populateTradeData(Repository repository, byte[] creatorPublicKey, long creationTimestamp,
			ATStateData atStateData, OptionalLong optionalBalance) throws DataException {
		String atAddress = atStateData.getATAddress();
		CrossChainTradeData tradeData = new CrossChainTradeData();
		tradeData.atAddress = atAddress;
		tradeData.acctName = NAME;
		tradeData.tradeDirection = TradeDirection.SELL_FOREIGN_FOR_FOREIGN;
		tradeData.creatorAddress = Crypto.toAddress(creatorPublicKey);
		tradeData.creationTimestamp = creationTimestamp;

		ATData atData = repository.getATRepository().fromATAddress(atAddress);
		if (atData == null)
			throw new DataException("Unable to fetch AT data for " + atAddress);

		tradeData.localAssetId = atData.getAssetId();
		Account atAccount = new Account(repository, atAddress);
		tradeData.localBalance = optionalBalance.isPresent() ? optionalBalance.getAsLong() : atAccount.getConfirmedBalance(tradeData.localAssetId);

		ByteBuffer dataByteBuffer = ByteBuffer.wrap(atStateData.getStateData());

		tradeData.creatorTradeAddress = Base58.encode(readBytes(dataByteBuffer, LAYOUT.addrCreatorTradeAddress1, 25));
		tradeData.creatorOfferedForeignPKH = readBytes(dataByteBuffer, LAYOUT.addrCreatorOfferedForeignPKH, PUBLIC_KEY_HASH_LENGTH);
		tradeData.creatorRequestedForeignPKH = readBytes(dataByteBuffer, LAYOUT.addrCreatorRequestedForeignPKH, PUBLIC_KEY_HASH_LENGTH);
		tradeData.hashOfSecretA = readBytes(dataByteBuffer, LAYOUT.addrHashOfSecretA, PUBLIC_KEY_HASH_LENGTH);
		tradeData.offeredForeignAmount = getLong(dataByteBuffer, LAYOUT.addrOfferedForeignAmount);
		tradeData.requestedForeignAmount = getLong(dataByteBuffer, LAYOUT.addrRequestedForeignAmount);
		tradeData.tradeTimeout = (int) getLong(dataByteBuffer, LAYOUT.addrTradeTimeout);

		long modeValue = getLong(dataByteBuffer, LAYOUT.addrMode);
		tradeData.mode = AcctMode.valueOf((int) (modeValue & 0xffL));
		if (tradeData.mode == null)
			tradeData.mode = AcctMode.OFFERING;

		if (tradeData.mode != AcctMode.OFFERING) {
			tradeData.partnerAddress = Base58.encode(readBytes(dataByteBuffer, LAYOUT.addrTakerAddress1, 25));
			tradeData.partnerOfferedForeignPKH = readBytes(dataByteBuffer, LAYOUT.addrPartnerOfferedForeignPKH, PUBLIC_KEY_HASH_LENGTH);
			tradeData.partnerRequestedForeignPKH = readBytes(dataByteBuffer, LAYOUT.addrPartnerRequestedForeignPKH, PUBLIC_KEY_HASH_LENGTH);

			long lockTimeA = getLong(dataByteBuffer, LAYOUT.addrLockTimeA);
			if (lockTimeA > 0)
				tradeData.lockTimeA = (int) lockTimeA;

			long lockTimeB = getLong(dataByteBuffer, LAYOUT.addrLockTimeB);
			if (lockTimeB > 0)
				tradeData.lockTimeB = (int) lockTimeB;

			long refundTimestamp = getLong(dataByteBuffer, LAYOUT.addrRefundTimestamp);
			if (refundTimestamp > 0)
				tradeData.tradeRefundHeight = new Timestamp(refundTimestamp).blockHeight;
		}

		byte[] offeredChainIdReference = readBytes(dataByteBuffer, LAYOUT.addrOfferedForeignBlockchainChainId, CHAIN_ID_REFERENCE_LENGTH);
		ForeignBlockchainRegistry.Entry offeredForeignBlockchain = ForeignBlockchainRegistry.fromBitcoinyChainIdReference(offeredChainIdReference);
		if (offeredForeignBlockchain == null || !offeredForeignBlockchain.isBitcoiny()
				|| !Arrays.equals(offeredChainIdReference, offeredForeignBlockchain.getActiveChainIdReferenceBytes()))
			return null;

		byte[] requestedChainIdReference = readBytes(dataByteBuffer, LAYOUT.addrRequestedForeignBlockchainChainId, CHAIN_ID_REFERENCE_LENGTH);
		ForeignBlockchainRegistry.Entry requestedForeignBlockchain = ForeignBlockchainRegistry.fromBitcoinyChainIdReference(requestedChainIdReference);
		if (requestedForeignBlockchain == null || !requestedForeignBlockchain.isBitcoiny()
				|| !Arrays.equals(requestedChainIdReference, requestedForeignBlockchain.getActiveChainIdReferenceBytes()))
			return null;

		tradeData.offeredForeignBlockchain = offeredForeignBlockchain.name();
		tradeData.requestedForeignBlockchain = requestedForeignBlockchain.name();
		return tradeData;
	}

	public static boolean isSupportedBitcoinyPair(ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain) {
		return offeredForeignBlockchain != null && offeredForeignBlockchain.isBitcoiny()
				&& offeredForeignBlockchain.getBitcoinySpec().supportsForeignForeignTrades()
				&& requestedForeignBlockchain != null && requestedForeignBlockchain.isBitcoiny()
				&& requestedForeignBlockchain.getBitcoinySpec().supportsForeignForeignTrades();
	}

	public static ForeignBlockchainRegistry.Entry requireBitcoinyEntry(String blockchainName, String fieldName) {
		ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromString(blockchainName);
		if (entry == null || !entry.isBitcoiny())
			throw new IllegalArgumentException("Unsupported Bitcoiny blockchain for " + fieldName + ": " + blockchainName);

		return entry;
	}

	public static byte[] buildReserveMessage(byte[] takerOfferedForeignPublicKeyHash, byte[] takerRequestedForeignPublicKeyHash) {
		byte[] data = new byte[RESERVE_MESSAGE_LENGTH];
		putPaddedHash160(data, 0, takerOfferedForeignPublicKeyHash, "taker offered-chain public key hash");
		putPaddedHash160(data, PADDED_PUBLIC_KEY_HASH_LENGTH, takerRequestedForeignPublicKeyHash, "taker requested-chain public key hash");
		return data;
	}

	public static byte[] buildMakerHtlcMessage(int makerLockTime) {
		return BitTwiddling.toBEByteArray((long) makerLockTime);
	}

	public static byte[] buildTakerHtlcMessage(int takerLockTime) {
		return BitTwiddling.toBEByteArray((long) takerLockTime);
	}

	public static byte[] buildSecretRevealMessage(byte[] secret) {
		if (secret == null || secret.length != SECRET_LENGTH)
			throw new IllegalArgumentException("Secret should be 32 bytes");

		byte[] data = new byte[SECRET_REVEAL_MESSAGE_LENGTH];
		System.arraycopy(secret, 0, data, 0, secret.length);
		return data;
	}

	@Override
	public byte[] buildCancelMessage(String creatorAddress) {
		byte[] data = new byte[CANCEL_MESSAGE_LENGTH];
		System.arraycopy(Base58.decode(creatorAddress), 0, data, 0, 25);
		return data;
	}

	@Override
	public byte[] findSecretA(Repository repository, CrossChainTradeData crossChainTradeData) throws DataException {
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(null,
				crossChainTradeData.atAddress, null, null, null);
		if (messageTransactionsData == null)
			return null;

		for (MessageTransactionData messageTransactionData : messageTransactionsData) {
			if (messageTransactionData.isText())
				continue;

			String sender = Crypto.toAddress(messageTransactionData.getCreatorPublicKey());
			if (!crossChainTradeData.creatorTradeAddress.equals(sender))
				continue;

			byte[] data = messageTransactionData.getData();
			if (data == null || data.length != SECRET_REVEAL_MESSAGE_LENGTH)
				continue;

			byte[] secret = Arrays.copyOf(data, SECRET_LENGTH);
			if (Arrays.equals(Crypto.hash160(secret), crossChainTradeData.hashOfSecretA))
				return secret;
		}

		return null;
	}

	private static void emitCheckSender(ByteBuffer codeByteBuffer, int expectedAddressStart, Integer rejectLabel) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrMessageSenderPointer));
		emitFarJumpIfAddressNotEqual(codeByteBuffer, LAYOUT.addrMessageSender1, expectedAddressStart, rejectLabel);
	}

	private static void emitCheckSender(ByteBuffer codeByteBuffer, int firstExpectedAddressStart, int secondExpectedAddressStart,
			Integer rejectLabel) throws CompilationException {
		Integer labelCheckSecondAddress = null;
		Integer labelSenderAccepted = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrMessageSenderPointer));
			emitFarJumpIfAddressNotEqual(codeByteBuffer, LAYOUT.addrMessageSender1, firstExpectedAddressStart, labelCheckSecondAddress);
			codeByteBuffer.put(OpCode.JMP_ADR.compile(labelSenderAccepted == null ? 0 : labelSenderAccepted));

			labelCheckSecondAddress = codeByteBuffer.position();
			emitFarJumpIfAddressNotEqual(codeByteBuffer, LAYOUT.addrMessageSender1, secondExpectedAddressStart, rejectLabel);

			labelSenderAccepted = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitFarJumpIfAddressNotEqual(ByteBuffer codeByteBuffer, int leftStart, int rightStart, Integer target) throws CompilationException {
		for (int i = 0; i < 4; ++i)
			emitFarJumpIfNotEqual(codeByteBuffer, leftStart + i, rightStart + i, target);
	}

	private static void emitFarJumpIfEqual(ByteBuffer codeByteBuffer, int lhs, int rhs, Integer target) throws CompilationException {
		int skip = codeByteBuffer.position() + OpCode.BNE_DAT.compile(lhs, rhs, 0).length + OpCode.JMP_ADR.compile(0).length;
		codeByteBuffer.put(OpCode.BNE_DAT.compile(lhs, rhs, calcOffset(codeByteBuffer, skip)));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(target == null ? 0 : target));
	}

	private static void emitFarJumpIfNotEqual(ByteBuffer codeByteBuffer, int lhs, int rhs, Integer target) throws CompilationException {
		int skip = codeByteBuffer.position() + OpCode.BEQ_DAT.compile(lhs, rhs, 0).length + OpCode.JMP_ADR.compile(0).length;
		codeByteBuffer.put(OpCode.BEQ_DAT.compile(lhs, rhs, calcOffset(codeByteBuffer, skip)));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(target == null ? 0 : target));
	}

	private static void emitFarJumpIfZero(ByteBuffer codeByteBuffer, int addr, Integer target) throws CompilationException {
		int skip = codeByteBuffer.position() + OpCode.BNZ_DAT.compile(addr, 0).length + OpCode.JMP_ADR.compile(0).length;
		codeByteBuffer.put(OpCode.BNZ_DAT.compile(addr, calcOffset(codeByteBuffer, skip)));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(target == null ? 0 : target));
	}

	private static void emitFarJumpIfNonZero(ByteBuffer codeByteBuffer, int addr, Integer target) throws CompilationException {
		int skip = codeByteBuffer.position() + OpCode.BZR_DAT.compile(addr, 0).length + OpCode.JMP_ADR.compile(0).length;
		codeByteBuffer.put(OpCode.BZR_DAT.compile(addr, calcOffset(codeByteBuffer, skip)));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(target == null ? 0 : target));
	}

	private static void emitFarJumpIfGreaterOrEqual(ByteBuffer codeByteBuffer, int lhs, int rhs, Integer target) throws CompilationException {
		int skip = codeByteBuffer.position() + OpCode.BLT_DAT.compile(lhs, rhs, 0).length + OpCode.JMP_ADR.compile(0).length;
		codeByteBuffer.put(OpCode.BLT_DAT.compile(lhs, rhs, calcOffset(codeByteBuffer, skip)));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(target == null ? 0 : target));
	}

	private static void emitFarJumpIfLessOrEqual(ByteBuffer codeByteBuffer, int lhs, int rhs, Integer target) throws CompilationException {
		int skip = codeByteBuffer.position() + OpCode.BGT_DAT.compile(lhs, rhs, 0).length + OpCode.JMP_ADR.compile(0).length;
		codeByteBuffer.put(OpCode.BGT_DAT.compile(lhs, rhs, calcOffset(codeByteBuffer, skip)));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(target == null ? 0 : target));
	}

	private static void putPaddedHash160(byte[] data, int offset, byte[] hash, String label) {
		requireHash160(hash, label);
		System.arraycopy(hash, 0, data, offset, hash.length);
	}

	private static void requireHash160(byte[] hash, String label) {
		if (hash == null || hash.length != PUBLIC_KEY_HASH_LENGTH)
			throw new IllegalArgumentException(label + " should be 20 bytes");
	}

	private static void requirePositive(long value, String label) {
		if (value <= 0)
			throw new IllegalArgumentException(label + " should be positive");
	}

	private static long getLong(ByteBuffer byteBuffer, int address) {
		return byteBuffer.getLong(MachineState.HEADER_LENGTH + address * MachineState.VALUE_SIZE);
	}

	private static byte[] readBytes(ByteBuffer byteBuffer, int address, int length) {
		byte[] bytes = new byte[length];
		int position = MachineState.HEADER_LENGTH + address * MachineState.VALUE_SIZE;
		byteBuffer.position(position);
		byteBuffer.get(bytes);
		return bytes;
	}

	private static void putLong(ByteBuffer byteBuffer, int address, long value) {
		byteBuffer.putLong(address * MachineState.VALUE_SIZE, value);
	}

	private static void putBytes(ByteBuffer byteBuffer, int address, byte[] bytes) {
		byte[] padded = Bytes.ensureCapacity(bytes, 32, 0);
		byteBuffer.position(address * MachineState.VALUE_SIZE);
		byteBuffer.put(padded, 0, 32);
	}

	private static void putRawBytes(ByteBuffer byteBuffer, int address, byte[] bytes) {
		byteBuffer.position(address * MachineState.VALUE_SIZE);
		byteBuffer.put(bytes);
	}

	private static class Layout {
		final int addrCreatorTradeAddress1;
		final int addrCreatorOfferedForeignPKH;
		final int addrCreatorRequestedForeignPKH;
		final int addrHashOfSecretA;
		final int addrOfferedForeignAmount;
		final int addrRequestedForeignAmount;
		final int addrTradeTimeout;
		final int addrLocktimeSafetyMarginSeconds;
		final int addrTakerRefundTimeoutMinutes;
		final int addrMessageTxnType;
		final int addrExpectedReserveMessageLength;
		final int addrExpectedMakerHtlcMessageLength;
		final int addrExpectedTakerHtlcMessageLength;
		final int addrExpectedSecretRevealMessageLength;
		final int addrExpectedCancelMessageLength;
		final int addrReserveMessageRequestedPKHOffset;
		final int addrCreatorAddressPointer;
		final int addrMessageSenderPointer;
		final int addrTakerAddressPointer;
		final int addrTempPointer;
		final int addrTempMessageDataPointer;
		final int addrTempMessageDataLength;
		final int addrZero;
		final int addrOne;
		final int addrReservedModeValue;
		final int addrForeignLockedModeValue;
		final int addrTradingModeValue;
		final int addrCreatorAddress1;
		final int addrTakerAddress1;
		final int addrLastTxnTimestamp;
		final int addrBlockTimestamp;
		final int addrTxnType;
		final int addrResult;
		final int addrTempPaymentCount;
		final int addrTempPaymentAssetId;
		final int addrTempAmount;
		final int addrMessageSender1;
		final int addrMessageLength;
		final int addrTempData1;
		final int addrTempMessageData;
		final int addrPartnerOfferedForeignPKH;
		final int addrPartnerRequestedForeignPKH;
		final int addrLockTimeA;
		final int addrLockTimeB;
		final int addrTempLocktime;
		final int addrTempLocktimeWithMargin;
		final int addrRefundTimestamp;
		final int addrMode;
		final int addrOfferedForeignBlockchainChainId;
		final int addrRequestedForeignBlockchainChainId;
		final int valueCount;

		Layout() {
			int c = 0;
			this.addrCreatorTradeAddress1 = c;
			c += 4;
			this.addrCreatorOfferedForeignPKH = c;
			c += 4;
			this.addrCreatorRequestedForeignPKH = c;
			c += 4;
			this.addrHashOfSecretA = c;
			c += 4;
			this.addrOfferedForeignAmount = c++;
			this.addrRequestedForeignAmount = c++;
			this.addrTradeTimeout = c++;
			this.addrLocktimeSafetyMarginSeconds = c++;
			this.addrTakerRefundTimeoutMinutes = c++;
			this.addrMessageTxnType = c++;
			this.addrExpectedReserveMessageLength = c++;
			this.addrExpectedMakerHtlcMessageLength = c++;
			this.addrExpectedTakerHtlcMessageLength = c++;
			this.addrExpectedSecretRevealMessageLength = c++;
			this.addrExpectedCancelMessageLength = c++;
			this.addrReserveMessageRequestedPKHOffset = c++;
			this.addrCreatorAddressPointer = c++;
			this.addrMessageSenderPointer = c++;
			this.addrTakerAddressPointer = c++;
			this.addrTempPointer = c++;
			this.addrTempMessageDataPointer = c++;
			this.addrTempMessageDataLength = c++;
			this.addrZero = c++;
			this.addrOne = c++;
			this.addrReservedModeValue = c++;
			this.addrForeignLockedModeValue = c++;
			this.addrTradingModeValue = c++;
			this.addrCreatorAddress1 = c;
			c += 4;
			this.addrTakerAddress1 = c;
			c += 4;
			this.addrLastTxnTimestamp = c++;
			this.addrBlockTimestamp = c++;
			this.addrTxnType = c++;
			this.addrResult = c++;
			this.addrTempPaymentCount = c++;
			this.addrTempPaymentAssetId = c++;
			this.addrTempAmount = c++;
			this.addrMessageSender1 = c;
			c += 4;
			this.addrMessageLength = c++;
			this.addrTempData1 = c;
			c += 4;
			this.addrTempMessageData = c;
			c += 4;
			this.addrPartnerOfferedForeignPKH = c;
			c += 4;
			this.addrPartnerRequestedForeignPKH = c;
			c += 4;
			this.addrLockTimeA = c++;
			this.addrLockTimeB = c++;
			this.addrTempLocktime = c++;
			this.addrTempLocktimeWithMargin = c++;
			this.addrRefundTimestamp = c++;
			this.addrMode = c++;
			this.addrOfferedForeignBlockchainChainId = c;
			c += CHAIN_ID_REFERENCE_LENGTH / MachineState.VALUE_SIZE;
			this.addrRequestedForeignBlockchainChainId = c;
			c += CHAIN_ID_REFERENCE_LENGTH / MachineState.VALUE_SIZE;
			this.valueCount = c;
		}
	}

}
