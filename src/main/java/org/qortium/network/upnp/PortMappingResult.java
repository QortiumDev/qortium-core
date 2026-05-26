package org.qortium.network.upnp;

import java.net.InetAddress;
import java.util.Optional;

public class PortMappingResult {

	private static final PortMappingResult NOT_MAPPED = new PortMappingResult(false, null);

	private final boolean mapped;
	private final InetAddress externalAddress;

	private PortMappingResult(boolean mapped, InetAddress externalAddress) {
		this.mapped = mapped;
		this.externalAddress = externalAddress;
	}

	public static PortMappingResult mapped(InetAddress externalAddress) {
		return new PortMappingResult(true, externalAddress);
	}

	public static PortMappingResult notMapped() {
		return NOT_MAPPED;
	}

	public boolean isMapped() {
		return this.mapped;
	}

	public Optional<InetAddress> getExternalAddress() {
		return Optional.ofNullable(this.externalAddress);
	}
}
