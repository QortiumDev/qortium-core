package org.qortal.repository;

import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingSummaryData;
import org.qortal.data.account.AccountTrustDerivationData;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.account.AccountTrustSummaryData;

import java.util.List;

public interface AccountRatingRepository {

	public AccountRatingData getRating(byte[] targetPublicKey, byte[] raterPublicKey) throws DataException;

	public AccountRatingData getRating(byte[] targetPublicKey, byte[] raterPublicKey, AccountRatingCategory category) throws DataException;

	public void save(AccountRatingData accountRatingData) throws DataException;

	public void delete(byte[] targetPublicKey, byte[] raterPublicKey) throws DataException;

	public void delete(byte[] targetPublicKey, byte[] raterPublicKey, AccountRatingCategory category) throws DataException;

	public AccountRatingSummaryData getRatingSummary(byte[] targetPublicKey, String targetAddress) throws DataException;

	public AccountRatingSummaryData getRatingSummary(byte[] targetPublicKey, String targetAddress, AccountRatingCategory category) throws DataException;

	public List<AccountRatingData> getRatings(byte[] targetPublicKey, byte[] raterPublicKey,
			Integer limit, Integer offset, Boolean reverse) throws DataException;

	public List<AccountRatingData> getRatings(byte[] targetPublicKey, byte[] raterPublicKey, AccountRatingCategory category,
			Integer limit, Integer offset, Boolean reverse) throws DataException;

	public void replaceTrustDerivationSnapshots(List<AccountTrustDerivationData> derivedAccounts, int snapshotHeight,
			long snapshotTimestamp) throws DataException;

	public AccountTrustSummaryData getTrustSummary(AccountRatingCategory activeCategory) throws DataException;

	public List<AccountTrustSnapshotData> getTrustDerivationSnapshots(Integer limit, Integer offset, Boolean reverse)
			throws DataException;

	public List<AccountTrustSnapshotData> getTrustDerivationSnapshots(String accountAddress) throws DataException;

	public List<AccountTrustSnapshotData> getTrustDerivationSnapshots(String accountAddress, AccountRatingCategory category,
			AccountTrustStatus status, Boolean seedMember, Integer minLevel, Integer limit, Integer offset, Boolean reverse)
			throws DataException;

	public List<AccountTrustSnapshotData> getTrustDerivationSnapshotsForDerivation(AccountTrustStatus status,
			AccountRatingCategory sortCategory, Boolean seedMember, Integer minLevel, Integer limit, Integer offset,
			Boolean reverse) throws DataException;

	public AccountTrustSnapshotData getTrustDerivationSnapshot(String accountAddress, AccountRatingCategory category)
			throws DataException;

}
