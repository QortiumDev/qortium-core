package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** One account node in a trust-graph response: identity plus its derived trust standing. */
@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustGraphNodeData {

	private String address;
	private byte[] publicKey;
	private AccountTrustStatus status;
	private int level;
	private long score;
	private boolean seedMember;

	protected AccountTrustGraphNodeData() {
	}

	public AccountTrustGraphNodeData(String address, byte[] publicKey, AccountTrustStatus status, int level, long score,
			boolean seedMember) {
		this.address = address;
		this.publicKey = publicKey;
		this.status = status;
		this.level = level;
		this.score = score;
		this.seedMember = seedMember;
	}

	public String getAddress() {
		return this.address;
	}

	public byte[] getPublicKey() {
		return this.publicKey;
	}

	public AccountTrustStatus getStatus() {
		return this.status;
	}

	public int getLevel() {
		return this.level;
	}

	public long getScore() {
		return this.score;
	}

	public boolean isSeedMember() {
		return this.seedMember;
	}
}
