package org.qortal.block;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.persistence.exceptions.XMLMarshalException;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.qortal.controller.Controller;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.block.BlockData;
import org.qortal.data.blockchain.ChainParameterData;
import org.qortal.network.Network;
import org.qortal.repository.*;
import org.qortal.settings.Settings;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;
import org.qortal.utils.StringLongMapXmlAdapter;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.UnmarshalException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Class representing the blockchain as a whole.
 *
 */
// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class BlockChain {

	private static final Logger LOGGER = LogManager.getLogger(BlockChain.class);

	public static final int REWARD_SHARE_LEVEL_COUNT = 10;

	private static BlockChain instance = null;

	// Properties

	private boolean isTestChain = false;

	/** Transaction expiry period, starting from transaction's timestamp, in milliseconds. */
	private long transactionExpiryPeriod;

	private int maxBytesPerUnitFee;

	/** Maximum acceptable timestamp disagreement offset in milliseconds. */
	private long blockTimestampMargin;

	/** Maximum block size, in bytes. */
	private int maxBlockSize;

	/** Minimum number of blocks between chain-parameter approval and activation. */
	private int chainParameterUpdateMinActivationDelay;

	/** Whether transactions with txGroupId of NO_GROUP are allowed */
	private boolean requireGroupForApproval;

	/** Four-byte network message magic values used to identify this chain's peer network. */
	private String mainnetMessageMagic;
	private String testnetMessageMagic;
	private byte[] mainnetMessageMagicBytes;
	private byte[] testnetMessageMagicBytes;

	private AccountTrustSettings accountTrustSettings;

	private GenesisBlock.GenesisInfo genesisInfo;

	public enum FeatureTrigger {
		transactionV6Timestamp
	}

    // V5.5 Default List of Historic Triggers
    private static final Map<FeatureTrigger, Long> defaultFeatureTriggerHeight = new EnumMap<>(FeatureTrigger.class);

	// Custom transaction fees
	/** Unit fees by transaction timestamp */
	public static class UnitFeesByTimestamp {
		public long timestamp;
		@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
		public long fee;
	}
	private List<UnitFeesByTimestamp> unitFees;
	private List<UnitFeesByTimestamp> nameRegistrationUnitFees;

	/** Map of which blockchain features are enabled when (height/timestamp) */
	@XmlJavaTypeAdapter(StringLongMapXmlAdapter.class)
	private Map<String, Long> featureTriggers;

	/** Checkpoints */
	public static class Checkpoint {
		public int height;
		public String signature;
	}
	private List<Checkpoint> checkpoints;

	/** Block rewards by block height */
	public static class RewardByHeight {
		public int height;
		@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
		public long reward;
	}
	private List<RewardByHeight> rewardsByHeight;

	/** Share of block reward/fees by account level */
	public static class AccountLevelShareBin implements Cloneable {
		public int id;
		public List<Integer> levels;
		@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
		public long share;

		public Object clone() {
			AccountLevelShareBin shareBinCopy = new AccountLevelShareBin();
			List<Integer> levelsCopy = new ArrayList<>();
			for (Integer level : this.levels) {
				levelsCopy.add(level);
			}
			shareBinCopy.id = this.id;
			shareBinCopy.levels = levelsCopy;
			shareBinCopy.share = this.share;
			return shareBinCopy;
		}
	}
	private List<AccountLevelShareBin> sharesByLevel;
	/** Generated lookup of share-bin by account level */
	private AccountLevelShareBin[] shareBinsByLevel;

	/** Minimum number of accounts before a share bin is considered activated */
	private int minAccountsToActivateShareBin;

	/** Min level at which share bin activation takes place; lower levels allow less than minAccountsPerShareBin */
	private int shareBinActivationMinLevel;

	/**
	 * Number of minted blocks required to reach next level from previous.
	 * <p>
	 * Use account's current level as index.<br>
	 * If account's level isn't valid as an index, then account's level is at maximum.
	 * <p>
	 * Example: if <tt>blocksNeededByLevel[3]</tt> is 200,<br>
	 * then level 3 accounts need to mint 200 blocks to reach level 4.
	 */
	private List<Integer> blocksNeededByLevel;

	/**
	 * Cumulative number of minted blocks required to reach next level from scratch.
	 * <p>
	 * Use target level as index. <tt>cumulativeBlocksByLevel[0]</tt> should be 0.
	 * <p>
	 * Example; if <tt>cumulativeBlocksByLevel[2</tt>] is 1800,<br>
	 * the a <b>new</b> account will need to mint 1800 blocks to reach level 2.
	 * <p>
	 * Generated just after blockchain config is parsed and validated.
	 * <p>
	 * Should NOT be present in blockchain config file!
	 */
	private List<Integer> cumulativeBlocksByLevel;

	/** Block times by block height */
	public static class BlockTimingByHeight {
		public int height;
		public long target; // ms
		public long deviation; // ms
		public double power;
	}
	private List<BlockTimingByHeight> blockTimingsByHeight;

	private int minAccountLevelToMint;
	private int minAccountLevelForBlockSubmissions;

	public static class IdsForHeight {
		public int height;
		public List<Integer> ids;
	}

	private List<IdsForHeight> mintingGroupIds;
	private List<IdsForHeight> devGroupIds;

	/** Minimum time to retain online account signatures (ms) for block validity checks. */
	private long onlineAccountSignaturesMinLifetime;

	/** Maximum time to retain online account signatures (ms) for block validity checks, to allow for clock variance. */
	private long onlineAccountSignaturesMaxLifetime;

	/** Feature-trigger timestamp to modify behaviour of various transactions that support mempow */
	private long mempowTransactionUpdatesTimestamp;

	/** Feature trigger block height for batch block reward payouts.
	 * This MUST be a multiple of blockRewardBatchSize. Can't use
	 * featureTriggers because unit tests need to set this value via Reflection. */
	private int blockRewardBatchStartHeight;

	/** Block reward batch size. Must be (significantly) less than block prune size,
	 * as all blocks in the range need to be present in the repository when processing/orphaning */
	private int blockRewardBatchSize;

	/** Number of blocks prior to the batch reward distribution blocks to include online accounts
	 * data and to base online accounts decisions on. */
	private int blockRewardBatchAccountsBlockCount;

	/** Max reward shares by block height */
	public static class MaxRewardSharesByTimestamp {
		public long timestamp;
		public int maxShares;
	}
	private List<MaxRewardSharesByTimestamp> maxRewardSharesByTimestamp;

	public static class AccountTrustSettings {
		public AccountRatingCategory activeWeightCategory;
		public long startingEnergy;
		public int managerEnergyHops;
		public int positiveMinBranchCount;
		public int suspiciousMinRaterCount;
		public int suspiciousMinBranchCount;
		public int suspiciousMinRatingConfidence;
		public Integer accountRatingChangeCooldownBlocks;
		public List<StatusVoteWeightPercent> statusVoteWeightPercents;
		public List<AccountTrustCategoryPolicy> categoryPolicies;

		private transient Map<AccountTrustStatus, Integer> voteWeightPercentByStatus;
		private transient Map<AccountRatingCategory, AccountTrustCategoryPolicy> policyByCategory;

		public AccountRatingCategory getActiveWeightCategory() {
			return this.activeWeightCategory;
		}

		public long getStartingEnergy() {
			return this.startingEnergy;
		}

		public int getManagerEnergyHops() {
			return this.managerEnergyHops;
		}

		public int getPositiveMinBranchCount() {
			return this.positiveMinBranchCount;
		}

		public int getSuspiciousMinRaterCount() {
			return this.suspiciousMinRaterCount;
		}

		public int getSuspiciousMinBranchCount() {
			return this.suspiciousMinBranchCount;
		}

		public int getSuspiciousMinRatingConfidence() {
			return this.suspiciousMinRatingConfidence;
		}

		public int getAccountRatingChangeCooldownBlocks() {
			return this.accountRatingChangeCooldownBlocks;
		}

		public int getVoteWeightPercent(AccountTrustStatus status) {
			Integer voteWeightPercent = this.voteWeightPercentByStatus.get(status == null ? AccountTrustStatus.UNVERIFIED : status);
			return voteWeightPercent == null ? 0 : voteWeightPercent;
		}

		public int[] getStatusVoteWeightPercents() {
			int[] voteWeightPercents = new int[AccountTrustStatus.values().length];
			for (AccountTrustStatus status : AccountTrustStatus.values())
				voteWeightPercents[status.ordinal()] = getVoteWeightPercent(status);

			return voteWeightPercents;
		}

		public long getLevelThreshold(AccountRatingCategory category, int level) {
			return getCategoryPolicy(category).getLevelThreshold(level);
		}

		public long getLevelScoreCap(AccountRatingCategory category, int level) {
			return getCategoryPolicy(category).getLevelScoreCap(level);
		}

		public long getSuspiciousThreshold(AccountRatingCategory category) {
			return getCategoryPolicy(category).suspiciousThreshold;
		}

		public long getSuspiciousLevelScoreCap(AccountRatingCategory category) {
			return getCategoryPolicy(category).suspiciousCap;
		}

		private AccountTrustCategoryPolicy getCategoryPolicy(AccountRatingCategory category) {
			AccountTrustCategoryPolicy policy = this.policyByCategory.get(category == null ? AccountRatingCategory.SUBJECT : category);
			if (policy == null)
				throw new IllegalStateException("Missing account trust category policy");

			return policy;
		}

		private void validate() {
			if (this.activeWeightCategory == null)
				Settings.throwValidationError("\"accountTrustSettings.activeWeightCategory\" is required");

			if (this.startingEnergy <= 0)
				Settings.throwValidationError("\"accountTrustSettings.startingEnergy\" must be greater than 0");

			if (this.managerEnergyHops <= 0)
				Settings.throwValidationError("\"accountTrustSettings.managerEnergyHops\" must be greater than 0");

			if (this.positiveMinBranchCount <= 0)
				Settings.throwValidationError("\"accountTrustSettings.positiveMinBranchCount\" must be greater than 0");

			if (this.suspiciousMinRaterCount <= 0)
				Settings.throwValidationError("\"accountTrustSettings.suspiciousMinRaterCount\" must be greater than 0");

			if (this.suspiciousMinBranchCount < 0)
				Settings.throwValidationError("\"accountTrustSettings.suspiciousMinBranchCount\" must not be negative");
			if (this.suspiciousMinBranchCount == 0)
				this.suspiciousMinBranchCount = this.suspiciousMinRaterCount;

			if (this.suspiciousMinRatingConfidence <= 0 || this.suspiciousMinRatingConfidence > 4)
				Settings.throwValidationError("\"accountTrustSettings.suspiciousMinRatingConfidence\" must be between 1 and 4");

			if (this.accountRatingChangeCooldownBlocks == null)
				Settings.throwValidationError("\"accountTrustSettings.accountRatingChangeCooldownBlocks\" is required");

			if (this.accountRatingChangeCooldownBlocks < 0)
				Settings.throwValidationError("\"accountTrustSettings.accountRatingChangeCooldownBlocks\" must not be negative");

			validateVoteWeights();
			validateCategoryPolicies();
		}

		private void validateVoteWeights() {
			if (this.statusVoteWeightPercents == null)
				Settings.throwValidationError("\"accountTrustSettings.statusVoteWeightPercents\" is required");

			EnumSet<AccountTrustStatus> seenStatuses = EnumSet.noneOf(AccountTrustStatus.class);
			for (StatusVoteWeightPercent voteWeightPercent : this.statusVoteWeightPercents) {
				if (voteWeightPercent == null || voteWeightPercent.status == null)
					Settings.throwValidationError("\"accountTrustSettings.statusVoteWeightPercents\" contains a missing status");

				if (!seenStatuses.add(voteWeightPercent.status))
					Settings.throwValidationError("Duplicate account trust vote weight status: " + voteWeightPercent.status);

				if (voteWeightPercent.percent < 0 || voteWeightPercent.percent > 100)
					Settings.throwValidationError("Account trust vote weight percent must be between 0 and 100");
			}

			for (AccountTrustStatus status : AccountTrustStatus.values())
				if (!seenStatuses.contains(status))
					Settings.throwValidationError("Missing account trust vote weight status: " + status);
		}

		private void validateCategoryPolicies() {
			if (this.categoryPolicies == null)
				Settings.throwValidationError("\"accountTrustSettings.categoryPolicies\" is required");

			EnumSet<AccountRatingCategory> seenCategories = EnumSet.noneOf(AccountRatingCategory.class);
			for (AccountTrustCategoryPolicy categoryPolicy : this.categoryPolicies) {
				if (categoryPolicy == null || categoryPolicy.category == null)
					Settings.throwValidationError("\"accountTrustSettings.categoryPolicies\" contains a missing category");

				if (!seenCategories.add(categoryPolicy.category))
					Settings.throwValidationError("Duplicate account trust category policy: " + categoryPolicy.category);

				categoryPolicy.validate(this.suspiciousMinRaterCount);
			}

			for (AccountRatingCategory category : AccountRatingCategory.values())
				if (!seenCategories.contains(category))
					Settings.throwValidationError("Missing account trust category policy: " + category);
		}

		private void fixUp() {
			EnumMap<AccountTrustStatus, Integer> voteWeights = new EnumMap<>(AccountTrustStatus.class);
			for (StatusVoteWeightPercent voteWeightPercent : this.statusVoteWeightPercents)
				voteWeights.put(voteWeightPercent.status, voteWeightPercent.percent);

			EnumMap<AccountRatingCategory, AccountTrustCategoryPolicy> policies = new EnumMap<>(AccountRatingCategory.class);
			for (AccountTrustCategoryPolicy categoryPolicy : this.categoryPolicies) {
				categoryPolicy.fixUp();
				policies.put(categoryPolicy.category, categoryPolicy);
			}

			this.voteWeightPercentByStatus = Collections.unmodifiableMap(voteWeights);
			this.policyByCategory = Collections.unmodifiableMap(policies);
			this.statusVoteWeightPercents = Collections.unmodifiableList(this.statusVoteWeightPercents);
			this.categoryPolicies = Collections.unmodifiableList(this.categoryPolicies);
		}
	}

	public static class StatusVoteWeightPercent {
		public AccountTrustStatus status;
		public int percent;
	}

	public static class AccountTrustCategoryPolicy {
		public AccountRatingCategory category;
		public List<AccountTrustLevelPolicy> levels;
		public long suspiciousThreshold;
		public long suspiciousCap;

		private transient Map<Integer, AccountTrustLevelPolicy> policyByLevel;

		private long getLevelThreshold(int level) {
			return getLevelPolicy(level).threshold;
		}

		private long getLevelScoreCap(int level) {
			return getLevelPolicy(level).cap;
		}

		private AccountTrustLevelPolicy getLevelPolicy(int level) {
			AccountTrustLevelPolicy policy = this.policyByLevel.get(level);
			if (policy == null)
				throw new IllegalStateException("Missing account trust level policy");

			return policy;
		}

		private void validate(int suspiciousMinRaterCount) {
			if (this.levels == null)
				Settings.throwValidationError("Account trust category policy is missing levels: " + this.category);

			Set<Integer> requiredLevels = requiredLevels(this.category);
			Set<Integer> seenLevels = new HashSet<>();
			for (AccountTrustLevelPolicy levelPolicy : this.levels) {
				if (levelPolicy == null)
					Settings.throwValidationError("Account trust category policy contains a missing level: " + this.category);

				if (!requiredLevels.contains(levelPolicy.level))
					Settings.throwValidationError("Unexpected account trust level " + levelPolicy.level + " for category " + this.category);

				if (!seenLevels.add(levelPolicy.level))
					Settings.throwValidationError("Duplicate account trust level " + levelPolicy.level + " for category " + this.category);

				levelPolicy.validate(this.category);
			}

			for (Integer requiredLevel : requiredLevels)
				if (!seenLevels.contains(requiredLevel))
					Settings.throwValidationError("Missing account trust level " + requiredLevel + " for category " + this.category);

			if (this.suspiciousThreshold >= 0)
				Settings.throwValidationError("Account trust suspicious threshold must be negative for category " + this.category);

			long suspiciousRequiredScore = -this.suspiciousThreshold;
			if (this.suspiciousCap <= 0 || this.suspiciousCap >= suspiciousRequiredScore)
				Settings.throwValidationError("Account trust suspicious cap must be positive and less than the threshold magnitude for category " + this.category);

			if (this.suspiciousCap <= Long.MAX_VALUE / suspiciousMinRaterCount
					&& this.suspiciousCap * suspiciousMinRaterCount < suspiciousRequiredScore)
				Settings.throwValidationError("Account trust suspicious cap and rater count cannot reach threshold for category " + this.category);
		}

		private void fixUp() {
			Map<Integer, AccountTrustLevelPolicy> policies = new HashMap<>();
			for (AccountTrustLevelPolicy levelPolicy : this.levels)
				policies.put(levelPolicy.level, levelPolicy);

			this.policyByLevel = Collections.unmodifiableMap(policies);
			this.levels = Collections.unmodifiableList(this.levels);
		}

		private static Set<Integer> requiredLevels(AccountRatingCategory category) {
			switch (category) {
				case MANAGER:
				case TRAINER:
					return new HashSet<>(Arrays.asList(1, 2));

				case PLAYER:
					return new HashSet<>(Arrays.asList(1, 2, 3));

				case SUBJECT:
				default:
					return new HashSet<>(Arrays.asList(1, 2, 3, 4));
			}
		}
	}

	public static class AccountTrustLevelPolicy {
		public int level;
		public long threshold;
		public long cap;

		private void validate(AccountRatingCategory category) {
			if (this.threshold <= 0)
				Settings.throwValidationError("Account trust level threshold must be greater than 0 for category " + category);

			if (this.cap <= 0 || this.cap >= this.threshold)
				Settings.throwValidationError("Account trust level cap must be positive and less than the threshold for category " + category);
		}
	}

	/** Settings relating to CIYAM AT feature. */
	public static class CiyamAtSettings {
		/** Fee per step/op-code executed. */
		@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
		public long feePerStep;
		/** Maximum number of steps per execution round, before AT is forced to sleep until next block. */
		public int maxStepsPerRound;
		/** How many steps for calling a function. */
		public int stepsPerFunctionCall;
		/** Roughly how many minutes per block. */
		public int minutesPerBlock;
	}
	private CiyamAtSettings ciyamAtSettings;

	// Constructors, etc.
	private BlockChain() {
	}

	public static BlockChain getInstance() {
		if (instance == null)
			// This will call BlockChain.fromJSON in turn
			Settings.getInstance(); // synchronized

		return instance;
	}

	/** Use blockchain config read from <tt>path</tt> + <tt>filename</tt>, or use resources-based default if <tt>filename</tt> is <tt>null</tt>. */
	public static void fileInstance(String path, String filename) {
		JAXBContext jc;
		Unmarshaller unmarshaller;

		try {
			// Create JAXB context aware of Settings
			jc = JAXBContextFactory.createContext(new Class[] {
					BlockChain.class, GenesisBlock.GenesisInfo.class
			}, null);

			// Create unmarshaller
			unmarshaller = jc.createUnmarshaller();

			// Set the unmarshaller media type to JSON
			unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");

			// Tell unmarshaller that there's no JSON root element in the JSON input
			unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);

		} catch (JAXBException e) {
			String message = "Failed to setup unmarshaller to process blockchain config file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}

		BlockChain blockchain = null;
		StreamSource jsonSource;

		if (filename != null) {
			LOGGER.info(String.format("Using blockchain config file: %s%s", path, filename));

			File jsonFile = new File(path + filename);

			if (!jsonFile.exists()) {
				String message = "Blockchain config file not found: " + path + filename;
				LOGGER.error(message);
				throw new RuntimeException(message, new FileNotFoundException(message));
			}

			jsonSource = new StreamSource(jsonFile);
		} else {
			LOGGER.info("Using default, resources-based blockchain config");

			ClassLoader classLoader = BlockChain.class.getClassLoader();
			InputStream in = classLoader.getResourceAsStream("blockchain.json");
			jsonSource = new StreamSource(in);
		}

		try  {
			// Attempt to unmarshal JSON stream to BlockChain config
			blockchain = unmarshaller.unmarshal(jsonSource, BlockChain.class).getValue();
		} catch (UnmarshalException e) {
			Throwable linkedException = e.getLinkedException();
			if (linkedException instanceof XMLMarshalException) {
				String message = ((XMLMarshalException) linkedException).getInternalException().getLocalizedMessage();

				if (message == null && linkedException.getCause() != null && linkedException.getCause().getCause() != null )
					message = linkedException.getCause().getCause().getLocalizedMessage();

				if (message == null && linkedException.getCause() != null)
					message = linkedException.getCause().getLocalizedMessage();

				if (message == null)
					message = linkedException.getLocalizedMessage();

				if (message == null)
					message = e.getLocalizedMessage();

				LOGGER.error(message);
				throw new RuntimeException(message, e);
			}

			String message = "Failed to parse blockchain config file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		} catch (JAXBException e) {
			String message = "Unexpected JAXB issue while processing blockchain config file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}

		// Validate config
		blockchain.validateConfig();

		// Minor fix-up
		blockchain.fixUp();

		// Successfully read config now in effect
		instance = blockchain;

		// Pass genesis info to GenesisBlock
		GenesisBlock.newInstance(blockchain.genesisInfo);
	}

	// Getters / setters

	public boolean isTestChain() {
		return this.isTestChain;
	}

	public byte[] getMessageMagic(boolean isTestNet) {
		byte[] messageMagic = isTestNet ? this.testnetMessageMagicBytes : this.mainnetMessageMagicBytes;
		return Arrays.copyOf(messageMagic, messageMagic.length);
	}

	public int getMaxBytesPerUnitFee() {
		return this.maxBytesPerUnitFee;
	}

	public long getTransactionExpiryPeriod() {
		return this.transactionExpiryPeriod;
	}

	public long getBlockTimestampMargin() {
		return this.blockTimestampMargin;
	}

	public int getMaxBlockSize() {
		return this.maxBlockSize;
	}

	public int getChainParameterUpdateMinActivationDelay() {
		return this.chainParameterUpdateMinActivationDelay;
	}

	/* Block reward batching */
	public long getBlockRewardBatchStartHeight() {
		return this.blockRewardBatchStartHeight;
	}

	public int getBlockRewardBatchSize() {
		return this.blockRewardBatchSize;
	}

	public int getBlockRewardBatchAccountsBlockCount() {
		return this.blockRewardBatchAccountsBlockCount;
	}

	// Feature-trigger timestamp to modify behaviour of various transactions that support mempow
	public long getMemPoWTransactionUpdatesTimestamp() {
		return this.mempowTransactionUpdatesTimestamp;
	}

	/** Returns true if approval-needing transaction types require a txGroupId other than NO_GROUP. */
	public boolean getRequireGroupForApproval() {
		return this.requireGroupForApproval;
	}

	public List<Checkpoint> getCheckpoints() {
		return this.checkpoints == null ? Collections.emptyList() : this.checkpoints;
	}

	public List<RewardByHeight> getBlockRewardsByHeight() {
		return this.rewardsByHeight;
	}

	public List<AccountLevelShareBin> getAccountLevelShareBins() {
		return this.sharesByLevel;
	}

	public List<AccountLevelShareBin> getAccountLevelShareBins(Repository repository, int height) throws DataException {
		ChainParameterData rewardShareWeightsUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.REWARD_SHARE_WEIGHTS.id, height);
		if (rewardShareWeightsUpdate != null)
			return buildAccountLevelShareBinsFromWeights(
					ChainParameter.REWARD_SHARE_WEIGHTS.decodeIntArrayValue(rewardShareWeightsUpdate.getValue()));

		return getAccountLevelShareBins();
	}

	public AccountLevelShareBin[] getShareBinsByAccountLevel() {
		return this.shareBinsByLevel;
	}

	public AccountLevelShareBin[] getShareBinsByAccountLevel(Repository repository, int height) throws DataException {
		return buildShareBinsByAccountLevel(getAccountLevelShareBins(repository, height));
	}

	public int[] getRewardShareWeights() {
		return getRewardShareWeightsFromShareBins(this.sharesByLevel);
	}

	public int[] getRewardShareWeights(Repository repository, int height) throws DataException {
		ChainParameterData rewardShareWeightsUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.REWARD_SHARE_WEIGHTS.id, height);
		if (rewardShareWeightsUpdate != null)
			return ChainParameter.REWARD_SHARE_WEIGHTS.decodeIntArrayValue(rewardShareWeightsUpdate.getValue());

		return getRewardShareWeights();
	}

	public List<Integer> getBlocksNeededByLevel() {
		return this.blocksNeededByLevel;
	}

	public List<Integer> getCumulativeBlocksByLevel() {
		return this.cumulativeBlocksByLevel;
	}

	public int getMinAccountsToActivateShareBin() {
		return this.minAccountsToActivateShareBin;
	}

	public int getMinAccountsToActivateShareBin(Repository repository, int height) throws DataException {
		ChainParameterData minAccountsUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.id, height);
		if (minAccountsUpdate != null)
			return ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.decodeIntValue(minAccountsUpdate.getValue());

		return getMinAccountsToActivateShareBin();
	}

	public int getShareBinActivationMinLevel() {
		return this.shareBinActivationMinLevel;
	}

	public int getMinAccountLevelToMint() {
		return this.minAccountLevelToMint;
	}

	public int getMinAccountLevelForBlockSubmissions() {
		return this.minAccountLevelForBlockSubmissions;
	}

	public long getOnlineAccountSignaturesMinLifetime() {
		return this.onlineAccountSignaturesMinLifetime;
	}

	public long getOnlineAccountSignaturesMaxLifetime() {
		return this.onlineAccountSignaturesMaxLifetime;
	}

	public List<IdsForHeight> getMintingGroupIds() {
		return mintingGroupIds;
	}

	public List<IdsForHeight> getDevGroupIds() {
		return devGroupIds;
	}

	public AccountTrustSettings getAccountTrustSettings() {
		return this.accountTrustSettings;
	}

	public byte[] getAccountTrustCategoryPoliciesValue() {
		return AccountTrustCategoryPolicyCodec.encode(this.accountTrustSettings);
	}

	public byte[] getAccountTrustCategoryPoliciesValue(Repository repository, int height) throws DataException {
		ChainParameterData categoryPoliciesUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.id, height);
		if (categoryPoliciesUpdate != null)
			return categoryPoliciesUpdate.getValue();

		return getAccountTrustCategoryPoliciesValue();
	}

	public long getAccountTrustStartingEnergy() {
		return this.accountTrustSettings.getStartingEnergy();
	}

	public long getAccountTrustStartingEnergy(Repository repository, int height) throws DataException {
		ChainParameterData startingEnergyUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.id, height);
		if (startingEnergyUpdate != null)
			return ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.decodeLongValue(startingEnergyUpdate.getValue());

		return getAccountTrustStartingEnergy();
	}

	public int getAccountTrustManagerEnergyHops() {
		return this.accountTrustSettings.getManagerEnergyHops();
	}

	public int getAccountTrustManagerEnergyHops(Repository repository, int height) throws DataException {
		ChainParameterData managerEnergyHopsUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.id, height);
		if (managerEnergyHopsUpdate != null)
			return ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.decodeIntValue(managerEnergyHopsUpdate.getValue());

		return getAccountTrustManagerEnergyHops();
	}

	public int getAccountTrustPositiveMinBranchCount() {
		return this.accountTrustSettings.getPositiveMinBranchCount();
	}

	public int getAccountTrustPositiveMinBranchCount(Repository repository, int height) throws DataException {
		ChainParameterData positiveMinBranchCountUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.id, height);
		if (positiveMinBranchCountUpdate != null)
			return ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.decodeIntValue(
					positiveMinBranchCountUpdate.getValue());

		return getAccountTrustPositiveMinBranchCount();
	}

	public int getAccountTrustSuspiciousMinRaterCount() {
		return this.accountTrustSettings.getSuspiciousMinRaterCount();
	}

	public int getAccountTrustSuspiciousMinRaterCount(Repository repository, int height) throws DataException {
		ChainParameterData suspiciousMinRaterCountUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.id, height);
		if (suspiciousMinRaterCountUpdate != null)
			return ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.decodeIntValue(
					suspiciousMinRaterCountUpdate.getValue());

		return getAccountTrustSuspiciousMinRaterCount();
	}

	public int getAccountTrustSuspiciousMinBranchCount() {
		return this.accountTrustSettings.getSuspiciousMinBranchCount();
	}

	public int getAccountTrustSuspiciousMinBranchCount(Repository repository, int height) throws DataException {
		ChainParameterData suspiciousMinBranchCountUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.id, height);
		if (suspiciousMinBranchCountUpdate == null)
			return getAccountTrustSuspiciousMinBranchCount();

		int suspiciousMinBranchCount = ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.decodeIntValue(
				suspiciousMinBranchCountUpdate.getValue());
		return suspiciousMinBranchCount == 0
				? getAccountTrustSuspiciousMinRaterCount(repository, height)
				: suspiciousMinBranchCount;
	}

	public int getAccountTrustSuspiciousMinRatingConfidence() {
		return this.accountTrustSettings.getSuspiciousMinRatingConfidence();
	}

	public int getAccountTrustSuspiciousMinRatingConfidence(Repository repository, int height) throws DataException {
		ChainParameterData suspiciousMinRatingConfidenceUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.id, height);
		if (suspiciousMinRatingConfidenceUpdate != null)
			return ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.decodeIntValue(
					suspiciousMinRatingConfidenceUpdate.getValue());

		return getAccountTrustSuspiciousMinRatingConfidence();
	}

	public int getAccountRatingChangeCooldownBlocks() {
		return this.accountTrustSettings.getAccountRatingChangeCooldownBlocks();
	}

	public int getAccountRatingChangeCooldownBlocks(Repository repository, int height) throws DataException {
		ChainParameterData accountRatingCooldownUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.id, height);
		if (accountRatingCooldownUpdate != null)
			return ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.decodeIntValue(accountRatingCooldownUpdate.getValue());

		return getAccountRatingChangeCooldownBlocks();
	}

	public int[] getAccountTrustStatusVoteWeightPercents() {
		return this.accountTrustSettings.getStatusVoteWeightPercents();
	}

	public int[] getAccountTrustStatusVoteWeightPercents(Repository repository, int height) throws DataException {
		ChainParameterData statusVoteWeightUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.id, height);
		if (statusVoteWeightUpdate != null)
			return ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.decodeIntArrayValue(statusVoteWeightUpdate.getValue());

		return getAccountTrustStatusVoteWeightPercents();
	}

	public int getAccountTrustStatusVoteWeightPercent(Repository repository, int height, AccountTrustStatus status)
			throws DataException {
		return getAccountTrustStatusVoteWeightPercent(getAccountTrustStatusVoteWeightPercents(repository, height), status);
	}

	public static int getAccountTrustStatusVoteWeightPercent(int[] voteWeightPercents, AccountTrustStatus status) {
		AccountTrustStatus effectiveStatus = status == null ? AccountTrustStatus.UNVERIFIED : status;
		if (voteWeightPercents == null || voteWeightPercents.length <= effectiveStatus.ordinal())
			return 0;

		return voteWeightPercents[effectiveStatus.ordinal()];
	}

	public CiyamAtSettings getCiyamAtSettings() {
		return this.ciyamAtSettings;
	}

	// Convenience methods for specific blockchain feature triggers

	public long getTransactionV6Timestamp() {
		return this.featureTriggers.get(FeatureTrigger.transactionV6Timestamp.name()).longValue();
	}

	// More complex getters for aspects that change by height or timestamp

	public long getRewardAtHeight(int ourHeight) {
		// Scan through for reward at our height
		for (int i = rewardsByHeight.size() - 1; i >= 0; --i)
			if (rewardsByHeight.get(i).height <= ourHeight)
				return rewardsByHeight.get(i).reward;

		return 0;
	}

	public long getRewardAtHeight(Repository repository, int ourHeight) throws DataException {
		ChainParameterData rewardUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.BLOCK_REWARD.id, ourHeight);
		if (rewardUpdate != null)
			return ChainParameter.BLOCK_REWARD.decodeLongValue(rewardUpdate.getValue());

		return getRewardAtHeight(ourHeight);
	}

	public BlockTimingByHeight getBlockTimingByHeight(int ourHeight) {
		for (int i = blockTimingsByHeight.size() - 1; i >= 0; --i)
			if (blockTimingsByHeight.get(i).height <= ourHeight)
				return blockTimingsByHeight.get(i);

		throw new IllegalStateException(String.format("No block timing info available for height %d", ourHeight));
	}

	public long getUnitFeeAtTimestamp(long ourTimestamp) {
		for (int i = unitFees.size() - 1; i >= 0; --i)
			if (unitFees.get(i).timestamp <= ourTimestamp)
				return unitFees.get(i).fee;

		// Shouldn't happen, but set a sensible default just in case
		return 100000;
	}

	public long getUnitFeeAtHeight(Repository repository, int ourHeight, long fallbackTimestamp) throws DataException {
		ChainParameterData unitFeeUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.UNIT_FEE.id, ourHeight);
		if (unitFeeUpdate != null)
			return ChainParameter.UNIT_FEE.decodeLongValue(unitFeeUpdate.getValue());

		return getUnitFeeAtTimestamp(fallbackTimestamp);
	}

	public long getNameRegistrationUnitFeeAtTimestamp(long ourTimestamp) {
		for (int i = nameRegistrationUnitFees.size() - 1; i >= 0; --i)
			if (nameRegistrationUnitFees.get(i).timestamp <= ourTimestamp)
				return nameRegistrationUnitFees.get(i).fee;

		// Shouldn't happen, but set a sensible default just in case
		return 100000;
	}

	public long getNameRegistrationUnitFeeAtHeight(Repository repository, int ourHeight, long fallbackTimestamp) throws DataException {
		ChainParameterData nameRegistrationUnitFeeUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.NAME_REGISTRATION_UNIT_FEE.id, ourHeight);
		if (nameRegistrationUnitFeeUpdate != null)
			return ChainParameter.NAME_REGISTRATION_UNIT_FEE.decodeLongValue(nameRegistrationUnitFeeUpdate.getValue());

		return getNameRegistrationUnitFeeAtTimestamp(fallbackTimestamp);
	}

	public int getMaxRewardSharesAtTimestamp(long ourTimestamp) {
		for (int i = maxRewardSharesByTimestamp.size() - 1; i >= 0; --i)
			if (maxRewardSharesByTimestamp.get(i).timestamp <= ourTimestamp)
				return maxRewardSharesByTimestamp.get(i).maxShares;

		return 0;
	}

	/** Validate blockchain config read from JSON */
	private void validateConfig() {
		if (this.genesisInfo == null)
			Settings.throwValidationError("No \"genesisInfo\" entry found in blockchain config");

		if (this.rewardsByHeight == null)
			Settings.throwValidationError("No \"rewardsByHeight\" entry found in blockchain config");

		if (this.sharesByLevel == null)
			Settings.throwValidationError("No \"sharesByLevel\" entry found in blockchain config");

		if (this.blocksNeededByLevel == null)
			Settings.throwValidationError("No \"blocksNeededByLevel\" entry found in blockchain config");

		if (this.blockTimingsByHeight == null)
			Settings.throwValidationError("No \"blockTimingsByHeight\" entry found in blockchain config");

		if (this.blockTimestampMargin <= 0)
			Settings.throwValidationError("Invalid \"blockTimestampMargin\" in blockchain config");

		if (this.transactionExpiryPeriod <= 0)
			Settings.throwValidationError("Invalid \"transactionExpiryPeriod\" in blockchain config");

		if (this.maxBlockSize <= 0)
			Settings.throwValidationError("Invalid \"maxBlockSize\" in blockchain config");

		if (this.chainParameterUpdateMinActivationDelay <= 0)
			Settings.throwValidationError("Invalid \"chainParameterUpdateMinActivationDelay\" in blockchain config");

		byte[] mainnetMagic = decodeMessageMagic("mainnetMessageMagic", this.mainnetMessageMagic);
		byte[] testnetMagic = decodeMessageMagic("testnetMessageMagic", this.testnetMessageMagic);
		if (Arrays.equals(mainnetMagic, testnetMagic))
			Settings.throwValidationError("\"mainnetMessageMagic\" and \"testnetMessageMagic\" must be different");

		if (this.accountTrustSettings == null)
			Settings.throwValidationError("No \"accountTrustSettings\" entry found in blockchain config");

		this.accountTrustSettings.validate();

		if (this.ciyamAtSettings == null)
			Settings.throwValidationError("No \"ciyamAtSettings\" entry found in blockchain config");

		if (this.featureTriggers == null)
			Settings.throwValidationError("No \"featureTriggers\" entry found in blockchain config");

		// Check all featureTriggers are present
		for (FeatureTrigger featureTrigger : FeatureTrigger.values())
			if (!this.featureTriggers.containsKey(featureTrigger.name()))
                if(!defaultFeatureTriggerHeight.containsKey(featureTrigger))
				    Settings.throwValidationError(String.format("Missing feature trigger \"%s\" in blockchain config", featureTrigger.name()));
                else
                    featureTriggers.put(featureTrigger.name(), defaultFeatureTriggerHeight.get(featureTrigger));

		// Check block reward share bounds
		long totalShare = 0;
		// Add share percents for account-level-based rewards
		for (AccountLevelShareBin accountLevelShareBin : this.sharesByLevel)
			totalShare += accountLevelShareBin.share;

		if (totalShare < 0 || totalShare > 1_00000000L)
			Settings.throwValidationError("Total configured reward share out of bounds (0<x<1e8)");

		// Check that blockRewardBatchSize isn't zero
		if (this.blockRewardBatchSize <= 0)
			Settings.throwValidationError("\"blockRewardBatchSize\" must be greater than 0");

		// Check that blockRewardBatchStartHeight is a multiple of blockRewardBatchSize
		if (this.blockRewardBatchStartHeight % this.blockRewardBatchSize != 0)
			Settings.throwValidationError("\"blockRewardBatchStartHeight\" must be a multiple of \"blockRewardBatchSize\"");

		// Check that blockRewardBatchAccountsBlockCount isn't zero
		if (this.blockRewardBatchAccountsBlockCount <= 0)
			Settings.throwValidationError("\"blockRewardBatchAccountsBlockCount\" must be greater than 0");

		// Check that blockRewardBatchSize isn't zero
		if (this.blockRewardBatchAccountsBlockCount > this.blockRewardBatchSize)
			Settings.throwValidationError("\"blockRewardBatchAccountsBlockCount\" must be less than or equal to \"blockRewardBatchSize\"");
	}

	private static byte[] decodeMessageMagic(String fieldName, String messageMagic) {
		if (messageMagic == null)
			Settings.throwValidationError(String.format("No \"%s\" entry found in blockchain config", fieldName));

		if (messageMagic.length() != 4)
			Settings.throwValidationError(String.format("\"%s\" must be exactly 4 ASCII characters", fieldName));

		for (int i = 0; i < messageMagic.length(); ++i)
			if (messageMagic.charAt(i) > 0x7f)
				Settings.throwValidationError(String.format("\"%s\" must contain only ASCII characters", fieldName));

		return messageMagic.getBytes(StandardCharsets.US_ASCII);
	}

	/** Minor normalization, cached value generation, etc. */
	private void fixUp() {
		this.mainnetMessageMagicBytes = decodeMessageMagic("mainnetMessageMagic", this.mainnetMessageMagic);
		this.testnetMessageMagicBytes = decodeMessageMagic("testnetMessageMagic", this.testnetMessageMagic);
		this.accountTrustSettings.fixUp();

		// Calculate cumulative blocks required for each level
		int cumulativeBlocks = 0;
		this.cumulativeBlocksByLevel = new ArrayList<>(this.blocksNeededByLevel.size() + 1);
		for (int level = 0; level <= this.blocksNeededByLevel.size(); ++level) {
			this.cumulativeBlocksByLevel.add(cumulativeBlocks);

			if (level < this.blocksNeededByLevel.size())
				cumulativeBlocks += this.blocksNeededByLevel.get(level);
		}

		// Generate lookup-array for account-level share bins
		this.shareBinsByLevel = buildShareBinsByAccountLevel(this.sharesByLevel);

		// Convert collections to unmodifiable form
		this.rewardsByHeight = Collections.unmodifiableList(this.rewardsByHeight);
		this.sharesByLevel = Collections.unmodifiableList(this.sharesByLevel);
		this.blocksNeededByLevel = Collections.unmodifiableList(this.blocksNeededByLevel);
		this.cumulativeBlocksByLevel = Collections.unmodifiableList(this.cumulativeBlocksByLevel);
		this.blockTimingsByHeight = Collections.unmodifiableList(this.blockTimingsByHeight);
		this.devGroupIds = this.devGroupIds == null ? Collections.emptyList() : Collections.unmodifiableList(this.devGroupIds);
	}

	private static List<AccountLevelShareBin> buildAccountLevelShareBinsFromWeights(int[] weights) {
		long totalWeight = 0;
		int lastPositiveWeightIndex = -1;
		for (int i = 0; i < weights.length; ++i) {
			if (weights[i] > 0)
				lastPositiveWeightIndex = i;

			totalWeight += weights[i];
		}

		List<AccountLevelShareBin> shareBins = new ArrayList<>(weights.length);
		long remainingShare = Amounts.MULTIPLIER;
		for (int i = 0; i < weights.length; ++i) {
			AccountLevelShareBin shareBin = new AccountLevelShareBin();
			shareBin.id = i + 1;
			shareBin.levels = Collections.singletonList(i + 1);

			if (weights[i] == 0) {
				shareBin.share = 0L;
			} else if (i == lastPositiveWeightIndex) {
				shareBin.share = remainingShare;
			} else {
				shareBin.share = Amounts.scaledDivide(weights[i], totalWeight);
				remainingShare -= shareBin.share;
			}

			shareBins.add(shareBin);
		}

		return Collections.unmodifiableList(shareBins);
	}

	private static AccountLevelShareBin[] buildShareBinsByAccountLevel(List<AccountLevelShareBin> shareBins) {
		AccountLevelShareBin lastAccountLevelShareBin = shareBins.get(shareBins.size() - 1);
		final int lastLevel = lastAccountLevelShareBin.levels.get(lastAccountLevelShareBin.levels.size() - 1);
		AccountLevelShareBin[] shareBinsByLevel = new AccountLevelShareBin[lastLevel];

		for (AccountLevelShareBin accountLevelShareBin : shareBins)
			for (int level : accountLevelShareBin.levels)
				// level 1 stored at index 0, level 2 stored at index 1, etc.
				// level 0 not allowed
				shareBinsByLevel[level - 1] = accountLevelShareBin;

		return shareBinsByLevel;
	}

	private static int[] getRewardShareWeightsFromShareBins(List<AccountLevelShareBin> shareBins) {
		int[] weights = new int[REWARD_SHARE_LEVEL_COUNT];

		for (AccountLevelShareBin shareBin : shareBins) {
			for (int level : shareBin.levels) {
				if (level < 1 || level > weights.length)
					continue;

				weights[level - 1] = (int) shareBin.share;
			}
		}

		return weights;
	}

	/**
	 * Some sort of start-up/initialization/checking method.
	 *
	 * @throws SQLException
	 */
	public static void validate() throws DataException {

		Settings settings = Settings.getInstance();
		boolean isTopOnly = settings.isTopOnly();
		boolean archiveEnabled = settings.isArchiveEnabled();
		boolean isLite = settings.isLite();
		boolean isSingleNodeTestnet = settings.isSingleNodeTestnet();
		boolean bootstrapEnabled = settings.getBootstrap();
		boolean hasBootstrapHostsConfigured = settings.hasBootstrapHostsConfigured();
		boolean canBootstrap = bootstrapEnabled && hasBootstrapHostsConfigured;
		boolean needsArchiveRebuild = false;
		int checkHeight = 0;
		BlockData chainTip;

		try (final Repository repository = RepositoryManager.getRepository()) {
			chainTip = repository.getBlockRepository().getLastBlock();
			checkHeight = repository.getBlockRepository().getBlockchainHeight();

			// Ensure archive is (at least partially) intact, and force a bootstrap if it isn't
			if (!isTopOnly && archiveEnabled && canBootstrap) {
				needsArchiveRebuild = (repository.getBlockArchiveRepository().fromHeight(2) == null);
				if (needsArchiveRebuild) {
					LOGGER.info("Couldn't retrieve block 2 from archive. Bootstrapping...");

					// If there are minting accounts, make sure to back them up
					// Don't backup if there are no minting accounts, as this can cause problems
					if (!repository.getAccountRepository().getMintingAccounts().isEmpty()) {
						Controller.getInstance().exportRepositoryData();
					}
				}
			}

			if (!canBootstrap && !isSingleNodeTestnet) {
				if (checkHeight > 2) {
					LOGGER.info("Retrieved block 2 from archive. Syncing from genesis block resumed!");
				} else {
					needsArchiveRebuild = (repository.getBlockArchiveRepository().fromHeight(2) == null);
					if (needsArchiveRebuild) {
						if (bootstrapEnabled && !hasBootstrapHostsConfigured) {
							LOGGER.info("Couldn't retrieve block 2 from archive. {} Syncing from genesis block!", Bootstrap.MISSING_BOOTSTRAP_HOSTS_MESSAGE);
						} else {
							LOGGER.info("Couldn't retrieve block 2 from archive. Bootstrapping is disabled. Syncing from genesis block!");
						}
					}
				}
			}

			// Validate checkpoints
			// Limited to topOnly nodes for now, in order to reduce risk, and to solve a real-world problem with divergent topOnly nodes
			// TODO: remove the isTopOnly conditional below once this feature has had more testing time
			if (isTopOnly && !isLite) {
				List<Checkpoint> checkpoints = BlockChain.getInstance().getCheckpoints();
				for (Checkpoint checkpoint : checkpoints) {
					BlockData blockData = repository.getBlockRepository().fromHeight(checkpoint.height);
					if (blockData == null) {
						// Try the archive
						blockData = repository.getBlockArchiveRepository().fromHeight(checkpoint.height);
					}
					if (blockData == null) {
						LOGGER.trace("Couldn't find block for height {}", checkpoint.height);
						// This is likely due to the block being pruned, so is safe to ignore.
						// Continue, as there might be other blocks we can check more definitively.
						continue;
					}

					byte[] signature = Base58.decode(checkpoint.signature);
					if (!Arrays.equals(signature, blockData.getSignature())) {
						LOGGER.info("Error: block at height {} with signature {} doesn't match checkpoint sig: {}. Bootstrapping...", checkpoint.height, Base58.encode(blockData.getSignature()), checkpoint.signature);
						needsArchiveRebuild = true;
						break;
					}
					LOGGER.info("Block at height {} matches checkpoint signature", blockData.getHeight());
				}
			}

		}

		// Check first block is Genesis Block
		if (!isGenesisBlockValid() || needsArchiveRebuild) {
			if (checkHeight < 3) {
				try {
					rebuildBlockchain();
				} catch (InterruptedException e) {
					throw new DataException(String.format("Interrupted when trying to rebuild blockchain: %s", e.getMessage()));
				}
			}
		}

		// We need to create a new connection, as the previous repository and its connections may be been
		// closed by rebuildBlockchain() if a bootstrap was applied
		try (final Repository repository = RepositoryManager.getRepository()) {
			repository.checkConsistency();

			int blocksToValidate = Math.min(Settings.getInstance().getPruneBlockLimit() - 10, 1440);

			int startHeight = Math.max(repository.getBlockRepository().getBlockchainHeight() - blocksToValidate, 1);
			BlockData detachedBlockData = repository.getBlockRepository().getDetachedBlockSignature(startHeight);

			if (detachedBlockData != null) {
				LOGGER.error(String.format("Block %d's reference does not match any block's signature",
						detachedBlockData.getHeight()));
				LOGGER.error(String.format("Your chain may be invalid and you should consider bootstrapping" +
						" or re-syncing from genesis."));
			}
		}
	}

	/**
	 * More thorough blockchain validation method. Useful for validating bootstraps.
	 * A DataException is thrown if anything is invalid.
	 *
	 * @throws DataException
	 */
	public static void validateAllBlocks() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockData chainTip = repository.getBlockRepository().getLastBlock();
			final int chainTipHeight = chainTip.getHeight();
			final int oldestBlock = 1; // TODO: increase if in pruning mode
			byte[] lastReference = null;

			for (int height = chainTipHeight; height > oldestBlock; height--) {
				BlockData blockData = repository.getBlockRepository().fromHeight(height);
				if (blockData == null) {
					blockData = repository.getBlockArchiveRepository().fromHeight(height);
				}

				if (blockData == null) {
					String error = String.format("Missing block at height %d", height);
					LOGGER.error(error);
					throw new DataException(error);
				}

				if (height != chainTipHeight) {
					// Check reference
					if (!Arrays.equals(blockData.getSignature(), lastReference)) {
						String error = String.format("Invalid reference for block at height %d: %s (should be %s)",
								height, Base58.encode(blockData.getReference()), Base58.encode(lastReference));
						LOGGER.error(error);
						throw new DataException(error);
					}
				}

				lastReference = blockData.getReference();
			}
		}
	}

	private static boolean isGenesisBlockValid() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockRepository blockRepository = repository.getBlockRepository();

			int blockchainHeight = blockRepository.getBlockchainHeight();
			if (blockchainHeight < 1)
				return false;

			BlockData blockData = blockRepository.fromHeight(1);
			if (blockData == null)
				return false;

			return GenesisBlock.isGenesisBlock(blockData);
		} catch (DataException e) {
			return false;
		}
	}

	private static void rebuildBlockchain() throws DataException, InterruptedException {
		Settings settings = Settings.getInstance();
		boolean shouldBootstrap = settings.getBootstrap();
		if (shouldBootstrap && settings.hasBootstrapHostsConfigured()) {
			// Settings indicate that we should apply a bootstrap rather than rebuilding and syncing from genesis
			Bootstrap bootstrap = new Bootstrap();
			bootstrap.startImport();
			return;
		}

		if (shouldBootstrap) {
			LOGGER.warn("{} Rebuilding repository and syncing from genesis instead.", Bootstrap.MISSING_BOOTSTRAP_HOSTS_MESSAGE);
		}

		// (Re)build repository
		if (!RepositoryManager.wasPristineAtOpen())
			RepositoryManager.rebuild();

		try (final Repository repository = RepositoryManager.getRepository()) {
			GenesisBlock genesisBlock = GenesisBlock.getInstance(repository);

			// Add Genesis Block to blockchain
			genesisBlock.process();

			repository.saveChanges();

			// Give Network a chance to install initial seed peers
			Network.installInitialPeers(repository);
		}
	}

	public static boolean orphan(int targetHeight) throws DataException {
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		if (!blockchainLock.tryLock())
			return false;

		try {
			try (final Repository repository = RepositoryManager.getRepository()) {
				int height = repository.getBlockRepository().getBlockchainHeight();
				BlockData orphanBlockData = repository.getBlockRepository().fromHeight(height);

				while (height > targetHeight) {
					if (Controller.isStopping()) {
						return false;
					}
					LOGGER.info(String.format("Forcably orphaning block %d", height));

					Block block = new Block(repository, orphanBlockData);
					block.orphan();

					repository.saveChanges();

					--height;
					orphanBlockData = repository.getBlockRepository().fromHeight(height);

					repository.discardChanges(); // clear transaction status to prevent deadlocks
					Controller.getInstance().onOrphanedBlock(orphanBlockData);
				}

				return true;
			}
		} finally {
			blockchainLock.unlock();
		}
	}
}
