package org.qortium.crosschain.monero;

import java.util.*;
import static org.qortium.crosschain.monero.MoneroSendContracts.*;

/** Worker-owned integration; admission itself is executed under the service's owner/lifecycle monitor. */
final class MoneroSendCoordinator {
    final MoneroSendMachine machine;
    private final MoneroSendBackend backend;
    MoneroSendCoordinator(MoneroSendBackend backend, String session) {
        this.backend = backend;
        machine = backend.sendMachine(session);
    }
    void reconcile(String session) throws Exception {
        var receipt = machine.beginReconciliation(session);
        try {
            Map<String, String> hashes = new LinkedHashMap<>();
            backend.journal().read().entries().forEach((id, entry) -> {
                if (entry.candidate() != null && entry.state() != State.PREPARED)
                    hashes.put(id, entry.candidate().txid());
            });
            machine.reconcile(receipt, backend.observe(hashes), session);
        } catch (Exception | LinkageError e) {
            try { machine.abandonReconciliation(receipt, session); } catch (RuntimeException ignored) { }
            throw e;
        }
    }
    void validateCommit(String id, String session) throws Exception {
        var entry = backend.journal().read().entries().get(id);
        machine.status(id, session);
        if (entry != null && entry.state() == State.PREPARED) backend.validateRelay(entry.candidate());
    }
    View finishPreparation(MoneroSendMachine.Admission admission, Request request, Runnable beforePublish) {
        if (admission.work() == null) return admission.view();
        Candidate candidate = null;
        try { candidate = backend.prepare(request); }
        catch (Exception e) { /* relay=false: an ordinary native rejection cannot have broadcast */ }
        beforePublish.run();
        return machine.finishPreparation(admission.work(), candidate);
    }
    View finishRelay(MoneroSendMachine.Admission admission, Runnable beforePublish) {
        if (admission.work() == null) return admission.view();
        Candidate candidate = machine.takeRelay(admission.work());
        String hash = null;
        try { hash = backend.relay(candidate); }
        catch (Exception e) { /* already claimed: never infer retry safety, including a lost response */ }
        beforePublish.run();
        return machine.finishRelay(admission.work(), hash);
    }
}
