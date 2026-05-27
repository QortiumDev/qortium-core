package org.qortium.test.api;

import org.junit.Test;
import org.qortium.api.resource.ApiExceptionMapper;
import org.qortium.test.common.ApiCommon;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.xml.bind.JAXBException;

import static org.junit.Assert.assertEquals;

public class ApiExceptionMapperTests extends ApiCommon {

	@Test
	public void testJaxbRequestBodyErrorsReturnBadRequest() {
		ApiExceptionMapper mapper = new ApiExceptionMapper();

		Response response = mapper.toResponse(new ProcessingException(new JAXBException("Bad request body")));

		assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
		assertEquals("Invalid request body", response.getEntity());
	}

	@Test
	public void testJaxbWebApplicationErrorsReturnBadRequest() {
		ApiExceptionMapper mapper = new ApiExceptionMapper();

		Response response = mapper.toResponse(new WebApplicationException(new JAXBException("Bad request body")));

		assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
		assertEquals("Invalid request body", response.getEntity());
	}

	@Test
	public void testGenericRuntimeErrorsRemainServerErrors() {
		ApiExceptionMapper mapper = new ApiExceptionMapper();

		Response response = mapper.toResponse(new RuntimeException("Unexpected failure"));

		assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
	}

}
