package org.qortium.network.upnp;

public final class PortMapperFactory {

	private static final PortMapper INSTANCE = JupnpPortMapper.getInstance();

	private PortMapperFactory() {
	}

	public static PortMapper getInstance() {
		return INSTANCE;
	}
}
