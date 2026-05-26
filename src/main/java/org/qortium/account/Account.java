package org.qortium.account;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.api.resource.TransactionsResource;
import org.qortium.block.BlockChain;
import org.qortium.controller.LiteNode;
import org.qortium.data.account.AccountBalanceData;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.naming.NameData;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.transaction.BuyNameTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateNameTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.GroupRepository;
import org.qortium.repository.Repository;
import org.qortium.settings.Settings;
import org.qortium.utils.Groups;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.qortium.utils.Amounts.prettyAmount;

@XmlAccessorType(XmlAccessType.NONE) // Stops JAX-RS errors when unmarshalling blockchain config
public class Account {

	private static final Logger LOGGER = LogManager.getLogger(Account.class);

	public static final int ADDRESS_LENGTH = 25;

	protected Repository repository;
	protected String address;

	protected Account() {
	}

	/** Construct Account business object using account's address */
	public Account(Repository repository, String address) {
		this.repository = repository;
		this.address = address;
	}

	// Simple getters / setters

	public String getAddress() {
		return this.address;
	}

	/**
	 * Build AccountData object using available account information.
	 * <p>
	 * For example, PublicKeyAccount might override and add public key info.
	 * 
	 * @return
	 */
	protected AccountData buildAccountData() {
		return new AccountData(this.address);
	}

	public void ensureAccount() throws DataException {
		this.repository.getAccountRepository().ensureAccount(this.buildAccountData());
	}

	// Balance manipulations - assetId is 0 for the native asset

	public long getConfirmedBalance(long assetId) throws DataException {
		AccountBalanceData accountBalanceData;

		if (Settings.getInstance().isLite()) {
			// Lite nodes request data from peers instead of the local db
			LiteNode.LiteDataResult<AccountBalanceData> result = LiteNode.getInstance().fetchAccountBalanceResult(this.address, assetId);
			switch (result.getStatus()) {
				case AGREED:
					accountBalanceData = result.getValue();
					break;

				case UNKNOWN:
					return 0;

				case CONFLICTED:
					throw new DataException(String.format("Conflicting lite peer balance data for %s [assetId %d]", this.address, assetId));

				case UNAVAILABLE:
				default:
					throw new DataException(String.format("No lite peer balance data available for %s [assetId %d]", this.address, assetId));
			}
		}
		else {
			// All other node types fetch from the local db
			accountBalanceData = this.repository.getAccountRepository().getBalance(this.address, assetId);
		}

		if (accountBalanceData == null)
			return 0;

		return accountBalanceData.getBalance();
	}

	public void setConfirmedBalance(long assetId, long balance) throws DataException {
		// Safety feature!
		if (balance < 0) {
			String message = String.format("Refusing to set negative balance %s [assetId %d] for %s", prettyAmount(balance), assetId, this.address);
			LOGGER.error(message);
			throw new DataException(message);
		}

		// Delete account balance record instead of setting balance to zero
		if (balance == 0) {
			this.repository.getAccountRepository().delete(this.address, assetId);
			return;
		}

		// Can't have a balance without an account - make sure it exists!
		this.ensureAccount();

		AccountBalanceData accountBalanceData = new AccountBalanceData(this.address, assetId, balance);
		this.repository.getAccountRepository().save(accountBalanceData);

		LOGGER.trace(() -> String.format("%s balance now %s [assetId %s]", this.address, prettyAmount(balance), assetId));
	}

	// Convenience method
	public void modifyAssetBalance(long assetId, long deltaBalance) throws DataException {
		this.repository.getAccountRepository().modifyAssetBalance(this.getAddress(), assetId, deltaBalance);

		LOGGER.trace(() -> String.format("%s balance %s by %s [assetId %s]",
				this.address,
				(deltaBalance >= 0 ? "increased" : "decreased"),
				prettyAmount(Math.abs(deltaBalance)),
				assetId));
	}

	public void deleteBalance(long assetId) throws DataException {
		this.repository.getAccountRepository().delete(this.address, assetId);
	}

	// Default groupID manipulations

	/** Returns account's default groupID or null if account doesn't exist. */
	public Integer getDefaultGroupId() throws DataException {
		return this.repository.getAccountRepository().getDefaultGroupId(this.address);
	}

