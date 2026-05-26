package org.qortium.data.voting;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

//All properties to be converted to JSON via JAX-RS
@XmlAccessorType(XmlAccessType.FIELD)
public class PollOptionData {

	// Properties
	@Schema(description = "One poll option name. Submit each option as its own array entry, not as a comma-separated list.")
	private String optionName;

	// Constructors

	// For JAX-RS
	protected PollOptionData() {
	}

	public PollOptionData(String optionName) {
		this.optionName = optionName;
	}

	// Getters/setters

	public String getOptionName() {
		return this.optionName;
	}

}
