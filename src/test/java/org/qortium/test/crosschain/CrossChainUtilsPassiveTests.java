package org.qortium.test.crosschain;

import org.bitcoinj.base.Coin;
import org.junit.Assert;
import org.junit.Test;
import org.qortium.api.resource.CrossChainUtils;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyBlockchainProvider;
import org.qortium.crosschain.ServerConfigurationInfo;
import org.qortium.test.common.Common;

public class CrossChainUtilsPassiveTests extends Common {

    @Test
    public void testBuildServerConfigurationInfoDoesNotInitializeProvider() {
        PassiveProvider provider = new PassiveProvider();

        ServerConfigurationInfo info = CrossChainUtils.buildServerConfigurationInfo(new PassiveBitcoiny(provider));

        Assert.assertEquals(0, provider.getCurrentHeightCallCount());
        Assert.assertTrue(info.getServers().isEmpty());
        Assert.assertTrue(info.getRemainingServers().isEmpty());
        Assert.assertTrue(info.getUselessServers().isEmpty());
    }

    private static class PassiveBitcoiny extends Bitcoiny {
        private PassiveBitcoiny(BitcoinyBlockchainProvider provider) {
            super(provider, null, null, "TEST", Coin.ZERO);
        }

        @Override
        public long getP2shFee(Long timestamp) {
            return 0;
        }

        @Override
        public long getFeeRequired() {
            return 0;
        }

        @Override
        public void setFeeRequired(long fee) {
        }
    }

    private static class PassiveProvider extends MockBitcoinyBlockchainProvider {
        private int currentHeightCallCount;

        private PassiveProvider() {
            super("passive-test");
        }

        private int getCurrentHeightCallCount() {
            return this.currentHeightCallCount;
        }

        @Override
        public int getCurrentHeight() {
            ++this.currentHeightCallCount;
            throw new AssertionError("A server-information read must not initialize foreign-chain networking");
        }
    }
}
