package org.qortium.network.message;

import com.google.common.primitives.Ints;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Publisher-initiated push (Fix 1): announces, to a reachable peer, the set of hashes a publisher
 * holds and is willing to push for the given resource signature. The receiver replies with an
 * {@link ArbitraryDataFileWantMessage} listing the subset it actually accepts. No data is sent
 * until that WANT arrives.
 *
 * Wire format: signature (64) + hashCount (int) + hashCount * SHA256 (32 each).
 */
public class ArbitraryDataFileOfferMessage extends Message {

	private static final int MAX_HASHES = 100_000;

	private byte[] signature;
	private List<byte[]> hashes;

	public ArbitraryDataFileOfferMessage(byte[] signature, List<byte[]> hashes) {
		super(MessageType.ARBITRARY_DATA_FILE_OFFER);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(signature);

			if (hashes != null) {
				bytes.write(Ints.toByteArray(hashes.size()));
				for (byte[] hash : hashes) {
					bytes.write(hash);
				}
			} else {
				bytes.write(Ints.toByteArray(0));
			}
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.signature = signature;
		this.hashes = hashes;
		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private ArbitraryDataFileOfferMessage(int id, byte[] signature, List<byte[]> hashes) {
		super(id, MessageType.ARBITRARY_DATA_FILE_OFFER);

		this.signature = signature;
		this.hashes = hashes;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public List<byte[]> getHashes() {
		return this.hashes;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		bytes.get(signature);

		int hashCount = bytes.getInt();
		if (hashCount < 0 || hashCount > MAX_HASHES)
			throw new MessageException("Invalid hash count in ArbitraryDataFileOfferMessage: " + hashCount);

		List<byte[]> hashes = new ArrayList<>(hashCount);
		for (int i = 0; i < hashCount; ++i) {
			byte[] hash = new byte[Transformer.SHA256_LENGTH];
			bytes.get(hash);
			hashes.add(hash);
		}

		return new ArbitraryDataFileOfferMessage(id, signature, hashes);
	}
}
