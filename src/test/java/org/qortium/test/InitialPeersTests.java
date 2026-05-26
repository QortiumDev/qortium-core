package org.qortium.test;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.controller.Controller;
import org.qortium.data.network.PeerData;
import org.qortium.network.Network;
import org.qortium.network.PeerAddress;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InitialPeersTests extends Common {

	private String[] originalInitialPeers;

	@Before
	public void beforeTest() throws DataException, IllegalAccessException {
		Common.useDefaultSettings();
		this.originalInitialPeers = (String[]) FieldUtils.readField(Settings.getInstance(), "initialPeers", true);

		try (Repository repository = RepositoryManager.getRepository()) {
			repository.getNetworkRepository().deleteAllPeers();
			repository.saveChanges();
		}
	}

	@After
	public void afterTest() throws IllegalAccessException, DataException {
		FieldUtils.writeField(Settings.getInstance(), "initialPeers", this.originalInitialPeers, true);

		try (Repository repository = RepositoryManager.getRepository()) {
			repository.getNetworkRepository().deleteAllPeers();
			repository.saveChanges();
		}
	}

	private Settings newSettingsInstance() throws ReflectiveOperationException {
		Constructor<Settings> constructor = Settings.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private void setInitialPeers(String... initialPeers) throws IllegalAccessException {
		FieldUtils.writeField(Settings.getInstance(), "initialPeers", initialPeers, true);
	}

	private void invokeControllerInstallInitialPeers() throws ReflectiveOperationException {
		Method installInitialPeers = Controller.class.getDeclaredMethod("installInitialPeers");
		installInitialPeers.setAccessible(true);
		installInitialPeers.invoke(null);
	}

	private Set<String> getKnownPeerAddresses() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			List<PeerData> peers = repository.getNetworkRepository().getAllPeers();
			return peers.stream().map(peerData -> peerData.getAddress().toString()).collect(Collectors.toSet());
		}
	}

	@Test
	public void testDefaultInitialPeersAreEmpty() throws ReflectiveOperationException {
		Settings settings = newSettingsInstance();

		assertFalse(settings.hasInitialPeersConfigured());
		assertArrayEquals(new String[0], settings.getInitialPeers());
	}

	@Test
	public void testInitialPeersAreTrimmedAndDeduplicated() throws ReflectiveOperationException, IllegalAccessException {
		Settings settings = newSettingsInstance();
		FieldUtils.writeField(settings, "initialPeers", new String[] {
				null,
				" ",
				"seed-one.example.org",
				" seed-one.example.org ",
				"seed-two.example.org:22392"
		}, true);

		assertTrue(settings.hasInitialPeersConfigured());
		assertArrayEquals(new String[] {
				"seed-one.example.org",
				"seed-two.example.org:22392"
		}, settings.getInitialPeers());
	}

	@Test
	public void testInstallInitialPeersAddsValidPeersAndSkipsInvalidEntries() throws DataException, IllegalAccessException {
		setInitialPeers(
				" seed-one.example.org ",
				"seed-one.example.org",
				"seed-two.example.org:not-a-port",
				"[2001:db8::1]:22392"
		);

		try (Repository repository = RepositoryManager.getRepository()) {
			Network.installInitialPeers(repository);

			List<PeerData> peers = repository.getNetworkRepository().getAllPeers();
			assertEquals(2, peers.size());
			assertTrue(peers.stream().allMatch(peerData -> "INIT".equals(peerData.getAddedBy())));
		}

		assertEquals(Set.of(
				"seed-one.example.org:" + Settings.getInstance().getDefaultListenPort(),
				"[2001:db8::1]:22392"
		), this.getKnownPeerAddresses());
	}

	@Test
	public void testControllerInstallInitialPeersSeedsEmptyRepositoryOnly() throws ReflectiveOperationException, IllegalAccessException, DataException {
		setInitialPeers("seed-one.example.org");

		this.invokeControllerInstallInitialPeers();

		try (Repository repository = RepositoryManager.getRepository()) {
			List<PeerData> peers = repository.getNetworkRepository().getAllPeers();
			assertEquals(1, peers.size());
			assertEquals("INIT", peers.get(0).getAddedBy());
			assertEquals("seed-one.example.org:" + Settings.getInstance().getDefaultListenPort(), peers.get(0).getAddress().toString());
		}
	}

	@Test
	public void testControllerInstallInitialPeersDoesNotOverrideExistingPeers() throws ReflectiveOperationException, IllegalAccessException, DataException {
		setInitialPeers("seed-one.example.org");

		try (Repository repository = RepositoryManager.getRepository()) {
			PeerAddress peerAddress = PeerAddress.fromString("existing-peer.example.org");
			PeerData peerData = new PeerData(peerAddress, System.currentTimeMillis(), "TEST");
			repository.getNetworkRepository().save(peerData);
			repository.saveChanges();
		}

		this.invokeControllerInstallInitialPeers();

		try (Repository repository = RepositoryManager.getRepository()) {
			List<PeerData> peers = repository.getNetworkRepository().getAllPeers();
			assertEquals(1, peers.size());
			assertEquals("TEST", peers.get(0).getAddedBy());
			assertEquals("existing-peer.example.org:" + Settings.getInstance().getDefaultListenPort(), peers.get(0).getAddress().toString());
		}
	}
}
