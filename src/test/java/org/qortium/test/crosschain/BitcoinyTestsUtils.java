package org.qortium.test.crosschain;

import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDPath;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.qortium.crosschain.BitcoinyChainSpecs;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crypto.Crypto;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;
import org.qortium.utils.Base58;

import java.nio.ByteBuffer;
import java.security.SecureRandom;

public class BitcoinyTestsUtils {

	public static void main(String[] args) throws DataException, ForeignBlockchainException {

		Common.useDefaultSettings();

		final String rootKey = generateBip32RootKey(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.TEST4).getParams());
		String address = ForeignBlockchainRegistry.fromStringRequired("LITECOIN").getBitcoinyInstance().getUnusedReceiveAddress(rootKey);

		System.out.println("rootKey = " + rootKey);
		System.out.println("address = " + address);

		System.exit(0);
	}

	public static String generateBip32RootKey(NetworkParameters networkParameters) {

		final DeterministicSeed seed = DeterministicSeed.ofRandom(new SecureRandom(),
				DeterministicSeed.DEFAULT_SEED_ENTROPY_BITS, DeterministicKeyChain.DEFAULT_PASSPHRASE_FOR_MNEMONIC);
		final DeterministicKeyChain keyChain = DeterministicKeyChain.builder()
				.seed(seed)
				.outputScriptType(ScriptType.P2PKH)
				.build();
		final HDPath path = keyChain.getAccountPath();
		final DeterministicKey parent = keyChain.getKeyByPath(path, true);
		final String rootKey = serializePrivB58(parent, networkParameters);

		return rootKey;
	}

	private static String serializePrivB58(DeterministicKey key, NetworkParameters networkParameters) {
		ByteBuffer payload = ByteBuffer.allocate(78);
		payload.putInt(networkParameters.getBip32HeaderP2PKHpriv());
		payload.put((byte) key.getDepth());
		payload.putInt(key.getParentFingerprint());
		payload.putInt(key.getChildNumber().i());
		payload.put(key.getChainCode());
		payload.put(key.getPrivKeyBytes33());

		byte[] payloadBytes = payload.array();
		byte[] extendedKey = new byte[payloadBytes.length + 4];
		System.arraycopy(payloadBytes, 0, extendedKey, 0, payloadBytes.length);
		System.arraycopy(Crypto.doubleDigest(payloadBytes), 0, extendedKey, payloadBytes.length, 4);
		return Base58.encode(extendedKey);
	}
}
