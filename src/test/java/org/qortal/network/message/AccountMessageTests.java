package org.qortal.network.message;

import org.junit.Test;
import org.qortal.data.account.AccountData;
import org.qortal.test.common.Common;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AccountMessageTests {

	@Test
	public void testRoundTrip() {
		byte[] reference = new byte[64];
		byte[] publicKey = new byte[32];
		for (int i = 0; i < reference.length; i++) {
			reference[i] = (byte) i;
		}
		for (int i = 0; i < publicKey.length; i++) {
			publicKey[i] = (byte) (i + 1);
		}

		AccountData accountData = new AccountData(Common.getTestAccount(null, "alice").getAddress(), reference, publicKey, 1, 2, 3, 4);
		AccountMessage message = new AccountMessage(accountData);

		AccountMessage decodedMessage = (AccountMessage) AccountMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));
		AccountData decodedData = decodedMessage.getAccountData();

		assertEquals(accountData.getAddress(), decodedData.getAddress());
		assertArrayEquals(accountData.getReference(), decodedData.getReference());
		assertArrayEquals(accountData.getPublicKey(), decodedData.getPublicKey());
		assertEquals(accountData.getDefaultGroupId(), decodedData.getDefaultGroupId());
		assertEquals(accountData.getFlags(), decodedData.getFlags());
		assertEquals(accountData.getLevel(), decodedData.getLevel());
		assertEquals(accountData.getBlocksMinted(), decodedData.getBlocksMinted());
	}
}
