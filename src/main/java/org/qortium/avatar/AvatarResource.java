package org.qortium.avatar;

import org.qortium.arbitrary.misc.Service;
import org.qortium.crypto.Crypto;
import org.qortium.data.avatar.AvatarData;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.Transformer;
import org.qortium.transaction.Transaction.ValidationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared consensus and API representation rules for explicitly authorized avatar resources. */
public final class AvatarResource {

	public static final long MAX_SIZE = 500L * 1024L;

	private AvatarResource() {
	}

	public static ValidationResult validate(Repository repository, byte[] signature, String requiredCreatorAddress) throws DataException {
		if (signature == null || signature.length != Transformer.SIGNATURE_LENGTH)
			return ValidationResult.INVALID_DATA_LENGTH;
		TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
		if (!(transactionData instanceof ArbitraryTransactionData) || transactionData.getBlockHeight() == null)
			return ValidationResult.INVALID_RESOURCE;
		ArbitraryTransactionData arbitrary = (ArbitraryTransactionData) transactionData;
		Service service = arbitrary.getService();
		if (arbitrary.getMethod() != ArbitraryTransactionData.Method.PUT || arbitrary.getSecret() != null || service == null
				|| service.isPrivate() || !service.isSingle() || arbitrary.getName() == null || arbitrary.getName().isBlank()
				|| arbitrary.getIdentifier() == null || arbitrary.getIdentifier().isBlank() || arbitrary.getSize() > MAX_SIZE)
			return ValidationResult.INVALID_RESOURCE;
		return requiredCreatorAddress != null && !requiredCreatorAddress.equals(Crypto.toAddress(arbitrary.getCreatorPublicKey()))
				? ValidationResult.INVALID_AVATAR_OWNER : ValidationResult.OK;
	}

	public static AvatarData descriptor(Repository repository, byte[] signature) throws DataException {
		if (signature == null)
			return null;
		TransactionData tx = repository.getTransactionRepository().fromSignature(signature);
		if (!(tx instanceof ArbitraryTransactionData))
			return null;
		ArbitraryTransactionData arbitrary = (ArbitraryTransactionData) tx;
		return new AvatarData(signature, arbitrary.getService(), arbitrary.getName(), arbitrary.getIdentifier());
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
