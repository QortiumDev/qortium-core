package org.qortium.test;

import com.sun.net.httpserver.HttpServer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.controller.BlockMinter;
import org.qortium.controller.Controller;
import org.qortium.data.block.BlockData;
import org.qortium.repository.*;
import org.qortium.settings.Settings;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.Common;
import org.qortium.transform.TransformationException;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class BootstrapTests extends Common {

    private static final String RUN_LIVE_BOOTSTRAP_CHECKS_PROPERTY = "qortium.runLiveBootstrapChecks";
    private static final String LIVE_BOOTSTRAP_HOSTS_PROPERTY = "qortium.liveBootstrapHosts";
    private static final int LIVE_BOOTSTRAP_CONNECT_TIMEOUT_MS = 10_000;
    private static final int LIVE_BOOTSTRAP_READ_TIMEOUT_MS = 10_000;
    private static final long MINIMUM_BOOTSTRAP_SIZE = 100 * 1024 * 1024L;
    private static final long MAXIMUM_BOOTSTRAP_AGE = 3 * 24 * 60 * 60 * 1000L;

    @Before
    public void beforeTest() throws DataException, IOException {
        Common.useSettingsAndDb(Common.testSettingsFilename, false);
        NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
        this.deleteBootstraps();
    }

    @After
    public void afterTest() throws DataException, IOException {
        this.deleteBootstraps();
        this.deleteExportDirectory();
    }

    @Test
    public void testCheckRepositoryState() throws DataException, InterruptedException, TransformationException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            this.buildDummyBlockchain(repository);

            Bootstrap bootstrap = new Bootstrap(repository);
            assertTrue(bootstrap.checkRepositoryState());

        }
    }

    @Test
    public void testValidateBlockchain() throws DataException, InterruptedException, TransformationException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            this.buildDummyBlockchain(repository);

            Bootstrap bootstrap = new Bootstrap(repository);
            assertTrue(bootstrap.validateBlockchain());

        }
    }


    @Test
    public void testCreateAndImportBootstrap() throws DataException, InterruptedException, TransformationException, IOException {

        Path archivePath = Paths.get(Settings.getInstance().getRepositoryPath(), "archive", "2-900.dat");
        BlockData block1000;
        byte[] originalArchiveContents;

        try (final Repository repository = RepositoryManager.getRepository()) {
            this.buildDummyBlockchain(repository);

            Bootstrap bootstrap = new Bootstrap(repository);
            Path bootstrapPath = bootstrap.getBootstrapOutputPath();

            // Ensure the compressed bootstrap doesn't exist
            assertFalse(Files.exists(bootstrapPath));

            // Create bootstrap
            bootstrap.create();

            // Ensure the compressed bootstrap exists
            assertTrue(Files.exists(bootstrapPath));

            // Ensure the original block archive file exists
            assertTrue(Files.exists(archivePath));
            originalArchiveContents = Files.readAllBytes(archivePath);

            // Ensure block 1000 exists in the repository
            block1000 = repository.getBlockRepository().fromHeight(1000);
            assertNotNull(block1000);

            // Ensure we can retrieve block 10 from the archive
            assertNotNull(repository.getBlockArchiveRepository().fromHeight(10));

            // Now delete block 1000
            repository.getBlockRepository().delete(block1000);
            assertNull(repository.getBlockRepository().fromHeight(1000));

            // Overwrite the archive with dummy data, and verify it
            try (PrintWriter out = new PrintWriter(archivePath.toFile())) {
                out.println("testdata");
            }
            String newline = System.getProperty("line.separator");
            assertEquals("testdata", Files.readString(archivePath).replace(newline, ""));

            // Ensure we can no longer retrieve block 10 from the archive
            assertNull(repository.getBlockArchiveRepository().fromHeight(10));

            // Import the bootstrap back in
            bootstrap.importFromPath(bootstrapPath);
        }

        // We need a new connection because we have switched to a new repository
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Ensure the block archive file exists
            assertTrue(Files.exists(archivePath));

            // and that its contents match the original
            assertArrayEquals(originalArchiveContents, Files.readAllBytes(archivePath));

            // Make sure that block 1000 exists again
            BlockData newBlock1000 = repository.getBlockRepository().fromHeight(1000);
            assertNotNull(newBlock1000);

            // and ensure that the signatures match
            assertArrayEquals(block1000.getSignature(), newBlock1000.getSignature());

            // Ensure we can retrieve block 10 from the archive
            assertNotNull(repository.getBlockArchiveRepository().fromHeight(10));
        }
    }


    private void buildDummyBlockchain(Repository repository) throws DataException, InterruptedException, TransformationException, IOException {
        // Alice self share online
        List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();
        PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
        mintingAndOnlineAccounts.add(aliceSelfShare);

        // Deploy an AT so that we have AT state data
        PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
        byte[] creationBytes = AtUtils.buildSimpleAT();
        long fundingAmount = 1_00000000L;
        AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);

        // Mint some blocks so that we are able to archive them later
        for (int i = 0; i < 1000; i++)
            BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

        // Assume 900 blocks are trimmed (this specifies the first untrimmed height)
        repository.getBlockRepository().setOnlineAccountsSignaturesTrimHeight(901);
        repository.getATRepository().setAtTrimHeight(901);

        // Check the max archive height - this should be one less than the first untrimmed height
        final int maximumArchiveHeight = BlockArchiveWriter.getMaxArchiveHeight(repository);

        // Write blocks 2-900 to the archive
        BlockArchiveWriter writer = new BlockArchiveWriter(0, maximumArchiveHeight, repository);
        writer.setShouldEnforceFileSizeTarget(false); // To avoid the need to pre-calculate file sizes
        BlockArchiveWriter.BlockArchiveWriteResult result = writer.write();

        // Increment block archive height
        repository.getBlockArchiveRepository().setBlockArchiveHeight(901);

        // Prune all the archived blocks
        repository.getBlockRepository().pruneBlocks(0, 900);
        repository.getBlockRepository().setBlockPruneHeight(901);

        // Prune the AT states for the archived blocks
        repository.getATRepository().rebuildLatestAtStates(900);
        repository.saveChanges();
        repository.getATRepository().pruneAtStates(0, 900);
        repository.getATRepository().setAtPruneHeight(901);

        // Refill cache, used by Controller.getMinimumLatestBlockTimestamp() and other methods
        Controller.getInstance().refillLatestBlocksCache();

        repository.saveChanges();
    }

    @Test
    public void testGetRandomHostUsesConfiguredHosts() throws IllegalAccessException, DataException {
        Settings settings = Settings.getInstance();
        String[] originalBootstrapHosts = settings.getBootstrapHosts();
        List<String> uniqueHosts = new ArrayList<>();

        try {
            FieldUtils.writeField(settings, "bootstrapHosts", new String[] {
                    " https://bootstrap-one.example ",
                    "https://bootstrap-two.example"
            }, true);

            String[] bootstrapHosts = settings.getBootstrapHosts();
            for (int i = 0; i < 1000; i++) {
                Bootstrap bootstrap = new Bootstrap();
                String randomHost = bootstrap.getRandomHost();
                assertNotNull(randomHost);

                if (!uniqueHosts.contains(randomHost)) {
                    uniqueHosts.add(randomHost);
                }
            }

            // Ensure we have more than one bootstrap host in the settings
            assertTrue(Arrays.asList(bootstrapHosts).size() > 1);

            // Ensure that all have been given the opportunity to be used
            assertEquals(uniqueHosts.size(), Arrays.asList(bootstrapHosts).size());
        } finally {
            FieldUtils.writeField(settings, "bootstrapHosts", originalBootstrapHosts, true);
        }
    }

    @Test
    public void testGetRandomHostFailsWithoutConfiguredHosts() throws IllegalAccessException {
        Settings settings = Settings.getInstance();
        String[] originalBootstrapHosts = settings.getBootstrapHosts();

        try {
            FieldUtils.writeField(settings, "bootstrapHosts", new String[] {"", "  ", null}, true);

            Bootstrap bootstrap = new Bootstrap();
            try {
                bootstrap.getRandomHost();
                fail("Expected getRandomHost() to fail without configured bootstrap hosts");
            } catch (DataException e) {
                assertEquals(Bootstrap.MISSING_BOOTSTRAP_HOSTS_MESSAGE, e.getMessage());
            }
        } finally {
            FieldUtils.writeField(settings, "bootstrapHosts", originalBootstrapHosts, true);
        }
    }

    @Test
    public void testLiveBootstrapHostsPropertyOverridesSettings() {
        String originalLiveBootstrapHostsProperty = System.getProperty(LIVE_BOOTSTRAP_HOSTS_PROPERTY);

        try {
            System.setProperty(LIVE_BOOTSTRAP_HOSTS_PROPERTY, " https://bootstrap-one.example ,, https://bootstrap-two.example ");

            assertArrayEquals(
                    new String[] {"https://bootstrap-one.example", "https://bootstrap-two.example"},
                    this.getLiveBootstrapHosts());
        } finally {
            restoreSystemProperty(LIVE_BOOTSTRAP_HOSTS_PROPERTY, originalLiveBootstrapHostsProperty);
        }
    }

    @Test
    public void testBootstrapHeadRequestUsesLoopbackServer() throws IOException {
        long fileSize = MINIMUM_BOOTSTRAP_SIZE + 1;
        long lastModified = (NTP.getTime() / 1000L) * 1000L;
        List<String> requestMethods = new ArrayList<>();
        HttpServer server = this.createBootstrapHeadServer(HttpURLConnection.HTTP_OK, fileSize, lastModified, requestMethods);

        try {
            server.start();
            String bootstrapUrl = this.getLoopbackBootstrapUrl(server);

            BootstrapHead bootstrapHead = this.fetchBootstrapHead(bootstrapUrl);

            assertEquals(1, requestMethods.size());
            assertEquals("HEAD", requestMethods.get(0));
            assertEquals(fileSize, bootstrapHead.fileSize);
            assertEquals(lastModified, bootstrapHead.lastModified);
            this.assertBootstrapHeadIsCurrent(bootstrapUrl, bootstrapHead);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testBootstrapHeadRequestRejectsHttpErrors() throws IOException {
        HttpServer server = this.createBootstrapHeadServer(HttpURLConnection.HTTP_NOT_FOUND, 0L, NTP.getTime(), new ArrayList<>());

        try {
            server.start();
            String bootstrapUrl = this.getLoopbackBootstrapUrl(server);

            try {
                this.fetchBootstrapHead(bootstrapUrl);
                fail("HTTP errors should fail bootstrap HEAD validation");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("HTTP 404"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testBootstrapHeadValidationRejectsSmallFile() {
        try {
            this.assertBootstrapHeadIsCurrent("https://bootstrap.example/bootstrap-archive.7z",
                    new BootstrapHead(MINIMUM_BOOTSTRAP_SIZE - 1, NTP.getTime()));
            fail("Bootstrap archive below the minimum size should fail validation");
        } catch (AssertionError e) {
            assertTrue(e.getMessage().contains("at least 100MiB"));
        }
    }

    @Test
    public void testBootstrapHeadValidationRejectsStaleFile() {
        try {
            this.assertBootstrapHeadIsCurrent("https://bootstrap.example/bootstrap-archive.7z",
                    new BootstrapHead(MINIMUM_BOOTSTRAP_SIZE, NTP.getTime() - MAXIMUM_BOOTSTRAP_AGE - 1000L));
            fail("Stale bootstrap archive should fail validation");
        } catch (AssertionError e) {
            assertTrue(e.getMessage().contains("last modified date"));
        }
    }

    @Test
    public void testBootstrapHosts() throws IOException {
        assumeTrue(Boolean.getBoolean(RUN_LIVE_BOOTSTRAP_CHECKS_PROPERTY));

        String[] bootstrapHosts = this.getLiveBootstrapHosts();
        assertTrue(String.format("No bootstrap hosts configured. Set -D%s or bootstrapHosts in settings.", LIVE_BOOTSTRAP_HOSTS_PROPERTY), bootstrapHosts.length > 0);
        String[] bootstrapTypes = { "archive" }; // , "toponly", "full"

        for (String host : bootstrapHosts) {
            for (String type : bootstrapTypes) {
                String bootstrapFilename = String.format("bootstrap-%s.7z", type);
                String bootstrapUrl = String.format("%s/%s", host, bootstrapFilename);

                BootstrapHead bootstrapHead = this.fetchBootstrapHead(bootstrapUrl);

                System.out.println(String.format("%s %s size is %d bytes", host, type, bootstrapHead.fileSize));
                System.out.println(String.format("%s %s last modified timestamp is %d", host, type, bootstrapHead.lastModified));
                this.assertBootstrapHeadIsCurrent(bootstrapUrl, bootstrapHead);
            }
        }
    }

    private BootstrapHead fetchBootstrapHead(String bootstrapUrl) throws IOException {
        URL url = new URL(bootstrapUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setConnectTimeout(LIVE_BOOTSTRAP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(LIVE_BOOTSTRAP_READ_TIMEOUT_MS);
            connection.setRequestMethod("HEAD");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK)
                throw new IOException(String.format("%s returned HTTP %d", bootstrapUrl, responseCode));

            return new BootstrapHead(connection.getContentLengthLong(), connection.getLastModified());
        } finally {
            connection.disconnect();
        }
    }

    private void assertBootstrapHeadIsCurrent(String bootstrapUrl, BootstrapHead bootstrapHead) {
        assertTrue(String.format("%s size must be at least 100MiB", bootstrapUrl), bootstrapHead.fileSize >= MINIMUM_BOOTSTRAP_SIZE);

        long minimumLastModifiedTimestamp = NTP.getTime() - MAXIMUM_BOOTSTRAP_AGE;
        assertTrue(String.format("%s last modified date must be in the last 3 days", bootstrapUrl), bootstrapHead.lastModified >= minimumLastModifiedTimestamp);
    }

    private HttpServer createBootstrapHeadServer(int responseCode, long fileSize, long lastModified, List<String> requestMethods) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bootstrap-archive.7z", exchange -> {
            try {
                requestMethods.add(exchange.getRequestMethod());
                exchange.getResponseHeaders().set("Content-Length", Long.toString(fileSize));
                exchange.getResponseHeaders().set("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(lastModified).atZone(ZoneOffset.UTC)));
                exchange.sendResponseHeaders(responseCode, -1);
            } finally {
                exchange.close();
            }
        });

        return server;
    }

    private String getLoopbackBootstrapUrl(HttpServer server) {
        return String.format("http://127.0.0.1:%d/bootstrap-archive.7z", server.getAddress().getPort());
    }

    private String[] getLiveBootstrapHosts() {
        String bootstrapHostsProperty = System.getProperty(LIVE_BOOTSTRAP_HOSTS_PROPERTY);
        if (bootstrapHostsProperty == null || bootstrapHostsProperty.trim().isEmpty())
            return Settings.getInstance().getBootstrapHosts();

        List<String> bootstrapHosts = new ArrayList<>();
        for (String bootstrapHost : bootstrapHostsProperty.split(",")) {
            String trimmedHost = bootstrapHost.trim();
            if (!trimmedHost.isEmpty())
                bootstrapHosts.add(trimmedHost);
        }

        return bootstrapHosts.toArray(new String[0]);
    }

    private static void restoreSystemProperty(String propertyName, String originalValue) {
        if (originalValue == null)
            System.clearProperty(propertyName);
        else
            System.setProperty(propertyName, originalValue);
    }

    private static class BootstrapHead {
        private final long fileSize;
        private final long lastModified;

        private BootstrapHead(long fileSize, long lastModified) {
            this.fileSize = fileSize;
            this.lastModified = lastModified;
        }
    }

    private void deleteBootstraps() throws IOException {
        try {
            Path archivePath = Paths.get(String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), "bootstrap-archive.7z"));
            Files.delete(archivePath);

            Path sha256Path = Paths.get(String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), "bootstrap-archive.7z.sha256"));
            Files.delete(sha256Path);

        } catch (NoSuchFileException e) {
            // Nothing to delete
        }

        try {
            Path path = Paths.get(String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), "bootstrap-toponly.7z"));
            Files.delete(path);

        } catch (NoSuchFileException e) {
            // Nothing to delete
        }

        try {
            Path path = Paths.get(String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), "bootstrap-full.7z"));
            Files.delete(path);

        } catch (NoSuchFileException e) {
            // Nothing to delete
        }
    }

    private void deleteExportDirectory() {
        // Delete archive directory if exists
        Path archivePath = Paths.get(Settings.getInstance().getExportPath());
        try {
            FileUtils.deleteDirectory(archivePath.toFile());
        } catch (IOException e) {

        }
    }

}
