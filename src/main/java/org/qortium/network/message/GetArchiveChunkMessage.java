package org.qortium.network.message;

import com.google.common.primitives.Ints;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Requests a byte range [offset, offset+length) of the block-archive chunk that begins at the given
 * start height. Chunk files can exceed the single-message size limit, so they are fetched in slices
 * and reassembled by the requester, which verifies the whole chunk against its manifest SHA-256.
 * The response is an {@link ArchiveChunkMessage}.
 * <p>
 * Wire format: startHeight(int), offset(int), length(int).
 */
public class GetArchiveChunkMessage extends Message {

	private final int startHeight;
	private final int offset;
	private final int length;

	public GetArchiveChunkMessage(int startHeight, int offset, int length) {
		super(MessageType.GET_ARCHIVE_CHUNK);

		this.startHeight = startHeight;
		this.offset = offset;
		this.length = length;

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(startHeight));
			bytes.write(Ints.toByteArray(offset));
			bytes.write(Ints.toByteArray(length));

			this.dataBytes = bytes.toByteArray();
		} catch (IOException e) {
			this.dataBytes = null;
			this.checksumBytes = null;
			return;
		}

		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private GetArchiveChunkMessage(int id, int startHeight, int offset, int length) {
		super(id, MessageType.GET_ARCHIVE_CHUNK);

		this.startHeight = startHeight;
		this.offset = offset;
		this.length = length;
	}

	public int getStartHeight() {
		return this.startHeight;
	}

	public int getOffset() {
		return this.offset;
	}

	public int getLength() {
		return this.length;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		if (bytes.remaining() != 3 * Transformer.INT_LENGTH)
			return null;

		int startHeight = bytes.getInt();
		int offset = bytes.getInt();
		int length = bytes.getInt();

		return new GetArchiveChunkMessage(id, startHeight, offset, length);
	}
}
