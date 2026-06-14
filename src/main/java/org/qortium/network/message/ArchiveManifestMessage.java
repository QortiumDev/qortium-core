package org.qortium.network.message;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.data.block.ArchiveChunkData;
import org.qortium.data.block.ArchiveManifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A peer's block-archive manifest: the archive version and the list of chunks it can serve, each
 * with its block-height range, SHA-256 (of the chunk file) and size. Sent in response to a
 * {@link GetArchiveManifestMessage}.
 * <p>
 * Wire format: archiveVersion(int), count(int), then per chunk: startHeight(int), endHeight(int),
 * sha256(32 bytes), size(long).
 */
public class ArchiveManifestMessage extends Message {

	private static final int SHA256_LENGTH = 32;

	private ArchiveManifest manifest;

	public ArchiveManifestMessage(ArchiveManifest manifest) {
		super(MessageType.ARCHIVE_MANIFEST);

		this.manifest = manifest;

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(manifest.getArchiveVersion()));
			bytes.write(Ints.toByteArray(manifest.getChunks().size()));

			for (ArchiveChunkData chunk : manifest.getChunks()) {
				bytes.write(Ints.toByteArray(chunk.getStartHeight()));
				bytes.write(Ints.toByteArray(chunk.getEndHeight()));
				bytes.write(HashCode.fromString(chunk.getSha256()).asBytes());
				bytes.write(Longs.toByteArray(chunk.getSize()));
			}

			this.dataBytes = bytes.toByteArray();
		} catch (IOException e) {
			this.dataBytes = null;
			this.checksumBytes = null;
			return;
		}

		if (this.dataBytes.length > 0)
			this.checksumBytes = Message.generateChecksum(this.dataBytes);
		else
			this.checksumBytes = null;
	}

	private ArchiveManifestMessage(int id, ArchiveManifest manifest) {
		super(id, MessageType.ARCHIVE_MANIFEST);

		this.manifest = manifest;
	}

	public ArchiveManifest getManifest() {
		return this.manifest;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		int archiveVersion = bytes.getInt();
		int count = bytes.getInt();

		if (count < 0)
			return null;

		List<ArchiveChunkData> chunks = new ArrayList<>(Math.min(count, 1024));
		for (int i = 0; i < count; ++i) {
			int startHeight = bytes.getInt();
			int endHeight = bytes.getInt();

			byte[] sha256 = new byte[SHA256_LENGTH];
			bytes.get(sha256);

			long size = bytes.getLong();

			chunks.add(new ArchiveChunkData(startHeight, endHeight, HashCode.fromBytes(sha256).toString(), size));
		}

		return new ArchiveManifestMessage(id, new ArchiveManifest(archiveVersion, chunks));
	}
}
