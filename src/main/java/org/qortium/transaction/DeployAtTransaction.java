package org.qortium.transaction;

import com.google.common.base.Utf8;
import org.ciyam.at.MachineState;
import org.ciyam.at.Timestamp;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.at.AT;
import org.qortium.at.ChainATAPI;
import org.qortium.at.ChainAtLoggerFactory;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.asset.AssetData;
import org.qortium.data.at.ATData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Amounts;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

public class DeployAtTransaction extends Transaction {

	// Properties
	private DeployAtTransactionData deployAtTransactionData;

	// Other useful constants
	public static final int MAX_NAME_SIZE = 200;
	public static final int MAX_DESCRIPTION_SIZE = 2000;
	public static final int MAX_AT_TYPE_SIZE = 200;
	public static final int MAX_TAGS_SIZE = 200;
	public static final int MAX_CREATION_BYTES_SIZE = 8192;
	public static final int MAX_CODE_BYTES_LENGTH = 8192;
	public static final int MAX_AT_STATE_LENGTH = 2048;

	// Constructors

	public DeployAtTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.deployAtTransactionData = (DeployAtTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.deployAtTransactionData.getAtAddress());
	}

	/** Returns AT version from the header bytes */
	private short getVersion() {
		byte[] creationBytes = deployAtTransactionData.getCreationBytes();
		return (short) ((creationBytes[0] << 8) | (creationBytes[1] & 0xff)); // Big-endian
	}

	/** Make sure deployATTransactionData has an ATAddress */
	public static void ensureATAddress(DeployAtTransactionData deployAtTransactionData) throws DataException {
		if (deployAtTransactionData.getAtAddress() != null)
			return;

		// Use transaction transformer
		try {
			String atAddress = Crypto.toATAddress(TransactionTransformer.toBytesForSigning(deployAtTransactionData));
			deployAtTransactionData.setAtAddress(atAddress);
		} catch (TransformationException e) {
			throw new DataException("Unable to generate AT address");
		}
	}

	// Navigation

	public Account getATAccount() throws DataException {
		ensureATAddress(this.deployAtTransactionData);

		return new Account(this.repository, this.deployAtTransactionData.getAtAddress());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check name size bounds
		int nameLength = Utf8.encodedLength(this.deployAtTransactionData.getName());
		if (nameLength < 1 || nameLength > MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check description size bounds
		int descriptionlength = Utf8.encodedLength(this.deployAtTransactionData.getDescription());
		if (descriptionlength < 1 || descriptionlength > MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		// Check AT-type size bounds
		int atTypeLength = Utf8.encodedLength(this.deployAtTransactionData.getAtType());
		if (atTypeLength < 1 || atTypeLength > MAX_AT_TYPE_SIZE)
			return ValidationResult.INVALID_AT_TYPE_LENGTH;

		// Check tags size bounds
		int tagsLength = Utf8.encodedLength(this.deployAtTransactionData.getTags());
		if (tagsLength < 1 || tagsLength > MAX_TAGS_SIZE)
			return ValidationResult.INVALID_TAGS_LENGTH;

		long amount = this.deployAtTransactionData.getAmount();
		long nativeFeeReserve = this.deployAtTransactionData.getNativeFeeReserve();

		// Check deployment funding is not negative
		if (amount < 0 || nativeFeeReserve < 0)
			return ValidationResult.NEGATIVE_AMOUNT;

		// ATs need at least a working balance or a native fee reserve
		if (amount == 0 && nativeFeeReserve == 0)
			return ValidationResult.INVALID_AMOUNT;

		long assetId = this.deployAtTransactionData.getAssetId();
		AssetData assetData = this.repository.getAssetRepository().fromAssetId(assetId);
		// Check asset even exists
		if (assetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

			// Non-native ATs only require native asset if a transaction fee, native fee reserve or AT step fees need it.
			// From deployAtWorkingAssetHeight the feePerStep clause is dropped: runtime waives step fees for ATs with a
			// non-native working asset (ChainATAPI.getFeePerStep), so chains without a native asset can still deploy them.
			boolean deployAtWorkingAssetEnabled = this.repository.getBlockRepository().getBlockchainHeight() + 1
					>= BlockChain.getInstance().getDeployAtWorkingAssetHeight();
			boolean nativeFundingRequired = assetId != Asset.NATIVE
					&& (this.deployAtTransactionData.getFee() > 0
					|| nativeFeeReserve > 0
					|| (!deployAtWorkingAssetEnabled && BlockChain.getInstance().getCiyamAtSettings().feePerStep > 0));
			if (nativeFundingRequired && this.repository.getAssetRepository().fromAssetId(Asset.NATIVE) == null)
				return ValidationResult.ASSET_DOES_NOT_EXIST;

		// Unspendable assets are not valid
		if (assetData.isUnspendable())
			return ValidationResult.ASSET_NOT_SPENDABLE;

		// Check asset amount is integer if asset is not divisible
		if (!assetData.isDivisible() && amount % Amounts.MULTIPLIER != 0)
			return ValidationResult.INVALID_AMOUNT;

		Account creator = this.getCreator();

		// Check creator has enough funds
		if (assetId == Asset.NATIVE) {
			// Simple case: amount, native fee reserve and fee are all in native asset
			long minimumBalance = this.deployAtTransactionData.getFee() + amount + nativeFeeReserve;

			if (creator.getConfirmedBalance(Asset.NATIVE) < minimumBalance)
				return ValidationResult.NO_BALANCE;
		} else {
			if (creator.getConfirmedBalance(Asset.NATIVE) < this.deployAtTransactionData.getFee() + nativeFeeReserve)
				return ValidationResult.NO_BALANCE;

			if (creator.getConfirmedBalance(assetId) < amount)
				return ValidationResult.NO_BALANCE;
		}

		// Check version from creation bytes
		if (this.getVersion() < 2)
			return ValidationResult.INVALID_CREATION_BYTES;

		// Check creation bytes are valid (for v2+)
		ensureATAddress(this.deployAtTransactionData);

		// Just enough AT data to allow API to query initial balances, etc.
		String atAddress = this.deployAtTransactionData.getAtAddress();
		byte[] creatorPublicKey = this.deployAtTransactionData.getCreatorPublicKey();
		long creation = this.deployAtTransactionData.getTimestamp();
		ATData skeletonAtData = new ATData(atAddress, creatorPublicKey, creation, assetId);

		int height = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		long blockTimestamp = Timestamp.toLong(height, 0);

		ChainATAPI api = new ChainATAPI(repository, skeletonAtData, blockTimestamp);
		ChainAtLoggerFactory loggerFactory = ChainAtLoggerFactory.getInstance();

		try {
			MachineState state = new MachineState(api, loggerFactory, this.deployAtTransactionData.getCreationBytes());

			byte[] codeBytes = state.getCodeBytes();
			if (codeBytes == null || codeBytes.length > MAX_CODE_BYTES_LENGTH)
				return ValidationResult.INVALID_CREATION_BYTES;

			byte[] atStateBytes = state.toBytes();
			if (atStateBytes == null || atStateBytes.length > MAX_AT_STATE_LENGTH)
				return ValidationResult.INVALID_CREATION_BYTES;

			// The serialization above has empty stacks and zeroed registers, so it understates how large
			// this AT's state can become once it runs. Bound the worst case too, otherwise an oversized
			// state only surfaces at runtime, where it cannot be rejected without stalling block processing.
			if (height >= BlockChain.getInstance().getAtPayoutSolvencyHeight()
					&& worstCaseStateLength(atStateBytes.length, this.deployAtTransactionData.getCreationBytes()) > MAX_AT_STATE_LENGTH)
				return ValidationResult.INVALID_CREATION_BYTES;
		} catch (IllegalArgumentException e) {
			// Not valid
			return ValidationResult.INVALID_CREATION_BYTES;
		}

		return ValidationResult.OK;
	}

	/**
	 * Upper bound on how large this AT's serialized state can grow at runtime.
	 *
	 * <p>Adds everything the empty deploy-time serialization omits: both stacks filled to the depth the
	 * creation-bytes header declares, non-zero A and B registers, an on-error address, a sleep-until height
	 * and a frozen balance. Stack page counts live in the header and are not otherwise bounded by
	 * creation-bytes size, so a small AT can legally declare very deep stacks.
	 */
	private static int worstCaseStateLength(int emptyStateLength, byte[] creationBytes) {
		if (creationBytes == null || creationBytes.length < MachineState.HEADER_LENGTH)
			return emptyStateLength;

		ByteBuffer header = ByteBuffer.wrap(creationBytes, 0, MachineState.HEADER_LENGTH);
		header.getShort(); // version
		header.getShort(); // reserved
		header.getShort(); // numCodePages
		header.getShort(); // numDataPages
		int numCallStackPages = header.getShort() & 0xffff;
		int numUserStackPages = header.getShort() & 0xffff;

		long worstCase = (long) emptyStateLength
				+ (long) numCallStackPages * MachineState.ADDRESS_SIZE
				+ (long) numUserStackPages * MachineState.VALUE_SIZE
				+ 2L * MachineState.AB_REGISTER_SIZE
				+ MachineState.ADDRESS_SIZE  // onErrorAddress (settable via ERR_ADR)
				+ MachineState.ADDRESS_SIZE  // sleepUntilHeight
				+ MachineState.VALUE_SIZE;   // frozenBalance

		return worstCase > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) worstCase;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		Account creator = getCreator();
		long assetId = this.deployAtTransactionData.getAssetId();
		long amount = this.deployAtTransactionData.getAmount();
		long nativeFeeReserve = this.deployAtTransactionData.getNativeFeeReserve();

		// Check creator has enough funds
		if (assetId == Asset.NATIVE) {
			// Simple case: amount, native fee reserve and fee are all in native asset
			long minimumBalance = this.deployAtTransactionData.getFee() + amount + nativeFeeReserve;

			if (creator.getConfirmedBalance(Asset.NATIVE) < minimumBalance)
				return ValidationResult.NO_BALANCE;
		} else {
			if (creator.getConfirmedBalance(Asset.NATIVE) < this.deployAtTransactionData.getFee() + nativeFeeReserve)
				return ValidationResult.NO_BALANCE;

			if (creator.getConfirmedBalance(assetId) < amount)
				return ValidationResult.NO_BALANCE;
		}

		// Check AT doesn't already exist
		if (this.repository.getATRepository().exists(this.deployAtTransactionData.getAtAddress()))
			return ValidationResult.AT_ALREADY_EXISTS;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		ensureATAddress(this.deployAtTransactionData);

		// Deploy AT, saving into repository
		AT at = new AT(this.repository, this.deployAtTransactionData);
		at.deploy();

		long assetId = this.deployAtTransactionData.getAssetId();
		long amount = this.deployAtTransactionData.getAmount();
		long nativeFeeReserve = this.deployAtTransactionData.getNativeFeeReserve();

		// Update creator's balance regarding initial payment to AT
		Account creator = getCreator();
		if (amount != 0)
			creator.modifyAssetBalance(assetId, - amount);

		if (nativeFeeReserve != 0)
			creator.modifyAssetBalance(Asset.NATIVE, - nativeFeeReserve);

		// Create AT account without mutating the deprecated reference field
		Account atAccount = this.getATAccount();
		atAccount.ensureAccount();

		// Update AT's balance
		if (amount != 0)
			atAccount.modifyAssetBalance(assetId, amount);

		if (nativeFeeReserve != 0)
			atAccount.modifyAssetBalance(Asset.NATIVE, nativeFeeReserve);
	}

	@Override
	public void orphan() throws DataException {
		// Delete AT from repository
		AT at = new AT(this.repository, this.deployAtTransactionData);
		at.undeploy();

		long assetId = this.deployAtTransactionData.getAssetId();
		long amount = this.deployAtTransactionData.getAmount();
		long nativeFeeReserve = this.deployAtTransactionData.getNativeFeeReserve();

		// Update creator's balance regarding initial payment to AT
		Account creator = getCreator();
		if (amount != 0)
			creator.modifyAssetBalance(assetId, amount);

		if (nativeFeeReserve != 0)
			creator.modifyAssetBalance(Asset.NATIVE, nativeFeeReserve);

		// Delete AT's account (and hence its balance)
		this.repository.getAccountRepository().delete(this.deployAtTransactionData.getAtAddress());
	}

}
