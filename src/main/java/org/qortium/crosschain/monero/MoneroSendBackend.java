package org.qortium.crosschain.monero;

import java.util.Map;
import static org.qortium.crosschain.monero.MoneroSendContracts.*;

/** Internal native seam. Every method is confined to the service's single worker. */
interface MoneroSendBackend extends MoneroWalletBackend {
    MoneroSendJournal journal();
    default MoneroSendMachine sendMachine(String session) {
        return new MoneroSendMachine(journal(), session, System::currentTimeMillis, System::nanoTime);
    }
    Candidate prepare(Request request) throws Exception;
    void validateRelay(Candidate candidate) throws Exception;
    String relay(Candidate candidate) throws Exception;
    // Ordinary untrusted sync followed by exact local-wallet lookup, never scanTxs or UI history.
    Map<String, MoneroSendMachine.Observation> observe(Map<String, String> operationHashes) throws Exception;
}
