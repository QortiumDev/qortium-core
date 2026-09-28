package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/**
 * JSON body of {@code POST /crosschain/arrr/send} (send protocol version 1).
 * <p>
 * Every field is a String on purpose: amounts are exact decimal text (never a JSON float that a
 * client library might round), and no field is interpreted by the JSON binding itself, so a
 * malformed value reaches the resource's validation and is rejected with a typed error rather
 * than a generic binding failure. {@code entropy58} is spend authority: it is never logged or
 * persisted.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainSendRequest {

	@Schema(description = "32 bytes of entropy, Base58 encoded (spend authority; never logged)",
			example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV", requiredMode = Schema.RequiredMode.REQUIRED)
	public String entropy58;

	@Schema(description = "Recipient's canonical lowercase Sapling address (zs1..., 78 characters). "
			+ "Ironwood (pirate1...), unified (u1...) and transparent (t1...) recipients are rejected.",
			example = "zs1...", requiredMode = Schema.RequiredMode.REQUIRED)
	public String receivingAddress;

	@Schema(description = "Amount of ARRR to send as plain decimal text with at most 8 fractional digits, "
			+ "e.g. \"1.00000001\" = 100000001 atomic units. Exponents, signs, zero and more than 8 decimals "
			+ "are rejected. A JSON number is accepted but a string is recommended.",
			example = "1.5", requiredMode = Schema.RequiredMode.REQUIRED)
	public String arrrAmount;

	@Schema(description = "Optional UTF-8 memo for the recipient: at most 512 encoded bytes, no control "
			+ "characters other than tab, carriage return and line feed, no lone surrogates. Never truncated.",
			nullable = true)
	public String memo;

	@Schema(description = "Client-generated idempotency key: a canonical lowercase UUID "
			+ "(8-4-4-4-12 hex). Keep the same key when retrying the same send.",
			example = "123e4567-e89b-12d3-a456-426614174000", requiredMode = Schema.RequiredMode.REQUIRED)
	public String idempotencyKey;

	@Schema(description = "Deprecated and unsupported: the fee is fixed (see GET /crosschain/arrr/sendcontract). "
			+ "Any non-null value is rejected.", deprecated = true, nullable = true)
	public String feePerByte;

	public PirateChainSendRequest() {
	}

}
