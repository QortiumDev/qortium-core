package org.qortium.data.at;

public final class ATMapChangeData {

	private final String atAddress;
	private final long key1;
	private final long key2;
	private final Long previousValue;
	private final Long newValue;

	public ATMapChangeData(String atAddress, long key1, long key2, Long previousValue, Long newValue) {
		this.atAddress = atAddress;
		this.key1 = key1;
		this.key2 = key2;
		this.previousValue = previousValue;
		this.newValue = newValue;
	}

	public String getATAddress() {
		return this.atAddress;
	}

	public long getKey1() {
		return this.key1;
	}

	public long getKey2() {
		return this.key2;
	}

	public Long getPreviousValue() {
		return this.previousValue;
	}

	public Long getNewValue() {
		return this.newValue;
	}

}
