package org.qortium.data.voting;

import java.util.List;

public class PollData {

	// Properties
	private Integer pollId;
	private byte[] creatorPublicKey;
	private String owner;
	private String pollName;
	private String description;
	private List<PollOptionData> pollOptions;
	private long published;
	private Long endTime;

	// Constructors

	// For JAXB
	protected PollData() {
		super();
	}

	public PollData(byte[] creatorPublicKey, String owner, String pollName, String description, List<PollOptionData> pollOptions, long published) {
		this(creatorPublicKey, owner, pollName, description, pollOptions, published, null);
	}

	public PollData(byte[] creatorPublicKey, String owner, String pollName, String description, List<PollOptionData> pollOptions, long published, Long endTime) {
		this(null, creatorPublicKey, owner, pollName, description, pollOptions, published, endTime);
	}

	public PollData(Integer pollId, byte[] creatorPublicKey, String owner, String pollName, String description, List<PollOptionData> pollOptions, long published, Long endTime) {
		this.pollId = pollId;
		this.creatorPublicKey = creatorPublicKey;
		this.owner = owner;
		this.pollName = pollName;
		this.description = description;
		this.pollOptions = pollOptions;
		this.published = published;
		this.endTime = endTime;
	}

	// Getters/setters

	public Integer getPollId() {
		return this.pollId;
	}

	public void setPollId(Integer pollId) {
		this.pollId = pollId;
	}

	public byte[] getCreatorPublicKey() {
		return this.creatorPublicKey;
	}

	public void setCreatorPublicKey(byte[] creatorPublicKey) {
		this.creatorPublicKey = creatorPublicKey;
	}

	public String getOwner() {
		return this.owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	public String getPollName() {
		return this.pollName;
	}

	public void setPollName(String pollName) {
		this.pollName = pollName;
	}

	public String getDescription() {
		return this.description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public List<PollOptionData> getPollOptions() {
		return this.pollOptions;
	}

	public void setPollOptions(List<PollOptionData> pollOptions) {
		this.pollOptions = pollOptions;
	}

	public long getPublished() {
		return this.published;
	}

	public void setPublished(long published) {
		this.published = published;
	}

	public Long getEndTime() {
		return this.endTime;
	}

	public void setEndTime(Long endTime) {
		this.endTime = endTime;
	}

	public boolean isClosedAt(long timestamp) {
		return this.endTime != null && timestamp >= this.endTime;
	}

}
