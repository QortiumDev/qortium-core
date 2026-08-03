package org.qortium.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.ArbitraryDataCache;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortium.arbitrary.ArbitraryDataResource;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.arbitrary.ArbitraryDataManager;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.ArbitraryTransactionData.Method;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArbitraryDataCacheTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testRateLimitedAccessDoesNotRefreshRateLimitWindow() throws Exception {
		String name = "TEST_cache_rate_limit";
		Service service = Service.DOCUMENT;
		String identifier = null;

		ArbitraryDataResource resource = new ArbitraryDataResource(name, ResourceIdType.NAME, service, identifier);
		ArbitraryDataManager manager = ArbitraryDataManager.getInstance();

		// Seed the rate-limit window, then shrink it so a refresh is detectable
		manager.addResourceToCache(resource);
		Map<String, Long> cachedResources = getCachedResourcesMap(manager);
		String key = resource.getUniqueKey();
		assertTrue(cachedResources.containsKey(key));
		Long shortenedExpiry = NTP.getTime() + 5_000L;
		cachedResources.put(key, shortenedExpiry);

		// A non-empty cache directory, so only the freshness logic decides the outcome
		Path cachePath = Files.createTempDirectory("qortium-cache-rate-limit-test");
		Files.write(cachePath.resolve("data"), "cached".getBytes());

		ArbitraryDataCache cache = new ArbitraryDataCache(cachePath, false, name, ResourceIdType.NAME, service, identifier);

		// While rate limited the cache is trusted without a signature lookup...
		assertTrue(cache.isCachedDataAvailable());

		// ...but the rate-limit window must NOT be refreshed by the access itself, otherwise
		// frequent access keeps a stale cache alive indefinitely whenever the
		// new-transaction invalidation was missed
		assertEquals(shortenedExpiry, cachedResources.get(key));
	}

	@Test
	public void testBlockProcessingInvalidatesCacheWithoutLocalData() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String publicKey58 = Base58.encode(alice.getPublicKey());
			String name = "TEST_cache_invalidation";
			String identifier = null;
			Service service = Service.DOCUMENT;

			// Register the name
			RegisterNameTransactionData registerData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
			registerData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerData.getTimestamp()));
			TransactionUtils.signAndMint(repository, registerData, alice);

			// Publish a resource
			Path dataPath = ArbitraryUtils.generateRandomDataPath(2000);
			ArbitraryUtils.createAndMintTxn(repository, publicKey58, dataPath, name, identifier, Method.PUT, service, alice);

			ArbitraryTransactionData transactionData =
					repository.getArbitraryRepository().getLatestTransaction(name, service, null, identifier);

			// Delete the local data files, so this node no longer holds the transaction's data
			ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromTransactionData(transactionData);
			assertTrue(arbitraryDataFile.deleteAll(true));

			// Seed the rate-limit window, as if the resource had recently been accessed
			ArbitraryDataResource resource = new ArbitraryDataResource(name, ResourceIdType.NAME, service, identifier);
			ArbitraryDataManager manager = ArbitraryDataManager.getInstance();
			manager.addResourceToCache(resource);
			assertTrue(getCachedResourcesMap(manager).containsKey(resource.getUniqueKey()));

			// Orphan the block containing the publish, then re-mint it. The transaction is
			// then re-processed via the block-processing path only - it is NOT re-imported
			// as unconfirmed, which mirrors a transaction arriving in a synced block.
			BlockUtils.orphanLastBlock(repository);
			BlockUtils.mintBlock(repository);

			// Block processing must purge the rate-limit entry even though this node holds
			// none of the transaction's data files
			assertFalse(getCachedResourcesMap(manager).containsKey(resource.getUniqueKey()));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Long> getCachedResourcesMap(ArbitraryDataManager manager) throws Exception {
		Field field = ArbitraryDataManager.class.getDeclaredField("arbitraryDataCachedResources");
		field.setAccessible(true);
		return (Map<String, Long>) field.get(manager);
	}

}
