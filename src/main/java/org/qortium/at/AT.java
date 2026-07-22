package org.qortium.at;

import com.google.common.primitives.Bytes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ciyam.at.MachineState;
import org.ciyam.at.Timestamp;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATData;
import org.qortium.data.at.ATStateData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.repository.ATRepository;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.AtTransaction;
import org.qortium.transaction.DeployAtTransaction;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AT {

	private static final Logger LOGGER = LogManager.getLogger(AT.class);

	// Properties
	private Repository repository;
	private ATData atData;
	private ATStateData atStateData;

	// Constructors

	public AT(Repository repository, ATData atData, ATStateData atStateData) {
		this.repository = repository;
		this.atData = atData;
		this.atStateData = atStateData;
	}

	public AT(Repository repository, ATData atData) {
		this(repository, atData, null);
	}

	/** Constructs AT-handling object when deploying AT */
	public AT(Repository repository, DeployAtTransactionData deployATTransactionData) throws DataException {
		this.repository = repository;

		String atAddress = deployATTransactionData.getAtAddress();
		int height = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		byte[] creatorPublicKey = deployATTransactionData.getCreatorPublicKey();
		long creation = deployATTransactionData.getTimestamp();
		long assetId = deployATTransactionData.getAssetId();

		// Just enough AT data to allow API to query initial balances, etc.
		ATData skeletonAtData = new ATData(atAddress, creatorPublicKey, creation, assetId);

		long blockTimestamp = Timestamp.toLong(height, 0);
		ChainATAPI api = new ChainATAPI(repository, skeletonAtData, height, blockTimestamp, null);
		ChainAtLoggerFactory loggerFactory = ChainAtLoggerFactory.getInstance();

		MachineState machineState = new MachineState(api, loggerFactory, deployATTransactionData.getCreationBytes());

		byte[] codeBytes = machineState.getCodeBytes();
		byte[] codeHash = Crypto.digest(codeBytes);

		this.atData = new ATData(atAddress, creatorPublicKey, creation, machineState.version, assetId, codeBytes, codeHash,
				machineState.isSleeping(), machineState.getSleepUntilHeight(), machineState.isFinished(), machineState.hadFatalError(),
				machineState.isFrozen(), machineState.getFrozenBalance(), null);

		byte[] stateData = machineState.toBytes();
		byte[] mapRoot = height >= BlockChain.getInstance().getAtMapStorageHeight()
				? ATMapExecutionContext.emptyMapRoot()
				: null;
		byte[] stateHash = calculateStateHash(stateData, mapRoot);

		this.atStateData = new ATStateData(atAddress, height, stateData, stateHash, mapRoot, 0L, true, null);
	}

	// Getters / setters

	public ATStateData getATStateData() {
		return this.atStateData;
	}

	// Processing

	public void deploy() throws DataException {
		ATRepository atRepository = this.repository.getATRepository();
		atRepository.save(this.atData);

		atRepository.save(this.atStateData);
	}

	public void undeploy() throws DataException {
		// AT states deleted implicitly by repository
		this.repository.getATRepository().delete(this.atData.getATAddress());
	}

	/**
	 * Potentially execute AT.
	 * <p>
	 * Note that sleep-until-message support might set/reset
	 * sleep-related flags/values.
	 * <p>
	 * {@link #getATStateData()} will return null if nothing happened.
	 * <p>
	 * @param blockHeight
	 * @param blockTimestamp
	 * @return AT-generated transactions, possibly empty
	 * @throws DataException
	 */
	public List<AtTransaction> run(int blockHeight, long blockTimestamp) throws DataException {
		return this.run(blockHeight, blockTimestamp, null);
	}

	/** Potentially execute AT using the block-scoped persistent-map overlay. */
	public List<AtTransaction> run(int blockHeight, long blockTimestamp, ATMapExecutionContext mapContext)
			throws DataException {
		String atAddress = this.atData.getATAddress();
		boolean mapStorageActive = blockHeight >= BlockChain.getInstance().getAtMapStorageHeight();
		if (mapStorageActive && mapContext == null)
			throw new IllegalArgumentException("Active AT map storage requires a block execution context");

		ChainATAPI api = new ChainATAPI(repository, this.atData, blockHeight, blockTimestamp, mapContext);
		ChainAtLoggerFactory loggerFactory = ChainAtLoggerFactory.getInstance();

		if (!api.willExecute(blockHeight))
			// this.atStateData will be null
			return Collections.emptyList();

		// Fetch latest ATStateData for this AT
		ATStateData latestAtStateData = this.repository.getATRepository().getLatestATState(atAddress);

		// There should be at least initial deployment AT state data
		if (latestAtStateData == null)
			throw new IllegalStateException("No previous AT state data found");

		// [Re]create AT machine state using AT state data or from scratch as applicable
		byte[] codeBytes = this.atData.getCodeBytes();
		MachineState state = MachineState.fromBytes(api, loggerFactory, latestAtStateData.getStateData(), codeBytes);
		if (mapStorageActive)
			mapContext.beginRound(atAddress);

		try {
			api.preExecute(state);
			state.execute();
		} catch (Exception e) {
			if (mapStorageActive)
				mapContext.rollbackRound();
			throw new DataException(String.format("Uncaught exception while running AT '%s'", atAddress), e);
		}

		byte[] stateData = state.toBytes();

		// An AT whose runtime state outgrows the storage limit must not take the block down with it.
		// Deploy-time validation bounds this for newly deployed ATs; this contains anything deployed
		// before that bound existed. Skipping changes the block's AT count/fees/state-hashes, which block
		// validation compares across nodes, so it MUST gate on the same feature trigger as the deploy-time
		// bound: an ungated skip would make updated and un-updated nodes deterministically disagree on any
		// block containing such an AT. Below the trigger we fall through to the old behaviour (persist the
		// oversized state), matching un-updated nodes.
		if (stateData.length > DeployAtTransaction.MAX_AT_STATE_LENGTH
				&& blockHeight >= BlockChain.getInstance().getAtPayoutSolvencyHeight()) {
			LOGGER.error(String.format("AT %s produced oversized state (%d bytes, limit %d) - skipping round",
					atAddress, stateData.length, DeployAtTransaction.MAX_AT_STATE_LENGTH));
			if (mapStorageActive)
				mapContext.rollbackRound();
			// this.atStateData stays null
			return Collections.emptyList();
		}

		byte[] mapRoot = mapStorageActive ? mapContext.getMapRoot(atAddress) : null;
		byte[] stateHash = calculateStateHash(stateData, mapRoot);

		// Nothing happened?
		if (state.getSteps() == 0 && Arrays.equals(stateHash, latestAtStateData.getStateHash())) {
			// We currently want to execute frozen ATs, to maintain backwards support.
			if (!state.isFrozen()) {
				// this.atStateData will be null
				if (mapStorageActive)
					mapContext.rollbackRound();
				// No accepted state means no map changes from this round can survive.
				return Collections.emptyList();
			}
		}

		long atFees = api.calcFinalFees(state);
		Long sleepUntilMessageTimestamp = this.atData.getSleepUntilMessageTimestamp();

		this.atStateData = new ATStateData(atAddress, blockHeight, stateData, stateHash, mapRoot, atFees, false,
				sleepUntilMessageTimestamp);

		if (mapStorageActive)
			mapContext.commitRound();

		return api.getTransactions();
	}

	private static byte[] calculateStateHash(byte[] stateData, byte[] mapRoot) {
		return mapRoot == null ? Crypto.digest(stateData) : Crypto.digest(Bytes.concat(stateData, mapRoot));
	}

	public void update(int blockHeight, long blockTimestamp) throws DataException {
		// Extract minimal/flags-only AT machine state using AT state data
		MachineState state = MachineState.flagsOnlyfromBytes(this.atStateData.getStateData());

		// Save latest AT state data
		this.repository.getATRepository().save(this.atStateData);

		// Update AT info in repository too
		this.atData.setIsSleeping(state.isSleeping());
		this.atData.setSleepUntilHeight(state.getSleepUntilHeight());
		this.atData.setIsFinished(state.isFinished());
		this.atData.setHadFatalError(state.hadFatalError());
		this.atData.setIsFrozen(state.isFrozen());
		this.atData.setFrozenBalance(state.getFrozenBalance());

		// Special sleep-until-message support
		this.atData.setSleepUntilMessageTimestamp(this.atStateData.getSleepUntilMessageTimestamp());

		this.repository.getATRepository().save(this.atData);
	}

	public void revert(int blockHeight, long blockTimestamp) throws DataException {
		String atAddress = this.atData.getATAddress();

		// Delete old AT state data from repository
		this.repository.getATRepository().delete(atAddress, blockHeight);

		if (this.atStateData.isInitial())
			return;

		// Load previous state data
		ATStateData previousStateData = this.repository.getATRepository().getLatestATState(atAddress);
		if (previousStateData == null)
			throw new DataException("Can't find previous AT state data for " + atAddress);

		// Extract minimal/flags-only AT machine state using AT state data
		MachineState state = MachineState.flagsOnlyfromBytes(previousStateData.getStateData());

		// Update AT info in repository
		this.atData.setIsSleeping(state.isSleeping());
		this.atData.setSleepUntilHeight(state.getSleepUntilHeight());
		this.atData.setIsFinished(state.isFinished());
		this.atData.setHadFatalError(state.hadFatalError());
		this.atData.setIsFrozen(state.isFrozen());
		this.atData.setFrozenBalance(state.getFrozenBalance());

		// Special sleep-until-message support
		this.atData.setSleepUntilMessageTimestamp(previousStateData.getSleepUntilMessageTimestamp());

		this.repository.getATRepository().save(this.atData);
	}

}
