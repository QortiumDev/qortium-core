package org.qortium.controller.arbitrary;

import org.junit.Test;
import org.qortium.arbitrary.ArbitraryDataResource;
import org.qortium.arbitrary.misc.Service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArbitraryDataRenderManagerTests {

    private static final String NAME = "QortiumHomeTest";

    @Test
    public void testIdentifierAuthorizationIsExact() {
        ArbitraryDataRenderManager manager = new ArbitraryDataRenderManager();

        manager.addToAuthorizedResources(new ArbitraryDataResource(NAME, null, Service.APP, "qortium-chat"));

        assertTrue(manager.isAuthorized(new ArbitraryDataResource(NAME, null, Service.APP, "qortium-chat")));
        assertFalse(manager.isAuthorized(new ArbitraryDataResource(NAME, null, Service.APP, "other-app")));
    }

    @Test
    public void testServiceNameAuthorizationCoversServiceIdentifiers() {
        ArbitraryDataRenderManager manager = new ArbitraryDataRenderManager();

        manager.addToAuthorizedResources(new ArbitraryDataResource(NAME, null, Service.APP, null));

        assertTrue(manager.isAuthorized(new ArbitraryDataResource(NAME, null, Service.APP, "qortium-chat")));
        assertTrue(manager.isAuthorized(new ArbitraryDataResource(NAME, null, Service.APP, "other-app")));
        assertFalse(manager.isAuthorized(new ArbitraryDataResource(NAME, null, Service.WEBSITE, "qortium-chat")));
    }

    @Test
    public void testNameAuthorizationCoversServicesAndIdentifiers() {
        ArbitraryDataRenderManager manager = new ArbitraryDataRenderManager();

        manager.addToAuthorizedResources(new ArbitraryDataResource(NAME, null, null, null));

        assertTrue(manager.isAuthorized(new ArbitraryDataResource(NAME, null, Service.APP, "qortium-chat")));
        assertTrue(manager.isAuthorized(new ArbitraryDataResource(NAME, null, Service.WEBSITE, "qortium-chat")));
    }

    @Test
    public void testNullResourceIsNotAuthorized() {
        ArbitraryDataRenderManager manager = new ArbitraryDataRenderManager();

        manager.addToAuthorizedResources(new ArbitraryDataResource(NAME, null, Service.APP, null));

        assertFalse(manager.isAuthorized(null));
    }
}
