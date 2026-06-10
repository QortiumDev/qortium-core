package org.qortium.api;

import org.qortium.globalization.Translator;

import javax.servlet.http.HttpServletRequest;

public enum ApiExceptionFactory {
	INSTANCE;

	public ApiException createException(HttpServletRequest request, ApiError apiError, Throwable throwable, Object... args) {
		// Full language tag (e.g. "zh-CN") rather than bare language (e.g. "zh"),
		// otherwise region-specific bundles like zh_CN/zh_TW can never be matched.
		String message = Translator.INSTANCE.translate("ApiError", request.getLocale().toLanguageTag(), apiError.name(), args);
		return new ApiException(apiError.getStatus(), apiError.getCode(), message, throwable);
	}

	public ApiException createException(HttpServletRequest request, ApiError apiError) {
		return createException(request, apiError, null);
	}

	public ApiException createCustomException(HttpServletRequest request, ApiError apiError, String message) {
		return new ApiException(apiError.getStatus(), apiError.getCode(), message, null);
	}

}
