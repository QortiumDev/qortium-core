package org.qortium.controller;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.crypto.Crypto;
import org.qortium.crypto.RewardNodeIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RewardNodeIdentityTests {

	private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
			PosixFilePermissions.fromString("rw-------");

	private Path testRoot;
	private Path identityPath;

	@Before
	public void beforeTest() throws IOException {
		this.testRoot = Files.createTempDirectory("reward-node-identity");
		this.identityPath = this.testRoot.resolve("reward-node").resolve("identity.key");
	}

	@After
	public void afterTest() throws IOException {
		if (this.testRoot != null)
			FileUtils.deleteDirectory(this.testRoot.toFile());
	}

	@Test
	public void testCreateReloadAndOwnerOnlyPermissions() throws Exception {
		RewardNodeIdentity createdIdentity = RewardNodeIdentity.loadOrCreate(this.identityPath);

		assertTrue(Files.isRegularFile(this.identityPath, LinkOption.NOFOLLOW_LINKS));
		byte[] storedSeed = Files.readAllBytes(this.identityPath);
		assertEquals(RewardNodeIdentity.SEED_LENGTH, storedSeed.length);
		assertArrayEquals(Crypto.toPublicKey(storedSeed), createdIdentity.getPublicKey());
		assertOwnerOnlyPermissionsIfSupported(this.identityPath);

		byte[] message = "reward-node-identity-test".getBytes(StandardCharsets.UTF_8);
		byte[] signature = createdIdentity.sign(message);
		assertTrue(Crypto.verify(createdIdentity.getPublicKey(), signature, message));

		RewardNodeIdentity reloadedIdentity = RewardNodeIdentity.loadOrCreate(this.identityPath);
		assertArrayEquals(createdIdentity.getPublicKey(), reloadedIdentity.getPublicKey());
		assertArrayEquals(signature, reloadedIdentity.sign(message));
		assertNoTemporaryIdentityFiles();
	}

	@Test
	public void testConcurrentCreationConvergesOnOneIdentity() throws Exception {
		int workerCount = 12;
		ExecutorService executor = Executors.newFixedThreadPool(workerCount);
		CountDownLatch ready = new CountDownLatch(workerCount);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<byte[]>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < workerCount; ++i) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return RewardNodeIdentity.loadOrCreate(this.identityPath).getPublicKey();
				}));
			}

			assertTrue("Concurrent identity workers did not become ready",
					ready.await(10, TimeUnit.SECONDS));
			start.countDown();

			byte[] expectedPublicKey = futures.get(0).get(10, TimeUnit.SECONDS);
			for (Future<byte[]> future : futures)
				assertArrayEquals(expectedPublicKey, future.get(10, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}

		assertEquals(RewardNodeIdentity.SEED_LENGTH, Files.size(this.identityPath));
		assertOwnerOnlyPermissionsIfSupported(this.identityPath);
		assertNoTemporaryIdentityFiles();
	}

	@Test
	public void testCorruptIdentityFailsClosedWithoutReplacement() throws Exception {
		Files.createDirectories(this.identityPath.getParent());

		for (int invalidLength : new int[] { 0, RewardNodeIdentity.SEED_LENGTH - 1,
				RewardNodeIdentity.SEED_LENGTH + 1 }) {
			byte[] corruptSeed = new byte[invalidLength];
			Arrays.fill(corruptSeed, (byte) invalidLength);
			Files.write(this.identityPath, corruptSeed);

			assertLoadFails(this.identityPath);
			assertArrayEquals("Corrupt identity must not be rotated or replaced", corruptSeed,
					Files.readAllBytes(this.identityPath));
		}
	}

	@Test
	public void testSymbolicLinkIdentityFailsClosed() throws Exception {
		Path realIdentityPath = this.testRoot.resolve("real-identity.key");
		byte[] realSeed = new byte[RewardNodeIdentity.SEED_LENGTH];
		Arrays.fill(realSeed, (byte) 7);
		Files.write(realIdentityPath, realSeed);

		Files.createDirectories(this.identityPath.getParent());
		Files.createSymbolicLink(this.identityPath, realIdentityPath);

		assertLoadFails(this.identityPath);
		assertTrue(Files.isSymbolicLink(this.identityPath));
		assertArrayEquals(realSeed, Files.readAllBytes(realIdentityPath));
	}

	@Test
	public void testUnreadableIdentityFailsClosedWherePosixSupported() throws Exception {
		Files.createDirectories(this.identityPath.getParent());
		byte[] seed = new byte[RewardNodeIdentity.SEED_LENGTH];
		Arrays.fill(seed, (byte) 11);
		Files.write(this.identityPath, seed);

		if (!supportsPosixPermissions(this.identityPath))
			return;

		try {
			Files.setPosixFilePermissions(this.identityPath, PosixFilePermissions.fromString("-w-------"));
			assertLoadFails(this.identityPath);
		} finally {
			Files.setPosixFilePermissions(this.identityPath, OWNER_ONLY_PERMISSIONS);
		}

		assertArrayEquals(seed, Files.readAllBytes(this.identityPath));
	}

	@Test
	public void testCopiedIdentityReloadsWithSamePublicKey() throws Exception {
		RewardNodeIdentity originalIdentity = RewardNodeIdentity.loadOrCreate(this.identityPath);

		Path copiedIdentityPath = this.testRoot.resolve("copied-runtime").resolve("reward-node").resolve("identity.key");
		Files.createDirectories(copiedIdentityPath.getParent());
		Files.copy(this.identityPath, copiedIdentityPath, StandardCopyOption.COPY_ATTRIBUTES);

		RewardNodeIdentity copiedIdentity = RewardNodeIdentity.loadOrCreate(copiedIdentityPath);
		assertArrayEquals(originalIdentity.getPublicKey(), copiedIdentity.getPublicKey());
		assertArrayEquals(Files.readAllBytes(this.identityPath), Files.readAllBytes(copiedIdentityPath));
		assertOwnerOnlyPermissionsIfSupported(copiedIdentityPath);
	}

	private void assertLoadFails(Path path) throws Exception {
		try {
			RewardNodeIdentity.loadOrCreate(path);
			fail("Expected reward-node identity loading to fail for " + path);
		} catch (IOException expected) {
			// Expected fail-closed behavior.
		}
	}

	private void assertOwnerOnlyPermissionsIfSupported(Path path) throws Exception {
		if (supportsPosixPermissions(path))
			assertEquals(OWNER_ONLY_PERMISSIONS, Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
	}

	private boolean supportsPosixPermissions(Path path) throws IOException {
		Path fileStorePath = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? path : path.getParent();
		return fileStorePath != null
				&& Files.getFileStore(fileStorePath).supportsFileAttributeView(PosixFileAttributeView.class);
	}

	private void assertNoTemporaryIdentityFiles() throws IOException {
		Path parentPath = this.identityPath.getParent();
		if (!Files.exists(parentPath))
			return;

		try (java.util.stream.Stream<Path> entries = Files.list(parentPath)) {
			assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
		}
	}
}
