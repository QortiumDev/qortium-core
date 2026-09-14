package org.qortium.tools.pirate;

import com.rust.litewalletjni.LiteWalletJni;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Inspects one copied Pirate Lite/Qortal serialization-v8 wallet through the
 * exact published legacy JNI library. The result contains hashes and counts,
 * never wallet secrets or addresses. One JVM must inspect exactly one file.
 */
public final class PirateLegacyV8Inspector {

	private static final long MAX_WALLET_BYTES = 256L * 1024L * 1024L;
	private static final int MAX_PASSWORD_BYTES = 4096;
	private static final int MAX_CANDIDATES = 16;
	private static final int MAX_CANDIDATE_ENVELOPE_BYTES = 64 * 1024;
	private static final Pattern SAPLING_ADDRESS = Pattern.compile(
			"zs1[023456789acdefghjklmnpqrstuvwxyz]{75}");
	private static final Pattern SAPLING_SPENDING_KEY = Pattern.compile(
			"secret-extended-key-main1[023456789acdefghjklmnpqrstuvwxyz]+");
	private static final Pattern SAPLING_VIEWING_KEY = Pattern.compile(
			"zxviews1[023456789acdefghjklmnpqrstuvwxyz]+");

	private PirateLegacyV8Inspector() {
	}

	private static final class InspectionFailure extends Exception {
		private final String code;
		private final int exitCode;

		private InspectionFailure(String code, int exitCode) {
			super(code);
			this.code = code;
			this.exitCode = exitCode;
		}
	}

	private record Candidate(String spendingKey, String address, String addressHash) {
	}

	private record Inspection(JSONObject redacted, JSONObject candidateEnvelope) {
	}

