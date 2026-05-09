package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.ciyam.at.API;
import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.ciyam.at.Timestamp;
import org.qortal.account.Account;
import org.qortal.api.resource.CrossChainUtils;
import org.qortal.at.ChainFunctionCode;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Base58;
import org.qortal.utils.BitTwiddling;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

import static org.ciyam.at.OpCode.calcOffset;

public class BitcoinyACCTv5 implements ACCT {

	public static final String NAME = BitcoinyACCTv5.class.getSimpleName();
	public static final byte[] CODE_BYTES_HASH = HashCode.fromString("4caa3fe9793e415341fe08a46d4d415de3887625c3493593dfc71491f7c2441b").asBytes();

	public static final int SECRET_LENGTH = 32;
	public static final int LOCK_MESSAGE_LENGTH = 32 /*taker foreign PKH*/ + 32 /*hash of secret-A*/ + 8 /*lockTimeA*/ + 8 /*refund timeout*/;
	public static final int REDEEM_MESSAGE_LENGTH = 32 /*secret-A*/ + 32 /*maker local receiving address*/;
	public static final int CANCEL_MESSAGE_LENGTH = 32 /*creator address*/;

	private static final Layout LAYOUT = new Layout();

	private static final int MODE_VALUE_OFFSET = LAYOUT.addrMode;
	public static final int MODE_BYTE_OFFSET = MachineState.HEADER_LENGTH + MODE_VALUE_OFFSET * MachineState.VALUE_SIZE;

	private static BitcoinyACCTv5 instance;

	private BitcoinyACCTv5() {
	}

	public static synchronized BitcoinyACCTv5 getInstance() {
		if (instance == null)
			instance = new BitcoinyACCTv5();

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

	public static byte[] buildTradeAT(ForeignBlockchainRegistry.Entry foreignBlockchain, String creatorTradeAddress,
			byte[] makerForeignPublicKeyHash, long localAmount, long foreignAmount, int tradeTimeout) {
		if (foreignBlockchain == null || !foreignBlockchain.isBitcoiny())
			throw new IllegalArgumentException("Unsupported Bitcoiny blockchain");

		if (makerForeignPublicKeyHash.length != 20)
			throw new IllegalArgumentException("Foreign public key hash should be 20 bytes");

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(LAYOUT.valueCount * MachineState.VALUE_SIZE);

		putBytes(dataByteBuffer, LAYOUT.addrCreatorTradeAddress1, Base58.decode(creatorTradeAddress));
		putBytes(dataByteBuffer, LAYOUT.addrCreatorForeignPKH, makerForeignPublicKeyHash);
		putLong(dataByteBuffer, LAYOUT.addrLocalAmount, localAmount);
		putLong(dataByteBuffer, LAYOUT.addrForeignAmount, foreignAmount);
		putLong(dataByteBuffer, LAYOUT.addrTradeTimeout, tradeTimeout);
		putLong(dataByteBuffer, LAYOUT.addrMessageTxnType, API.ATTransactionType.MESSAGE.value);
		putLong(dataByteBuffer, LAYOUT.addrExpectedLockMessageLength, LOCK_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedRedeemMessageLength, REDEEM_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedCancelMessageLength, CANCEL_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrCreatorAddressPointer, LAYOUT.addrCreatorAddress1);
		putLong(dataByteBuffer, LAYOUT.addrMessageSenderPointer, LAYOUT.addrMessageSender1);
		putLong(dataByteBuffer, LAYOUT.addrTakerAddressPointer, LAYOUT.addrTakerAddress1);
		putLong(dataByteBuffer, LAYOUT.addrTempPointer, LAYOUT.addrTempData1);
		putLong(dataByteBuffer, LAYOUT.addrTempMessageDataPointer, LAYOUT.addrTempMessageData);
		putLong(dataByteBuffer, LAYOUT.addrTempMessageDataLength, SECRET_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrLockMessageHashOfSecretAOffset, 32);
		putLong(dataByteBuffer, LAYOUT.addrLockMessageValuesOffset, 64);
		putLong(dataByteBuffer, LAYOUT.addrRedeemMessageReceivingAddressOffset, 32);
		putLong(dataByteBuffer, LAYOUT.addrZero, 0);
		putLong(dataByteBuffer, LAYOUT.addrOne, 1);
		putLong(dataByteBuffer, LAYOUT.addrTradingModeValue, AcctMode.TRADING.value);
		putRawBytes(dataByteBuffer, LAYOUT.addrForeignBlockchainChainId, foreignBlockchain.getActiveChainIdReferenceBytes());

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(2048);

		Integer labelLoop = null;
		Integer labelProcessTxn = null;
		Integer labelStop = null;
		Integer labelNotRefund = null;
		Integer labelRefund = null;
		Integer labelCheckOfferingMessages = null;
		Integer labelCheckTradingMessages = null;
		Integer labelCheckLock = null;
		Integer labelCheckCancel = null;
		Integer labelCheckRedeem = null;

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
				codeByteBuffer.put(OpCode.BLT_DAT.compile(LAYOUT.addrBlockTimestamp, LAYOUT.addrRefundTimestamp, calcOffset(codeByteBuffer, labelNotRefund)));
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRefund == null ? 0 : labelRefund));

