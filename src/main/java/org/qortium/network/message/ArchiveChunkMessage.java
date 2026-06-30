package org.qortium.network.message;

import com.google.common.primitives.Ints;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A slice of a block-archive chunk: the chunk's start height, the byte offset of this slice within
 * the chunk file, the chunk's total size, and the slice bytes. Sent in response to a
 * {@link GetArchiveChunkMessage}. A requester reassembles all slices in order and verifies the
 * complete chunk against its manifest SHA-256 before use.
 * <p>
 * Wire format: startHeight(int), offset(int), totalSize(int), dataLength(int), data(bytes).
 */
public class ArchiveChunkMessage extends Message {

	/** Hard upper bound on a slice; the effective per-message cap is the smaller of this and the
	 *  peer's receive-buffer size — see {@link #maxWireSafeSlice(int)}. */
	public static final int MAX_SLICE_LENGTH = 8 * 1024 * 1024;

	/**
	 * Largest slice byte-count that, wrapped in an ArchiveChunkMessage, still fits a peer's receive
	 * buffer ({@code Network.maxMessageSize}). MAX_SLICE_LENGTH (8 MB) can exceed that buffer (which is
	 * only ~maxBlockSize, e.g. ~2 MB on preview), in which case the slice message is never delivered and
	 * the chunk can't be fetched. Both requester and server bound their slice length by this so any
	 * chunk — including the multi-MB archive chunks — downloads in buffer-fitting slices.
	 */
	public static int maxWireSafeSlice(int maxMessageSize) {
		// Generous headroom for the message header + this message's four int fields.
		return Math.max(0, Math.min(MAX_SLICE_LENGTH, maxMessageSize - 1024));
	}

	private final int startHeight;
	private final int offset;
	private final int totalSize;
	private final byte[] data;

	public ArchiveChunkMessage(int startHeight, int offset, int totalSize, byte[] data) {
		super(MessageType.ARCHIVE_CHUNK);

		this.startHeight = startHeight;
		this.offset = offset;
		this.totalSize = totalSize;
		this.data = data;

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(startHeight));
			bytes.write(Ints.toByteArray(offset));
			bytes.write(Ints.toByteArray(totalSize));
			bytes.write(Ints.toByteArray(data.length));
			bytes.write(data);

			this.dataBytes = bytes.toByteArray();
		} catch (IOException e) {
			this.dataBytes = null;
			this.checksumBytes = null;
			return;
		}

		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private ArchiveChunkMessage(int id, int startHeight, int offset, int totalSize, byte[] data) {
		super(id, MessageType.ARCHIVE_CHUNK);

		this.startHeight = startHeight;
		this.offset = offset;
		this.totalSize = totalSize;
		this.data = data;
	}

	public int getStartHeight() {
		return this.startHeight;
	}

	public int getOffset() {
		return this.offset;
	}

	public int getTotalSize() {
		return this.totalSize;
	}

	public byte[] getData() {
		return this.data;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		int startHeight = bytes.getInt();
		int offset = bytes.getInt();
		int totalSize = bytes.getInt();
		int dataLength = bytes.getInt();

		if (dataLength < 0 || dataLength > bytes.remaining())
			return null;

		byte[] data = new byte[dataLength];
		bytes.get(data);

		return new ArchiveChunkMessage(id, startHeight, offset, totalSize, data);
	}
}
