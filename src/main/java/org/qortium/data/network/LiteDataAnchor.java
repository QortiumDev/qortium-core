package org.qortium.data.network;

import java.util.Arrays;

import org.qortium.data.block.BlockData;

public class LiteDataAnchor {

	private final int height;
	private final byte[] blockSignature;
	private final long timestamp;

	public LiteDataAnchor(int height, byte[] blockSignature, long timestamp) {
		this.height = height;
		this.blockSignature = blockSignature == null ? null : Arrays.copyOf(blockSignature, blockSignature.length);
		this.timestamp = timestamp;
	}

	public static LiteDataAnchor fromBlockData(BlockData blockData) {
		if (blockData == null || blockData.getHeight() == null || blockData.getSignature() == null)
			return null;

		return new LiteDataAnchor(blockData.getHeight(), blockData.getSignature(), blockData.getTimestamp());
	}

	public int getHeight() {
		return this.height;
	}

	public byte[] getBlockSignature() {
		return this.blockSignature == null ? null : Arrays.copyOf(this.blockSignature, this.blockSignature.length);
	}

	public long getTimestamp() {
		return this.timestamp;
	}

}