	/**
	 * Sets account's default groupID and saves into repository.
	 * <p>
	 * Caller will need to call <tt>repository.saveChanges()</tt>.
	 * 
	 * @param defaultGroupId
	 * @throws DataException
	 */
	public void setDefaultGroupId(int defaultGroupId) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setDefaultGroupId(defaultGroupId);
		this.repository.getAccountRepository().setDefaultGroupId(accountData);

		LOGGER.trace(() -> String.format("Account %s defaultGroupId now %d", accountData.getAddress(), defaultGroupId));
	}

	// Minting blocks

	/** Returns whether account can be considered a "minting account".
	 * <p>
	 * Mint eligibility is governed by membership in one of the configured minting groups
	 * plus the active Subject trust snapshot.
	 *
	 * @param isGroupValidated true if this account has already been validated for configured minting-group membership
	 * @return true if account can be considered "minting account"
	 * @throws DataException
	 */
	public boolean canMint(boolean isGroupValidated) throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);
		GroupRepository groupRepository = this.repository.getGroupRepository();
		if (accountData == null)
			return false;

		AccountTrustSnapshotData activeTrustSnapshot = this.repository.getAccountRatingRepository()
				.getTrustDerivationSnapshot(this.address, AccountTrustWeight.getActiveWeightCategory());
		if (!AccountTrustWeight.canMint(activeTrustSnapshot))
			return false;

		int blockchainHeight = this.repository.getBlockRepository().getBlockchainHeight();
		List<Integer> groupIdsToMint = Groups.getGroupIdsToMint(BlockChain.getInstance(), blockchainHeight);
		String myAddress = accountData.getAddress();

		return isGroupValidated || Groups.memberExistsInAnyGroup(groupRepository, groupIdsToMint, myAddress);
	}

	public AccountTrustStatus getTrustStatus() throws DataException {
		AccountTrustSnapshotData activeTrustSnapshot = this.repository.getAccountRatingRepository()
				.getTrustDerivationSnapshot(this.address, AccountTrustWeight.getActiveWeightCategory());
		return AccountTrustWeight.statusFromSnapshot(activeTrustSnapshot);
	}

	public int getEffectiveVoteWeight() throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);
		if (accountData == null)
			return 0;

		AccountTrustSnapshotData activeTrustSnapshot = this.repository.getAccountRatingRepository()
				.getTrustDerivationSnapshot(this.address, AccountTrustWeight.getActiveWeightCategory());
		int blockchainHeight = this.repository.getBlockRepository().getBlockchainHeight();
		return AccountTrustWeight.calculateEffectiveVoteWeight(this.repository, blockchainHeight,
				accountData.getBlocksMinted(), activeTrustSnapshot);
	}

	/** Returns account's blockMinted (0+) or null if account not found in repository. */
	public Integer getBlocksMinted() throws DataException {
		return this.repository.getAccountRepository().getMintedBlockCount(this.address);
	}

	// Account level

	/** Returns account's level (0+) or null if account not found in repository. */
	public Integer getLevel() throws DataException {
		return this.repository.getAccountRepository().getLevel(this.address);
	}

	public void setLevel(int level) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setLevel(level);
		this.repository.getAccountRepository().setLevel(accountData);
	}

	/**
	 * Returns 'effective' minting level, or zero if account does not exist/cannot mint.
	 * 
	 * @return 0+
	 * @throws DataException
	 */
	public int getEffectiveMintingLevel() throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);
		if (accountData == null)
			return 0;

		return accountData.getLevel();
	}

	/**
	 * Get Primary Name
	 *
	 * @return the primary name for this address if present, otherwise empty
	 *
	 * @throws DataException
	 */
	public Optional<String> getPrimaryName() throws DataException {

		return this.repository.getNameRepository().getPrimaryName(this.address);
	}

	/**
	 * Remove Primary Name
	 *
	 * @throws DataException
	 */
	public void removePrimaryName() throws DataException {
		this.repository.getNameRepository().removePrimaryName(this.address);
	}

	/**
	 * Reset Primary Name
	 *
	 * Set primary name based on the names (and their history) this account owns.
	 *
	 * @param confirmationStatus the status of the transactions for the determining the primary name
	 *
	 * @return the primary name, empty if their isn't one
	 *
	 * @throws DataException
	 */
	public Optional<String> resetPrimaryName(TransactionsResource.ConfirmationStatus confirmationStatus) throws DataException {
		Optional<String> primaryName = determinePrimaryName(confirmationStatus);

		if(primaryName.isPresent()) {
			return setPrimaryName(primaryName.get());
		}
		else {
			return primaryName;
		}
	}

	/**
	 * Determine Primary Name
	 *
	 * Determine primary name based on a list of registered names.
	 *
	 * @param confirmationStatus the status of the transactions for this determination
	 *
	 * @return the primary name, empty if there is no primary name
	 *
	 * @throws DataException
	 */
	public Optional<String> determinePrimaryName(TransactionsResource.ConfirmationStatus confirmationStatus) throws DataException {

		// all registered names for the owner
		List<NameData> names = this.repository.getNameRepository().getNamesByOwner(this.address);

		// if no registered names, the no primary name possible
		if (names.isEmpty()) {
			return Optional.empty();
		}

		Set<String> currentlyOwnedNames = names.stream().map(NameData::getName).collect(Collectors.toSet());

		List<TransactionData> nameHistory = new ArrayList<>();
		for (NameData nameData : names)
			nameHistory.addAll(fetchNameChangingHistory(nameData.getReference(), confirmationStatus));

		nameHistory.sort(Comparator.comparing(TransactionData::getTimestamp));

		Optional<String> primaryName = Optional.empty();
		for (TransactionData transactionData : nameHistory) {
			switch (transactionData.getType()) {
				case REGISTER_NAME: {
					RegisterNameTransactionData registerNameTransactionData = (RegisterNameTransactionData) transactionData;
					PublicKeyAccount registrant = new PublicKeyAccount(this.repository, registerNameTransactionData.getRegistrantPublicKey());
					if (registrant.getAddress().equals(this.address) && primaryName.isEmpty())
						primaryName = Optional.of(registerNameTransactionData.getName());

					break;
				}

				case BUY_NAME: {
					BuyNameTransactionData buyNameTransactionData = (BuyNameTransactionData) transactionData;
					PublicKeyAccount buyer = new PublicKeyAccount(this.repository, buyNameTransactionData.getBuyerPublicKey());
					if (buyer.getAddress().equals(this.address) && primaryName.isEmpty())
						primaryName = Optional.of(buyNameTransactionData.getName());

					break;
				}

				case UPDATE_NAME: {
					UpdateNameTransactionData updateNameTransactionData = (UpdateNameTransactionData) transactionData;
					PublicKeyAccount owner = new PublicKeyAccount(this.repository, updateNameTransactionData.getOwnerPublicKey());
					if (!owner.getAddress().equals(this.address))
						break;

					String oldName = updateNameTransactionData.getName();
					String newName = updateNameTransactionData.getNewName();
					String updatedName = newName.isEmpty() ? oldName : newName;
					Boolean primary = updateNameTransactionData.getPrimary();

					if (primary != null) {
						if (primary)
							primaryName = Optional.of(updatedName);
						else if (primaryName.isPresent() && primaryName.get().equals(oldName))
							primaryName = Optional.empty();
					} else if (primaryName.isPresent() && primaryName.get().equals(oldName) && !newName.isEmpty()) {
						primaryName = Optional.of(updatedName);
					}

					break;
				}

				default:
					break;
			}
		}

		if (primaryName.isPresent() && !currentlyOwnedNames.contains(primaryName.get()))
			return Optional.empty();

		return primaryName;
	}

	private List<TransactionData> fetchNameChangingHistory(byte[] nameReference, TransactionsResource.ConfirmationStatus confirmationStatus) throws DataException {
		List<TransactionData> nameHistory = new ArrayList<>();

		while (nameReference != null) {
			TransactionData transactionData = this.repository.getTransactionRepository().fromSignature(nameReference);
			if (transactionData == null) {
				LOGGER.warn("No matching transaction for name reference");
				break;
			}

			if (matchesConfirmationStatus(transactionData, confirmationStatus))
				nameHistory.add(transactionData);

			switch (transactionData.getType()) {
				case REGISTER_NAME:
					return nameHistory;

				case UPDATE_NAME:
					nameReference = ((UpdateNameTransactionData) transactionData).getNameReference();
					break;

				case BUY_NAME:
					nameReference = ((BuyNameTransactionData) transactionData).getNameReference();
					break;

				default:
					return nameHistory;
			}
		}

		return nameHistory;
	}

	private static boolean matchesConfirmationStatus(TransactionData transactionData, TransactionsResource.ConfirmationStatus confirmationStatus) {
		switch (confirmationStatus) {
			case CONFIRMED:
				return transactionData.getBlockHeight() != null;

			case UNCONFIRMED:
				return transactionData.getBlockHeight() == null;

			case BOTH:
			default:
				return true;
		}
	}

	/**
	 * Set Primary Name
	 *
	 * @param primaryName the primary to set to this address
	 *
	 * @return the primary name if successful, empty if unsuccessful
	 *
	 * @throws DataException
	 */
	public Optional<String> setPrimaryName( String primaryName ) throws DataException {
		int changed = this.repository.getNameRepository().setPrimaryName(this.address, primaryName);

		return changed > 0 ? Optional.of(primaryName) : Optional.empty();
	}

	/**
	 * Returns reward-share minting address, or unknown if reward-share does not exist.
	 * 
	 * @param repository
	 * @param rewardSharePublicKey
	 * @return address or unknown
	 * @throws DataException
	 */
	public static String getRewardShareMintingAddress(Repository repository, byte[] rewardSharePublicKey) throws DataException {
		// Find actual minter address
		RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardSharePublicKey);

		if (rewardShareData == null)
			return "Unknown";

		if (!rewardShareData.isSelfShare())
			return "Unknown";

		return rewardShareData.getMinter();
	}

	/**
	 * Returns 'effective' minting level, or zero if reward-share does not exist.
	 *
	 * @param repository
	 * @param rewardSharePublicKey
	 * @return 0+
	 * @throws DataException
	 */
	public static int getRewardShareEffectiveMintingLevel(Repository repository, byte[] rewardSharePublicKey) throws DataException {
		Integer mintingLevel = getRewardShareEffectiveMintingLevelIfPresent(repository, rewardSharePublicKey);
		return mintingLevel == null ? 0 : mintingLevel;
	}

	/**
	 * Returns a self-share minter's actual level, or null if the self-share does not exist.
	 *
	 * @param repository
	 * @param rewardSharePublicKey
	 * @return actual account level, including zero, or null
	 * @throws DataException
	 */
	public static Integer getRewardShareEffectiveMintingLevelIfPresent(Repository repository, byte[] rewardSharePublicKey) throws DataException {
		RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardSharePublicKey);
		if (rewardShareData == null)
			return null;

		if (!rewardShareData.isSelfShare())
			return null;

		Account rewardShareMinter = new Account(repository, rewardShareData.getMinter());
		return rewardShareMinter.getEffectiveMintingLevel();
	}

	/**
	 * Returns a reward-share minter's actual level, or null if the reward-share is not currently allowed to mint.
	 *
	 * @param repository
	 * @param rewardSharePublicKey
	 * @return actual account level, including zero, or null
	 * @throws DataException
	 */
	public static Integer getRewardShareEffectiveMintingLevelIfMinting(Repository repository, byte[] rewardSharePublicKey) throws DataException {
		RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardSharePublicKey);
		if (rewardShareData == null)
			return null;

		if (!rewardShareData.isSelfShare())
			return null;

		Account rewardShareMinter = new Account(repository, rewardShareData.getMinter());
		if (!rewardShareMinter.canMint(false))
			return null;

		return rewardShareMinter.getEffectiveMintingLevel();
	}

	/**
	 * Returns whether the supplied self-share public key belongs to an account currently allowed to mint.
	 *
	 * @param repository
	 * @param rewardSharePublicKey
	 * @return true if the self-share exists and its minter can mint
	 * @throws DataException
	 */
	public static boolean canRewardShareMint(Repository repository, byte[] rewardSharePublicKey) throws DataException {
		return getRewardShareEffectiveMintingLevelIfMinting(repository, rewardSharePublicKey) != null;
	}

	/**
	 * Returns the account level to use in minting-weight calculations.
	 * <p>
	 * Level-zero minters are eligible, but the weight divisor must be at least one.
	 *
	 * @param accountLevel
	 * @return account level clamped to the minimum minting weight
	 */
	public static int getMintingWeightLevel(int accountLevel) {
		return Math.max(1, accountLevel);
	}

}
