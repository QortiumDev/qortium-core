package org.qortium.test.common;

import org.ciyam.at.CompilationException;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.DeployAtTransaction;

import java.nio.ByteBuffer;

public class AtUtils {

    public static byte[] buildSimpleAT() {
        return buildSimpleAT((short) 2);
    }

    /**
     * Builds the same trivial AT as {@link #buildSimpleAT()} but with a caller-chosen creation
     * version, so tests can exercise the deployment gates that key on creation version.
     */
    public static byte[] buildSimpleAT(short ciyamAtVersion) {
        // Pretend we use 4 values in data segment
        int addrCounter = 4;

        // Data segment
        ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);

        ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);

        // Two-pass version
        for (int pass = 0; pass < 2; ++pass) {
            codeByteBuffer.clear();

            try {
                // Stop and wait for next block
                codeByteBuffer.put(OpCode.STP_IMD.compile());
            } catch (CompilationException e) {
                throw new IllegalStateException("Unable to compile AT?", e);
            }
        }

        codeByteBuffer.flip();

        byte[] codeBytes = new byte[codeByteBuffer.limit()];
        codeByteBuffer.get(codeBytes);

        final short numCallStackPages = 0;
        final short numUserStackPages = 0;
        final long minActivationAmount = 0L;

        return MachineState.toCreationBytes(ciyamAtVersion, codeBytes, dataByteBuffer.array(), numCallStackPages, numUserStackPages, minActivationAmount);
    }

    public static DeployAtTransaction doDeployAT(Repository repository, PrivateKeyAccount deployer, byte[] creationBytes, long fundingAmount) throws DataException {
        return doDeployAT(repository, deployer, creationBytes, fundingAmount, Asset.NATIVE, 0L);
    }

    public static DeployAtTransaction doDeployAT(Repository repository, PrivateKeyAccount deployer, byte[] creationBytes, long fundingAmount, long assetId, long nativeFeeReserve) throws DataException {
        long txTimestamp = System.currentTimeMillis();

        Long fee = null;
        String name = "Test AT";
        String description = "Test AT";
        String atType = "Test";
        String tags = "TEST";

        BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, deployer.getPublicKey(), fee, null);
        TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, atType, tags, creationBytes, fundingAmount, assetId, nativeFeeReserve);

        DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);

        fee = deployAtTransaction.calcRecommendedFee();
        deployAtTransactionData.setFee(fee);

        TransactionUtils.signAndMint(repository, deployAtTransactionData, deployer);

        return deployAtTransaction;
    }
}
