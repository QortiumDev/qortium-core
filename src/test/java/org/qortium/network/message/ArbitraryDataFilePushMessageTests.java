package org.qortium.network.message;

import org.junit.Test;
import org.qortium.transform.Transformer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Wire round-trip tests for the publisher-initiated push messages (Fix 1):
 * {@link ArbitraryDataFileOfferMessage} and {@link ArbitraryDataFileWantMessage}.
 */
public class ArbitraryDataFilePushMessageTests {

	private static byte[] filledArray(int length, int seed) {
		byte[] array = new byte[length];
		for (int i = 0; i < length; i++)
			array[i] = (byte) (i + seed);
		return array;
	}

	private static List<byte[]> sampleHashes(int count) {
		List<byte[]> hashes = new ArrayList<>();
		for (int i = 0; i < count; i++)
			hashes.add(filledArray(Transformer.SHA256_LENGTH, i + 1));
		return hashes;
	}

	private static void assertHashesEqual(List<byte[]> expected, List<byte[]> actual) {
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++)
			assertArrayEquals(expected.get(i), actual.get(i));
	}

	@Test
	public void testOfferRoundTrip() throws MessageException {
		byte[] signature = filledArray(Transformer.SIGNATURE_LENGTH, 7);
		List<byte[]> hashes = sampleHashes(3);

		ArbitraryDataFileOfferMessage message = new ArbitraryDataFileOfferMessage(signature, hashes);
		ArbitraryDataFileOfferMessage decoded = (ArbitraryDataFileOfferMessage)
				ArbitraryDataFileOfferMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertArrayEquals(signature, decoded.getSignature());
		assertHashesEqual(hashes, decoded.getHashes());
	}

	@Test
	public void testWantRoundTrip() throws MessageException {
		byte[] signature = filledArray(Transformer.SIGNATURE_LENGTH, 11);
		List<byte[]> hashes = sampleHashes(5);

		ArbitraryDataFileWantMessage message = new ArbitraryDataFileWantMessage(signature, hashes);
		ArbitraryDataFileWantMessage decoded = (ArbitraryDataFileWantMessage)
				ArbitraryDataFileWantMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertArrayEquals(signature, decoded.getSignature());
		assertHashesEqual(hashes, decoded.getHashes());
	}

	@Test
	public void testOfferEmptyHashesRoundTrip() throws MessageException {
		byte[] signature = filledArray(Transformer.SIGNATURE_LENGTH, 1);

		ArbitraryDataFileOfferMessage message = new ArbitraryDataFileOfferMessage(signature, new ArrayList<>());
		ArbitraryDataFileOfferMessage decoded = (ArbitraryDataFileOfferMessage)
				ArbitraryDataFileOfferMessage.fromByteBuffer(1, ByteBuffer.wrap(message.dataBytes));

		assertArrayEquals(signature, decoded.getSignature());
		assertEquals(0, decoded.getHashes().size());
	}

	@Test
	public void testOfferNullHashesRoundTrip() throws MessageException {
		byte[] signature = filledArray(Transformer.SIGNATURE_LENGTH, 1);

		ArbitraryDataFileOfferMessage message = new ArbitraryDataFileOfferMessage(signature, null);
		ArbitraryDataFileOfferMessage decoded = (ArbitraryDataFileOfferMessage)
				ArbitraryDataFileOfferMessage.fromByteBuffer(1, ByteBuffer.wrap(message.dataBytes));

		assertArrayEquals(signature, decoded.getSignature());
		assertEquals(0, decoded.getHashes().size());
	}
}
