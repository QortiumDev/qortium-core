package org.qortium.api.restricted.resource;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.api.Security;
import org.qortium.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortium.arbitrary.ArbitraryDataRenderer;
import org.qortium.arbitrary.ArbitraryDataResource;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.arbitrary.ArbitraryDataRenderManager;
import org.qortium.data.arbitrary.ArbitraryResourceStatus;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;


@Path("/render")
@Tag(name = "Render")
public class RenderResource {

    private static final Logger LOGGER = LogManager.getLogger(RenderResource.class);

    @Context HttpServletRequest request;
    @Context HttpServletResponse response;
    @Context ServletContext context;

    @POST
    @Path("/authorize/{resourceId}")
    @SecurityRequirement(name = "apiKey")
    public Response authorizeResource(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @PathParam("resourceId") String resourceId) {
        Security.checkApiCallAllowed(request, apiKey);
        ArbitraryDataResource resource = new ArbitraryDataResource(resourceId, null, null, null);
        ArbitraryDataRenderManager.getInstance().addToAuthorizedResources(resource);
        return authorizationSuccessResponse();
    }

    @POST
    @Path("authorize/{service}/{resourceId}")
    @SecurityRequirement(name = "apiKey")
    public Response authorizeResource(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
                                     @PathParam("service") Service service,
                                     @PathParam("resourceId") String resourceId) {
        Security.checkApiCallAllowed(request, apiKey);
        ArbitraryDataResource resource = new ArbitraryDataResource(resourceId, null, service, null);
        ArbitraryDataRenderManager.getInstance().addToAuthorizedResources(resource);
        return authorizationSuccessResponse();
    }

    @POST
    @Path("authorize/{service}/{resourceId}/{identifier}")
    @SecurityRequirement(name = "apiKey")
    public Response authorizeResource(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
                                     @PathParam("service") Service service,
                                     @PathParam("resourceId") String resourceId,
                                     @PathParam("identifier") String identifier) {
        Security.checkApiCallAllowed(request, apiKey);
        ArbitraryDataResource resource = new ArbitraryDataResource(resourceId, null, service, identifier);
        ArbitraryDataRenderManager.getInstance().addToAuthorizedResources(resource);
        return authorizationSuccessResponse();
    }

    static Response authorizationSuccessResponse() {
        return Response.ok("true", MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/signature/{signature}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getIndexBySignature(@PathParam("signature") String signature,
                                                   @QueryParam("theme") String theme,
                                                   @QueryParam("lang") String lang,
                                                   @QueryParam("textSize") String textSize,
                                                   @QueryParam("accent") String accent,
                                                   @QueryParam("uiStyle") String uiStyle) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, signature, Service.WEBSITE, null);

        return this.get(signature, ResourceIdType.SIGNATURE, null, null, "/", null, "/render/signature", true, true, theme, lang, textSize, accent, uiStyle);
    }

