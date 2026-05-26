package org.qortium.network.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import org.qortium.data.network.LiteDataAnchor;
import org.qortium.transform.block.BlockTransformer;

final class LiteDataMessageUtils {

	private LiteDataMessageUtils() {
	}

	static void serializeStatusAndAnchor(ByteArrayOutputStream bytes, LiteDataResponseStatus status, LiteDataAnchor anchor) throws IOException {
		if (status == null)
			throw new IllegalArgumentException("Lite data response status is required");
		if (anchor == null)
			throw new IllegalArgumentException("Lite data response anchor is required");

		byte[] blockSignature = anchor.getBlockSignature();
		if (blockSignature == null || blockSignature.length != BlockTransformer.BLOCK_SIGNATURE_LENGTH)
			throw new IllegalArgumentException("Lite data response anchor has invalid block signature");

		bytes.write(Ints.toByteArray(status.getValue()));
		bytes.write(Ints.toByteArray(anchor.getHeight()));
		bytes.write(blockSignature);
		bytes.write(Longs.toByteArray(anchor.getTimestamp()));
	}

	static LiteDataResponseStatus deserializeStatus(ByteBuffer byteBuffer) throws MessageException {
		return LiteDataResponseStatus.valueOf(byteBuffer.getInt());
	}

	static LiteDataAnchor deserializeAnchor(ByteBuffer byteBuffer) {
		int height = byteBuffer.getInt();

		byte[] blockSignature = new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH];
		byteBuffer.get(blockSignature);

		long timestamp = byteBuffer.getLong();

		return new LiteDataAnchor(height, blockSignature, timestamp);
	}

}
