package org.qortium.test.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.Before;
import org.junit.Test;
import org.qortium.block.Block;
import org.qortium.block.BlockChain;
import org.qortium.controller.BlockMinter;
import org.qortium.controller.OnlineAccountsManager;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OnlineAccountsTests extends Common {

    static {
        // This must go before any calls to LogManager/Logger
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");

        Security.insertProviderAt(new BouncyCastleProvider(), 0);
        Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);
    }

    @Before
    public void beforeTest() throws DataException, IOException {
        Common.useSettingsAndDb("test-settings-v2.json", false);
        NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
    }


    @Test
    public void testOnlineAccountsModulusBaseline() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            assertEquals(10 * 60 * 1000L, OnlineAccountsManager.getOnlineTimestampModulus());

            List<String> onlineAccountSignatures = new ArrayList<>();
            long fakeNTPOffset = 0L;

            // Mint a block and store its timestamp
            Block block = BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));
            long lastBlockTimestamp = block.getBlockData().getTimestamp();

            // Mint some blocks and keep track of the different online account signatures
            for (int i = 0; i < 30; i++) {
                block = BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));

                // Increase NTP fixed offset by the block time, to simulate time passing
                long blockTimeDelta = block.getBlockData().getTimestamp() - lastBlockTimestamp;
                lastBlockTimestamp = block.getBlockData().getTimestamp();
                fakeNTPOffset += blockTimeDelta;
                NTP.setFixedOffset(fakeNTPOffset);

                String lastOnlineAccountSignatures58 = Base58.encode(block.getBlockData().getOnlineAccountsSignatures());
                if (!onlineAccountSignatures.contains(lastOnlineAccountSignatures58)) {
                    onlineAccountSignatures.add(lastOnlineAccountSignatures58);
                }
            }

            // With the 10 minute baseline modulus and 1 minute-ish blocks, 30 blocks should span
            // multiple online-account windows and therefore produce more than one unique signature set.
            System.out.println(String.format("onlineAccountSignatures count: %d", onlineAccountSignatures.size()));
            assertTrue(onlineAccountSignatures.size() >= 2);
        }
    }

    @Test
    public void testOnlineAccountsSignatureV2BlockRoundTrip() throws DataException, IllegalAccessException {
        // Activate the secure per-account Ed25519 signature scheme (c-01) from height 1 for this test, so
        // minting exercises the V2 block-creation path and mintTestingBlock's validation exercises the V2
        // per-account verification path. A broken create/validate round-trip would fail here.
        long previousHeight = setOnlineAccountsSignatureV2Height(1L);
        try (final Repository repository = RepositoryManager.getRepository()) {
            for (int i = 0; i < 5; i++) {
                Block block = BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));
                assertNotNull("V2 online-accounts block should mint and validate", block);

                // The dynamic signature count must parse the V2 byte layout without throwing.
                assertTrue("V2 block should carry at least one online-account signature",
                        block.getBlockData().getOnlineAccountsSignaturesCount() >= 1);
            }
        } finally {
            setOnlineAccountsSignatureV2Height(previousHeight);
        }
    }

    private static long setOnlineAccountsSignatureV2Height(long height) throws IllegalAccessException {
        BlockChain blockChain = BlockChain.getInstance();
        long previous = (long) FieldUtils.readField(blockChain, "onlineAccountsSignatureV2Height", true);
        FieldUtils.writeField(blockChain, "onlineAccountsSignatureV2Height", height, true);
        return previous;
    }
}
