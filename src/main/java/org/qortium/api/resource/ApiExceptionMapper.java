package org.qortium.api.resource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.persistence.exceptions.XMLMarshalException;
import org.glassfish.jersey.server.ParamException;
import org.qortium.settings.Settings;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import javax.xml.bind.JAXBException;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<RuntimeException> {

	private static final Logger LOGGER = LogManager.getLogger(ApiExceptionMapper.class);

	@Context
	private HttpServletRequest request;

	@Context
	private ServletContext servletContext;

	@Override
	public Response toResponse(RuntimeException e) {
		if (Settings.getInstance().isApiLoggingEnabled())
			LOGGER.info(String.format("Exception %s during API call: %s", e.getClass().getCanonicalName(), getRequestUri()));

		if (e instanceof ParamException) {
			ParamException pe = (ParamException) e;
			return Response.status(Response.Status.BAD_REQUEST).entity("Bad parameter \"" + pe.getParameterName() + "\"").build();
		}

		if ((e instanceof ProcessingException || e instanceof WebApplicationException) && isJaxbException(e))
			return Response.status(Response.Status.BAD_REQUEST).entity("Invalid request body").build();

		if (e instanceof WebApplicationException)
			return ((WebApplicationException) e).getResponse();

		return Response.serverError().build();
	}

	private String getRequestUri() {
		return this.request != null ? this.request.getRequestURI() : "unknown";
	}

	private static boolean isJaxbException(Throwable throwable) {
		for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
			if (cause instanceof JAXBException || cause instanceof XMLMarshalException)
				return true;
		}

		return false;
	}

}
