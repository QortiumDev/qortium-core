package org.qortium.network.message;

import java.nio.ByteBuffer;

/**
 * Requests a peer's block-archive manifest — the list of archive chunks it can serve, each with its
 * block-height range, SHA-256 and size. The response is an {@link ArchiveManifestMessage}.
 * <p>
 * Part of block-archive distribution: a node fetches a trusted manifest, then fetches and verifies
 * individual chunks against it. This request carries no payload.
 */
public class GetArchiveManifestMessage extends Message {

	public GetArchiveManifestMessage() {
		super(MessageType.GET_ARCHIVE_MANIFEST);

		this.dataBytes = EMPTY_DATA_BYTES;
	}

	private GetArchiveManifestMessage(int id) {
		super(id, MessageType.GET_ARCHIVE_MANIFEST);
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		return new GetArchiveManifestMessage(id);
	}
}
