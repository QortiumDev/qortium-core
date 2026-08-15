package org.qortium.transform;

import org.junit.Test;
import org.qortium.crypto.Crypto;
import org.qortium.data.network.OnlineAccountBundleData;
import org.qortium.data.network.OnlineAccountBundleData.Member;
import org.qortium.transform.OnlineAccountBundleTransformer.ChainIdentity;
import org.qortium.transform.OnlineAccountBundleTransformer.Limits;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OnlineAccountBundleTransformerTests {

	private static final ChainIdentity CHAIN_IDENTITY =
			new ChainIdentity("preview", "genesis", "config");
	private static final long TIMESTAMP = 0x0102030405060708L;

	@Test
	public void testGoldenCanonicalVectorAndSignatures() throws TransformationException {
		OnlineAccountBundleData bundle = signedBundle();
		byte[] encoded = OnlineAccountBundleTransformer.toBytes(bundle, CHAIN_IDENTITY);

		assertEquals("21b3891126a6d4e238358614ed69c2bc7538c1fa475acd6630679c54746e919c",
				toHex(bundle.getCommitmentHash()));
		assertEquals("0000000101020304050607088a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c000000028139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b3941122334405d08ada7032686b054a3df88d2e1928ba18d7729ad9e46d0c943de165f7c78800fc9852841214a0bc4bb69449ff73fc3700c0123954567f22584139d740e00bed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d15566778837363023f9e71aff6af0fa4fd7991a996686a5bbc07adeaa38e9f74e3df96c80f4bbba81a71ae80fad9148e785e3434f0c4ed714031500a273b668b4d8c77b05923d6293aad8ec4dd10828cd8736867594c008c4196f4329c8e62e256e511472c76948f559366cee6c6bd9bbaf4c1ff0214da42da7bf1ba763d5e7dfb2148700",
				toHex(encoded));
		assertTrue(OnlineAccountBundleTransformer.verifySignatures(bundle, CHAIN_IDENTITY));
	}

	@Test
	public void testCanonicalRoundTripAndDefensiveCopies() throws TransformationException {
		OnlineAccountBundleData original = signedBundle();
		byte[] encoded = OnlineAccountBundleTransformer.toBytes(original, CHAIN_IDENTITY);
		OnlineAccountBundleData decoded =
				OnlineAccountBundleTransformer.fromBytes(encoded, CHAIN_IDENTITY);

		assertEquals(original.getProtocolVersion(), decoded.getProtocolVersion());
		assertEquals(original.getTimestamp(), decoded.getTimestamp());
		assertArrayEquals(original.getNodePublicKey(), decoded.getNodePublicKey());
		assertArrayEquals(original.getCommitmentHash(), decoded.getCommitmentHash());
		assertArrayEquals(original.getNodeSignature(), decoded.getNodeSignature());
		assertEquals(original.getMembers(), decoded.getMembers());

		byte[] returnedNodeKey = decoded.getNodePublicKey();
		returnedNodeKey[0] ^= 1;
		assertNotEquals(returnedNodeKey[0], decoded.getNodePublicKey()[0]);
		byte[] returnedMemberKey = decoded.getMembers().get(0).getPublicKey();
		returnedMemberKey[0] ^= 1;
		assertNotEquals(returnedMemberKey[0], decoded.getMembers().get(0).getPublicKey()[0]);
		assertThrows(UnsupportedOperationException.class,
				() -> decoded.getMembers().add(decoded.getMembers().get(0)));
	}

	@Test
	public void testUnsignedCanonicalOrderingAndDuplicateRejection() throws TransformationException {
		byte[] highKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
		highKey[0] = (byte) 0xff;
		byte[] lowKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
		lowKey[0] = 0;
		lowKey[1] = 1;

		List<Member> sorted = OnlineAccountBundleTransformer.canonicalizeMembers(Arrays.asList(
				new Member(highKey, 1, null), new Member(lowKey, 2, null)));
		assertArrayEquals(lowKey, sorted.get(0).getPublicKey());
		assertArrayEquals(highKey, sorted.get(1).getPublicKey());

		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.canonicalizeMembers(Arrays.asList(
						new Member(lowKey, 1, null), new Member(lowKey, 2, null))));
	}

	@Test
	public void testDecoderRejectsUnsortedDuplicateTrailingAndTruncatedBundles()
			throws TransformationException {
		byte[] canonical = OnlineAccountBundleTransformer.toBytes(signedBundle(), CHAIN_IDENTITY);
		int firstMemberOffset = Transformer.INT_LENGTH + Transformer.TIMESTAMP_LENGTH
				+ Transformer.PUBLIC_KEY_LENGTH + Transformer.INT_LENGTH;
		int memberLength = Transformer.PUBLIC_KEY_LENGTH + Transformer.INT_LENGTH
				+ Transformer.SIGNATURE_LENGTH;

		byte[] unsorted = Arrays.copyOf(canonical, canonical.length);
		byte[] first = Arrays.copyOfRange(unsorted, firstMemberOffset, firstMemberOffset + memberLength);
		System.arraycopy(unsorted, firstMemberOffset + memberLength, unsorted, firstMemberOffset, memberLength);
		System.arraycopy(first, 0, unsorted, firstMemberOffset + memberLength, memberLength);
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromBytes(unsorted, CHAIN_IDENTITY));

		byte[] duplicate = Arrays.copyOf(canonical, canonical.length);
		System.arraycopy(duplicate, firstMemberOffset, duplicate, firstMemberOffset + memberLength,
				Transformer.PUBLIC_KEY_LENGTH);
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromBytes(duplicate, CHAIN_IDENTITY));
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromBytes(
						Arrays.copyOf(canonical, canonical.length - 1), CHAIN_IDENTITY));
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromBytes(
						Arrays.copyOf(canonical, canonical.length + 1), CHAIN_IDENTITY));
	}

	@Test
	public void testSignatureVerificationBindsFieldsAndChainIdentity() throws TransformationException {
		OnlineAccountBundleData bundle = signedBundle();
		byte[] encoded = OnlineAccountBundleTransformer.toBytes(bundle, CHAIN_IDENTITY);
		encoded[Transformer.INT_LENGTH + Transformer.TIMESTAMP_LENGTH] ^= 1;
		OnlineAccountBundleData mutated = OnlineAccountBundleTransformer.fromBytes(encoded, CHAIN_IDENTITY);

		assertFalse(OnlineAccountBundleTransformer.verifySignatures(mutated, CHAIN_IDENTITY));
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.validateBundle(bundle,
						new ChainIdentity("other", "genesis", "config")));
	}

	@Test
	public void testLegacyTimestampSignatureCannotAuthorizeBundleMembership()
			throws TransformationException {
		byte[] otherNodePrivateKey = filled(Transformer.PRIVATE_KEY_LENGTH, 11);
		byte[] memberPrivateKey = filled(Transformer.PRIVATE_KEY_LENGTH, 12);
		byte[] memberPublicKey = Crypto.toPublicKey(memberPrivateKey);
		byte[] legacyTimestampBytes = ByteBuffer.allocate(Long.BYTES).putLong(TIMESTAMP).array();
		byte[] legacyTimestampSignature = Crypto.sign(memberPrivateKey, legacyTimestampBytes);
		assertTrue(Crypto.verify(memberPublicKey, legacyTimestampSignature, legacyTimestampBytes));

		Member legacyMember = new Member(memberPublicKey, 99, legacyTimestampSignature);
		byte[] commitment = OnlineAccountBundleTransformer.computeMemberCommitment(CHAIN_IDENTITY,
				OnlineAccountBundleTransformer.PROTOCOL_VERSION, TIMESTAMP,
				Crypto.toPublicKey(otherNodePrivateKey), Collections.singletonList(legacyMember));
		byte[] nodeApproval = OnlineAccountBundleTransformer.computeNodeApproval(commitment,
				Collections.singletonList(legacyMember));
		byte[] nodeSignature =
				OnlineAccountBundleTransformer.signNode(otherNodePrivateKey, nodeApproval);
		OnlineAccountBundleData regroupedBundle = OnlineAccountBundleTransformer.createBundle(
				CHAIN_IDENTITY, OnlineAccountBundleTransformer.PROTOCOL_VERSION, TIMESTAMP,
				Crypto.toPublicKey(otherNodePrivateKey), Collections.singletonList(legacyMember),
				nodeSignature);

		assertFalse(OnlineAccountBundleTransformer.verifySignatures(regroupedBundle, CHAIN_IDENTITY));
	}

	@Test
	public void testCohortRoundTripAndAggregateLimits() throws TransformationException {
		OnlineAccountBundleData bundle = signedBundle();
		byte[] encoded = OnlineAccountBundleTransformer.toGossipCohortBytes(
				Collections.singletonList(bundle), CHAIN_IDENTITY);
		List<OnlineAccountBundleData> decoded =
				OnlineAccountBundleTransformer.fromGossipCohortBytes(encoded, CHAIN_IDENTITY);

		assertEquals(1, decoded.size());
		assertArrayEquals(bundle.getCommitmentHash(), decoded.get(0).getCommitmentHash());
		assertThrows(UnsupportedOperationException.class, () -> decoded.add(bundle));

		Limits oneMemberOnly = new Limits(1, 1, 4096);
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.toCohortBytes(
						Collections.singletonList(bundle), oneMemberOnly, CHAIN_IDENTITY));
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromCohortBytes(
						encoded, oneMemberOnly, CHAIN_IDENTITY));
		Limits tooSmall = new Limits(1, 2, Transformer.INT_LENGTH);
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.toCohortBytes(
						Collections.singletonList(bundle), tooSmall, CHAIN_IDENTITY));
	}

	@Test
	public void testCohortRejectsCountsLengthsTruncationAndTrailingBytes()
			throws TransformationException {
		byte[] valid = OnlineAccountBundleTransformer.toGossipCohortBytes(
				Collections.singletonList(signedBundle()), CHAIN_IDENTITY);
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromGossipCohortBytes(
						Arrays.copyOf(valid, valid.length - 1), CHAIN_IDENTITY));
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromGossipCohortBytes(
						Arrays.copyOf(valid, valid.length + 1), CHAIN_IDENTITY));
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromGossipCohortBytes(
						ByteBuffer.allocate(4).putInt(-1).array(), CHAIN_IDENTITY));
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromGossipCohortBytes(
						ByteBuffer.allocate(4).putInt(
								OnlineAccountBundleTransformer.MAX_BUNDLES_PER_COHORT + 1).array(),
						CHAIN_IDENTITY));
		assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromGossipCohortBytes(
						ByteBuffer.allocate(8).putInt(1).putInt(Integer.MAX_VALUE).array(),
						CHAIN_IDENTITY));
	}

	@Test
	public void testAggregateWorkCapIsCheckedBeforeBundleHashing()
			throws TransformationException {
		OnlineAccountBundleData bundle = signedBundle();
		byte[] twoBundles = OnlineAccountBundleTransformer.toCohortBytes(
				Arrays.asList(bundle, bundle), new Limits(2, 4, 4096), CHAIN_IDENTITY);
		// Corrupt the first bundle's version. A single-pass decoder would reject this before it ever
		// discovered that the later declared member pushes the aggregate over its work cap.
		ByteBuffer.wrap(twoBundles).putInt(2 * Transformer.INT_LENGTH, 99);

		TransformationException exception = assertThrows(TransformationException.class,
				() -> OnlineAccountBundleTransformer.fromCohortBytes(twoBundles,
						new Limits(2, 1, 4096), CHAIN_IDENTITY));
		assertTrue(exception.getMessage().contains("Too many member occurrences"));
	}

	private static OnlineAccountBundleData signedBundle() throws TransformationException {
		byte[] nodePrivateKey = filled(Transformer.PRIVATE_KEY_LENGTH, 1);
		byte[] memberPrivateKeyA = filled(Transformer.PRIVATE_KEY_LENGTH, 2);
		byte[] memberPrivateKeyB = filled(Transformer.PRIVATE_KEY_LENGTH, 3);

		List<KeyedMember> keyedMembers = Arrays.asList(
				new KeyedMember(memberPrivateKeyB, 0x55667788),
				new KeyedMember(memberPrivateKeyA, 0x11223344));
		List<Member> unsignedMembers = new ArrayList<>();
		for (KeyedMember keyedMember : keyedMembers)
			unsignedMembers.add(new Member(Crypto.toPublicKey(keyedMember.privateKey),
					keyedMember.nonce, null));
		List<Member> canonicalUnsigned =
				OnlineAccountBundleTransformer.canonicalizeMembers(unsignedMembers);
		byte[] commitment = OnlineAccountBundleTransformer.computeMemberCommitment(CHAIN_IDENTITY,
				OnlineAccountBundleTransformer.PROTOCOL_VERSION, TIMESTAMP,
				Crypto.toPublicKey(nodePrivateKey), canonicalUnsigned);

		List<Member> signedMembers = new ArrayList<>();
		for (Member member : canonicalUnsigned) {
			byte[] matchingPrivateKey = Arrays.equals(member.getPublicKey(),
					Crypto.toPublicKey(memberPrivateKeyA)) ? memberPrivateKeyA : memberPrivateKeyB;
			signedMembers.add(member.withSignature(
					OnlineAccountBundleTransformer.signMember(matchingPrivateKey, commitment)));
		}
		byte[] nodeApproval =
				OnlineAccountBundleTransformer.computeNodeApproval(commitment, signedMembers);
		byte[] nodeSignature = OnlineAccountBundleTransformer.signNode(nodePrivateKey, nodeApproval);

		return OnlineAccountBundleTransformer.createBundle(CHAIN_IDENTITY,
				OnlineAccountBundleTransformer.PROTOCOL_VERSION, TIMESTAMP,
				Crypto.toPublicKey(nodePrivateKey), signedMembers, nodeSignature);
	}

	private static byte[] filled(int length, int value) {
		byte[] bytes = new byte[length];
		Arrays.fill(bytes, (byte) value);
		return bytes;
	}

	private static String toHex(byte[] bytes) {
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte value : bytes)
			result.append(String.format("%02x", value));
		return result.toString();
	}

	private static final class KeyedMember {
		private final byte[] privateKey;
		private final int nonce;

		private KeyedMember(byte[] privateKey, int nonce) {
			this.privateKey = privateKey;
			this.nonce = nonce;
		}
	}
}
