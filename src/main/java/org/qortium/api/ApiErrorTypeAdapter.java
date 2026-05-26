package org.qortium.api;

import javax.xml.bind.annotation.adapters.XmlAdapter;

public class ApiErrorTypeAdapter extends XmlAdapter<ApiErrorTypeAdapter.AdaptedApiError, ApiError> {

	public static class AdaptedApiError {
		public int code;
		public String description;
	}

	@Override
	public ApiError unmarshal(AdaptedApiError adaptedInput) throws Exception {
		if (adaptedInput == null)
			return null;

		return ApiError.fromCode(adaptedInput.code);
	}

	@Override
	public AdaptedApiError marshal(ApiError output) throws Exception {
		if (output == null)
			return null;

		AdaptedApiError adaptedOutput = new AdaptedApiError();
		adaptedOutput.code = output.getCode();
		adaptedOutput.description = output.name();

		return adaptedOutput;
	}

}
