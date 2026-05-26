package org.qortium.crosschain;

import org.qortium.settings.Settings;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class ZcashFamilyWalletConfig {

	private final String displayName;
	private final String currencyCode;
	private final String walletDirectoryName;
	private final String qdnWalletSignature;
	private final String walletEncryptionPrefix;
	private final String privateAddressHrp;
	private final IntSupplier defaultBirthdaySupplier;
	private final Supplier<? extends Bitcoiny> blockchainSupplier;

	public ZcashFamilyWalletConfig(String displayName, String currencyCode, String walletDirectoryName,
			String qdnWalletSignature, String walletEncryptionPrefix, String privateAddressHrp,
			IntSupplier defaultBirthdaySupplier, Supplier<? extends Bitcoiny> blockchainSupplier) {
		this.displayName = Objects.requireNonNull(displayName);
		this.currencyCode = Objects.requireNonNull(currencyCode);
		this.walletDirectoryName = Objects.requireNonNull(walletDirectoryName);
		this.qdnWalletSignature = Objects.requireNonNull(qdnWalletSignature);
		this.walletEncryptionPrefix = Objects.requireNonNull(walletEncryptionPrefix);
		this.privateAddressHrp = Objects.requireNonNull(privateAddressHrp);
		this.defaultBirthdaySupplier = Objects.requireNonNull(defaultBirthdaySupplier);
		this.blockchainSupplier = Objects.requireNonNull(blockchainSupplier);
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public String getCurrencyCode() {
		return this.currencyCode;
	}

	public String getWalletDirectoryName() {
		return this.walletDirectoryName;
	}

	public String getQdnWalletSignature() {
		return this.qdnWalletSignature;
	}

	public String getWalletEncryptionPrefix() {
		return this.walletEncryptionPrefix;
	}

	public String getPrivateAddressHrp() {
		return this.privateAddressHrp;
	}

	public int getDefaultBirthday() {
		return this.defaultBirthdaySupplier.getAsInt();
	}

	public Bitcoiny getBlockchain() {
		return this.blockchainSupplier.get();
	}

	public Path getWalletsLibDirectory() {
		return Paths.get(Settings.getInstance().getWalletsPath(), this.walletDirectoryName, "lib");
	}

	public Path getRustLibOuterDirectory() {
		String sigPrefix = this.qdnWalletSignature.substring(0, 8);
		return Paths.get(Settings.getInstance().getWalletsPath(), this.walletDirectoryName, "lib", sigPrefix);
	}

	public Path getWalletPath(String filename) {
		return Paths.get(Settings.getInstance().getWalletsPath(), this.walletDirectoryName, filename);
	}
}
