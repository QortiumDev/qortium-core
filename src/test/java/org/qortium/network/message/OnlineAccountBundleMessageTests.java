package org.qortium.network.message;

import org.junit.Test;
import org.qortium.crypto.Crypto;
import org.qortium.data.network.OnlineAccountBundleData;
import org.qortium.data.network.OnlineAccountBundleData.Member;
import org.qortium.network.message.GetOnlineAccountBundlesMessage.BundleIdentifier;
import org.qortium.transform.OnlineAccountBundleTransformer;
import org.qortium.transform.OnlineAccountBundleTransformer.ChainIdentity;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;
import org.qortium.test.common.Common;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class OnlineAccountBundleMessageTests extends Common {

	private static final ChainIdentity CHAIN_IDENTITY = new ChainIdentity("net", "genesis", "hash");

	@Test
	public void testResponseRoundTripAndEmptyRoundTrip() throws Exception {
		OnlineAccountBundleData bundle = signedBundle();
		OnlineAccountBundlesMessage outgoing = new OnlineAccountBundlesMessage(
				Collections.singletonList(bundle), CHAIN_IDENTITY);
		OnlineAccountBundlesMessage decoded = (OnlineAccountBundlesMessage)
				OnlineAccountBundlesMessage.fromByteBuffer(17, ByteBuffer.wrap(outgoing.dataBytes),
						CHAIN_IDENTITY);

		assertEquals(17, decoded.getId());
		assertEquals(1, decoded.getBundles().size());
		assertArrayEquals(bundle.getCommitmentHash(),
				decoded.getBundles().get(0).getCommitmentHash());

		OnlineAccountBundlesMessage empty =
				new OnlineAccountBundlesMessage(Collections.emptyList(), CHAIN_IDENTITY);
		OnlineAccountBundlesMessage emptyDecoded = (OnlineAccountBundlesMessage)
				OnlineAccountBundlesMessage.fromByteBuffer(18, ByteBuffer.wrap(empty.dataBytes),
						CHAIN_IDENTITY);
		assertEquals(0, emptyDecoded.getBundles().size());
	}

	@Test
	public void testFullMessageFramingRoundTripForResponseAndRequest() throws Exception {
		ChainIdentity currentIdentity = ChainIdentity.current();
		OnlineAccountBundleData bundle = signedBundle(currentIdentity);
		OnlineAccountBundlesMessage response =
				new OnlineAccountBundlesMessage(Collections.singletonList(bundle));
		Message decodedResponseMessage =
				Message.fromByteBuffer(ByteBuffer.wrap(response.toBytes()));
		OnlineAccountBundlesMessage decodedResponse =
				(OnlineAccountBundlesMessage) decodedResponseMessage;
		assertEquals(MessageType.ONLINE_ACCOUNT_BUNDLES, decodedResponse.getType());
		assertArrayEquals(bundle.getCommitmentHash(),
				decodedResponse.getBundles().get(0).getCommitmentHash());

		BundleIdentifier identifier = new BundleIdentifier(bundle.getTimestamp(),
				bundle.getNodePublicKey(), bundle.getCommitmentHash());
		GetOnlineAccountBundlesMessage request =
				new GetOnlineAccountBundlesMessage(Collections.singletonList(identifier));
		Message decodedRequestMessage =
				Message.fromByteBuffer(ByteBuffer.wrap(request.toBytes()));
		GetOnlineAccountBundlesMessage decodedRequest =
				(GetOnlineAccountBundlesMessage) decodedRequestMessage;
		assertEquals(MessageType.GET_ONLINE_ACCOUNT_BUNDLES, decodedRequest.getType());
		assertEquals(Collections.singletonList(identifier), decodedRequest.getKnownBundles());
	}

	@Test
	public void testResponseRejectsTruncatedTrailingAndOversizedPayloads() throws Exception {
		OnlineAccountBundlesMessage outgoing = new OnlineAccountBundlesMessage(
				Collections.singletonList(signedBundle()), CHAIN_IDENTITY);
		assertThrows(MessageException.class,
				() -> OnlineAccountBundlesMessage.fromByteBuffer(1,
						ByteBuffer.wrap(Arrays.copyOf(outgoing.dataBytes, outgoing.dataBytes.length - 1)),
						CHAIN_IDENTITY));
		assertThrows(MessageException.class,
				() -> OnlineAccountBundlesMessage.fromByteBuffer(1,
						ByteBuffer.wrap(Arrays.copyOf(outgoing.dataBytes, outgoing.dataBytes.length + 1)),
						CHAIN_IDENTITY));
		assertThrows(MessageException.class,
				() -> OnlineAccountBundlesMessage.fromByteBuffer(1,
						ByteBuffer.allocate(OnlineAccountBundleTransformer.MAX_GOSSIP_PAYLOAD_SIZE + 1),
						CHAIN_IDENTITY));
	}

	@Test
	public void testRequestRoundTripAndDefensiveCopies() throws MessageException {
		byte[] nodeKey = filled(Transformer.PUBLIC_KEY_LENGTH, 7);
		byte[] commitment = filled(Transformer.SHA256_LENGTH, 9);
		BundleIdentifier identifier = new BundleIdentifier(1234L, nodeKey, commitment);
		GetOnlineAccountBundlesMessage outgoing =
				new GetOnlineAccountBundlesMessage(Collections.singletonList(identifier));
		GetOnlineAccountBundlesMessage decoded = (GetOnlineAccountBundlesMessage)
				GetOnlineAccountBundlesMessage.fromByteBuffer(23, ByteBuffer.wrap(outgoing.dataBytes));

		assertEquals(23, decoded.getId());
		assertEquals(Collections.singletonList(identifier), decoded.getKnownBundles());
		nodeKey[0] ^= 1;
		commitment[0] ^= 1;
		assertArrayEquals(filled(Transformer.PUBLIC_KEY_LENGTH, 7),
				decoded.getKnownBundles().get(0).getNodePublicKey());
		assertArrayEquals(filled(Transformer.SHA256_LENGTH, 9),
				decoded.getKnownBundles().get(0).getCommitmentHash());
		assertThrows(UnsupportedOperationException.class,
				() -> decoded.getKnownBundles().add(identifier));
	}

	@Test
	public void testRequestRejectsInvalidCountsTruncationAndTrailingBytes() throws MessageException {
		assertThrows(MessageException.class,
				() -> GetOnlineAccountBundlesMessage.fromByteBuffer(1,
						ByteBuffer.allocate(4).putInt(-1).flip()));
		assertThrows(MessageException.class,
				() -> GetOnlineAccountBundlesMessage.fromByteBuffer(1,
						ByteBuffer.allocate(4)
								.putInt(GetOnlineAccountBundlesMessage.MAX_IDENTIFIERS + 1).flip()));
		assertThrows(MessageException.class,
				() -> GetOnlineAccountBundlesMessage.fromByteBuffer(1,
						ByteBuffer.allocate(4).putInt(1).flip()));

		GetOnlineAccountBundlesMessage empty =
				new GetOnlineAccountBundlesMessage(Collections.emptyList());
		byte[] trailing = Arrays.copyOf(empty.dataBytes, empty.dataBytes.length + 1);
		assertThrows(MessageException.class,
				() -> GetOnlineAccountBundlesMessage.fromByteBuffer(1, ByteBuffer.wrap(trailing)));
	}

	@Test
	public void testRequestOutgoingBoundsAndWireIds() {
		List<BundleIdentifier> tooMany = new ArrayList<>();
		for (int i = 0; i <= GetOnlineAccountBundlesMessage.MAX_IDENTIFIERS; ++i)
			tooMany.add(new BundleIdentifier(i, new byte[Transformer.PUBLIC_KEY_LENGTH],
					new byte[Transformer.SHA256_LENGTH]));

		assertThrows(MessageException.class, () -> new GetOnlineAccountBundlesMessage(tooMany));
		assertEquals(86, MessageType.ONLINE_ACCOUNT_BUNDLES.value);
		assertEquals(87, MessageType.GET_ONLINE_ACCOUNT_BUNDLES.value);
		assertEquals(MessageType.ONLINE_ACCOUNT_BUNDLES, MessageType.valueOf(86));
		assertEquals(MessageType.GET_ONLINE_ACCOUNT_BUNDLES, MessageType.valueOf(87));
	}

	private static OnlineAccountBundleData signedBundle()
			throws TransformationException {
		return signedBundle(CHAIN_IDENTITY);
	}

	private static OnlineAccountBundleData signedBundle(ChainIdentity chainIdentity)
			throws TransformationException {
		byte[] nodePrivateKey = filled(Transformer.PRIVATE_KEY_LENGTH, 1);
		byte[] memberPrivateKey = filled(Transformer.PRIVATE_KEY_LENGTH, 2);
		Member unsigned = new Member(Crypto.toPublicKey(memberPrivateKey), 42, null);
		byte[] commitment = OnlineAccountBundleTransformer.computeMemberCommitment(chainIdentity,
				OnlineAccountBundleTransformer.PROTOCOL_VERSION, 1234L,
				Crypto.toPublicKey(nodePrivateKey), Collections.singletonList(unsigned));
		Member signed = unsigned.withSignature(
				OnlineAccountBundleTransformer.signMember(memberPrivateKey, commitment));
		byte[] approval = OnlineAccountBundleTransformer.computeNodeApproval(commitment,
				Collections.singletonList(signed));
		byte[] nodeSignature = OnlineAccountBundleTransformer.signNode(nodePrivateKey, approval);
		return OnlineAccountBundleTransformer.createBundle(chainIdentity,
				OnlineAccountBundleTransformer.PROTOCOL_VERSION, 1234L,
				Crypto.toPublicKey(nodePrivateKey), Collections.singletonList(signed), nodeSignature);
	}

	private static byte[] filled(int length, int value) {
		byte[] bytes = new byte[length];
		Arrays.fill(bytes, (byte) value);
		return bytes;
	}
}
