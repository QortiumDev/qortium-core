package org.qortium.api;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

public class ApiErrorRoot {

	private ApiError apiError;

	@XmlJavaTypeAdapter(ApiErrorTypeAdapter.class)
	@XmlElement(name = "error")
	public ApiError getApiError() {
		return this.apiError;
	}

	public void setApiError(ApiError apiError) {
		this.apiError = apiError;
	}

}
