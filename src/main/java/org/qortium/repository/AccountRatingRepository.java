package org.qortium.repository;

import org.qortium.data.account.AccountRatingData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingSummaryData;
import org.qortium.data.account.AccountTrustDerivationData;
import org.qortium.data.account.AccountTrustDerivationOrder;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.AccountTrustStatusChangeData;
import org.qortium.data.account.AccountTrustSummaryData;

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
			AccountRatingCategory sortCategory, AccountTrustDerivationOrder order, Boolean seedMember, Integer minLevel,
			Integer limit, Integer offset, Boolean reverse) throws DataException;

	/** Counts derivation rows matching the same filters, ignoring ordering and paging. */
	public long getTrustDerivationSnapshotCountForDerivation(AccountTrustStatus status, AccountRatingCategory sortCategory,
			Boolean seedMember, Integer minLevel) throws DataException;

	public AccountTrustSnapshotData getTrustDerivationSnapshot(String accountAddress, AccountRatingCategory category)
			throws DataException;

	public List<AccountTrustStatusChangeData> getTrustStatusChanges(String accountAddress, AccountRatingCategory category,
			AccountTrustStatus previousStatus, AccountTrustStatus newStatus, Integer limit, Integer offset,
			Boolean reverse) throws DataException;

	public Integer getLatestRatingChangeHeight(byte[] targetPublicKey, byte[] raterPublicKey,
			AccountRatingCategory category) throws DataException;

}
