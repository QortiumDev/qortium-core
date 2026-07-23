package org.qortium.data.avatar;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.arbitrary.misc.Service;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** Pointer to the QDN resource an account or group has chosen as its avatar.
 *  It is a plain (service, name, identifier) reference resolved to the resource's
 *  latest revision when served — not pinned to a specific transaction, and not
 *  restricted to a resource published by the avatar's owner. */
@XmlAccessorType(XmlAccessType.FIELD)
public class AvatarData {

	@Schema(description = "QDN service of the target resource")
	private Service service;
	@Schema(description = "Registered name that owns the target resource")
	private String name;
	@Schema(description = "Identifier of the target resource (may be empty for the default resource)")
	private String identifier;

	protected AvatarData() {
	}

	public AvatarData(Service service, String name, String identifier) {
		this.service = service;
		this.name = name;
		this.identifier = identifier;
	}

	public Service getService() { return this.service; }
	public String getName() { return this.name; }
	public String getIdentifier() { return this.identifier; }
}
