package org.qortium.api;

import org.qortium.api.gateway.resource.PublicQdnResource;
import org.qortium.settings.Settings;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import java.lang.reflect.Method;

/**
 * Applies the public API allowlist to API resources exposed by the QDN gateway.
 * QDN-serving routes are identified from the resource selected by Jersey, not
 * from path guesses, because they share a URL space with the API resources.
 */
@Provider
public final class GatewayPublicApiAccessFilter implements ContainerRequestFilter {

	@Context
	private HttpServletRequest servletRequest;

	@Context
	private ResourceInfo resourceInfo;

	@Override
	public void filter(ContainerRequestContext requestContext) {
		if (isPublicQdnResource(this.resourceInfo))
			return;

		String passedApiKey = requestContext.getHeaderString(Security.API_KEY_HEADER);
		String path = requestContext.getUriInfo().getRequestUri().getRawPath();

		if (!PublicApiAccessHandler.isRequestAllowed(
				this.servletRequest.getRemoteAddr(),
				requestContext.getMethod(),
				path,
				passedApiKey,
				Settings.getInstance()))
			requestContext.abortWith(Response.status(Response.Status.FORBIDDEN).build());
	}

	private static boolean isPublicQdnResource(ResourceInfo resourceInfo) {
		Class<?> resourceClass = resourceInfo.getResourceClass();
		if (resourceClass != null && resourceClass.isAnnotationPresent(PublicQdnResource.class))
			return true;

		Method resourceMethod = resourceInfo.getResourceMethod();
		return resourceMethod != null && resourceMethod.isAnnotationPresent(PublicQdnResource.class);
	}
}
