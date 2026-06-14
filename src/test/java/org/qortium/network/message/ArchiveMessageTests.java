package org.qortium.network.message;

import org.junit.Test;
import org.qortium.data.block.ArchiveChunkData;
import org.qortium.data.block.ArchiveManifest;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Wire round-trip tests for the block-archive distribution messages. Each message is serialized via
 * its public constructor then decoded with fromByteBuffer, and the decoded fields are compared.
 */
public class ArchiveMessageTests {

	/** A deterministic 64-char lowercase-hex SHA-256 stand-in. */
	private static String hash(int seed) {
		StringBuilder sb = new StringBuilder(64);
		for (int i = 0; i < 32; ++i)
			sb.append(String.format("%02x", (seed + i) & 0xff));
		return sb.toString();
	}

	@Test
	public void testGetArchiveManifestRoundTrip() throws MessageException {
		GetArchiveManifestMessage message = new GetArchiveManifestMessage();

		GetArchiveManifestMessage decoded = (GetArchiveManifestMessage)
				GetArchiveManifestMessage.fromByteBuffer(7, ByteBuffer.wrap(message.dataBytes));

		assertNotNull(decoded);
		assertEquals(7, decoded.getId());
	}

	@Test
	public void testArchiveManifestRoundTrip() throws MessageException {
		List<ArchiveChunkData> chunks = new ArrayList<>();
		chunks.add(new ArchiveChunkData(2, 1247, hash(1), 10_500_000L));
		chunks.add(new ArchiveChunkData(1248, 2490, hash(40), 10_400_123L));
		ArchiveManifest manifest = new ArchiveManifest(1, chunks);

		ArchiveManifestMessage message = new ArchiveManifestMessage(manifest);

		ArchiveManifestMessage decoded = (ArchiveManifestMessage)
				ArchiveManifestMessage.fromByteBuffer(9, ByteBuffer.wrap(message.dataBytes));

		ArchiveManifest decodedManifest = decoded.getManifest();
		assertEquals(1, decodedManifest.getArchiveVersion());
		assertEquals(2, decodedManifest.getChunks().size());
		assertEquals(chunks, decodedManifest.getChunks());
	}

	@Test
	public void testEmptyArchiveManifestRoundTrip() throws MessageException {
		ArchiveManifest manifest = new ArchiveManifest(1, new ArrayList<>());
		ArchiveManifestMessage message = new ArchiveManifestMessage(manifest);

		ArchiveManifestMessage decoded = (ArchiveManifestMessage)
				ArchiveManifestMessage.fromByteBuffer(1, ByteBuffer.wrap(message.dataBytes));

		assertEquals(1, decoded.getManifest().getArchiveVersion());
		assertTrue(decoded.getManifest().getChunks().isEmpty());
	}

	@Test
	public void testGetArchiveChunkRoundTrip() throws MessageException {
		GetArchiveChunkMessage message = new GetArchiveChunkMessage(2, 4_000_000, 8_000_000);

		GetArchiveChunkMessage decoded = (GetArchiveChunkMessage)
				GetArchiveChunkMessage.fromByteBuffer(5, ByteBuffer.wrap(message.dataBytes));

		assertEquals(2, decoded.getStartHeight());
		assertEquals(4_000_000, decoded.getOffset());
		assertEquals(8_000_000, decoded.getLength());
	}

	@Test
	public void testArchiveChunkRoundTrip() throws MessageException {
		byte[] data = new byte[5000];
		for (int i = 0; i < data.length; ++i)
			data[i] = (byte) (i % 251);

		ArchiveChunkMessage message = new ArchiveChunkMessage(2, 1_000_000, 10_500_000, data);

		ArchiveChunkMessage decoded = (ArchiveChunkMessage)
				ArchiveChunkMessage.fromByteBuffer(3, ByteBuffer.wrap(message.dataBytes));

		assertEquals(2, decoded.getStartHeight());
		assertEquals(1_000_000, decoded.getOffset());
		assertEquals(10_500_000, decoded.getTotalSize());
		assertArrayEquals(data, decoded.getData());
	}
}
