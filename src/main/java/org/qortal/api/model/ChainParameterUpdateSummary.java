package org.qortal.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.data.account.AccountTrustCategoryPoliciesData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.transaction.Transaction.ApprovalStatus;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlAccessorType(XmlAccessType.FIELD)
public class ChainParameterUpdateSummary {

	@Schema(description = "transaction signature")
	public byte[] signature;

	@Schema(description = "transaction timestamp in milliseconds since unix epoch")
	public long timestamp;

	@Schema(description = "block height containing the proposal, or null if unconfirmed")
	public Integer blockHeight;

	@Schema(description = "block height where group approval settled, if decided")
	public Integer approvalHeight;

	@Schema(description = "group ID responsible for approving the proposal")
	public int txGroupId;

	@Schema(description = "numeric chain parameter ID")
	public int parameterId;

	@Schema(description = "known name for the parameter ID")
	public String parameterName;

	@Schema(description = "block height from which the approved value becomes active")
	public int activationHeight;

	@Schema(description = "canonical binary value stored in the proposal")
	public byte[] value;

	@Schema(description = "human-facing decoded value type")
	public String valueType;

	@Schema(description = "human-readable decoded value")
	public String displayValue;

	@Schema(description = "decoded amount value for amount-like parameters", type = "number")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public Long amount;

	@Schema(description = "decoded plain long value for long-like non-amount parameters")
	public Long longValue;

	@Schema(description = "decoded integer value for integer-like parameters")
	public Integer integerValue;

	@Schema(description = "decoded integer list value for integer-list parameters")
	public int[] integerValues;

	@Schema(description = "decoded account trust category policy table for account-trust-category-policy parameters")
	public AccountTrustCategoryPoliciesData accountTrustCategoryPolicies;

	@Schema(description = "current group-approval status")
	public ApprovalStatus approvalStatus;

	@Schema(description = "group approval threshold")
	public ApprovalThreshold approvalThreshold;

	@Schema(description = "number of current approval authorities whose latest decision is yes")
	public int approvalCount;

	@Schema(description = "number of current approval authorities whose latest decision is no")
	public int rejectionCount;

	@Schema(description = "number of current accounts allowed to approve this group's transactions")
	public int approvalAuthorityCount;

	@Schema(description = "whether this approved proposal is the effective overlay at the current chain height")
	public boolean effectiveNow;

	public ChainParameterUpdateSummary() {
	}

}
