package org.qortium.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Objects;

/** Keeps Pirate's generated protobuf package behind the existing shared provider boundary. */
public final class PirateCompactBlockAdapter {

	private PirateCompactBlockAdapter() {
	}

	public static CompactBlock toSharedBlock(pirate.wallet.sdk.rpc.CompactFormats.CompactBlock pirateBlock) {
		Objects.requireNonNull(pirateBlock);

		try {
			return CompactBlock.parseFrom(pirateBlock.toByteArray());
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalArgumentException("Unable to adapt Pirate compact block", e);
		}
	}
}
