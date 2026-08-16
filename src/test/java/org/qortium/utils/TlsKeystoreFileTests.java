package org.qortium.utils;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.net.URI;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TlsKeystoreFileTests {

	private static final char[] PASSWORD = "test-password".toCharArray();

	private Path testDirectory;

	@Before
	public void before() throws IOException {
		this.testDirectory = Files.createTempDirectory("tls-keystore-permissions-");
	}

	@After
	public void after() throws IOException {
		if (this.testDirectory == null || !Files.exists(this.testDirectory))
			return;

		try (Stream<Path> paths = Files.walk(this.testDirectory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}

	@Test
	public void testStoreKeystoreCreatesOwnerOnlyFile() throws Exception {
		Path keystorePath = this.testDirectory.resolve("new-keystore.p12");
		KeyStore keyStore = emptyKeystore();

		SslUtils.storeKeystore(keystorePath, keyStore, PASSWORD);

		assertTrue(Files.isRegularFile(keystorePath, LinkOption.NOFOLLOW_LINKS));
		assertOwnerOnly(keystorePath);
		assertEquals(0, loadKeystore(keystorePath).size());
		assertNoTemporaryFiles(keystorePath);
	}

	@Test
	public void testExistingBroadKeystoreIsRepairedWithoutChangingContents() throws Exception {
		Path keystorePath = this.testDirectory.resolve("existing-keystore.p12");
		byte[] originalBytes = encodedKeystore(emptyKeystore());
		Files.write(keystorePath, originalBytes);

		PosixFileAttributeView posixView = Files.getFileAttributeView(keystorePath,
				PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posixView != null)
			Files.setPosixFilePermissions(keystorePath, PosixFilePermissions.fromString("rw-rw----"));

		SslUtils.ensureKeystorePermissions(keystorePath);

		assertArrayEquals(originalBytes, Files.readAllBytes(keystorePath));
		assertOwnerOnly(keystorePath);
		assertEquals(0, loadKeystore(keystorePath).size());
	}

	@Test
	public void testFailedStorePreservesExistingKeystoreAndCleansTemporaryFile() throws Exception {
		Path keystorePath = this.testDirectory.resolve("preserved-keystore.p12");
		byte[] originalBytes = encodedKeystore(emptyKeystore());
		Files.write(keystorePath, originalBytes);

		try {
			TlsKeystoreFile.writeAtomically(keystorePath, outputStream -> {
				outputStream.write(new byte[] { 1, 2, 3, 4 });
				throw new IOException("injected store failure");
			});
			fail("Expected injected store failure");
		} catch (IOException e) {
			assertTrue(e.getMessage().contains("injected store failure"));
		}

		assertArrayEquals(originalBytes, Files.readAllBytes(keystorePath));
		assertOwnerOnly(keystorePath);
		assertNoTemporaryFiles(keystorePath);
	}

	@Test
	public void testDirectoryTargetIsRejected() throws Exception {
		Path directoryPath = this.testDirectory.resolve("keystore-directory");
		Files.createDirectory(directoryPath);
		assertPermissionFailure(directoryPath);
	}

	@Test
	public void testSymlinkTargetIsRejectedBeforeWriting() throws Exception {
		Path targetPath = this.testDirectory.resolve("symlink-target.p12");
		byte[] originalBytes = encodedKeystore(emptyKeystore());
		Files.write(targetPath, originalBytes);
		Path symlinkPath = this.testDirectory.resolve("symlink-keystore.p12");
		try {
			Files.createSymbolicLink(symlinkPath, targetPath.getFileName());
		} catch (UnsupportedOperationException | IOException e) {
			Assume.assumeNoException("Symbolic links are unavailable on this filesystem", e);
		}

		AtomicBoolean writerCalled = new AtomicBoolean();
		try {
			TlsKeystoreFile.writeAtomically(symlinkPath, outputStream -> writerCalled.set(true));
			fail("Expected symbolic-link keystore rejection");
		} catch (IOException e) {
			assertTrue(e.getMessage().contains("regular file"));
		}

		assertFalse(writerCalled.get());
		assertTrue(Files.isSymbolicLink(symlinkPath));
		assertArrayEquals(originalBytes, Files.readAllBytes(targetPath));
	}

	@Test
	public void testUnsupportedPermissionModelFailsClosed() throws Exception {
		Path zipPath = this.testDirectory.resolve("unsupported-filesystem.zip");
		URI zipUri = URI.create("jar:" + zipPath.toUri());
		try (FileSystem fileSystem = FileSystems.newFileSystem(zipUri, Map.of("create", "true"))) {
			Path keystorePath = fileSystem.getPath("/unsupported-keystore.p12");
			Files.write(keystorePath, encodedKeystore(emptyKeystore()));

			try {
				SslUtils.ensureKeystorePermissions(keystorePath);
				fail("Expected unsupported permission model rejection");
			} catch (IOException e) {
				assertTrue(e.getMessage().contains("cannot enforce owner-only"));
			}
		}
	}

	@Test
	public void testSuccessfulReplacementLeavesCompleteOwnerOnlyKeystore() throws Exception {
		Path keystorePath = this.testDirectory.resolve("replacement-keystore.p12");
		Files.write(keystorePath, new byte[] { 9, 8, 7 });

		SslUtils.storeKeystore(keystorePath, emptyKeystore(), PASSWORD);

		assertEquals(0, loadKeystore(keystorePath).size());
		assertOwnerOnly(keystorePath);
		assertNoTemporaryFiles(keystorePath);
	}

	private void assertPermissionFailure(Path path) throws Exception {
		try {
			SslUtils.ensureKeystorePermissions(path);
			fail("Expected unsafe keystore path rejection");
		} catch (IOException e) {
			assertTrue(e.getMessage().contains("regular file"));
		}
	}

	private void assertOwnerOnly(Path path) throws IOException {
		PosixFileAttributeView posixView = Files.getFileAttributeView(path,
				PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posixView != null) {
			assertEquals(TlsKeystoreFile.OWNER_ONLY_PERMISSIONS, posixView.readAttributes().permissions());
			return;
		}

		assertTrue(path.toFile().canRead());
		assertTrue(path.toFile().canWrite());
		assertFalse(path.toFile().canExecute());
	}

	private void assertNoTemporaryFiles(Path keystorePath) throws IOException {
		String prefix = "." + keystorePath.getFileName() + ".";
		try (Stream<Path> paths = Files.list(keystorePath.getParent())) {
			assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(prefix)));
		}
	}

	private static KeyStore emptyKeystore() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		keyStore.load(null, null);
		return keyStore;
	}

	private static byte[] encodedKeystore(KeyStore keyStore) throws Exception {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		keyStore.store(outputStream, PASSWORD);
		return outputStream.toByteArray();
	}

	private static KeyStore loadKeystore(Path path) throws Exception {
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		keyStore.load(new ByteArrayInputStream(Files.readAllBytes(path)), PASSWORD);
		return keyStore;
	}
}
