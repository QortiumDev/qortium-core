package org.qortal.crosschain;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.params.AbstractBitcoinNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

final class StaticBitcoinyParams extends AbstractBitcoinNetParams {

	private final String paymentProtocolId;
	private final String uriScheme;
	private final long genesisVersion;
	private final long genesisTime;
	private final long genesisNonce;
	private final long genesisDifficultyTarget;
	private final Sha256Hash genesisMerkleRoot;
	private final byte[] genesisCoinbaseScript;
	private final Coin genesisOutputValue;
	private final byte[] genesisOutputScript;
	private final Sha256Hash genesisHash;
	private final String difficultyValidationFailure;
	private final Coin maxMoney;
	private final Coin minNonDustOutput;
	private final MonetaryFormat monetaryFormat;

	private StaticBitcoinyParams(Builder builder) {
		this.id = builder.id;
		this.paymentProtocolId = builder.paymentProtocolId;
		this.uriScheme = builder.uriScheme;
		this.genesisVersion = builder.genesisVersion;
		this.genesisTime = builder.genesisTime;
		this.genesisNonce = builder.genesisNonce;
		this.genesisDifficultyTarget = builder.genesisDifficultyTarget;
		this.genesisMerkleRoot = builder.genesisMerkleRoot == null ? null : Sha256Hash.wrap(builder.genesisMerkleRoot);
		this.genesisCoinbaseScript = builder.genesisCoinbaseScript == null ? null : Utils.HEX.decode(builder.genesisCoinbaseScript);
		this.genesisOutputValue = builder.genesisOutputValue;
		this.genesisOutputScript = builder.genesisOutputScript == null ? null : Utils.HEX.decode(builder.genesisOutputScript);
		this.genesisHash = Sha256Hash.wrap(builder.genesisHash);
		this.difficultyValidationFailure = builder.difficultyValidationFailure;
		this.maxMoney = builder.maxMoney;
		this.minNonDustOutput = builder.minNonDustOutput;
		this.monetaryFormat = builder.monetaryFormat;

		this.targetTimespan = builder.targetTimespan;
		this.interval = builder.interval;
		this.maxTarget = Utils.decodeCompactBits(builder.maxTargetCompact);
		this.port = builder.port;
		this.packetMagic = builder.packetMagic;
		this.dumpedPrivateKeyHeader = builder.dumpedPrivateKeyHeader;
		this.addressHeader = builder.addressHeader;
		this.p2shHeader = builder.p2shHeader;
		this.segwitAddressHrp = builder.segwitAddressHrp;
		this.spendableCoinbaseDepth = builder.spendableCoinbaseDepth;
		this.subsidyDecreaseBlockCount = builder.subsidyDecreaseBlockCount;
		this.bip32HeaderP2PKHpub = builder.bip32HeaderP2PKHpub;
		this.bip32HeaderP2PKHpriv = builder.bip32HeaderP2PKHpriv;
		this.bip32HeaderP2WPKHpub = builder.bip32HeaderP2WPKHpub;
		this.bip32HeaderP2WPKHpriv = builder.bip32HeaderP2WPKHpriv;
		this.majorityEnforceBlockUpgrade = builder.majorityEnforceBlockUpgrade;
		this.majorityRejectBlockOutdated = builder.majorityRejectBlockOutdated;
		this.majorityWindow = builder.majorityWindow;
		this.dnsSeeds = Arrays.copyOf(builder.dnsSeeds, builder.dnsSeeds.length);
	}

	static Builder builder(String id, String paymentProtocolId, String uriScheme) {
		return new Builder(id, paymentProtocolId, uriScheme);
	}

	@Override
	public Block getGenesisBlock() {
		synchronized (this.genesisHash) {
			if (this.genesisBlock == null) {
				if (this.genesisMerkleRoot == null) {
					this.genesisBlock = Block.createGenesis(this);
					this.genesisBlock.setDifficultyTarget(this.genesisDifficultyTarget);
					this.genesisBlock.setTime(this.genesisTime);
					this.genesisBlock.setNonce(this.genesisNonce);
				} else {
					List<Transaction> genesisTransactions = this.genesisCoinbaseScript == null ? List.of() : List.of(createGenesisTransaction());
					this.genesisBlock = new Block(this, this.genesisVersion, Sha256Hash.ZERO_HASH, this.genesisMerkleRoot,
							this.genesisTime, this.genesisDifficultyTarget, this.genesisNonce, genesisTransactions);
				}

				if (!this.genesisBlock.getHash().equals(this.genesisHash))
					throw new IllegalStateException("Invalid genesis hash for " + this.id);
			}
		}

		return this.genesisBlock;
	}

	private Transaction createGenesisTransaction() {
		try {
			Transaction transaction = new Transaction(this);
			transaction.addInput(new TransactionInput(this, transaction, this.genesisCoinbaseScript));

			ByteArrayOutputStream outputScript = new ByteArrayOutputStream();
			Script.writeBytes(outputScript, this.genesisOutputScript);
			outputScript.write(0xac);

			transaction.addOutput(new TransactionOutput(this, transaction, this.genesisOutputValue, outputScript.toByteArray()));
			return transaction;
		} catch (IOException e) {
			throw new IllegalStateException("Invalid genesis transaction for " + this.id, e);
		}
	}

	@Override
	public String getPaymentProtocolId() {
		return this.paymentProtocolId;
	}

	@Override
	public String getUriScheme() {
		return this.uriScheme;
	}

	@Override
	public Coin getMaxMoney() {
		return this.maxMoney != null ? this.maxMoney : super.getMaxMoney();
	}

	@Override
	public Coin getMinNonDustOutput() {
		return this.minNonDustOutput != null ? this.minNonDustOutput : super.getMinNonDustOutput();
	}

