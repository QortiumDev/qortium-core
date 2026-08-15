package org.qortium.crypto;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Persistent Ed25519 identity used to bind a Core instance's online minting-account bundle.
 *
 * <p>The caller owns path selection. A missing identity is created from secure randomness and
 * atomically published from a same-directory temporary file. Existing identities are never
 * replaced: malformed, unreadable, symbolic-link, and non-regular paths fail closed.</p>
 */
public final class RewardNodeIdentity {

	public static final int SEED_LENGTH = 32;

	private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
			Collections.unmodifiableSet(PosixFilePermissions.fromString("rw-------"));
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final ConcurrentMap<Path, Object> JVM_CREATION_LOCKS = new ConcurrentHashMap<>();

	private final Ed25519PrivateKeyParameters privateKeyParameters;
	private final byte[] publicKey;

	private RewardNodeIdentity(byte[] seed) {
		this.privateKeyParameters = new Ed25519PrivateKeyParameters(seed, 0);
		this.publicKey = this.privateKeyParameters.generatePublicKey().getEncoded();
	}

	/**
	 * Loads the identity at {@code identityPath}, creating it atomically when it is absent.
	 *
	 * @throws IOException if the existing path cannot be safely read or a missing identity cannot
	 *                     be created and atomically published
	 */
	public static RewardNodeIdentity loadOrCreate(Path identityPath) throws IOException {
		return loadOrCreate(identityPath, null);
	}

	/**
	 * Loads the identity at {@code identityPath}, copying a valid identity from
	 * {@code legacyIdentityPath} when the authoritative path is absent.
	 *
	 * <p>The legacy file is deliberately retained for rollback. An existing authoritative
	 * path always wins and is never replaced; an unsafe or malformed legacy path fails
	 * closed instead of silently rotating the node identity.</p>
	 */
	public static RewardNodeIdentity loadOrCreate(Path identityPath, Path legacyIdentityPath) throws IOException {
		Objects.requireNonNull(identityPath, "identityPath");

		Path normalizedPath = identityPath.toAbsolutePath().normalize();
		Path normalizedLegacyPath = legacyIdentityPath == null ? null
				: legacyIdentityPath.toAbsolutePath().normalize();
		Object jvmLock = JVM_CREATION_LOCKS.computeIfAbsent(normalizedPath, ignored -> new Object());

		synchronized (jvmLock) {
			RewardNodeIdentity existingIdentity = loadIfPresent(normalizedPath);
			if (existingIdentity != null)
				return existingIdentity;

			Path parentPath = normalizedPath.getParent();
			if (parentPath == null)
				throw new IOException("Reward-node identity path has no parent directory: " + normalizedPath);

			Files.createDirectories(parentPath);
			if (!Files.isDirectory(parentPath))
				throw new IOException("Reward-node identity parent is not a directory: " + parentPath);

			Path lockPath = normalizedPath.resolveSibling(normalizedPath.getFileName() + ".lock");
			try (FileChannel lockChannel = openLockFile(lockPath);
					 FileLock ignored = lockChannel.lock()) {
				existingIdentity = loadIfPresent(normalizedPath);
				if (existingIdentity != null)
					return existingIdentity;

				if (normalizedLegacyPath != null && !normalizedPath.equals(normalizedLegacyPath)) {
					byte[] legacySeed = readSeedIfPresent(normalizedLegacyPath);
					if (legacySeed != null)
						return createIdentity(normalizedPath, parentPath, legacySeed);
				}

				byte[] seed = new byte[SEED_LENGTH];
				SECURE_RANDOM.nextBytes(seed);
				return createIdentity(normalizedPath, parentPath, seed);
			}
		}
	}

	public byte[] getPublicKey() {
		return Arrays.copyOf(this.publicKey, this.publicKey.length);
	}

	public byte[] sign(byte[] message) {
		Objects.requireNonNull(message, "message");
		return Crypto.sign(this.privateKeyParameters, message);
	}

	private static RewardNodeIdentity loadIfPresent(Path identityPath) throws IOException {
		byte[] seed = readSeedIfPresent(identityPath);
		return seed == null ? null : new RewardNodeIdentity(seed);
	}

