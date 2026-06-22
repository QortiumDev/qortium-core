package org.qortium.data.account;

/**
 * Ordering options for the account trust-derivation listing.
 *
 * <p>{@link #VOTE_WEIGHT} and {@link #BLOCKS_MINTED} rely on per-account minting data that is only
 * present on live derivation rows; stored snapshots do not carry it, so those orderings are only
 * valid when the listing is computed live (see {@link #requiresLiveDerivation()}).
 */
public enum AccountTrustDerivationOrder {
	ACCOUNT,
	LEVEL,
	SCORE,
	VOTE_WEIGHT,
	BLOCKS_MINTED;

	/**
	 * Parses an API-supplied ordering token, accepting a few friendly synonyms.
	 *
	 * @return the matching ordering, or {@code null} when the input is blank or unrecognised
	 */
	public static AccountTrustDerivationOrder fromString(String value) {
		if (value == null || value.trim().isEmpty())
			return null;

		switch (value.trim().toLowerCase()) {
			case "account":
				return ACCOUNT;
			case "level":
				return LEVEL;
			case "score":
				return SCORE;
			case "voteweight":
			case "vote_weight":
			case "weight":
				return VOTE_WEIGHT;
			case "blocksminted":
			case "blocks_minted":
			case "blocks":
				return BLOCKS_MINTED;
			default:
				return null;
		}
	}

	/** @return true when this ordering needs live-only fields that stored snapshots do not carry */
	public boolean requiresLiveDerivation() {
		return this == VOTE_WEIGHT || this == BLOCKS_MINTED;
	}
}
