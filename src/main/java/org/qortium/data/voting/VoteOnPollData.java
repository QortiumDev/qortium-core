package org.qortium.data.voting;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.crypto.Crypto;

public class VoteOnPollData {

	// Properties
	private Integer pollId;
	private String pollName;
	private byte[] voterPublicKey;
	private int optionIndex;

	// Constructors

	// For JAXB
	protected VoteOnPollData() {
		super();
	}

	public VoteOnPollData(String pollName, byte[] voterPublicKey, int optionIndex) {
		this(null, pollName, voterPublicKey, optionIndex);
	}

	public VoteOnPollData(int pollId, byte[] voterPublicKey, int optionIndex) {
		this(pollId, null, voterPublicKey, optionIndex);
	}

	public VoteOnPollData(Integer pollId, String pollName, byte[] voterPublicKey, int optionIndex) {
		this.pollId = pollId;
		this.pollName = pollName;
		this.voterPublicKey = voterPublicKey;
		this.optionIndex = optionIndex;
	}

	// Getters/setters

	public Integer getPollId() {
		return this.pollId;
	}

	public void setPollId(Integer pollId) {
		this.pollId = pollId;
	}

	public String getPollName() {
		return this.pollName;
	}

	public void setPollName(String pollName) {
		this.pollName = pollName;
	}

	public byte[] getVoterPublicKey() {
		return this.voterPublicKey;
	}

	@Schema(description = "Voter's address")
	public String getVoterAddress() {
		return this.voterPublicKey == null ? null : Crypto.toAddress(this.voterPublicKey);
	}

	public void setVoterPublicKey(byte[] voterPublicKey) {
		this.voterPublicKey = voterPublicKey;
	}

	public int getOptionIndex() {
		return this.optionIndex;
	}

	public void setOptionIndex(int optionIndex) {
		this.optionIndex = optionIndex;
	}

}
