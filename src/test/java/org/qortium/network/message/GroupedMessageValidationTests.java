package org.qortium.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.junit.Test;
import org.qortium.data.network.OnlineAccountData;
import org.qortium.data.network.TradePresenceData;
import org.qortium.transform.Transformer;
import org.qortium.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class GroupedMessageValidationTests {

	@Test
	public void testOnlineAccountsEmptyRoundTrip() throws Exception {
		OnlineAccountsMessage decoded = (OnlineAccountsMessage) OnlineAccountsMessage.fromByteBuffer(1,
				ByteBuffer.wrap(new OnlineAccountsMessage(Collections.emptyList()).dataBytes));

		assertEquals(0, decoded.getOnlineAccounts().size());
	}

	@Test
	public void testOnlineAccountsRejectMalformedGroupCounts() throws Exception {
		assertGroupedParserRejectsMalformedCounts(data -> OnlineAccountsMessage.fromByteBuffer(1, ByteBuffer.wrap(data)),
				this::onlineAccountEntry);
	}

	@Test
	public void testOnlineAccountsAcceptTwoGroups() throws Exception {
		byte[] firstEntry = onlineAccountEntry(1);
		byte[] secondEntry = onlineAccountEntry(2);
		OnlineAccountsMessage decoded = (OnlineAccountsMessage) OnlineAccountsMessage.fromByteBuffer(1,
				ByteBuffer.wrap(twoGroupPayload(firstEntry, secondEntry)));

		List<OnlineAccountData> onlineAccounts = decoded.getOnlineAccounts();
		assertEquals(2, onlineAccounts.size());
		assertEquals(1000L, onlineAccounts.get(0).getTimestamp());
		assertEquals(2000L, onlineAccounts.get(1).getTimestamp());
		assertArrayEquals(firstEntrySlice(firstEntry, Transformer.SIGNATURE_LENGTH, Transformer.PUBLIC_KEY_LENGTH),
				onlineAccounts.get(0).getPublicKey());
	}

	@Test
	public void testGetTradePresencesEmptyRoundTrip() throws Exception {
		GetTradePresencesMessage decoded = (GetTradePresencesMessage) GetTradePresencesMessage.fromByteBuffer(1,
				ByteBuffer.wrap(new GetTradePresencesMessage(Collections.emptyList()).dataBytes));

		assertEquals(0, decoded.getTradePresences().size());
	}

	@Test
	public void testGetTradePresencesRejectMalformedGroupCounts() throws Exception {
		assertGroupedParserRejectsMalformedCounts(data -> GetTradePresencesMessage.fromByteBuffer(1, ByteBuffer.wrap(data)),
				this::publicKeyEntry);
	}

	@Test
	public void testGetTradePresencesAcceptTwoGroups() throws Exception {
		byte[] firstEntry = publicKeyEntry(1);
		byte[] secondEntry = publicKeyEntry(2);
		GetTradePresencesMessage decoded = (GetTradePresencesMessage) GetTradePresencesMessage.fromByteBuffer(1,
				ByteBuffer.wrap(twoGroupPayload(firstEntry, secondEntry)));

		List<TradePresenceData> tradePresences = decoded.getTradePresences();
		assertEquals(2, tradePresences.size());
		assertEquals(1000L, tradePresences.get(0).getTimestamp());
		assertEquals(2000L, tradePresences.get(1).getTimestamp());
		assertArrayEquals(firstEntry, tradePresences.get(0).getPublicKey());
	}

	@Test
	public void testTradePresencesEmptyRoundTrip() throws Exception {
		TradePresencesMessage decoded = (TradePresencesMessage) TradePresencesMessage.fromByteBuffer(1,
				ByteBuffer.wrap(new TradePresencesMessage(Collections.emptyList()).dataBytes));

		assertEquals(0, decoded.getTradePresences().size());
	}

	@Test
	public void testTradePresencesRejectMalformedGroupCounts() throws Exception {
		assertGroupedParserRejectsMalformedCounts(data -> TradePresencesMessage.fromByteBuffer(1, ByteBuffer.wrap(data)),
				this::tradePresenceEntry);
	}

	@Test
	public void testTradePresencesAcceptTwoGroups() throws Exception {
		byte[] firstEntry = tradePresenceEntry(1);
		byte[] secondEntry = tradePresenceEntry(2);
		TradePresencesMessage decoded = (TradePresencesMessage) TradePresencesMessage.fromByteBuffer(1,
				ByteBuffer.wrap(twoGroupPayload(firstEntry, secondEntry)));

		List<TradePresenceData> tradePresences = decoded.getTradePresences();
		assertEquals(2, tradePresences.size());
		assertEquals(1000L, tradePresences.get(0).getTimestamp());
		assertEquals(2000L, tradePresences.get(1).getTimestamp());
		assertArrayEquals(firstEntrySlice(firstEntry, 0, Transformer.PUBLIC_KEY_LENGTH),
				tradePresences.get(0).getPublicKey());
	}

	private void assertGroupedParserRejectsMalformedCounts(Parser parser, EntryFactory entryFactory) throws Exception {
		byte[] entry = entryFactory.entry(1);

		assertRejects(parser, Ints.toByteArray(1));
		assertRejects(parser, concat(Ints.toByteArray(1), Longs.toByteArray(1000L)));

		byte[] validFirstGroup = oneGroupPayload(entry);
		assertRejects(parser, concat(validFirstGroup, Ints.toByteArray(-1)));
		assertRejects(parser, concat(validFirstGroup, Ints.toByteArray(0)));
		assertRejects(parser, concat(validFirstGroup, Ints.toByteArray(Integer.MAX_VALUE)));
		assertRejects(parser, concat(validFirstGroup, new byte[] { 1, 2, 3 }));
	}

	private void assertRejects(Parser parser, byte[] data) throws Exception {
		try {
			parser.parse(data);
		} catch (MessageException e) {
			return;
		}

		throw new AssertionError("Expected malformed grouped message to be rejected");
	}

	private byte[] oneGroupPayload(byte[] entry) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(Ints.toByteArray(1));
		bytes.write(Longs.toByteArray(1000L));
		bytes.write(entry);
		return bytes.toByteArray();
	}

	private byte[] twoGroupPayload(byte[] firstEntry, byte[] secondEntry) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(oneGroupPayload(firstEntry));
		bytes.write(Ints.toByteArray(1));
		bytes.write(Longs.toByteArray(2000L));
		bytes.write(secondEntry);
		return bytes.toByteArray();
	}

	private byte[] onlineAccountEntry(int seed) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(filledBytes(Transformer.SIGNATURE_LENGTH, seed));
		bytes.write(filledBytes(Transformer.PUBLIC_KEY_LENGTH, seed + 1));
		bytes.write(Ints.toByteArray(seed));
		return bytes.toByteArray();
	}

	private byte[] publicKeyEntry(int seed) {
		return filledBytes(Transformer.PUBLIC_KEY_LENGTH, seed);
	}

	private byte[] tradePresenceEntry(int seed) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(filledBytes(Transformer.PUBLIC_KEY_LENGTH, seed));
		bytes.write(filledBytes(Transformer.SIGNATURE_LENGTH, seed + 1));
		bytes.write(Base58.decode(Base58.encode(filledBytes(Transformer.ADDRESS_LENGTH, seed + 2))));
		return bytes.toByteArray();
	}

	private static byte[] filledBytes(int size, int seed) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < bytes.length; ++i)
			bytes[i] = (byte) (seed + i);
		return bytes;
	}

	private static byte[] firstEntrySlice(byte[] entry, int offset, int length) {
		byte[] slice = new byte[length];
		System.arraycopy(entry, offset, slice, 0, length);
		return slice;
	}

	private static byte[] concat(byte[] first, byte[] second) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(first);
		bytes.write(second);
		return bytes.toByteArray();
	}

	@FunctionalInterface
	private interface Parser {
		Message parse(byte[] data) throws MessageException;
	}

	@FunctionalInterface
	private interface EntryFactory {
		byte[] entry(int seed) throws IOException;
	}
}
