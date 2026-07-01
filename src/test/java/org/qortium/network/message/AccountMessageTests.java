package org.qortium.network.message;

import org.junit.Test;
import org.qortium.data.account.AccountBalanceData;
import org.qortium.data.account.AccountData;
import org.qortium.data.network.LiteDataAnchor;
import org.qortium.data.naming.NameData;
import org.qortium.test.common.Common;
import org.qortium.transform.Transformer;
import org.qortium.transform.block.BlockTransformer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AccountMessageTests {

	@Test
	public void testRoundTrip() throws MessageException {
		byte[] publicKey = new byte[32];
		for (int i = 0; i < publicKey.length; i++) {
			publicKey[i] = (byte) (i + 1);
		}

		LiteDataAnchor anchor = anchor();
		AccountData accountData = new AccountData(Common.getTestAccount(null, "alice").getAddress(), publicKey, 1, 3, 4);
		AccountMessage message = new AccountMessage(accountData, anchor);

		AccountMessage decodedMessage = (AccountMessage) AccountMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));
		assertEquals(LiteDataResponseStatus.DATA, decodedMessage.getStatus());
		assertAnchor(anchor, decodedMessage.getAnchor());

		AccountData decodedData = decodedMessage.getAccountData();

		assertEquals(accountData.getAddress(), decodedData.getAddress());
		assertArrayEquals(accountData.getPublicKey(), decodedData.getPublicKey());
		assertEquals(accountData.getDefaultGroupId(), decodedData.getDefaultGroupId());
		assertEquals(accountData.getLevel(), decodedData.getLevel());
		assertEquals(accountData.getBlocksMinted(), decodedData.getBlocksMinted());
	}

	@Test
	public void testAccountUnknownRoundTrip() throws MessageException {
		LiteDataAnchor anchor = anchor();
		AccountMessage message = AccountMessage.unknown(anchor);

		AccountMessage decodedMessage = (AccountMessage) AccountMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(LiteDataResponseStatus.UNKNOWN, decodedMessage.getStatus());
		assertAnchor(anchor, decodedMessage.getAnchor());
		assertEquals(null, decodedMessage.getAccountData());
	}

	@Test
	public void testGetNameRoundTrip() throws MessageException {
		String name = "test-name";
		GetNameMessage message = new GetNameMessage(name);

		GetNameMessage decodedMessage = (GetNameMessage) GetNameMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(name, decodedMessage.getName());
	}

	@Test
	public void testGetAccountRoundTrip() {
		String address = Common.getTestAccount(null, "alice").getAddress();
		GetAccountMessage message = new GetAccountMessage(address);

		GetAccountMessage decodedMessage = (GetAccountMessage) GetAccountMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(address, decodedMessage.getAddress());
	}

	@Test
	public void testGetAccountBalanceRoundTrip() {
		String address = Common.getTestAccount(null, "alice").getAddress();
		long assetId = 123L;
		GetAccountBalanceMessage message = new GetAccountBalanceMessage(address, assetId);

		GetAccountBalanceMessage decodedMessage = (GetAccountBalanceMessage) GetAccountBalanceMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(address, decodedMessage.getAddress());
		assertEquals(assetId, decodedMessage.getAssetId());
	}

	@Test
	public void testGetAccountNamesRoundTrip() {
		String address = Common.getTestAccount(null, "alice").getAddress();
		GetAccountNamesMessage message = new GetAccountNamesMessage(address);

		GetAccountNamesMessage decodedMessage = (GetAccountNamesMessage) GetAccountNamesMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(address, decodedMessage.getAddress());
	}

	@Test
	public void testGetAccountTransactionsRoundTrip() {
		String address = Common.getTestAccount(null, "alice").getAddress();
		int limit = 25;
		int offset = 10;
		GetAccountTransactionsMessage message = new GetAccountTransactionsMessage(address, limit, offset);

		GetAccountTransactionsMessage decodedMessage = (GetAccountTransactionsMessage) GetAccountTransactionsMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(address, decodedMessage.getAddress());
		assertEquals(limit, decodedMessage.getLimit());
		assertEquals(offset, decodedMessage.getOffset());
	}

	@Test
	public void testAccountBalanceRoundTrip() throws MessageException {
		LiteDataAnchor anchor = anchor();
		AccountBalanceData accountBalanceData = new AccountBalanceData(Common.getTestAccount(null, "alice").getAddress(), 123L, 456L);
		AccountBalanceMessage message = new AccountBalanceMessage(accountBalanceData, anchor);

		AccountBalanceMessage decodedMessage = (AccountBalanceMessage) AccountBalanceMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));
		assertEquals(LiteDataResponseStatus.DATA, decodedMessage.getStatus());
		assertAnchor(anchor, decodedMessage.getAnchor());

		AccountBalanceData decodedData = decodedMessage.getAccountBalanceData();

		assertEquals(accountBalanceData.getAddress(), decodedData.getAddress());
		assertEquals(accountBalanceData.getAssetId(), decodedData.getAssetId());
		assertEquals(accountBalanceData.getBalance(), decodedData.getBalance());
	}

	@Test
	public void testAccountBalanceUnknownRoundTrip() throws MessageException {
		LiteDataAnchor anchor = anchor();
		AccountBalanceMessage message = AccountBalanceMessage.unknown(anchor);

		AccountBalanceMessage decodedMessage = (AccountBalanceMessage) AccountBalanceMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(LiteDataResponseStatus.UNKNOWN, decodedMessage.getStatus());
		assertAnchor(anchor, decodedMessage.getAnchor());
		assertEquals(null, decodedMessage.getAccountBalanceData());
	}

	@Test
	public void testNamesRoundTrip() throws MessageException {
		LiteDataAnchor anchor = anchor();
		String owner = Common.getTestAccount(null, "alice").getAddress();
		String saleRecipient = Common.getTestAccount(null, "bob").getAddress();
		byte[] reference = new byte[Transformer.SIGNATURE_LENGTH];
		for (int i = 0; i < reference.length; i++) {
			reference[i] = (byte) (i + 1);
		}

		NameData nameData = new NameData("test-name", "test-name", owner, "{\"test\":true}",
				12345L, 12346L, true, 1000L, saleRecipient, reference, 1);
		NamesMessage message = new NamesMessage(Collections.singletonList(nameData), anchor);

		NamesMessage decodedMessage = (NamesMessage) NamesMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));
		assertEquals(LiteDataResponseStatus.DATA, decodedMessage.getStatus());
		assertAnchor(anchor, decodedMessage.getAnchor());

		List<NameData> decodedDataList = decodedMessage.getNameDataList();
		assertEquals(1, decodedDataList.size());

		NameData decodedData = decodedDataList.get(0);
		assertEquals(nameData.getName(), decodedData.getName());
		assertEquals(nameData.getReducedName(), decodedData.getReducedName());
		assertEquals(nameData.getOwner(), decodedData.getOwner());
		assertEquals(nameData.getData(), decodedData.getData());
		assertEquals(nameData.getRegistered(), decodedData.getRegistered());
		assertEquals(nameData.getUpdated(), decodedData.getUpdated());
		assertEquals(nameData.isForSale(), decodedData.isForSale());
		assertEquals(nameData.getSalePrice(), decodedData.getSalePrice());
		assertEquals(nameData.getSaleRecipient(), decodedData.getSaleRecipient());
		assertArrayEquals(nameData.getReference(), decodedData.getReference());
		assertEquals(nameData.getCreationGroupId(), decodedData.getCreationGroupId());
	}

	@Test
	public void testShortNamesMessageAcceptsMultipleEntries() throws MessageException {
		LiteDataAnchor anchor = anchor();
		String owner = Common.getTestAccount(null, "alice").getAddress();
		byte[] reference = new byte[Transformer.SIGNATURE_LENGTH];
		for (int i = 0; i < reference.length; i++)
			reference[i] = (byte) (i + 1);

		NameData firstName = new NameData("a", "a", owner, "{}", 12345L, reference, 1);
		NameData secondName = new NameData("b", "b", owner, "{}", 12346L, reference, 1);
		NamesMessage message = new NamesMessage(Arrays.asList(firstName, secondName), anchor);

		NamesMessage decodedMessage = (NamesMessage) NamesMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(LiteDataResponseStatus.DATA, decodedMessage.getStatus());
		assertEquals(2, decodedMessage.getNameDataList().size());
		assertEquals(firstName.getName(), decodedMessage.getNameDataList().get(0).getName());
		assertEquals(secondName.getName(), decodedMessage.getNameDataList().get(1).getName());
	}

	@Test
	public void testNamesUnknownRoundTrip() throws MessageException {
		LiteDataAnchor anchor = anchor();
		NamesMessage message = NamesMessage.unknown(anchor);

		NamesMessage decodedMessage = (NamesMessage) NamesMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(LiteDataResponseStatus.UNKNOWN, decodedMessage.getStatus());
		assertAnchor(anchor, decodedMessage.getAnchor());
		assertEquals(null, decodedMessage.getNameDataList());
	}

	@Test
	public void testTransactionsRoundTrip() throws MessageException {
		LiteDataAnchor anchor = anchor();
		TransactionsMessage message = new TransactionsMessage(Collections.emptyList(), anchor);

		TransactionsMessage decodedMessage = (TransactionsMessage) TransactionsMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(LiteDataResponseStatus.DATA, decodedMessage.getStatus());
		assertAnchor(anchor, decodedMessage.getAnchor());
		assertEquals(0, decodedMessage.getTransactions().size());
	}

	@Test
	public void testTransactionsUnknownRoundTrip() throws MessageException {
		LiteDataAnchor anchor = anchor();
		TransactionsMessage message = TransactionsMessage.unknown(anchor);

		TransactionsMessage decodedMessage = (TransactionsMessage) TransactionsMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));

		assertEquals(LiteDataResponseStatus.UNKNOWN, decodedMessage.getStatus());
		assertAnchor(anchor, decodedMessage.getAnchor());
		assertEquals(null, decodedMessage.getTransactions());
	}

	private static LiteDataAnchor anchor() {
		byte[] blockSignature = new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH];
		for (int i = 0; i < blockSignature.length; ++i)
			blockSignature[i] = (byte) (i + 1);

		return new LiteDataAnchor(12, blockSignature, 3456L);
	}

	private static void assertAnchor(LiteDataAnchor expected, LiteDataAnchor actual) {
		assertEquals(expected.getHeight(), actual.getHeight());
		assertArrayEquals(expected.getBlockSignature(), actual.getBlockSignature());
		assertEquals(expected.getTimestamp(), actual.getTimestamp());
	}
}
