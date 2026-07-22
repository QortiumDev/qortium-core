package org.qortium.network.message;

import org.junit.Test;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;
import org.qortium.transform.transaction.ChatTransactionTransformer;
import org.qortium.transform.transaction.MessageTransactionTransformer;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for a remotely-triggerable denial of service: a peer declares a negative
 * length in a message it sends, and the decoder allocates an array of that size.
 * <p>
 * The guards these replace all had the same shape - {@code if (remaining() < declaredLength)} or
 * {@code if (declaredLength > MAX)}. Neither rejects a negative value: {@code remaining()} is never
 * negative, so the first comparison is always false, and a negative length is never greater than a
 * maximum. The declared length then reached {@code new byte[length]} and threw
 * {@link NegativeArraySizeException}.
 * <p>
 * That exception is what made this critical rather than merely wrong. It is not
 * {@code MessageException}, {@code TransformationException}, {@code BufferUnderflowException} or
 * {@code IOException} - the only types the P2P read path catches - so it escaped
 * {@code Peer.readChannel()} and {@code Network.runIOLoop()} and killed the single shared
 * Network-IO thread. The process stayed alive with all P2P traffic silently stopped.
 * <p>
 * Every case below asserts the decoder rejects the input through its normal error contract, so the
 * offending peer is dropped and the node keeps running. Asserting "does not throw
 * NegativeArraySizeException" alone would be too weak: it would also pass if a decoder returned a
 * half-built object.
 */
public class NegativeLengthDecodeTests {

	private static final int NEGATIVE_LENGTH = -1;
	/** Negative and a multiple of 4 / of the AT entry length, to slip past alignment checks too. */
	private static final int NEGATIVE_ALIGNED_LENGTH = -24;

	@Test
	public void testArbitraryDataMessageRejectsNegativeDataLength() {
		assertRejectedAsMessageException(arbitraryPayload(NEGATIVE_LENGTH), MessageType.ARBITRARY_DATA);
	}

	@Test
	public void testArbitraryDataFileMessageRejectsNegativeDataLength() {
		assertRejectedAsMessageException(arbitraryPayload(NEGATIVE_LENGTH), MessageType.ARBITRARY_DATA_FILE);
	}

	@Test
	public void testArbitraryMetadataMessageRejectsNegativeDataLength() {
		assertRejectedAsMessageException(arbitraryPayload(NEGATIVE_LENGTH), MessageType.ARBITRARY_METADATA);
	}

	@Test
	public void testArbitraryDataMessageRejectsNegativeAlignedDataLength() {
		assertRejectedAsMessageException(arbitraryPayload(NEGATIVE_ALIGNED_LENGTH), MessageType.ARBITRARY_DATA);
	}

	/**
	 * The reported attack in full: a single unauthenticated ARBITRARY_DATA message whose inner
	 * length field is 0xFFFFFFFF, decoded through the same entry point the network read loop uses.
	 */
	@Test
	public void testFullMessageDecodeRejectsNegativeInnerLength() {
		byte[] payload = arbitraryPayload(NEGATIVE_LENGTH);

		try {
			MessageType.ARBITRARY_DATA.fromByteBuffer(1, ByteBuffer.wrap(payload));
			fail("A negative inner data length must be rejected");
		} catch (NegativeArraySizeException e) {
			fail("Negative length reached an allocation and would kill the Network-IO thread: " + e);
		} catch (MessageException e) {
			assertTrue("Rejection should name the offending length, got: " + e.getMessage(),
					e.getMessage() != null && e.getMessage().contains("-1"));
		}
	}

	/**
	 * Built to reach the length field for real - a buffer that fails earlier for some other reason
	 * would pass this test without ever exercising the guard.
	 */
	@Test
	public void testChatTransactionTransformerRejectsNegativeDataSize() {
		ByteBuffer buffer = ByteBuffer.allocate(128);
		buffer.putLong(0L);                                  // timestamp
		buffer.putInt(0);                                    // txGroupId
		buffer.put(new byte[Transformer.PUBLIC_KEY_LENGTH]); // sender public key
		buffer.putInt(0);                                    // nonce
		buffer.put((byte) 0);                                // has recipient? no
		buffer.putInt(NEGATIVE_LENGTH);                      // dataSize
		buffer.flip();

		assertRejectedAsTransformationException(() -> ChatTransactionTransformer.fromByteBuffer(buffer));
	}

	@Test
	public void testMessageTransactionTransformerRejectsNegativeDataSize() {
		ByteBuffer buffer = ByteBuffer.allocate(128);
		buffer.putLong(0L);                                  // timestamp
		buffer.putInt(0);                                    // txGroupId
		buffer.put(new byte[Transformer.PUBLIC_KEY_LENGTH]); // sender public key
		buffer.putInt(0);                                    // nonce
		buffer.put((byte) 0);                                // has recipient? no
		buffer.putLong(0L);                                  // amount 0, so no assetId follows
		buffer.putInt(NEGATIVE_LENGTH);                      // dataSize
		buffer.flip();

		assertRejectedAsTransformationException(() -> MessageTransactionTransformer.fromByteBuffer(buffer));
	}

	/** signature + a declared data length, which is the shape all three arbitrary messages share. */
	private static byte[] arbitraryPayload(int declaredLength) {
		ByteBuffer buffer = ByteBuffer.allocate(Transformer.SIGNATURE_LENGTH + Integer.BYTES);
		buffer.put(new byte[Transformer.SIGNATURE_LENGTH]);
		buffer.putInt(declaredLength);
		return buffer.array();
	}

	private static void assertRejectedAsMessageException(byte[] payload, MessageType messageType) {
		try {
			messageType.fromByteBuffer(1, ByteBuffer.wrap(payload));
			fail(messageType + " must reject a negative declared length");
		} catch (NegativeArraySizeException e) {
			fail(messageType + " allocated on a negative length, which kills the Network-IO thread: " + e);
		} catch (MessageException e) {
			// expected: malformed input, peer gets dropped, node survives
		}
	}

	private interface DecodeCall {
		Object decode() throws TransformationException;
	}

	/**
	 * The payload reaches the length field, so the decoder must reject it there. Accepting any
	 * checked/unchecked rejection except the allocation crash keeps this robust to a decoder
	 * choosing a different (but still safe) error for malformed input.
	 */
	private static void assertRejectedAsTransformationException(DecodeCall call) {
		try {
			call.decode();
		} catch (NegativeArraySizeException e) {
			fail("Negative length reached an allocation: " + e);
		} catch (TransformationException | RuntimeException e) {
			// expected: rejected through the normal error contract
		}
	}
}
