package org.qortium.api.resource;

import org.qortium.api.ApiError;
import org.qortium.api.ApiExceptionFactory;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.Transformer;
import org.qortium.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;

public final class PublicKeyOrAddressResolver {

	private PublicKeyOrAddressResolver() {
	}

	public static byte[] parseOptionalPublicKeyOrAddress(Repository repository, HttpServletRequest request, String publicKeyOrAddress)
			throws DataException {
		return parsePublicKeyOrAddress(repository, request, publicKeyOrAddress, false);
	}

	public static byte[] parseRequiredPublicKeyOrAddress(Repository repository, HttpServletRequest request, String publicKeyOrAddress)
			throws DataException {
		return parsePublicKeyOrAddress(repository, request, publicKeyOrAddress, true);
	}

	public static byte[] parseKnownPublicKeyOrAddress(Repository repository, HttpServletRequest request, String publicKeyOrAddress)
			throws DataException {
		byte[] publicKey = parseRequiredPublicKeyOrAddress(repository, request, publicKeyOrAddress);
		AccountData accountData = repository.getAccountRepository().getAccount(Crypto.toAddress(publicKey));
		if (accountData == null || accountData.getPublicKey() == null || !Arrays.equals(publicKey, accountData.getPublicKey()))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return publicKey;
	}

	private static byte[] parsePublicKeyOrAddress(Repository repository, HttpServletRequest request, String publicKeyOrAddress,
			boolean required) throws DataException {
		if (publicKeyOrAddress == null || publicKeyOrAddress.trim().isEmpty()) {
			if (required)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			return null;
		}

		String trimmed = publicKeyOrAddress.trim();
		if (Crypto.isValidAddress(trimmed))
			return resolveAddressToPublicKey(repository, request, trimmed);

		return parsePublicKey(request, trimmed);
	}

	private static byte[] resolveAddressToPublicKey(Repository repository, HttpServletRequest request, String address)
			throws DataException {
		AccountData accountData = repository.getAccountRepository().getAccount(address);
		if (accountData == null || accountData.getPublicKey() == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.PUBLIC_KEY_NOT_FOUND);

		return accountData.getPublicKey();
	}

	private static byte[] parsePublicKey(HttpServletRequest request, String publicKey58) {
		byte[] publicKey;
		try {
			publicKey = Base58.decode(publicKey58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY, e);
		}

		if (publicKey == null || publicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		return publicKey;
	}

}
