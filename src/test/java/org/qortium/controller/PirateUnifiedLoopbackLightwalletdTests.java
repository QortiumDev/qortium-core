package org.qortium.controller;

import cash.z.wallet.sdk.rpc.CompactFormats;
import cash.z.wallet.sdk.rpc.CompactTxStreamerGrpc;
import cash.z.wallet.sdk.rpc.Service;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import org.junit.Test;

import java.net.URI;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PirateUnifiedLoopbackLightwalletdTests {

	@Test
	public void testHistoricalModeServesOneWellFormedCompactNote() throws Exception {
		try (PirateUnifiedLoopbackLightwalletd fixture = new PirateUnifiedLoopbackLightwalletd(true)) {
			URI endpoint = URI.create(fixture.endpoint());
			ManagedChannel channel =
					ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
			try {
				CompactFormats.CompactBlock block = CompactTxStreamerGrpc.newBlockingStub(channel)
						.getBlock(Service.BlockID.newBuilder()
								.setHeight(PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_HEIGHT)
								.build());
				assertEquals(PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_HEIGHT, block.getHeight());
				assertEquals(1, block.getVtxCount());
				assertEquals(32, block.getVtx(0).getHash().size());
				assertEquals(1, block.getVtx(0).getOutputsCount());
				assertEquals(32, block.getVtx(0).getOutputs(0).getCmu().size());
				assertEquals(32, block.getVtx(0).getOutputs(0).getEpk().size());
				assertEquals(52, block.getVtx(0).getOutputs(0).getCiphertext().size());
			} finally {
				channel.shutdownNow();
				channel.awaitTermination(10, TimeUnit.SECONDS);
			}
		}
	}

	@Test
	public void testBothServiceNamesExposeDeterministicFixture() throws Exception {
		try (PirateUnifiedLoopbackLightwalletd fixture = new PirateUnifiedLoopbackLightwalletd()) {
			URI endpoint = URI.create(fixture.endpoint());
			ManagedChannel channel =
					ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
			try {
				assertEquals(PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT,
						CompactTxStreamerGrpc.newBlockingStub(channel)
								.getLightdInfo(Service.Empty.getDefaultInstance())
								.getBlockHeight());
				MethodDescriptor<Service.Empty, Service.LightdInfo> pirateInfo =
						CompactTxStreamerGrpc.getGetLightdInfoMethod().toBuilder()
								.setFullMethodName(MethodDescriptor.generateFullMethodName(
										PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE, "GetLightdInfo"))
								.setSchemaDescriptor(null)
								.build();
				assertEquals(PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT,
						ClientCalls.blockingUnaryCall(channel, pirateInfo, io.grpc.CallOptions.DEFAULT,
								Service.Empty.getDefaultInstance()).getBlockHeight());

				MethodDescriptor<Service.ChainSpec, Service.BlockID> pirateLatest =
						CompactTxStreamerGrpc.getGetLatestBlockMethod()
								.toBuilder()
								.setFullMethodName(MethodDescriptor.generateFullMethodName(
										PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE, "GetLatestBlock"))
								.setSchemaDescriptor(null)
								.build();
				assertEquals(PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT,
						ClientCalls
								.blockingUnaryCall(channel, pirateLatest, io.grpc.CallOptions.DEFAULT,
										Service.ChainSpec.getDefaultInstance())
								.getHeight());

				Service.BlockRange fullRange =
						Service.BlockRange.newBuilder()
								.setStart(Service.BlockID.newBuilder().setHeight(
										PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT))
								.setEnd(Service.BlockID.newBuilder().setHeight(
										PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT))
								.build();
				Iterator<cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock> blocks =
						CompactTxStreamerGrpc.newBlockingStub(channel).getBlockRange(fullRange);
				assertCompleteRange(blocks);
				MethodDescriptor<Service.BlockRange, cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock> pirateRange =
						CompactTxStreamerGrpc.getGetBlockRangeMethod().toBuilder()
								.setFullMethodName(MethodDescriptor.generateFullMethodName(
										PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE, "GetBlockRange"))
								.setSchemaDescriptor(null)
								.build();
				assertCompleteRange(ClientCalls.blockingServerStreamingCall(channel, pirateRange,
						io.grpc.CallOptions.DEFAULT, fullRange));

				StatusRuntimeException sendFailure = assertThrows(StatusRuntimeException.class,
						() -> CompactTxStreamerGrpc.newBlockingStub(channel)
								.sendTransaction(Service.RawTransaction.getDefaultInstance()));
				assertEquals(Status.Code.PERMISSION_DENIED, sendFailure.getStatus().getCode());
				StatusRuntimeException transactionFailure = assertThrows(StatusRuntimeException.class,
						() -> CompactTxStreamerGrpc.newBlockingStub(channel)
								.getTransaction(Service.TxFilter.getDefaultInstance()));
				assertEquals(Status.Code.PERMISSION_DENIED, transactionFailure.getStatus().getCode());
				StatusRuntimeException treeFailure = assertThrows(StatusRuntimeException.class,
						() -> CompactTxStreamerGrpc.newBlockingStub(channel)
								.getTreeState(Service.BlockID.newBuilder()
										.setHeight(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT)
										.build()));
				assertEquals(Status.Code.FAILED_PRECONDITION, treeFailure.getStatus().getCode());
				StatusRuntimeException blockFailure = assertThrows(StatusRuntimeException.class,
						() -> CompactTxStreamerGrpc.newBlockingStub(channel)
								.getBlock(Service.BlockID.newBuilder()
										.setHeight(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT - 1)
										.build()));
				assertEquals(Status.Code.INVALID_ARGUMENT, blockFailure.getStatus().getCode());
				assertEquals(PirateUnifiedLoopbackLightwalletd.IRONWOOD_PROBE_HEIGHT,
						CompactTxStreamerGrpc.newBlockingStub(channel)
								.getBlock(Service.BlockID.newBuilder()
										.setHeight(PirateUnifiedLoopbackLightwalletd.IRONWOOD_PROBE_HEIGHT)
										.build())
								.getHeight());

				MethodDescriptor<Service.Empty, Service.LightdInfo> unknownMethod =
						CompactTxStreamerGrpc.getGetLightdInfoMethod().toBuilder()
								.setFullMethodName(MethodDescriptor.generateFullMethodName(
										PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE, "UnknownFixtureMethod"))
								.setSchemaDescriptor(null)
								.build();
				StatusRuntimeException unknownFailure = assertThrows(StatusRuntimeException.class,
						() -> ClientCalls.blockingUnaryCall(channel, unknownMethod, io.grpc.CallOptions.DEFAULT,
								Service.Empty.getDefaultInstance()));
				assertEquals(Status.Code.UNIMPLEMENTED, unknownFailure.getStatus().getCode());
				MethodDescriptor<Service.Empty, Service.LightdInfo> subtreeProbe =
						CompactTxStreamerGrpc.getGetLightdInfoMethod().toBuilder()
								.setFullMethodName(MethodDescriptor.generateFullMethodName(
										PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE, "GetSubtreeRoots"))
								.setSchemaDescriptor(null)
								.build();
				StatusRuntimeException subtreeFailure = assertThrows(StatusRuntimeException.class,
						() -> ClientCalls.blockingUnaryCall(channel, subtreeProbe, io.grpc.CallOptions.DEFAULT,
								Service.Empty.getDefaultInstance()));
				assertEquals(Status.Code.UNIMPLEMENTED, subtreeFailure.getStatus().getCode());

				assertEquals(1, fixture.completeRangeCount(PirateUnifiedLoopbackLightwalletd.CASH_SERVICE));
				assertEquals(1, fixture.completeRangeCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE));
				assertEquals(1, fixture.pirateTipRangeCount());
				assertEquals(0, fixture.pirateScannedBlockCount());
				assertEquals(1, fixture.activationProbeCount());
				assertEquals(1, fixture.subtreeProbeCount());
				assertEquals(2, fixture.forbiddenRpcCount());
				assertEquals(2, fixture.unexpectedRpcCount());
			} finally {
				channel.shutdownNow();
				channel.awaitTermination(10, TimeUnit.SECONDS);
			}
		}
	}

	@Test
	public void testFixedPortChainNameAndStandaloneAuditAreSanitized() throws Exception {
		try (PirateUnifiedLoopbackLightwalletd fixture =
				new PirateUnifiedLoopbackLightwalletd(0, "regtest", "main")) {
			URI endpoint = URI.create(fixture.endpoint());
			ManagedChannel channel =
					ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
			try {
				assertEquals("regtest", CompactTxStreamerGrpc.newBlockingStub(channel)
						.getLightdInfo(Service.Empty.getDefaultInstance()).getChainName());
				MethodDescriptor<Service.Empty, Service.LightdInfo> pirateInfo =
						CompactTxStreamerGrpc.getGetLightdInfoMethod().toBuilder()
								.setFullMethodName(MethodDescriptor.generateFullMethodName(
										PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE, "GetLightdInfo"))
								.setSchemaDescriptor(null)
								.build();
				assertEquals("main", ClientCalls.blockingUnaryCall(channel, pirateInfo,
						io.grpc.CallOptions.DEFAULT, Service.Empty.getDefaultInstance()).getChainName());
			} finally {
				channel.shutdownNow();
				channel.awaitTermination(10, TimeUnit.SECONDS);
			}

			String audit = PirateUnifiedLoopbackLightwalletdMain.audit(fixture);
			assertTrue(audit.contains("result=PASS\n"));
			assertTrue(audit.contains("forbiddenRpcs=0\n"));
			assertFalse(audit.contains("127.0.0.1"));
		}
	}

	private static void assertCompleteRange(
			Iterator<cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock> blocks) {
		long expectedHeight = PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT;
		while (blocks.hasNext())
			assertEquals(expectedHeight++, blocks.next().getHeight());
		assertEquals(PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT + 1, expectedHeight);
	}
}
