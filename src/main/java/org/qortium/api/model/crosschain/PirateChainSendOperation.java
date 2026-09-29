package org.qortium.api.model.crosschain;

import org.qortium.crosschain.PirateChainSendJournal;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** A durable operation, never a claim that a payment is confirmed. No recipient, memo or keys. */
@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainSendOperation {
    public String operationId, idempotencyKey, walletIdentityHash, network, state, txid, reason;
    public String feeAtomic = "10000", feePolicy = "FIXED";
    public int sendProtocolVersion = 2;
    public boolean resolutionRequired;
    public long createdAt, updatedAt;
    public PirateChainSendOperation() { }
    public PirateChainSendOperation(PirateChainSendJournal.Operation op) {
        operationId = op.operationId(); idempotencyKey = op.idempotencyKey(); walletIdentityHash = op.walletIdentityHash(); network = op.network();
        state = op.phase().name(); txid = op.txid(); reason = op.reason(); createdAt = op.createdAt(); updatedAt = op.updatedAt();
        resolutionRequired = op.phase() == PirateChainSendJournal.Phase.UNRESOLVED;
    }
}
