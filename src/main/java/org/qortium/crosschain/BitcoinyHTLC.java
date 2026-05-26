package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.*;
import org.bitcoinj.core.Transaction.SigHash;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.qortium.crypto.Crypto;
import org.qortium.utils.Base58;
import org.qortium.utils.BitTwiddling;

import java.util.*;
import java.util.function.Function;

public class BitcoinyHTLC {

	public enum Status {
		UNFUNDED, FUNDING_IN_PROGRESS, FUNDED, REDEEM_IN_PROGRESS, REDEEMED, REFUND_IN_PROGRESS, REFUNDED
	}

	public static final int SECRET_LENGTH = 32;
	public static final int MIN_LOCKTIME = 1500000000;

	public static final long NO_LOCKTIME_NO_RBF_SEQUENCE = 0xFFFFFFFFL;
	public static final long LOCKTIME_NO_RBF_SEQUENCE = NO_LOCKTIME_NO_RBF_SEQUENCE - 1;

	// Assuming node's trade-bot has no more than 100 entries?
	private static final int MAX_CACHE_ENTRIES = 100;

	// Max time-to-live for cache entries (milliseconds)
	private static final long CACHE_TIMEOUT = 30_000L;

	@SuppressWarnings("serial")
	private static final Map<String, byte[]> SECRET_CACHE = new LinkedHashMap<>(MAX_CACHE_ENTRIES + 1, 0.75F, true) {
		// This method is called just after a new entry has been added
		@Override
		public boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
			return size() > MAX_CACHE_ENTRIES;
		}
	};
	private static final byte[] NO_SECRET_CACHE_ENTRY = new byte[0];

	@SuppressWarnings("serial")
	private static final Map<String, Status> STATUS_CACHE = new LinkedHashMap<>(MAX_CACHE_ENTRIES + 1, 0.75F, true) {
		// This method is called just after a new entry has been added
		@Override
		public boolean removeEldestEntry(Map.Entry<String, Status> eldest) {
			return size() > MAX_CACHE_ENTRIES;
		}
	};

	/*
	 * OP_TUCK (to copy public key to before signature)
	 * OP_CHECKSIGVERIFY (sig & pubkey must verify or script fails)
	 * OP_HASH160 (convert public key to PKH)
	 * OP_DUP (duplicate PKH)
	 * <push 20 bytes> <refund PKH> OP_EQUAL (does PKH match refund PKH?)
	 * OP_IF
	 * 	OP_DROP (no need for duplicate PKH)
	 * 	<push 4 bytes> <locktime>
	 * 	OP_CHECKLOCKTIMEVERIFY (if this passes, leftover stack is <locktime> so script passes)
	 * OP_ELSE
	 * 	<push 20 bytes> <redeem PKH> OP_EQUALVERIFY (duplicate PKH must match redeem PKH or script fails)
	 * 	OP_HASH160 (hash secret)
	 * 	<push 20 bytes> <hash of secret> OP_EQUAL (do hashes of secrets match? if true, script passes else script fails)
	 * OP_ENDIF
	 */

	private static final byte[] redeemScript1 = HashCode.fromString("7dada97614").asBytes(); // OP_TUCK OP_CHECKSIGVERIFY OP_HASH160 OP_DUP push(0x14 bytes)
	private static final byte[] redeemScript2 = HashCode.fromString("87637504").asBytes(); // OP_EQUAL OP_IF OP_DROP push(0x4 bytes)
	private static final byte[] redeemScript3 = HashCode.fromString("b16714").asBytes(); // OP_CHECKLOCKTIMEVERIFY OP_ELSE push(0x14 bytes)
	private static final byte[] redeemScript4 = HashCode.fromString("88a914").asBytes(); // OP_EQUALVERIFY OP_HASH160 push(0x14 bytes)
	private static final byte[] redeemScript5 = HashCode.fromString("8768").asBytes(); // OP_EQUAL OP_ENDIF

	/**
	 * Returns redeemScript used for cross-chain trading.
	 * <p>
	 * See comments in {@link BitcoinyHTLC} for more details.
	 * 
	 * @param refunderPubKeyHash 20-byte HASH160 of P2SH funder's public key, for refunding purposes
	 * @param lockTime seconds-since-epoch threshold, after which P2SH funder can claim refund
	 * @param redeemerPubKeyHash 20-byte HASH160 of P2SH redeemer's public key
	 * @param hashOfSecret 20-byte HASH160 of secret, used by P2SH redeemer to claim funds
	 */
	public static byte[] buildScript(byte[] refunderPubKeyHash, int lockTime, byte[] redeemerPubKeyHash, byte[] hashOfSecret) {
		return Bytes.concat(redeemScript1, refunderPubKeyHash, redeemScript2, BitTwiddling.toLEByteArray((int) (lockTime & 0xffffffffL)),
				redeemScript3, redeemerPubKeyHash, redeemScript4, hashOfSecret, redeemScript5);
	}

	/**
	 * Builds a custom transaction to spend HTLC P2SH.
	 * 
	 * @param params blockchain network parameters
	 * @param amount output amount, should be total of input amounts, less miner fees
	 * @param spendKey key for signing transaction, and also where funds are 'sent' (output)
	 * @param fundingOutput output from transaction that funded P2SH address
	 * @param redeemScriptBytes the redeemScript itself, in byte[] form
	 * @param lockTime (optional) transaction nLockTime, used in refund scenario
	 * @param scriptSigBuilder function for building scriptSig using transaction input signature
	 * @param outputPublicKeyHash PKH used to create P2PKH output
	 * @return Signed transaction for spending P2SH
	 */
	public static Transaction buildP2shTransaction(NetworkParameters params, Coin amount, ECKey spendKey,
			List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes,
			Long lockTime, Function<byte[], byte[]> scriptSigBuilder, byte[] outputPublicKeyHash) {
		return buildP2shTransaction(params, amount, spendKey, fundingOutputs, redeemScriptBytes, lockTime, scriptSigBuilder,
				outputPublicKeyHash, 2);
	}

	public static Transaction buildP2shTransaction(NetworkParameters params, Coin amount, ECKey spendKey,
			List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes,
			Long lockTime, Function<byte[], byte[]> scriptSigBuilder, byte[] outputPublicKeyHash, int transactionVersion) {
		Transaction transaction = new Transaction(params);
		transaction.setVersion(transactionVersion);

		// Output is back to P2SH funder
		transaction.addOutput(amount, new Script(BitcoinyScript.p2pkhScript(outputPublicKeyHash)));

		for (int inputIndex = 0; inputIndex < fundingOutputs.size(); ++inputIndex) {
			UnspentOutput fundingOutput = fundingOutputs.get(inputIndex);
			TransactionOutPoint fundingOutPoint = new TransactionOutPoint(fundingOutput.index, Sha256Hash.wrap(fundingOutput.hash));

			// Input (without scriptSig prior to signing)
			TransactionInput input = new TransactionInput(null, redeemScriptBytes, fundingOutPoint);
			if (lockTime != null)
				input = input.withSequence(LOCKTIME_NO_RBF_SEQUENCE); // Use max-value - 1, so lockTime can be used but not RBF
			else
				input = input.withSequence(NO_LOCKTIME_NO_RBF_SEQUENCE); // Use max-value, so no lockTime and no RBF
			transaction.addInput(input);
		}

		// Set locktime after inputs added but before input signatures are generated
		if (lockTime != null)
			transaction.setLockTime(lockTime);

		for (int inputIndex = 0; inputIndex < fundingOutputs.size(); ++inputIndex) {
			// Generate transaction signature for input
			final boolean anyoneCanPay = false;
			TransactionSignature txSig = transaction.calculateSignature(inputIndex, spendKey, redeemScriptBytes, SigHash.ALL, anyoneCanPay);

			// Calculate transaction signature
			byte[] txSigBytes = txSig.encodeToBitcoin();

			// Build scriptSig using lambda and tx signature
			byte[] scriptSigBytes = scriptSigBuilder.apply(txSigBytes);

			// Set input scriptSig
			transaction.replaceInput(inputIndex, transaction.getInput(inputIndex).withScriptSig(new Script(scriptSigBytes)));
		}

		return transaction;
	}

	/**
	 * Returns signed transaction claiming refund from HTLC P2SH.
	 * 
	 * @param params blockchain network parameters
	 * @param refundAmount refund amount, should be total of input amounts, less miner fees
	 * @param refundKey key for signing transaction
	 * @param fundingOutputs outputs from transaction that funded P2SH address
	 * @param redeemScriptBytes the redeemScript itself, in byte[] form
	 * @param lockTime transaction nLockTime - must be at least locktime used in redeemScript
	 * @param receivingAccountInfo public-key-hash used for P2PKH output
	 * @return Signed transaction for refunding P2SH
	 */
	public static Transaction buildRefundTransaction(NetworkParameters params, Coin refundAmount, ECKey refundKey,
			List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, long lockTime, byte[] receivingAccountInfo) {
		return buildRefundTransaction(params, refundAmount, refundKey, fundingOutputs, redeemScriptBytes, lockTime, receivingAccountInfo, 2);
	}

	public static Transaction buildRefundTransaction(NetworkParameters params, Coin refundAmount, ECKey refundKey,
			List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, long lockTime, byte[] receivingAccountInfo, int transactionVersion) {
		byte[] refundPubKey = refundKey.getPubKey();
		Function<byte[], byte[]> refundScriptSigBuilder = (txSigBytes) -> Bytes.concat(
				BitcoinyScript.pushData(txSigBytes),
				BitcoinyScript.pushData(refundPubKey),
				BitcoinyScript.pushData(redeemScriptBytes));

		// Send funds back to funding address
		return buildP2shTransaction(params, refundAmount, refundKey, fundingOutputs, redeemScriptBytes, lockTime, refundScriptSigBuilder,
				receivingAccountInfo, transactionVersion);
	}

	/**
	 * Returns signed transaction redeeming funds from P2SH address.
	 * 
	 * @param params blockchain network parameters
	 * @param redeemAmount redeem amount, should be total of input amounts, less miner fees
	 * @param redeemKey key for signing transaction
	 * @param fundingOutputs outputs from transaction that funded P2SH address
	 * @param redeemScriptBytes the redeemScript itself, in byte[] form
	 * @param secret actual 32-byte secret used when building redeemScript
	 * @param receivingAccountInfo Bitcoin PKH used for output
	 * @return Signed transaction for redeeming P2SH
	 */
	public static Transaction buildRedeemTransaction(NetworkParameters params, Coin redeemAmount, ECKey redeemKey,
			List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, byte[] secret, byte[] receivingAccountInfo) {
		return buildRedeemTransaction(params, redeemAmount, redeemKey, fundingOutputs, redeemScriptBytes, secret, receivingAccountInfo, 2);
	}

	public static Transaction buildRedeemTransaction(NetworkParameters params, Coin redeemAmount, ECKey redeemKey,
			List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, byte[] secret, byte[] receivingAccountInfo, int transactionVersion) {
		byte[] redeemPubKey = redeemKey.getPubKey();
		Function<byte[], byte[]> redeemScriptSigBuilder = (txSigBytes) -> Bytes.concat(
				BitcoinyScript.pushData(secret),
				BitcoinyScript.pushData(txSigBytes),
				BitcoinyScript.pushData(redeemPubKey),
				BitcoinyScript.pushData(redeemScriptBytes));

		return buildP2shTransaction(params, redeemAmount, redeemKey, fundingOutputs, redeemScriptBytes, null, redeemScriptSigBuilder,
				receivingAccountInfo, transactionVersion);
	}

	/**
	 * Returns 'secret', if any, given HTLC's P2SH address.
	 * <p>
	 * @throws ForeignBlockchainException
	 */
	public static byte[] findHtlcSecret(Bitcoiny bitcoiny, String p2shAddress) throws ForeignBlockchainException {
		NetworkParameters params = bitcoiny.getNetworkParameters();
		BitcoinyAddress htlcAddress = BitcoinyAddress.fromString(params, p2shAddress);
		if (htlcAddress.getType() != BitcoinyAddress.Type.P2SH)
			throw new IllegalArgumentException("HTLC address must be P2SH");

		return findHtlcSecret(bitcoiny.getBlockchainProvider(), p2shAddress, BitcoinyScript.scriptPubKey(htlcAddress),
				htlcAddress.getPayload());
	}

	private static byte[] findHtlcSecret(BitcoinyBlockchainProvider blockchain, String p2shAddress, byte[] ourScriptPubKey,
			byte[] ourRedeemScriptHash) throws ForeignBlockchainException {
		String compoundKey = String.format("%s-%s-%d", blockchain.getNetId(), p2shAddress, System.currentTimeMillis() / CACHE_TIMEOUT);

		byte[] secret = SECRET_CACHE.getOrDefault(compoundKey, NO_SECRET_CACHE_ENTRY);
		if (secret != NO_SECRET_CACHE_ENTRY)
			return secret;

		List<TransactionHash> transactionHashes = blockchain.getAddressTransactions(ourScriptPubKey, BitcoinyBlockchainProvider.EXCLUDE_UNCONFIRMED);
		transactionHashes.sort(TransactionHash.CONFIRMED_FIRST.thenComparing(TransactionHash::getHeight));

		for (TransactionHash transactionInfo : transactionHashes) {
			BitcoinyTransaction transaction = blockchain.getTransaction(transactionInfo.txHash);
			// Cycle through inputs, looking for one that spends our HTLC
			for (BitcoinyTransaction.Input input : transaction.inputs) {
				if (input.scriptSig == null)
					continue;

				List<byte[]> scriptChunks;
				try {
					scriptChunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(input.scriptSig).asBytes());
				} catch (IllegalArgumentException e) {
					continue;
				}

				// Expected number of script chunks for redeem. Refund might not have the same number.
				int expectedChunkCount = 1 /*secret*/ + 1 /*sig*/ + 1 /*pubkey*/ + 1 /*redeemScript*/;
				if (scriptChunks.size() != expectedChunkCount)
					continue;

				// We're expecting last chunk to contain the actual redeemScript
				byte[] redeemScriptBytes = scriptChunks.get(scriptChunks.size() - 1);

				byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);

				if (!Arrays.equals(redeemScriptHash, ourRedeemScriptHash))
					// Input isn't spending our HTLC
					continue;

				secret = scriptChunks.get(0);
				if (secret.length != BitcoinyHTLC.SECRET_LENGTH)
					continue;

				// Cache secret for a while
				SECRET_CACHE.put(compoundKey, secret);

				return secret;
			}
		}

		// Cache negative result
		SECRET_CACHE.put(compoundKey, null);

		return null;
	}

	/**
	 * Returns HTLC status, given P2SH address and expected redeem/refund amount
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public static Status determineHtlcStatus(Bitcoiny bitcoiny, String p2shAddress, long minimumAmount) throws ForeignBlockchainException {
		NetworkParameters params = bitcoiny.getNetworkParameters();
		BitcoinyAddress htlcAddress = BitcoinyAddress.fromString(params, p2shAddress);
		if (htlcAddress.getType() != BitcoinyAddress.Type.P2SH)
			throw new IllegalArgumentException("HTLC address must be P2SH");

		return determineHtlcStatus(bitcoiny.getBlockchainProvider(), p2shAddress, BitcoinyScript.scriptPubKey(htlcAddress),
				htlcAddress.getPayload(), minimumAmount);
	}

	public static Status determineHtlcStatus(BitcoinyBlockchainProvider blockchain, String p2shAddress, long minimumAmount) throws ForeignBlockchainException {
		return determineHtlcStatus(blockchain, p2shAddress, addressToScriptPubKey(p2shAddress), addressToRedeemScriptHash(p2shAddress), minimumAmount);
	}

	private static Status determineHtlcStatus(BitcoinyBlockchainProvider blockchain, String p2shAddress, byte[] ourScriptPubKey,
			byte[] ourRedeemScriptHash, long minimumAmount) throws ForeignBlockchainException {
		String compoundKey = String.format("%s-%s-%d", blockchain.getNetId(), p2shAddress, System.currentTimeMillis() / CACHE_TIMEOUT);

		Status cachedStatus = STATUS_CACHE.getOrDefault(compoundKey, null);
		if (cachedStatus != null)
			return cachedStatus;

		List<TransactionHash> transactionHashes = blockchain.getAddressTransactions(ourScriptPubKey, BitcoinyBlockchainProvider.INCLUDE_UNCONFIRMED);

		// Sort by confirmed first, followed by ascending height
		transactionHashes.sort(TransactionHash.CONFIRMED_FIRST.thenComparing(TransactionHash::getHeight));

		// Transaction cache
		Map<String, BitcoinyTransaction> transactionsByHash = new HashMap<>();

		// Check for spends first, caching full transaction info as we progress just in case we don't return in this loop
		for (TransactionHash transactionInfo : transactionHashes) {
			BitcoinyTransaction bitcoinyTransaction = blockchain.getTransaction(transactionInfo.txHash);

			// Cache for possible later reuse
			transactionsByHash.put(transactionInfo.txHash, bitcoinyTransaction);

			// Acceptable funding is one transaction output, so we're expecting only one input
			if (bitcoinyTransaction.inputs.size() != 1)
				// Wrong number of inputs
				continue;

			String scriptSig = bitcoinyTransaction.inputs.get(0).scriptSig;

			List<byte[]> scriptSigChunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(scriptSig).asBytes());
			if (scriptSigChunks.size() < 3 || scriptSigChunks.size() > 4)
				// Not valid chunks for our form of HTLC
				continue;

			// Last chunk is redeem script
			byte[] redeemScriptBytes = scriptSigChunks.get(scriptSigChunks.size() - 1);
			byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
			if (!Arrays.equals(redeemScriptHash, ourRedeemScriptHash))
				// Not spending our specific HTLC redeem script
				continue;

			if (scriptSigChunks.size() == 4)
				// If we have 4 chunks, then secret is present, hence redeem
				cachedStatus = transactionInfo.height == 0 ? Status.REDEEM_IN_PROGRESS : Status.REDEEMED;
			else
				cachedStatus = transactionInfo.height == 0 ? Status.REFUND_IN_PROGRESS : Status.REFUNDED;

			STATUS_CACHE.put(compoundKey, cachedStatus);
			return cachedStatus;
		}

		String ourScriptPubKeyHex = HashCode.fromBytes(ourScriptPubKey).toString();

		// Check for funding
		for (TransactionHash transactionInfo : transactionHashes) {
			BitcoinyTransaction bitcoinyTransaction = transactionsByHash.get(transactionInfo.txHash);
			if (bitcoinyTransaction == null)
				// Should be present in map!
				throw new ForeignBlockchainException("Cached Bitcoin transaction now missing?");

			// Check outputs for our specific P2SH
			for (BitcoinyTransaction.Output output : bitcoinyTransaction.outputs) {
				// Check amount
				if (output.value < minimumAmount)
					// Output amount too small (not taking fees into account)
					continue;

				String scriptPubKeyHex = output.scriptPubKey;
				if (!scriptPubKeyHex.equals(ourScriptPubKeyHex))
					// Not funding our specific P2SH
					continue;

				cachedStatus = transactionInfo.height == 0 ? Status.FUNDING_IN_PROGRESS : Status.FUNDED;
				STATUS_CACHE.put(compoundKey, cachedStatus);
				return cachedStatus;
			}
		}

		cachedStatus = Status.UNFUNDED;
		STATUS_CACHE.put(compoundKey, cachedStatus);
		return cachedStatus;
	}

	private static byte[] addressToScriptPubKey(String p2shAddress) {
		// We want the HASH160 part of the P2SH address
		byte[] p2shAddressBytes = Base58.decode(p2shAddress);

		byte[] scriptPubKey = new byte[1 + 1 + 20 + 1];
		scriptPubKey[0x00] = (byte) 0xa9; /* OP_HASH160 */
		scriptPubKey[0x01] = (byte) 0x14; /* PUSH 0x14 bytes */
		System.arraycopy(p2shAddressBytes, 1, scriptPubKey, 2, 0x14);
		scriptPubKey[0x16] = (byte) 0x87; /* OP_EQUAL */

		return scriptPubKey;
	}

	private static byte[] addressToRedeemScriptHash(String p2shAddress) {
		// We want the HASH160 part of the P2SH address
		byte[] p2shAddressBytes = Base58.decode(p2shAddress);

		return Arrays.copyOfRange(p2shAddressBytes, 1, 1 + 20);
	}

}
