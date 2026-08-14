package org.qortium.transform;

import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.network.OnlineAccountBundleData;
import org.qortium.data.network.OnlineAccountBundleData.Member;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Canonical version-1 encoding and signing inputs for online-account reward bundles. */
public final class OnlineAccountBundleTransformer extends Transformer {

	public static final int PROTOCOL_VERSION = 1;

	public static final int MAX_MEMBERS_PER_BUNDLE = 1024;
	public static final int MAX_BUNDLES_PER_COHORT = 1024;
	public static final int MAX_BLOCK_MEMBER_OCCURRENCES = 8192;
	public static final int MAX_BLOCK_PAYLOAD_SIZE = 1024 * 1024;
	public static final int MAX_GOSSIP_MEMBER_OCCURRENCES = 16384;
	public static final int MAX_GOSSIP_PAYLOAD_SIZE = 2 * 1024 * 1024;

	public static final Limits BLOCK_LIMITS = new Limits(MAX_BUNDLES_PER_COHORT,
			MAX_BLOCK_MEMBER_OCCURRENCES, MAX_BLOCK_PAYLOAD_SIZE);
	public static final Limits GOSSIP_LIMITS = new Limits(MAX_BUNDLES_PER_COHORT,
			MAX_GOSSIP_MEMBER_OCCURRENCES, MAX_GOSSIP_PAYLOAD_SIZE);

	private static final String MEMBER_DOMAIN = "QORTIUM_ONLINE_NODE_REWARD_BUNDLE";
	private static final String NODE_DOMAIN = "QORTIUM_ONLINE_NODE_REWARD_BUNDLE_NODE";

	private static final int BUNDLE_HEADER_LENGTH = INT_LENGTH + TIMESTAMP_LENGTH + PUBLIC_KEY_LENGTH + INT_LENGTH;
	private static final int MEMBER_LENGTH = PUBLIC_KEY_LENGTH + INT_LENGTH + SIGNATURE_LENGTH;
	private static final int BUNDLE_TRAILER_LENGTH = SIGNATURE_LENGTH;
	private static final int MIN_BUNDLE_LENGTH = BUNDLE_HEADER_LENGTH + MEMBER_LENGTH + BUNDLE_TRAILER_LENGTH;
	private static final int MEMBER_COUNT_OFFSET = INT_LENGTH + TIMESTAMP_LENGTH + PUBLIC_KEY_LENGTH;

	private OnlineAccountBundleTransformer() {
	}

	public static List<Member> canonicalizeMembers(List<Member> members) throws TransformationException {
		if (members == null)
			throw new TransformationException("Bundle members are missing");
		if (members.isEmpty() || members.size() > MAX_MEMBERS_PER_BUNDLE)
			throw new TransformationException("Invalid bundle member count: " + members.size());

		List<Member> canonical = new ArrayList<>(members.size());
		for (Member member : members) {
			if (member == null)
				throw new TransformationException("Bundle member is missing");
			validatePublicKey(member.getPublicKey(), "member public key");
			if (member.getNonce() < 0)
				throw new TransformationException("Bundle member nonce is negative");
			canonical.add(new Member(member));
		}

		canonical.sort(OnlineAccountBundleData.UNSIGNED_PUBLIC_KEY_COMPARATOR);
		for (int i = 1; i < canonical.size(); ++i) {
			if (OnlineAccountBundleData.compareUnsigned(canonical.get(i - 1).getPublicKey(),
					canonical.get(i).getPublicKey()) == 0)
				throw new TransformationException("Duplicate bundle member public key");
		}

		return Collections.unmodifiableList(canonical);
	}

	public static byte[] computeMemberCommitment(ChainIdentity chainIdentity, int protocolVersion,
			long timestamp, byte[] nodePublicKey, List<Member> members) throws TransformationException {
		validateProtocolVersion(protocolVersion);
		Objects.requireNonNull(chainIdentity, "chainIdentity");
		validatePublicKey(nodePublicKey, "reward-node public key");
		List<Member> canonicalMembers = canonicalizeMembers(members);

		try {
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
			DataOutputStream bytes = new DataOutputStream(byteStream);
			writeText(bytes, MEMBER_DOMAIN);
			bytes.writeInt(protocolVersion);
			writeText(bytes, chainIdentity.networkId);
			writeText(bytes, chainIdentity.genesisSignature);
			writeText(bytes, chainIdentity.chainConfigHash);
			bytes.writeLong(timestamp);
			bytes.write(nodePublicKey);
			bytes.writeInt(canonicalMembers.size());
			for (Member member : canonicalMembers) {
				bytes.write(member.getPublicKey());
				bytes.writeInt(member.getNonce());
			}

			return Crypto.digest(byteStream.toByteArray());
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with byte-array streams", e);
		}
	}

