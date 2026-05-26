package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.ciyam.at.*;
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

public class BitcoinyACCTv4 implements ACCT {

	public static final String NAME = BitcoinyACCTv4.class.getSimpleName();
	public static final byte[] CODE_BYTES_HASH = HashCode.fromString("ac36e0c182969d65171c8e73d6b9ac962c8504d4b6696b3a5a5540f975d2834a").asBytes();

	public static final int SECRET_LENGTH = 32;
	public static final int SLOT_COUNT = 8;

	private static final int SLOT_EMPTY = 0;
	private static final int SLOT_ACTIVE = 1;

	private static final Layout LAYOUT = new Layout();

	private static final int MODE_VALUE_OFFSET = LAYOUT.addrMode;
	public static final int MODE_BYTE_OFFSET = MachineState.HEADER_LENGTH + MODE_VALUE_OFFSET * MachineState.VALUE_SIZE;

	public static class OfferMessageData {
		public byte[] partnerForeignPKH;
		public byte[] hashOfSecretA;
		public long lockTimeA;
		public long fillLocalAmount;
		public long fillForeignAmount;
	}

	public static final int OFFER_MESSAGE_LENGTH = 20 /*partnerForeignPKH*/ + 20 /*hashOfSecretA*/ + 8 /*lockTimeA*/
			+ 8 /*fillLocalAmount*/ + 8 /*fillForeignAmount*/;
	public static final int TRADE_MESSAGE_LENGTH = 32 /*partner local-chain trade address*/
			+ 32 /*partner foreign PKH padded from 20*/
			+ 32 /*hash of secret-A padded from 20*/
			+ 8 /*slot index*/
			+ 8 /*lockTimeA*/
			+ 8 /*refund timeout*/
			+ 8 /*fill local amount*/
			+ 8 /*fill foreign amount*/;
	public static final int REDEEM_MESSAGE_LENGTH = 8 /*slot index*/ + 32 /*secret-A*/ + 32 /*receiving address*/;
	public static final int CANCEL_MESSAGE_LENGTH = 32 /*creator address*/;

	private static BitcoinyACCTv4 instance;

	private BitcoinyACCTv4() {
	}

