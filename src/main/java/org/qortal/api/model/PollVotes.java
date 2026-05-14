package org.qortal.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.data.voting.VoteOnPollData;

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

    @Schema(description = "Total weight of votes")
    public Integer totalWeight;

    @Schema(description = "Total raw blocksMinted weight before trust-tier multipliers")
    public Integer rawTotalWeight;

    @Schema(description = "List of vote counts for each option")
    public List<OptionCount> voteCounts;

    @Schema(description = "List of vote weights for each option")
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

        @Schema(description = "Vote weight")
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

        @Schema(description = "Stored account trust status name, retained for audit comparison")
        public String storedTrustStatus;

        @Schema(description = "Stored account trust status storage value, retained for audit comparison")
        public Integer storedTrustStatusValue;

        @Schema(description = "Stored account trust-tier vote multiplier percent, retained for audit comparison")
        public Integer storedTrustWeightPercent;

        @Schema(description = "Effective vote weight if stored account trust were used")
        public Integer storedEffectiveVoteWeight;

        @Schema(description = "Derived Subject trust status name from the stored trust snapshot")
        public String derivedTrustStatus;

        @Schema(description = "Derived Subject trust status storage value from the stored trust snapshot")
        public Integer derivedTrustStatusValue;

        @Schema(description = "Derived Subject trust-tier vote multiplier percent")
        public Integer derivedTrustWeightPercent;

        @Schema(description = "Effective vote weight if derived Subject trust were used")
        public Integer derivedEffectiveVoteWeight;

        @Schema(description = "Block height of the derived trust snapshot used for audit")
        public Integer derivedSnapshotHeight;

        @Schema(description = "Block timestamp of the derived trust snapshot used for audit")
        public Long derivedSnapshotTimestamp;

        // For JAX-RS
        protected VoteDetail() {
        }

        public VoteDetail(String voterAddress, Integer optionIndex, Integer rawVoteWeight, String trustStatus,
                          Integer trustStatusValue, Integer trustWeightPercent, Integer effectiveVoteWeight) {
            this(voterAddress, optionIndex, rawVoteWeight, trustStatus, trustStatusValue, trustWeightPercent,
                    effectiveVoteWeight, null, null, null, null, null, null, null, null, null, null);
        }

        public VoteDetail(String voterAddress, Integer optionIndex, Integer rawVoteWeight, String trustStatus,
                          Integer trustStatusValue, Integer trustWeightPercent, Integer effectiveVoteWeight,
                          String derivedTrustStatus, Integer derivedTrustStatusValue, Integer derivedTrustWeightPercent,
                          Integer derivedEffectiveVoteWeight, Integer derivedSnapshotHeight,
                          Long derivedSnapshotTimestamp) {
            this(voterAddress, optionIndex, rawVoteWeight, trustStatus, trustStatusValue, trustWeightPercent,
                    effectiveVoteWeight, null, null, null, null, derivedTrustStatus, derivedTrustStatusValue,
                    derivedTrustWeightPercent, derivedEffectiveVoteWeight, derivedSnapshotHeight, derivedSnapshotTimestamp);
        }

        public VoteDetail(String voterAddress, Integer optionIndex, Integer rawVoteWeight, String trustStatus,
                          Integer trustStatusValue, Integer trustWeightPercent, Integer effectiveVoteWeight,
                          String storedTrustStatus, Integer storedTrustStatusValue, Integer storedTrustWeightPercent,
                          Integer storedEffectiveVoteWeight, String derivedTrustStatus, Integer derivedTrustStatusValue,
                          Integer derivedTrustWeightPercent, Integer derivedEffectiveVoteWeight, Integer derivedSnapshotHeight,
                          Long derivedSnapshotTimestamp) {
            this.voterAddress = voterAddress;
            this.optionIndex = optionIndex;
            this.rawVoteWeight = rawVoteWeight;
            this.trustStatus = trustStatus;
            this.trustStatusValue = trustStatusValue;
            this.trustWeightPercent = trustWeightPercent;
            this.effectiveVoteWeight = effectiveVoteWeight;
            this.storedTrustStatus = storedTrustStatus;
            this.storedTrustStatusValue = storedTrustStatusValue;
            this.storedTrustWeightPercent = storedTrustWeightPercent;
            this.storedEffectiveVoteWeight = storedEffectiveVoteWeight;
            this.derivedTrustStatus = derivedTrustStatus;
            this.derivedTrustStatusValue = derivedTrustStatusValue;
            this.derivedTrustWeightPercent = derivedTrustWeightPercent;
            this.derivedEffectiveVoteWeight = derivedEffectiveVoteWeight;
            this.derivedSnapshotHeight = derivedSnapshotHeight;
            this.derivedSnapshotTimestamp = derivedSnapshotTimestamp;
        }
    }
}
