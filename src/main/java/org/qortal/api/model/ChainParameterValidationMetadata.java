package org.qortal.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ChainParameterValidationMetadata {

	@Schema(description = "minimum canonical long value accepted for long-like parameters, when applicable")
	public Long minimumLongValue;

	@Schema(description = "minimum integer value accepted for integer-like parameters, when applicable")
	public Integer minimumIntegerValue;

	@Schema(description = "maximum integer value accepted for integer-like parameters, when applicable")
	public Integer maximumIntegerValue;

	@Schema(description = "required integer-list item count, when applicable")
	public Integer integerListLength;

	@Schema(description = "minimum accepted value for each integer-list item, when applicable")
	public Integer minimumIntegerListValue;

	@Schema(description = "maximum accepted value for each integer-list item, when applicable")
	public Integer maximumIntegerListValue;

	@Schema(description = "ordered labels for integer-list items, when applicable")
	public String[] integerListLabels;

	@Schema(description = "whether an integer-list value must have a positive total")
	public boolean requiresPositiveTotal;

	@Schema(description = "whether the first integer-list item must be positive")
	public boolean requiresPositiveFirstValue;

	@Schema(description = "whether at least one integer-list item must be positive")
	public boolean requiresAnyPositiveValue;

	public ChainParameterValidationMetadata() {
	}

	public ChainParameterValidationMetadata(Long minimumLongValue, Integer minimumIntegerValue,
			Integer maximumIntegerValue, Integer integerListLength, Integer minimumIntegerListValue,
			Integer maximumIntegerListValue, String[] integerListLabels, boolean requiresPositiveTotal,
			boolean requiresPositiveFirstValue, boolean requiresAnyPositiveValue) {
		this.minimumLongValue = minimumLongValue;
		this.minimumIntegerValue = minimumIntegerValue;
		this.maximumIntegerValue = maximumIntegerValue;
		this.integerListLength = integerListLength;
		this.minimumIntegerListValue = minimumIntegerListValue;
		this.maximumIntegerListValue = maximumIntegerListValue;
		this.integerListLabels = integerListLabels;
		this.requiresPositiveTotal = requiresPositiveTotal;
		this.requiresPositiveFirstValue = requiresPositiveFirstValue;
		this.requiresAnyPositiveValue = requiresAnyPositiveValue;
	}

}
