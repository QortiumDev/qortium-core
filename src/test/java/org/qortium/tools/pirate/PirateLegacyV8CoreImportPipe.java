package org.qortium.tools.pirate;

import org.json.JSONArray;
import org.json.JSONObject;
import org.qortium.api.Security;
import org.qortium.utils.Base58;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Acceptance-only, one-shot bridge from a secret legacy-v8 candidate pipe to
 * a disposable packaged Core's loopback verified-import endpoint. Secret input
 * and HTTP bodies stay in memory; the retained result is redacted.
 */
public final class PirateLegacyV8CoreImportPipe {

	private static final int MAX_SECRET_BYTES = 64 * 1024;
	private static final int MAX_API_KEY_BYTES = 4096;
	private static final Pattern LOOPBACK_CORE = Pattern.compile("http://127\\.0\\.0\\.1:[0-9]{1,5}");
	private static final Pattern SAPLING_ADDRESS = Pattern.compile(
			"zs1[023456789acdefghjklmnpqrstuvwxyz]{75}");
	private static final Pattern SAPLING_SPENDING_KEY = Pattern.compile(
			"secret-extended-key-main1[023456789acdefghjklmnpqrstuvwxyz]+");

	private PirateLegacyV8CoreImportPipe() {
	}

	private static final class ImportFailure extends Exception {
		private final String code;

		private ImportFailure(String code) {
			super(code);
			this.code = code;
		}
	}

	private record Candidate(String pool, String spendingKey, String expectedAddress, int addressIndex,
			int birthdayHeight, String walletSha256) {
	}

	private record ImportResult(long keyId, String pool, String address, int addressIndex,
			int birthdayHeight, boolean alreadyImported, boolean rescanRequired,
			Long requiredRescanFromHeight) {
	}

	private static ImportFailure reject(String code) {
		return new ImportFailure(code);
	}

	private static Path requireRegularAbsolutePath(String value, String code) throws Exception {
		Path path = Path.of(value);
		if (!path.isAbsolute())
			throw reject(code);
		path = path.normalize();
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
			throw reject(code);
		return path;
	}

	private static byte[] readBounded(InputStream input, int maximum, String code) throws Exception {
		byte[] bytes = input.readNBytes(maximum + 1);
		if (bytes.length == 0 || bytes.length > maximum) {
			Arrays.fill(bytes, (byte) 0);
			throw reject(code);
		}
		return bytes;
	}

	private static String readBoundedFile(Path path, int maximum, String code) throws Exception {
		long size = Files.size(path);
		if (size < 1 || size > maximum)
			throw reject(code);
		byte[] bytes = Files.readAllBytes(path);
		if (bytes.length != size || Files.size(path) != size) {
			Arrays.fill(bytes, (byte) 0);
			throw reject(code);
		}
		String value = new String(bytes, StandardCharsets.UTF_8);
		Arrays.fill(bytes, (byte) 0);
		return value;
	}

	private static String readExactTrimmedFile(Path path, int maximum, String code) throws Exception {
		String value = readBoundedFile(path, maximum, code);
		String trimmed = value.trim();
		if (!value.equals(trimmed))
			throw reject(code);
		return value;
	}

	private static String requireString(JSONObject object, String key, String code) throws Exception {
		if (!object.has(key) || !(object.get(key) instanceof String value) || value.isBlank())
			throw reject(code);
		return value;
	}

	private static boolean requireBoolean(JSONObject object, String key, String code) throws Exception {
		if (!object.has(key) || !(object.get(key) instanceof Boolean))
			throw reject(code);
		return object.getBoolean(key);
	}

	private static long requireIntegral(JSONObject object, String key, long minimum, long maximum,
			String code) throws Exception {
		Object value = object.opt(key);
		if (!(value instanceof Integer) && !(value instanceof Long)
				&& !(value instanceof java.math.BigInteger))
			throw reject(code);
		long number;
		try {
			number = value instanceof java.math.BigInteger bigInteger
					? bigInteger.longValueExact() : ((Number) value).longValue();
		} catch (ArithmeticException e) {
			throw reject(code);
		}
		if (number < minimum || number > maximum)
			throw reject(code);
		return number;
	}

