package org.qortium.data.account;

import java.util.Locale;

public enum AccountRatingCategory {
	SUBJECT(0),
	PLAYER(1),
	TRAINER(2),
	MANAGER(3);

	public final int value;

	AccountRatingCategory(int value) {
		this.value = value;
	}

	public static AccountRatingCategory valueOf(int value) {
		for (AccountRatingCategory category : values())
			if (category.value == value)
				return category;

		return null;
	}

	public static AccountRatingCategory parse(String value) {
		if (value == null || value.trim().isEmpty())
			return null;

		try {
			return AccountRatingCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
