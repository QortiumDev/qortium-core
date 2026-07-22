package org.qortium.data.at;

public final class ATMapEntryData {

	private final String atAddress;
	private final long key1;
	private final long key2;
	private final long value;

	public ATMapEntryData(String atAddress, long key1, long key2, long value) {
		this.atAddress = atAddress;
		this.key1 = key1;
		this.key2 = key2;
		this.value = value;
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

	public long getValue() {
		return this.value;
	}

}
