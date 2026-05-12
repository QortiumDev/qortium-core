package org.qortal.controller;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ArbitraryUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TestChainBootstrapUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class AutoUpdateQdnTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testWriteVerifiedUpdateDecodesXoredBytes() throws Exception {
		byte[] jarBytes = "qortium test jar bytes".getBytes();
		byte[] updateBytes = xor(jarBytes);
		Path newJar = Files.createTempFile("new-qortium", ".jar");
		Files.deleteIfExists(newJar);

		assertTrue(AutoUpdate.writeVerifiedUpdate(new ByteArrayInputStream(updateBytes), sha256(updateBytes), newJar, "test"));
		assertArrayEquals(jarBytes, Files.readAllBytes(newJar));

		Files.deleteIfExists(newJar);
	}

	@Test
	public void testWriteVerifiedUpdateRejectsHashMismatch() throws Exception {
		byte[] updateBytes = xor("qortium test jar bytes".getBytes());
		byte[] wrongHash = sha256("wrong".getBytes());
		Path newJar = Files.createTempFile("new-qortium", ".jar");

		assertFalse(AutoUpdate.writeVerifiedUpdate(new ByteArrayInputStream(updateBytes), wrongHash, newJar, "test"));
		assertFalse(Files.exists(newJar));
	}

	@Test
	public void testResolveQdnUpdatePathRequiresPinnedSignatureMatch() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String updateName = "aliceupdates";
			registerName(repository, alice, updateName);

			byte[] commitHash = sequentialBytes(20, 1);
			byte[] updateBytes = xor("qortium qdn update".getBytes());
			Path updateFile = Files.createTempDirectory("qdn-update").resolve(AutoUpdateManifest.QDN_UPDATE_PATH);
			Files.write(updateFile, updateBytes);

			ArbitraryUtils.createAndMintTxn(repository, Base58.encode(alice.getPublicKey()), updateFile,
					updateName, hex(commitHash), ArbitraryTransactionData.Method.PUT,
					Service.AUTO_UPDATE_BINARY, alice);

			byte[] binarySignature = repository.getArbitraryRepository()
					.getLatestTransaction(updateName, Service.AUTO_UPDATE_BINARY,
							ArbitraryTransactionData.Method.PUT, hex(commitHash))
					.getSignature();

			AutoUpdateManifest manifest = AutoUpdateManifest.qdnV1(1_700_000_000_000L, commitHash, sha256(updateBytes), binarySignature);
			Path resolvedPath = AutoUpdate.resolveQdnUpdatePath(manifest);
			assertArrayEquals(updateBytes, Files.readAllBytes(resolvedPath));

			byte[] wrongSignature = Arrays.copyOf(binarySignature, binarySignature.length);
			wrongSignature[0] ^= 1;
			AutoUpdateManifest wrongManifest = AutoUpdateManifest.qdnV1(1_700_000_000_000L, commitHash, sha256(updateBytes), wrongSignature);

			try {
				AutoUpdate.resolveQdnUpdatePath(wrongManifest);
				fail("Expected mismatched pinned signature to be rejected");
			} catch (DataException e) {
				assertTrue(e.getMessage().contains("signature"));
			}
		}
	}

	@Test
	public void testResolveQdnUpdatePathRejectsWrongPinnedService() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String updateName = "wrongserviceupdates";
			registerName(repository, alice, updateName);

			byte[] commitHash = sequentialBytes(20, 1);
			byte[] updateBytes = xor("qortium qdn update".getBytes());
			Path updateFile = Files.createTempDirectory("qdn-update").resolve(AutoUpdateManifest.QDN_UPDATE_PATH);
			Files.write(updateFile, updateBytes);

			ArbitraryUtils.createAndMintTxn(repository, Base58.encode(alice.getPublicKey()), updateFile,
					updateName, hex(commitHash), ArbitraryTransactionData.Method.PUT,
					Service.FILE, alice);

			byte[] binarySignature = repository.getArbitraryRepository()
					.getLatestTransaction(updateName, Service.FILE,
							ArbitraryTransactionData.Method.PUT, hex(commitHash))
					.getSignature();

			AutoUpdateManifest manifest = AutoUpdateManifest.qdnV1(1_700_000_000_000L, commitHash, sha256(updateBytes), binarySignature);

			try {
				AutoUpdate.resolveQdnUpdatePath(manifest);
				fail("Expected pinned transaction with the wrong service to be rejected");
			} catch (DataException e) {
				assertTrue(e.getMessage().contains("service"));
			}
		}
	}

	@Test
	public void testResolveQdnUpdatePathRejectsUnpinnedManifest() throws Exception {
		byte[] commitHash = sequentialBytes(20, 1);
		byte[] updateBytes = xor("qortium qdn update".getBytes());
		AutoUpdateManifest manifest = AutoUpdateManifest.qdnV1(1_700_000_000_000L, commitHash, sha256(updateBytes), null);

		try {
			AutoUpdate.resolveQdnUpdatePath(manifest);
			fail("Expected unpinned QDN update manifest to be rejected");
		} catch (DataException e) {
			assertTrue(e.getMessage().contains("pin"));
		}
	}

	@Test
	public void testCheckLatestUpdateFindsApprovedQdnManifestWhenAutoUpdateDisabled() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long updateTimestamp = Controller.getInstance().getBuildTimestamp() * 1000L + 1_000L;
			byte[] commitHash = sequentialBytes(20, 1);
			byte[] updateBytes = xor("qortium qdn update".getBytes());
			String updateName = "approvedupdates";
			byte[] binarySignature = createAutoUpdateBinary(repository, alice, updateName, commitHash, updateBytes);
			AutoUpdateManifest manifest = AutoUpdateManifest.qdnV1(updateTimestamp, commitHash, sha256(updateBytes), binarySignature);

			TransactionData transactionData = createAndApproveAutoUpdateManifest(repository, alice, manifest.toBytes());

			AutoUpdate.UpdateCheckResult status = AutoUpdate.checkLatestUpdate();

			assertFalse(status.autoUpdateEnabled);
			assertTrue(status.qdnEnabled);
			assertTrue(status.updateAvailable);
			assertFalse(status.installing);
			assertEquals(AutoUpdate.STATUS_UPDATE_AVAILABLE, status.status);
			assertEquals(updateTimestamp, status.updateTimestamp.longValue());
			assertEquals(hex(commitHash), status.commitHash);
			assertEquals(Base58.encode(transactionData.getSignature()), status.manifestSignature);
			assertEquals(alice.getAddress(), status.manifestCreatorAddress);
			assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, status.manifestTxGroupId.intValue());
			assertEquals(Transaction.ApprovalStatus.APPROVED.name(), status.manifestApprovalStatus);
			assertNotNull(status.manifestBlockHeight);
			assertNotNull(status.manifestApprovalHeight);
			assertNotNull(status.devGroups);
			assertFalse(status.devGroups.isEmpty());
			assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, status.devGroups.get(0).groupId.intValue());
			assertEquals(Base58.encode(binarySignature), status.binarySignature);
			assertEquals(alice.getAddress(), status.binaryCreatorAddress);
			assertEquals(Service.AUTO_UPDATE_BINARY.name(), status.binaryService);
			assertEquals(updateName, status.binaryName);
			assertEquals(hex(commitHash), status.binaryIdentifier);
			assertEquals(ArbitraryTransactionData.Method.PUT.name(), status.binaryMethod);
			assertNotNull(status.binaryBlockHeight);
			assertEquals(Service.AUTO_UPDATE_BINARY.name(), status.qdnService);
			assertEquals(AutoUpdateManifest.QDN_UPDATE_NAME, status.qdnName);
			assertEquals(hex(commitHash), status.qdnIdentifier);
			assertEquals(AutoUpdateManifest.QDN_UPDATE_PATH, status.qdnPath);
		}
	}

	@Test
	public void testCheckLatestUpdateReportsApprovedManifestThatIsNotNewer() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long updateTimestamp = Controller.getInstance().getBuildTimestamp() * 1000L;
			byte[] commitHash = sequentialBytes(20, 1);
			byte[] updateBytes = xor("qortium qdn update".getBytes());
			byte[] binarySignature = createAutoUpdateBinary(repository, alice, "notnewerupdates", commitHash, updateBytes);
			AutoUpdateManifest manifest = AutoUpdateManifest.qdnV1(updateTimestamp, commitHash, sha256(updateBytes), binarySignature);

			createAndApproveAutoUpdateManifest(repository, alice, manifest.toBytes());

			AutoUpdate.UpdateCheckResult status = AutoUpdate.checkLatestUpdate();

			assertFalse(status.updateAvailable);
			assertEquals(AutoUpdate.STATUS_NOT_NEWER, status.status);
			assertEquals(updateTimestamp, status.updateTimestamp.longValue());
			assertEquals(hex(commitHash), status.commitHash);
		}
	}

	@Test
	public void testCheckLatestUpdateRejectsApprovedUnpinnedManifest() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long updateTimestamp = Controller.getInstance().getBuildTimestamp() * 1000L + 1_000L;
			byte[] commitHash = sequentialBytes(20, 1);
			byte[] updateHash = sequentialBytes(32, 21);
			AutoUpdateManifest manifest = AutoUpdateManifest.qdnV1(updateTimestamp, commitHash, updateHash, null);

			createAndApproveAutoUpdateManifest(repository, alice, manifest.toBytes());

			AutoUpdate.UpdateCheckResult status = AutoUpdate.checkLatestUpdate();

			assertFalse(status.updateAvailable);
			assertEquals(AutoUpdate.STATUS_UNPINNED_MANIFEST, status.status);
			assertTrue(status.message.contains("pin"));
			assertNull(status.binarySignature);
		}
	}

	@Test
	public void testCheckLatestUpdateRejectsMissingPinnedBinaryTransaction() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long updateTimestamp = Controller.getInstance().getBuildTimestamp() * 1000L + 1_000L;
			byte[] commitHash = sequentialBytes(20, 1);
			byte[] updateHash = sequentialBytes(32, 21);
			AutoUpdateManifest manifest = AutoUpdateManifest.qdnV1(updateTimestamp, commitHash, updateHash, sequentialBytes(64, 53));

			createAndApproveAutoUpdateManifest(repository, alice, manifest.toBytes());

			AutoUpdate.UpdateCheckResult status = AutoUpdate.checkLatestUpdate();

			assertFalse(status.updateAvailable);
			assertEquals(AutoUpdate.STATUS_INVALID_BINARY_TRANSACTION, status.status);
			assertTrue(status.message.contains("ARBITRARY"));
		}
	}

	private static byte[] createAutoUpdateBinary(Repository repository, PrivateKeyAccount creator, String updateName,
			byte[] commitHash, byte[] updateBytes) throws Exception {
		registerName(repository, creator, updateName);

		Path updateFile = Files.createTempDirectory("qdn-update").resolve(AutoUpdateManifest.QDN_UPDATE_PATH);
		Files.write(updateFile, updateBytes);

		ArbitraryUtils.createAndMintTxn(repository, Base58.encode(creator.getPublicKey()), updateFile,
				updateName, hex(commitHash), ArbitraryTransactionData.Method.PUT,
				Service.AUTO_UPDATE_BINARY, creator);

		return repository.getArbitraryRepository()
				.getLatestTransaction(updateName, Service.AUTO_UPDATE_BINARY,
						ArbitraryTransactionData.Method.PUT, hex(commitHash))
				.getSignature();
	}

	private static TransactionData createAndApproveAutoUpdateManifest(Repository repository, PrivateKeyAccount creator, byte[] manifestData) throws DataException {
		BaseTransactionData baseTransactionData = TestTransaction.generateBase(creator, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
		int version = Transaction.getVersionByTimestamp(baseTransactionData.getTimestamp());
		ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData, version,
				Service.AUTO_UPDATE.value, 0, manifestData.length, null, null, ArbitraryTransactionData.Method.PUT,
				null, ArbitraryTransactionData.Compression.NONE, manifestData, ArbitraryTransactionData.DataType.RAW_DATA,
				null, Collections.emptyList());

		TransactionUtils.signAndMint(repository, transactionData, creator);
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		GroupData groupData = repository.getGroupRepository().fromGroupId(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
		BlockUtils.mintBlocks(repository, Math.max(2, groupData.getMinimumBlockDelay() + 1));

		TransactionData approvedTransactionData = repository.getTransactionRepository().fromSignature(transactionData.getSignature());
		assertEquals(Transaction.ApprovalStatus.APPROVED, approvedTransactionData.getApprovalStatus());
		return approvedTransactionData;
	}

	private static void registerName(Repository repository, PrivateKeyAccount account, String name) throws DataException {
		TransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(account), name, "{}");
		transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
		TransactionUtils.signAndMint(repository, transactionData, account);
	}

	private static byte[] xor(byte[] bytes) {
		byte[] xored = bytes.clone();
		for (int i = 0; i < xored.length; ++i)
			xored[i] ^= AutoUpdate.XOR_VALUE;

		return xored;
	}

	private static byte[] sha256(byte[] bytes) throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("SHA-256").digest(bytes);
	}

	private static byte[] sequentialBytes(int length, int start) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; ++i)
			bytes[i] = (byte) (start + i);

		return bytes;
	}

	private static String hex(byte[] bytes) {
		StringBuilder stringBuilder = new StringBuilder(bytes.length * 2);
		for (byte b : bytes)
			stringBuilder.append(String.format("%02x", b));

		return stringBuilder.toString();
	}
}
