package org.qortium.controller;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Test;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.ArbitraryDataTransactionBuilder;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.arbitrary.ArbitraryDataCacheManager;
import org.qortium.crypto.Crypto;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.transaction.Transaction;
import org.qortium.utils.ArbitraryTransactionUtils;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Opt-in preparation of a disposable, local-only QDN fixture for packaged Core acceptance.
 *
 * <p>The repository row carries a deterministic placeholder signature. It is saved directly and is never signed,
 * imported, broadcast, confirmed, or minted. The resulting fixture must not be represented as a QDN publication.</p>
 */
public class PirateUnifiedLocalQdnFixtureTests extends Common {

	private static final String RUN_PROPERTY = "qortium.preparePirateUnifiedLocalQdnFixture";
	private static final String BUNDLE_PATH_PROPERTY = "qortium.pirateUnifiedBundlePath";
	private static final String FIXTURE_PATH_PROPERTY = "qortium.pirateUnifiedQdnFixturePath";
	private static final String NAME = "pirate-unified-local-fixture";
	private static final String IDENTIFIER = "wallet-library";
	private static final String REQUIRED_LIBRARY = "librust-linux-x86_64.so";

	@Test
	public void prepareUnsignedLocalQdnFixture() throws Exception {
		assumeTrue("Set -D" + RUN_PROPERTY + "=true to prepare a disposable local-QDN fixture",
				Boolean.getBoolean(RUN_PROPERTY));

		Path bundle = requiredAbsoluteDirectory(BUNDLE_PATH_PROPERTY);
		Path fixture = requiredAbsoluteDirectory(FIXTURE_PATH_PROPERTY);
		try (var children = Files.list(fixture)) {
			assertEquals("Fixture directory must be empty", 0L, children.count());
		}
		PirateUnifiedWalletBundle.validate(bundle, REQUIRED_LIBRARY);

		configureFixturePaths(fixture);
		Common.setShouldRetainRepositoryAfterTest(true);
		RepositoryManager.closeRepositoryFactory();
		Common.setRepository(false);
		NTP.setFixedOffset(0L);
		Common.resetBlockchain();

		byte[] signature;
		try (Repository repository = RepositoryManager.getRepository()) {
			ArbitraryDataTransactionBuilder builder = new ArbitraryDataTransactionBuilder(repository,
					Base58.encode(sequence(32, 1)), 0L, bundle, NAME, ArbitraryTransactionData.Method.PUT,
					Service.ARBITRARY_DATA, IDENTIFIER, null, null, null, null);
			builder.build();

			ArbitraryTransactionData transactionData = builder.getArbitraryTransactionData();
			assertNotNull(transactionData);
			signature = syntheticSignature(transactionData);
			transactionData.setSignature(signature);
			transactionData.setApprovalStatus(Transaction.ApprovalStatus.NOT_REQUIRED);

			repository.getTransactionRepository().save(transactionData);
			int relocatedFiles = ArbitraryTransactionUtils.checkAndRelocateMiscFiles(transactionData);
			assertTrue("Expected the QDN writer output to move from _misc", relocatedFiles > 0);
			ArbitraryDataFile dataFile = ArbitraryDataFile.fromTransactionData(transactionData);
			assertNotNull(dataFile);
			assertTrue("Relocated local QDN data is incomplete", dataFile.allFilesExist());
			repository.saveChanges();
			assertFalse("Fixture cache preparation requires non-lite test settings",
					Settings.getInstance().isLite());
			assertTrue("Fresh fixture should expose the packaged-startup cache gap",
					ArbitraryDataCacheManager.getInstance().needsArbitraryResourcesCacheRebuild(repository));
			assertTrue("Expected fixture QDN resource cache to be built",
					ArbitraryDataCacheManager.getInstance().buildArbitraryResourcesCache(repository, false));
			assertFalse("Fixture QDN resource cache remains incomplete",
					ArbitraryDataCacheManager.getInstance().needsArbitraryResourcesCacheRebuild(repository));

			assertTrue(repository.getTransactionRepository().getUnconfirmedTransactionSignatures().stream()
					.noneMatch(candidate -> Arrays.equals(candidate, signature)));
			assertNull(repository.getTransactionRepository().fromSignature(signature).getBlockHeight());
		}

		// A fresh factory proves the retained on-disk repository and signature-keyed data can be reopened.
		RepositoryManager.closeRepositoryFactory();
		Common.setRepository(false);
		String signature58 = Base58.encode(signature);
		try (Repository repository = RepositoryManager.getRepository()) {
			ArbitraryTransactionData transactionData = ZcashFamilyWalletController
					.getPinnedTransactionData(repository, signature58);
			assertEquals(Service.ARBITRARY_DATA, transactionData.getService());
			assertEquals(IDENTIFIER, transactionData.getIdentifier());
			assertNull(transactionData.getBlockHeight());
			assertFalse("Retained fixture QDN resource cache did not survive repository reopen",
					ArbitraryDataCacheManager.getInstance().needsArbitraryResourcesCacheRebuild(repository));

			Path resolved = ZcashFamilyWalletController
					.resolvePinnedQdnWalletPath(signature58, transactionData, true, false);
			PirateUnifiedWalletBundle.validate(resolved, REQUIRED_LIBRARY);
			for (String filename : PirateUnifiedWalletBundle.bundleFiles())
				assertEquals(filename, -1L, Files.mismatch(bundle.resolve(filename), resolved.resolve(filename)));
			assertEquals(PirateUnifiedWalletBundle.MANIFEST_FILENAME, -1L,
					Files.mismatch(bundle.resolve(PirateUnifiedWalletBundle.MANIFEST_FILENAME),
							resolved.resolve(PirateUnifiedWalletBundle.MANIFEST_FILENAME)));
		}

		writeFixtureProperties(fixture, signature58, bundle);
	}

