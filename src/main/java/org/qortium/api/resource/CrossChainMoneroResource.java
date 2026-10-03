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
}
