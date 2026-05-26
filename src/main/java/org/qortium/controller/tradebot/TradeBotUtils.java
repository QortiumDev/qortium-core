package org.qortium.controller.tradebot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.resource.CrossChainUtils;
import org.qortium.crosschain.ACCT;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyHTLC;
import org.qortium.crosschain.BitcoinySignedTransaction;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crypto.Crypto;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.crosschain.TradeBotData;
import org.qortium.data.transaction.MessageTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.transaction.MessageTransaction;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;
import org.qortium.transaction.Transaction.ValidationResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.qortium.controller.tradebot.TradeStates.State;

public class TradeBotUtils {

    private static final Logger LOGGER = LogManager.getLogger(TradeBotUtils.class);
    /**
     * Creates trade-bot entries from the 'taker' viewpoint, i.e. matching Bitcoiny coin to existing offers.
     * <p>
     * Requires chosen trade offers from maker, passed by <tt>crossChainTradeData</tt>
     * and access to a Blockchain wallet via <tt>foreignKey</tt>.
     * <p>
     * The <tt>crossChainTradeData</tt> contains the current trade offers state
     * as extracted from the AT's data segment.
     * <p>
     * Access to a funded wallet is via a Blockchain BIP32 hierarchical deterministic key,
     * passed via <tt>foreignKey</tt>.
     * <b>This key will be stored in your node's database</b>
     * to allow trade-bot to create/fund the necessary P2SH transactions!
     * However, due to the nature of BIP32 keys, it is possible to give the trade-bot
     * only a subset of wallet access (see BIP32 for more details).
     * <p>
     * As an example, the foreignKey can be extract from a <i>legacy, password-less</i>
     * Electrum wallet by going to the console tab and entering:<br>
     * <tt>wallet.keystore.xprv</tt><br>
     * which should result in a base58 string starting with either 'xprv' (for Blockchain main-net)
     * or 'tprv' for (Blockchain test-net).
     * <p>
     * It is envisaged that the value in <tt>foreignKey</tt> will actually come from a local-chain UI-managed wallet.
     * <p>
     * If sufficient funds are available, <b>this method will actually fund the P2SH-A</b>
     * with the Blockchain amount expected by 'maker'.
     * <p>
     * If the Blockchain transaction is successfully broadcast to the network then
     * we also send a MESSAGE to maker's trade-bot to let them know; one message for each trade.
     * <p>
     * The trade-bot entries are saved to the repository and the cross-chain trading process commences.
     * <p>
     *
     * @param repository for backing up the trade bot data
     * @param crossChainTradeDataList chosen trade OFFERs that taker wants to match
     * @param receiveAddress taker's local-chain address
     * @param foreignKey              funded wallet xprv in base58
     * @param bitcoiny the bitcoiny chain to match the sell offer with
     * @return true if P2SH-A funding transaction successfully broadcast to Blockchain network, false otherwise
     * @throws DataException
     */
    public static AcctTradeBot.ResponseResult startResponseMultiple(
            Repository repository,
            ACCT acct,
            List<CrossChainTradeData> crossChainTradeDataList,
            String receiveAddress,
            String foreignKey,
            Bitcoiny bitcoiny) throws DataException {

        // Check we have enough funds via foreignKey to fund P2SH to cover expectedForeignAmount
        long now = NTP.getTime();
        long p2shFee;
        try {
            p2shFee = bitcoiny.getP2shFee(now);
        } catch (ForeignBlockchainException e) {
            LOGGER.debug("Couldn't estimate blockchain transaction fees?");
            return AcctTradeBot.ResponseResult.NETWORK_ISSUE;
        }

        Map<String, Long> valueByP2shAddress = new HashMap<>(crossChainTradeDataList.size());

        class DataCombiner{
            CrossChainTradeData crossChainTradeData;
            TradeBotData tradeBotData;
            String p2shAddress;

            public DataCombiner(CrossChainTradeData crossChainTradeData, TradeBotData tradeBotData, String p2shAddress) {
                this.crossChainTradeData = crossChainTradeData;
                this.tradeBotData = tradeBotData;
                this.p2shAddress = p2shAddress;
            }
        }

        List<DataCombiner> dataToProcess = new ArrayList<>();

        for(CrossChainTradeData crossChainTradeData : crossChainTradeDataList) {
            byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
            byte[] secretA = TradeBot.generateSecret();
            byte[] hashOfSecretA = Crypto.hash160(secretA);

            byte[] tradeLocalPublicKey = TradeBot.deriveTradeLocalPublicKey(tradePrivateKey);
            byte[] tradeLocalPublicKeyHash = Crypto.hash160(tradeLocalPublicKey);
            String tradeLocalAddress = Crypto.toAddress(tradeLocalPublicKey);

            byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
            byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);
            // We need to generate lockTime-A: add tradeTimeout to now
            int lockTimeA = (crossChainTradeData.tradeTimeout * 60) + (int) (now / 1000L);
            byte[] receivingPublicKeyHash = Base58.decode(receiveAddress); // Actually the whole address, not just PKH

	            TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, acct.getClass().getSimpleName(),
	                    State.TAKER_WAITING_FOR_AT_LOCK.name(), State.TAKER_WAITING_FOR_AT_LOCK.value,
	                    receiveAddress,
	                    crossChainTradeData.atAddress,
	                    now,
	                    crossChainTradeData.localAssetId,
	                    crossChainTradeData.localAmount,
	                    tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress,
                    secretA, hashOfSecretA,
                    crossChainTradeData.foreignBlockchain,
                    tradeForeignPublicKey, tradeForeignPublicKeyHash,
                    crossChainTradeData.expectedForeignAmount,
                    foreignKey, null, lockTimeA, receivingPublicKeyHash);

