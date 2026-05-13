package org.qortal.data.account;

public enum AccountRatingLevel {
	UNTRUSTED(-1, false),
	UNKNOWN(0, false),
	KNOWN(1, true),
	TRUSTED(2, true);

	private final int value;
	private final boolean active;

	AccountRatingLevel(int value, boolean active) {
		this.value = value;
		this.active = active;
	}

	public int getValue() {
		return this.value;
	}

	public boolean isActive() {
		return this.active;
	}

	public static AccountRatingLevel valueOf(int value) {
		for (AccountRatingLevel level : values())
			if (level.value == value)
				return level;

		return null;
	}
}
