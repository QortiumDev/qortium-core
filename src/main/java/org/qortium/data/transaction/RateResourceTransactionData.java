package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.arbitrary.misc.Service;
import org.qortium.crypto.Crypto;
import org.qortium.rating.ResourceRating;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
@XmlDiscriminatorValue("RATE_RESOURCE")
public class RateResourceTransactionData extends TransactionData {

	@Schema(description = "Rating creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] raterPublicKey;
	private int service;
	private String name;
	private String identifier;
	@Schema(description = "Resource rating: 1 through 10, or 0 to remove an existing rating")
	private int rating;

	@XmlTransient
	@Schema(hidden = true)
	private Integer previousRating;

	protected RateResourceTransactionData() {
		super(TransactionType.RATE_RESOURCE);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.raterPublicKey;
		this.identifier = ResourceRating.normalizeIdentifier(this.identifier);
	}

	public RateResourceTransactionData(BaseTransactionData baseTransactionData, int service, String name, String identifier,
			int rating, Integer previousRating) {
		super(TransactionType.RATE_RESOURCE, baseTransactionData);

		this.raterPublicKey = baseTransactionData.creatorPublicKey;
		this.service = service;
		this.name = name;
		this.identifier = ResourceRating.normalizeIdentifier(identifier);
		this.rating = rating;
		this.previousRating = previousRating;
	}

	public RateResourceTransactionData(BaseTransactionData baseTransactionData, int service, String name, String identifier, int rating) {
		this(baseTransactionData, service, name, identifier, rating, null);
	}

	public byte[] getRaterPublicKey() {
		return this.raterPublicKey;
	}

	@XmlElement(name = "raterAddress")
	@Schema(description = "Rating creator's address")
	protected String getRaterAddress() {
		return this.raterPublicKey == null ? null : Crypto.toAddress(this.raterPublicKey);
	}

	public int getServiceInt() {
		return this.service;
	}

	public Service getService() {
		return Service.valueOf(this.service);
	}

	public String getName() {
		return this.name;
	}

	public String getIdentifier() {
		return this.identifier;
	}

	public int getRating() {
		return this.rating;
	}

	public Integer getPreviousRating() {
		return this.previousRating;
	}

	public void setPreviousRating(Integer previousRating) {
		this.previousRating = previousRating;
	}

}