	@Override
	public MonetaryFormat getMonetaryFormat() {
		return this.monetaryFormat != null ? this.monetaryFormat : super.getMonetaryFormat();
	}

	@Override
	public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore) throws VerificationException, BlockStoreException {
		throw new VerificationException(this.difficultyValidationFailure);
	}

	static final class Builder {
		private final String id;
		private final String paymentProtocolId;
		private final String uriScheme;

		private long genesisVersion = Block.BLOCK_VERSION_GENESIS;
		private long genesisTime;
		private long genesisNonce;
		private long genesisDifficultyTarget;
		private String genesisMerkleRoot;
		private String genesisCoinbaseScript;
		private Coin genesisOutputValue;
		private String genesisOutputScript;
		private String genesisHash;
		private long maxTargetCompact;
		private int targetTimespan = NetworkParameters.TARGET_TIMESPAN;
		private int interval = NetworkParameters.INTERVAL;
		private int port;
		private long packetMagic;
		private int dumpedPrivateKeyHeader;
		private int addressHeader;
		private int p2shHeader;
		private String segwitAddressHrp;
		private int spendableCoinbaseDepth = 100;
		private int subsidyDecreaseBlockCount = 210000;
		private int bip32HeaderP2PKHpub;
		private int bip32HeaderP2PKHpriv;
		private int bip32HeaderP2WPKHpub;
		private int bip32HeaderP2WPKHpriv;
		private int majorityEnforceBlockUpgrade;
		private int majorityRejectBlockOutdated;
		private int majorityWindow;
		private String[] dnsSeeds = new String[0];
		private String difficultyValidationFailure = "Difficulty verification is not implemented for these Electrum-backed parameters";
		private Coin maxMoney;
		private Coin minNonDustOutput;
		private MonetaryFormat monetaryFormat;

		private Builder(String id, String paymentProtocolId, String uriScheme) {
			this.id = id;
			this.paymentProtocolId = paymentProtocolId;
			this.uriScheme = uriScheme;
		}

		Builder genesis(long time, long nonce, long difficultyTarget, String hash) {
			this.genesisTime = time;
			this.genesisNonce = nonce;
			this.genesisDifficultyTarget = difficultyTarget;
			this.genesisHash = hash;
			return this;
		}

		Builder genesisHeader(long version, String merkleRoot) {
			this.genesisVersion = version;
			this.genesisMerkleRoot = merkleRoot;
			return this;
		}

		Builder genesisTransaction(String coinbaseScript, Coin outputValue, String outputScript) {
			this.genesisCoinbaseScript = coinbaseScript;
			this.genesisOutputValue = outputValue;
			this.genesisOutputScript = outputScript;
			return this;
		}

		Builder maxTarget(long maxTargetCompact) {
			this.maxTargetCompact = maxTargetCompact;
			return this;
		}

		Builder targetTimespan(int targetTimespan) {
			this.targetTimespan = targetTimespan;
			return this;
		}

		Builder interval(int interval) {
			this.interval = interval;
			return this;
		}

		Builder port(int port) {
			this.port = port;
			return this;
		}

		Builder packetMagic(long packetMagic) {
			this.packetMagic = packetMagic;
			return this;
		}

		Builder addressHeaders(int addressHeader, int p2shHeader, int dumpedPrivateKeyHeader) {
			this.addressHeader = addressHeader;
			this.p2shHeader = p2shHeader;
			this.dumpedPrivateKeyHeader = dumpedPrivateKeyHeader;
			return this;
		}

		Builder segwitAddressHrp(String segwitAddressHrp) {
			this.segwitAddressHrp = segwitAddressHrp;
			return this;
		}

		Builder coinbaseAndSubsidy(int spendableCoinbaseDepth, int subsidyDecreaseBlockCount) {
			this.spendableCoinbaseDepth = spendableCoinbaseDepth;
			this.subsidyDecreaseBlockCount = subsidyDecreaseBlockCount;
			return this;
		}

		Builder bip32Headers(int p2pkhPublic, int p2pkhPrivate) {
			this.bip32HeaderP2PKHpub = p2pkhPublic;
			this.bip32HeaderP2PKHpriv = p2pkhPrivate;
			return this;
		}

		Builder bip32SegwitHeaders(int p2wpkhPublic, int p2wpkhPrivate) {
			this.bip32HeaderP2WPKHpub = p2wpkhPublic;
			this.bip32HeaderP2WPKHpriv = p2wpkhPrivate;
			return this;
		}

		Builder majorityWindow(int enforceBlockUpgrade, int rejectBlockOutdated, int window) {
			this.majorityEnforceBlockUpgrade = enforceBlockUpgrade;
			this.majorityRejectBlockOutdated = rejectBlockOutdated;
			this.majorityWindow = window;
			return this;
		}

		Builder dnsSeeds(String... dnsSeeds) {
			this.dnsSeeds = Arrays.copyOf(dnsSeeds, dnsSeeds.length);
			return this;
		}

		Builder maxMoney(Coin maxMoney) {
			this.maxMoney = maxMoney;
			return this;
		}

		Builder minNonDustOutput(Coin minNonDustOutput) {
			this.minNonDustOutput = minNonDustOutput;
			return this;
		}

		Builder monetaryFormat(MonetaryFormat monetaryFormat) {
			this.monetaryFormat = monetaryFormat;
			return this;
		}

		Builder difficultyValidationFailure(String difficultyValidationFailure) {
			this.difficultyValidationFailure = difficultyValidationFailure;
			return this;
		}

		StaticBitcoinyParams build() {
			return new StaticBitcoinyParams(this);
		}
	}
}
