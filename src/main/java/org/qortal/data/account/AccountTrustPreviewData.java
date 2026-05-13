package org.qortal.data.account;

import org.qortal.crypto.Crypto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustPreviewData {

	private byte[] targetPublicKey;
	private String targetAddress;
	private AccountTrustStatus trustStatus;
	private int trustStatusValue;
	private int trustWeightPercent;
	private int inboundTrustedCount;
	private int inboundKnownCount;
	private int inboundUntrustedCount;
	private int inboundTotalRatingCount;
	private int outboundTrustedCount;
	private int outboundKnownCount;
	private int outboundUntrustedCount;
	private int outboundTotalRatingCount;
	private int mutualPositiveCount;
	private int positiveScore;
	private int negativeScore;
	private int netScore;

	protected AccountTrustPreviewData() {
	}

	public AccountTrustPreviewData(byte[] targetPublicKey, AccountTrustStatus trustStatus,
			int inboundTrustedCount, int inboundKnownCount, int inboundUntrustedCount,
			int outboundTrustedCount, int outboundKnownCount, int outboundUntrustedCount,
			int mutualPositiveCount) {
		this(targetPublicKey, Crypto.toAddress(targetPublicKey), trustStatus,
				inboundTrustedCount, inboundKnownCount, inboundUntrustedCount,
				outboundTrustedCount, outboundKnownCount, outboundUntrustedCount,
				mutualPositiveCount);
	}

	public AccountTrustPreviewData(byte[] targetPublicKey, String targetAddress, AccountTrustStatus trustStatus,
			int inboundTrustedCount, int inboundKnownCount, int inboundUntrustedCount,
			int outboundTrustedCount, int outboundKnownCount, int outboundUntrustedCount,
			int mutualPositiveCount) {
		AccountTrustStatus storedTrustStatus = trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus;

		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetAddress;
		this.trustStatus = storedTrustStatus;
		this.trustStatusValue = storedTrustStatus.getValue();
		this.trustWeightPercent = storedTrustStatus.getVoteWeightPercent();
		this.inboundTrustedCount = inboundTrustedCount;
		this.inboundKnownCount = inboundKnownCount;
		this.inboundUntrustedCount = inboundUntrustedCount;
		this.inboundTotalRatingCount = inboundTrustedCount + inboundKnownCount + inboundUntrustedCount;
		this.outboundTrustedCount = outboundTrustedCount;
		this.outboundKnownCount = outboundKnownCount;
		this.outboundUntrustedCount = outboundUntrustedCount;
		this.outboundTotalRatingCount = outboundTrustedCount + outboundKnownCount + outboundUntrustedCount;
		this.mutualPositiveCount = mutualPositiveCount;
		this.positiveScore = inboundTrustedCount * 2 + inboundKnownCount + mutualPositiveCount * 2;
		this.negativeScore = inboundUntrustedCount * 4;
		this.netScore = this.positiveScore - this.negativeScore;
	}

	public byte[] getTargetPublicKey() {
		return this.targetPublicKey;
	}

	public String getTargetAddress() {
		return this.targetAddress;
	}

	public AccountTrustStatus getTrustStatus() {
		return this.trustStatus;
	}

	public int getTrustStatusValue() {
		return this.trustStatusValue;
	}

	public int getTrustWeightPercent() {
		return this.trustWeightPercent;
	}

	public int getInboundTrustedCount() {
		return this.inboundTrustedCount;
	}

	public int getInboundKnownCount() {
		return this.inboundKnownCount;
	}

	public int getInboundUntrustedCount() {
		return this.inboundUntrustedCount;
	}

	public int getInboundTotalRatingCount() {
		return this.inboundTotalRatingCount;
	}

	public int getOutboundTrustedCount() {
		return this.outboundTrustedCount;
	}

	public int getOutboundKnownCount() {
		return this.outboundKnownCount;
	}

	public int getOutboundUntrustedCount() {
		return this.outboundUntrustedCount;
	}

	public int getOutboundTotalRatingCount() {
		return this.outboundTotalRatingCount;
	}

	public int getMutualPositiveCount() {
		return this.mutualPositiveCount;
	}

	public int getPositiveScore() {
		return this.positiveScore;
	}

	public int getNegativeScore() {
		return this.negativeScore;
	}

	public int getNetScore() {
		return this.netScore;
	}
}
