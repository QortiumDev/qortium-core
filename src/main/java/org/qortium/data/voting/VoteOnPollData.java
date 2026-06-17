package org.qortium.data.voting;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.crypto.Crypto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VoteOnPollData {

	// Properties
	private Integer pollId;
	private String pollName;
	private byte[] voterPublicKey;
	private int optionIndex;
	@Schema(description = "Selected poll option indexes. Empty means no active vote.")
	private List<Integer> optionIndexes;

	// Constructors

	// For JAXB
	protected VoteOnPollData() {
		super();
	}

	public VoteOnPollData(String pollName, byte[] voterPublicKey, int optionIndex) {
		this(null, pollName, voterPublicKey, optionIndex);
	}

	public VoteOnPollData(String pollName, byte[] voterPublicKey, List<Integer> optionIndexes) {
		this(null, pollName, voterPublicKey, optionIndexes);
	}

	public VoteOnPollData(int pollId, byte[] voterPublicKey, int optionIndex) {
		this(pollId, null, voterPublicKey, optionIndex);
	}

	public VoteOnPollData(int pollId, byte[] voterPublicKey, List<Integer> optionIndexes) {
		this(pollId, null, voterPublicKey, optionIndexes);
	}

	public VoteOnPollData(Integer pollId, String pollName, byte[] voterPublicKey, int optionIndex) {
		this(pollId, pollName, voterPublicKey,
				optionIndex == 0 ? Collections.emptyList() : Collections.singletonList(optionIndex));
	}

	public VoteOnPollData(Integer pollId, String pollName, byte[] voterPublicKey, List<Integer> optionIndexes) {
		this.pollId = pollId;
		this.pollName = pollName;
		this.voterPublicKey = voterPublicKey;
		this.optionIndexes = normalizeOptionIndexes(optionIndexes);
		this.optionIndex = this.optionIndexes.isEmpty() ? 0 : this.optionIndexes.get(0);
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
		this.optionIndexes = optionIndex == 0 ? Collections.emptyList() : Collections.singletonList(optionIndex);
	}

	public List<Integer> getOptionIndexes() {
		return this.optionIndexes == null ? Collections.emptyList() : Collections.unmodifiableList(this.optionIndexes);
	}

	public void setOptionIndexes(List<Integer> optionIndexes) {
		this.optionIndexes = normalizeOptionIndexes(optionIndexes);
		this.optionIndex = this.optionIndexes.isEmpty() ? 0 : this.optionIndexes.get(0);
	}

	private static List<Integer> normalizeOptionIndexes(List<Integer> optionIndexes) {
		if (optionIndexes == null || optionIndexes.isEmpty())
			return new ArrayList<>();

		return new ArrayList<>(optionIndexes);
	}

}
