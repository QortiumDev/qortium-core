package org.qortium.api.gateway.resource;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.qortium.api.ApiError;
import org.qortium.api.ApiExceptionFactory;
import org.qortium.api.Security;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortium.arbitrary.ArbitraryDataReader;
import org.qortium.arbitrary.ArbitraryDataRenderer;
import org.qortium.arbitrary.ArbitraryDataResource;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.arbitrary.ArbitraryResourceData;
import org.qortium.data.arbitrary.ArbitraryResourceStatus;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;


@Path("/")
@Tag(name = "Gateway")
@PublicQdnResource
public class GatewayResource {

    @Context HttpServletRequest request;
    @Context HttpServletResponse response;
    @Context ServletContext context;

    /** Returns the matching {@link Service}, or null if this segment is not a service name. */
    private static Service parseService(String segment) {
        if (segment == null || segment.isEmpty()) {
            return null;
        }
        try {
            return Service.valueOf(segment.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Not a service
            return null;
        }
    }

    private ArbitraryResourceStatus getStatus(Service service, String name, String identifier, Boolean build) {

        // If "build=true" has been specified in the query string, build the resource before returning its status
        if (build != null && build) {
            try {
                ArbitraryDataReader reader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, null);
                if (!reader.isBuilding()) {
                    reader.loadSynchronously(false);
                }
            } catch (Exception e) {
                // No need to handle exception, as it will be reflected in the status
            }
        }

        try (final Repository repository = RepositoryManager.getRepository()) {
            ArbitraryDataResource resource = new ArbitraryDataResource(name, ResourceIdType.NAME, service, identifier);
            return resource.getStatus(repository);

        } catch (DataException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
        }
    }

    /**
     * When a bare {@code /{service}/{name}} request has no default resource, resolve it to the sole
     * non-default identifier published under that service/name — but only if it's unambiguous.
     * Mirrors the single-resource convenience Home/Explore already apply client-side. A default
     * resource always takes precedence over this fallback.
     *
     * @return the identifier to use, or null to leave the request resolving to the default resource
     * as before (either because the default exists, or because the match is ambiguous/absent).
     */
    /* package */ String resolveBareIdentifierIfUnambiguous(Service service, String name) {
        ArbitraryResourceStatus defaultStatus = this.getStatus(service, name, null, false);
        if (defaultStatus.getTotalChunkCount() > 0) {
            // Default resource exists - it always takes precedence, so don't override it.
            return null;
        }
        return this.resolveSingletonNonDefaultIdentifier(service, name);
    }

    /** @return the sole non-default identifier for service+name, or null if there isn't exactly one. */
    private String resolveSingletonNonDefaultIdentifier(Service service, String name) {
        try (final Repository repository = RepositoryManager.getRepository()) {
            List<ArbitraryResourceData> resources = repository.getArbitraryRepository().searchArbitraryResourcesSimple(
                    service, null, List.of(name), false,
                    null, null, 2, null, null, true);

            String singletonIdentifier = null;
            for (ArbitraryResourceData resourceData : resources) {
                String candidateIdentifier = resourceData.identifier;
                if (candidateIdentifier == null) {
                    // Default resource - irrelevant here, as callers only reach this method once
                    // they've already established no default resource exists.
                    continue;
                }
                if (singletonIdentifier != null && !singletonIdentifier.equals(candidateIdentifier)) {
                    // More than one distinct non-default identifier - ambiguous, never guess.
                    return null;
                }
                singletonIdentifier = candidateIdentifier;
            }
            return singletonIdentifier;

        } catch (DataException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
        }
    }


    /**
     * The browse root, equivalent to Home's {@code qdn://} address: an index of the QDN
     * service types, each linking to {@code /{SERVICE}}.
     */
    @GET
    public HttpServletResponse getRoot(@QueryParam("theme") String theme,
                                       @QueryParam("accent") String accent,
                                       @QueryParam("uiStyle") String uiStyle) {
        return ArbitraryDataRenderer.getBrowseResponse(response, null, theme, accent, uiStyle);
    }


