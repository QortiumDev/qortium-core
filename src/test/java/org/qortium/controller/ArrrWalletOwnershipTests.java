package org.qortium.controller;

import org.junit.Test;
import org.qortium.crosschain.ForeignBlockchainException;
import static org.junit.Assert.*;

public class ArrrWalletOwnershipTests {
    @Test public void ownershipSurvivesStopButApprovalsDoNot() throws Exception {
        ArrrWalletOwnership owner = new ArrrWalletOwnership();
        assertThrows(ForeignBlockchainException.WalletBusyException.class, () -> owner.requireOwner("a"));
        owner.selected("a", "address-a");
        String revision = owner.snapshot().revision();
        owner.lifecycleChanged();
        owner.requireOwner("a");
        assertThrows(ForeignBlockchainException.WalletBusyException.class, () -> owner.requireOwner("b"));
        assertThrows(ForeignBlockchainException.class, () -> owner.requireRevision(revision));
        assertEquals("address-a", owner.address("a"));
        assertNull(owner.address("b"));
        owner.selected("b", "address-b");
        assertEquals("address-a", owner.address("a"));
        assertEquals("address-b", owner.address("b"));
        assertThrows(ForeignBlockchainException.WalletBusyException.class, () -> owner.requireOwner("a"));
    }
}
