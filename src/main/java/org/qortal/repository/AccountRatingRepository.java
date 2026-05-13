package org.qortal.repository;

import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingSummaryData;

import java.util.List;

public interface AccountRatingRepository {

	public AccountRatingData getRating(byte[] targetPublicKey, byte[] raterPublicKey) throws DataException;

	public void save(AccountRatingData accountRatingData) throws DataException;

	public void delete(byte[] targetPublicKey, byte[] raterPublicKey) throws DataException;

	public AccountRatingSummaryData getRatingSummary(byte[] targetPublicKey, String targetAddress) throws DataException;

	public List<AccountRatingData> getRatings(byte[] targetPublicKey, byte[] raterPublicKey,
			Integer limit, Integer offset, Boolean reverse) throws DataException;

}
