package org.qortium.data.rating;

import org.qortium.arbitrary.misc.Service;

public class ResourceRatingData {

	private final Service service;
	private final String nameKey;
	private final String name;
	private final String identifier;
	private final byte[] raterPublicKey;
	private final int rating;

	public ResourceRatingData(Service service, String nameKey, String name, String identifier, byte[] raterPublicKey, int rating) {
		this.service = service;
		this.nameKey = nameKey;
		this.name = name;
		this.identifier = identifier;
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

	public int getRating() {
		return this.rating;
	}

}
