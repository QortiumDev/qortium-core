package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** One directed rating edge in a trust-graph response: {@code source} rated {@code target}. */
@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustGraphEdgeData {

	private String source;
	private String target;
	private int rating;
	private int confidence;

	protected AccountTrustGraphEdgeData() {
	}

	public AccountTrustGraphEdgeData(String source, String target, int rating, int confidence) {
		this.source = source;
		this.target = target;
		this.rating = rating;
		this.confidence = confidence;
	}

	public String getSource() {
		return this.source;
	}

	public String getTarget() {
		return this.target;
	}

	public int getRating() {
		return this.rating;
	}

	public int getConfidence() {
		return this.confidence;
	}
}
