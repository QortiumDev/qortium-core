package org.qortium.crosschain;

import java.util.Arrays;

/** A JSON-RPC method name plus its parameters, chosen for one connection's negotiated protocol version. */
public final class ElectrumRequest {

	private final String method;
	private final Object[] params;

	public ElectrumRequest(String method, Object... params) {
		this.method = method;
		this.params = params == null ? new Object[0] : params;
	}

	public String getMethod() {
		return this.method;
	}

	public Object[] getParams() {
		return this.params.clone();
	}

	@Override
	public String toString() {
		return this.method + Arrays.toString(this.params);
	}
}
