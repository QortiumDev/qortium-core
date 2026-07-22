package org.qortium.test;

import org.junit.Test;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SetGroupAvatarTransactionData;
import org.qortium.data.transaction.SetAccountAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;
import org.qortium.transform.transaction.TransactionTransformer;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SetGroupAvatarTransactionSerializationTests {
	@Test
	public void testSignatureAndClearFormsRoundTrip() throws TransformationException {
		byte[] owner = new byte[Transformer.PUBLIC_KEY_LENGTH]; Arrays.fill(owner, (byte) 1);
		byte[] avatar = new byte[Transformer.SIGNATURE_LENGTH]; Arrays.fill(avatar, (byte) 2);
		for (byte[] expectedAvatar : new byte[][] { avatar, null }) {
			SetGroupAvatarTransactionData original = new SetGroupAvatarTransactionData(
					new BaseTransactionData(1_700_000_000_000L, Group.NO_GROUP, owner, 100_000L, 7, new byte[Transformer.SIGNATURE_LENGTH]), 7, expectedAvatar);
			byte[] bytes = TransactionTransformer.toBytes(original);
			assertEquals(TransactionTransformer.getDataLength(original), bytes.length);
			TransactionData decoded = TransactionTransformer.fromBytes(bytes);
			SetGroupAvatarTransactionData result = (SetGroupAvatarTransactionData) decoded;
			if (expectedAvatar == null) assertNull(result.getAvatarSignature()); else assertArrayEquals(expectedAvatar, result.getAvatarSignature());
			assertArrayEquals(bytes, TransactionTransformer.toBytes(result));
		}
	}

	@Test
	public void testAccountAvatarSignatureAndClearFormsRoundTrip() throws TransformationException {
		byte[] owner = new byte[Transformer.PUBLIC_KEY_LENGTH]; Arrays.fill(owner, (byte) 3);
		byte[] avatar = new byte[Transformer.SIGNATURE_LENGTH]; Arrays.fill(avatar, (byte) 4);
		for (byte[] expectedAvatar : new byte[][] { avatar, null }) {
			SetAccountAvatarTransactionData original = new SetAccountAvatarTransactionData(
					new BaseTransactionData(1_700_000_000_000L, Group.NO_GROUP, owner, 100_000L, 7, new byte[Transformer.SIGNATURE_LENGTH]), expectedAvatar);
			byte[] bytes = TransactionTransformer.toBytes(original);
			assertEquals(TransactionTransformer.getDataLength(original), bytes.length);
			SetAccountAvatarTransactionData result = (SetAccountAvatarTransactionData) TransactionTransformer.fromBytes(bytes);
			if (expectedAvatar == null) assertNull(result.getAvatarSignature()); else assertArrayEquals(expectedAvatar, result.getAvatarSignature());
			assertArrayEquals(bytes, TransactionTransformer.toBytes(result));
		}
	}
}