    @GET
    @Path("{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPath(@PathParam("path") String inPath,
                                       @QueryParam("theme") String theme,
                                       @QueryParam("accent") String accent,
                                       @QueryParam("uiStyle") String uiStyle) {
        // Block requests from localhost, to prevent websites/apps from running javascript that fetches unvetted data
        Security.disallowLoopbackRequests(request);
        return this.parsePath(inPath, "gateway", null, true, true, theme, accent, uiStyle);
    }


    private HttpServletResponse parsePath(String inPath, String qdnContext, String secret58, boolean includeResourceIdInPrefix, boolean async,
                                          String theme, String accent, String uiStyle) {

        if (inPath == null || inPath.isEmpty()) {
            // Assume not a real file
            return ArbitraryDataRenderer.getResponse(response, 404, "Error 404: File Not Found");
        }

        // Default service is WEBSITE
        Service service = Service.WEBSITE;
        String name = null;
        String identifier = null;
        String outPath = "";
        List<String> prefixParts = new ArrayList<>();

        if (!inPath.contains("/")) {
            Service singleSegmentService = parseService(inPath);
            if (singleSegmentService != null) {
                // A bare service name is the service listing, equivalent to Home's qdn://{SERVICE}.
                // Note this takes precedence over a registered name that happens to collide with a
                // service name, which is the same precedence the multi-segment branch below applies.
                return ArbitraryDataRenderer.getBrowseResponse(response, singleSegmentService, theme, accent, uiStyle);
            }

            // Assume entire inPath is a registered name
            name = inPath;
        }
        else {
            // Parse the path to determine what we need to load
            List<String> parts = new LinkedList<>(Arrays.asList(inPath.split("/")));

            if (parts.isEmpty()) {
                // Nothing but separators (e.g. "//"); assume not a real file
                return ArbitraryDataRenderer.getResponse(response, 404, "Error 404: File Not Found");
            }

            // Check if the first element is a service
            Service parsedService = parseService(parts.get(0));
            if (parsedService != null) {
                // First element matches a service, so we can assume it is one
                service = parsedService;
                parts.remove(0);
                prefixParts.add(service.name());
            }

            if (parts.isEmpty()) {
                if (parsedService != null) {
                    // Only a service was supplied (e.g. "/APP/"), so show its resource listing
                    return ArbitraryDataRenderer.getBrowseResponse(response, service, theme, accent, uiStyle);
                }

                // We need more than just a service
                return ArbitraryDataRenderer.getResponse(response, 404, "Error 404: File Not Found");
            }

            // Service is removed, so assume first element is now a registered name
            name = parts.get(0);
            parts.remove(0);

            if (!parts.isEmpty()) {
                // Name is removed, so check if the first element is now an identifier
                ArbitraryResourceStatus status = this.getStatus(service, name, parts.get(0), false);
                if (status.getTotalChunkCount() > 0) {
                    // Matched service, name and identifier combination - so assume this is an identifier and can be removed.
                    // The identifier is NOT added to the prefix: the prefix precedes the resource id (the name) in the
                    // rendered base href, so HTMLParser appends the identifier after the name instead.
                    identifier = parts.get(0);
                    parts.remove(0);
                }
            }

            if (!parts.isEmpty()) {
                // outPath can be built by combining any remaining parts
                outPath = String.join("/", parts);
            }
        }

        if (identifier == null && outPath.isEmpty()) {
            // Bare "/{service}/{name}" (no further path segments to disambiguate) - resolve to the
            // sole non-default identifier when unambiguous. See resolveBareIdentifierIfUnambiguous().
            String resolvedIdentifier = this.resolveBareIdentifierIfUnambiguous(service, name);
            if (resolvedIdentifier != null) {
                identifier = resolvedIdentifier;
            }
        }

        String prefix = StringUtils.join(prefixParts, "/");
        if (prefix != null && !prefix.isEmpty()) {
            prefix = "/" + prefix;
        }

        ArbitraryDataRenderer renderer = new ArbitraryDataRenderer(name, ResourceIdType.NAME, service, identifier, outPath,
                secret58, prefix, includeResourceIdInPrefix, async, qdnContext, request, response, context);
        return renderer.render();
    }

}
