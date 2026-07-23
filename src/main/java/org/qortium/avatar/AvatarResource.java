package org.qortium.avatar;

import com.google.common.base.Utf8;
import org.qortium.arbitrary.misc.Service;
import org.qortium.naming.Name;
import org.qortium.transaction.ArbitraryTransaction;
import org.qortium.transaction.Transaction.ValidationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared consensus and API representation rules for account and group avatars.
 *
 * <p>An avatar is a plain pointer to a QDN resource — a (service, name, identifier)
 * tuple. It is validated for shape only: any registered name's resource is allowed
 * (no owner restriction), the target need not already exist, and the image is
 * resolved to the resource's latest revision when served. The raster-image type and
 * {@link #MAX_SIZE} bound are enforced at serve time, not here, because the target
 * resource is mutable. */
public final class AvatarResource {

	public static final long MAX_SIZE = 500L * 1024L;

	private AvatarResource() {
	}

	/** Validates the shape of an avatar pointer. Owner-agnostic and existence-agnostic. */
	public static ValidationResult validate(Service service, String name, String identifier) {
		if (service == null || service.isPrivate() || !service.isSingle())
			return ValidationResult.INVALID_RESOURCE;
		if (name == null || name.isBlank() || Utf8.encodedLength(name) > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_RESOURCE;
		// Identifier is optional (empty selects the default resource) but is length-bounded like ARBITRARY.
		if (identifier != null && Utf8.encodedLength(identifier) > ArbitraryTransaction.MAX_IDENTIFIER_LENGTH)
			return ValidationResult.INVALID_RESOURCE;
		return ValidationResult.OK;
	}

	/** Returns a supported raster MIME type only after inspecting bytes and enforcing the avatar bound. */
	public static String detectRasterImageContentType(Path file) throws IOException {
		if (Files.size(file) > MAX_SIZE)
			return null;
		byte[] bytes = Files.readAllBytes(file);
		if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
				&& bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a)
			return "image/png";
		if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff)
			return "image/jpeg";
		if (bytes.length >= 6 && ((bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8' && bytes[4] == '7' && bytes[5] == 'a')
				|| (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8' && bytes[4] == '9' && bytes[5] == 'a')))
			return "image/gif";
		if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P')
			return "image/webp";
		if (bytes.length >= 26 && bytes[0] == 'B' && bytes[1] == 'M' && (bytes[14] == 40 || bytes[14] == 108 || bytes[14] == 124))
			return "image/bmp";
		return null;
	}
}
