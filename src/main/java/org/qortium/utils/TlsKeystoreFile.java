package org.qortium.utils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class TlsKeystoreFile {

	static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
			PosixFilePermissions.fromString("rw-------");
	private static final Set<AclEntryPermission> OWNER_ACL_PERMISSIONS = EnumSet.of(
			AclEntryPermission.READ_DATA,
			AclEntryPermission.WRITE_DATA,
			AclEntryPermission.APPEND_DATA,
			AclEntryPermission.READ_NAMED_ATTRS,
			AclEntryPermission.WRITE_NAMED_ATTRS,
			AclEntryPermission.READ_ATTRIBUTES,
			AclEntryPermission.WRITE_ATTRIBUTES,
			AclEntryPermission.DELETE,
			AclEntryPermission.READ_ACL,
			AclEntryPermission.WRITE_ACL,
			AclEntryPermission.WRITE_OWNER,
			AclEntryPermission.SYNCHRONIZE
	);

	@FunctionalInterface
	interface Writer {
		void write(OutputStream outputStream) throws Exception;
	}

	private TlsKeystoreFile() {
	}

	static void ensureOwnerOnly(Path path) throws IOException {
		Path normalizedPath = path.toAbsolutePath().normalize();
		requireRegularFile(normalizedPath);

		PosixFileAttributeView posixView = Files.getFileAttributeView(normalizedPath,
				PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posixView != null) {
			posixView.setPermissions(OWNER_ONLY_PERMISSIONS);
			Set<PosixFilePermission> actualPermissions = posixView.readAttributes().permissions();
			if (!OWNER_ONLY_PERMISSIONS.equals(actualPermissions))
				throw new IOException("Unable to confirm owner-only permissions on TLS keystore: " + normalizedPath);
			return;
		}

		AclFileAttributeView aclView = Files.getFileAttributeView(normalizedPath,
				AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (aclView == null)
			throw new IOException("Filesystem cannot enforce owner-only TLS keystore permissions: " + normalizedPath);

		restrictAclPermissions(normalizedPath, aclView);
	}

	static void writeAtomically(Path path, Writer writer) throws Exception {
		Path normalizedPath = path.toAbsolutePath().normalize();
		Path parentPath = normalizedPath.getParent();
		if (parentPath == null || !Files.isDirectory(parentPath))
			throw new IOException("TLS keystore parent directory is unavailable: " + parentPath);

		if (Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS))
			ensureOwnerOnly(normalizedPath);

		Path temporaryPath = null;
		try {
			temporaryPath = createOwnerOnlyTemporaryFile(parentPath, normalizedPath.getFileName().toString());
			try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING)) {
				OutputStream outputStream = Channels.newOutputStream(channel);
				writer.write(outputStream);
				outputStream.flush();
				channel.force(true);
			}

			ensureOwnerOnly(temporaryPath);
			try {
				Files.move(temporaryPath, normalizedPath, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				throw new IOException("TLS keystore requires an atomic same-filesystem replacement: " + normalizedPath, e);
			}
			temporaryPath = null;
			ensureOwnerOnly(normalizedPath);
		} finally {
			if (temporaryPath != null)
				Files.deleteIfExists(temporaryPath);
		}
	}

	private static Path createOwnerOnlyTemporaryFile(Path parentPath, String fileName) throws IOException {
		String prefix = "." + fileName + ".";
		Path probePath = parentPath.resolve(fileName);
		PosixFileAttributeView posixView = Files.getFileAttributeView(probePath,
				PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posixView != null) {
			FileAttribute<Set<PosixFilePermission>> permissions =
					PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS);
			return Files.createTempFile(parentPath, prefix, ".tmp", permissions);
		}

		Path temporaryPath = Files.createTempFile(parentPath, prefix, ".tmp");
		try {
			ensureOwnerOnly(temporaryPath);
			return temporaryPath;
		} catch (IOException e) {
			Files.deleteIfExists(temporaryPath);
			throw e;
		}
	}

	private static void requireRegularFile(Path path) throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile())
			throw new IOException("TLS keystore must be a regular file and not a symbolic link: " + path);
	}

	private static void restrictAclPermissions(Path path, AclFileAttributeView aclView) throws IOException {
		UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
		AclEntry ownerEntry = AclEntry.newBuilder()
				.setType(AclEntryType.ALLOW)
				.setPrincipal(owner)
				.setPermissions(OWNER_ACL_PERMISSIONS)
				.build();
		aclView.setAcl(List.of(ownerEntry));

		boolean ownerCanReadAndWrite = false;
		for (AclEntry entry : aclView.getAcl()) {
			if (entry.type() != AclEntryType.ALLOW)
				continue;
			if (!owner.equals(entry.principal()))
				throw new IOException("TLS keystore ACL still grants access outside its owner: " + path);
			if (entry.permissions().contains(AclEntryPermission.READ_DATA)
					&& entry.permissions().contains(AclEntryPermission.WRITE_DATA))
				ownerCanReadAndWrite = true;
		}

		if (!ownerCanReadAndWrite)
			throw new IOException("Unable to confirm owner read/write access on TLS keystore: " + path);
		requireRegularFile(path);
	}
}
