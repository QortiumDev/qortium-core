package org.qortium.event;

@FunctionalInterface
public interface Listener {
	void listen(Event event);
}
