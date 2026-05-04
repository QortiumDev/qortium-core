package org.qortal.network.upnp;

public interface PortMapper extends AutoCloseable {

	PortMappingResult openTcpPort(int port, String description);

	void closeTcpPort(int port);

	@Override
	void close();
}
