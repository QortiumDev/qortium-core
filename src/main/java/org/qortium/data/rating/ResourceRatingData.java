package org.qortium.data.rating;

import org.qortium.arbitrary.misc.Service;
import org.qortium.crypto.Crypto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ResourceRatingData {

	private Service service;
	private String nameKey;
	private String name;
	private String identifier;
	private String raterAddress;
	private byte[] raterPublicKey;
	private int rating;

	protected ResourceRatingData() {
	}

	public ResourceRatingData(Service service, String nameKey, String name, String identifier, byte[] raterPublicKey, int rating) {
		this(service, nameKey, name, identifier, Crypto.toAddress(raterPublicKey), raterPublicKey, rating);
	}

	public ResourceRatingData(Service service, String nameKey, String name, String identifier, String raterAddress,
			byte[] raterPublicKey, int rating) {
		this.service = service;
		this.nameKey = nameKey;
		this.name = name;
		this.identifier = identifier;
		this.raterAddress = raterAddress;
		this.raterPublicKey = raterPublicKey;
		this.rating = rating;
	}

	public Service getService() {
		return this.service;
	}

	public String getNameKey() {
		return this.nameKey;
	}

	public String getName() {
		return this.name;
	}

	public String getIdentifier() {
		return this.identifier;
	}

	public byte[] getRaterPublicKey() {
		return this.raterPublicKey;
	}

	public String getRaterAddress() {
		return this.raterAddress;
	}

	public int getRating() {
		return this.rating;
	}

}
