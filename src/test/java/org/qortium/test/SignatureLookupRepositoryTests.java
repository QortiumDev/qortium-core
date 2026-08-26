package org.qortium.test;

import org.junit.Before;
import org.junit.Test;
import org.qortium.data.block.BlockData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.repository.hsqldb.HSQLDBSignatureLookup;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.utils.NTP;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SignatureLookupRepositoryTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testTransactionLookupFiltersDeduplicatesAndBatches() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<byte[]> storedSignatures = repository.getTransactionRepository()
					.getSignaturesMatchingCriteria(null, null, 1, Integer.MAX_VALUE);
			assertFalse(storedSignatures.isEmpty());

			byte[] storedSignature = storedSignatures.get(0);
			TransactionData individuallyLoaded = repository.getTransactionRepository().fromSignature(storedSignature);
			List<TransactionData> batched = repository.getTransactionRepository()
					.fromSignatures(largeLookupWithCrossBatchDuplicate(storedSignature));

			assertEquals(1, batched.size());
			assertArrayEquals(storedSignature, batched.get(0).getSignature());
			assertEquals(individuallyLoaded.getNonce(), batched.get(0).getNonce());
			assertTransactionEmptyInputs(repository);
		}
	}

	@Test
	public void testBlockLookupFiltersDeduplicatesAndBatches() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockData storedBlock = repository.getBlockRepository().fromHeight(1);
			byte[] storedSignature = storedBlock.getSignature();
			List<BlockData> batched = repository.getBlockRepository()
					.fromSignatures(largeLookupWithCrossBatchDuplicate(storedSignature));

			assertEquals(1, batched.size());
			assertArrayEquals(storedSignature, batched.get(0).getSignature());
			assertBlockEmptyInputs(repository);
		}
	}

	@Test
	public void testChatLookupFiltersDeduplicatesAndBatches() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			byte[] storedSignature = missingSignature(Integer.MAX_VALUE);
			Long networkTime = NTP.getTime();
			long timestamp = networkTime != null ? networkTime : System.currentTimeMillis();
			BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP,
					alice.getPublicKey(), 0L, 37, storedSignature);
			ChatTransactionData chatTransactionData = new ChatTransactionData(baseTransactionData,
					alice.getAddress(), 37, null, null, "batched".getBytes(StandardCharsets.UTF_8), true, false);
			repository.getChatStoreRepository().save(chatTransactionData);
			repository.saveChanges();

			List<ChatTransactionData> batched = repository.getChatStoreRepository()
					.fromSignatures(largeLookupWithCrossBatchDuplicate(storedSignature));

			assertEquals(1, batched.size());
			assertArrayEquals(storedSignature, batched.get(0).getSignature());
			assertEquals(37, batched.get(0).getNonce());
			assertChatEmptyInputs(repository);
		}
	}

	private static List<byte[]> largeLookupWithCrossBatchDuplicate(byte[] storedSignature) {
		List<byte[]> signatures = new ArrayList<>(HSQLDBSignatureLookup.MAX_BATCH_SIZE + 4);
		signatures.add(storedSignature);
		for (int i = 0; i < HSQLDBSignatureLookup.MAX_BATCH_SIZE; ++i) {
			byte[] missingSignature = missingSignature(i);
			if (Arrays.equals(missingSignature, storedSignature))
				missingSignature[1] ^= 1;
			signatures.add(missingSignature);
		}
		signatures.add(Arrays.copyOf(storedSignature, storedSignature.length));
		signatures.add(null);
		signatures.add(new byte[0]);
		return signatures;
	}

	private static byte[] missingSignature(int value) {
		byte[] signature = new byte[64];
		signature[0] = 0x55;
		ByteBuffer.wrap(signature).putInt(signature.length - Integer.BYTES, value);
		return signature;
	}

	private static void assertTransactionEmptyInputs(Repository repository) throws DataException {
		assertTrue(repository.getTransactionRepository().fromSignatures(null).isEmpty());
		assertTrue(repository.getTransactionRepository().fromSignatures(Collections.emptyList()).isEmpty());
		assertTrue(repository.getTransactionRepository()
				.fromSignatures(Arrays.<byte[]>asList(null, new byte[0])).isEmpty());
	}

	private static void assertBlockEmptyInputs(Repository repository) throws DataException {
		assertTrue(repository.getBlockRepository().fromSignatures(null).isEmpty());
		assertTrue(repository.getBlockRepository().fromSignatures(Collections.emptyList()).isEmpty());
		assertTrue(repository.getBlockRepository()
				.fromSignatures(Arrays.<byte[]>asList(null, new byte[0])).isEmpty());
	}

	private static void assertChatEmptyInputs(Repository repository) throws DataException {
		assertTrue(repository.getChatStoreRepository().fromSignatures(null).isEmpty());
		assertTrue(repository.getChatStoreRepository().fromSignatures(Collections.emptyList()).isEmpty());
		assertTrue(repository.getChatStoreRepository()
				.fromSignatures(Arrays.<byte[]>asList(null, new byte[0])).isEmpty());
	}
}
