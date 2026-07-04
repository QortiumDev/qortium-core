package org.qortium.network.message;

import com.google.common.primitives.Ints;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.block.Block;
import org.qortium.data.block.BlockData;
import org.qortium.transform.TransformationException;
import org.qortium.transform.block.BlockTransformation;
import org.qortium.transform.block.BlockTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class BlocksMessage extends Message {

    private static final Logger LOGGER = LogManager.getLogger(BlocksMessage.class);
    private static final int BLOCK_COUNT_LENGTH = Integer.BYTES;
    private static final int BLOCK_HEIGHT_LENGTH = Integer.BYTES;
    private static final int WIRE_SIZE_HEADROOM = 1024;

    private List<Block> blocks;

    public BlocksMessage(List<Block> blocks) {
        super(MessageType.BLOCKS);

        this.blocks = blocks;

        try {
            List<SerializedBlock> serializedBlocks = new ArrayList<>(this.blocks.size());
            for (Block block : this.blocks)
                serializedBlocks.add(SerializedBlock.fromBlock(block));

            this.dataBytes = toDataBytes(serializedBlocks);
        } catch (IOException | TransformationException e) {
            this.dataBytes = null;
            this.checksumBytes = null;
            return;
        }

        setChecksum();
    }

    private BlocksMessage(List<Block> blocks, byte[] dataBytes) {
        super(MessageType.BLOCKS);

        this.blocks = blocks;
        this.dataBytes = dataBytes;
        setChecksum();
    }

    private BlocksMessage(int id, List<Block> blocks) {
        super(id, MessageType.BLOCKS);

        this.blocks = blocks;
    }

    public List<Block> getBlocks() {
        return this.blocks;
    }

    public int getPayloadLength() {
        return this.dataBytes == null ? 0 : this.dataBytes.length;
    }

    public static int maxWireSafePayload(int maxMessageSize) {
        return Math.max(0, maxMessageSize - WIRE_SIZE_HEADROOM);
    }

    public static BoundedBuilder newBoundedBuilder(int maxMessageSize) {
        return new BoundedBuilder(maxWireSafePayload(maxMessageSize));
    }

    public static class SerializedBlock {
        private final Block block;
        private final int height;
        private final byte[] blockBytes;

        SerializedBlock(Block block, int height, byte[] blockBytes) {
            if (blockBytes == null)
                throw new IllegalArgumentException("Serialized block bytes must not be null");

            this.block = block;
            this.height = height;
            this.blockBytes = blockBytes;
        }

        public static SerializedBlock fromBlock(Block block) throws TransformationException {
            return new SerializedBlock(block, block.getBlockData().getHeight(), BlockTransformer.toBytesV2(block));
        }

        public Block getBlock() {
            return this.block;
        }

        public int getHeight() {
            return this.height;
        }

        public int getPayloadLength() {
            return BLOCK_HEIGHT_LENGTH + this.blockBytes.length;
        }
    }

    public static class BoundedBuilder {
        private final int maxPayloadLength;
        private final List<SerializedBlock> serializedBlocks = new ArrayList<>();
        private int payloadLength = BLOCK_COUNT_LENGTH;
        private boolean truncated;
        private boolean firstBlockOversized;
        private Integer firstExcludedHeight;
        private int firstExcludedPayloadLength;

        private BoundedBuilder(int maxPayloadLength) {
            this.maxPayloadLength = maxPayloadLength;
        }

        public boolean tryAdd(SerializedBlock serializedBlock) {
            int candidatePayloadLength = this.payloadLength + serializedBlock.getPayloadLength();

            if (candidatePayloadLength > this.maxPayloadLength) {
                this.truncated = true;
                this.firstBlockOversized = this.serializedBlocks.isEmpty();
                this.firstExcludedHeight = serializedBlock.getHeight();
                this.firstExcludedPayloadLength = serializedBlock.getPayloadLength();
                return false;
            }

            this.serializedBlocks.add(serializedBlock);
            this.payloadLength = candidatePayloadLength;
            return true;
        }

        public BoundedBuildResult build() throws IOException {
            List<Block> blocks = new ArrayList<>(this.serializedBlocks.size());
            for (SerializedBlock serializedBlock : this.serializedBlocks)
                blocks.add(serializedBlock.getBlock());

            byte[] dataBytes = toDataBytes(this.serializedBlocks);
            return new BoundedBuildResult(
                    new BlocksMessage(blocks, dataBytes),
                    this.payloadLength,
                    this.truncated,
                    this.firstBlockOversized,
                    this.firstExcludedHeight,
                    this.firstExcludedPayloadLength);
        }
    }

    public static class BoundedBuildResult {
        private final BlocksMessage message;
        private final int payloadLength;
        private final boolean truncated;
        private final boolean firstBlockOversized;
        private final Integer firstExcludedHeight;
        private final int firstExcludedPayloadLength;

        private BoundedBuildResult(BlocksMessage message, int payloadLength, boolean truncated, boolean firstBlockOversized,
                                   Integer firstExcludedHeight, int firstExcludedPayloadLength) {
            this.message = message;
            this.payloadLength = payloadLength;
            this.truncated = truncated;
            this.firstBlockOversized = firstBlockOversized;
            this.firstExcludedHeight = firstExcludedHeight;
            this.firstExcludedPayloadLength = firstExcludedPayloadLength;
        }

        public BlocksMessage getMessage() {
            return this.message;
        }

        public int getPayloadLength() {
            return this.payloadLength;
        }

        public int getBlockCount() {
            return this.message.getBlocks().size();
        }

        public boolean isTruncated() {
            return this.truncated;
        }

        public boolean isFirstBlockOversized() {
            return this.firstBlockOversized;
        }

        public Integer getFirstExcludedHeight() {
            return this.firstExcludedHeight;
        }

        public int getFirstExcludedPayloadLength() {
            return this.firstExcludedPayloadLength;
        }
    }

    public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {

        int count = bytes.getInt();
        List<Block> blocks = new ArrayList<>();

        for (int i = 0; i < count; ++i) {
            int height = bytes.getInt();

            try {
                BlockTransformation blockTransformation = BlockTransformer.fromByteBufferV2(bytes);
                BlockData blockData = blockTransformation.getBlockData();
                blockData.setHeight(height);

                // We are unable to obtain a valid Repository instance here, so set it to null and we will attach it later
                Block block = new Block(null, blockData, blockTransformation.getTransactions(), blockTransformation.getAtStatesHash());
                blocks.add(block);

            } catch (TransformationException e) {
                LOGGER.warn(String.format("Received garbled BLOCKS message: %s", e.getMessage()));
                return null;
            }

        }

        return new BlocksMessage(id, blocks);
    }

    private static byte[] toDataBytes(List<SerializedBlock> serializedBlocks) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        bytes.write(Ints.toByteArray(serializedBlocks.size()));

        for (SerializedBlock serializedBlock : serializedBlocks) {
            bytes.write(Ints.toByteArray(serializedBlock.height));
            bytes.write(serializedBlock.blockBytes);
        }
        LOGGER.trace(String.format("Total length of %d blocks is %d bytes", serializedBlocks.size(), bytes.size()));

        return bytes.toByteArray();
    }

    private void setChecksum() {
        if (this.dataBytes.length > 0)
            this.checksumBytes = Message.generateChecksum(this.dataBytes);
        else
            this.checksumBytes = null;
    }

}
