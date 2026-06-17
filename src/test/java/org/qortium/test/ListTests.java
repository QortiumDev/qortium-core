package org.qortium.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.resource.ListsResource;
import org.qortium.list.ResourceList;
import org.qortium.list.ResourceListManager;
import org.qortium.repository.DataException;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.utils.ListUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

public class ListTests {

    private static final String[] TEST_LISTS = {
            "followedQdn", "blockedQdn", "blockedChatNames", "blockedChatAddresses", "testListA", "testListB"
    };

    @Before
    public void beforeTest() throws DataException, IOException {
        Common.useDefaultSettings();
        ApiCommon.installTestApiKey();
        this.cleanup();
    }

    @After
    public void afterTest() throws DataException, IOException {
        this.cleanup();
        ApiCommon.clearTestApiKey();
    }

    private void cleanup() throws IOException {
        // Delete any lists created by test methods
        for (String listName : TEST_LISTS) {
            ResourceList list = new ResourceList(listName);
            list.clear();
            list.save();
        }

        // Clear resource list manager instance
        ResourceListManager.reset();
    }

    @Test
    public void testListNameDiscovery() {
        ResourceListManager resourceListManager = ResourceListManager.getInstance();

        assertTrue(resourceListManager.getListNames().isEmpty());

        resourceListManager.addToList("testListA", "item1", true);
        resourceListManager.addToList("testListB", "item2", true);

        List<String> listNames = resourceListManager.getListNames();

        assertEquals(2, listNames.size());
        assertEquals("testListA", listNames.get(0));
        assertEquals("testListB", listNames.get(1));
    }

    @Test
    public void testListNameApi() {
        ResourceListManager.getInstance().addToList("testListA", "item1", true);

        ListsResource listsResource = (ListsResource) ApiCommon.buildResource(ListsResource.class, ApiCommon.TEST_API_KEY);
        List<String> listNames = listsResource.getLists(ApiCommon.TEST_API_KEY);

        assertEquals(1, listNames.size());
        assertEquals("testListA", listNames.get(0));
    }

    @Test
    public void testListRejectsTraversalName() throws IOException {
        try {
            new ResourceList("../followedQdn_escape");
            fail("Expected traversal list name to be rejected");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("outside"));
        }

        assertFalse(ResourceListManager.getInstance().addToList("../followedQdn_escape", "testName1", true));
        assertFalse(Files.exists(Paths.get(Settings.getInstance().getListsPath()).resolve("..").resolve("followedQdn_escape.json").normalize()));
    }

    @Test
    public void testQdnLists() {
        ResourceListManager resourceListManager = ResourceListManager.getInstance();

        assertTrue(ListUtils.followedQdn().isEmpty());
        assertEquals(0, ListUtils.followedQdnCount());
        assertTrue(ListUtils.blockedQdn().isEmpty());

        // The four lists are read by exact name only (no prefix scanning)
        resourceListManager.addToList("followedQdn", "BLOG_POST", false);
        resourceListManager.addToList("followedQdn", "APP/BOB", false);
        resourceListManager.addToList("blockedQdn", "*/SPAMMER", false);

        assertEquals(2, ListUtils.followedQdn().size());
        assertEquals(2, ListUtils.followedQdnCount());
        assertEquals(1, ListUtils.blockedQdn().size());
    }

    @Test
    public void testChatLists() {
        ResourceListManager resourceListManager = ResourceListManager.getInstance();

        assertFalse(ListUtils.isChatNameBlocked("alice"));
        assertFalse(ListUtils.isChatAddressBlocked("Qaddress"));

        resourceListManager.addToList("blockedChatNames", "Alice", false);
        resourceListManager.addToList("blockedChatAddresses", "Qspammer", false);

        // Names are matched case-insensitively
        assertTrue(ListUtils.isChatNameBlocked("alice"));
        assertTrue(ListUtils.isChatNameBlocked("ALICE"));
        assertFalse(ListUtils.isChatNameBlocked("bob"));

        // Addresses are matched case-sensitively
        assertTrue(ListUtils.isChatAddressBlocked("Qspammer"));
        assertFalse(ListUtils.isChatAddressBlocked("qspammer"));
    }

    @Test
    public void testDataPersistence() {
        // Ensure lists are empty to begin with
        assertEquals(0, ListUtils.followedQdn().size());
        assertEquals(0, ListUtils.blockedQdn().size());

        // Add some items
        ResourceListManager.getInstance().addToList("followedQdn", "testName1", true);
        ResourceListManager.getInstance().addToList("followedQdn", "testName2", true);
        ResourceListManager.getInstance().addToList("blockedQdn", "testName3", true);

        // Ensure they are added
        assertEquals(2, ResourceListManager.getInstance().getStringsInList("followedQdn").size());
        assertEquals(1, ResourceListManager.getInstance().getStringsInList("blockedQdn").size());

        // Clear local state
        ResourceListManager.reset();

        // Ensure items are automatically loaded back in from disk
        assertEquals(2, ResourceListManager.getInstance().getStringsInList("followedQdn").size());
        assertEquals(1, ResourceListManager.getInstance().getStringsInList("blockedQdn").size());

        // Delete the followedQdn file
        File followedQdnFile = Paths.get(Settings.getInstance().getListsPath(), "followedQdn.json").toFile();
        followedQdnFile.delete();

        // Clear local state again
        ResourceListManager.reset();

        // Ensure only the blocked entries are loaded back in
        assertEquals(0, ListUtils.followedQdn().size());
        assertEquals(1, ResourceListManager.getInstance().getStringsInList("blockedQdn").size());
    }

}
