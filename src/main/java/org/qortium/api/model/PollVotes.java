package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.data.voting.VoteOnPollData;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import java.util.List;

@Schema(description = "Poll vote info, including voters")
// All properties to be converted to JSON via JAX-RS
@XmlAccessorType(XmlAccessType.FIELD)
public class PollVotes {

    @Schema(description = "List of individual votes")
    @XmlElement(name = "votes")
    public List<VoteOnPollData> votes;

    @Schema(description = "Total number of votes")
    public Integer totalVotes;

    @Schema(description = "Total effective vote weight after trust-tier multipliers")
    public Integer totalWeight;

    @Schema(description = "Total raw blocksMinted weight before trust-tier multipliers")
    public Integer rawTotalWeight;

    @Schema(description = "List of vote counts for each option")
    public List<OptionCount> voteCounts;

    @Schema(description = "List of effective and raw vote weights for each option")
    public List<OptionWeight> voteWeights;

    @Schema(description = "Trust-tier audit details for individual votes")
    public List<VoteDetail> voteDetails;

    // For JAX-RS
    protected PollVotes() {
    }

    public PollVotes(List<VoteOnPollData> votes, Integer totalVotes, Integer totalWeight, List<OptionCount> voteCounts, List<OptionWeight> voteWeights) {
        this(votes, totalVotes, totalWeight, totalWeight, voteCounts, voteWeights, null);
    }

    public PollVotes(List<VoteOnPollData> votes, Integer totalVotes, Integer totalWeight, Integer rawTotalWeight,
                     List<OptionCount> voteCounts, List<OptionWeight> voteWeights, List<VoteDetail> voteDetails) {
        this.votes = votes;
        this.totalVotes = totalVotes;
        this.totalWeight = totalWeight;
        this.rawTotalWeight = rawTotalWeight;
        this.voteCounts = voteCounts;
        this.voteWeights = voteWeights;
        this.voteDetails = voteDetails;
    }

    @Schema(description = "Vote info")
    // All properties to be converted to JSON via JAX-RS
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class OptionCount {
        @Schema(description = "Option name")
        public String optionName;

        @Schema(description = "Vote count")
        public Integer voteCount;

        // For JAX-RS
        protected OptionCount() {
        }

        public OptionCount(String optionName, Integer voteCount) {
            this.optionName = optionName;
            this.voteCount = voteCount;
        }
    }

    @Schema(description = "Vote weights")
    // All properties to be converted to JSON via JAX-RS
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class OptionWeight {
        @Schema(description = "Option name")
        public String optionName;

        @Schema(description = "Effective vote weight after trust-tier multipliers")
        public Integer voteWeight;

        @Schema(description = "Raw blocksMinted vote weight before trust-tier multipliers")
        public Integer rawVoteWeight;

        // For JAX-RS
        protected OptionWeight() {
        }

        public OptionWeight(String optionName, Integer voteWeight) {
            this(optionName, voteWeight, voteWeight);
        }

        public OptionWeight(String optionName, Integer voteWeight, Integer rawVoteWeight) {
            this.optionName = optionName;
            this.voteWeight = voteWeight;
            this.rawVoteWeight = rawVoteWeight;
        }
    }

    @Schema(description = "Per-vote trust-tier audit details")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class VoteDetail {
        @Schema(description = "Voter address")
        public String voterAddress;

        @Schema(description = "Selected option index")
        public Integer optionIndex;

        @Schema(description = "Raw blocksMinted vote weight before trust-tier multipliers")
        public Integer rawVoteWeight;

        @Schema(description = "Trust status name")
        public String trustStatus;

        @Schema(description = "Trust status storage value")
        public Integer trustStatusValue;

        @Schema(description = "Trust-tier vote multiplier percent")
        public Integer trustWeightPercent;

        @Schema(description = "Effective vote weight after trust-tier multiplier")
        public Integer effectiveVoteWeight;

        @Schema(description = "Block height of the active trust snapshot used for open-poll weighting")
        public Integer trustSnapshotHeight;

        @Schema(description = "Block timestamp of the active trust snapshot used for open-poll weighting")
        public Long trustSnapshotTimestamp;

        // For JAX-RS
        protected VoteDetail() {
        }

        public VoteDetail(String voterAddress, Integer optionIndex, Integer rawVoteWeight, String trustStatus,
                          Integer trustStatusValue, Integer trustWeightPercent, Integer effectiveVoteWeight) {
            this(voterAddress, optionIndex, rawVoteWeight, trustStatus, trustStatusValue, trustWeightPercent,
                    effectiveVoteWeight, null, null);
        }

        public VoteDetail(String voterAddress, Integer optionIndex, Integer rawVoteWeight, String trustStatus,
                          Integer trustStatusValue, Integer trustWeightPercent, Integer effectiveVoteWeight,
                          Integer trustSnapshotHeight, Long trustSnapshotTimestamp) {
            this.voterAddress = voterAddress;
            this.optionIndex = optionIndex;
            this.rawVoteWeight = rawVoteWeight;
            this.trustStatus = trustStatus;
            this.trustStatusValue = trustStatusValue;
            this.trustWeightPercent = trustWeightPercent;
            this.effectiveVoteWeight = effectiveVoteWeight;
            this.trustSnapshotHeight = trustSnapshotHeight;
            this.trustSnapshotTimestamp = trustSnapshotTimestamp;
        }
    }
}