    @GET
    @Path("/signature/{signature}/{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPathBySignature(@PathParam("signature") String signature, @PathParam("path") String inPath,
                                                  @QueryParam("theme") String theme,
                                                  @QueryParam("lang") String lang,
                                                  @QueryParam("textSize") String textSize,
                                                  @QueryParam("accent") String accent,
                                                  @QueryParam("uiStyle") String uiStyle) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, signature, Service.WEBSITE, null);

        return this.get(signature, ResourceIdType.SIGNATURE, null, null, inPath,null, "/render/signature", true, true, theme, lang, textSize, accent, uiStyle);
    }

    @GET
    @Path("/hash/{hash}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getIndexByHash(@PathParam("hash") String hash58, @QueryParam("secret") String secret58,
                                              @QueryParam("theme") String theme,
                                              @QueryParam("lang") String lang,
                                              @QueryParam("textSize") String textSize,
                                              @QueryParam("accent") String accent,
                                              @QueryParam("uiStyle") String uiStyle) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, hash58, Service.WEBSITE, null);

        return this.get(hash58, ResourceIdType.FILE_HASH, Service.ARBITRARY_DATA, null, "/", secret58, "/render/hash", true, false, theme, lang, textSize, accent, uiStyle);
    }

    @GET
    @Path("/hash/{hash}/{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPathByHash(@PathParam("hash") String hash58, @PathParam("path") String inPath,
                                             @QueryParam("secret") String secret58,
                                             @QueryParam("theme") String theme,
                                             @QueryParam("lang") String lang,
                                             @QueryParam("textSize") String textSize,
                                             @QueryParam("accent") String accent,
                                             @QueryParam("uiStyle") String uiStyle) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, hash58, Service.WEBSITE, null);

        return this.get(hash58, ResourceIdType.FILE_HASH, Service.ARBITRARY_DATA, null, inPath, secret58, "/render/hash", true, false, theme, lang, textSize, accent, uiStyle);
    }

    @GET
    @Path("{service}/{name}/{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPathByName(@PathParam("service") Service service,
                                             @PathParam("name") String name,
                                             @PathParam("path") String inPath,
                                             @QueryParam("identifier") String identifier,
                                             @QueryParam("theme") String theme,
                                             @QueryParam("lang") String lang,
                                             @QueryParam("textSize") String textSize,
                                             @QueryParam("accent") String accent,
                                             @QueryParam("uiStyle") String uiStyle) {
        // If an explicit ?identifier= was supplied and the path also starts with that same identifier,
        // strip the duplicate segment. This preserves relative links resolved from Core's injected
        // <base href="/render/{service}/{name}/{identifier}/"> without looking for identifier/path.
        if (identifier != null && !identifier.isBlank()) {
            inPath = stripDuplicateIdentifierPathSegment(inPath, identifier);
        }

        // If no explicit ?identifier= was supplied, attempt to peel a non-default identifier from the
        // leading path segment (mirroring the gateway), so /render/{service}/{name}/{identifier}/{path}
        // works with a clean path-segment identifier. The ?identifier= query is honoured as-is for back-compat.
        if ((identifier == null || identifier.isBlank()) && inPath != null && !inPath.isEmpty()) {
            int slashIndex = inPath.indexOf('/');
            String candidate = slashIndex >= 0 ? inPath.substring(0, slashIndex) : inPath;
            String rest = slashIndex >= 0 ? inPath.substring(slashIndex + 1) : "";

            if (!candidate.isEmpty() && !candidate.equalsIgnoreCase("default")
                    && this.isRealIdentifier(service, name, candidate)) {
                identifier = candidate;
                inPath = rest;
            }
        }

        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, name, service, identifier);

        String prefix = String.format("/render/%s", service);
        return this.get(name, ResourceIdType.NAME, service, identifier, inPath, null, prefix, true, true, theme, lang, textSize, accent, uiStyle);
    }

    static String stripDuplicateIdentifierPathSegment(String inPath, String identifier) {
        if (inPath == null || inPath.isEmpty() || identifier == null || identifier.isBlank()) {
            return inPath;
        }

        String normalizedPath = inPath.startsWith("/") ? inPath.substring(1) : inPath;
        if (normalizedPath.equals(identifier)) {
            return "";
        }

        String duplicatedPrefix = identifier + "/";
        if (normalizedPath.startsWith(duplicatedPrefix)) {
            return normalizedPath.substring(duplicatedPrefix.length());
        }

        return inPath;
    }

    /**
     * Probe whether (service, name, candidate) is a real published resource, using the same mechanism
     * as {@link org.qortium.api.gateway.resource.GatewayResource}'s path parser (a status lookup with a
     * positive total chunk count). Used to disambiguate a path-segment identifier from a sub-path.
     */
    private boolean isRealIdentifier(Service service, String name, String candidate) {
        try (final Repository repository = RepositoryManager.getRepository()) {
            ArbitraryDataResource resource = new ArbitraryDataResource(name, ResourceIdType.NAME, service, candidate);
            ArbitraryResourceStatus status = resource.getStatus(repository);
            return status != null && status.getTotalChunkCount() != null && status.getTotalChunkCount() > 0;
        } catch (Exception e) {
            // On any lookup failure, treat the candidate as a sub-path rather than an identifier
            return false;
        }
    }

    @GET
    @Path("{service}/{name}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getIndexByName(@PathParam("service") Service service,
                                              @PathParam("name") String name,
                                              @QueryParam("identifier") String identifier,
                                              @QueryParam("theme") String theme,
                                              @QueryParam("lang") String lang,
                                              @QueryParam("textSize") String textSize,
                                              @QueryParam("accent") String accent,
                                              @QueryParam("uiStyle") String uiStyle) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, name, service, identifier);

        String prefix = String.format("/render/%s", service);
        return this.get(name, ResourceIdType.NAME, service, identifier, "/", null, prefix, true, true, theme, lang, textSize, accent, uiStyle);
    }



    private HttpServletResponse get(String resourceId, ResourceIdType resourceIdType, Service service, String identifier,
                                    String inPath, String secret58, String prefix, boolean includeResourceIdInPrefix, boolean async,
                                    String theme, String lang, String textSize, String accent, String uiStyle) {

        ArbitraryDataRenderer renderer = new ArbitraryDataRenderer(resourceId, resourceIdType, service, identifier, inPath,
                secret58, prefix, includeResourceIdInPrefix, async, "render", request, response, context);

        if (theme != null) {
            renderer.setTheme(theme);
        }
        if (lang != null) {
            renderer.setLang(lang);
        }
        if (textSize != null) {
            renderer.setTextSize(textSize);
        }
        if (accent != null) {
            renderer.setAccent(accent);
        }
        if (uiStyle != null) {
            renderer.setUiStyle(uiStyle);
        }
        return renderer.render();
    }

}