	private static byte[] readSeedIfPresent(Path identityPath) throws IOException {
		if (!Files.exists(identityPath, LinkOption.NOFOLLOW_LINKS))
			return null;

		if (Files.isSymbolicLink(identityPath))
			throw new IOException("Reward-node identity path must not be a symbolic link: " + identityPath);

		if (!Files.isRegularFile(identityPath, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Reward-node identity path is not a regular file: " + identityPath);

		if (!isReadableByOwner(identityPath) || !Files.isReadable(identityPath))
			throw new IOException("Reward-node identity is unreadable: " + identityPath);

		byte[] seed = new byte[SEED_LENGTH];
		try (FileChannel channel = FileChannel.open(identityPath, StandardOpenOption.READ,
				LinkOption.NOFOLLOW_LINKS)) {
			if (channel.size() != SEED_LENGTH)
				throw invalidSeedLength(identityPath, channel.size());

			ByteBuffer seedBuffer = ByteBuffer.wrap(seed);
			while (seedBuffer.hasRemaining()) {
				if (channel.read(seedBuffer) < 0)
					throw invalidSeedLength(identityPath, seedBuffer.position());
			}

			if (channel.size() != SEED_LENGTH)
				throw invalidSeedLength(identityPath, channel.size());
		}

		restrictPermissions(identityPath);
		return seed;
	}

	private static RewardNodeIdentity createIdentity(Path identityPath, Path parentPath, byte[] seed) throws IOException {
		Path temporaryPath = createTemporaryIdentityFile(parentPath, identityPath.getFileName().toString());
		try {
			try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
				ByteBuffer seedBuffer = ByteBuffer.wrap(seed);
				while (seedBuffer.hasRemaining())
					channel.write(seedBuffer);

				channel.force(true);
			}

			restrictPermissions(temporaryPath);

			try {
				Files.move(temporaryPath, identityPath, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				throw new IOException("Atomic reward-node identity creation is not supported for " + identityPath, e);
			}

			restrictPermissions(identityPath);
			return loadIfPresent(identityPath);
		} finally {
			Files.deleteIfExists(temporaryPath);
		}
	}

	private static FileChannel openLockFile(Path lockPath) throws IOException {
		if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
			if (Files.isSymbolicLink(lockPath) || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Reward-node identity lock path is unsafe: " + lockPath);
		} else {
			try {
				createOwnerOnlyFile(lockPath);
			} catch (java.nio.file.FileAlreadyExistsException e) {
				if (Files.isSymbolicLink(lockPath) || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("Reward-node identity lock path is unsafe: " + lockPath, e);
			}
		}

		restrictPermissions(lockPath);
		return FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
	}

	private static Path createTemporaryIdentityFile(Path parentPath, String identityFileName) throws IOException {
		String prefix = "." + identityFileName + ".";
		if (supportsPosixPermissions(parentPath)) {
			FileAttribute<Set<PosixFilePermission>> permissions =
					PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS);
			return Files.createTempFile(parentPath, prefix, ".tmp", permissions);
		}

		Path temporaryPath = Files.createTempFile(parentPath, prefix, ".tmp");
		restrictPermissions(temporaryPath);
		return temporaryPath;
	}

	private static void createOwnerOnlyFile(Path path) throws IOException {
		Path parentPath = path.getParent();
		if (parentPath != null && supportsPosixPermissions(parentPath)) {
			FileAttribute<Set<PosixFilePermission>> permissions =
					PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS);
			Files.createFile(path, permissions);
			return;
		}

		Files.createFile(path);
		restrictPermissions(path);
	}

	private static boolean isReadableByOwner(Path path) throws IOException {
		if (!supportsPosixPermissions(path))
			return true;

		return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
				.contains(PosixFilePermission.OWNER_READ);
	}

	private static void restrictPermissions(Path path) throws IOException {
		if (supportsPosixPermissions(path)) {
			Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS);
			return;
		}

		java.io.File file = path.toFile();
		boolean changed = file.setReadable(false, false);
		changed &= file.setWritable(false, false);
		changed &= file.setExecutable(false, false);
		changed &= file.setReadable(true, true);
		changed &= file.setWritable(true, true);
		if (!changed)
			throw new IOException("Unable to set owner-only permissions on reward-node identity file: " + path);
	}

	private static boolean supportsPosixPermissions(Path path) throws IOException {
		Path fileStorePath = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? path : path.getParent();
		if (fileStorePath == null)
			fileStorePath = Path.of(".").toAbsolutePath().normalize();

		return Files.getFileStore(fileStorePath).supportsFileAttributeView(PosixFileAttributeView.class);
	}

	private static IOException invalidSeedLength(Path identityPath, long actualLength) {
		return new IOException(String.format("Reward-node identity must contain exactly %d bytes, but %s contains %d",
				SEED_LENGTH, identityPath, actualLength));
	}
}
