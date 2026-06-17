package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.AccessMode;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.data.voting.PollOptionData;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
@XmlDiscriminatorValue("CREATE_POLL")
public class CreatePollTransactionData extends TransactionData {


	@Schema(description = "Poll creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] pollCreatorPublicKey;
	
	// Properties
	@Schema(accessMode = AccessMode.READ_ONLY, description = "assigned poll ID")
	private Integer pollId = null;
	private String owner;
	private String pollName;
	private String description;
	@Schema(description = "Poll options as separate array entries; do not submit one comma-separated string")
	private List<PollOptionData> pollOptions;
	@Schema(description = "Optional poll start time in epoch milliseconds. Null means the poll starts when published.")
	private Long startTime;
	private Long endTime;

	// Constructors

	// For JAXB
	protected CreatePollTransactionData() {
		super(TransactionType.CREATE_POLL);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.pollCreatorPublicKey;
	}
	
	public CreatePollTransactionData(BaseTransactionData baseTransactionData,
			String owner, String pollName, String description, List<PollOptionData> pollOptions) {
		this(baseTransactionData, owner, pollName, description, pollOptions, null);
	}

	public CreatePollTransactionData(BaseTransactionData baseTransactionData,
			String owner, String pollName, String description, List<PollOptionData> pollOptions, Long endTime) {
		this(baseTransactionData, owner, pollName, description, pollOptions, null, endTime, null);
	}

	public CreatePollTransactionData(BaseTransactionData baseTransactionData,
			String owner, String pollName, String description, List<PollOptionData> pollOptions, Long startTime, Long endTime) {
		this(baseTransactionData, owner, pollName, description, pollOptions, startTime, endTime, null);
	}

	public CreatePollTransactionData(BaseTransactionData baseTransactionData,
			String owner, String pollName, String description, List<PollOptionData> pollOptions, Long endTime, Integer pollId) {
		this(baseTransactionData, owner, pollName, description, pollOptions, null, endTime, pollId);
	}

	public CreatePollTransactionData(BaseTransactionData baseTransactionData,
			String owner, String pollName, String description, List<PollOptionData> pollOptions, Long startTime, Long endTime, Integer pollId) {
		super(Transaction.TransactionType.CREATE_POLL, baseTransactionData);

		this.creatorPublicKey = baseTransactionData.creatorPublicKey;
		this.pollId = pollId;
		this.owner = owner;
		this.pollName = pollName;
		this.description = description == null ? "" : description;
		this.pollOptions = pollOptions;
		this.startTime = startTime;
		this.endTime = endTime;
	}

	// Getters/setters

	public byte[] getPollCreatorPublicKey() { return this.creatorPublicKey; }
	public Integer getPollId() {
		return this.pollId;
	}

	public void setPollId(Integer pollId) {
		this.pollId = pollId;
	}

	public String getOwner() {
		return this.owner;
	}

	public String getPollName() {
		return this.pollName;
	}

	public String getDescription() {
		return this.description == null ? "" : this.description;
	}

	public List<PollOptionData> getPollOptions() {
		return this.pollOptions;
	}

	public Long getStartTime() {
		return this.startTime;
	}

	public Long getEndTime() {
		return this.endTime;
	}

}
