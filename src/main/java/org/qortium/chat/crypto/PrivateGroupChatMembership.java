package org.qortium.chat.crypto;

import org.qortium.account.PublicKeyAccount;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountData;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PrivateGroupChatMembership {

	private static final byte[] EPOCH_DOMAIN = "QPGC epoch v1".getBytes(StandardCharsets.US_ASCII);

	private static final Comparator<byte[]> PUBLIC_KEY_COMPARATOR = (left, right) -> {
		for (int i = 0; i < Math.min(left.length, right.length); ++i) {
			int comparison = Integer.compare(left[i] & 0xff, right[i] & 0xff);
			if (comparison != 0)
				return comparison;
		}

		return Integer.compare(left.length, right.length);
	};

	private PrivateGroupChatMembership() {
	}

	public static MembershipEpoch currentClosedGroupEpoch(Repository repository, int groupId) throws DataException {
		if (repository == null)
			throw new IllegalArgumentException("repository is missing");

		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		if (groupData == null)
			throw new IllegalArgumentException("group does not exist");

		if (groupData.isOpen())
			throw new IllegalArgumentException("private group chat epochs require a closed group");

		List<GroupMemberData> groupMembers = repository.getGroupRepository().getGroupMembers(groupId);
		if (groupMembers == null || groupMembers.isEmpty())
			throw new IllegalStateException("group has no members");

		List<byte[]> memberPublicKeys = new ArrayList<>(groupMembers.size());
		for (GroupMemberData groupMemberData : groupMembers)
			memberPublicKeys.add(loadMemberPublicKey(repository, groupMemberData));

		return new MembershipEpoch(groupId, memberPublicKeys);
	}

	public static byte[] computeEpochId(int groupId, List<byte[]> memberPublicKeys) {
		List<byte[]> sortedMemberPublicKeys = copySortedMemberPublicKeys(memberPublicKeys);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeBytes(bytes, EPOCH_DOMAIN);
		writeInt(bytes, groupId);
		writeInt(bytes, sortedMemberPublicKeys.size());

		for (byte[] memberPublicKey : sortedMemberPublicKeys)
			writeBytes(bytes, memberPublicKey);

		return Crypto.digest(bytes.toByteArray());
	}

	private static byte[] loadMemberPublicKey(Repository repository, GroupMemberData groupMemberData) throws DataException {
		String memberAddress = groupMemberData.getMember();
		AccountData accountData = repository.getAccountRepository().getAccount(memberAddress);

		if (accountData == null || !isUsablePublicKey(accountData.getPublicKey()))
			throw new IllegalStateException("group member has no known public key: " + memberAddress);

		byte[] publicKey = accountData.getPublicKey();
		if (!memberAddress.equals(Crypto.toAddress(publicKey)))
			throw new IllegalStateException("group member public key does not match address: " + memberAddress);

		return copy(publicKey);
	}

	private static List<byte[]> copySortedMemberPublicKeys(List<byte[]> memberPublicKeys) {
		if (memberPublicKeys == null)
			throw new IllegalArgumentException("member public keys are missing");

		if (memberPublicKeys.isEmpty())
			throw new IllegalArgumentException("member public keys are empty");

		List<byte[]> sortedMemberPublicKeys = new ArrayList<>(memberPublicKeys.size());
		for (byte[] memberPublicKey : memberPublicKeys) {
			validatePublicKey(memberPublicKey);
			sortedMemberPublicKeys.add(copy(memberPublicKey));
		}

		sortedMemberPublicKeys.sort(PUBLIC_KEY_COMPARATOR);
		return sortedMemberPublicKeys;
	}

	private static void validatePublicKey(byte[] publicKey) {
		if (!isUsablePublicKey(publicKey))
			throw new IllegalArgumentException("member public key is invalid");
	}

	private static boolean isUsablePublicKey(byte[] publicKey) {
		return publicKey != null
				&& publicKey.length == Transformer.PUBLIC_KEY_LENGTH
				&& !Arrays.equals(publicKey, PublicKeyAccount.ALL_ZEROS);
	}

	private static byte[] copy(byte[] bytes) {
		return Arrays.copyOf(bytes, bytes.length);
	}

	private static void writeInt(ByteArrayOutputStream bytes, int value) {
		bytes.write((value >>> 24) & 0xff);
		bytes.write((value >>> 16) & 0xff);
		bytes.write((value >>> 8) & 0xff);
		bytes.write(value & 0xff);
	}

	private static void writeBytes(ByteArrayOutputStream bytes, byte[] value) {
		bytes.write(value, 0, value.length);
	}

	public static class MembershipEpoch {
		private final int groupId;
		private final List<byte[]> memberPublicKeys;
		private final byte[] epochId;

		private MembershipEpoch(int groupId, List<byte[]> memberPublicKeys) {
			this.groupId = groupId;
			this.memberPublicKeys = copySortedMemberPublicKeys(memberPublicKeys);
			this.epochId = computeEpochId(groupId, this.memberPublicKeys);
		}

		public int getGroupId() {
			return this.groupId;
		}

		public List<byte[]> getMemberPublicKeys() {
			List<byte[]> copies = new ArrayList<>(this.memberPublicKeys.size());
			for (byte[] memberPublicKey : this.memberPublicKeys)
				copies.add(copy(memberPublicKey));

			return Collections.unmodifiableList(copies);
		}

		public byte[] getEpochId() {
			return copy(this.epochId);
		}
	}
}
