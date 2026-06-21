package org.qortium.network.message;

import com.google.common.primitives.Ints;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Publisher-initiated push (Fix 1): a receiver's storage-policy-filtered acceptance of an
 * {@link ArbitraryDataFileOfferMessage}. Lists the subset of offered hashes the receiver wants and
 * has pre-registered as expected; the publisher then sends exactly these as standard
 * ARBITRARY_DATA_FILE chunks.
 *
 * Wire format: signature (64) + hashCount (int) + hashCount * SHA256 (32 each).
 */
public class ArbitraryDataFileWantMessage extends Message {

	private static final int MAX_HASHES = 100_000;

	private byte[] signature;
	private List<byte[]> hashes;

	public ArbitraryDataFileWantMessage(byte[] signature, List<byte[]> hashes) {
		super(MessageType.ARBITRARY_DATA_FILE_WANT);

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

	private ArbitraryDataFileWantMessage(int id, byte[] signature, List<byte[]> hashes) {
		super(id, MessageType.ARBITRARY_DATA_FILE_WANT);

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
			throw new MessageException("Invalid hash count in ArbitraryDataFileWantMessage: " + hashCount);

		List<byte[]> hashes = new ArrayList<>(hashCount);
		for (int i = 0; i < hashCount; ++i) {
			byte[] hash = new byte[Transformer.SHA256_LENGTH];
			bytes.get(hash);
			hashes.add(hash);
		}

		return new ArbitraryDataFileWantMessage(id, signature, hashes);
	}
}