	private static int parseDescriptor(String value, String code) throws Exception {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw reject(code);
		}
	}

	private static InspectionFailure reject(String code) {
		return new InspectionFailure(code, 1);
	}

	private static String sha256(byte[] value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
	}

	private static String sha256(String value) throws Exception {
		return sha256(value.getBytes(StandardCharsets.UTF_8));
	}

	private static Path requireRegularAbsolutePath(String value, String code) throws Exception {
		Path supplied = Path.of(value);
		if (!supplied.isAbsolute())
			throw reject(code);
		Path normalized = supplied.normalize();
		if (Files.isSymbolicLink(normalized)
				|| !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS))
			throw reject(code);
		return normalized;
	}

	private static String requireNativeSuccess(String response, String code) throws Exception {
		if (response == null)
			throw reject(code);
		JSONObject parsed;
		try {
			parsed = new JSONObject(response);
		} catch (RuntimeException e) {
			throw reject(code);
		}
		if (!"success".equals(parsed.optString("result")))
			throw reject(code);
		return response;
	}

	private static boolean requireBoolean(JSONObject object, String key, String code) throws Exception {
		if (!object.has(key) || !(object.get(key) instanceof Boolean))
			throw reject(code);
		return object.getBoolean(key);
	}

	private static void requireInitialized(String response) throws Exception {
		JSONObject parsed;
		try {
			parsed = new JSONObject(response);
		} catch (RuntimeException e) {
			throw reject("wallet-load-failed");
		}
		boolean hasCorrectKey = parsed.has("initialized");
		boolean hasLegacyKey = parsed.has("initalized");
		if (hasCorrectKey == hasLegacyKey)
			throw reject("wallet-load-failed");
		String key = hasCorrectKey ? "initialized" : "initalized";
		if (!requireBoolean(parsed, key, "wallet-load-failed"))
			throw reject("wallet-load-failed");
	}

	private static void requireUnlockSuccess(String response) throws Exception {
		JSONObject parsed;
		try {
			parsed = new JSONObject(response);
		} catch (RuntimeException e) {
			throw reject("unlock-response-invalid");
		}
		if (!parsed.has("result") || !(parsed.get("result") instanceof String))
			throw reject("unlock-response-invalid");
		String result = parsed.getString("result");
		if ("success".equals(result))
			return;
		if ("error".equals(result)
				&& parsed.has("error")
				&& parsed.get("error") instanceof String
				&& parsed.getString("error").contains("Decryption failed"))
			throw new InspectionFailure("password-rejected", 3);
		throw reject("unlock-failed");
	}

	private static char[] readPassword(int descriptor) throws Exception {
		if (descriptor < 3)
			throw reject("password-required");
		Path descriptorPath = Path.of("/proc/self/fd", Integer.toString(descriptor));
		byte[] buffer = new byte[MAX_PASSWORD_BYTES + 1];
		int length = 0;
		try (InputStream input = Files.newInputStream(descriptorPath)) {
			while (length < buffer.length) {
				int read = input.read(buffer, length, buffer.length - length);
				if (read < 0)
					break;
				length += read;
			}
		}
		if (length > MAX_PASSWORD_BYTES) {
			java.util.Arrays.fill(buffer, (byte) 0);
			throw reject("password-too-large");
		}
		if (length == 0)
			throw reject("password-required");
		for (int i = 0; i < length; i++)
			if (buffer[i] == 0)
				throw reject("password-invalid");
		String decoded = new String(buffer, 0, length, StandardCharsets.UTF_8);
		java.util.Arrays.fill(buffer, (byte) 0);
		return decoded.toCharArray();
	}

	private static void writeResult(Path resultPath, JSONObject result) throws Exception {
		if (Files.exists(resultPath, LinkOption.NOFOLLOW_LINKS))
			throw reject("result-exists");
		Files.createFile(resultPath, PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rw-------")));
		Files.writeString(resultPath, result.toString() + System.lineSeparator(),
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	private static void writeCandidateEnvelope(int descriptor, JSONObject envelope) throws Exception {
		if (descriptor < 3)
			throw reject("candidate-fd-invalid");
		byte[] encoded = envelope.toString().getBytes(StandardCharsets.UTF_8);
		if (encoded.length > MAX_CANDIDATE_ENVELOPE_BYTES) {
			java.util.Arrays.fill(encoded, (byte) 0);
			throw reject("candidate-envelope-too-large");
		}
		try (OutputStream output = new FileOutputStream(
				Path.of("/proc/self/fd", Integer.toString(descriptor)).toFile())) {
			output.write(encoded);
			output.write('\n');
		} catch (java.io.IOException e) {
			throw reject("candidate-write-failed");
		} finally {
			java.util.Arrays.fill(encoded, (byte) 0);
		}
	}

	private static Inspection inspect(String[] args) throws Exception {
		Path library = requireRegularAbsolutePath(args[0], "library-invalid");
		Path coinParams = requireRegularAbsolutePath(args[1], "coinparams-invalid");
		Path saplingOutput = requireRegularAbsolutePath(args[2], "sapling-output-invalid");
		Path saplingSpend = requireRegularAbsolutePath(args[3], "sapling-spend-invalid");
		Path wallet = requireRegularAbsolutePath(args[4], "wallet-invalid");
		String serverUri = args[5];
		if (!serverUri.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}"))
			throw reject("server-not-loopback");
		int passwordDescriptor = parseDescriptor(args[6], "password-fd-invalid");

		long size = Files.size(wallet);
		if (size < Long.BYTES || size > MAX_WALLET_BYTES)
			throw reject("wallet-size-invalid");
		byte[] walletBytes = Files.readAllBytes(wallet);
		if (walletBytes.length != size || Files.size(wallet) != size)
			throw reject("wallet-changed-during-read");
		String beforeHash = sha256(walletBytes);
		long version = ByteBuffer.wrap(walletBytes, 0, Long.BYTES)
				.order(ByteOrder.LITTLE_ENDIAN).getLong();
		if (version != 8L)
			throw reject("wallet-version-unsupported");

		LiteWalletJni.loadLibrary(library);
		if (!LiteWalletJni.isLoaded())
			throw reject("legacy-library-load-failed");
		String initialized;
		try {
			initialized = LiteWalletJni.initfromb64(serverUri,
					Files.readString(coinParams), Base64.getEncoder().encodeToString(walletBytes),
					Files.readString(saplingOutput).trim(), Files.readString(saplingSpend).trim());
		} finally {
			java.util.Arrays.fill(walletBytes, (byte) 0);
		}
		requireInitialized(initialized);
		initialized = null;

		JSONObject encryption;
		try {
			encryption = new JSONObject(LiteWalletJni.execute("encryptionstatus", ""));
		} catch (RuntimeException e) {
			throw reject("encryption-status-invalid");
		}
		boolean encrypted = requireBoolean(encryption, "encrypted", "encryption-status-invalid");
		boolean locked = requireBoolean(encryption, "locked", "encryption-status-invalid");
		if (encrypted != locked)
			throw reject("encryption-state-invalid");

		char[] password = null;
		if (encrypted) {
			password = readPassword(passwordDescriptor);
			String passwordString = new String(password);
			String unlock = LiteWalletJni.execute("unlock", passwordString);
			passwordString = null;
			java.util.Arrays.fill(password, '\0');
			password = null;
			try {
				requireUnlockSuccess(unlock);
			} finally {
				unlock = null;
			}
		} else if (passwordDescriptor >= 0) {
			throw reject("password-not-expected");
		}

		String seedResponse = LiteWalletJni.execute("seed", "");
		long birthday;
		try {
			JSONObject seed = new JSONObject(seedResponse);
			if (!seed.has("seed") || !seed.has("birthday"))
				throw reject("birthday-unavailable");
			birthday = seed.getLong("birthday");
			seed.remove("seed");
			seed.clear();
		} catch (InspectionFailure e) {
			throw e;
		} catch (RuntimeException e) {
			throw reject("birthday-unavailable");
		} finally {
			seedResponse = null;
		}
		if (birthday < 1 || birthday > Integer.MAX_VALUE)
			throw reject("birthday-invalid");

		String addressesResponse = LiteWalletJni.execute("addresses", "");
		String exportResponse = LiteWalletJni.execute("export", "");
		JSONObject addresses;
		JSONArray exported;
		try {
			addresses = new JSONObject(addressesResponse);
			exported = new JSONArray(exportResponse);
		} catch (RuntimeException e) {
			throw reject("legacy-export-invalid");
		} finally {
			addressesResponse = null;
			exportResponse = null;
		}

		JSONArray shieldedAddresses = addresses.optJSONArray("z_addresses");
		JSONArray transparentAddresses = addresses.optJSONArray("t_addresses");
		if (shieldedAddresses == null || transparentAddresses == null
				|| transparentAddresses.length() != 0)
			throw reject("unsupported-transparent-key");

		Set<String> addressHashes = new HashSet<>();
		for (int i = 0; i < shieldedAddresses.length(); i++) {
			String address = shieldedAddresses.optString(i, "");
			if (!SAPLING_ADDRESS.matcher(address).matches())
				throw reject("shielded-address-invalid");
			addressHashes.add(sha256(address));
		}

		Map<String, Candidate> candidates = new HashMap<>();
		for (int i = 0; i < exported.length(); i++) {
			JSONObject row = exported.optJSONObject(i);
			if (row == null || !row.has("viewing_key"))
				throw reject("unsupported-transparent-key");
			String address = row.optString("address", "");
			String spendingKey = row.optString("private_key", "");
			String viewingKey = row.optString("viewing_key", "");
			if (spendingKey.isEmpty())
				throw reject("viewing-key-only");
			if (!SAPLING_ADDRESS.matcher(address).matches()
					|| !SAPLING_SPENDING_KEY.matcher(spendingKey).matches()
					|| !SAPLING_VIEWING_KEY.matcher(viewingKey).matches())
				throw reject("sapling-export-invalid");
			String addressHash = sha256(address);
			if (!addressHashes.contains(addressHash))
				throw reject("export-address-not-in-wallet");
			String keyHash = sha256(spendingKey);
			Candidate previous = candidates.putIfAbsent(keyHash,
					new Candidate(spendingKey, address, addressHash));
			if (previous != null)
				throw reject("ambiguous-v8-address-group");
			row.remove("address");
			row.remove("private_key");
			row.remove("viewing_key");
		}
		addresses.clear();
		exported.clear();
		if (candidates.isEmpty() || candidates.size() > MAX_CANDIDATES
				|| candidates.size() != addressHashes.size())
			throw reject("candidate-set-incomplete");

		if (encrypted) {
			String lock = LiteWalletJni.execute("lock", "");
			requireNativeSuccess(lock, "wallet-relock-failed");
			lock = null;
		}

		String afterHash = sha256(Files.readAllBytes(wallet));
		if (!beforeHash.equals(afterHash))
			throw reject("wallet-copy-changed");

		List<String> candidateAddressHashes = new ArrayList<>();
		for (Candidate candidate : candidates.values())
			candidateAddressHashes.add(candidate.addressHash());
		candidateAddressHashes.sort(String::compareTo);
		JSONObject result = new JSONObject();
		result.put("format", "qortium-pirate-legacy-v8-inspection-v1");
		result.put("serializedVersion", version);
		result.put("walletBytes", size);
		result.put("walletSha256", beforeHash);
		result.put("sourceUnchanged", true);
		result.put("network", "mainnet");
		result.put("encrypted", encrypted);
		result.put("birthdayHeight", birthday);
		result.put("birthdaySource", "legacy-v8-wallet");
		result.put("pool", "sapling");
		result.put("suggestedAddressIndex", 0);
		result.put("selectionBasis", "legacy-v8-default-row");
		result.put("candidateCount", candidates.size());
		result.put("candidateAddressSha256", new JSONArray(candidateAddressHashes));

		List<Candidate> orderedCandidates = new ArrayList<>(candidates.values());
		orderedCandidates.sort(java.util.Comparator.comparing(Candidate::address));
		JSONArray candidateRows = new JSONArray();
		for (Candidate candidate : orderedCandidates) {
			candidateRows.put(new JSONObject()
					.put("pool", "sapling")
					.put("spendingKey", candidate.spendingKey())
					.put("expectedAddress", candidate.address())
					.put("addressIndex", 0));
		}
		JSONObject envelope = new JSONObject()
				.put("format", "qortium-pirate-legacy-v8-candidate-pipe-v1")
				.put("walletSha256", beforeHash)
				.put("birthdayHeight", birthday)
				.put("candidates", candidateRows);
		return new Inspection(result, envelope);
	}

	public static void main(String[] args) {
		if (args.length != 8 && args.length != 9) {
			System.err.println("[error] usage-invalid");
			System.exit(2);
		}
		try {
			Path result = Path.of(args[7]);
			if (!result.isAbsolute())
				throw reject("result-path-invalid");
			Integer candidateDescriptor = args.length == 9
					? parseDescriptor(args[8], "candidate-fd-invalid") : null;
			int passwordDescriptor = parseDescriptor(args[6], "password-fd-invalid");
			if (candidateDescriptor != null
					&& (candidateDescriptor < 3 || candidateDescriptor == passwordDescriptor))
				throw reject("candidate-fd-invalid");
			Inspection inspection = inspect(args);
			writeResult(result.normalize(), inspection.redacted());
			if (candidateDescriptor != null)
				writeCandidateEnvelope(candidateDescriptor, inspection.candidateEnvelope());
			System.out.println("[ok] one legacy v8 wallet inspected; result is redacted");
		} catch (InspectionFailure e) {
			System.err.println("[error] " + e.code);
			System.exit(e.exitCode);
		} catch (Throwable e) {
			System.err.println("[error] unexpected-inspection-failure");
			System.exit(1);
		}
	}
}
