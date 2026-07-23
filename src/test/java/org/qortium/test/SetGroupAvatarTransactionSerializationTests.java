package org.qortium.test;

import org.junit.Test;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.avatar.AvatarData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SetGroupAvatarTransactionData;
import org.qortium.data.transaction.SetAccountAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;
import org.qortium.transform.transaction.TransactionTransformer;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class SetGroupAvatarTransactionSerializationTests {

	private static void assertAvatarEquals(AvatarData expected, AvatarData actual) {
		if (expected == null) {
			assertNull(actual);
			return;
		}
		assertEquals(expected.getService(), actual.getService());
		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getIdentifier() == null ? "" : expected.getIdentifier(), actual.getIdentifier());
	}

	// A set pointer, a pointer with the default (empty) identifier, and the clear form.
	private static final AvatarData[] AVATARS = new AvatarData[] {
			new AvatarData(Service.THUMBNAIL, "owner-name", "custom-id"),
			new AvatarData(Service.IMAGE, "another-name", ""),
			new AvatarData(Service.IMAGE, "null-id-name", null),
			null
	};

	@Test
	public void testGroupAvatarPointerAndClearFormsRoundTrip() throws TransformationException {
		byte[] owner = new byte[Transformer.PUBLIC_KEY_LENGTH]; Arrays.fill(owner, (byte) 1);
		for (AvatarData expectedAvatar : AVATARS) {
			SetGroupAvatarTransactionData original = new SetGroupAvatarTransactionData(
					new BaseTransactionData(1_700_000_000_000L, Group.NO_GROUP, owner, 100_000L, 7, new byte[Transformer.SIGNATURE_LENGTH]), 7, expectedAvatar);
			byte[] bytes = TransactionTransformer.toBytes(original);
			assertEquals(TransactionTransformer.getDataLength(original), bytes.length);
			SetGroupAvatarTransactionData result = (SetGroupAvatarTransactionData) TransactionTransformer.fromBytes(bytes);
			assertAvatarEquals(expectedAvatar, result.getAvatar());
			assertArrayEquals(bytes, TransactionTransformer.toBytes(result));
		}
	}

	@Test
	public void testAccountAvatarPointerAndClearFormsRoundTrip() throws TransformationException {
		byte[] owner = new byte[Transformer.PUBLIC_KEY_LENGTH]; Arrays.fill(owner, (byte) 3);
		for (AvatarData expectedAvatar : AVATARS) {
			SetAccountAvatarTransactionData original = new SetAccountAvatarTransactionData(
					new BaseTransactionData(1_700_000_000_000L, Group.NO_GROUP, owner, 100_000L, 7, new byte[Transformer.SIGNATURE_LENGTH]), expectedAvatar);
			byte[] bytes = TransactionTransformer.toBytes(original);
			assertEquals(TransactionTransformer.getDataLength(original), bytes.length);
			SetAccountAvatarTransactionData result = (SetAccountAvatarTransactionData) TransactionTransformer.fromBytes(bytes);
			assertAvatarEquals(expectedAvatar, result.getAvatar());
			assertArrayEquals(bytes, TransactionTransformer.toBytes(result));
		}
	}

	@Test
	public void testNullAndEmptyIdentifiersHaveIdenticalSignedBytes() throws TransformationException {
		byte[] owner = new byte[Transformer.PUBLIC_KEY_LENGTH];
		BaseTransactionData base = new BaseTransactionData(1_700_000_000_000L, Group.NO_GROUP, owner, 100_000L, 7,
				new byte[Transformer.SIGNATURE_LENGTH]);
		byte[] nullIdentifier = TransactionTransformer.toBytes(
				new SetAccountAvatarTransactionData(base, new AvatarData(Service.IMAGE, "owner-name", null)));
		byte[] emptyIdentifier = TransactionTransformer.toBytes(
				new SetAccountAvatarTransactionData(base, new AvatarData(Service.IMAGE, "owner-name", "")));
		assertArrayEquals(nullIdentifier, emptyIdentifier);
	}

	@Test
	public void testUnknownAvatarServicesAreRejectedDuringTransformation() throws TransformationException {
		byte[] owner = new byte[Transformer.PUBLIC_KEY_LENGTH];

		SetAccountAvatarTransactionData account = new SetAccountAvatarTransactionData(
				new BaseTransactionData(1_700_000_000_000L, Group.NO_GROUP, owner, 100_000L, 7,
						new byte[Transformer.SIGNATURE_LENGTH]),
				new AvatarData(Service.IMAGE, "owner-name", ""));
		byte[] accountBytes = TransactionTransformer.toBytes(account);
		ByteBuffer.wrap(accountBytes).putInt(53, Integer.MAX_VALUE);
		assertThrows(TransformationException.class, () -> TransactionTransformer.fromBytes(accountBytes));

		SetGroupAvatarTransactionData group = new SetGroupAvatarTransactionData(
				new BaseTransactionData(1_700_000_000_000L, Group.NO_GROUP, owner, 100_000L, 7,
						new byte[Transformer.SIGNATURE_LENGTH]),
				7, new AvatarData(Service.IMAGE, "owner-name", ""));
		byte[] groupBytes = TransactionTransformer.toBytes(group);
		ByteBuffer.wrap(groupBytes).putInt(57, Integer.MAX_VALUE);
		assertThrows(TransformationException.class, () -> TransactionTransformer.fromBytes(groupBytes));
	}
}
