package org.qortium.chat.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.arbitrary.misc.EncryptedDataEnvelope;
import org.qortium.crypto.Crypto;
import org.qortium.transform.TransformationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrivateChatAttachmentInteropVectorsTests {

	private static final String FIXTURE = "chat/interop/qenc-attachment-v2.json";
	private static final HexFormat HEX = HexFormat.of();
	private static final ObjectMapper JSON = new ObjectMapper();
	private static JsonNode vectors;

	@BeforeClass
	public static void loadVectors() throws IOException {
		try (InputStream input = PrivateChatAttachmentInteropVectorsTests.class.getClassLoader()
				.getResourceAsStream(FIXTURE)) {
			if (input == null)
				throw new IOException("Missing private attachment interoperability fixture: " + FIXTURE);
			vectors = JSON.readTree(input);
		}
		assertEquals("qortium-chat-attachment-v2", vectors.path("format").asText());
	}

	@Test
	public void testPayloadVector() throws Exception {
		PrivateChatAttachmentPayload payload = payload();
		assertArrayEquals(hex(vectors.path("payload"), "dataSha256"), Crypto.digest(payload.getData()));
		assertArrayEquals(hex(vectors.path("payload"), "serialized"), payload.toBytes());

		PrivateChatAttachmentPayload parsed = PrivateChatAttachmentPayload.fromBytes(payload.toBytes());
		assertEquals(payload.getFilename(), parsed.getFilename());
		assertEquals(payload.getMediaType(), parsed.getMediaType());
		assertArrayEquals(payload.getData(), parsed.getData());
	}

	@Test
	public void testDirectRecipientVectorAndSenderReopen() throws Exception {
		JsonNode direct = vectors.path("direct");
		List<byte[]> publicKeys = new ArrayList<>();
		List<byte[]> wrapNonces = new ArrayList<>();
		for (JsonNode recipient : direct.path("recipientsInCanonicalKeyIdOrder")) {
			publicKeys.add(hex(recipient, "publicKey"));
			wrapNonces.add(hex(recipient, "wrapNonce"));
			assertArrayEquals(hex(recipient, "keyId"),
					PrivateChatAttachmentCrypto.recipientKeyId(hex(recipient, "publicKey")));
			byte[] wrapAssociatedData = PrivateChatAttachmentCrypto.buildRecipientWrapAssociatedData(
					hex(direct, "ephemeralPublicKey"), hex(recipient, "keyId"),
					hex(direct, "contentNonce"));
			assertArrayEquals(hex(recipient, "wrapAssociatedData"), wrapAssociatedData);
			assertArrayEquals(hex(recipient, "wrappingKey"),
					PrivateChatAttachmentCrypto.deriveRecipientWrappingKey(
							hex(recipient, "sharedSecret"), wrapAssociatedData));
		}

		byte[] envelope = PrivateChatAttachmentCrypto.encryptForRecipients(publicKeys, payload(),
				hex(direct, "ephemeralPrivateKey"), hex(direct, "contentKey"),
				hex(direct, "contentNonce"), wrapNonces);
		assertArrayEquals(hex(direct, "envelope"), envelope);
		assertTrue(EncryptedDataEnvelope.isEnvelope(envelope));

		assertPayload(PrivateChatAttachmentCrypto.decryptForRecipient(
				hex(account("alice"), "privateKey"), envelope));
		assertPayload(PrivateChatAttachmentCrypto.decryptForRecipient(
				hex(account("bob"), "privateKey"), envelope));
		byte[] unrelatedPrivateKey = HEX.parseHex(
				"4142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60");
		assertThrows(GeneralSecurityException.class,
				() -> PrivateChatAttachmentCrypto.decryptForRecipient(unrelatedPrivateKey, envelope));

		byte[] productionEnvelope = PrivateChatAttachmentCrypto.encryptDirect(
				hex(account("alice"), "publicKey"), hex(account("bob"), "publicKey"), payload());
		assertPayload(PrivateChatAttachmentCrypto.decryptForRecipient(
				hex(account("alice"), "privateKey"), productionEnvelope));
		assertPayload(PrivateChatAttachmentCrypto.decryptForRecipient(
				hex(account("bob"), "privateKey"), productionEnvelope));
	}

	@Test
	public void testGroupVectorUsesDomainSeparatedContentKey() throws Exception {
		JsonNode group = vectors.path("group");
		byte[] groupInfo = PrivateChatAttachmentCrypto.buildGroupContentInfo(group.path("groupId").asInt(),
				hex(group, "epochId"), hex(group, "keyId"));
		assertArrayEquals(hex(group, "contentKdfInfo"), groupInfo);
		assertArrayEquals(hex(group, "contentKey"),
				PrivateChatAttachmentCrypto.deriveGroupContentKey(hex(group, "groupKey"), groupInfo));
		assertTrue(!Arrays.equals(hex(group, "groupKey"), hex(group, "contentKey")));

		byte[] envelope = PrivateChatAttachmentCrypto.encryptForGroup(hex(group, "groupKey"),
				group.path("groupId").asInt(), hex(group, "epochId"), hex(group, "keyId"),
				hex(group, "contentNonce"), payload());
		assertArrayEquals(hex(group, "envelope"), envelope);
		assertTrue(EncryptedDataEnvelope.isEnvelope(envelope));
		assertPayload(PrivateChatAttachmentCrypto.decryptForGroup(hex(group, "groupKey"),
				group.path("groupId").asInt(), hex(group, "epochId"), hex(group, "keyId"), envelope));
	}

	@Test
	public void testNegativeVectors() throws Exception {
		for (JsonNode vector : vectors.path("negativeCases")) {
			String id = vector.path("id").asText();
			assertTrue("missing expected layer for " + id,
					!vector.path("expectedLayer").asText().isBlank());
			byte[] mutated = mutate(vector);
			switch (id) {
				case "direct-auth-tag", "direct-recipient-key-id" -> assertThrows(id,
						GeneralSecurityException.class, () -> PrivateChatAttachmentCrypto.decryptForRecipient(
								hex(account("alice"), "privateKey"), mutated));
				case "group-context" -> assertThrows(id, GeneralSecurityException.class,
						() -> PrivateChatAttachmentCrypto.decryptForGroup(hex(vectors.path("group"), "groupKey"),
								vectors.path("group").path("groupId").asInt(),
								hex(vectors.path("group"), "epochId"),
								hex(vectors.path("group"), "keyId"), mutated));
				case "payload-data-digest" -> assertThrows(id, TransformationException.class,
						() -> PrivateChatAttachmentPayload.fromBytes(mutated));
				default -> throw new AssertionError("Unknown negative vector: " + id);
			}
		}
	}

	@Test
	public void testCompleteEnvelopeCountsAgainstOneMibLimit() throws Exception {
		JsonNode direct = vectors.path("direct");
		List<byte[]> publicKeys = List.of(hex(account("alice"), "publicKey"),
				hex(account("bob"), "publicKey"));
		List<byte[]> wrapNonces = List.of(hex(direct.path("recipientsInCanonicalKeyIdOrder").path(0), "wrapNonce"),
				hex(direct.path("recipientsInCanonicalKeyIdOrder").path(1), "wrapNonce"));
		int nonDataBytes = EncryptedDataEnvelope.FIXED_HEADER_LENGTH
				+ EncryptedDataEnvelope.RECIPIENTS_HEADER_PREFIX_LENGTH
				+ 2 * EncryptedDataEnvelope.RECIPIENT_ENTRY_LENGTH
				+ EncryptedDataEnvelope.AUTH_TAG_LENGTH
				+ PrivateChatAttachmentPayload.FIXED_LENGTH + 1;
		int maximumDataBytes = PrivateChatAttachmentCrypto.MAX_ENVELOPE_BYTES - nonDataBytes;

		PrivateChatAttachmentPayload maximum = new PrivateChatAttachmentPayload("x", "",
				new byte[maximumDataBytes]);
		byte[] accepted = PrivateChatAttachmentCrypto.encryptForRecipients(publicKeys, maximum,
				hex(direct, "ephemeralPrivateKey"), hex(direct, "contentKey"),
				hex(direct, "contentNonce"), wrapNonces);
		assertEquals(PrivateChatAttachmentCrypto.MAX_ENVELOPE_BYTES, accepted.length);

		PrivateChatAttachmentPayload oversized = new PrivateChatAttachmentPayload("x", "",
				new byte[maximumDataBytes + 1]);
		assertThrows(IllegalArgumentException.class,
				() -> PrivateChatAttachmentCrypto.encryptForRecipients(publicKeys, oversized,
						hex(direct, "ephemeralPrivateKey"), hex(direct, "contentKey"),
						hex(direct, "contentNonce"), wrapNonces));
	}

	private static PrivateChatAttachmentPayload payload() {
		JsonNode payload = vectors.path("payload");
		return new PrivateChatAttachmentPayload(payload.path("filename").asText(),
				payload.path("mediaType").asText(), hex(payload, "data"));
	}

	private static void assertPayload(PrivateChatAttachmentPayload actual) {
		assertEquals(vectors.path("payload").path("filename").asText(), actual.getFilename());
		assertEquals(vectors.path("payload").path("mediaType").asText(), actual.getMediaType());
		assertArrayEquals(hex(vectors.path("payload"), "data"), actual.getData());
	}

	private static JsonNode account(String name) {
		return vectors.path("accounts").path(name);
	}

	private static byte[] mutate(JsonNode vector) {
		String[] sourcePath = vector.path("source").asText().split("\\.");
		JsonNode source = vectors;
		for (String segment : sourcePath)
			source = source.path(segment);
		byte[] bytes = HEX.parseHex(source.asText());
		JsonNode mutation = vector.path("mutation");
		int offset = mutation.has("xorOffset") ? mutation.path("xorOffset").asInt()
				: bytes.length - mutation.path("xorOffsetFromEnd").asInt();
		bytes[offset] ^= (byte) mutation.path("xor").asInt();
		return bytes;
	}

	private static byte[] hex(JsonNode node, String field) {
		return HEX.parseHex(node.path(field).asText());
	}
}