	private static Path requiredAbsoluteDirectory(String property) {
		String value = System.getProperty(property);
		assumeTrue("Set -D" + property + "=/absolute/path", value != null && !value.isBlank());
		Path path = Path.of(value);
		assertTrue(property + " must be absolute", path.isAbsolute());
		path = path.normalize();
		assertTrue(property + " must identify a directory", Files.isDirectory(path));
		return path;
	}

	private static void configureFixturePaths(Path fixture) throws IllegalAccessException {
		Settings settings = Settings.getInstance();
		FieldUtils.writeField(settings, "repositoryPath", fixture.resolve("repository").toString(), true);
		FieldUtils.writeField(settings, "dataPath", fixture.resolve("data").toString(), true);
		FieldUtils.writeField(settings, "tempDataPath", fixture.resolve("temp").toString(), true);
		FieldUtils.writeField(settings, "listsPath", fixture.resolve("lists").toString(), true);
		FieldUtils.writeField(settings, "exportPath", fixture.resolve("export").toString(), true);
		FieldUtils.writeField(settings, "walletsPath", fixture.resolve("wallets").toString(), true);
	}

	private static byte[] syntheticSignature(ArbitraryTransactionData transactionData) {
		byte[] domain = "qortium-pirate-unified-local-qdn-fixture-v2".getBytes(StandardCharsets.UTF_8);
		byte[] data = transactionData.getData();
		byte[] metadata = transactionData.getMetadataHash() != null
				? transactionData.getMetadataHash() : new byte[0];
		byte[] first = Crypto.digest(concatenate(domain, data, metadata));
		byte[] second = Crypto.digest(concatenate(domain, first, data, metadata));
		return concatenate(first, second);
	}

	private static byte[] concatenate(byte[]... inputs) {
		int length = Arrays.stream(inputs).mapToInt(input -> input.length).sum();
		byte[] output = new byte[length];
		int offset = 0;
		for (byte[] input : inputs) {
			System.arraycopy(input, 0, output, offset, input.length);
			offset += input.length;
		}
		return output;
	}

	private static byte[] sequence(int length, int start) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; ++i)
			bytes[i] = (byte) (start + i);
		return bytes;
	}

	private static void writeFixtureProperties(Path fixture, String signature58, Path bundle) throws Exception {
		String manifestSha256 = HexFormat.of().formatHex(
				Crypto.digestFileStream(bundle.resolve(PirateUnifiedWalletBundle.MANIFEST_FILENAME).toFile()));
		String properties = "format=qortium-pirate-unified-local-qdn-fixture-v2\n"
				+ "signature=" + signature58 + "\n"
				+ "service=ARBITRARY_DATA\n"
				+ "name=" + NAME + "\n"
				+ "identifier=" + IDENTIFIER + "\n"
				+ "repositoryPath=repository\n"
				+ "dataPath=data\n"
				+ "tempDataPath=temp\n"
				+ "walletsPath=wallets\n"
				+ "bundleManifestSha256=" + manifestSha256 + "\n"
				+ "transactionState=synthetic-direct-repository-row\n"
				+ "arbitraryResourceCacheReady=true\n"
				+ "unconfirmedPoolEntry=false\n"
				+ "blockHeight=null\n";
		Files.writeString(fixture.resolve("fixture.properties"), properties,
				StandardCharsets.UTF_8);
	}
}
