package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountRatingCooldownUpdateRequest {

	@Schema(description = "timestamp when transaction created, in milliseconds since unix epoch", example = "__unix_epoch_time_milliseconds__")
	public long timestamp;

	@Schema(description = "development group ID that must approve this update", example = "1")
	public int txGroupId;

	@Schema(description = "update creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	public byte[] updaterPublicKey;

	@Schema(description = "block height from which the approved cooldown becomes active", example = "10000")
	public int activationHeight;

	@Schema(description = "number of blocks before the same rater can change or remove a rating for the same target and category edge", example = "1440")
	public int cooldownBlocks;

	@Schema(description = "transaction fee. If omitted, the builder uses the recommended fee", example = "0.0001", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public Long fee;

	@Schema(description = "optional MemoryPoW nonce used as an alternative to a paid fee")
	public Integer nonce;

	public AccountRatingCooldownUpdateRequest() {
	}

}
