package org.qortium.api.gateway.resource;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortium.arbitrary.ArbitraryDataReader;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.arbitrary.ArbitraryDataManager;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.utils.Base58;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the gateway's bare "/{service}/{name}" -> sole non-default identifier
 * resolution (GatewayResource#resolveBareIdentifierIfUnambiguous and its use in #parsePath).
 */
public class GatewayResourceBareIdentifierTests extends Common {

    private static final String ASSET_MARKER = "window.assetMarker = 1;";

    @Before
    public void beforeTest() throws DataException, IllegalAccessException {
        Common.useDefaultSettings();
        // Keep PoW cheap for minted arbitrary transactions
        FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);
    }

    // --- Case handling -----------------------------------------------------------------

    @Test
    public void testParseServiceIsCaseInsensitive() throws Exception {
        java.lang.reflect.Method parseService = GatewayResource.class.getDeclaredMethod("parseService", String.class);
        parseService.setAccessible(true);

        assertEquals(Service.APP, parseService.invoke(null, "APP"));
        assertEquals(Service.APP, parseService.invoke(null, "app"));
        assertEquals(Service.APP, parseService.invoke(null, "App"));
        assertNull(parseService.invoke(null, "not-a-real-service"));
    }

    // --- resolveBareIdentifierIfUnambiguous: default precedence, singleton, ambiguity ---

    @Test
    public void testDefaultResourceTakesPrecedenceOverSingletonIdentifier() throws Exception {
        try (final Repository repository = RepositoryManager.getRepository()) {
            String name = registerName(repository, "alice");
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());

            // Publish both a default resource and a single non-default identifier
            publishWebsite(repository, publicKey58, name, null, "default-content", alice);
            publishWebsite(repository, publicKey58, name, "only-id", "only-id-content", alice);

            GatewayResource gateway = new GatewayResource();
            String resolved = gateway.resolveBareIdentifierIfUnambiguous(Service.WEBSITE, name);

            assertNull("Default resource exists, so bare service/name must not be redirected to another identifier", resolved);
        }
    }

    @Test
    public void testSingletonNonDefaultIdentifierIsResolvedWhenNoDefaultExists() throws Exception {
        try (final Repository repository = RepositoryManager.getRepository()) {
            String name = registerName(repository, "alice");
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());

            // No default resource - only a single non-default identifier
            publishWebsite(repository, publicKey58, name, "only-id", "only-id-content", alice);

            GatewayResource gateway = new GatewayResource();
            String resolved = gateway.resolveBareIdentifierIfUnambiguous(Service.WEBSITE, name);

            assertEquals("only-id", resolved);
        }
    }

    @Test
    public void testSingletonIdentifierResolvesForDifferentlyCasedName() throws Exception {
        try (final Repository repository = RepositoryManager.getRepository()) {
            String name = registerName(repository, "alice");
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());

            publishWebsite(repository, publicKey58, name, "only-id", "only-id-content", alice);

            GatewayResource gateway = new GatewayResource();
            String resolved = gateway.resolveBareIdentifierIfUnambiguous(Service.WEBSITE, name.toLowerCase());

            assertEquals("only-id", resolved);
        }
    }

    @Test
    public void testAmbiguousNonDefaultIdentifiersAreNeverGuessed() throws Exception {
        try (final Repository repository = RepositoryManager.getRepository()) {
            String name = registerName(repository, "alice");
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());

            // No default resource - two non-default identifiers, so it's ambiguous
            publishWebsite(repository, publicKey58, name, "id-one", "id-one-content", alice);
            publishWebsite(repository, publicKey58, name, "id-two", "id-two-content", alice);

            GatewayResource gateway = new GatewayResource();
            String resolved = gateway.resolveBareIdentifierIfUnambiguous(Service.WEBSITE, name);

            assertNull("Two non-default identifiers exist, so the gateway must never guess", resolved);
        }
    }

    @Test
    public void testNoMatchesResolvesToNull() throws Exception {
        try (final Repository repository = RepositoryManager.getRepository()) {
            String name = registerName(repository, "alice");

            // Name registered, but nothing has been published under it
            GatewayResource gateway = new GatewayResource();
            String resolved = gateway.resolveBareIdentifierIfUnambiguous(Service.WEBSITE, name);

            assertNull(resolved);
        }
    }

    // --- Full parsePath() flow: singleton resolution + path collision --------------------

    @Test
    public void testGatewaySelectsSingletonIdentifierForBareServiceAndName() throws Exception {
        try (final Repository repository = RepositoryManager.getRepository()) {
            String name = registerName(repository, "alice");
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());

            publishWebsite(repository, publicKey58, name, "only-id", "only-id-content", alice);

            GatewayResource gateway = new GatewayResource();
            Exchange exchange = new Exchange();
            gateway.request = exchange.request;
            gateway.response = exchange.response;
            gateway.context = exchange.context;

            // Lower-case service segment also exercises case-insensitive service matching
            // Async rendering returns the normal loading document without depending on the
            // background cache builder. Its templated identifier proves parsePath selected the
            // singleton before it constructed the renderer.
            invokeParsePath(gateway, "website/" + name, true);

            assertTrue("Expected the loading response to target the singleton identifier; response was: " + exchange.outputStream.toString(StandardCharsets.UTF_8),
                    exchange.outputStream.toString(StandardCharsets.UTF_8).contains("var identifier = \"only-id\""));
        }
    }

    @Test
    public void testGatewayDoesNotResolveSingletonWhenAThirdPathSegmentIsPresent() throws Exception {
        try (final Repository repository = RepositoryManager.getRepository()) {
            String name = registerName(repository, "alice");
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());

            // Only one non-default identifier exists, so a bare request would resolve it...
            publishWebsite(repository, publicKey58, name, "only-id", "only-id-content", alice);

            GatewayResource gateway = new GatewayResource();
            Exchange exchange = new Exchange();
            gateway.request = exchange.request;
            gateway.response = exchange.response;
            gateway.context = exchange.context;

            // ...but here a third path segment is present and doesn't match any identifier, so it
            // must be treated as a sub-path under the (non-existent) default resource, not silently
            // redirected to the singleton identifier.
            invokeParsePath(gateway, "WEBSITE/" + name + "/some/sub/path.html", true);

            String rendered = exchange.outputStream.toString(StandardCharsets.UTF_8);
            assertTrue("A path segment that isn't a real identifier must keep the default-resource route; response was: " + rendered,
                    rendered.contains("var identifier = \"default\""));
        }
    }

    // --- Rendered base href ------------------------------------------------------------

    /**
     * The rendered base href drives every relative asset in a published app. It must be
     * "/{service}/{name}/{identifier}/": the gateway parses that same shape back, so any other
     * ordering makes each asset request miss and return 503.
     */
    @Test
    public void testExplicitIdentifierRendersNameBeforeIdentifierInBaseHref() throws Exception {
        try (final Repository repository = RepositoryManager.getRepository()) {
            String name = registerName(repository, "alice");
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());

            publishSite(repository, publicKey58, name, "only-id", alice);
            buildCache(name, "only-id");

            GatewayResource gateway = new GatewayResource();
            Exchange exchange = new Exchange();
            gateway.request = exchange.request;
            gateway.response = exchange.response;
            gateway.context = exchange.context;

            invokeParsePath(gateway, "WEBSITE/" + name + "/only-id", false);

            String rendered = exchange.outputStream.toString(StandardCharsets.UTF_8);
            assertTrue("Base href must place the name before the identifier; response was: " + rendered,
                    rendered.contains("<base href=\"/WEBSITE/" + name + "/only-id/\""));

            // Close the loop: the relative asset, resolved against that base href, must come back
            // from the gateway. A swapped base href sends this request to a resource that isn't there.
            Exchange assetExchange = new Exchange();
            gateway.request = assetExchange.request;
            gateway.response = assetExchange.response;
            gateway.context = assetExchange.context;

            invokeParsePath(gateway, "WEBSITE/" + name + "/only-id/app.js", false);

            assertTrue("The base-href-relative asset must resolve back to this resource",
                    assetExchange.outputStream.toString(StandardCharsets.UTF_8).contains(ASSET_MARKER));
        }
    }

    @Test
    public void testResolvedSingletonIdentifierFollowsNameInBaseHref() throws Exception {
        try (final Repository repository = RepositoryManager.getRepository()) {
            String name = registerName(repository, "alice");
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());

            publishSite(repository, publicKey58, name, "only-id", alice);
            buildCache(name, "only-id");

            GatewayResource gateway = new GatewayResource();
            Exchange exchange = new Exchange();
            gateway.request = exchange.request;
            gateway.response = exchange.response;
            gateway.context = exchange.context;

            // Bare "/{service}/{name}" resolves the singleton identifier, which must land in the
            // same base-href position as an explicitly requested one.
            invokeParsePath(gateway, "website/" + name, false);

            String rendered = exchange.outputStream.toString(StandardCharsets.UTF_8);
            assertTrue("A resolved singleton identifier must follow the name; response was: " + rendered,
                    rendered.contains("<base href=\"/WEBSITE/" + name + "/only-id/\""));
        }
    }

    // --- helpers ---------------------------------------------------------------------

    /** Renders synchronously only once the resource is cached, so the test never waits on a background build. */
    private static void buildCache(String name, String identifier) throws Exception {
        new ArbitraryDataReader(name, ResourceIdType.NAME, Service.WEBSITE, identifier).loadSynchronously(true);
    }

    /**
     * A site with an index and one relative asset. More than one file keeps it packaged as a
     * directory, which WEBSITE validation requires, and gives the base href something to resolve.
     */
    private static void publishSite(Repository repository, String publicKey58, String name, String identifier,
                                    PrivateKeyAccount account) throws Exception {
        Path dir = Files.createTempDirectory("gatewayBaseHrefTest");
        dir.toFile().deleteOnExit();

        Path indexFile = Paths.get(dir.toString(), "index.html");
        Files.write(indexFile, "<html><head><script src=\"app.js\"></script></head><body>site</body></html>"
                .getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
        indexFile.toFile().deleteOnExit();

        Path assetFile = Paths.get(dir.toString(), "app.js");
        Files.write(assetFile, ASSET_MARKER.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
        assetFile.toFile().deleteOnExit();

        ArbitraryUtils.createAndMintTxn(repository, publicKey58, dir, name, identifier,
                ArbitraryTransactionData.Method.PUT, Service.WEBSITE, account);
    }

    private static String registerName(Repository repository, String accountName) throws DataException {
        PrivateKeyAccount account = Common.getTestAccount(repository, accountName);
        String name = "TEST-" + ArbitraryUtils.generateRandomString(8);

        RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(account), name, "");
        transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
        TransactionUtils.signAndMint(repository, transactionData, account);

        return name;
    }

    private static void publishWebsite(Repository repository, String publicKey58, String name, String identifier,
                                       String markerContent, PrivateKeyAccount account) throws Exception {
        Path dir = Files.createTempDirectory("gatewayBareIdentifierTest");
        dir.toFile().deleteOnExit();
        Path indexFile = Paths.get(dir.toString(), "index.html");
        Files.write(indexFile, ("<html>" + markerContent + "</html>").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
        indexFile.toFile().deleteOnExit();

        ArbitraryUtils.createAndMintTxn(repository, publicKey58, dir, name, identifier,
                ArbitraryTransactionData.Method.PUT, Service.WEBSITE, account);
    }

    private static void invokeParsePath(GatewayResource gateway, String inPath, boolean async) throws Exception {
        java.lang.reflect.Method parsePath = GatewayResource.class.getDeclaredMethod("parsePath",
                String.class, String.class, String.class, boolean.class, boolean.class, String.class, String.class, String.class);
        parsePath.setAccessible(true);
        parsePath.invoke(gateway, inPath, "gateway", null, true, async, null, null, null);
    }

    /** Minimal servlet request/response/context fakes, sufficient to render a single-file HTML resource. */
    private static class Exchange {
        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final ServletContext context;

        private Exchange() {
            this.request = (HttpServletRequest) Proxy.newProxyInstance(
                    Exchange.class.getClassLoader(),
                    new Class[]{HttpServletRequest.class},
                    (proxy, method, args) -> defaultValue(method.getReturnType()));

            this.context = (ServletContext) Proxy.newProxyInstance(
                    Exchange.class.getClassLoader(),
                    new Class[]{ServletContext.class},
                    (proxy, method, args) -> {
                        if ("getMimeType".equals(method.getName())) {
                            return "text/html";
                        }
                        return defaultValue(method.getReturnType());
                    });

            this.response = (HttpServletResponse) Proxy.newProxyInstance(
                    Exchange.class.getClassLoader(),
                    new Class[]{HttpServletResponse.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getOutputStream":
                                return new javax.servlet.ServletOutputStream() {
                                    @Override
                                    public void write(int b) {
                                        outputStream.write(b);
                                    }

                                    @Override
                                    public boolean isReady() {
                                        return true;
                                    }

                                    @Override
                                    public void setWriteListener(javax.servlet.WriteListener writeListener) {
                                    }
                                };
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }
}