	private static Candidate parseCandidate(byte[] envelopeBytes) throws Exception {
		JSONObject envelope;
		try {
			envelope = new JSONObject(new String(envelopeBytes, StandardCharsets.UTF_8));
		} catch (RuntimeException e) {
			throw reject("candidate-envelope-invalid");
		} finally {
			Arrays.fill(envelopeBytes, (byte) 0);
		}
		if (!"qortium-pirate-legacy-v8-candidate-pipe-v1".equals(
				requireString(envelope, "format", "candidate-envelope-invalid")))
			throw reject("candidate-format-invalid");
		String walletSha256 = requireString(envelope, "walletSha256", "candidate-envelope-invalid");
		if (!walletSha256.matches("[0-9a-f]{64}"))
			throw reject("candidate-wallet-hash-invalid");
		int birthday = (int) requireIntegral(envelope, "birthdayHeight", 1L, Integer.MAX_VALUE,
				"candidate-envelope-invalid");
		JSONArray candidates = envelope.optJSONArray("candidates");
		if (candidates == null || candidates.length() != 1)
			throw reject("candidate-count-unsupported");
		JSONObject row = candidates.optJSONObject(0);
		if (row == null)
			throw reject("candidate-envelope-invalid");
		String pool = requireString(row, "pool", "candidate-envelope-invalid");
		String spendingKey = requireString(row, "spendingKey", "candidate-envelope-invalid");
		String address = requireString(row, "expectedAddress", "candidate-envelope-invalid");
		int index = (int) requireIntegral(row, "addressIndex", 0L, 4096L,
				"candidate-envelope-invalid");
		if (!"sapling".equals(pool) || !SAPLING_SPENDING_KEY.matcher(spendingKey).matches()
				|| !SAPLING_ADDRESS.matcher(address).matches() || index != 0)
			throw reject("candidate-row-invalid");
		return new Candidate(pool, spendingKey, address, index, birthday, walletSha256);
	}

	private static ImportResult post(HttpClient client, URI endpoint, String apiKey,
			String entropy58, Candidate candidate) throws Exception {
		JSONObject requestBody = new JSONObject()
				.put("entropy58", entropy58)
				.put("pool", candidate.pool())
				.put("spendingKey", candidate.spendingKey())
				.put("expectedAddress", candidate.expectedAddress())
				.put("addressIndex", candidate.addressIndex())
				.put("birthdayHeight", candidate.birthdayHeight());
		HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(Duration.ofSeconds(60))
				.header(Security.API_KEY_HEADER, apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
				.build();
		HttpResponse<InputStream> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw reject("core-import-interrupted");
		} catch (Exception e) {
			throw reject("core-import-unavailable");
		}
		byte[] responseBytes;
		try (InputStream body = response.body()) {
			responseBytes = readBounded(body, MAX_SECRET_BYTES, "core-response-invalid");
		}
		if (response.statusCode() != 200) {
			int apiError = -1;
			String failureClass = "unclassified";
			try {
				JSONObject apiFailure = new JSONObject(new String(responseBytes, StandardCharsets.UTF_8));
				apiError = apiFailure.optInt("error", -1);
				String message = apiFailure.optString("message", "");
				String lower = message.toLowerCase(java.util.Locale.ROOT);
					if ("Wallet isn't synchronized yet".equals(message))
						failureClass = "readiness";
					else if (lower.contains("chain tip"))
						failureClass = "chain-tip";
					else if (lower.contains("birthday"))
						failureClass = "birthday";
					else if (lower.contains("network"))
						failureClass = "network";
					else if (lower.contains("address") || message.contains(candidate.expectedAddress()))
						failureClass = "ownership-or-address";
					else if (lower.contains("wallet secret"))
						failureClass = "wallet-secret";
				else if (lower.contains("verified key import"))
					failureClass = "native-import";
				else if (lower.contains("identity"))
					failureClass = "wallet-identity";
			} catch (RuntimeException ignored) {
				// The numeric status and sentinel still provide a secret-free failure class.
			}
			Arrays.fill(responseBytes, (byte) 0);
			throw reject("core-import-rejected-http-" + response.statusCode() + "-api-" + apiError
					+ "-class-" + failureClass);
		}
		JSONObject result;
		try {
			result = new JSONObject(new String(responseBytes, StandardCharsets.UTF_8));
		} catch (RuntimeException e) {
			throw reject("core-response-invalid");
		} finally {
			Arrays.fill(responseBytes, (byte) 0);
		}
		Long requiredFloor = null;
		if (result.has("requiredRescanFromHeight") && !result.isNull("requiredRescanFromHeight"))
			requiredFloor = requireIntegral(result, "requiredRescanFromHeight", 1L,
					Integer.MAX_VALUE, "core-response-invalid");
		return new ImportResult(
				requireIntegral(result, "keyId", Long.MIN_VALUE, Long.MAX_VALUE,
						"core-response-invalid"),
				requireString(result, "pool", "core-response-invalid"),
				requireString(result, "address", "core-response-invalid"),
				(int) requireIntegral(result, "addressIndex", 0L, 4096L,
						"core-response-invalid"),
				(int) requireIntegral(result, "birthdayHeight", 1L, Integer.MAX_VALUE,
						"core-response-invalid"),
				requireBoolean(result, "alreadyImported", "core-response-invalid"),
				requireBoolean(result, "rescanRequired", "core-response-invalid"),
				requiredFloor);
	}