	public static byte[] computeNodeApproval(byte[] commitmentHash, List<Member> members)
			throws TransformationException {
		validateHash(commitmentHash, "member commitment");
		List<Member> canonicalMembers = canonicalizeMembers(members);

		try {
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
			DataOutputStream bytes = new DataOutputStream(byteStream);
			writeText(bytes, NODE_DOMAIN);
			bytes.write(commitmentHash);
			for (Member member : canonicalMembers) {
				validateSignature(member.getSignature(), "member signature");
				bytes.write(member.getSignature());
			}

			return Crypto.digest(byteStream.toByteArray());
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with byte-array streams", e);
		}
	}

	public static byte[] signMember(byte[] memberPrivateKey, byte[] commitmentHash)
			throws TransformationException {
		validatePrivateKey(memberPrivateKey);
		validateHash(commitmentHash, "member commitment");
		return Crypto.sign(memberPrivateKey, commitmentHash);
	}

	public static boolean verifyMemberSignature(Member member, byte[] commitmentHash) {
		if (member == null || commitmentHash == null || commitmentHash.length != SHA256_LENGTH)
			return false;
		return Crypto.verify(member.getPublicKey(), member.getSignature(), commitmentHash);
	}

	public static byte[] signNode(byte[] nodePrivateKey, byte[] nodeApprovalHash)
			throws TransformationException {
		validatePrivateKey(nodePrivateKey);
		validateHash(nodeApprovalHash, "node approval");
		return Crypto.sign(nodePrivateKey, nodeApprovalHash);
	}

	public static boolean verifyNodeSignature(byte[] nodePublicKey, byte[] nodeSignature,
			byte[] nodeApprovalHash) {
		return Crypto.verify(nodePublicKey, nodeSignature, nodeApprovalHash);
	}

	/** Build a complete canonical bundle and derive (rather than trust) its commitment hash. */
	public static OnlineAccountBundleData createBundle(ChainIdentity chainIdentity, int protocolVersion,
			long timestamp, byte[] nodePublicKey, List<Member> members, byte[] nodeSignature)
			throws TransformationException {
		List<Member> canonicalMembers = canonicalizeMembers(members);
		for (Member member : canonicalMembers)
			validateSignature(member.getSignature(), "member signature");
		validateSignature(nodeSignature, "reward-node signature");
		byte[] commitmentHash = computeMemberCommitment(chainIdentity, protocolVersion, timestamp,
				nodePublicKey, canonicalMembers);
		return new OnlineAccountBundleData(protocolVersion, timestamp, nodePublicKey, canonicalMembers,
				nodeSignature, commitmentHash);
	}

	/** Verify canonical structure, the locally reconstructed commitment, and every Ed25519 signature. */
	public static boolean verifySignatures(OnlineAccountBundleData bundle, ChainIdentity chainIdentity)
			throws TransformationException {
		validateBundle(bundle, chainIdentity);
		byte[] commitmentHash = bundle.getCommitmentHash();
		for (Member member : bundle.getMembers()) {
			if (!verifyMemberSignature(member, commitmentHash))
				return false;
		}

		byte[] nodeApproval = computeNodeApproval(commitmentHash, bundle.getMembers());
		return verifyNodeSignature(bundle.getNodePublicKey(), bundle.getNodeSignature(), nodeApproval);
	}

	public static void validateBundle(OnlineAccountBundleData bundle, ChainIdentity chainIdentity)
			throws TransformationException {
		if (bundle == null)
			throw new TransformationException("Bundle is missing");
		validateProtocolVersion(bundle.getProtocolVersion());
		Objects.requireNonNull(chainIdentity, "chainIdentity");
		validatePublicKey(bundle.getNodePublicKey(), "reward-node public key");
		validateSignature(bundle.getNodeSignature(), "reward-node signature");

		List<Member> canonicalMembers = requireCanonicalMembers(bundle.getMembers());
		for (Member member : canonicalMembers)
			validateSignature(member.getSignature(), "member signature");

		byte[] expectedCommitment = computeMemberCommitment(chainIdentity, bundle.getProtocolVersion(),
				bundle.getTimestamp(), bundle.getNodePublicKey(), canonicalMembers);
		validateHash(bundle.getCommitmentHash(), "member commitment");
		if (!Arrays.equals(expectedCommitment, bundle.getCommitmentHash()))
			throw new TransformationException("Bundle commitment does not match canonical fields");
	}

