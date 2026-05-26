package org.qortium.data.account;

import org.qortium.crypto.Crypto;
import org.qortium.transaction.Transaction;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountRatingImpactPreviewData {

	private byte[] targetPublicKey;
	private String targetAddress;
	private byte[] raterPublicKey;
	private String raterAddress;
	private AccountRatingCategory category;
	private int categoryValue;
	private int candidateRating;
	private String candidateRatingDirection;
	private int candidateRatingConfidence;
	private Integer activeRating;
	private String activeRatingDirection;
	private Integer activeRatingConfidence;
	private Integer previewActiveRating;
	private String previewActiveRatingDirection;
	private Integer previewActiveRatingConfidence;
	private String validationResult;
	private int validationResultValue;
	private boolean canSubmit;
	private AccountRatingCooldownData cooldown;
	private AccountTrustDerivationData currentTrust;
	private AccountTrustDerivationData previewTrust;
	private AccountTrustCategoryData currentSelectedCategory;
	private AccountTrustCategoryData previewSelectedCategory;
	private boolean trustStatusChanged;
	private boolean trustWeightChanged;
	private boolean selectedCategoryLevelChanged;
	private boolean selectedCategoryScoreChanged;

	protected AccountRatingImpactPreviewData() {
	}

	public AccountRatingImpactPreviewData(byte[] targetPublicKey, byte[] raterPublicKey, AccountRatingCategory category,
			int candidateRating, Integer activeRating, Integer previewActiveRating,
			Transaction.ValidationResult validationResult, AccountRatingCooldownData cooldown,
			AccountTrustDerivationData currentTrust, AccountTrustDerivationData previewTrust,
			AccountTrustCategoryData currentSelectedCategory, AccountTrustCategoryData previewSelectedCategory) {
		AccountRatingCategory effectiveCategory = category == null ? AccountRatingCategory.SUBJECT : category;
		Transaction.ValidationResult effectiveValidationResult = validationResult == null
				? Transaction.ValidationResult.INVALID_ACCOUNT_RATING
				: validationResult;

		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetPublicKey == null ? null : Crypto.toAddress(targetPublicKey);
		this.raterPublicKey = raterPublicKey;
		this.raterAddress = raterPublicKey == null ? null : Crypto.toAddress(raterPublicKey);
		this.category = effectiveCategory;
		this.categoryValue = effectiveCategory.value;
		this.candidateRating = candidateRating;
		this.candidateRatingDirection = AccountRating.getDirection(candidateRating);
		this.candidateRatingConfidence = AccountRating.getConfidence(candidateRating);
		this.activeRating = activeRating;
		this.activeRatingDirection = direction(activeRating);
		this.activeRatingConfidence = confidence(activeRating);
		this.previewActiveRating = previewActiveRating;
		this.previewActiveRatingDirection = direction(previewActiveRating);
		this.previewActiveRatingConfidence = confidence(previewActiveRating);
		this.validationResult = effectiveValidationResult.name();
		this.validationResultValue = effectiveValidationResult.value;
		this.canSubmit = effectiveValidationResult == Transaction.ValidationResult.OK;
		this.cooldown = cooldown;
		this.currentTrust = currentTrust;
		this.previewTrust = previewTrust;
		this.currentSelectedCategory = currentSelectedCategory;
		this.previewSelectedCategory = previewSelectedCategory;
		this.trustStatusChanged = trustStatus(currentTrust) != trustStatus(previewTrust);
		this.trustWeightChanged = trustWeight(currentTrust) != trustWeight(previewTrust);
		this.selectedCategoryLevelChanged = categoryLevel(currentSelectedCategory) != categoryLevel(previewSelectedCategory);
		this.selectedCategoryScoreChanged = categoryScore(currentSelectedCategory) != categoryScore(previewSelectedCategory);
	}

	public byte[] getTargetPublicKey() {
		return this.targetPublicKey;
	}

	public String getTargetAddress() {
		return this.targetAddress;
	}

	public byte[] getRaterPublicKey() {
		return this.raterPublicKey;
	}

	public String getRaterAddress() {
		return this.raterAddress;
	}

	public AccountRatingCategory getCategory() {
		return this.category;
	}

	public int getCategoryValue() {
		return this.categoryValue;
	}

	public int getCandidateRating() {
		return this.candidateRating;
	}

	public String getCandidateRatingDirection() {
		return this.candidateRatingDirection;
	}

	public int getCandidateRatingConfidence() {
		return this.candidateRatingConfidence;
	}

	public Integer getActiveRating() {
		return this.activeRating;
	}

	public String getActiveRatingDirection() {
		return this.activeRatingDirection;
	}

	public Integer getActiveRatingConfidence() {
		return this.activeRatingConfidence;
	}

	public Integer getPreviewActiveRating() {
		return this.previewActiveRating;
	}

	public String getPreviewActiveRatingDirection() {
		return this.previewActiveRatingDirection;
	}

	public Integer getPreviewActiveRatingConfidence() {
		return this.previewActiveRatingConfidence;
	}

	public String getValidationResult() {
		return this.validationResult;
	}

	public int getValidationResultValue() {
		return this.validationResultValue;
	}

	public boolean isCanSubmit() {
		return this.canSubmit;
	}

	public AccountRatingCooldownData getCooldown() {
		return this.cooldown;
	}

	public AccountTrustDerivationData getCurrentTrust() {
		return this.currentTrust;
	}

	public AccountTrustDerivationData getPreviewTrust() {
		return this.previewTrust;
	}

	public AccountTrustCategoryData getCurrentSelectedCategory() {
		return this.currentSelectedCategory;
	}

	public AccountTrustCategoryData getPreviewSelectedCategory() {
		return this.previewSelectedCategory;
	}

	public boolean isTrustStatusChanged() {
		return this.trustStatusChanged;
	}

	public boolean isTrustWeightChanged() {
		return this.trustWeightChanged;
	}

	public boolean isSelectedCategoryLevelChanged() {
		return this.selectedCategoryLevelChanged;
	}

	public boolean isSelectedCategoryScoreChanged() {
		return this.selectedCategoryScoreChanged;
	}

	private static String direction(Integer rating) {
		return rating == null ? null : AccountRating.getDirection(rating);
	}

	private static Integer confidence(Integer rating) {
		return rating == null ? null : AccountRating.getConfidence(rating);
	}

	private static AccountTrustStatus trustStatus(AccountTrustDerivationData trust) {
		return trust == null ? AccountTrustStatus.UNVERIFIED : trust.getDerivedTrustStatus();
	}

	private static int trustWeight(AccountTrustDerivationData trust) {
		return trust == null ? AccountTrustStatus.UNVERIFIED.getVoteWeightPercent() : trust.getDerivedTrustWeightPercent();
	}

	private static int categoryLevel(AccountTrustCategoryData category) {
		return category == null ? 0 : category.getLevel();
	}

	private static long categoryScore(AccountTrustCategoryData category) {
		return category == null ? 0L : category.getScore();
	}
}
