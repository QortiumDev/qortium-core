package org.qortium.avatar;

import org.junit.Before;
import org.junit.Test;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transaction.Transaction.ApprovalStatus;
import java.util.Collections;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class AvatarResourceValidationTests extends Common {
	private static int signatureCounter;
	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testCustomPublicSinglePutIsAcceptedAndUnsafeShapesAreRejected() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			try {
				TestAccount alice = Common.getTestAccount(repository, "alice");
				assertEquals(ValidationResult.OK, validate(repository, alice, Service.IMAGE, ArbitraryTransactionData.Method.PUT, "alice-avatar", "custom", null, 500 * 1024));
				assertEquals(ValidationResult.INVALID_RESOURCE, validate(repository, alice, Service.IMAGE, ArbitraryTransactionData.Method.PATCH, "alice-avatar", "custom", null, 1));
				assertEquals(ValidationResult.INVALID_RESOURCE, validate(repository, alice, Service.IMAGE_PRIVATE, ArbitraryTransactionData.Method.PUT, "alice-avatar", "custom", null, 1));
				assertEquals(ValidationResult.INVALID_RESOURCE, validate(repository, alice, Service.WEBSITE, ArbitraryTransactionData.Method.PUT, "alice-avatar", "custom", null, 1));
				assertEquals(ValidationResult.INVALID_RESOURCE, validate(repository, alice, Service.IMAGE, ArbitraryTransactionData.Method.PUT, "", "custom", null, 1));
				assertEquals(ValidationResult.INVALID_RESOURCE, validate(repository, alice, Service.IMAGE, ArbitraryTransactionData.Method.PUT, "alice-avatar", "", null, 1));
				assertEquals(ValidationResult.INVALID_RESOURCE, validate(repository, alice, Service.IMAGE, ArbitraryTransactionData.Method.PUT, "alice-avatar", "custom", null, 500 * 1024 + 1));
				assertEquals(ValidationResult.INVALID_RESOURCE, validate(repository, alice, Service.IMAGE, ArbitraryTransactionData.Method.PUT, "alice-avatar", "custom", new byte[32], 1));
				assertEquals(ValidationResult.INVALID_AVATAR_OWNER, validate(repository, alice, Service.IMAGE, ArbitraryTransactionData.Method.PUT, "alice-avatar", "custom", null, 1, true, "different-owner"));
				assertEquals(ValidationResult.INVALID_RESOURCE, validate(repository, alice, Service.IMAGE, ArbitraryTransactionData.Method.PUT, "alice-avatar", "unconfirmed", null, 1, false, alice.getAddress()));
			} finally {
				repository.discardChanges();
			}
		}
	}

	@Test
	public void testRasterMagicRequiresFullPngSignatureAndSizeBound() throws Exception {
		Path png = Files.createTempFile("avatar", ".png");
		Path truncated = Files.createTempFile("avatar", ".png");
		Path oversized = Files.createTempFile("avatar", ".png");
		try {
			Files.write(png, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
			Files.write(truncated, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
			Files.write(oversized, new byte[(int) AvatarResource.MAX_SIZE + 1]);
			assertEquals("image/png", AvatarResource.detectRasterImageContentType(png));
			assertEquals(null, AvatarResource.detectRasterImageContentType(truncated));
			assertEquals(null, AvatarResource.detectRasterImageContentType(oversized));
		} finally {
			Files.deleteIfExists(png); Files.deleteIfExists(truncated); Files.deleteIfExists(oversized);
		}
	}

	private ValidationResult validate(Repository repository, TestAccount account, Service service, ArbitraryTransactionData.Method method,
			String name, String identifier, byte[] secret, int size) throws Exception {
		return validate(repository, account, service, method, name, identifier, secret, size, true, account.getAddress());
	}

	private ValidationResult validate(Repository repository, TestAccount account, Service service, ArbitraryTransactionData.Method method,
			String name, String identifier, byte[] secret, int size, boolean confirmed, String requiredCreator) throws Exception {
		byte[] signature = new byte[64]; signature[0] = (byte) ++signatureCounter; signature[1] = (byte) method.value;
		BaseTransactionData base = new BaseTransactionData(System.currentTimeMillis(), 0, account.getPublicKey(), 0L, 0,
				ApprovalStatus.NOT_REQUIRED, null, null, signature);
		ArbitraryTransactionData arbitrary = new ArbitraryTransactionData(base, 5, service.value, 0, size, name, identifier, method, secret,
				ArbitraryTransactionData.Compression.NONE, new byte[32], ArbitraryTransactionData.DataType.DATA_HASH, null, Collections.emptyList());
		repository.getTransactionRepository().save(arbitrary);
		try {
			if (confirmed) repository.getTransactionRepository().updateBlockHeight(signature, 1);
			return AvatarResource.validate(repository, signature, requiredCreator);
		} finally {
			repository.getTransactionRepository().delete(arbitrary);
		}
	}
}
