package org.qortium.network.message;

import com.google.common.primitives.Ints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.block.Block;
import org.qortium.data.block.BlockData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transform.TransformationException;
import org.qortium.transform.block.BlockTransformation;
import org.qortium.transform.block.BlockTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public class BlockMessage extends Message {

	private static final Logger LOGGER = LogManager.getLogger(BlockMessage.class);

	private BlockData blockData;
	private List<TransactionData> transactions;
	private byte[] atStatesHash;

	public BlockMessage(Block block) throws TransformationException {
		super(MessageType.BLOCK);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(Ints.toByteArray(block.getBlockData().getHeight()));

			bytes.write(BlockTransformer.toBytesV2(block));
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	public BlockMessage(byte[] cachedBytes) {
		super(MessageType.BLOCK);

		this.dataBytes = cachedBytes;
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private BlockMessage(int id, BlockData blockData, List<TransactionData> transactions, byte[] atStatesHash) {
		super(id, MessageType.BLOCK);

		this.blockData = blockData;
		this.transactions = transactions;
		this.atStatesHash = atStatesHash;
	}

	public BlockData getBlockData() {
		return this.blockData;
	}

	public List<TransactionData> getTransactions() {
		return this.transactions;
	}

	public byte[] getAtStatesHash() {
		return this.atStatesHash;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		try {
			int height = byteBuffer.getInt();

			BlockTransformation blockTransformation = BlockTransformer.fromByteBufferV2(byteBuffer);

			BlockData blockData = blockTransformation.getBlockData();
			blockData.setHeight(height);

			return new BlockMessage(id, blockData, blockTransformation.getTransactions(), blockTransformation.getAtStatesHash());
		} catch (TransformationException e) {
			LOGGER.info(String.format("Received garbled BLOCK message: %s", e.getMessage()));
			throw new MessageException(e.getMessage(), e);
		}
	}

}
