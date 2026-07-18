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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
	private Integer optionIndex;
	@Schema(description = "Poll option indexes. Empty or [0] removes an existing vote; real poll options start at 1.")
	private List<Integer> optionIndexes;
	// For internal use when orphaning
	@XmlTransient
	@Schema(hidden = true)
	private Integer previousOptionIndex;
	@XmlTransient
	@Schema(hidden = true)
	private List<Integer> previousOptionIndexes;

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
		this(baseTransactionData, pollId, optionIndex, previousOptionIndex == null ? null : Collections.singletonList(previousOptionIndex));
	}

	/** From repository */
	public VoteOnPollTransactionData(BaseTransactionData baseTransactionData, int pollId, int optionIndex, List<Integer> previousOptionIndexes) {
		this(baseTransactionData, pollId, optionIndex == 0 ? Collections.emptyList() : Collections.singletonList(optionIndex), previousOptionIndexes);
	}

	/** From repository */
	public VoteOnPollTransactionData(BaseTransactionData baseTransactionData, int pollId, List<Integer> optionIndexes,
			List<Integer> previousOptionIndexes) {
		super(TransactionType.VOTE_ON_POLL, baseTransactionData);

		this.voterPublicKey = baseTransactionData.creatorPublicKey;
		this.pollId = pollId;
		this.optionIndexes = copyOptionIndexes(optionIndexes);
		this.optionIndex = this.optionIndexes.size() <= 1 ? (this.optionIndexes.isEmpty() ? 0 : this.optionIndexes.get(0)) : null;
		this.previousOptionIndexes = previousOptionIndexes == null ? null : copyOptionIndexes(previousOptionIndexes);
		this.previousOptionIndex = this.previousOptionIndexes == null || this.previousOptionIndexes.isEmpty()
				? null
				: this.previousOptionIndexes.get(0);
	}

	/** From network/API */
	public VoteOnPollTransactionData(BaseTransactionData baseTransactionData, int pollId, int optionIndex) {
		this(baseTransactionData, pollId, optionIndex, (Integer) null);
	}

	/** From network/API */
	public VoteOnPollTransactionData(BaseTransactionData baseTransactionData, int pollId, List<Integer> optionIndexes) {
		this(baseTransactionData, pollId, optionIndexes, null);
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
		if (this.optionIndex != null)
			return this.optionIndex;

		List<Integer> selectedOptionIndexes = getSelectedOptionIndexes();
		return selectedOptionIndexes.isEmpty() ? 0 : selectedOptionIndexes.get(0);
	}

	@XmlTransient
	@Schema(hidden = true)
	public Integer getRawOptionIndex() {
		return this.optionIndex;
	}

	public List<Integer> getOptionIndexes() {
		return this.optionIndexes == null ? null : Collections.unmodifiableList(this.optionIndexes);
	}

	@XmlTransient
	@Schema(hidden = true)
	public List<Integer> getSelectedOptionIndexes() {
		if (this.optionIndexes != null)
			return Collections.unmodifiableList(this.optionIndexes);

		if (this.optionIndex == null || this.optionIndex == 0)
			return Collections.emptyList();

		return Collections.singletonList(this.optionIndex);
	}

	/**
	 * Sorts a multi-option selection into ascending order — the canonical serialized form.
	 * The repository returns stored selections ascending, so a transaction signed with any
	 * other order breaks its own signature once re-serialized for a block. Conflicting or
	 * invalid inputs are left untouched for validation to reject.
	 */
	public void normalizeOptionIndexOrder() {
		if (this.optionIndexes == null || this.optionIndexes.size() <= 1 || hasConflictingOptionInputs())
			return;

		for (Integer optionIndex : this.optionIndexes)
			if (optionIndex == null)
				return;

		Collections.sort(this.optionIndexes);
	}

	@XmlTransient
	@Schema(hidden = true)
	public boolean hasConflictingOptionInputs() {
		if (this.optionIndex == null || this.optionIndexes == null)
			return false;

		if (this.optionIndex == 0)
			return this.optionIndexes.size() != 0
					&& !(this.optionIndexes.size() == 1 && this.optionIndexes.get(0) != null && this.optionIndexes.get(0) == 0);

		return this.optionIndexes.size() != 1
				|| this.optionIndexes.get(0) == null
				|| this.optionIndexes.get(0).intValue() != this.optionIndex.intValue();
	}

	public Integer getPreviousOptionIndex() {
		return this.previousOptionIndex;
	}

	public void setPreviousOptionIndex(Integer previousOptionIndex) {
		this.previousOptionIndex = previousOptionIndex;
		this.previousOptionIndexes = previousOptionIndex == null ? null : Collections.singletonList(previousOptionIndex);
	}

	public List<Integer> getPreviousOptionIndexes() {
		return this.previousOptionIndexes == null ? null : Collections.unmodifiableList(this.previousOptionIndexes);
	}

	public void setPreviousOptionIndexes(List<Integer> previousOptionIndexes) {
		this.previousOptionIndexes = previousOptionIndexes == null ? null : copyOptionIndexes(previousOptionIndexes);
		this.previousOptionIndex = this.previousOptionIndexes == null || this.previousOptionIndexes.isEmpty()
				? null
				: this.previousOptionIndexes.get(0);
	}

	public void clearPreviousOptionIndexes() {
		this.previousOptionIndex = null;
		this.previousOptionIndexes = null;
	}

	private static List<Integer> copyOptionIndexes(List<Integer> optionIndexes) {
		if (optionIndexes == null || optionIndexes.isEmpty())
			return new ArrayList<>();

		return new ArrayList<>(optionIndexes);
	}

}
