package org.qortal.data.account;

import org.qortal.crypto.Crypto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountRatingSummaryData {

	private byte[] targetPublicKey;
	private String targetAddress;
	private int trustedCount;
	private int knownCount;
	private int untrustedCount;
	private int totalRatingCount;

	protected AccountRatingSummaryData() {
	}

	public AccountRatingSummaryData(byte[] targetPublicKey, int trustedCount, int knownCount, int untrustedCount) {
		this(targetPublicKey, Crypto.toAddress(targetPublicKey), trustedCount, knownCount, untrustedCount);
	}

	public AccountRatingSummaryData(byte[] targetPublicKey, String targetAddress, int trustedCount, int knownCount, int untrustedCount) {
		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetAddress;
		this.trustedCount = trustedCount;
		this.knownCount = knownCount;
		this.untrustedCount = untrustedCount;
		this.totalRatingCount = trustedCount + knownCount + untrustedCount;
	}

	public byte[] getTargetPublicKey() {
		return this.targetPublicKey;
	}

	public String getTargetAddress() {
		return this.targetAddress;
	}

	public int getTrustedCount() {
		return this.trustedCount;
	}

	public int getKnownCount() {
		return this.knownCount;
	}

	public int getUntrustedCount() {
		return this.untrustedCount;
	}

	public int getTotalRatingCount() {
		return this.totalRatingCount;
	}
}
