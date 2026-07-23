package org.qortium.api.resource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.persistence.exceptions.EclipseLinkException;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.IOException;

/**
 * Turns malformed API request bodies into a plain 400 instead of a bare 500.
 * <p>
 * MOXy (EclipseLink JAXB) reports most binding failures with <i>unchecked</i> EclipseLink
 * exceptions that carry no JAXB cause, for example a {@code DescriptorException} when a
 * transaction body names a {@code "type"} that no {@code @XmlDiscriminatorValue} matches.
 * Those never reach {@link ApiExceptionMapper}'s request-body branch, so the caller used to
 * receive Jetty's bare HTML 500.
 * <p>
 * The fix deliberately lives here rather than in {@link ApiExceptionMapper}. Only unmarshalling
 * runs inside {@link ReaderInterceptorContext#proceed()}, so catching a type as broad as
 * {@link EclipseLinkException} at this one point cannot capture anything else. The exception
 * mapper has no such guarantee: {@code TransactionsResource.processTransaction} marshals its
 * API v2 reply <i>inside</i> the resource method, so an unchecked MOXy failure while writing
 * that reply arrives at the mapper looking exactly like a binding failure. Reporting
 * "Invalid request body" for a transaction that has already been accepted and broadcast would
 * be worse than the bug this class fixes.
 * <p>
 * A body that unmarshals cleanly can still be the wrong class: MOXy happily builds a sibling
 * subclass when the body's {@code "type"} names one, and Jersey then fails at reflective
 * dispatch without consulting any exception mapper at all. Comparing the unmarshalled entity
 * against the declared parameter type catches that case before dispatch.
 */
@Provider
public class ApiRequestBodyInterceptor implements ReaderInterceptor {

	private static final Logger LOGGER = LogManager.getLogger(ApiRequestBodyInterceptor.class);

	/** Must stay byte-identical to {@link ApiExceptionMapper}'s request-body response. */
	private static final String INVALID_REQUEST_BODY = "Invalid request body";

	@Override
	public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
		Object entity;

		// Only unmarshalling happens inside proceed(), so this catch cannot swallow a server fault.
		try {
			entity = context.proceed();
		} catch (EclipseLinkException e) {
			LOGGER.debug("Rejecting request body that MOXy could not bind", e);
			throw invalidRequestBody();
		}

		if (isWrongType(context.getType(), entity)) {
			LOGGER.debug("Rejecting request body that bound to {} instead of the declared {}",
					entity.getClass().getName(), context.getType().getName());
			throw invalidRequestBody();
		}

		return entity;
	}

	/**
	 * The {@code isPrimitive()} guard is load-bearing: {@link ReaderInterceptorContext#getType()}
	 * returns the raw declared class, so a primitive entity parameter reports {@code long.class}
	 * while the entity itself is the boxed {@code Long}, and {@code long.class.isInstance(Long)}
	 * is always false. Without the guard every such call would be rejected with a spurious 400.
	 */
	private static boolean isWrongType(Class<?> declaredType, Object entity) {
		return entity != null && declaredType != null && !declaredType.isPrimitive() && !declaredType.isInstance(entity);
	}

	private static BadRequestException invalidRequestBody() {
		// No cause is attached: ApiExceptionMapper returns this exact response untouched, whereas a
		// JAXB-flavoured cause would divert it into the mapper's own request-body branch instead.
		return new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
				.entity(INVALID_REQUEST_BODY)
				.type(MediaType.TEXT_PLAIN_TYPE)
				.build());
	}

}