				labelNotRefund = codeByteBuffer.position();

				labelProcessTxn = codeByteBuffer.position();
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, LAYOUT.addrLastTxnTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, LAYOUT.addrResult));
				emitFarJumpIfNonZero(codeByteBuffer, LAYOUT.addrResult, labelStop);

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, LAYOUT.addrLastTxnTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, LAYOUT.addrTxnType));
				emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTxnType, LAYOUT.addrMessageTxnType, labelLoop);

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_MESSAGE_LENGTH_FROM_TX_IN_A.value, LAYOUT.addrMessageLength));
				emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrMode, LAYOUT.addrZero, labelCheckTradingMessages);

				labelCheckOfferingMessages = codeByteBuffer.position();
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedLockMessageLength, labelCheckLock);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedCancelMessageLength, labelCheckCancel);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckTradingMessages = codeByteBuffer.position();
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedRedeemMessageLength, labelCheckRedeem);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckLock = codeByteBuffer.position();
				emitProcessLockMessage(codeByteBuffer, labelLoop);

				labelCheckCancel = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorAddress1, labelLoop);
				codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.CANCELLED.value));
				codeByteBuffer.put(OpCode.FIN_IMD.compile());

				labelCheckRedeem = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorTradeAddress1, labelLoop);
				emitProcessRedeemMessage(codeByteBuffer, labelLoop);

				labelRefund = codeByteBuffer.position();
				emitRefundToTaker(codeByteBuffer);

				labelStop = codeByteBuffer.position();
				codeByteBuffer.put(OpCode.STP_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile Bitcoiny reverse ACCT?", e);
			}
		}

		codeByteBuffer.flip();
		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		assert Arrays.equals(Crypto.digest(codeBytes), BitcoinyACCTv5.CODE_BYTES_HASH)
				: String.format("BitcoinyACCTv5.CODE_BYTES_HASH mismatch: expected %s, actual %s", HashCode.fromBytes(CODE_BYTES_HASH), HashCode.fromBytes(Crypto.digest(codeBytes)));

		return MachineState.toCreationBytes((short) 2, codeBytes, dataByteBuffer.array(), (short) 0, (short) 0, 0L);
	}

	private static void emitProcessLockMessage(ByteBuffer codeByteBuffer, Integer rejectLabel) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_CONFIGURED_ASSET_ID.value, LAYOUT.addrTempAssetId));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_PAYMENT_COUNT_FROM_TX_IN_A.value, LAYOUT.addrTempPaymentCount));
		emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTempPaymentCount, LAYOUT.addrOne, rejectLabel);
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_ASSET_ID_FROM_TX_IN_A.value, LAYOUT.addrTempPaymentAssetId));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_AMOUNT_FROM_TX_IN_A, LAYOUT.addrTempAmount));
		emitRefundLockPaymentIfNotEqual(codeByteBuffer, LAYOUT.addrTempPaymentAssetId, LAYOUT.addrTempAssetId, rejectLabel);
		emitRefundLockPaymentIfNotEqual(codeByteBuffer, LAYOUT.addrTempAmount, LAYOUT.addrLocalAmount, rejectLabel);

		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTakerAddressPointer));

		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrPartnerForeignPKH));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrLockMessageHashOfSecretAOffset));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrHashOfSecretA));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrLockMessageValuesOffset));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrLockTimeA));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B2, LAYOUT.addrRefundTimeout));
		emitFarJumpIfLessOrEqual(codeByteBuffer, LAYOUT.addrRefundTimeout, LAYOUT.addrZero, rejectLabel);
		codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, LAYOUT.addrRefundTimestamp,
				LAYOUT.addrLastTxnTimestamp, LAYOUT.addrRefundTimeout));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.TRADING.value));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
	}

	private static void emitRefundLockPaymentIfNotEqual(ByteBuffer codeByteBuffer, int lhs, int rhs, Integer rejectLabel)
			throws CompilationException {
		Integer labelValid = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			codeByteBuffer.put(OpCode.BEQ_DAT.compile(lhs, rhs, calcOffset(codeByteBuffer, labelValid)));
			codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, LAYOUT.addrResult,
					LAYOUT.addrTempPaymentAssetId, LAYOUT.addrTempAmount));
			codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
			labelValid = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitProcessRedeemMessage(ByteBuffer codeByteBuffer, Integer rejectLabel) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempMessageDataPointer));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrHashOfSecretA));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, LAYOUT.addrTempPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.CHECK_HASH160_WITH_B, LAYOUT.addrResult,
				LAYOUT.addrTempMessageDataPointer, LAYOUT.addrTempMessageDataLength));
		emitFarJumpIfZero(codeByteBuffer, LAYOUT.addrResult, rejectLabel);

		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrRedeemMessageReceivingAddressOffset));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrTempReceivingAddress));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_CONFIGURED_ASSET_ID.value, LAYOUT.addrTempAssetId));
		codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, LAYOUT.addrResult,
				LAYOUT.addrTempAssetId, LAYOUT.addrLocalAmount));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.REDEEMED.value));
		codeByteBuffer.put(OpCode.FIN_IMD.compile());
	}

	private static void emitRefundToTaker(ByteBuffer codeByteBuffer) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, LAYOUT.addrTakerAddressPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_CONFIGURED_ASSET_ID.value, LAYOUT.addrTempAssetId));
		codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, LAYOUT.addrResult,
				LAYOUT.addrTempAssetId, LAYOUT.addrLocalAmount));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.REFUNDED.value));
		codeByteBuffer.put(OpCode.FIN_IMD.compile());
	}

	private static void emitCheckSender(ByteBuffer codeByteBuffer, int expectedAddressStart, Integer rejectLabel) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrMessageSenderPointer));
		emitFarJumpIfAddressNotEqual(codeByteBuffer, LAYOUT.addrMessageSender1, expectedAddressStart, rejectLabel);
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

	private static void emitFarJumpIfLessOrEqual(ByteBuffer codeByteBuffer, int lhs, int rhs, Integer target) throws CompilationException {
		int skip = codeByteBuffer.position() + OpCode.BGT_DAT.compile(lhs, rhs, 0).length + OpCode.JMP_ADR.compile(0).length;
		codeByteBuffer.put(OpCode.BGT_DAT.compile(lhs, rhs, calcOffset(codeByteBuffer, skip)));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(target == null ? 0 : target));
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
		tradeData.tradeDirection = TradeDirection.SELL_FOREIGN;
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
		tradeData.creatorForeignPKH = readBytes(dataByteBuffer, LAYOUT.addrCreatorForeignPKH, 20);
		tradeData.localAmount = getLong(dataByteBuffer, LAYOUT.addrLocalAmount);
		tradeData.totalLocalAmount = tradeData.localAmount;
		tradeData.minFillLocalAmount = tradeData.localAmount;
		tradeData.maxFillLocalAmount = tradeData.localAmount;
		tradeData.expectedForeignAmount = getLong(dataByteBuffer, LAYOUT.addrForeignAmount);
		tradeData.tradeTimeout = (int) getLong(dataByteBuffer, LAYOUT.addrTradeTimeout);

		long modeValue = getLong(dataByteBuffer, LAYOUT.addrMode);
		tradeData.mode = AcctMode.valueOf((int) (modeValue & 0xffL));
		if (tradeData.mode == null)
			tradeData.mode = AcctMode.OFFERING;

		if (tradeData.mode == AcctMode.OFFERING) {
			tradeData.remainingLocalAmount = tradeData.localAmount;
			tradeData.availableFillSlots = 1;
		} else {
			tradeData.remainingLocalAmount = 0L;
			tradeData.activeLocalAmount = tradeData.mode == AcctMode.TRADING ? tradeData.localAmount : 0L;
			tradeData.completedLocalAmount = tradeData.mode == AcctMode.REDEEMED ? tradeData.localAmount : 0L;
			tradeData.partnerAddress = Base58.encode(readBytes(dataByteBuffer, LAYOUT.addrTakerAddress1, 25));
			tradeData.partnerForeignPKH = readBytes(dataByteBuffer, LAYOUT.addrPartnerForeignPKH, 20);
			tradeData.hashOfSecretA = readBytes(dataByteBuffer, LAYOUT.addrHashOfSecretA, 20);
			tradeData.lockTimeA = (int) getLong(dataByteBuffer, LAYOUT.addrLockTimeA);
			tradeData.refundTimeout = (int) getLong(dataByteBuffer, LAYOUT.addrRefundTimeout);
			tradeData.tradeRefundHeight = new Timestamp(getLong(dataByteBuffer, LAYOUT.addrRefundTimestamp)).blockHeight;
		}

		byte[] chainIdReference = readBytes(dataByteBuffer, LAYOUT.addrForeignBlockchainChainId, Bip122ChainId.REFERENCE_BYTE_LENGTH);
		ForeignBlockchainRegistry.Entry foreignBlockchain = ForeignBlockchainRegistry.fromBitcoinyChainIdReference(chainIdReference);
		if (foreignBlockchain == null || !foreignBlockchain.isBitcoiny()
				|| !Arrays.equals(chainIdReference, foreignBlockchain.getActiveChainIdReferenceBytes()))
			return null;

		tradeData.foreignBlockchain = foreignBlockchain.name();
		return tradeData;
	}

	public static byte[] buildLockMessage(byte[] takerForeignPKH, byte[] hashOfSecretA, int lockTimeA, int refundTimeout) {
		byte[] data = new byte[LOCK_MESSAGE_LENGTH];
		System.arraycopy(takerForeignPKH, 0, data, 0, takerForeignPKH.length);
		System.arraycopy(hashOfSecretA, 0, data, 32, hashOfSecretA.length);
		System.arraycopy(BitTwiddling.toBEByteArray((long) lockTimeA), 0, data, 64, 8);
		System.arraycopy(BitTwiddling.toBEByteArray((long) refundTimeout), 0, data, 72, 8);
		return data;
	}

	public static byte[] buildRedeemMessage(byte[] secretA, String receivingAddress) {
		byte[] data = new byte[REDEEM_MESSAGE_LENGTH];
		System.arraycopy(secretA, 0, data, 0, secretA.length);
		System.arraycopy(Base58.decode(receivingAddress), 0, data, 32, 25);
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
			if (data == null || data.length != REDEEM_MESSAGE_LENGTH)
				continue;

			byte[] secret = Arrays.copyOfRange(data, 0, SECRET_LENGTH);
			if (Arrays.equals(Crypto.hash160(secret), crossChainTradeData.hashOfSecretA))
				return secret;
		}

		return null;
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

	private static class Layout {
		final int addrCreatorTradeAddress1;
		final int addrCreatorForeignPKH;
		final int addrLocalAmount;
		final int addrForeignAmount;
		final int addrTradeTimeout;
		final int addrMessageTxnType;
		final int addrExpectedLockMessageLength;
		final int addrExpectedRedeemMessageLength;
		final int addrExpectedCancelMessageLength;
		final int addrCreatorAddressPointer;
		final int addrMessageSenderPointer;
		final int addrTakerAddressPointer;
		final int addrTempPointer;
		final int addrTempMessageDataPointer;
		final int addrTempMessageDataLength;
		final int addrLockMessageHashOfSecretAOffset;
		final int addrLockMessageValuesOffset;
		final int addrRedeemMessageReceivingAddressOffset;
		final int addrZero;
		final int addrOne;
		final int addrTradingModeValue;
		final int addrCreatorAddress1;
		final int addrTakerAddress1;
		final int addrLastTxnTimestamp;
		final int addrBlockTimestamp;
		final int addrTxnType;
		final int addrResult;
		final int addrTempAmount;
		final int addrTempPaymentCount;
		final int addrTempPaymentAssetId;
		final int addrMessageSender1;
		final int addrMessageLength;
		final int addrTempData1;
		final int addrTempMessageData;
		final int addrTempReceivingAddress;
		final int addrTempAssetId;
		final int addrPartnerForeignPKH;
		final int addrHashOfSecretA;
		final int addrLockTimeA;
		final int addrRefundTimeout;
		final int addrRefundTimestamp;
		final int addrMode;
		final int addrForeignBlockchainChainId;
		final int valueCount;

		Layout() {
			int c = 0;
			this.addrCreatorTradeAddress1 = c;
			c += 4;
			this.addrCreatorForeignPKH = c;
			c += 4;
			this.addrLocalAmount = c++;
			this.addrForeignAmount = c++;
			this.addrTradeTimeout = c++;
			this.addrMessageTxnType = c++;
			this.addrExpectedLockMessageLength = c++;
			this.addrExpectedRedeemMessageLength = c++;
			this.addrExpectedCancelMessageLength = c++;
			this.addrCreatorAddressPointer = c++;
			this.addrMessageSenderPointer = c++;
			this.addrTakerAddressPointer = c++;
			this.addrTempPointer = c++;
			this.addrTempMessageDataPointer = c++;
			this.addrTempMessageDataLength = c++;
			this.addrLockMessageHashOfSecretAOffset = c++;
			this.addrLockMessageValuesOffset = c++;
			this.addrRedeemMessageReceivingAddressOffset = c++;
			this.addrZero = c++;
			this.addrOne = c++;
			this.addrTradingModeValue = c++;
			this.addrCreatorAddress1 = c;
			c += 4;
			this.addrTakerAddress1 = c;
			c += 4;
			this.addrLastTxnTimestamp = c++;
			this.addrBlockTimestamp = c++;
			this.addrTxnType = c++;
			this.addrResult = c++;
			this.addrTempAmount = c++;
			this.addrTempPaymentCount = c++;
			this.addrTempPaymentAssetId = c++;
			this.addrMessageSender1 = c;
			c += 4;
			this.addrMessageLength = c++;
			this.addrTempData1 = c;
			c += 4;
			this.addrTempMessageData = c;
			c += 4;
			this.addrTempReceivingAddress = c;
			c += 4;
			this.addrTempAssetId = c++;
			this.addrPartnerForeignPKH = c;
			c += 4;
			this.addrHashOfSecretA = c;
			c += 4;
			this.addrLockTimeA = c++;
			this.addrRefundTimeout = c++;
			this.addrRefundTimestamp = c++;
			this.addrMode = c++;
			this.addrForeignBlockchainChainId = c;
			c += Bip122ChainId.REFERENCE_BYTE_LENGTH / MachineState.VALUE_SIZE;
			this.valueCount = c;
		}
	}
}