	public static byte[] toBytes(OnlineAccountBundleData bundle, ChainIdentity chainIdentity)
			throws TransformationException {
		validateBundle(bundle, chainIdentity);
		List<Member> members = bundle.getMembers();
		int byteSize = BUNDLE_HEADER_LENGTH + members.size() * MEMBER_LENGTH + BUNDLE_TRAILER_LENGTH;

		try {
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream(byteSize);
			DataOutputStream bytes = new DataOutputStream(byteStream);
			bytes.writeInt(bundle.getProtocolVersion());
			bytes.writeLong(bundle.getTimestamp());
			bytes.write(bundle.getNodePublicKey());
			bytes.writeInt(members.size());
			for (Member member : members) {
				bytes.write(member.getPublicKey());
				bytes.writeInt(member.getNonce());
				bytes.write(member.getSignature());
			}
			bytes.write(bundle.getNodeSignature());

			return byteStream.toByteArray();
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with byte-array streams", e);
		}
	}

	public static byte[] toBytes(OnlineAccountBundleData bundle) throws TransformationException {
		return toBytes(bundle, ChainIdentity.current());
	}

	public static OnlineAccountBundleData fromBytes(byte[] bytes, ChainIdentity chainIdentity)
			throws TransformationException {
		if (bytes == null || bytes.length < MIN_BUNDLE_LENGTH)
			throw new TransformationException("Canonical bundle is truncated");

		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		int protocolVersion = buffer.getInt();
		validateProtocolVersion(protocolVersion);
		long timestamp = buffer.getLong();
		byte[] nodePublicKey = readBytes(buffer, PUBLIC_KEY_LENGTH);
		int memberCount = buffer.getInt();
		validateMemberCount(memberCount);

		long expectedLength = (long) BUNDLE_HEADER_LENGTH + (long) memberCount * MEMBER_LENGTH
				+ BUNDLE_TRAILER_LENGTH;
		if (expectedLength != bytes.length)
			throw new TransformationException(expectedLength > bytes.length
					? "Canonical bundle is truncated" : "Canonical bundle has trailing bytes");

		List<Member> members = new ArrayList<>(memberCount);
		for (int i = 0; i < memberCount; ++i) {
			byte[] publicKey = readBytes(buffer, PUBLIC_KEY_LENGTH);
			int nonce = buffer.getInt();
			byte[] signature = readBytes(buffer, SIGNATURE_LENGTH);
			members.add(new Member(publicKey, nonce, signature));
		}
		byte[] nodeSignature = readBytes(buffer, SIGNATURE_LENGTH);

		List<Member> canonicalMembers = requireCanonicalMembers(members);
		byte[] commitmentHash = computeMemberCommitment(chainIdentity, protocolVersion, timestamp,
				nodePublicKey, canonicalMembers);
		return new OnlineAccountBundleData(protocolVersion, timestamp,
				nodePublicKey, canonicalMembers, nodeSignature, commitmentHash);
	}

	public static OnlineAccountBundleData fromBytes(byte[] bytes) throws TransformationException {
		return fromBytes(bytes, ChainIdentity.current());
	}

