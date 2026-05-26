package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.crypto.Crypto;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
@XmlDiscriminatorValue("VOTE_ON_POLL")
public class VoteOnPollTransactionData extends TransactionData {

	// Properties
	@Schema(description = "Vote creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] voterPublicKey;
	@Schema(description = "Stable numeric poll ID; vote transactions do not use the editable poll name")
	private int pollId;
	@Schema(description = "Poll option index: 0 removes an existing vote, 1 selects the first poll option, 2 selects the second, and so on")
	private int optionIndex;
	// For internal use when orphaning
	@XmlTransient
	@Schema(hidden = true)
	private Integer previousOptionIndex;

	// Constructors

	// For JAXB
	protected VoteOnPollTransactionData() {
		super(TransactionType.VOTE_ON_POLL);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.voterPublicKey;
	}

	/** From repository */
	public VoteOnPollTransactionData(BaseTransactionData baseTransactionData, int pollId, int optionIndex, Integer previousOptionIndex) {
		super(TransactionType.VOTE_ON_POLL, baseTransactionData);

		this.voterPublicKey = baseTransactionData.creatorPublicKey;
		this.pollId = pollId;
		this.optionIndex = optionIndex;
		this.previousOptionIndex = previousOptionIndex;
	}

	/** From network/API */
	public VoteOnPollTransactionData(BaseTransactionData baseTransactionData, int pollId, int optionIndex) {
		this(baseTransactionData, pollId, optionIndex, null);
	}

	// Getters / setters

	public byte[] getVoterPublicKey() {
		return this.voterPublicKey;
	}

	@XmlElement(name = "voterAddress")
	@Schema(description = "Voter's address")
	protected String getVoterAddress() {
		return this.voterPublicKey == null ? null : Crypto.toAddress(this.voterPublicKey);
	}

	public int getPollId() {
		return this.pollId;
	}

	public int getOptionIndex() {
		return this.optionIndex;
	}

	public Integer getPreviousOptionIndex() {
		return this.previousOptionIndex;
	}

	public void setPreviousOptionIndex(Integer previousOptionIndex) {
		this.previousOptionIndex = previousOptionIndex;
	}

}
