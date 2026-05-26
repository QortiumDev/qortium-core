package org.qortium.data;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class PaymentData {

	// Properties

	private String recipient;

	private long assetId;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long amount;

	// Constructors

	// For JAXB
	protected PaymentData() {
	}

	public PaymentData(String recipient, long assetId, long amount) {
		this.recipient = recipient;
		this.assetId = assetId;
		this.amount = amount;
	}

	// Getters/setters

	public String getRecipient() {
		return this.recipient;
	}

	public long getAssetId() {
		return this.assetId;
	}

	public long getAmount() {
		return this.amount;
	}

}