	/** Encode the shared count/length-prefixed cohort used by block-v2 storage and gossip. */
	public static byte[] toCohortBytes(List<OnlineAccountBundleData> bundles, Limits limits,
			ChainIdentity chainIdentity) throws TransformationException {
		if (bundles == null)
			throw new TransformationException("Bundle cohort is missing");
		Objects.requireNonNull(limits, "limits");
		if (bundles.size() > limits.maxBundles)
			throw new TransformationException("Too many bundles in cohort: " + bundles.size());

		try {
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
			DataOutputStream bytes = new DataOutputStream(byteStream);
			bytes.writeInt(bundles.size());
			long memberOccurrences = 0;
			for (OnlineAccountBundleData bundle : bundles) {
				memberOccurrences += bundle == null ? 0 : bundle.getMembers().size();
				if (memberOccurrences > limits.maxMemberOccurrences)
					throw new TransformationException("Too many member occurrences in cohort");

				byte[] bundleBytes = toBytes(bundle, chainIdentity);
				long prospectiveSize = (long) byteStream.size() + INT_LENGTH + bundleBytes.length;
				if (prospectiveSize > limits.maxPayloadBytes)
					throw new TransformationException("Bundle cohort payload exceeds byte limit");
				bytes.writeInt(bundleBytes.length);
				bytes.write(bundleBytes);
			}

			if (byteStream.size() > limits.maxPayloadBytes)
				throw new TransformationException("Bundle cohort payload exceeds byte limit");
			return byteStream.toByteArray();
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with byte-array streams", e);
		}
	}

	public static byte[] toBlockCohortBytes(List<OnlineAccountBundleData> bundles,
			ChainIdentity chainIdentity) throws TransformationException {
		return toCohortBytes(bundles, BLOCK_LIMITS, chainIdentity);
	}

	public static byte[] toGossipCohortBytes(List<OnlineAccountBundleData> bundles,
			ChainIdentity chainIdentity) throws TransformationException {
		return toCohortBytes(bundles, GOSSIP_LIMITS, chainIdentity);
	}

	public static List<OnlineAccountBundleData> fromCohortBytes(byte[] payload, Limits limits,
			ChainIdentity chainIdentity) throws TransformationException {
		if (payload == null || payload.length < INT_LENGTH)
			throw new TransformationException("Bundle cohort is truncated");
		Objects.requireNonNull(limits, "limits");
		Objects.requireNonNull(chainIdentity, "chainIdentity");
		if (payload.length > limits.maxPayloadBytes)
			throw new TransformationException("Bundle cohort payload exceeds byte limit");

		ByteBuffer bytes = ByteBuffer.wrap(payload);
		int bundleCount = bytes.getInt();
		if (bundleCount < 0 || bundleCount > limits.maxBundles)
			throw new TransformationException("Invalid bundle cohort count: " + bundleCount);
		if ((long) bundleCount * (INT_LENGTH + MIN_BUNDLE_LENGTH) > bytes.remaining())
			throw new TransformationException("Bundle cohort is truncated");

		// First pass validates every nested length/count and the aggregate member-work cap before
		// allocating member lists or hashing any attacker-controlled bundle.
		ByteBuffer preflight = bytes.duplicate();
		long memberOccurrences = 0;
		for (int i = 0; i < bundleCount; ++i) {
			if (preflight.remaining() < INT_LENGTH)
				throw new TransformationException("Bundle cohort is truncated before bundle length");
			int bundleLength = preflight.getInt();
			if (bundleLength < MIN_BUNDLE_LENGTH || bundleLength > preflight.remaining())
				throw new TransformationException("Invalid or truncated canonical bundle length: " + bundleLength);

			int memberCount = preflight.getInt(preflight.position() + MEMBER_COUNT_OFFSET);
			validateMemberCount(memberCount);
			long expectedLength = (long) BUNDLE_HEADER_LENGTH + (long) memberCount * MEMBER_LENGTH
					+ BUNDLE_TRAILER_LENGTH;
			if (expectedLength != bundleLength)
				throw new TransformationException("Canonical bundle length does not match member count");
			memberOccurrences += memberCount;
			if (memberOccurrences > limits.maxMemberOccurrences)
				throw new TransformationException("Too many member occurrences in cohort");
			preflight.position(preflight.position() + bundleLength);
		}
		if (preflight.hasRemaining())
			throw new TransformationException("Bundle cohort has trailing bytes");

		List<OnlineAccountBundleData> bundles = new ArrayList<>(bundleCount);
		for (int i = 0; i < bundleCount; ++i) {
			int bundleLength = bytes.getInt();
			byte[] bundleBytes = readBytes(bytes, bundleLength);
			bundles.add(fromBytes(bundleBytes, chainIdentity));
		}
		return Collections.unmodifiableList(bundles);
	}

	public static List<OnlineAccountBundleData> fromBlockCohortBytes(byte[] payload,
			ChainIdentity chainIdentity) throws TransformationException {
		return fromCohortBytes(payload, BLOCK_LIMITS, chainIdentity);
	}

