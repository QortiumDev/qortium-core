package org.qortium.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import com.google.protobuf.ByteString;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PirateCompactBlockAdapterTests {

	@Test
	public void testPirateBlockAdaptsWithoutChangingSharedProviderType() throws Exception {
		pirate.wallet.sdk.rpc.CompactFormats.CompactOrchardAction orchardAction =
				pirate.wallet.sdk.rpc.CompactFormats.CompactOrchardAction.newBuilder()
						.setNullifier(ByteString.copyFromUtf8("nullifier"))
						.setCmx(ByteString.copyFromUtf8("commitment"))
						.setEphemeralKey(ByteString.copyFromUtf8("ephemeral-key"))
						.setCiphertext(ByteString.copyFromUtf8("ciphertext"))
						.build();
		pirate.wallet.sdk.rpc.CompactFormats.CompactBlock pirateBlock =
				pirate.wallet.sdk.rpc.CompactFormats.CompactBlock.newBuilder()
						.setProtoVersion(4)
						.setHeight(2_345_678L)
						.setHash(ByteString.copyFromUtf8("hash"))
						.addVtx(pirate.wallet.sdk.rpc.CompactFormats.CompactTx.newBuilder()
								.setIndex(7)
								.addActions(orchardAction))
						.build();

		CompactBlock sharedBlock = PirateCompactBlockAdapter.toSharedBlock(pirateBlock);

		assertEquals(pirateBlock.getProtoVersion(), sharedBlock.getProtoVersion());
		assertEquals(pirateBlock.getHeight(), sharedBlock.getHeight());
		assertEquals(pirateBlock.getHash(), sharedBlock.getHash());
		assertEquals(pirateBlock.getVtx(0).getIndex(), sharedBlock.getVtx(0).getIndex());

		pirate.wallet.sdk.rpc.CompactFormats.CompactBlock roundTripped =
				pirate.wallet.sdk.rpc.CompactFormats.CompactBlock.parseFrom(sharedBlock.toByteArray());
		assertArrayEquals(pirateBlock.toByteArray(), roundTripped.toByteArray());
		assertEquals(orchardAction, roundTripped.getVtx(0).getActions(0));

		Method providerMethod = BitcoinyBlockchainProvider.class.getMethod("getCompactBlocks", int.class, int.class);
		String providerType = providerMethod.getGenericReturnType().getTypeName();
		assertTrue(providerType.contains("cash.z.wallet.sdk.rpc.CompactFormats$CompactBlock"));
		assertFalse(providerType.contains("pirate.wallet.sdk.rpc"));
	}
}
