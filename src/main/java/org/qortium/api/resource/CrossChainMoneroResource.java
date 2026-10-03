package org.qortium.api.resource;

import org.qortium.api.Security;
import org.qortium.crosschain.monero.*;
import org.qortium.settings.Settings;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.InputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Local-operator prototype. Home integration must retain custody/consent and never expose this to QDN. */
@Path("/crosschain/xmr")
@Tag(name = "Cross-Chain (Monero experimental)")
@Produces(MediaType.APPLICATION_JSON)
public class CrossChainMoneroResource {
    public static final String SESSION_HEADER = "X-XMR-SESSION";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    @Context HttpServletRequest request;

    private void authorize(String apiKey) {
        Security.checkApiCallAllowed(request, apiKey);
        Security.requireLoopbackRequest(request);
    }
    private Response result(Supplier<?> action) {
        // Explicit JSON bytes avoid MOXy's Map/record binding changing the protocol shape.
        try { return Response.ok(JSON.writeValueAsString(action.get()), MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").build(); }
        catch (MoneroWalletService.Rejected e) { return error(e.status, e.code); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { return error(500, "XMR_RESPONSE_FAILED"); }
    }
    private static Response error(int status, String code) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity("{\"code\":\"" + code + "\"}")
                .header("Cache-Control", "no-store").build();
    }

    @GET @Path("/capabilities")
    public Response capabilities(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
        authorize(apiKey);
        return result(() -> Map.of("protocolVersion", 1, "derivationVersion", 1, "decimals", 12,
                "enabled", Settings.getInstance().isMoneroWalletEnabled(), "platformSupported", MoneroNativeLoader.supported(),
                "network", "mainnet", "localCustodyOnly", true, "send", false, "historyLimit", 100));
    }

    @GET @Path("/session")
    public Response session(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
        authorize(apiKey);
        return result(() -> MoneroWalletRuntime.get().session());
    }

    @POST @Path("/activate") @Consumes(MediaType.APPLICATION_JSON)
    public Response activate(@HeaderParam(Security.API_KEY_HEADER) String apiKey, InputStream input) {
        authorize(apiKey);
        return result(() -> {
            MoneroWalletService service = MoneroWalletRuntime.get();
            try (var activation = MoneroActivationReader.read(input)) {
                return service.activate(activation.coinSeed(), activation.restoreHeight(), activation.expectedSession());
            } catch (IOException | IllegalArgumentException e) {
                // Never return parser diagnostics: they can quote the secret request body.
                throw new BadRequestException(error(400, "XMR_INVALID_ACTIVATION"));
            }
        });
    }

    @GET @Path("/wallet")
    public Response wallet(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @HeaderParam(SESSION_HEADER) String session) {
        authorize(apiKey);
        return result(() -> MoneroWalletRuntime.get().status(session));
    }

    @POST @Path("/deactivate")
    public Response deactivate(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @HeaderParam(SESSION_HEADER) String session) {
        authorize(apiKey);
        return result(() -> MoneroWalletRuntime.get().deactivate(session));
    }

    /** Send capability remains false until Home consent and Wallet integration are reviewed. */
    @POST @Path("/send/prepare") @Consumes(MediaType.APPLICATION_JSON)
    public Response prepareSend(@HeaderParam(Security.API_KEY_HEADER) String key,
                                @HeaderParam(SESSION_HEADER) String session, InputStream input) {
        return sendCommand(key, session, input, MoneroSendReader.Kind.PREPARE, "prepare");
    }
    @POST @Path("/send/commit") @Consumes(MediaType.APPLICATION_JSON)
    public Response commitSend(@HeaderParam(Security.API_KEY_HEADER) String key,
                               @HeaderParam(SESSION_HEADER) String session, InputStream input) {
        return sendCommand(key, session, input, MoneroSendReader.Kind.COMMIT, "commit");
    }
    @POST @Path("/send/cancel") @Consumes(MediaType.APPLICATION_JSON)
    public Response cancelSend(@HeaderParam(Security.API_KEY_HEADER) String key,
                               @HeaderParam(SESSION_HEADER) String session, InputStream input) {
        return sendCommand(key, session, input, MoneroSendReader.Kind.OPERATION, "cancel");
    }
    @POST @Path("/send/reconcile") @Consumes(MediaType.APPLICATION_JSON)
    public Response reconcileSend(@HeaderParam(Security.API_KEY_HEADER) String key,
                                  @HeaderParam(SESSION_HEADER) String session, InputStream input) {
        return sendCommand(key, session, input, MoneroSendReader.Kind.OPERATION, "reconcile");
    }
    @GET @Path("/send/status/{operationId}")
    public Response sendStatus(@HeaderParam(Security.API_KEY_HEADER) String key,
                               @HeaderParam(SESSION_HEADER) String session, @PathParam("operationId") String id) {
        authorize(key);
        return result(() -> {
            MoneroSendAccess access = new MoneroSendAccess(MoneroWalletRuntime.get());
            access.owner(session);
            try { MoneroSendAccess.validateId(id); }
            catch (IllegalArgumentException e) { throw new BadRequestException(error(400, "XMR_INVALID_SEND_REQUEST")); }
            return access.status(id, session);
        });
    }
    private Response sendCommand(String key, String session, InputStream input, MoneroSendReader.Kind kind, String command) {
        authorize(key);
        return result(() -> {
            MoneroSendAccess access = new MoneroSendAccess(MoneroWalletRuntime.get());
            access.owner(session); // stale/non-owner/disabled calls must not consume a body
            MoneroSendReader.Body body;
            try { body = MoneroSendReader.read(input, kind); }
            catch (IOException | IllegalArgumentException e) { throw new BadRequestException(error(400, "XMR_INVALID_SEND_REQUEST")); }
            if (command.equals("cancel")) return access.cancel(body.operationId(), session);
            CompletableFuture<?> work = switch (command) {
                case "prepare" -> access.prepare(body.operationId(), body.address(), body.amountAtomic(), session);
                case "commit" -> access.commit(body.operationId(), body.quoteDigest(), session);
                case "reconcile" -> access.reconcile(body.operationId(), session);
                default -> throw new IllegalStateException("Unknown XMR command");
            };
            try { work.get(2, TimeUnit.SECONDS); }
            catch (TimeoutException e) { return pending(access, body.operationId(), session); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return pending(access, body.operationId(), session); }
            catch (ExecutionException e) {
                if (e.getCause() instanceof MoneroWalletService.Rejected rejection) throw rejection;
                throw new WebApplicationException(error(503, "XMR_SEND_STATUS_REQUIRED"));
            }
            // Do not serve the future's snapshot: recheck ownership and read current durable state.
            return access.status(body.operationId(), session);
        });
    }
    private Object pending(MoneroSendAccess access, String id, String session) {
        access.owner(session);
        boolean durable = true;
        try { access.status(id, session); }
        catch (MoneroWalletService.Rejected e) {
            if (e.status != 409 || !e.code.equals("XMR_SEND_NOT_READY")) throw e;
            durable = false; // still queued/reconciling: no durable operation is promised
        }
        // Neither response cancels work or grants resubmission authority. Poll the same ID.
        String code = durable ? "XMR_SEND_PENDING" : "XMR_SEND_ADMISSION_PENDING";
        throw new WebApplicationException(Response.status(durable ? 202 : 503).type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .entity("{\"code\":\"" + code + "\",\"operationId\":\"" + id
                        + "\",\"durable\":" + durable + ",\"statusRequired\":true}").build());
    }
}