	public static List<OnlineAccountBundleData> fromGossipCohortBytes(byte[] payload,
			ChainIdentity chainIdentity) throws TransformationException {
		return fromCohortBytes(payload, GOSSIP_LIMITS, chainIdentity);
	}

	private static List<Member> requireCanonicalMembers(List<Member> members) throws TransformationException {
		List<Member> canonical = canonicalizeMembers(members);
		for (int i = 0; i < members.size(); ++i) {
			if (OnlineAccountBundleData.compareUnsigned(members.get(i).getPublicKey(),
					canonical.get(i).getPublicKey()) != 0)
				throw new TransformationException("Bundle members are not in canonical unsigned order");
		}
		return canonical;
	}

	private static void validateProtocolVersion(int protocolVersion) throws TransformationException {
		if (protocolVersion != PROTOCOL_VERSION)
			throw new TransformationException("Unsupported online-account bundle version: " + protocolVersion);
	}

	private static void validateMemberCount(int memberCount) throws TransformationException {
		if (memberCount <= 0 || memberCount > MAX_MEMBERS_PER_BUNDLE)
			throw new TransformationException("Invalid bundle member count: " + memberCount);
	}

	private static void validatePublicKey(byte[] publicKey, String label) throws TransformationException {
		if (publicKey == null || publicKey.length != PUBLIC_KEY_LENGTH)
			throw new TransformationException("Invalid " + label + " length");
	}

	private static void validatePrivateKey(byte[] privateKey) throws TransformationException {
		if (privateKey == null || privateKey.length != PRIVATE_KEY_LENGTH)
			throw new TransformationException("Invalid private key length");
	}

	private static void validateSignature(byte[] signature, String label) throws TransformationException {
		if (signature == null || signature.length != SIGNATURE_LENGTH)
			throw new TransformationException("Invalid " + label + " length");
	}

	private static void validateHash(byte[] hash, String label) throws TransformationException {
		if (hash == null || hash.length != SHA256_LENGTH)
			throw new TransformationException("Invalid " + label + " length");
	}

	private static byte[] readBytes(ByteBuffer bytes, int length) throws TransformationException {
		if (length < 0 || length > bytes.remaining())
			throw new TransformationException("Canonical bundle is truncated");
		byte[] result = new byte[length];
		bytes.get(result);
		return result;
	}

	private static void writeText(DataOutputStream bytes, String text) throws IOException,
			TransformationException {
		if (text == null)
			throw new TransformationException("Chain identity text is missing");
		byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
		bytes.writeInt(utf8.length);
		bytes.write(utf8);
	}

	public static final class Limits {

		private final int maxBundles;
		private final int maxMemberOccurrences;
		private final int maxPayloadBytes;

		public Limits(int maxBundles, int maxMemberOccurrences, int maxPayloadBytes) {
			if (maxBundles < 0 || maxMemberOccurrences < 0 || maxPayloadBytes < INT_LENGTH)
				throw new IllegalArgumentException("Invalid online-account bundle limits");
			this.maxBundles = maxBundles;
			this.maxMemberOccurrences = maxMemberOccurrences;
			this.maxPayloadBytes = maxPayloadBytes;
		}

		public int getMaxBundles() {
			return this.maxBundles;
		}

		public int getMaxMemberOccurrences() {
			return this.maxMemberOccurrences;
		}

		public int getMaxPayloadBytes() {
			return this.maxPayloadBytes;
		}
	}

	public static final class ChainIdentity {

		private final String networkId;
		private final String genesisSignature;
		private final String chainConfigHash;

		public ChainIdentity(String networkId, String genesisSignature, String chainConfigHash) {
			this.networkId = Objects.requireNonNull(networkId, "networkId");
			this.genesisSignature = Objects.requireNonNull(genesisSignature, "genesisSignature");
			this.chainConfigHash = Objects.requireNonNull(chainConfigHash, "chainConfigHash");
		}

		public static ChainIdentity current() {
			BlockChain blockChain = BlockChain.getInstance();
			return new ChainIdentity(blockChain.getNetworkId(), blockChain.getGenesisSignature(),
					blockChain.getChainConfigHash());
		}

		public String getNetworkId() {
			return this.networkId;
		}

		public String getGenesisSignature() {
			return this.genesisSignature;
		}

		public String getChainConfigHash() {
			return this.chainConfigHash;
		}
	}
}
