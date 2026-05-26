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

public class BitcoinyACCTv6 implements ACCT {

	public static final String NAME = BitcoinyACCTv6.class.getSimpleName();
	public static final byte[] CODE_BYTES_HASH = HashCode.fromString("1d7e3ec8373076919a3d4baeda5617b9549f4d87d9e39d707ec682327ca148a8").asBytes();

	public static final int SECRET_LENGTH = 32;
	public static final int SLOT_COUNT = 2;

	private static final int SLOT_EMPTY = 0;
	private static final int SLOT_RESERVED = 1;
	private static final int SLOT_FOREIGN_LOCKED = 2;
	private static final int SLOT_TRADING = 3;

	private static final Layout LAYOUT = new Layout();

	private static final int MODE_VALUE_OFFSET = LAYOUT.addrMode;
	public static final int MODE_BYTE_OFFSET = MachineState.HEADER_LENGTH + MODE_VALUE_OFFSET * MachineState.VALUE_SIZE;

	public static final int RESERVE_MESSAGE_LENGTH = 8 /*slot index*/
			+ 32 /*taker foreign PKH padded from 20*/
			+ 8 /*fill local amount*/
			+ 8 /*fill foreign amount*/;
	public static final int FOREIGN_LOCK_MESSAGE_LENGTH = 8 /*slot index*/
			+ 32 /*hash of secret-A padded from 20*/
			+ 8 /*lockTimeA*/;
	public static final int LOCAL_LOCK_MESSAGE_LENGTH = 16 /*slot index + reserved bytes; payment carries the local asset*/;
	public static final int REDEEM_MESSAGE_LENGTH = 8 /*slot index*/ + 32 /*secret-A*/ + 32 /*maker local receiving address*/;
	public static final int CANCEL_MESSAGE_LENGTH = 32 /*creator or maker trade address*/;
	public static final int CANCEL_FILL_MESSAGE_LENGTH = 8 /*slot index*/ + 32 /*creator or maker trade address*/;

	private static BitcoinyACCTv6 instance;

	private BitcoinyACCTv6() {
	}

