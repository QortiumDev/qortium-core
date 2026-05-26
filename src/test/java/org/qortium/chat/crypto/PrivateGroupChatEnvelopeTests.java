package org.qortium.chat.crypto;

import org.junit.Test;
import org.qortium.transform.TransformationException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrivateGroupChatEnvelopeTests {

	@Test
	public void testMessageRoundTrip() throws TransformationException {
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 1);
		byte[] keyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 2);
		byte[] nonce = bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 3);
		byte[] ciphertext = bytes(48, 4);

		PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.message(7, epochId, keyId, nonce, ciphertext);
		PrivateGroupChatEnvelope parsedEnvelope = PrivateGroupChatEnvelope.fromBytes(envelope.toBytes());

		assertEquals(PrivateGroupChatEnvelope.Type.MESSAGE, parsedEnvelope.getType());
		assertEquals(7, parsedEnvelope.getGroupId());
		assertArrayEquals(epochId, parsedEnvelope.getEpochId());
		assertArrayEquals(keyId, parsedEnvelope.getKeyId());
		assertArrayEquals(nonce, parsedEnvelope.getNonce());
		assertArrayEquals(ciphertext, parsedEnvelope.getCiphertext());
		assertArrayEquals(envelope.toBytes(), parsedEnvelope.toBytes());
	}

	@Test
	public void testKeyAnnouncementRoundTripPreservesWrappers() throws TransformationException {
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 10);
		byte[] keyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 11);
		byte[] creatorPublicKey = bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 12);
		byte[] signature = bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 13);

		PrivateGroupChatEnvelope.KeyWrapper firstWrapper = new PrivateGroupChatEnvelope.KeyWrapper(
				bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 14), bytes(40, 15));
		PrivateGroupChatEnvelope.KeyWrapper secondWrapper = new PrivateGroupChatEnvelope.KeyWrapper(
				bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 16), bytes(44, 17));

		PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.keyAnnouncement(8, epochId, keyId,
				creatorPublicKey, Arrays.asList(firstWrapper, secondWrapper), signature);
		PrivateGroupChatEnvelope parsedEnvelope = PrivateGroupChatEnvelope.fromBytes(envelope.toBytes());

		assertEquals(PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT, parsedEnvelope.getType());
		assertEquals(8, parsedEnvelope.getGroupId());
		assertArrayEquals(epochId, parsedEnvelope.getEpochId());
		assertArrayEquals(keyId, parsedEnvelope.getKeyId());
		assertArrayEquals(creatorPublicKey, parsedEnvelope.getCreatorPublicKey());
		assertArrayEquals(signature, parsedEnvelope.getSignature());

		List<PrivateGroupChatEnvelope.KeyWrapper> parsedWrappers = parsedEnvelope.getKeyWrappers();
		assertEquals(2, parsedWrappers.size());
		assertArrayEquals(firstWrapper.getRecipientPublicKey(), parsedWrappers.get(0).getRecipientPublicKey());
		assertArrayEquals(firstWrapper.getWrappedKey(), parsedWrappers.get(0).getWrappedKey());
		assertArrayEquals(secondWrapper.getRecipientPublicKey(), parsedWrappers.get(1).getRecipientPublicKey());
		assertArrayEquals(secondWrapper.getWrappedKey(), parsedWrappers.get(1).getWrappedKey());
		assertArrayEquals(envelope.toBytes(), parsedEnvelope.toBytes());
	}

	@Test
	public void testKeyRequestWithKeyIdRoundTrip() throws TransformationException {
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 20);
		byte[] requesterPublicKey = bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 21);
		byte[] keyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 22);
		byte[] signature = bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 23);

		PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.keyRequest(9, epochId, requesterPublicKey,
				keyId, signature);
		PrivateGroupChatEnvelope parsedEnvelope = PrivateGroupChatEnvelope.fromBytes(envelope.toBytes());

		assertEquals(PrivateGroupChatEnvelope.Type.KEY_REQUEST, parsedEnvelope.getType());
		assertEquals(9, parsedEnvelope.getGroupId());
		assertArrayEquals(epochId, parsedEnvelope.getEpochId());
		assertArrayEquals(requesterPublicKey, parsedEnvelope.getRequesterPublicKey());
		assertTrue(parsedEnvelope.hasRequestedKeyId());
		assertArrayEquals(keyId, parsedEnvelope.getKeyId());
		assertArrayEquals(signature, parsedEnvelope.getSignature());
		assertArrayEquals(envelope.toBytes(), parsedEnvelope.toBytes());
	}

	@Test
	public void testKeyRequestWithoutKeyIdRoundTrip() throws TransformationException {
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 30);
		byte[] requesterPublicKey = bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 31);
		byte[] signature = bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 32);

		PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.keyRequest(10, epochId, requesterPublicKey,
				null, signature);
		PrivateGroupChatEnvelope parsedEnvelope = PrivateGroupChatEnvelope.fromBytes(envelope.toBytes());

		assertEquals(PrivateGroupChatEnvelope.Type.KEY_REQUEST, parsedEnvelope.getType());
		assertEquals(10, parsedEnvelope.getGroupId());
		assertArrayEquals(epochId, parsedEnvelope.getEpochId());
		assertArrayEquals(requesterPublicKey, parsedEnvelope.getRequesterPublicKey());
		assertFalse(parsedEnvelope.hasRequestedKeyId());
		assertNull(parsedEnvelope.getKeyId());
		assertArrayEquals(signature, parsedEnvelope.getSignature());
		assertArrayEquals(envelope.toBytes(), parsedEnvelope.toBytes());
	}

	@Test
	public void testRotationRequestRoundTrip() throws TransformationException {
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 40);
		byte[] requesterPublicKey = bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 41);
		byte[] signature = bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 42);

		PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.rotationRequest(11, epochId,
				requesterPublicKey, signature);
		PrivateGroupChatEnvelope parsedEnvelope = PrivateGroupChatEnvelope.fromBytes(envelope.toBytes());

		assertEquals(PrivateGroupChatEnvelope.Type.ROTATION_REQUEST, parsedEnvelope.getType());
		assertEquals(11, parsedEnvelope.getGroupId());
		assertArrayEquals(epochId, parsedEnvelope.getEpochId());
		assertArrayEquals(requesterPublicKey, parsedEnvelope.getRequesterPublicKey());
		assertArrayEquals(signature, parsedEnvelope.getSignature());
		assertArrayEquals(envelope.toBytes(), parsedEnvelope.toBytes());
	}

	@Test
	public void testParserRejectsInvalidHeaderAndLengthData() {
		byte[] validMessage = messageBytes();

		byte[] invalidMagic = validMessage.clone();
		invalidMagic[0] ^= 1;
		assertParseFails(invalidMagic);

		byte[] invalidVersion = validMessage.clone();
		invalidVersion[4] = (byte) (PrivateGroupChatEnvelope.VERSION + 1);
		assertParseFails(invalidVersion);

		byte[] unknownType = validMessage.clone();
		unknownType[5] = 99;
		assertParseFails(unknownType);

		assertParseFails(Arrays.copyOf(validMessage, validMessage.length - 1));

		byte[] trailingData = Arrays.copyOf(validMessage, validMessage.length + 1);
		assertParseFails(trailingData);

		byte[] negativeCiphertextLength = validMessage.clone();
		writeInt(negativeCiphertextLength, messageCiphertextLengthOffset(), -1);
		assertParseFails(negativeCiphertextLength);

		byte[] oversizedCiphertextLength = validMessage.clone();
		writeInt(oversizedCiphertextLength, messageCiphertextLengthOffset(), 4001);
		assertParseFails(oversizedCiphertextLength);
	}

	@Test
	public void testParserRejectsInvalidControlEnvelopeData() {
		byte[] validAnnouncement = keyAnnouncementBytes();
		byte[] emptyAnnouncement = validAnnouncement.clone();
		writeInt(emptyAnnouncement, keyAnnouncementWrapperCountOffset(), 0);
		assertParseFails(emptyAnnouncement);

		byte[] invalidOptionalMarker = keyRequestWithoutKeyIdBytes();
		invalidOptionalMarker[keyRequestOptionalMarkerOffset()] = 2;
		assertParseFails(invalidOptionalMarker);

		assertThrows(IllegalArgumentException.class, () -> PrivateGroupChatEnvelope.keyAnnouncement(1,
				bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 60), bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 61),
				bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 62), Collections.emptyList(),
				bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 63)));
	}

	@Test
	public void testDefensiveCopies() throws TransformationException {
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 70);
		byte[] keyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 71);
		byte[] nonce = bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 72);
		byte[] ciphertext = bytes(16, 73);

		PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.message(12, epochId, keyId, nonce, ciphertext);
		epochId[0] ^= 1;
		keyId[0] ^= 1;
		nonce[0] ^= 1;
		ciphertext[0] ^= 1;

		PrivateGroupChatEnvelope parsedEnvelope = PrivateGroupChatEnvelope.fromBytes(envelope.toBytes());
		byte[] parsedEpochId = parsedEnvelope.getEpochId();
		parsedEpochId[0] ^= 1;
		assertArrayEquals(envelope.getEpochId(), parsedEnvelope.getEpochId());

		byte[] parsedKeyId = parsedEnvelope.getKeyId();
		parsedKeyId[0] ^= 1;
		assertArrayEquals(envelope.getKeyId(), parsedEnvelope.getKeyId());

		byte[] parsedNonce = parsedEnvelope.getNonce();
		parsedNonce[0] ^= 1;
		assertArrayEquals(envelope.getNonce(), parsedEnvelope.getNonce());

		byte[] parsedCiphertext = parsedEnvelope.getCiphertext();
		parsedCiphertext[0] ^= 1;
		assertArrayEquals(envelope.getCiphertext(), parsedEnvelope.getCiphertext());

		PrivateGroupChatEnvelope.KeyWrapper wrapper = new PrivateGroupChatEnvelope.KeyWrapper(
				bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 74), bytes(16, 75));
		PrivateGroupChatEnvelope announcement = PrivateGroupChatEnvelope.keyAnnouncement(13,
				bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 76), bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 77),
				bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 78), Collections.singletonList(wrapper),
				bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 79));
		byte[] recipientPublicKey = announcement.getKeyWrappers().get(0).getRecipientPublicKey();
		recipientPublicKey[0] ^= 1;
		assertArrayEquals(wrapper.getRecipientPublicKey(), announcement.getKeyWrappers().get(0).getRecipientPublicKey());
	}

	private static void assertParseFails(byte[] bytes) {
		assertThrows(TransformationException.class, () -> PrivateGroupChatEnvelope.fromBytes(bytes));
	}

	private static byte[] messageBytes() {
		return PrivateGroupChatEnvelope.message(1, bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 1),
				bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 2), bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 3),
				bytes(12, 4)).toBytes();
	}

	private static byte[] keyAnnouncementBytes() {
		return PrivateGroupChatEnvelope.keyAnnouncement(1, bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 10),
				bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 11), bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 12),
				Collections.singletonList(new PrivateGroupChatEnvelope.KeyWrapper(
						bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 13), bytes(16, 14))),
				bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 15)).toBytes();
	}

	private static byte[] keyRequestWithoutKeyIdBytes() {
		return PrivateGroupChatEnvelope.keyRequest(1, bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 20),
				bytes(PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, 21), null,
				bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 22)).toBytes();
	}

	private static int messageCiphertextLengthOffset() {
		return Integer.BYTES + 1 + 1 + Integer.BYTES
				+ PrivateGroupChatEnvelope.EPOCH_ID_LENGTH
				+ PrivateGroupChatEnvelope.KEY_ID_LENGTH
				+ PrivateGroupChatEnvelope.NONCE_LENGTH;
	}

	private static int keyAnnouncementWrapperCountOffset() {
		return Integer.BYTES + 1 + 1 + Integer.BYTES
				+ PrivateGroupChatEnvelope.EPOCH_ID_LENGTH
				+ PrivateGroupChatEnvelope.KEY_ID_LENGTH
				+ PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH;
	}

	private static int keyRequestOptionalMarkerOffset() {
		return Integer.BYTES + 1 + 1 + Integer.BYTES
				+ PrivateGroupChatEnvelope.EPOCH_ID_LENGTH
				+ PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH;
	}

	private static byte[] bytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; ++i)
			bytes[i] = (byte) (seed + i);

		return bytes;
	}

	private static void writeInt(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) (value >>> 24);
		bytes[offset + 1] = (byte) (value >>> 16);
		bytes[offset + 2] = (byte) (value >>> 8);
		bytes[offset + 3] = (byte) value;
	}

}
