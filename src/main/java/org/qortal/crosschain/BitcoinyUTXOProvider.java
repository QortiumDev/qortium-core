package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;

import java.util.ArrayList;
import java.util.List;

/**
 * Class BitcoinyUTXOProvider
 *
 * Uses Bitcoiny resources for UTXO provision.
 */
public class BitcoinyUTXOProvider implements UTXOProvider {

    private Bitcoiny bitcoiny;

    public BitcoinyUTXOProvider(Bitcoiny bitcoiny) {
        this.bitcoiny = bitcoiny;
    }

    @Override
    public List<UTXO> getOpenTransactionOutputs(List<ECKey> keys) throws UTXOProviderException {
        try {
            List<UTXO> utxos = new ArrayList<>();

            for( ECKey key : keys) {
                byte[] script = BitcoinyScript.p2pkhScript(key.getPubKeyHash());

                // collection UTXO's for all confirmed unspent outputs
                for (UnspentOutput output : this.bitcoiny.blockchainProvider.getUnspentOutputs(script, true)) {
                    utxos.add(toUTXO(output));
                }
            }
            return utxos;
        } catch (ForeignBlockchainException e) {
            throw new UTXOProviderException(e);
        }
    }

    /**
     * Convert Unspent Output to a UTXO
     *
     * @param unspentOutput
     *
     * @return the UTXO
     *
     * @throws ForeignBlockchainException
     */
    private UTXO toUTXO(UnspentOutput unspentOutput) throws ForeignBlockchainException {
        byte[] scriptPubKeyBytes = unspentOutput.script;

        if (scriptPubKeyBytes == null) {
            List<BitcoinyTransaction.Output> transactionOutputs = this.bitcoiny.getOutputs(unspentOutput.hash);
            BitcoinyTransaction.Output transactionOutput = transactionOutputs.get(unspentOutput.index);
            scriptPubKeyBytes = HashCode.fromString(transactionOutput.scriptPubKey).asBytes();
        }

        Script scriptPubKey = new Script(scriptPubKeyBytes);

        return new UTXO(
                        Sha256Hash.wrap(unspentOutput.hash),
                        unspentOutput.index,
                        Coin.valueOf(unspentOutput.value),
                        unspentOutput.height,
                false,
                        scriptPubKey
        );
    }

    @Override
    public int getChainHeadHeight() throws UTXOProviderException {
        try {
            return this.bitcoiny.blockchainProvider.getCurrentHeight();
        } catch (ForeignBlockchainException e) {
            throw new UTXOProviderException(e);
        }
    }

    @Override
    public NetworkParameters getParams() {
        return this.bitcoiny.params;
    }
}