	public static synchronized BitcoinyACCTv6 getInstance() {
		if (instance == null)
			instance = new BitcoinyACCTv6();

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
			byte[] makerForeignPublicKeyHash, long totalLocalAmount, long totalForeignAmount, long minFillLocalAmount,
			long maxFillLocalAmount, int tradeTimeout) {
		if (foreignBlockchain == null || !foreignBlockchain.isBitcoiny())
			throw new IllegalArgumentException("Unsupported Bitcoiny blockchain");

		if (makerForeignPublicKeyHash.length != 20)
			throw new IllegalArgumentException("Foreign public key hash should be 20 bytes");

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(LAYOUT.valueCount * MachineState.VALUE_SIZE);

		putBytes(dataByteBuffer, LAYOUT.addrCreatorTradeAddress1, Base58.decode(creatorTradeAddress));
		putBytes(dataByteBuffer, LAYOUT.addrCreatorForeignPKH, makerForeignPublicKeyHash);
		putLong(dataByteBuffer, LAYOUT.addrTotalLocalAmount, totalLocalAmount);
		putLong(dataByteBuffer, LAYOUT.addrTotalForeignAmount, totalForeignAmount);
		putLong(dataByteBuffer, LAYOUT.addrMinFillLocalAmount, minFillLocalAmount);
		putLong(dataByteBuffer, LAYOUT.addrMaxFillLocalAmount, maxFillLocalAmount);
		putLong(dataByteBuffer, LAYOUT.addrTradeTimeout, tradeTimeout);
		putLong(dataByteBuffer, LAYOUT.addrLocalRefundTimeout, calcLocalRefundTimeout(tradeTimeout));
		putLong(dataByteBuffer, LAYOUT.addrAvailableLocalAmount, totalLocalAmount);
		putLong(dataByteBuffer, LAYOUT.addrMessageTxnType, API.ATTransactionType.MESSAGE.value);
		putLong(dataByteBuffer, LAYOUT.addrExpectedReserveMessageLength, RESERVE_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedForeignLockMessageLength, FOREIGN_LOCK_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedLocalLockMessageLength, LOCAL_LOCK_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedRedeemMessageLength, REDEEM_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedCancelMessageLength, CANCEL_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedCancelFillMessageLength, CANCEL_FILL_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrCreatorAddressPointer, LAYOUT.addrCreatorAddress1);
		putLong(dataByteBuffer, LAYOUT.addrMessageSenderPointer, LAYOUT.addrMessageSender1);
		putLong(dataByteBuffer, LAYOUT.addrTempPointer, LAYOUT.addrTempAddress);
		putLong(dataByteBuffer, LAYOUT.addrTempMessageDataPointer, LAYOUT.addrTempData);
		putLong(dataByteBuffer, LAYOUT.addrTempMessageDataLength, SECRET_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrForeignLockMessageHashOfSecretAOffset, 8);
		putLong(dataByteBuffer, LAYOUT.addrForeignLockMessageLockTimeOffset, 40);
		putLong(dataByteBuffer, LAYOUT.addrReserveMessagePartnerForeignPKHOffset, 8);
		putLong(dataByteBuffer, LAYOUT.addrReserveMessageFillAmountsOffset, 40);
		putLong(dataByteBuffer, LAYOUT.addrRedeemMessageSecretOffset, 8);
		putLong(dataByteBuffer, LAYOUT.addrRedeemMessageReceivingAddressOffset, 40);
		putLong(dataByteBuffer, LAYOUT.addrZero, 0);
		putLong(dataByteBuffer, LAYOUT.addrOne, 1);
		putLong(dataByteBuffer, LAYOUT.addrReservedStateValue, SLOT_RESERVED);
		putLong(dataByteBuffer, LAYOUT.addrForeignLockedStateValue, SLOT_FOREIGN_LOCKED);
		putLong(dataByteBuffer, LAYOUT.addrTradingStateValue, SLOT_TRADING);
		for (int i = 0; i < SLOT_COUNT; ++i)
			putLong(dataByteBuffer, LAYOUT.addrSlotIndexValues[i], i);
		putRawBytes(dataByteBuffer, LAYOUT.addrForeignBlockchainChainId, foreignBlockchain.getActiveChainIdReferenceBytes());

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(12288);

		Integer labelLoop = null;
		Integer labelProcessTxn = null;
		Integer labelProcessMessages = null;
		Integer labelStop = null;
		Integer labelCheckReserve = null;
		Integer labelCheckForeignLock = null;
		Integer labelCheckLocalLock = null;
		Integer labelCheckRedeem = null;
		Integer labelCheckCancel = null;
		Integer labelCheckCancelFill = null;
		Integer labelRefundUnexpectedPayment = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, LAYOUT.addrLastTxnTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_CREATOR_INTO_B));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrCreatorAddressPointer));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				labelLoop = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_BLOCK_TIMESTAMP, LAYOUT.addrBlockTimestamp));
				for (Slot slot : LAYOUT.slots)
					emitRefundExpiredTradingSlot(codeByteBuffer, slot);

				emitFinishChecks(codeByteBuffer, labelProcessMessages);

				labelProcessMessages = codeByteBuffer.position();
				emitFarJumpIfNonZero(codeByteBuffer, LAYOUT.addrActiveSlotCount, labelProcessTxn);
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.SLEEP_UNTIL_MESSAGE.value, LAYOUT.addrLastTxnTimestamp));

				labelProcessTxn = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, LAYOUT.addrLastTxnTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, LAYOUT.addrResult));
				emitFarJumpIfNonZero(codeByteBuffer, LAYOUT.addrResult, labelStop);

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, LAYOUT.addrLastTxnTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, LAYOUT.addrTxnType));
				emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTxnType, LAYOUT.addrMessageTxnType, labelLoop);

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_MESSAGE_LENGTH_FROM_TX_IN_A.value, LAYOUT.addrMessageLength));
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedReserveMessageLength, labelCheckReserve);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedForeignLockMessageLength, labelCheckForeignLock);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedLocalLockMessageLength, labelCheckLocalLock);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedRedeemMessageLength, labelCheckRedeem);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedCancelMessageLength, labelCheckCancel);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedCancelFillMessageLength, labelCheckCancelFill);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckReserve = codeByteBuffer.position();
				emitFarJumpIfNonZero(codeByteBuffer, LAYOUT.addrCancelledFlag, labelLoop);
				emitExtractReserveMessage(codeByteBuffer, labelLoop);
				for (Slot slot : LAYOUT.slots)
					emitProcessReserveSlot(codeByteBuffer, slot, labelLoop);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckForeignLock = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorTradeAddress1, labelLoop);
				emitExtractForeignLockMessage(codeByteBuffer);
				for (Slot slot : LAYOUT.slots)
					emitProcessForeignLockSlot(codeByteBuffer, slot, labelLoop);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckLocalLock = codeByteBuffer.position();
				emitExtractLocalLockMessage(codeByteBuffer);
				for (Slot slot : LAYOUT.slots)
					emitProcessLocalLockSlot(codeByteBuffer, slot, labelLoop, labelRefundUnexpectedPayment);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRefundUnexpectedPayment == null ? 0 : labelRefundUnexpectedPayment));

				labelRefundUnexpectedPayment = codeByteBuffer.position();
				emitRefundUnexpectedPayment(codeByteBuffer, labelLoop);

				labelCheckRedeem = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorTradeAddress1, labelLoop);
				emitExtractRedeemMessage(codeByteBuffer);
				for (Slot slot : LAYOUT.slots)
					emitProcessRedeemSlot(codeByteBuffer, slot, labelLoop);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckCancel = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorAddress1, LAYOUT.addrCreatorTradeAddress1, labelLoop);
				codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrCancelledFlag, 1));
				codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.CANCELLED.value));
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckCancelFill = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorAddress1, LAYOUT.addrCreatorTradeAddress1, labelLoop);
				emitExtractSlotIndex(codeByteBuffer);
				for (Slot slot : LAYOUT.slots)
					emitProcessCancelFillSlot(codeByteBuffer, slot, labelLoop);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelStop = codeByteBuffer.position();
				codeByteBuffer.put(OpCode.STP_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile Bitcoiny reverse split-fill ACCT?", e);
			}
		}

		codeByteBuffer.flip();
		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		assert Arrays.equals(Crypto.digest(codeBytes), BitcoinyACCTv6.CODE_BYTES_HASH)
				: String.format("BitcoinyACCTv6.CODE_BYTES_HASH mismatch: expected %s, actual %s", HashCode.fromBytes(CODE_BYTES_HASH), HashCode.fromBytes(Crypto.digest(codeBytes)));

		return MachineState.toCreationBytes((short) 2, codeBytes, dataByteBuffer.array(), (short) 0, (short) 0, 0L);
	}

	public static int calcLocalRefundTimeout(int tradeTimeout) {
		return Math.max(1, tradeTimeout / 2);
	}

	private static void emitFinishChecks(ByteBuffer codeByteBuffer, Integer labelProcessMessages) throws CompilationException {
		emitFarJumpIfNonZero(codeByteBuffer, LAYOUT.addrActiveSlotCount, labelProcessMessages);

		Integer labelNotCancelled = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			emitFarJumpIfZero(codeByteBuffer, LAYOUT.addrCancelledFlag, labelNotCancelled);
			codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.CANCELLED.value));
			codeByteBuffer.put(OpCode.FIN_IMD.compile());
			labelNotCancelled = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}

		emitFarJumpIfNonZero(codeByteBuffer, LAYOUT.addrAvailableLocalAmount, labelProcessMessages);
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.REDEEMED.value));
		codeByteBuffer.put(OpCode.FIN_IMD.compile());
	}

	private static void emitRefundExpiredTradingSlot(ByteBuffer codeByteBuffer, Slot slot) throws CompilationException {
		Integer labelSkip = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			emitFarJumpIfNotEqual(codeByteBuffer, slot.addrState, LAYOUT.addrTradingStateValue, labelSkip);
			emitFarJumpIfLess(codeByteBuffer, LAYOUT.addrBlockTimestamp, slot.addrRefundTimestamp, labelSkip);
			codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, slot.addrPartnerAddress));
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, LAYOUT.addrTempPointer));
			codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_CONFIGURED_ASSET_ID.value, LAYOUT.addrTempAssetId));
			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, LAYOUT.addrResult,
					LAYOUT.addrTempAssetId, slot.addrFillLocalAmount));
			codeByteBuffer.put(OpCode.ADD_DAT.compile(LAYOUT.addrAvailableLocalAmount, slot.addrFillLocalAmount));
			codeByteBuffer.put(OpCode.DEC_DAT.compile(LAYOUT.addrActiveSlotCount));
			emitClearSlot(codeByteBuffer, slot);
			labelSkip = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitExtractReserveMessage(ByteBuffer codeByteBuffer, Integer rejectLabel) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_PAYMENT_COUNT_FROM_TX_IN_A.value, LAYOUT.addrTempPaymentCount));
		emitRefundAnyPaymentIfNonZero(codeByteBuffer, rejectLabel);

		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrTempAddress));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));

		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrZero));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrTempSlotIndex));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrReserveMessagePartnerForeignPKHOffset));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrTempData));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrReserveMessageFillAmountsOffset));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrTempFillLocalAmount));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B2, LAYOUT.addrTempFillForeignAmount));
	}

	private static void emitProcessReserveSlot(ByteBuffer codeByteBuffer, Slot slot, Integer rejectLabel) throws CompilationException {
		Integer labelNext = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTempSlotIndex, LAYOUT.addrSlotIndexValues[slot.index], labelNext);
			emitFarJumpIfNotEqual(codeByteBuffer, slot.addrState, LAYOUT.addrZero, rejectLabel);
			emitFarJumpIfLess(codeByteBuffer, LAYOUT.addrTempFillLocalAmount, LAYOUT.addrMinFillLocalAmount, rejectLabel);
			emitFarJumpIfGreater(codeByteBuffer, LAYOUT.addrTempFillLocalAmount, LAYOUT.addrMaxFillLocalAmount, rejectLabel);
			emitFarJumpIfGreater(codeByteBuffer, LAYOUT.addrTempFillLocalAmount, LAYOUT.addrAvailableLocalAmount, rejectLabel);
			emitFarJumpIfLessOrEqual(codeByteBuffer, LAYOUT.addrTempFillForeignAmount, LAYOUT.addrZero, rejectLabel);
			emitValidateRemainingAfterReserve(codeByteBuffer, rejectLabel);

			copyBlock(codeByteBuffer, LAYOUT.addrTempAddress, slot.addrPartnerAddress);
			copyBlock(codeByteBuffer, LAYOUT.addrTempData, slot.addrPartnerForeignPKH);
			codeByteBuffer.put(OpCode.SET_DAT.compile(slot.addrFillLocalAmount, LAYOUT.addrTempFillLocalAmount));
			codeByteBuffer.put(OpCode.SET_DAT.compile(slot.addrFillForeignAmount, LAYOUT.addrTempFillForeignAmount));
			codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrState, SLOT_RESERVED));
			codeByteBuffer.put(OpCode.SUB_DAT.compile(LAYOUT.addrAvailableLocalAmount, LAYOUT.addrTempFillLocalAmount));
			codeByteBuffer.put(OpCode.INC_DAT.compile(LAYOUT.addrActiveSlotCount));
			codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
			labelNext = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitValidateRemainingAfterReserve(ByteBuffer codeByteBuffer, Integer rejectLabel) throws CompilationException {
		Integer labelValid = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			codeByteBuffer.put(OpCode.SET_DAT.compile(LAYOUT.addrTempRemainingLocalAmount, LAYOUT.addrAvailableLocalAmount));
			codeByteBuffer.put(OpCode.SUB_DAT.compile(LAYOUT.addrTempRemainingLocalAmount, LAYOUT.addrTempFillLocalAmount));
			emitFarJumpIfZero(codeByteBuffer, LAYOUT.addrTempRemainingLocalAmount, labelValid);
			emitFarJumpIfLess(codeByteBuffer, LAYOUT.addrTempRemainingLocalAmount, LAYOUT.addrMinFillLocalAmount, rejectLabel);
			labelValid = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitExtractForeignLockMessage(ByteBuffer codeByteBuffer) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrZero));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrTempSlotIndex));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrForeignLockMessageHashOfSecretAOffset));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrTempData));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrForeignLockMessageLockTimeOffset));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrTempLockTimeA));
	}

	private static void emitProcessForeignLockSlot(ByteBuffer codeByteBuffer, Slot slot, Integer rejectLabel) throws CompilationException {
		Integer labelNext = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTempSlotIndex, LAYOUT.addrSlotIndexValues[slot.index], labelNext);
			emitFarJumpIfNotEqual(codeByteBuffer, slot.addrState, LAYOUT.addrReservedStateValue, rejectLabel);
			emitFarJumpIfLessOrEqual(codeByteBuffer, LAYOUT.addrTempLockTimeA, LAYOUT.addrZero, rejectLabel);
			copyBlock(codeByteBuffer, LAYOUT.addrTempData, slot.addrHashOfSecretA);
			codeByteBuffer.put(OpCode.SET_DAT.compile(slot.addrLockTimeA, LAYOUT.addrTempLockTimeA));
			codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrState, SLOT_FOREIGN_LOCKED));
			codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
			labelNext = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitExtractLocalLockMessage(ByteBuffer codeByteBuffer) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrZero));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrTempSlotIndex));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_PAYMENT_COUNT_FROM_TX_IN_A.value, LAYOUT.addrTempPaymentCount));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_ASSET_ID_FROM_TX_IN_A.value, LAYOUT.addrTempPaymentAssetId));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_AMOUNT_FROM_TX_IN_A, LAYOUT.addrTempAmount));
		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrMessageSenderPointer));
	}

	private static void emitProcessLocalLockSlot(ByteBuffer codeByteBuffer, Slot slot, Integer successLabel, Integer refundLabel) throws CompilationException {
		Integer labelNext = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTempSlotIndex, LAYOUT.addrSlotIndexValues[slot.index], labelNext);
			emitFarJumpIfNotEqual(codeByteBuffer, slot.addrState, LAYOUT.addrForeignLockedStateValue, refundLabel);
			emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTempPaymentCount, LAYOUT.addrOne, refundLabel);
			codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_CONFIGURED_ASSET_ID.value, LAYOUT.addrTempAssetId));
			emitRefundLockPaymentIfNotEqual(codeByteBuffer, LAYOUT.addrTempPaymentAssetId, LAYOUT.addrTempAssetId, refundLabel);
			emitRefundLockPaymentIfNotEqual(codeByteBuffer, LAYOUT.addrTempAmount, slot.addrFillLocalAmount, refundLabel);
			emitRefundLockPaymentIfAddressNotEqual(codeByteBuffer, LAYOUT.addrMessageSender1, slot.addrPartnerAddress, refundLabel);

			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, LAYOUT.addrTempRefundTimestamp,
					LAYOUT.addrLastTxnTimestamp, LAYOUT.addrLocalRefundTimeout));
			codeByteBuffer.put(OpCode.SET_DAT.compile(slot.addrRefundTimestamp, LAYOUT.addrTempRefundTimestamp));
			codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrState, SLOT_TRADING));
			codeByteBuffer.put(OpCode.JMP_ADR.compile(successLabel == null ? 0 : successLabel));
			labelNext = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitRefundUnexpectedPayment(ByteBuffer codeByteBuffer, Integer rejectLabel) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_PAYMENT_COUNT_FROM_TX_IN_A.value, LAYOUT.addrTempPaymentCount));
		emitRefundAnyPaymentIfNonZero(codeByteBuffer, rejectLabel);
		codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
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

	private static void emitRefundLockPaymentIfAddressNotEqual(ByteBuffer codeByteBuffer, int leftStart, int rightStart, Integer rejectLabel)
			throws CompilationException {
		Integer labelValid = null;
		Integer labelRefund = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			for (int i = 0; i < 4; ++i)
				emitFarJumpIfNotEqual(codeByteBuffer, leftStart + i, rightStart + i, labelRefund);

			codeByteBuffer.put(OpCode.JMP_ADR.compile(labelValid == null ? 0 : labelValid));

			labelRefund = codeByteBuffer.position();
			codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, LAYOUT.addrResult,
					LAYOUT.addrTempPaymentAssetId, LAYOUT.addrTempAmount));
			codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));

			labelValid = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitExtractRedeemMessage(ByteBuffer codeByteBuffer) throws CompilationException {
		emitExtractSlotIndex(codeByteBuffer);
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrRedeemMessageSecretOffset));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempMessageDataPointer));
	}

	private static void emitProcessRedeemSlot(ByteBuffer codeByteBuffer, Slot slot, Integer rejectLabel) throws CompilationException {
		Integer labelNext = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTempSlotIndex, LAYOUT.addrSlotIndexValues[slot.index], labelNext);
			emitFarJumpIfNotEqual(codeByteBuffer, slot.addrState, LAYOUT.addrTradingStateValue, rejectLabel);
			codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, slot.addrHashOfSecretA));
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, LAYOUT.addrTempPointer));
			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.CHECK_HASH160_WITH_B, LAYOUT.addrResult,
					LAYOUT.addrTempMessageDataPointer, LAYOUT.addrTempMessageDataLength));
			emitFarJumpIfZero(codeByteBuffer, LAYOUT.addrResult, rejectLabel);

			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrRedeemMessageReceivingAddressOffset));
			codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrTempAddress));
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));
			codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_CONFIGURED_ASSET_ID.value, LAYOUT.addrTempAssetId));
			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, LAYOUT.addrResult,
					LAYOUT.addrTempAssetId, slot.addrFillLocalAmount));
			codeByteBuffer.put(OpCode.ADD_DAT.compile(LAYOUT.addrCompletedLocalAmount, slot.addrFillLocalAmount));
			codeByteBuffer.put(OpCode.DEC_DAT.compile(LAYOUT.addrActiveSlotCount));
			emitClearSlot(codeByteBuffer, slot);
			codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
			labelNext = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitProcessCancelFillSlot(ByteBuffer codeByteBuffer, Slot slot, Integer rejectLabel) throws CompilationException {
		Integer labelNext = null;
		Integer labelCancel = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTempSlotIndex, LAYOUT.addrSlotIndexValues[slot.index], labelNext);
			emitFarJumpIfEqual(codeByteBuffer, slot.addrState, LAYOUT.addrReservedStateValue, labelCancel);
			emitFarJumpIfEqual(codeByteBuffer, slot.addrState, LAYOUT.addrForeignLockedStateValue, labelCancel);
			codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));

			labelCancel = codeByteBuffer.position();
			codeByteBuffer.put(OpCode.ADD_DAT.compile(LAYOUT.addrAvailableLocalAmount, slot.addrFillLocalAmount));
			codeByteBuffer.put(OpCode.DEC_DAT.compile(LAYOUT.addrActiveSlotCount));
			emitClearSlot(codeByteBuffer, slot);
			codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));

			labelNext = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitExtractSlotIndex(ByteBuffer codeByteBuffer) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrZero));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrTempSlotIndex));
	}

	private static void emitClearSlot(ByteBuffer codeByteBuffer, Slot slot) throws CompilationException {
		codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrState, SLOT_EMPTY));
		codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrFillLocalAmount, 0));
		codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrFillForeignAmount, 0));
		codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrLockTimeA, 0));
		codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrRefundTimestamp, 0));
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

	private static void copyBlock(ByteBuffer codeByteBuffer, int sourceStart, int targetStart) throws CompilationException {
		for (int i = 0; i < 4; ++i)
			codeByteBuffer.put(OpCode.SET_DAT.compile(targetStart + i, sourceStart + i));
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

	private static void emitFarJumpIfLess(ByteBuffer codeByteBuffer, int lhs, int rhs, Integer target) throws CompilationException {
		int skip = codeByteBuffer.position() + OpCode.BGE_DAT.compile(lhs, rhs, 0).length + OpCode.JMP_ADR.compile(0).length;
		codeByteBuffer.put(OpCode.BGE_DAT.compile(lhs, rhs, calcOffset(codeByteBuffer, skip)));
		codeByteBuffer.put(OpCode.JMP_ADR.compile(target == null ? 0 : target));
	}

	private static void emitFarJumpIfGreater(ByteBuffer codeByteBuffer, int lhs, int rhs, Integer target) throws CompilationException {
		int skip = codeByteBuffer.position() + OpCode.BLE_DAT.compile(lhs, rhs, 0).length + OpCode.JMP_ADR.compile(0).length;
		codeByteBuffer.put(OpCode.BLE_DAT.compile(lhs, rhs, calcOffset(codeByteBuffer, skip)));
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
		tradeData.localAmount = getLong(dataByteBuffer, LAYOUT.addrTotalLocalAmount);
		tradeData.totalLocalAmount = tradeData.localAmount;
		tradeData.expectedForeignAmount = getLong(dataByteBuffer, LAYOUT.addrTotalForeignAmount);
		tradeData.minFillLocalAmount = getLong(dataByteBuffer, LAYOUT.addrMinFillLocalAmount);
		tradeData.maxFillLocalAmount = getLong(dataByteBuffer, LAYOUT.addrMaxFillLocalAmount);
		tradeData.remainingLocalAmount = getLong(dataByteBuffer, LAYOUT.addrAvailableLocalAmount);
		tradeData.completedLocalAmount = getLong(dataByteBuffer, LAYOUT.addrCompletedLocalAmount);
		tradeData.tradeTimeout = (int) getLong(dataByteBuffer, LAYOUT.addrTradeTimeout);
		tradeData.refundTimeout = (int) getLong(dataByteBuffer, LAYOUT.addrLocalRefundTimeout);
		tradeData.activeFillCount = (int) getLong(dataByteBuffer, LAYOUT.addrActiveSlotCount);
		tradeData.availableFillSlots = SLOT_COUNT - tradeData.activeFillCount;

		long modeValue = getLong(dataByteBuffer, LAYOUT.addrMode);
		tradeData.mode = AcctMode.valueOf((int) (modeValue & 0xffL));
		if (tradeData.mode == null)
			tradeData.mode = AcctMode.OFFERING;

		long activeLocalAmount = 0L;
		for (Slot slot : LAYOUT.slots) {
			long state = getLong(dataByteBuffer, slot.addrState);
			if (state == SLOT_EMPTY)
				continue;

			CrossChainTradeData.Fill fill = new CrossChainTradeData.Fill();
			fill.slotIndex = slot.index;
			fill.partnerAddress = Base58.encode(readBytes(dataByteBuffer, slot.addrPartnerAddress, 25));
			fill.partnerForeignPKH = readBytes(dataByteBuffer, slot.addrPartnerForeignPKH, 20);
			fill.hashOfSecretA = readBytes(dataByteBuffer, slot.addrHashOfSecretA, 20);
			fill.localAmount = getLong(dataByteBuffer, slot.addrFillLocalAmount);
			fill.expectedForeignAmount = getLong(dataByteBuffer, slot.addrFillForeignAmount);
			fill.lockTimeA = (int) getLong(dataByteBuffer, slot.addrLockTimeA);
			long refundTimestamp = getLong(dataByteBuffer, slot.addrRefundTimestamp);
			if (refundTimestamp > 0)
				fill.tradeRefundHeight = new Timestamp(refundTimestamp).blockHeight;
			tradeData.fills.add(fill);

			if (state == SLOT_TRADING)
				activeLocalAmount += fill.localAmount;
		}
		tradeData.activeLocalAmount = activeLocalAmount;

		byte[] chainIdReference = readBytes(dataByteBuffer, LAYOUT.addrForeignBlockchainChainId, Bip122ChainId.REFERENCE_BYTE_LENGTH);
		ForeignBlockchainRegistry.Entry foreignBlockchain = ForeignBlockchainRegistry.fromBitcoinyChainIdReference(chainIdReference);
		if (foreignBlockchain == null || !foreignBlockchain.isBitcoiny()
				|| !Arrays.equals(chainIdReference, foreignBlockchain.getActiveChainIdReferenceBytes()))
			return null;

		tradeData.foreignBlockchain = foreignBlockchain.name();
		return tradeData;
	}

	public static byte[] buildReserveMessage(int slotIndex, byte[] takerForeignPKH, long fillLocalAmount, long fillForeignAmount) {
		byte[] data = new byte[RESERVE_MESSAGE_LENGTH];
		System.arraycopy(BitTwiddling.toBEByteArray((long) slotIndex), 0, data, 0, 8);
		System.arraycopy(takerForeignPKH, 0, data, 8, takerForeignPKH.length);
		System.arraycopy(BitTwiddling.toBEByteArray(fillLocalAmount), 0, data, 40, 8);
		System.arraycopy(BitTwiddling.toBEByteArray(fillForeignAmount), 0, data, 48, 8);
		return data;
	}

	public static byte[] buildForeignLockMessage(int slotIndex, byte[] hashOfSecretA, int lockTimeA) {
		byte[] data = new byte[FOREIGN_LOCK_MESSAGE_LENGTH];
		System.arraycopy(BitTwiddling.toBEByteArray((long) slotIndex), 0, data, 0, 8);
		System.arraycopy(hashOfSecretA, 0, data, 8, hashOfSecretA.length);
		System.arraycopy(BitTwiddling.toBEByteArray((long) lockTimeA), 0, data, 40, 8);
		return data;
	}

	public static byte[] buildLocalLockMessage(int slotIndex) {
		byte[] data = new byte[LOCAL_LOCK_MESSAGE_LENGTH];
		System.arraycopy(BitTwiddling.toBEByteArray((long) slotIndex), 0, data, 0, 8);
		return data;
	}

	public static byte[] buildRedeemMessage(int slotIndex, byte[] secretA, String receivingAddress) {
		byte[] data = new byte[REDEEM_MESSAGE_LENGTH];
		System.arraycopy(BitTwiddling.toBEByteArray((long) slotIndex), 0, data, 0, 8);
		System.arraycopy(secretA, 0, data, 8, secretA.length);
		System.arraycopy(Base58.decode(receivingAddress), 0, data, 40, 25);
		return data;
	}

	@Override
	public byte[] buildCancelMessage(String creatorAddress) {
		byte[] data = new byte[CANCEL_MESSAGE_LENGTH];
		System.arraycopy(Base58.decode(creatorAddress), 0, data, 0, 25);
		return data;
	}

	public static byte[] buildCancelFillMessage(int slotIndex, String creatorOrMakerTradeAddress) {
		byte[] data = new byte[CANCEL_FILL_MESSAGE_LENGTH];
		System.arraycopy(BitTwiddling.toBEByteArray((long) slotIndex), 0, data, 0, 8);
		System.arraycopy(Base58.decode(creatorOrMakerTradeAddress), 0, data, 8, 25);
		return data;
	}

	@Override
	public byte[] findSecretA(Repository repository, CrossChainTradeData crossChainTradeData) throws DataException {
		for (CrossChainTradeData.Fill fill : crossChainTradeData.fills) {
			byte[] secret = findSecretA(repository, crossChainTradeData.atAddress, fill.slotIndex,
					crossChainTradeData.creatorTradeAddress, fill.hashOfSecretA);
			if (secret != null)
				return secret;
		}
		return null;
	}

	public static byte[] findSecretA(Repository repository, String atAddress, int slotIndex, String creatorTradeAddress, byte[] hashOfSecretA)
			throws DataException {
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(null, atAddress, null, null, null);
		if (messageTransactionsData == null)
			return null;

		for (MessageTransactionData messageTransactionData : messageTransactionsData) {
			if (messageTransactionData.isText())
				continue;

			byte[] data = messageTransactionData.getData();
			if (data == null || data.length != REDEEM_MESSAGE_LENGTH)
				continue;

			if ((int) BitTwiddling.longFromBEBytes(data, 0) != slotIndex)
				continue;

			String sender = Crypto.toAddress(messageTransactionData.getCreatorPublicKey());
			if (!creatorTradeAddress.equals(sender))
				continue;

			byte[] secret = Arrays.copyOfRange(data, 8, 40);
			if (Arrays.equals(Crypto.hash160(secret), hashOfSecretA))
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
		final int addrTotalLocalAmount;
		final int addrTotalForeignAmount;
		final int addrMinFillLocalAmount;
		final int addrMaxFillLocalAmount;
		final int addrTradeTimeout;
		final int addrLocalRefundTimeout;
		final int addrMessageTxnType;
		final int addrExpectedReserveMessageLength;
		final int addrExpectedForeignLockMessageLength;
		final int addrExpectedLocalLockMessageLength;
		final int addrExpectedRedeemMessageLength;
		final int addrExpectedCancelMessageLength;
		final int addrExpectedCancelFillMessageLength;
		final int addrCreatorAddressPointer;
		final int addrMessageSenderPointer;
		final int addrTempPointer;
		final int addrTempMessageDataPointer;
		final int addrTempMessageDataLength;
		final int addrForeignLockMessageHashOfSecretAOffset;
		final int addrForeignLockMessageLockTimeOffset;
		final int addrReserveMessagePartnerForeignPKHOffset;
		final int addrReserveMessageFillAmountsOffset;
		final int addrRedeemMessageSecretOffset;
		final int addrRedeemMessageReceivingAddressOffset;
		final int addrZero;
		final int addrOne;
		final int addrReservedStateValue;
		final int addrForeignLockedStateValue;
		final int addrTradingStateValue;
		final int[] addrSlotIndexValues = new int[SLOT_COUNT];
		final int addrCreatorAddress1;
		final int addrLastTxnTimestamp;
		final int addrBlockTimestamp;
		final int addrTxnType;
		final int addrResult;
		final int addrTempAmount;
		final int addrTempPaymentCount;
		final int addrTempPaymentAssetId;
		final int addrMessageSender1;
		final int addrMessageLength;
		final int addrTempAddress;
		final int addrTempData;
		final int addrTempSlotIndex;
		final int addrTempLockTimeA;
		final int addrTempRefundTimestamp;
		final int addrTempFillLocalAmount;
		final int addrTempFillForeignAmount;
		final int addrTempRemainingLocalAmount;
		final int addrTempAssetId;
		final int addrAvailableLocalAmount;
		final int addrCompletedLocalAmount;
		final int addrActiveSlotCount;
		final int addrCancelledFlag;
		final Slot[] slots = new Slot[SLOT_COUNT];
		final int addrMode;
		final int addrForeignBlockchainChainId;
		final int valueCount;

		Layout() {
			int c = 0;
			this.addrCreatorTradeAddress1 = c;
			c += 4;
			this.addrCreatorForeignPKH = c;
			c += 4;
			this.addrTotalLocalAmount = c++;
			this.addrTotalForeignAmount = c++;
			this.addrMinFillLocalAmount = c++;
			this.addrMaxFillLocalAmount = c++;
			this.addrTradeTimeout = c++;
			this.addrLocalRefundTimeout = c++;
			this.addrMessageTxnType = c++;
			this.addrExpectedReserveMessageLength = c++;
			this.addrExpectedForeignLockMessageLength = c++;
			this.addrExpectedLocalLockMessageLength = c++;
			this.addrExpectedRedeemMessageLength = c++;
			this.addrExpectedCancelMessageLength = c++;
			this.addrExpectedCancelFillMessageLength = c++;
			this.addrCreatorAddressPointer = c++;
			this.addrMessageSenderPointer = c++;
			this.addrTempPointer = c++;
			this.addrTempMessageDataPointer = c++;
			this.addrTempMessageDataLength = c++;
			this.addrForeignLockMessageHashOfSecretAOffset = c++;
			this.addrForeignLockMessageLockTimeOffset = c++;
			this.addrReserveMessagePartnerForeignPKHOffset = c++;
			this.addrReserveMessageFillAmountsOffset = c++;
			this.addrRedeemMessageSecretOffset = c++;
			this.addrRedeemMessageReceivingAddressOffset = c++;
			this.addrZero = c++;
			this.addrOne = c++;
			this.addrReservedStateValue = c++;
			this.addrForeignLockedStateValue = c++;
			this.addrTradingStateValue = c++;
			for (int i = 0; i < SLOT_COUNT; ++i)
				this.addrSlotIndexValues[i] = c++;
			this.addrCreatorAddress1 = c;
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
			this.addrTempAddress = c;
			c += 4;
			this.addrTempData = c;
			c += 4;
			this.addrTempSlotIndex = c++;
			this.addrTempLockTimeA = c++;
			this.addrTempRefundTimestamp = c++;
			this.addrTempFillLocalAmount = c++;
			this.addrTempFillForeignAmount = c++;
			this.addrTempRemainingLocalAmount = c++;
			this.addrTempAssetId = c++;
			this.addrAvailableLocalAmount = c++;
			this.addrCompletedLocalAmount = c++;
			this.addrActiveSlotCount = c++;
			this.addrCancelledFlag = c++;
			for (int i = 0; i < SLOT_COUNT; ++i) {
				this.slots[i] = new Slot(i, c);
				c = this.slots[i].nextAddress;
			}
			this.addrMode = c++;
			this.addrForeignBlockchainChainId = c;
			c += Bip122ChainId.REFERENCE_BYTE_LENGTH / MachineState.VALUE_SIZE;
			this.valueCount = c;
		}
	}

	private static class Slot {
		final int index;
		final int addrState;
		final int addrPartnerAddress;
		final int addrPartnerForeignPKH;
		final int addrHashOfSecretA;
		final int addrLockTimeA;
		final int addrRefundTimestamp;
		final int addrFillLocalAmount;
		final int addrFillForeignAmount;
		final int nextAddress;

		Slot(int index, int startAddress) {
			this.index = index;
			int c = startAddress;
			this.addrState = c++;
			this.addrPartnerAddress = c;
			c += 4;
			this.addrPartnerForeignPKH = c;
			c += 4;
			this.addrHashOfSecretA = c;
			c += 4;
			this.addrLockTimeA = c++;
			this.addrRefundTimestamp = c++;
			this.addrFillLocalAmount = c++;
			this.addrFillForeignAmount = c++;
			this.nextAddress = c;
		}
	}
}
