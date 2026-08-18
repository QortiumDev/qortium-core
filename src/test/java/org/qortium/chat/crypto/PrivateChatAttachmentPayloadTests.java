package org.qortium.chat.crypto;

import org.junit.Test;
import org.qortium.transform.TransformationException;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PrivateChatAttachmentPayloadTests {

	@Test
	public void roundTripsEncryptedMetadataAndData() throws Exception {
		byte[] data = "private attachment bytes".getBytes(StandardCharsets.UTF_8);
		PrivateChatAttachmentPayload payload = new PrivateChatAttachmentPayload(
				"photo ☃.png", "image/png", data);

		PrivateChatAttachmentPayload parsed = PrivateChatAttachmentPayload.fromBytes(payload.toBytes());
		assertEquals(payload.getFilename(), parsed.getFilename());
		assertEquals(payload.getMediaType(), parsed.getMediaType());
		assertArrayEquals(data, parsed.getData());
	}

	@Test
	public void rejectsUnsafeFilenameAndControlMetadata() {
		byte[] data = { 1 };
		assertThrows(IllegalArgumentException.class,
				() -> new PrivateChatAttachmentPayload("../secret.txt", "text/plain", data));
		assertThrows(IllegalArgumentException.class,
				() -> new PrivateChatAttachmentPayload("secret.txt", "text/plain\r\nunsafe", data));
	}

	@Test
	public void rejectsDigestAndLengthMutations() {
		byte[] encoded = new PrivateChatAttachmentPayload("secret.txt", "text/plain",
				new byte[] { 1, 2, 3 }).toBytes();

		byte[] digestMutation = encoded.clone();
		digestMutation[14] ^= 1;
		assertThrows(TransformationException.class,
				() -> PrivateChatAttachmentPayload.fromBytes(digestMutation));

		byte[] lengthMutation = encoded.clone();
		lengthMutation[13] = (byte) (lengthMutation[13] + 1);
		assertThrows(TransformationException.class,
				() -> PrivateChatAttachmentPayload.fromBytes(lengthMutation));
	}
}