	private static void requireIdentity(ImportResult result, Candidate candidate) throws Exception {
		if (!candidate.pool().equals(result.pool())
				|| !candidate.expectedAddress().equals(result.address())
				|| candidate.addressIndex() != result.addressIndex()
				|| candidate.birthdayHeight() != result.birthdayHeight())
			throw reject("core-import-identity-mismatch");
	}

	private static void writeResult(Path path, JSONObject result) throws Exception {
		if (!path.isAbsolute() || Files.exists(path, LinkOption.NOFOLLOW_LINKS))
			throw reject("result-path-invalid");
		path = path.normalize();
		Files.createFile(path, PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rw-------")));
		Files.writeString(path, result.toString() + System.lineSeparator(),
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	private static String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	public static void main(String[] args) {
		if (args.length != 5) {
			System.err.println("[error] usage-invalid");
			System.exit(2);
		}
		try {
			if (!LOOPBACK_CORE.matcher(args[0]).matches())
				throw reject("core-url-invalid");
			int separator = args[0].lastIndexOf(':');
			int port;
			try {
				port = Integer.parseInt(args[0].substring(separator + 1));
			} catch (RuntimeException e) {
				throw reject("core-url-invalid");
			}
			if (port < 1 || port > 65_535)
				throw reject("core-url-invalid");
			URI endpoint = URI.create(args[0] + "/crosschain/arrr/recovery/import");
			Path targetFixture = requireRegularAbsolutePath(args[1], "target-fixture-invalid");
			Path apiKeyPath = requireRegularAbsolutePath(args[2], "api-key-invalid");
			String mode = args[3];
			if (!"first".equals(mode) && !"pending".equals(mode) && !"completed".equals(mode))
				throw reject("mode-invalid");
			Path resultPath = Path.of(args[4]);

			JSONObject target = new JSONObject(readBoundedFile(targetFixture, MAX_SECRET_BYTES,
					"target-fixture-invalid"));
			String entropy58 = requireString(target, "entropy58", "target-fixture-invalid");
			byte[] entropy;
			try {
				entropy = Base58.decode(entropy58);
			} catch (RuntimeException e) {
				throw reject("target-fixture-invalid");
			}
			if (entropy.length != 32) {
				Arrays.fill(entropy, (byte) 0);
				throw reject("target-fixture-invalid");
			}
			Arrays.fill(entropy, (byte) 0);

			String apiKey = readExactTrimmedFile(apiKeyPath, MAX_API_KEY_BYTES, "api-key-invalid");
			if (apiKey.isEmpty() || apiKey.indexOf('\n') >= 0 || apiKey.indexOf('\r') >= 0)
				throw reject("api-key-invalid");
			Candidate candidate = parseCandidate(readBounded(System.in, MAX_SECRET_BYTES,
					"candidate-envelope-invalid"));
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(10))
					.followRedirects(HttpClient.Redirect.NEVER)
					.build();

			ImportResult first = post(client, endpoint, apiKey, entropy58, candidate);
			requireIdentity(first, candidate);
			JSONObject redacted = new JSONObject()
					.put("format", "qortium-pirate-legacy-v8-core-import-v1")
					.put("mode", mode)
					.put("walletSha256", candidate.walletSha256())
					.put("candidateAddressSha256", sha256(candidate.expectedAddress()))
					.put("birthdayHeight", candidate.birthdayHeight())
					.put("keyIdSha256", sha256(Long.toString(first.keyId())));
			if ("first".equals(mode)) {
				if (first.alreadyImported() || !first.rescanRequired()
						|| !Long.valueOf(candidate.birthdayHeight()).equals(first.requiredRescanFromHeight()))
					throw reject("first-import-contract-failed");
				redacted.put("firstAlreadyImported", false)
						.put("rescanRequired", true)
						.put("requiredRescanFromHeight", first.requiredRescanFromHeight());
			} else if ("pending".equals(mode)) {
				if (!first.alreadyImported() || !first.rescanRequired()
						|| !Long.valueOf(candidate.birthdayHeight()).equals(first.requiredRescanFromHeight()))
					throw reject("pending-retry-contract-failed");
				redacted.put("pendingRetryAlreadyImported", true)
						.put("rescanRequired", true)
						.put("requiredRescanFromHeight", first.requiredRescanFromHeight());
			} else {
				if (!first.alreadyImported() || first.rescanRequired()
						|| first.requiredRescanFromHeight() != null)
					throw reject("completed-retry-contract-failed");
				redacted.put("completedRetryAlreadyImported", true)
						.put("rescanRequired", false);
			}
			writeResult(resultPath, redacted);
			System.out.println("[ok] candidate passed the disposable Core verified-import contract");
		} catch (ImportFailure e) {
			System.err.println("[error] " + e.code);
			System.exit(1);
		} catch (Throwable e) {
			System.err.println("[error] unexpected-core-import-pipe-failure");
			System.exit(1);
		}
	}
}
