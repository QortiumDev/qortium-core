package org.qortium.data.at;

public class ATStateData {

	// Properties
	private String atAddress;
	private Integer height;
	private byte[] stateData;
	private byte[] stateHash;
	private byte[] mapRoot;
	private Long fees;
	private boolean isInitial;

	// Chain AT-specific
	private Long sleepUntilMessageTimestamp;

	// Constructors

	/** Create new ATStateData */
	public ATStateData(String atAddress, Integer height, byte[] stateData, byte[] stateHash, Long fees,
			boolean isInitial, Long sleepUntilMessageTimestamp) {
		this(atAddress, height, stateData, stateHash, null, fees, isInitial, sleepUntilMessageTimestamp);
	}

	/** Create new ATStateData with a committed AT map root. */
	public ATStateData(String atAddress, Integer height, byte[] stateData, byte[] stateHash, byte[] mapRoot, Long fees,
			boolean isInitial, Long sleepUntilMessageTimestamp) {
		this.atAddress = atAddress;
		this.height = height;
		this.stateData = stateData;
		this.stateHash = stateHash;
		this.mapRoot = mapRoot;
		this.fees = fees;
		this.isInitial = isInitial;
		this.sleepUntilMessageTimestamp = sleepUntilMessageTimestamp;
	}

	/** For recreating per-block ATStateData from repository where not all info is needed */
	public ATStateData(String atAddress, int height, byte[] stateHash, Long fees, boolean isInitial) {
		this(atAddress, height, null, stateHash, fees, isInitial, null);
	}

	/** For recreating per-block ATStateData with a committed AT map root. */
	public ATStateData(String atAddress, int height, byte[] stateHash, byte[] mapRoot, Long fees, boolean isInitial) {
		this(atAddress, height, null, stateHash, mapRoot, fees, isInitial, null);
	}

	/** For creating ATStateData from serialized bytes when we don't have all the info */
	public ATStateData(String atAddress, byte[] stateHash, Long fees) {
		// This won't ever be initial AT state from deployment, as that's never serialized over the network.
		this(atAddress, null, null, stateHash, fees, false, null);
	}

	// Getters / setters

	public String getATAddress() {
		return this.atAddress;
	}

	public Integer getHeight() {
		return this.height;
	}

	// Likely to be used when block received over network is attached to blockchain
	public void setHeight(Integer height) {
		this.height = height;
	}

	public byte[] getStateData() {
		return this.stateData;
	}

	public byte[] getStateHash() {
		return this.stateHash;
	}

	public byte[] getMapRoot() {
		return this.mapRoot;
	}

	public Long getFees() {
		return this.fees;
	}

	public boolean isInitial() {
		return this.isInitial;
	}

	public Long getSleepUntilMessageTimestamp() {
		return this.sleepUntilMessageTimestamp;
	}

	public void setSleepUntilMessageTimestamp(Long sleepUntilMessageTimestamp) {
		this.sleepUntilMessageTimestamp = sleepUntilMessageTimestamp;
	}

}
