package org.qortium.api.model.crosschain;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainSendReadiness {
    public int sendProtocolVersion = 2;
    public boolean sendAllowed;
    public String network, blockingOperationId, reason;
}
