package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.data.account.AccountTrustCategoryPoliciesData;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlAccessorType(XmlAccessType.FIELD)
public class ChainParameterEffectiveValue {

	public enum Source {
		CONFIG,
		ON_CHAIN
	}

	@Schema(description = "numeric chain parameter ID")
	public int id;

	@Schema(description = "canonical chain parameter name")
	public String name;

	@Schema(description = "human-facing decoded value type")
	public String valueType;

	@Schema(description = "height used for this effective-value lookup")
	public int height;

	@Schema(description = "whether the current effective value comes from blockchain config or an approved on-chain overlay")
	public Source source;

	@Schema(description = "transaction signature for the active approved overlay, if source is ON_CHAIN")
	public byte[] signature;

	@Schema(description = "activation height for the active approved overlay, if source is ON_CHAIN")
	public Integer activationHeight;

	@Schema(description = "canonical binary value currently effective at this height")
	public byte[] value;

	@Schema(description = "human-readable decoded current value")
	public String displayValue;

	@Schema(description = "decoded current amount value for amount-like parameters", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public Long amount;

	@Schema(description = "decoded current plain long value for long-like non-amount parameters")
	public Long longValue;

	@Schema(description = "decoded current integer value for integer-like parameters")
	public Integer integerValue;

	@Schema(description = "decoded current integer list value for integer-list parameters")
	public int[] integerValues;

	@Schema(description = "decoded current account trust category policy table for account-trust-category-policy parameters")
	public AccountTrustCategoryPoliciesData accountTrustCategoryPolicies;

	@Schema(description = "transaction signature for the next approved future overlay, if one exists")
	public byte[] nextSignature;

	@Schema(description = "activation height for the next approved future overlay, if one exists")
	public Integer nextActivationHeight;

	@Schema(description = "canonical binary value for the next approved future overlay, if one exists")
	public byte[] nextValue;

	@Schema(description = "human-readable decoded value for the next approved future overlay, if one exists")
	public String nextDisplayValue;

	@Schema(description = "decoded next amount value for amount-like parameters", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public Long nextAmount;

	@Schema(description = "decoded next plain long value for long-like non-amount parameters")
	public Long nextLongValue;

	@Schema(description = "decoded next integer value for integer-like parameters")
	public Integer nextIntegerValue;

	@Schema(description = "decoded next integer list value for integer-list parameters")
	public int[] nextIntegerValues;

	@Schema(description = "decoded next account trust category policy table for account-trust-category-policy parameters")
	public AccountTrustCategoryPoliciesData nextAccountTrustCategoryPolicies;

	public ChainParameterEffectiveValue() {
	}

}
