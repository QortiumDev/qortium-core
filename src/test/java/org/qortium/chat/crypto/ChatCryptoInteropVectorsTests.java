package org.qortium.chat.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.crypto.Crypto;
import org.qortium.transform.TransformationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ChatCryptoInteropVectorsTests {

	private static final String FIXTURE = "chat/interop/chat-crypto-v1.json";
	private static final HexFormat HEX = HexFormat.of();
	private static final ObjectMapper JSON = new ObjectMapper();

	private static JsonNode vectors;

	@BeforeClass
	public static void loadVectors() throws IOException {
		try (InputStream input = ChatCryptoInteropVectorsTests.class.getClassLoader().getResourceAsStream(FIXTURE)) {
			if (input == null)
				throw new IOException("Missing chat interoperability fixture: " + FIXTURE);

			vectors = JSON.readTree(input);
		}

		assertEquals("qortium-chat-crypto-v1", vectors.path("format").asText());
	}

	@Test
	public void testQdm1Vector() throws Exception {
		JsonNode qdm1 = vectors.path("qdm1");
		JsonNode sender = account(qdm1.path("sender").asText());
		JsonNode recipient = account(qdm1.path("recipient").asText());
		byte[] senderPrivateKey = hex(sender, "privateKey");
		byte[] senderPublicKey = hex(sender, "publicKey");
		byte[] recipientPrivateKey = hex(recipient, "privateKey");
		byte[] recipientPublicKey = hex(recipient, "publicKey");
		byte[] nonce = hex(qdm1, "nonce");
		byte[] plaintext = utf8(qdm1, "plaintextUtf8");
		byte[] sharedSecret = Crypto.getSharedSecret(senderPrivateKey, recipientPublicKey);
		byte[] associatedData = DirectPrivateChatCrypto.buildMessageAssociatedData(senderPublicKey,
				recipientPublicKey);

		assertArrayEquals(senderPublicKey, Crypto.toPublicKey(senderPrivateKey));
		assertArrayEquals(recipientPublicKey, Crypto.toPublicKey(recipientPrivateKey));
		assertArrayEquals(hex(qdm1, "sharedSecret"), sharedSecret);
		assertArrayEquals(hex(qdm1, "associatedData"), associatedData);
		assertArrayEquals(hex(qdm1, "sharedKey"),
				DirectPrivateChatCrypto.deriveSharedKey(sharedSecret, associatedData));

		byte[] envelopeBytes = DirectPrivateChatCrypto.encryptMessage(senderPrivateKey, recipientPublicKey,
				nonce, plaintext);
		assertArrayEquals(hex(qdm1, "envelope"), envelopeBytes);

		DirectPrivateChatEnvelope envelope = DirectPrivateChatEnvelope.fromBytes(envelopeBytes);
		assertArrayEquals(hex(qdm1, "ciphertext"), envelope.getCiphertext());
		assertArrayEquals(plaintext, DirectPrivateChatCrypto.decryptMessage(senderPrivateKey, envelope));
		assertArrayEquals(plaintext, DirectPrivateChatCrypto.decryptMessage(recipientPrivateKey, envelope));

		byte[] tamperedCiphertext = envelope.getCiphertext();
		tamperedCiphertext[tamperedCiphertext.length - 1] ^= 1;
		DirectPrivateChatEnvelope tampered = DirectPrivateChatEnvelope.message(senderPublicKey,
				recipientPublicKey, nonce, tamperedCiphertext);
		assertThrows(GeneralSecurityException.class,
				() -> DirectPrivateChatCrypto.decryptMessage(recipientPrivateKey, tampered));
	}

	@Test
	public void testQpgcMessageVector() throws Exception {
		JsonNode qpgc = vectors.path("qpgc");
		int groupId = qpgc.path("groupId").asInt();
		List<byte[]> memberPublicKeys = memberPublicKeys(qpgc.path("membersInUnsortedInputOrder"));
		byte[] epochId = hex(qpgc, "epochId");
		byte[] groupKey = hex(qpgc, "groupKey");
		byte[] keyId = hex(qpgc, "keyId");
		JsonNode message = qpgc.path("message");
		byte[] nonce = hex(message, "nonce");
		byte[] plaintext = utf8(message, "plaintextUtf8");

		assertArrayEquals(epochId, PrivateGroupChatMembership.computeEpochId(groupId, memberPublicKeys));
		assertArrayEquals(keyId, PrivateGroupChatCrypto.computeKeyId(groupId, epochId, groupKey));
		assertArrayEquals(hex(message, "associatedData"),
				PrivateGroupChatCrypto.buildMessageAssociatedData(groupId, epochId, keyId));

		byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, groupId, epochId, keyId,
				nonce, plaintext);
		assertArrayEquals(hex(message, "ciphertext"), ciphertext);
		assertArrayEquals(plaintext, PrivateGroupChatCrypto.decryptMessage(groupKey, groupId, epochId,
				keyId, nonce, ciphertext));

		byte[] envelopeBytes = PrivateGroupChatEnvelope.message(groupId, epochId, keyId, nonce,
				ciphertext).toBytes();
		assertArrayEquals(hex(message, "envelope"), envelopeBytes);
		assertArrayEquals(envelopeBytes, PrivateGroupChatEnvelope.fromBytes(envelopeBytes).toBytes());

		assertThrows(GeneralSecurityException.class,
				() -> PrivateGroupChatCrypto.decryptMessage(groupKey, groupId + 1, epochId, keyId,
						nonce, ciphertext));
	}

	@Test
	public void testQpgcKeyWrapVector() throws Exception {
		JsonNode qpgc = vectors.path("qpgc");
		JsonNode keyWrap = qpgc.path("bobKeyWrap");
		JsonNode announcer = account(keyWrap.path("announcer").asText());
		JsonNode recipient = account(keyWrap.path("recipient").asText());
		int groupId = qpgc.path("groupId").asInt();
		byte[] epochId = hex(qpgc, "epochId");
		byte[] groupKey = hex(qpgc, "groupKey");
		byte[] keyId = hex(qpgc, "keyId");
		byte[] announcerPrivateKey = hex(announcer, "privateKey");
		byte[] announcerPublicKey = hex(announcer, "publicKey");
		byte[] recipientPrivateKey = hex(recipient, "privateKey");
		byte[] recipientPublicKey = hex(recipient, "publicKey");
		byte[] nonce = hex(keyWrap, "nonce");
		byte[] sharedSecret = Crypto.getSharedSecret(announcerPrivateKey, recipientPublicKey);
		byte[] associatedData = PrivateGroupChatCrypto.buildKeyWrapAssociatedData(groupId, epochId, keyId,
				announcerPublicKey, recipientPublicKey);

		assertArrayEquals(hex(keyWrap, "sharedSecret"), sharedSecret);
		assertArrayEquals(hex(keyWrap, "associatedData"), associatedData);
		assertArrayEquals(hex(keyWrap, "wrappingKey"),
				PrivateGroupChatCrypto.deriveWrappingKey(sharedSecret, associatedData));

		byte[] wrappedKey = PrivateGroupChatCrypto.wrapGroupKey(groupId, epochId, keyId, groupKey,
				announcerPrivateKey, recipientPublicKey, nonce);
		assertArrayEquals(hex(keyWrap, "wrappedKey"), wrappedKey);
		assertArrayEquals(groupKey, PrivateGroupChatCrypto.unwrapGroupKey(groupId, epochId, keyId,
				wrappedKey, recipientPrivateKey, announcerPublicKey));

		byte[] tampered = wrappedKey.clone();
		tampered[tampered.length - 1] ^= 1;
		assertThrows(GeneralSecurityException.class,
				() -> PrivateGroupChatCrypto.unwrapGroupKey(groupId, epochId, keyId, tampered,
						recipientPrivateKey, announcerPublicKey));
	}

	@Test
	public void testQpgcKeyAnnouncementVector() throws Exception {
		JsonNode qpgc = vectors.path("qpgc");
		JsonNode vector = qpgc.path("keyAnnouncement");
		PrivateGroupChatMembership.MembershipEpoch epoch = qpgcEpoch(qpgc);
		JsonNode announcer = account(vector.path("announcer").asText());
		byte[] groupKey = hex(qpgc, "groupKey");
		List<byte[]> wrapperNonces = hexList(vector.path("wrapperNoncesInSortedMemberOrder"));

		PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyAnnouncement.create(epoch, groupKey,
				hex(announcer, "privateKey"), wrapperNonces);
		assertArrayEquals(hex(vector, "signingBytes"), PrivateGroupChatKeyAnnouncement.buildSigningBytes(
				epoch.getGroupId(), epoch.getEpochId(), envelope.getKeyId(), envelope.getCreatorPublicKey(),
				envelope.getKeyWrappers()));
		assertArrayEquals(hex(vector, "signature"), envelope.getSignature());
		assertArrayEquals(hex(vector, "envelope"), envelope.toBytes());
		assertEquals(vector.path("wrappers").size(), envelope.getKeyWrappers().size());

		for (int index = 0; index < envelope.getKeyWrappers().size(); ++index) {
			JsonNode expectedWrapper = vector.path("wrappers").path(index);
			PrivateGroupChatEnvelope.KeyWrapper wrapper = envelope.getKeyWrappers().get(index);
			assertArrayEquals(hex(account(expectedWrapper.path("recipient").asText()), "publicKey"),
					wrapper.getRecipientPublicKey());
			assertArrayEquals(hex(expectedWrapper, "wrappedKey"), wrapper.getWrappedKey());
		}

		assertTrue(PrivateGroupChatKeyAnnouncement.isValid(epoch, envelope));
		for (JsonNode memberName : qpgc.path("membersInUnsortedInputOrder"))
			assertArrayEquals(groupKey, PrivateGroupChatKeyAnnouncement.unwrapForRecipient(epoch, envelope,
					hex(account(memberName.asText()), "privateKey")));

		byte[] badSignature = envelope.getSignature();
		badSignature[0] ^= 1;
		PrivateGroupChatEnvelope tampered = PrivateGroupChatEnvelope.keyAnnouncement(envelope.getGroupId(),
				envelope.getEpochId(), envelope.getKeyId(), envelope.getCreatorPublicKey(),
				envelope.getKeyWrappers(), badSignature);
		assertFalse(PrivateGroupChatKeyAnnouncement.isValid(epoch, tampered));
	}

	@Test
	public void testQpgcKeyRequestVectors() throws Exception {
		JsonNode qpgc = vectors.path("qpgc");
		PrivateGroupChatMembership.MembershipEpoch epoch = qpgcEpoch(qpgc);
		assertKeyRequestVector(epoch, qpgc.path("keyRequest"), hex(qpgc, "keyId"));
		assertKeyRequestVector(epoch, qpgc.path("currentKeyRequest"), null);
	}

	@Test
	public void testQpgcRotationRequestVector() throws Exception {
		JsonNode qpgc = vectors.path("qpgc");
		JsonNode vector = qpgc.path("rotationRequest");
		PrivateGroupChatMembership.MembershipEpoch epoch = qpgcEpoch(qpgc);
		JsonNode requester = account(vector.path("requester").asText());

		PrivateGroupChatEnvelope envelope = PrivateGroupChatRotationRequest.create(epoch,
				hex(requester, "privateKey"));
		assertArrayEquals(hex(vector, "signingBytes"), PrivateGroupChatRotationRequest.buildSigningBytes(
				epoch.getGroupId(), epoch.getEpochId(), hex(requester, "publicKey")));
		assertArrayEquals(hex(vector, "signature"), envelope.getSignature());
		assertArrayEquals(hex(vector, "envelope"), envelope.toBytes());
		assertTrue(PrivateGroupChatRotationRequest.isValid(epoch, envelope));
	}

	@Test
	public void testLanguageNeutralPositiveVariants() throws Exception {
		int count = 0;
		for (JsonNode vector : vectors.path("interopCases").path("positiveVariants")) {
			assertEquals("validateReorderedAnnouncement", vector.path("operation").asText());
			PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(resolveSource(vector));
			List<PrivateGroupChatEnvelope.KeyWrapper> wrappers = new ArrayList<>(envelope.getKeyWrappers());
			Collections.reverse(wrappers);
			PrivateGroupChatEnvelope reordered = PrivateGroupChatEnvelope.keyAnnouncement(envelope.getGroupId(),
					envelope.getEpochId(), envelope.getKeyId(), envelope.getCreatorPublicKey(), wrappers,
					envelope.getSignature());
			assertTrue(vector.path("id").asText(),
					PrivateGroupChatKeyAnnouncement.isValid(qpgcEpoch(vectors.path("qpgc")), reordered));
			++count;
		}
		assertEquals(1, count);
	}

	@Test
	public void testLanguageNeutralNegativeCases() throws Exception {
		int count = 0;
		for (JsonNode vector : vectors.path("interopCases").path("negativeCases")) {
			assertFalse("missing expected layer for " + vector.path("id").asText(),
					vector.path("expectedLayer").asText().isBlank());
			assertNegativeCase(vector);
			++count;
		}
		assertEquals(15, count);
	}

	private static void assertNegativeCase(JsonNode vector) throws Exception {
		String operation = vector.path("operation").asText();
		switch (operation) {
			case "decryptQdm1Mutation": {
				DirectPrivateChatEnvelope envelope = DirectPrivateChatEnvelope.fromBytes(mutate(vector));
				byte[] recipientPrivateKey = hex(account(vectors.path("qdm1").path("recipient").asText()),
						"privateKey");
				assertThrows(vector.path("id").asText(), GeneralSecurityException.class,
						() -> DirectPrivateChatCrypto.decryptMessage(recipientPrivateKey, envelope));
				break;
			}

			case "parseDirectMutation":
				assertThrows(vector.path("id").asText(), TransformationException.class,
						() -> DirectPrivateChatEnvelope.fromBytes(mutate(vector)));
				break;

			case "decryptQpgcMutation": {
				PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(mutate(vector));
				byte[] groupKey = hex(vectors.path("qpgc"), "groupKey");
				assertThrows(vector.path("id").asText(), GeneralSecurityException.class,
						() -> PrivateGroupChatCrypto.decryptMessage(groupKey, envelope.getGroupId(),
								envelope.getEpochId(), envelope.getKeyId(), envelope.getNonce(),
								envelope.getCiphertext()));
				break;
			}

			case "parseQpgcMutation":
				assertThrows(vector.path("id").asText(), TransformationException.class,
						() -> PrivateGroupChatEnvelope.fromBytes(mutate(vector)));
				break;

			case "validateDuplicateAnnouncementWrapper":
				assertFalse(vector.path("id").asText(), validateAnnouncementWithWrapperChange(vector, true));
				break;

			case "validateMissingAnnouncementWrapper":
				assertFalse(vector.path("id").asText(), validateAnnouncementWithWrapperChange(vector, false));
				break;

			case "validateAnnouncementSignatureMutation": {
				PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(resolveSource(vector));
				byte[] signature = envelope.getSignature();
				signature[0] ^= 1;
				PrivateGroupChatEnvelope tampered = PrivateGroupChatEnvelope.keyAnnouncement(envelope.getGroupId(),
						envelope.getEpochId(), envelope.getKeyId(), envelope.getCreatorPublicKey(),
						envelope.getKeyWrappers(), signature);
				assertFalse(vector.path("id").asText(), PrivateGroupChatKeyAnnouncement.isValid(
						qpgcEpoch(vectors.path("qpgc")), tampered));
				break;
			}

			case "constructOversizedDirectEnvelope":
				assertThrows(vector.path("id").asText(), IllegalArgumentException.class,
						() -> DirectPrivateChatEnvelope.message(new byte[32], new byte[32], new byte[12],
								new byte[vector.path("length").asInt()]));
				break;

			case "constructOversizedQpgcEnvelope":
				assertThrows(vector.path("id").asText(), IllegalArgumentException.class,
						() -> PrivateGroupChatEnvelope.message(12, new byte[32], new byte[32], new byte[12],
								new byte[vector.path("length").asInt()]));
				break;

			default:
				throw new AssertionError("Unhandled negative fixture operation: " + operation);
		}
	}

	private static boolean validateAnnouncementWithWrapperChange(JsonNode vector, boolean duplicate)
			throws TransformationException {
		PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(resolveSource(vector));
		List<PrivateGroupChatEnvelope.KeyWrapper> wrappers = new ArrayList<>(envelope.getKeyWrappers());
		if (duplicate)
			wrappers.set(1, wrappers.get(0));
		else
			wrappers.remove(0);
		PrivateGroupChatEnvelope changed = PrivateGroupChatEnvelope.keyAnnouncement(envelope.getGroupId(),
				envelope.getEpochId(), envelope.getKeyId(), envelope.getCreatorPublicKey(), wrappers,
				envelope.getSignature());
		return PrivateGroupChatKeyAnnouncement.isValid(qpgcEpoch(vectors.path("qpgc")), changed);
	}

	private static byte[] mutate(JsonNode vector) {
		byte[] source = resolveSource(vector);
		JsonNode mutation = vector.path("mutation");
		switch (mutation.path("kind").asText()) {
			case "xorByte": {
				int offset = mutation.has("offset") ? mutation.path("offset").asInt()
						: source.length - mutation.path("offsetFromEnd").asInt();
				source[offset] ^= (byte) mutation.path("xor").asInt();
				return source;
			}

			case "appendByte": {
				byte[] output = java.util.Arrays.copyOf(source, source.length + 1);
				output[output.length - 1] = (byte) mutation.path("value").asInt();
				return output;
			}

			default:
				throw new AssertionError("Unhandled fixture mutation: " + mutation.path("kind").asText());
		}
	}

	private static byte[] resolveSource(JsonNode vector) {
		switch (vector.path("source").asText()) {
			case "qdm1.envelope":
				return hex(vectors.path("qdm1"), "envelope");
			case "qpgc.message.envelope":
				return hex(vectors.path("qpgc").path("message"), "envelope");
			case "qpgc.keyAnnouncement.envelope":
				return hex(vectors.path("qpgc").path("keyAnnouncement"), "envelope");
			default:
				throw new AssertionError("Unknown fixture source: " + vector.path("source").asText());
		}
	}

	private static void assertKeyRequestVector(PrivateGroupChatMembership.MembershipEpoch epoch,
			JsonNode vector, byte[] keyId) throws Exception {
		JsonNode requester = account(vector.path("requester").asText());
		byte[] requesterPrivateKey = hex(requester, "privateKey");
		byte[] requesterPublicKey = hex(requester, "publicKey");
		PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyRequest.create(epoch.getGroupId(),
				epoch.getEpochId(), requesterPrivateKey, keyId);

		assertArrayEquals(hex(vector, "signingBytes"), PrivateGroupChatKeyRequest.buildSigningBytes(
				epoch.getGroupId(), epoch.getEpochId(), requesterPublicKey, keyId));
		assertArrayEquals(hex(vector, "signature"), envelope.getSignature());
		assertArrayEquals(hex(vector, "envelope"), envelope.toBytes());
		assertEquals(keyId != null, envelope.hasRequestedKeyId());
		assertTrue(PrivateGroupChatKeyRequest.isValid(epoch, envelope));
	}

	private static PrivateGroupChatMembership.MembershipEpoch qpgcEpoch(JsonNode qpgc) {
		return PrivateGroupChatMembership.fromMemberPublicKeys(qpgc.path("groupId").asInt(),
				memberPublicKeys(qpgc.path("membersInUnsortedInputOrder")));
	}

	private static JsonNode account(String name) {
		JsonNode account = vectors.path("accounts").path(name);
		if (account.isMissingNode())
			throw new IllegalArgumentException("Fixture references missing account: " + name);
		return account;
	}

	private static List<byte[]> memberPublicKeys(JsonNode memberNames) {
		List<byte[]> publicKeys = new ArrayList<>();
		for (JsonNode memberName : memberNames)
			publicKeys.add(hex(account(memberName.asText()), "publicKey"));
		return publicKeys;
	}

	private static List<byte[]> hexList(JsonNode values) {
		List<byte[]> output = new ArrayList<>();
		for (JsonNode value : values)
			output.add(HEX.parseHex(value.asText()));
		return output;
	}

	private static byte[] utf8(JsonNode object, String field) {
		return object.path(field).asText().getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] hex(JsonNode object, String field) {
		String value = object.path(field).asText(null);
		if (value == null)
			throw new IllegalArgumentException("Fixture field is missing: " + field);
		return HEX.parseHex(value);
	}
}
