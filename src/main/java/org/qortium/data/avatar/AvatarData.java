package org.qortium.data.avatar;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.arbitrary.misc.Service;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** Public descriptor for one exact explicitly authorized QDN avatar resource. */
@XmlAccessorType(XmlAccessType.FIELD)
public class AvatarData {

	@Schema(description = "Base58 signature of the authorized ARBITRARY transaction")
	private byte[] signature;
	private Service service;
	private String name;
	private String identifier;

	protected AvatarData() {
	}

	public AvatarData(byte[] signature, Service service, String name, String identifier) {
		this.signature = signature;
		this.service = service;
		this.name = name;
		this.identifier = identifier;
	}

	public byte[] getSignature() { return this.signature; }
	public Service getService() { return this.service; }
	public String getName() { return this.name; }
	public String getIdentifier() { return this.identifier; }
}
