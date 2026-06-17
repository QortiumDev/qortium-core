package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.crypto.Crypto;
import org.qortium.data.voting.PollData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
@XmlDiscriminatorValue("UPDATE_POLL")
public class UpdatePollTransactionData extends TransactionData {

	@Schema(description = "Poll owner's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] ownerPublicKey;
	@Schema(description = "Stable numeric poll ID for the poll being updated")
	private int pollId;
	private String newPollName;
	private String newDescription;
	@Schema(description = "Replacement poll options as separate array entries; do not submit one comma-separated string")
	private List<PollOptionData> newPollOptions;
	@Schema(description = "Replacement poll start time. Null means the poll starts immediately; the start time can only be changed before the poll starts.")
	private Long newStartTime;
	@Schema(description = "Replacement poll end time. Full edits are allowed only while there are no active votes; after active votes exist, only extending an existing future end time is allowed")
	private Long newEndTime;

	@XmlTransient
	@Schema(hidden = true)
	private String previousPollName;
	@XmlTransient
	@Schema(hidden = true)
	private String previousDescription;
	@XmlTransient
	@Schema(hidden = true)
	private List<PollOptionData> previousPollOptions;
	@XmlTransient
	@Schema(hidden = true)
	private Long previousStartTime;
	@XmlTransient
	@Schema(hidden = true)
	private Long previousEndTime;

	protected UpdatePollTransactionData() {
		super(TransactionType.UPDATE_POLL);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.ownerPublicKey;
	}

	/** From repository */
	public UpdatePollTransactionData(BaseTransactionData baseTransactionData, int pollId, String newPollName,
			String newDescription, List<PollOptionData> newPollOptions, Long newEndTime, String previousPollName,
			String previousDescription, List<PollOptionData> previousPollOptions, Long previousEndTime) {
		this(baseTransactionData, pollId, newPollName, newDescription, newPollOptions, null, newEndTime,
				previousPollName, previousDescription, previousPollOptions, null, previousEndTime);
	}

	/** From repository */
	public UpdatePollTransactionData(BaseTransactionData baseTransactionData, int pollId, String newPollName,
			String newDescription, List<PollOptionData> newPollOptions, Long newStartTime, Long newEndTime,
			String previousPollName, String previousDescription, List<PollOptionData> previousPollOptions,
			Long previousStartTime, Long previousEndTime) {
		super(TransactionType.UPDATE_POLL, baseTransactionData);

		this.ownerPublicKey = baseTransactionData.creatorPublicKey;
		this.pollId = pollId;
		this.newPollName = newPollName;
		this.newDescription = newDescription == null ? "" : newDescription;
		this.newPollOptions = newPollOptions;
		this.newStartTime = newStartTime;
		this.newEndTime = newEndTime;
		this.previousPollName = previousPollName;
		this.previousDescription = previousDescription == null && previousPollName != null ? "" : previousDescription;
		this.previousPollOptions = previousPollOptions;
		this.previousStartTime = previousStartTime;
		this.previousEndTime = previousEndTime;
	}

	/** From network/API */
	public UpdatePollTransactionData(BaseTransactionData baseTransactionData, int pollId, String newPollName,
			String newDescription, List<PollOptionData> newPollOptions, Long newEndTime) {
		this(baseTransactionData, pollId, newPollName, newDescription, newPollOptions, newEndTime, null, null, null, null);
	}

	/** From network/API */
	public UpdatePollTransactionData(BaseTransactionData baseTransactionData, int pollId, String newPollName,
			String newDescription, List<PollOptionData> newPollOptions, Long newStartTime, Long newEndTime) {
		this(baseTransactionData, pollId, newPollName, newDescription, newPollOptions, newStartTime, newEndTime,
				null, null, null, null, null);
	}

	public byte[] getOwnerPublicKey() {
		return this.ownerPublicKey;
	}

	@XmlElement(name = "ownerAddress")
	@Schema(description = "Poll owner's address")
	protected String getOwnerAddress() {
		return this.ownerPublicKey == null ? null : Crypto.toAddress(this.ownerPublicKey);
	}

	public int getPollId() {
		return this.pollId;
	}

	public String getNewPollName() {
		return this.newPollName;
	}

	public String getNewDescription() {
		return this.newDescription == null ? "" : this.newDescription;
	}

	public List<PollOptionData> getNewPollOptions() {
		return this.newPollOptions;
	}

	public Long getNewStartTime() {
		return this.newStartTime;
	}

	public Long getNewEndTime() {
		return this.newEndTime;
	}

	public String getPreviousPollName() {
		return this.previousPollName;
	}

	public String getPreviousDescription() {
		return this.previousDescription == null && this.previousPollName != null ? "" : this.previousDescription;
	}

	public List<PollOptionData> getPreviousPollOptions() {
		return this.previousPollOptions;
	}

	public Long getPreviousStartTime() {
		return this.previousStartTime;
	}

	public Long getPreviousEndTime() {
		return this.previousEndTime;
	}

	public void setPreviousPollData(PollData pollData) {
		this.previousPollName = pollData.getPollName();
		this.previousDescription = pollData.getDescription();
		this.previousPollOptions = pollData.getPollOptions();
		this.previousStartTime = pollData.getStartTime();
		this.previousEndTime = pollData.getEndTime();
	}

	public void clearPreviousPollData() {
		this.previousPollName = null;
		this.previousDescription = null;
		this.previousPollOptions = null;
		this.previousStartTime = null;
		this.previousEndTime = null;
	}

}
