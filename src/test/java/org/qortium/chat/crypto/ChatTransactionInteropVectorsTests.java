package org.qortium.chat.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.crypto.Crypto;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.test.common.Common;
import org.qortium.transform.transaction.ChatTransactionTransformer;
import org.qortium.transform.transaction.TransactionTransformer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatTransactionInteropVectorsTests {

	private static final String FIXTURE = "chat/interop/chat-crypto-v1.json";
	private static final HexFormat HEX = HexFormat.of();
	private static final ObjectMapper JSON = new ObjectMapper();

	private static JsonNode vectors;

	@BeforeClass
	public static void beforeClass() throws Exception {
		Common.useDefaultSettings();
		try (InputStream input = ChatTransactionInteropVectorsTests.class.getClassLoader()
				.getResourceAsStream(FIXTURE)) {
			if (input == null)
				throw new IOException("Missing chat interoperability fixture: " + FIXTURE);
			vectors = JSON.readTree(input);
		}
	}

	@Test
	public void testChatTransactionVectors() throws Exception {
		Iterator<Map.Entry<String, JsonNode>> entries = vectors.path("chatTransactions").fields();
		int count = 0;
		while (entries.hasNext()) {
			Map.Entry<String, JsonNode> entry = entries.next();
			assertTransactionVector(entry.getKey(), entry.getValue());
			++count;
		}
		assertEquals(3, count);
	}

	@Test
	public void testRelaySeparatesOuterSenderFromAnnouncementCreator() throws Exception {
		JsonNode vector = vectors.path("chatTransactions").path("relayedKeyAnnouncement");
		ChatTransactionData chat = buildChat(vector);
		PrivateGroupChatEnvelope announcement = PrivateGroupChatEnvelope.fromBytes(chat.getData());

		assertArrayEquals(hex(account("bob"), "publicKey"), chat.getSenderPublicKey());
		assertArrayEquals(hex(account("alice"), "publicKey"), announcement.getCreatorPublicKey());
		assertFalse(chat.getIsText());
		assertTrue(chat.getIsEncrypted());
	}

	private static void assertTransactionVector(String name, JsonNode vector) throws Exception {
		ChatTransactionData transactionData = buildChat(vector);
		JsonNode sender = account(vector.path("sender").asText());
		byte[] unsigned = ChatTransactionTransformer.toBytes(transactionData);

		assertArrayEquals(name + " unsigned bytes", hex(vector, "unsigned"), unsigned);
		assertArrayEquals(name + " signing bytes", unsigned,
				TransactionTransformer.toBytesForSigning(transactionData));

		byte[] signature = Crypto.sign(hex(sender, "privateKey"), unsigned);
		assertArrayEquals(name + " signature", hex(vector, "signature"), signature);
		transactionData.setSignature(signature);
		byte[] signed = ChatTransactionTransformer.toBytes(transactionData);
		assertArrayEquals(name + " signed bytes", hex(vector, "signed"), signed);
		assertTrue(name + " signature verification",
				Crypto.verify(hex(sender, "publicKey"), signature,
						TransactionTransformer.toBytesForSigning(transactionData)));

		TransactionData decodedData = TransactionTransformer.fromBytes(signed);
		assertTrue(name + " decoded type", decodedData instanceof ChatTransactionData);
		ChatTransactionData decoded = (ChatTransactionData) decodedData;
		assertEquals(vector.path("timestamp").asLong(), decoded.getTimestamp());
		assertEquals(vector.path("txGroupId").asInt(), decoded.getTxGroupId());
		assertEquals(vector.path("nonce").asInt(), decoded.getNonce());
		assertArrayEquals(transactionData.getData(), decoded.getData());
		assertArrayEquals(transactionData.getChatReference(), decoded.getChatReference());
		assertArrayEquals(signature, decoded.getSignature());
	}

	private static ChatTransactionData buildChat(JsonNode vector) {
		JsonNode sender = account(vector.path("sender").asText());
		byte[] senderPublicKey = hex(sender, "publicKey");
		byte[] data = vector.has("dataUtf8")
				? vector.path("dataUtf8").asText().getBytes(StandardCharsets.UTF_8)
				: resolveData(vector.path("dataFrom").asText());
		byte[] chatReference = vector.has("chatReference") ? hex(vector, "chatReference") : null;
		int nonce = vector.path("nonce").asInt();
		BaseTransactionData base = new BaseTransactionData(vector.path("timestamp").asLong(),
				vector.path("txGroupId").asInt(), senderPublicKey, 0L, nonce, null);

		return new ChatTransactionData(base, Crypto.toAddress(senderPublicKey), nonce, null, chatReference,
				data, vector.path("isText").asBoolean(), vector.path("isEncrypted").asBoolean());
	}

	private static byte[] resolveData(String path) {
		if ("qpgc.keyAnnouncement.envelope".equals(path))
			return hex(vectors.path("qpgc").path("keyAnnouncement"), "envelope");
		throw new IllegalArgumentException("Unknown fixture dataFrom path: " + path);
	}

	private static JsonNode account(String name) {
		JsonNode account = vectors.path("accounts").path(name);
		if (account.isMissingNode())
			throw new IllegalArgumentException("Fixture references missing account: " + name);
		return account;
	}

	private static byte[] hex(JsonNode object, String field) {
		String value = object.path(field).asText(null);
		if (value == null)
			throw new IllegalArgumentException("Fixture field is missing: " + field);
		return HEX.parseHex(value);
	}
}
