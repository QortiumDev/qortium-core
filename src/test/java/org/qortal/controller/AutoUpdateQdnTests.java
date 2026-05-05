package org.qortal.controller;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ArbitraryUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.utils.Base58;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

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
