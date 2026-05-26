package org.qortium.api;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.qortium.settings.Settings;

public class ApiErrorHandler extends ErrorHandler {

	private static final Logger LOGGER = LogManager.getLogger(ApiErrorHandler.class);

	@Override
	public boolean handle(Request request, Response response, Callback callback) throws Exception {
		if (Settings.getInstance().isApiLoggingEnabled()) {
			String requestURI = request.getHttpURI().asString();

			Throwable th = (Throwable) request.getAttribute(ErrorHandler.ERROR_EXCEPTION);
			if (th != null) {
				LOGGER.error(String.format("Unexpected %s during request %s", th.getClass().getCanonicalName(), requestURI));
			} else {
				LOGGER.error(String.format("Unexpected error during request %s", requestURI));
			}
		}

		return super.handle(request, response, callback);
	}

}
