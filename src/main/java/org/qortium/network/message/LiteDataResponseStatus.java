package org.qortium.network.message;

public enum LiteDataResponseStatus {

	DATA(1),
	UNKNOWN(2);

	private final int value;

	LiteDataResponseStatus(int value) {
		this.value = value;
	}

	public int getValue() {
		return this.value;
	}

	public static LiteDataResponseStatus valueOf(int value) throws MessageException {
		for (LiteDataResponseStatus status : LiteDataResponseStatus.values())
			if (status.value == value)
				return status;

		throw new MessageException(String.format("Unsupported lite data response status: %d", value));
	}

}
