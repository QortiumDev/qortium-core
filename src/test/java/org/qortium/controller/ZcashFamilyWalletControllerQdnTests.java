package org.qortium.controller;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.misc.Service;
import org.qortium.crosschain.ZcashFamilyWalletConfig;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.utils.Base58;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ZcashFamilyWalletControllerQdnTests extends Common {

	private static final String IDENTIFIER = "wallet-library";
	private static final String LIB_FILENAME = "librust-linux-x86_64.so";

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testPinnedTransactionIgnoresLaterNameRevision() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "pinnedwalletlibrary";
			registerName(repository, alice, name);

			byte[] pinnedBytes = "pinned native bytes".getBytes(StandardCharsets.UTF_8);
			ArbitraryTransactionData pinnedTransaction = publishLibrary(repository, alice, name, pinnedBytes);
			String pinnedSignature = Base58.encode(pinnedTransaction.getSignature());

			byte[] laterBytes = "later mutable bytes".getBytes(StandardCharsets.UTF_8);
			ArbitraryTransactionData laterTransaction = publishLibrary(repository, alice, name, laterBytes);
			assertNotEquals(pinnedSignature, Base58.encode(laterTransaction.getSignature()));

			ArbitraryTransactionData resolvedTransaction = ZcashFamilyWalletController
					.getPinnedTransactionData(repository, pinnedSignature);
			Path resolvedPath = ZcashFamilyWalletController
					.resolvePinnedQdnWalletPath(pinnedSignature, resolvedTransaction);

			assertArrayEquals(pinnedBytes, Files.readAllBytes(resolvedPath.resolve(LIB_FILENAME)));
		}
	}

	@Test
	public void testPinnedTransactionRejectsWrongService() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "wrongservicewalletlibrary";
			registerName(repository, alice, name);

			Path directory = Files.createTempDirectory("wrong-service-wallet");
			Files.writeString(directory.resolve(LIB_FILENAME), "not a wallet service");
			ArbitraryUtils.createAndMintTxn(repository, Base58.encode(alice.getPublicKey()), directory,
					name, IDENTIFIER, ArbitraryTransactionData.Method.PUT, Service.FILE, alice);

			ArbitraryTransactionData transactionData = repository.getArbitraryRepository()
					.getLatestTransaction(name, Service.FILE, ArbitraryTransactionData.Method.PUT, IDENTIFIER);
			String signature = Base58.encode(transactionData.getSignature());

			DataException exception = assertThrows(DataException.class,
					() -> ZcashFamilyWalletController.getPinnedTransactionData(repository, signature));
			assertTrue(exception.getMessage().contains("ARBITRARY_DATA"));
		}
	}

	@Test
	public void testPinnedTransactionRejectsMalformedSignature() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			DataException exception = assertThrows(DataException.class,
					() -> ZcashFamilyWalletController.getPinnedTransactionData(repository, "not-a-signature"));
			assertTrue(exception.getMessage().contains("invalid"));
		}
	}

	@Test
	public void testPinnedTransactionRejectsNonArbitraryTransaction() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			TransactionData registerNameTransaction = registerName(repository, alice, "notawalletpublication");
			String signature = Base58.encode(registerNameTransaction.getSignature());

			DataException exception = assertThrows(DataException.class,
					() -> ZcashFamilyWalletController.getPinnedTransactionData(repository, signature));
			assertTrue(exception.getMessage().contains("ARBITRARY"));
		}
	}

	@Test
	public void testFullSignaturesUseIsolatedCacheDirectories() {
		String firstSignature = Base58.encode(sequence(64, 1));
		String secondSignature = Base58.encode(sequence(64, 2));
		ZcashFamilyWalletConfig first = config(firstSignature);
		ZcashFamilyWalletConfig second = config(secondSignature);

		Path firstDirectory = first.getRustLibOuterDirectory();
		Path secondDirectory = second.getRustLibOuterDirectory();

		assertEquals(firstSignature, firstDirectory.getFileName().toString());
		assertEquals(secondSignature, secondDirectory.getFileName().toString());
		assertNotEquals(firstDirectory, secondDirectory);
		assertNotEquals(firstSignature.substring(0, 8), firstDirectory.getFileName().toString());
	}

	@Test
	public void testPinnedResourceRequiresDirectoryAndPlatformLibrary() throws Exception {
		Path plainFile = Files.createTempFile("wallet-resource", ".bin");
		Path directory = Files.createTempDirectory("wallet-resource");

		assertThrows(DataException.class,
				() -> ZcashFamilyWalletController.validatePinnedResourcePath(plainFile, LIB_FILENAME));
		assertThrows(DataException.class,
				() -> ZcashFamilyWalletController.validatePinnedResourcePath(directory, LIB_FILENAME));

		Files.writeString(directory.resolve(LIB_FILENAME), "native bytes");
		ZcashFamilyWalletController.validatePinnedResourcePath(directory, LIB_FILENAME);
	}

	@Test
	public void testPlatformLibrarySelectionPreservesSupportedMappings() {
		assertEquals("librust-macos-x86_64.dylib",
				ZcashFamilyWalletController.resolveRustLibFilename("Mac OS X", "x86_64"));
		assertEquals("librust-linux-aarch64.so",
				ZcashFamilyWalletController.resolveRustLibFilename("Linux", "aarch64"));
		assertEquals("librust-linux-x86_64.so",
				ZcashFamilyWalletController.resolveRustLibFilename("FreeBSD", "amd64"));
		assertEquals("librust-windows-x86_64.dll",
				ZcashFamilyWalletController.resolveRustLibFilename("Windows 11", "amd64"));
		assertNull(ZcashFamilyWalletController.resolveRustLibFilename("Mac OS X", "aarch64"));
		assertNull(ZcashFamilyWalletController.resolveRustLibFilename("Plan 9", "amd64"));
	}

	private static ArbitraryTransactionData publishLibrary(Repository repository, PrivateKeyAccount creator,
			String name, byte[] bytes) throws Exception {
		Path directory = Files.createTempDirectory("pinned-wallet-library");
		Files.write(directory.resolve(LIB_FILENAME), bytes);
		Files.writeString(directory.resolve("MANIFEST.txt"), "test wallet bundle\n");
		ArbitraryUtils.createAndMintTxn(repository, Base58.encode(creator.getPublicKey()), directory,
				name, IDENTIFIER, ArbitraryTransactionData.Method.PUT, Service.ARBITRARY_DATA, creator);
		return repository.getArbitraryRepository().getLatestTransaction(name, Service.ARBITRARY_DATA,
				ArbitraryTransactionData.Method.PUT, IDENTIFIER);
	}

	private static TransactionData registerName(Repository repository, PrivateKeyAccount account, String name) throws DataException {
		TransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(account), name, "{}");
		transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
		TransactionUtils.signAndMint(repository, transactionData, account);
		return transactionData;
	}

	private static ZcashFamilyWalletConfig config(String signature) {
		return new ZcashFamilyWalletConfig("Test wallet", "TEST", "TestWallet", signature,
				"TestWalletEncryption", "zs", () -> 1, () -> null);
	}

	private static byte[] sequence(int length, int start) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; ++i)
			bytes[i] = (byte) (start + i);
		return bytes;
	}
}