            // Attempt to backup the trade bot data
            // Include tradeBotData as an additional parameter, since it's not in the repository yet
            TradeBot.backupTradeBotData(repository, Arrays.asList(tradeBotData));

            // Fee for redeem/refund is subtracted from P2SH-A balance.
            // Do not include fee for funding transaction as this is covered by buildSpend()
            long amountA = crossChainTradeData.expectedForeignAmount + p2shFee /*redeeming/refunding P2SH-A*/;

            // P2SH-A to be funded
            byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(tradeForeignPublicKeyHash, lockTimeA, crossChainTradeData.creatorForeignPKH, hashOfSecretA);
            String p2shAddress = bitcoiny.deriveP2shAddress(redeemScriptBytes);

            valueByP2shAddress.put(p2shAddress, amountA);

            dataToProcess.add(new DataCombiner(crossChainTradeData, tradeBotData, p2shAddress));
        }

        // Build transaction for funding P2SH-A
        BitcoinySignedTransaction p2shFundingTransaction = bitcoiny.buildSpendMultipleTransaction(foreignKey, valueByP2shAddress, null);
        if (p2shFundingTransaction == null) {
            LOGGER.debug("Unable to build P2SH-A funding transaction - lack of funds?");
            return AcctTradeBot.ResponseResult.BALANCE_ISSUE;
        }

        try {
            bitcoiny.broadcastTransaction(p2shFundingTransaction);
        } catch (ForeignBlockchainException e) {
            // We couldn't fund P2SH-A at this time
            LOGGER.debug("Couldn't broadcast P2SH-A funding transaction?");
            return AcctTradeBot.ResponseResult.NETWORK_ISSUE;
        }

        for(DataCombiner datumToProcess : dataToProcess ) {
            // Attempt to send MESSAGE to maker's local-chain trade address
            TradeBotData tradeBotData = datumToProcess.tradeBotData;

            byte[] messageData = CrossChainUtils.buildOfferMessage(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getHashOfSecret(), tradeBotData.getLockTimeA());
            CrossChainTradeData crossChainTradeData = datumToProcess.crossChainTradeData;
            String messageRecipient = crossChainTradeData.creatorTradeAddress;

            boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeLocalPublicKey(), messageRecipient, messageData);
            if (!isMessageAlreadySent) {
                // Do this in a new thread so caller doesn't have to wait for computeNonce()
                // In the unlikely event that the transaction doesn't validate then the buy won't happen and eventually taker's AT will be refunded
                new Thread(() -> {
                    try (final Repository threadsRepository = RepositoryManager.getRepository()) {
                        PrivateKeyAccount sender = new PrivateKeyAccount(threadsRepository, tradeBotData.getTradePrivateKey());
                        MessageTransaction messageTransaction = MessageTransaction.build(threadsRepository, sender, Group.NO_GROUP, messageRecipient, messageData, false, false);

                        LOGGER.info("Computing nonce at difficulty {} for AT {} and recipient {}", messageTransaction.getPoWDifficulty(), tradeBotData.getAtAddress(), messageRecipient);
                        messageTransaction.computeNonce();
                        MessageTransactionData newMessageTransactionData = (MessageTransactionData) messageTransaction.getTransactionData();
                        LOGGER.info("Computed nonce {} at difficulty {}", newMessageTransactionData.getNonce(), messageTransaction.getPoWDifficulty());
                        messageTransaction.sign(sender);

                        // reset repository state to prevent deadlock
                        threadsRepository.discardChanges();

                        if (messageTransaction.isSignatureValid()) {
                            ValidationResult result = messageTransaction.importAsUnconfirmed();

                            if (result != ValidationResult.OK) {
                                LOGGER.warn(() -> String.format("Unable to send MESSAGE to maker's trade-bot %s: %s", messageRecipient, result.name()));
                            }
                        } else {
                            LOGGER.warn(() -> String.format("Unable to send MESSAGE to maker's trade-bot %s: signature invalid", messageRecipient));
                        }
                    } catch (DataException e) {
                        LOGGER.warn(() -> String.format("Unable to send MESSAGE to maker's trade-bot %s: %s", messageRecipient, e.getMessage()));
                    }
                }, "TradeBot response").start();
            }

            TradeBot.updateTradeBotState(repository, tradeBotData, () -> String.format("Funding P2SH-A %s. Messaged maker. Waiting for AT-lock", datumToProcess.p2shAddress));
        }

        return AcctTradeBot.ResponseResult.OK;
    }
}
