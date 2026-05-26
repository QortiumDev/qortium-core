package org.qortium.account;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountData;
import org.qortium.repository.Repository;
import org.qortium.transform.Transformer;

import java.util.Arrays;

public class PublicKeyAccount extends Account {

	protected final byte[] publicKey;
	protected final Ed25519PublicKeyParameters edPublicKeyParams;
	public static final byte[] ALL_ZEROS = new byte[Transformer.PUBLIC_KEY_LENGTH];

	/** <p>Constructor for generating a PublicKeyAccount</p>
	 *
	 * @param repository Block Chain
	 * @param publicKey 32 byte Public Key
	 * @since v4.7.3
	 * @since v6.0.0 - Updated for Bouncy Castle v1.73
	 */
	public PublicKeyAccount(Repository repository, byte[] publicKey) {
		super(repository, Crypto.toAddress(requireValidPublicKey(publicKey)));

		this.publicKey = publicKey;

		if (Arrays.equals(publicKey, ALL_ZEROS)) {
			this.edPublicKeyParams = null;
			return;
		}

		this.edPublicKeyParams = new Ed25519PublicKeyParameters(publicKey, 0);
	}

	protected PublicKeyAccount(Repository repository, Ed25519PublicKeyParameters edPublicKeyParams) {
		super(repository, Crypto.toAddress(edPublicKeyParams.getEncoded()));

		this.edPublicKeyParams = edPublicKeyParams;
		this.publicKey = edPublicKeyParams.getEncoded();
	}

	protected PublicKeyAccount(Repository repository, byte[] publicKey, String address) {
		super(repository, address);

		this.publicKey = publicKey;
		this.edPublicKeyParams = null;
	}

	protected PublicKeyAccount() {
		this.publicKey = null;
		this.edPublicKeyParams = null;
	}

	public byte[] getPublicKey() {
		return this.publicKey;
	}

	@Override
	protected AccountData buildAccountData() {
		AccountData accountData = super.buildAccountData();
		accountData.setPublicKey(this.publicKey);
		return accountData;
	}

	public boolean verify(byte[] signature, byte[] message) {
		return Crypto.verify(this.publicKey, signature, message);
	}

	public static String getAddress(byte[] publicKey) {
		return Crypto.toAddress(publicKey);
	}

	private static byte[] requireValidPublicKey(byte[] publicKey) {
		if (publicKey == null || publicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw new IllegalArgumentException("Public key must be 32 bytes");

		return publicKey;
	}

}
