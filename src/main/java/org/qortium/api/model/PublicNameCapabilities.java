package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class PublicNameCapabilities {

	@Schema(description = "Public name builder protocol version")
	public final int protocolVersion;

	@Schema(description = "QDN bridge name actions supported by the public unsigned builders")
	public final List<String> actions;

	@Schema(description = "MemoryPoW difficulty required when using the zero-fee transaction alternative")
	public final int mempowFeeAlternativeDifficulty;

	protected PublicNameCapabilities() {
		this(0, List.of(), 0);
	}

	public PublicNameCapabilities(int protocolVersion, List<String> actions, int mempowFeeAlternativeDifficulty) {
		this.protocolVersion = protocolVersion;
		this.actions = List.copyOf(actions);
		this.mempowFeeAlternativeDifficulty = mempowFeeAlternativeDifficulty;
	}
}
