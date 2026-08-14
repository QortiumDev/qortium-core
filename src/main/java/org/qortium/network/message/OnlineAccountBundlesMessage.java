package org.qortium.network.message;

import org.qortium.data.network.OnlineAccountBundleData;
import org.qortium.transform.OnlineAccountBundleTransformer;
import org.qortium.transform.OnlineAccountBundleTransformer.ChainIdentity;
import org.qortium.transform.TransformationException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Relays complete, origin-independent online-account reward bundles. */
public class OnlineAccountBundlesMessage extends Message {

	private final List<OnlineAccountBundleData> bundles;

	public OnlineAccountBundlesMessage(List<OnlineAccountBundleData> bundles) throws MessageException {
		this(bundles, ChainIdentity.current());
	}

	public OnlineAccountBundlesMessage(List<OnlineAccountBundleData> bundles, ChainIdentity chainIdentity)
			throws MessageException {
		super(MessageType.ONLINE_ACCOUNT_BUNDLES);
		try {
			this.dataBytes = OnlineAccountBundleTransformer.toGossipCohortBytes(bundles, chainIdentity);
		} catch (TransformationException | RuntimeException e) {
			throw new MessageException("Unable to encode online-account bundles", e);
		}
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
		this.bundles = immutableBundleCopies(bundles);
	}

	private OnlineAccountBundlesMessage(int id, List<OnlineAccountBundleData> bundles) {
		super(id, MessageType.ONLINE_ACCOUNT_BUNDLES);
		this.bundles = immutableBundleCopies(bundles);
	}

	public List<OnlineAccountBundleData> getBundles() {
		return immutableBundleCopies(this.bundles);
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		return fromByteBuffer(id, bytes, ChainIdentity.current());
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes, ChainIdentity chainIdentity)
			throws MessageException {
		if (bytes.remaining() > OnlineAccountBundleTransformer.MAX_GOSSIP_PAYLOAD_SIZE)
			throw new MessageException("Online-account bundle payload exceeds gossip byte limit");

		byte[] payload = new byte[bytes.remaining()];
		bytes.get(payload);
		try {
			List<OnlineAccountBundleData> bundles =
					OnlineAccountBundleTransformer.fromGossipCohortBytes(payload, chainIdentity);
			return new OnlineAccountBundlesMessage(id, bundles);
		} catch (TransformationException | RuntimeException e) {
			throw new MessageException("Invalid online-account bundles payload", e);
		}
	}

	private static List<OnlineAccountBundleData> immutableBundleCopies(
			List<OnlineAccountBundleData> bundles) {
		if (bundles == null)
			return Collections.emptyList();
		List<OnlineAccountBundleData> copies = new ArrayList<>(bundles.size());
		for (OnlineAccountBundleData bundle : bundles) {
			if (bundle == null) {
				copies.add(null);
				continue;
			}
			copies.add(new OnlineAccountBundleData(bundle.getProtocolVersion(), bundle.getTimestamp(),
					bundle.getNodePublicKey(), bundle.getMembers(), bundle.getNodeSignature(),
					bundle.getCommitmentHash()));
		}
		return Collections.unmodifiableList(copies);
	}
}
