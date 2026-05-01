package org.qortal.account;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.resource.TransactionsResource;
import org.qortal.block.BlockChain;
import org.qortal.controller.LiteNode;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.data.naming.NameData;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.GroupRepository;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;
import org.qortal.utils.Groups;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.qortal.utils.Amounts.prettyAmount;

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
			accountBalanceData = LiteNode.getInstance().fetchAccountBalance(this.address, assetId);
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
	 * Mint eligibility is governed by membership in one of the configured minting groups.
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

		int blockchainHeight = this.repository.getBlockRepository().getBlockchainHeight();
		List<Integer> groupIdsToMint = Groups.getGroupIdsToMint(BlockChain.getInstance(), blockchainHeight);
		String myAddress = accountData.getAddress();

		return isGroupValidated || Groups.memberExistsInAnyGroup(groupRepository, groupIdsToMint, myAddress);
	}

	/** Returns account's blockMinted (0+) or null if account not found in repository. */
	public Integer getBlocksMinted() throws DataException {
		return this.repository.getAccountRepository().getMintedBlockCount(this.address);
	}

	/** Returns whether account can build reward-shares.
	 *
	 * @return true if the account exists in the repository
	 * @throws DataException
	 */
	public boolean canRewardShare() throws DataException {
		AccountData accountData = this.repository.getAccountRepository().getAccount(this.address);

		if (accountData == null)
			return false;

		return true;
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

		Optional<String> primaryName;

		// if no registered names, the no primary name possible
		if (names.isEmpty()) {
			primaryName = Optional.empty();
		}
		// if names
		else {
			// if one name, then that is the primary name
			if (names.size() == 1) {
				primaryName = Optional.of( names.get(0).getName() );
			}
			// if more than one name, then seek the earliest name acquisition that was never released
			else {
				Map<String, TransactionData> txByName = new HashMap<>(names.size());

				// for each name, get the latest transaction
				for (NameData nameData : names) {

					// since the name is currently registered to the owner,
					// we assume the latest transaction involving this name was the transaction that the acquired
					// name through registration, purchase or update
					Optional<TransactionData> latestTransaction
							= this.repository
							.getTransactionRepository()
							.getTransactionsInvolvingName(
									nameData.getName(),
									confirmationStatus
							)
							.stream()
							.sorted(Comparator.comparing(
									TransactionData::getTimestamp).reversed()
							)
							.findFirst(); // first is the last, since it was reversed

					// if there is a latest transaction, expected for all registered names
					if (latestTransaction.isPresent()) {
						txByName.put(nameData.getName(), latestTransaction.get());
					}
					// if there is no latest transaction, then
					else {
						LOGGER.warn("No matching transaction for name: " + nameData.getName());
					}
				}

				// get the first name aqcuistion for this address
				Optional<Map.Entry<String, TransactionData>> firstNameEntry
						= txByName.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getValue().getTimestamp())).findFirst();

				// if their is a name acquisition, then the first one is the primary name
				if (firstNameEntry.isPresent()) {
					primaryName = Optional.of( firstNameEntry.get().getKey() );
				}
				// if there is no nameacquistion, then there is no primary name
				else {
					primaryName =  Optional.empty();
				}
			}
		}
		return primaryName;
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
	 * Returns a reward-share minter's actual level, or null if the reward-share does not exist.
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

		Account rewardShareMinter = new Account(repository, rewardShareData.getMinter());
		if (!rewardShareMinter.canMint(false))
			return null;

		return rewardShareMinter.getEffectiveMintingLevel();
	}

	/**
	 * Returns whether the supplied reward-share public key belongs to an account currently allowed to mint.
	 *
	 * @param repository
	 * @param rewardSharePublicKey
	 * @return true if the reward-share exists and its minter can mint
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
