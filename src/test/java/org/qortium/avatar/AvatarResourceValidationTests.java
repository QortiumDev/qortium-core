package org.qortium.avatar;

import org.junit.Before;
import org.junit.Test;
import org.qortium.arbitrary.misc.Service;
import org.qortium.naming.Name;
import org.qortium.test.common.Common;
import org.qortium.transaction.ArbitraryTransaction;
import org.qortium.transaction.Transaction.ValidationResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class AvatarResourceValidationTests extends Common {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testPointerShapeIsValidatedWithoutOwnerOrExistenceChecks() {
		// A single-file, public image service with a non-blank name is accepted, regardless of who owns it.
		assertEquals(ValidationResult.OK, AvatarResource.validate(Service.IMAGE, "alice-avatar", "custom"));
		assertEquals(ValidationResult.OK, AvatarResource.validate(Service.THUMBNAIL, "someone-elses-name", "x"));
		// Empty identifier selects the default resource and is allowed.
		assertEquals(ValidationResult.OK, AvatarResource.validate(Service.IMAGE, "alice-avatar", ""));

		// Rejected shapes: null / private / multi-file service, blank name, over-long name or identifier.
		assertEquals(ValidationResult.INVALID_RESOURCE, AvatarResource.validate(null, "alice-avatar", "custom"));
		assertEquals(ValidationResult.INVALID_RESOURCE, AvatarResource.validate(Service.IMAGE_PRIVATE, "alice-avatar", "custom"));
		assertEquals(ValidationResult.INVALID_RESOURCE, AvatarResource.validate(Service.WEBSITE, "alice-avatar", "custom"));
		assertEquals(ValidationResult.INVALID_RESOURCE, AvatarResource.validate(Service.IMAGE, "", "custom"));
		assertEquals(ValidationResult.INVALID_RESOURCE, AvatarResource.validate(Service.IMAGE, "a".repeat(Name.MAX_NAME_SIZE + 1), "custom"));
		assertEquals(ValidationResult.INVALID_RESOURCE, AvatarResource.validate(Service.IMAGE, "alice-avatar", "i".repeat(65)));

		// Wire limits are UTF-8 bytes, not Java characters.
		assertEquals(ValidationResult.OK, AvatarResource.validate(Service.IMAGE, "é".repeat(Name.MAX_NAME_SIZE / 2), "custom"));
		assertEquals(ValidationResult.INVALID_RESOURCE,
				AvatarResource.validate(Service.IMAGE, "é".repeat(Name.MAX_NAME_SIZE / 2 + 1), "custom"));
		assertEquals(ValidationResult.OK,
				AvatarResource.validate(Service.IMAGE, "alice-avatar", "é".repeat(ArbitraryTransaction.MAX_IDENTIFIER_LENGTH / 2)));
		assertEquals(ValidationResult.INVALID_RESOURCE,
				AvatarResource.validate(Service.IMAGE, "alice-avatar", "é".repeat(ArbitraryTransaction.MAX_IDENTIFIER_LENGTH / 2 + 1)));
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
}