	public static synchronized BitcoinyACCTv4 getInstance() {
		if (instance == null)
			instance = new BitcoinyACCTv4();

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
			byte[] foreignPublicKeyHash, long totalLocalAmount, long totalForeignAmount, long minFillLocalAmount,
			long maxFillLocalAmount, int tradeTimeout) {
		if (foreignBlockchain == null || !foreignBlockchain.isBitcoiny())
			throw new IllegalArgumentException("Unsupported Bitcoiny blockchain");

		if (foreignPublicKeyHash.length != 20)
			throw new IllegalArgumentException("Foreign public key hash should be 20 bytes");

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(LAYOUT.valueCount * MachineState.VALUE_SIZE);

		putBytes(dataByteBuffer, LAYOUT.addrCreatorTradeAddress1, Base58.decode(creatorTradeAddress));
		putBytes(dataByteBuffer, LAYOUT.addrForeignPublicKeyHash, foreignPublicKeyHash);
		putLong(dataByteBuffer, LAYOUT.addrTotalLocalAmount, totalLocalAmount);
		putLong(dataByteBuffer, LAYOUT.addrTotalForeignAmount, totalForeignAmount);
		putLong(dataByteBuffer, LAYOUT.addrMinFillLocalAmount, minFillLocalAmount);
		putLong(dataByteBuffer, LAYOUT.addrMaxFillLocalAmount, maxFillLocalAmount);
		putLong(dataByteBuffer, LAYOUT.addrTradeTimeout, tradeTimeout);
		putLong(dataByteBuffer, LAYOUT.addrAvailableLocalAmount, totalLocalAmount);
		putLong(dataByteBuffer, LAYOUT.addrMessageTxnType, API.ATTransactionType.MESSAGE.value);
		putLong(dataByteBuffer, LAYOUT.addrExpectedTradeMessageLength, TRADE_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedRedeemMessageLength, REDEEM_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrExpectedCancelMessageLength, CANCEL_MESSAGE_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrCreatorAddressPointer, LAYOUT.addrCreatorAddress1);
		putLong(dataByteBuffer, LAYOUT.addrMessageSenderPointer, LAYOUT.addrMessageSender1);
		putLong(dataByteBuffer, LAYOUT.addrTempMessageDataPointer, LAYOUT.addrTempMessageData);
		putLong(dataByteBuffer, LAYOUT.addrTempMessageDataLength, SECRET_LENGTH);
		putLong(dataByteBuffer, LAYOUT.addrTradeMessagePartnerForeignPKHOffset, 32);
		putLong(dataByteBuffer, LAYOUT.addrTradeMessageHashOfSecretAOffset, 64);
		putLong(dataByteBuffer, LAYOUT.addrTradeMessageValuesOffset, 96);
		putLong(dataByteBuffer, LAYOUT.addrTradeMessageFillForeignOffset, 128);
		putLong(dataByteBuffer, LAYOUT.addrRedeemMessageSecretOffset, 8);
		putLong(dataByteBuffer, LAYOUT.addrRedeemMessageReceivingAddressOffset, 40);
		putLong(dataByteBuffer, LAYOUT.addrZero, 0);
		putLong(dataByteBuffer, LAYOUT.addrActiveStateValue, SLOT_ACTIVE);
		for (int i = 0; i < SLOT_COUNT; ++i)
			putLong(dataByteBuffer, LAYOUT.addrSlotIndexValues[i], i);
		putRawBytes(dataByteBuffer, LAYOUT.addrForeignBlockchainChainId, foreignBlockchain.getActiveChainIdReferenceBytes());

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(8192);

		Integer labelLoop = null;
		Integer labelProcessTxn = null;
		Integer labelProcessMessages = null;
		Integer labelStop = null;
		Integer labelCheckTrade = null;
		Integer labelCheckRedeem = null;
		Integer labelCheckCancel = null;

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
					emitRefundExpiredSlot(codeByteBuffer, slot);

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
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedTradeMessageLength, labelCheckTrade);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedRedeemMessageLength, labelCheckRedeem);
				emitFarJumpIfEqual(codeByteBuffer, LAYOUT.addrMessageLength, LAYOUT.addrExpectedCancelMessageLength, labelCheckCancel);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckTrade = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorTradeAddress1, labelLoop);
				emitFarJumpIfNonZero(codeByteBuffer, LAYOUT.addrCancelledFlag, labelLoop);
				emitExtractTradeMessage(codeByteBuffer);
				for (Slot slot : LAYOUT.slots)
					emitProcessTradeSlot(codeByteBuffer, slot, labelLoop);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckRedeem = codeByteBuffer.position();
				emitExtractRedeemPrefix(codeByteBuffer);
				for (Slot slot : LAYOUT.slots)
					emitProcessRedeemSlot(codeByteBuffer, slot, labelLoop);
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelCheckCancel = codeByteBuffer.position();
				emitCheckSender(codeByteBuffer, LAYOUT.addrCreatorAddress1, labelLoop);
				codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrCancelledFlag, 1));
				codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrMode, AcctMode.CANCELLED.value));
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelLoop == null ? 0 : labelLoop));

				labelStop = codeByteBuffer.position();
				codeByteBuffer.put(OpCode.STP_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile Bitcoiny split-fill ACCT?", e);
			}
		}

		codeByteBuffer.flip();
		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		assert Arrays.equals(Crypto.digest(codeBytes), BitcoinyACCTv4.CODE_BYTES_HASH)
				: String.format("BitcoinyACCTv4.CODE_BYTES_HASH mismatch: expected %s, actual %s", HashCode.fromBytes(CODE_BYTES_HASH), HashCode.fromBytes(Crypto.digest(codeBytes)));

		return MachineState.toCreationBytes((short) 2, codeBytes, dataByteBuffer.array(), (short) 0, (short) 0, 0L);
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

	private static void emitRefundExpiredSlot(ByteBuffer codeByteBuffer, Slot slot) throws CompilationException {
		Integer labelSkip = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			codeByteBuffer.put(OpCode.BNE_DAT.compile(slot.addrState, LAYOUT.addrActiveStateValue, calcOffset(codeByteBuffer, labelSkip)));
			codeByteBuffer.put(OpCode.BLT_DAT.compile(LAYOUT.addrBlockTimestamp, slot.addrRefundTimestamp, calcOffset(codeByteBuffer, labelSkip)));
			codeByteBuffer.put(OpCode.ADD_DAT.compile(LAYOUT.addrAvailableLocalAmount, slot.addrFillLocalAmount));
			codeByteBuffer.put(OpCode.DEC_DAT.compile(LAYOUT.addrActiveSlotCount));
			codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrState, SLOT_EMPTY));
			codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrFillLocalAmount, 0));
			labelSkip = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitExtractTradeMessage(ByteBuffer codeByteBuffer) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrTempAddress));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrTradeMessagePartnerForeignPKHOffset));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrTempForeignPKH));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrTradeMessageHashOfSecretAOffset));
		codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrTempHashOfSecretA));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrTradeMessageValuesOffset));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrTempSlotIndex));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B2, LAYOUT.addrTempLockTimeA));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B3, LAYOUT.addrTempRefundTimeout));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B4, LAYOUT.addrTempFillLocalAmount));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrTradeMessageFillForeignOffset));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrTempFillForeignAmount));
	}

	private static void emitProcessTradeSlot(ByteBuffer codeByteBuffer, Slot slot, Integer rejectLabel) throws CompilationException {
		Integer labelNext = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTempSlotIndex, LAYOUT.addrSlotIndexValues[slot.index], labelNext);
			emitFarJumpIfNotEqual(codeByteBuffer, slot.addrState, LAYOUT.addrZero, rejectLabel);
			emitFarJumpIfLess(codeByteBuffer, LAYOUT.addrTempFillLocalAmount, LAYOUT.addrMinFillLocalAmount, rejectLabel);
			emitFarJumpIfGreater(codeByteBuffer, LAYOUT.addrTempFillLocalAmount, LAYOUT.addrMaxFillLocalAmount, rejectLabel);
			emitFarJumpIfGreater(codeByteBuffer, LAYOUT.addrTempFillLocalAmount, LAYOUT.addrAvailableLocalAmount, rejectLabel);
			emitFarJumpIfLessOrEqual(codeByteBuffer, LAYOUT.addrTempFillForeignAmount, LAYOUT.addrZero, rejectLabel);

			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, LAYOUT.addrTempRefundTimestamp,
					LAYOUT.addrLastTxnTimestamp, LAYOUT.addrTempRefundTimeout));
			copyBlock(codeByteBuffer, LAYOUT.addrTempAddress, slot.addrPartnerAddress);
			copyBlock(codeByteBuffer, LAYOUT.addrTempForeignPKH, slot.addrPartnerForeignPKH);
			copyBlock(codeByteBuffer, LAYOUT.addrTempHashOfSecretA, slot.addrHashOfSecretA);
			codeByteBuffer.put(OpCode.SET_DAT.compile(slot.addrLockTimeA, LAYOUT.addrTempLockTimeA));
			codeByteBuffer.put(OpCode.SET_DAT.compile(slot.addrRefundTimestamp, LAYOUT.addrTempRefundTimestamp));
			codeByteBuffer.put(OpCode.SET_DAT.compile(slot.addrFillLocalAmount, LAYOUT.addrTempFillLocalAmount));
			codeByteBuffer.put(OpCode.SET_DAT.compile(slot.addrFillForeignAmount, LAYOUT.addrTempFillForeignAmount));
			codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrState, SLOT_ACTIVE));
			codeByteBuffer.put(OpCode.SUB_DAT.compile(LAYOUT.addrAvailableLocalAmount, LAYOUT.addrTempFillLocalAmount));
			codeByteBuffer.put(OpCode.INC_DAT.compile(LAYOUT.addrActiveSlotCount));
			codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
			labelNext = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
	}

	private static void emitExtractRedeemPrefix(ByteBuffer codeByteBuffer) throws CompilationException {
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrZero));
		codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B1, LAYOUT.addrTempSlotIndex));
		codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrMessageSenderPointer));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrRedeemMessageSecretOffset));
		codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempMessageDataPointer));
	}

	private static void emitProcessRedeemSlot(ByteBuffer codeByteBuffer, Slot slot, Integer rejectLabel) throws CompilationException {
		Integer labelNext = null;
		for (int pass = 0; pass < 2; ++pass) {
			int start = codeByteBuffer.position();
			emitFarJumpIfNotEqual(codeByteBuffer, LAYOUT.addrTempSlotIndex, LAYOUT.addrSlotIndexValues[slot.index], labelNext);
			emitFarJumpIfNotEqual(codeByteBuffer, slot.addrState, LAYOUT.addrActiveStateValue, rejectLabel);
			emitFarJumpIfAddressNotEqual(codeByteBuffer, LAYOUT.addrMessageSender1, slot.addrPartnerAddress, rejectLabel);
			codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, slot.addrHashOfSecretA));
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, LAYOUT.addrTempPointer));
			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.CHECK_HASH160_WITH_B, LAYOUT.addrResult,
					LAYOUT.addrTempMessageDataPointer, LAYOUT.addrTempMessageDataLength));
			emitFarJumpIfZero(codeByteBuffer, LAYOUT.addrResult, rejectLabel);
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, LAYOUT.addrRedeemMessageReceivingAddressOffset));
			codeByteBuffer.put(OpCode.SET_VAL.compile(LAYOUT.addrTempPointer, LAYOUT.addrTempReceivingAddress));
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, LAYOUT.addrTempPointer));
			codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_CONFIGURED_ASSET_ID.value, LAYOUT.addrTempAssetId));
			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, LAYOUT.addrResult,
					LAYOUT.addrTempAssetId, slot.addrFillLocalAmount));
			codeByteBuffer.put(OpCode.ADD_DAT.compile(LAYOUT.addrCompletedLocalAmount, slot.addrFillLocalAmount));
			codeByteBuffer.put(OpCode.DEC_DAT.compile(LAYOUT.addrActiveSlotCount));
			codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrState, SLOT_EMPTY));
			codeByteBuffer.put(OpCode.SET_VAL.compile(slot.addrFillLocalAmount, 0));
			codeByteBuffer.put(OpCode.JMP_ADR.compile(rejectLabel == null ? 0 : rejectLabel));
			labelNext = codeByteBuffer.position();
			if (pass == 0)
				codeByteBuffer.position(start);
		}
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
		tradeData.tradeDirection = TradeDirection.SELL_LOCAL;
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
		tradeData.creatorForeignPKH = readBytes(dataByteBuffer, LAYOUT.addrForeignPublicKeyHash, 20);
		tradeData.localAmount = getLong(dataByteBuffer, LAYOUT.addrTotalLocalAmount);
		tradeData.totalLocalAmount = tradeData.localAmount;
		tradeData.expectedForeignAmount = getLong(dataByteBuffer, LAYOUT.addrTotalForeignAmount);
		tradeData.minFillLocalAmount = getLong(dataByteBuffer, LAYOUT.addrMinFillLocalAmount);
		tradeData.maxFillLocalAmount = getLong(dataByteBuffer, LAYOUT.addrMaxFillLocalAmount);
		tradeData.remainingLocalAmount = getLong(dataByteBuffer, LAYOUT.addrAvailableLocalAmount);
		tradeData.completedLocalAmount = getLong(dataByteBuffer, LAYOUT.addrCompletedLocalAmount);
		tradeData.tradeTimeout = (int) getLong(dataByteBuffer, LAYOUT.addrTradeTimeout);
		tradeData.activeFillCount = (int) getLong(dataByteBuffer, LAYOUT.addrActiveSlotCount);
		tradeData.availableFillSlots = SLOT_COUNT - tradeData.activeFillCount;

		long modeValue = getLong(dataByteBuffer, LAYOUT.addrMode);
		tradeData.mode = AcctMode.valueOf((int) (modeValue & 0xffL));
		if (tradeData.mode == null)
			tradeData.mode = AcctMode.OFFERING;

		long activeLocalAmount = 0L;
		for (Slot slot : LAYOUT.slots) {
			if (getLong(dataByteBuffer, slot.addrState) != SLOT_ACTIVE)
				continue;

			CrossChainTradeData.Fill fill = new CrossChainTradeData.Fill();
			fill.slotIndex = slot.index;
			fill.partnerAddress = Base58.encode(readBytes(dataByteBuffer, slot.addrPartnerAddress, 25));
			fill.partnerForeignPKH = readBytes(dataByteBuffer, slot.addrPartnerForeignPKH, 20);
			fill.hashOfSecretA = readBytes(dataByteBuffer, slot.addrHashOfSecretA, 20);
			fill.localAmount = getLong(dataByteBuffer, slot.addrFillLocalAmount);
			fill.expectedForeignAmount = getLong(dataByteBuffer, slot.addrFillForeignAmount);
			fill.lockTimeA = (int) getLong(dataByteBuffer, slot.addrLockTimeA);
			fill.tradeRefundHeight = new Timestamp(getLong(dataByteBuffer, slot.addrRefundTimestamp)).blockHeight;
			tradeData.fills.add(fill);
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

	public static OfferMessageData extractOfferMessageData(byte[] messageData) {
		if (messageData == null || messageData.length != OFFER_MESSAGE_LENGTH)
			return null;

		OfferMessageData offerMessageData = new OfferMessageData();
		offerMessageData.partnerForeignPKH = Arrays.copyOfRange(messageData, 0, 20);
		offerMessageData.hashOfSecretA = Arrays.copyOfRange(messageData, 20, 40);
		offerMessageData.lockTimeA = BitTwiddling.longFromBEBytes(messageData, 40);
		offerMessageData.fillLocalAmount = BitTwiddling.longFromBEBytes(messageData, 48);
		offerMessageData.fillForeignAmount = BitTwiddling.longFromBEBytes(messageData, 56);
		return offerMessageData;
	}

	public static byte[] buildOfferMessage(byte[] partnerForeignPKH, byte[] hashOfSecretA, int lockTimeA,
			long fillLocalAmount, long fillForeignAmount) {
		return Bytes.concat(partnerForeignPKH, hashOfSecretA, BitTwiddling.toBEByteArray((long) lockTimeA),
				BitTwiddling.toBEByteArray(fillLocalAmount), BitTwiddling.toBEByteArray(fillForeignAmount));
	}

	public static byte[] buildTradeMessage(int slotIndex, String partnerTradeAddress, byte[] partnerForeignPKH,
			byte[] hashOfSecretA, int lockTimeA, int refundTimeout, long fillLocalAmount, long fillForeignAmount) {
		byte[] data = new byte[TRADE_MESSAGE_LENGTH];
		System.arraycopy(Base58.decode(partnerTradeAddress), 0, data, 0, 25);
		System.arraycopy(partnerForeignPKH, 0, data, 32, partnerForeignPKH.length);
		System.arraycopy(hashOfSecretA, 0, data, 64, hashOfSecretA.length);
		System.arraycopy(BitTwiddling.toBEByteArray((long) slotIndex), 0, data, 96, 8);
		System.arraycopy(BitTwiddling.toBEByteArray((long) lockTimeA), 0, data, 104, 8);
		System.arraycopy(BitTwiddling.toBEByteArray((long) refundTimeout), 0, data, 112, 8);
		System.arraycopy(BitTwiddling.toBEByteArray(fillLocalAmount), 0, data, 120, 8);
		System.arraycopy(BitTwiddling.toBEByteArray(fillForeignAmount), 0, data, 128, 8);
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

	public static int calcRefundTimeout(long offerMessageTimestamp, int lockTimeA) {
		return (int) ((lockTimeA - (offerMessageTimestamp / 1000L)) / 2L / 60L);
	}

	@Override
	public byte[] findSecretA(Repository repository, CrossChainTradeData crossChainTradeData) throws DataException {
		for (CrossChainTradeData.Fill fill : crossChainTradeData.fills) {
			byte[] secret = findSecretA(repository, crossChainTradeData.atAddress, fill.slotIndex, fill.partnerAddress, fill.hashOfSecretA);
			if (secret != null)
				return secret;
		}
		return null;
	}

	public static byte[] findSecretA(Repository repository, String atAddress, int slotIndex, String partnerAddress, byte[] hashOfSecretA) throws DataException {
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
			if (!partnerAddress.equals(sender))
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
		final int addrForeignPublicKeyHash;
		final int addrTotalLocalAmount;
		final int addrTotalForeignAmount;
		final int addrMinFillLocalAmount;
		final int addrMaxFillLocalAmount;
		final int addrTradeTimeout;
		final int addrMessageTxnType;
		final int addrExpectedTradeMessageLength;
		final int addrExpectedRedeemMessageLength;
		final int addrExpectedCancelMessageLength;
		final int addrCreatorAddressPointer;
		final int addrMessageSenderPointer;
		final int addrTempMessageDataPointer;
		final int addrTempMessageDataLength;
		final int addrTradeMessagePartnerForeignPKHOffset;
		final int addrTradeMessageHashOfSecretAOffset;
		final int addrTradeMessageValuesOffset;
		final int addrTradeMessageFillForeignOffset;
		final int addrRedeemMessageSecretOffset;
		final int addrRedeemMessageReceivingAddressOffset;
		final int addrZero;
		final int addrActiveStateValue;
		final int[] addrSlotIndexValues = new int[SLOT_COUNT];
		final int addrCreatorAddress1;
		final int addrLastTxnTimestamp;
		final int addrBlockTimestamp;
		final int addrTxnType;
		final int addrResult;
		final int addrMessageSender1;
		final int addrMessageLength;
		final int addrTempPointer;
		final int addrTempAddress;
		final int addrTempForeignPKH;
		final int addrTempHashOfSecretA;
		final int addrTempReceivingAddress;
		final int addrTempMessageData;
		final int addrTempSlotIndex;
		final int addrTempLockTimeA;
		final int addrTempRefundTimeout;
		final int addrTempRefundTimestamp;
		final int addrTempFillLocalAmount;
		final int addrTempFillForeignAmount;
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
			this.addrForeignPublicKeyHash = c;
			c += 4;
			this.addrTotalLocalAmount = c++;
			this.addrTotalForeignAmount = c++;
			this.addrMinFillLocalAmount = c++;
			this.addrMaxFillLocalAmount = c++;
			this.addrTradeTimeout = c++;
			this.addrMessageTxnType = c++;
			this.addrExpectedTradeMessageLength = c++;
			this.addrExpectedRedeemMessageLength = c++;
			this.addrExpectedCancelMessageLength = c++;
			this.addrCreatorAddressPointer = c++;
			this.addrMessageSenderPointer = c++;
			this.addrTempMessageDataPointer = c++;
			this.addrTempMessageDataLength = c++;
			this.addrTradeMessagePartnerForeignPKHOffset = c++;
			this.addrTradeMessageHashOfSecretAOffset = c++;
			this.addrTradeMessageValuesOffset = c++;
			this.addrTradeMessageFillForeignOffset = c++;
			this.addrRedeemMessageSecretOffset = c++;
			this.addrRedeemMessageReceivingAddressOffset = c++;
			this.addrZero = c++;
			this.addrActiveStateValue = c++;
			for (int i = 0; i < SLOT_COUNT; ++i)
				this.addrSlotIndexValues[i] = c++;
			this.addrCreatorAddress1 = c;
			c += 4;
			this.addrLastTxnTimestamp = c++;
			this.addrBlockTimestamp = c++;
			this.addrTxnType = c++;
			this.addrResult = c++;
			this.addrMessageSender1 = c;
			c += 4;
			this.addrMessageLength = c++;
			this.addrTempPointer = c++;
			this.addrTempAddress = c;
			c += 4;
			this.addrTempForeignPKH = c;
			c += 4;
			this.addrTempHashOfSecretA = c;
			c += 4;
			this.addrTempReceivingAddress = c;
			c += 4;
			this.addrTempMessageData = c;
			c += 4;
			this.addrTempSlotIndex = c++;
			this.addrTempLockTimeA = c++;
			this.addrTempRefundTimeout = c++;
			this.addrTempRefundTimestamp = c++;
			this.addrTempFillLocalAmount = c++;
			this.addrTempFillForeignAmount = c++;
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
