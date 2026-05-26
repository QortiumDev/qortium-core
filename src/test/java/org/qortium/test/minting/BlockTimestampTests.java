package org.qortium.test.minting;

import org.junit.Before;
import org.junit.Test;
import org.qortium.block.Block;
import org.qortium.data.block.BlockData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.transform.Transformer;
import org.qortium.utils.NTP;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BlockTimestampTests extends Common {

    private static class BlockTimestampDataPoint {
        public byte[] minterPublicKey;
        public int minterAccountLevel;
        public long blockTimestamp;
    }

    private static final Random RANDOM = new Random();

    @Before
    public void beforeTest() throws DataException {
        Common.useSettings("test-settings-v2-block-timestamps.json");
        NTP.setFixedOffset(0L);
    }

    @Test
    public void testTimestamps() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            Block parentBlock = BlockUtils.mintBlock(repository);
            BlockData parentBlockData = parentBlock.getBlockData();

            // Generate lots of test minters
            List<BlockTimestampDataPoint> dataPoints = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                BlockTimestampDataPoint dataPoint = new BlockTimestampDataPoint();

                dataPoint.minterPublicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
                RANDOM.nextBytes(dataPoint.minterPublicKey);

                dataPoint.minterAccountLevel = RANDOM.nextInt(5) + 5;

                dataPoint.blockTimestamp = Block.calcTimestamp(parentBlockData, dataPoint.minterPublicKey, dataPoint.minterAccountLevel);

                System.out.printf("[%d] level %d, blockTimestamp %d - parentTimestamp %d = %d%n",
                        i,
                        dataPoint.minterAccountLevel,
                        dataPoint.blockTimestamp,
                        parentBlockData.getTimestamp(),
                        dataPoint.blockTimestamp - parentBlockData.getTimestamp()
                );
            }
        }
    }
}
