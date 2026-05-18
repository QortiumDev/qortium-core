package org.qortal.network.message;

import org.junit.Test;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.data.naming.NameData;
import org.qortal.test.common.Common;
import org.qortal.transform.Transformer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AccountMessageTests {

	@Test
	public void testRoundTrip() {
		byte[] publicKey = new byte[32];
		for (int i = 0; i < publicKey.length; i++) {
			publicKey[i] = (byte) (i + 1);
		}

		AccountData accountData = new AccountData(Common.getTestAccount(null, "alice").getAddress(), publicKey, 1, 3, 4);
		AccountMessage message = new AccountMessage(accountData);

		AccountMessage decodedMessage = (AccountMessage) AccountMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));
		AccountData decodedData = decodedMessage.getAccountData();

		assertEquals(accountData.getAddress(), decodedData.getAddress());
		assertArrayEquals(accountData.getPublicKey(), decodedData.getPublicKey());
		assertEquals(accountData.getDefaultGroupId(), decodedData.getDefaultGroupId());
		assertEquals(accountData.getLevel(), decodedData.getLevel());
		assertEquals(accountData.getBlocksMinted(), decodedData.getBlocksMinted());
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
	public void testAccountBalanceRoundTrip() {
		AccountBalanceData accountBalanceData = new AccountBalanceData(Common.getTestAccount(null, "alice").getAddress(), 123L, 456L);
		AccountBalanceMessage message = new AccountBalanceMessage(accountBalanceData);

		AccountBalanceMessage decodedMessage = (AccountBalanceMessage) AccountBalanceMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));
		AccountBalanceData decodedData = decodedMessage.getAccountBalanceData();

		assertEquals(accountBalanceData.getAddress(), decodedData.getAddress());
		assertEquals(accountBalanceData.getAssetId(), decodedData.getAssetId());
		assertEquals(accountBalanceData.getBalance(), decodedData.getBalance());
	}

	@Test
	public void testNamesRoundTrip() throws MessageException {
		String owner = Common.getTestAccount(null, "alice").getAddress();
		String saleRecipient = Common.getTestAccount(null, "bob").getAddress();
		byte[] reference = new byte[Transformer.SIGNATURE_LENGTH];
		for (int i = 0; i < reference.length; i++) {
			reference[i] = (byte) (i + 1);
		}

		NameData nameData = new NameData("test-name", "test-name", owner, "{\"test\":true}",
				12345L, 12346L, true, 1000L, saleRecipient, reference, 1);
		NamesMessage message = new NamesMessage(Collections.singletonList(nameData));

		NamesMessage decodedMessage = (NamesMessage) NamesMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));
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
}
