package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ChainParameterMetadata {

	@Schema(description = "numeric chain parameter ID")
	public int id;

	@Schema(description = "canonical chain parameter name")
	public String name;

	@Schema(description = "human-facing value type accepted by the public builder")
	public String valueType;

	@Schema(description = "canonical on-chain value length in bytes")
	public int valueLength;

	@Schema(description = "minimum number of blocks required between approval and activation")
	public int minimumActivationDelay;

	@Schema(description = "plain-language description of the parameter")
	public String description;

	@Schema(description = "API path for building a proposal transaction for this parameter")
	public String builderPath;

	@Schema(description = "API path for reading the effective value for this parameter")
	public String effectivePath;

	@Schema(description = "structured validation hints for building proposal forms")
	public ChainParameterValidationMetadata validation;

	public ChainParameterMetadata() {
	}

	public ChainParameterMetadata(int id, String name, String valueType, int valueLength, int minimumActivationDelay,
			String description, String builderPath, String effectivePath) {
		this(id, name, valueType, valueLength, minimumActivationDelay, description, builderPath, effectivePath, null);
	}

	public ChainParameterMetadata(int id, String name, String valueType, int valueLength, int minimumActivationDelay,
			String description, String builderPath, String effectivePath, ChainParameterValidationMetadata validation) {
		this.id = id;
		this.name = name;
		this.valueType = valueType;
		this.valueLength = valueLength;
		this.minimumActivationDelay = minimumActivationDelay;
		this.description = description;
		this.builderPath = builderPath;
		this.effectivePath = effectivePath;
		this.validation = validation;
	}

}
