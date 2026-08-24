package org.qortium.crosschain;

import org.qortium.settings.Settings;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.BooleanSupplier;
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
	private final BooleanSupplier unifiedWalletEnabledSupplier;
	private final Supplier<String> unifiedQdnWalletSignatureSupplier;
	private final BooleanSupplier unifiedDebugLoggingSupplier;

	public ZcashFamilyWalletConfig(String displayName, String currencyCode, String walletDirectoryName,
			String qdnWalletSignature, String walletEncryptionPrefix, String privateAddressHrp,
			IntSupplier defaultBirthdaySupplier, Supplier<? extends Bitcoiny> blockchainSupplier) {
		this(displayName, currencyCode, walletDirectoryName, qdnWalletSignature, walletEncryptionPrefix,
				privateAddressHrp, defaultBirthdaySupplier, blockchainSupplier, () -> false, () -> null, () -> false);
	}

	public ZcashFamilyWalletConfig(String displayName, String currencyCode, String walletDirectoryName,
			String qdnWalletSignature, String walletEncryptionPrefix, String privateAddressHrp,
			IntSupplier defaultBirthdaySupplier, Supplier<? extends Bitcoiny> blockchainSupplier,
			BooleanSupplier unifiedWalletEnabledSupplier, Supplier<String> unifiedQdnWalletSignatureSupplier,
			BooleanSupplier unifiedDebugLoggingSupplier) {
		this.displayName = Objects.requireNonNull(displayName);
		this.currencyCode = Objects.requireNonNull(currencyCode);
		this.walletDirectoryName = Objects.requireNonNull(walletDirectoryName);
		this.qdnWalletSignature = Objects.requireNonNull(qdnWalletSignature);
		this.walletEncryptionPrefix = Objects.requireNonNull(walletEncryptionPrefix);
		this.privateAddressHrp = Objects.requireNonNull(privateAddressHrp);
		this.defaultBirthdaySupplier = Objects.requireNonNull(defaultBirthdaySupplier);
		this.blockchainSupplier = Objects.requireNonNull(blockchainSupplier);
		this.unifiedWalletEnabledSupplier = Objects.requireNonNull(unifiedWalletEnabledSupplier);
		this.unifiedQdnWalletSignatureSupplier = Objects.requireNonNull(unifiedQdnWalletSignatureSupplier);
		this.unifiedDebugLoggingSupplier = Objects.requireNonNull(unifiedDebugLoggingSupplier);
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

	public boolean isUnifiedWalletEnabled() {
		return this.unifiedWalletEnabledSupplier.getAsBoolean();
	}

	/** Returns null without consulting the signature source while Unified mode is disabled. */
	public String getUnifiedQdnWalletSignature() {
		if (!isUnifiedWalletEnabled())
			return null;

		return this.unifiedQdnWalletSignatureSupplier.get();
	}

	/** Returns false without consulting the debug source while Unified mode is disabled. */
	public boolean isUnifiedDebugLoggingEnabled() {
		return isUnifiedWalletEnabled() && this.unifiedDebugLoggingSupplier.getAsBoolean();
	}

	public Path getWalletsLibDirectory() {
		return Paths.get(Settings.getInstance().getWalletsPath(), this.walletDirectoryName, "lib");
	}

	public Path getRustLibOuterDirectory() {
		return Paths.get(Settings.getInstance().getWalletsPath(), this.walletDirectoryName, "lib", this.qdnWalletSignature);
	}

	public Path getWalletPath(String filename) {
		return Paths.get(Settings.getInstance().getWalletsPath(), this.walletDirectoryName, filename);
	}
}
